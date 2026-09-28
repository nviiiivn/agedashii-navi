#!/usr/bin/env python3
"""Report what a PMTiles v3 archive really holds, read from its directories.

The header's min/max zoom and bounds are whatever the writer claimed. The Flet
app went blank above z15 because a layer trusted a zoom range that the tiles did
not have, so this walks every directory entry and derives the zoom range, the
tile-grid bounds and the per-zoom counts from the TileIDs themselves, then says
whether the header agrees.

Standard library only, so it runs on the Pi, on Tower and in CI.

    python3 inspect_pmtiles.py bayarea.pmtiles          # human summary
    python3 inspect_pmtiles.py bayarea.pmtiles --json   # for scripts

Layout follows protomaps/PMTiles spec/v3/spec.md: 127-byte header (section 3),
varint-encoded directories (section 4.2), TileIDs on cumulative Hilbert curves
(section 4.1).
"""
import gzip
import json
import math
import struct
import sys
from collections import Counter

HEADER_LEN = 127
COMPRESSION = {0: "unknown", 1: "none", 2: "gzip", 3: "brotli", 4: "zstd"}
TILE_TYPE = {0: "unknown", 1: "mvt", 2: "png", 3: "jpeg", 4: "webp",
             5: "avif", 6: "mlt"}


def read_header(buf):
    if buf[:7] != b"PMTiles":
        raise ValueError("not a PMTiles archive (bad magic)")
    if buf[7] != 3:
        raise ValueError(f"PMTiles version {buf[7]}, only v3 is understood")
    (root_off, root_len, meta_off, meta_len, leaf_off, leaf_len, data_off,
     data_len, n_addressed, n_entries, n_contents) = struct.unpack_from("<11Q", buf, 8)
    clustered, ic, tc, tt, minz, maxz = struct.unpack_from("<6B", buf, 96)
    min_lon, min_lat, max_lon, max_lat = struct.unpack_from("<4i", buf, 102)
    cz, c_lon, c_lat = struct.unpack_from("<B2i", buf, 118)
    return {
        "root_offset": root_off, "root_length": root_len,
        "metadata_offset": meta_off, "metadata_length": meta_len,
        "leaf_offset": leaf_off, "leaf_length": leaf_len,
        "tile_data_offset": data_off, "tile_data_length": data_len,
        "addressed_tiles": n_addressed, "tile_entries": n_entries,
        "tile_contents": n_contents, "clustered": bool(clustered),
        "internal_compression": COMPRESSION.get(ic, ic),
        "tile_compression": COMPRESSION.get(tc, tc),
        "tile_type": TILE_TYPE.get(tt, tt),
        "min_zoom": minz, "max_zoom": maxz,
        "bounds": [min_lon / 1e7, min_lat / 1e7, max_lon / 1e7, max_lat / 1e7],
        "center": [c_lon / 1e7, c_lat / 1e7, cz],
    }


def decompress(data, how):
    if how == "gzip":
        return gzip.decompress(data)
    if how == "none":
        return data
    raise ValueError(f"internal compression {how} not supported here")


def varints(buf):
    pos = 0
    while pos < len(buf):
        shift = value = 0
        while True:
            b = buf[pos]
            pos += 1
            value |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        yield value


def decode_directory(buf):
    it = varints(buf)
    n = next(it)
    ids, last = [], 0
    for _ in range(n):
        last += next(it)
        ids.append(last)
    runs = [next(it) for _ in range(n)]
    lengths = [next(it) for _ in range(n)]
    offsets = []
    for i in range(n):
        v = next(it)
        offsets.append(offsets[i - 1] + lengths[i - 1] if v == 0 and i > 0 else v - 1)
    return list(zip(ids, offsets, lengths, runs))


def zoom_of(tile_id):
    """Zoom z holds IDs from (4**z - 1) / 3 up to (4**(z+1) - 1) / 3 - 1."""
    z = 0
    while (4 ** (z + 1) - 1) // 3 <= tile_id:
        z += 1
    return z


def id_to_zxy(tile_id):
    z = zoom_of(tile_id)
    d = tile_id - (4 ** z - 1) // 3
    x = y = 0
    s = 1
    n = 1 << z
    while s < n:  # Hilbert d -> (x, y), the curve spec 4.1 tabulates
        rx = 1 & (d // 2)
        ry = 1 & (d ^ rx)
        if ry == 0:
            if rx == 1:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
        x += s * rx
        y += s * ry
        d //= 4
        s *= 2
    return z, x, y


def tile_lon(x, z):
    return x / (1 << z) * 360.0 - 180.0


def tile_lat(y, z):
    return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / (1 << z)))))


def open_archive(f):
    """(header, metadata dict, tile entries, leaf directory count).

    Tile entries are (tile_id, offset into the tile data section, length,
    run_length), leaf directories already followed.
    """
    h = read_header(f.read(HEADER_LEN))
    f.seek(h["metadata_offset"])
    meta = json.loads(decompress(f.read(h["metadata_length"]), h["internal_compression"]))

    def load(offset, length):
        f.seek(offset)
        return decode_directory(decompress(f.read(length), h["internal_compression"]))

    tiles, leaves = [], 0
    pending = [(h["root_offset"], h["root_length"])]
    while pending:
        off, ln = pending.pop()
        for tid, o, length, run in load(off, ln):
            if run == 0:
                leaves += 1
                pending.append((h["leaf_offset"] + o, length))
            else:
                tiles.append((tid, o, length, run))
    return h, meta, tiles, leaves


def inspect(path):
    with open(path, "rb") as f:
        h, meta, entries, leaves = open_archive(f)
    tiles = [(tid, run, length) for tid, _, length, run in entries]

    per_zoom = Counter()
    bytes_zoom = Counter()
    grid = {}  # z -> [minx, miny, maxx, maxy]
    for tid, run, length in tiles:
        for t in range(tid, tid + run):
            z, x, y = id_to_zxy(t)
            per_zoom[z] += 1
            bytes_zoom[z] += length
            g = grid.setdefault(z, [x, y, x, y])
            g[0], g[1] = min(g[0], x), min(g[1], y)
            g[2], g[3] = max(g[2], x), max(g[3], y)

    zooms = sorted(per_zoom)
    zmax = zooms[-1]
    gx0, gy0, gx1, gy1 = grid[zmax]
    real_bounds = [round(tile_lon(gx0, zmax), 5), round(tile_lat(gy1 + 1, zmax), 5),
                   round(tile_lon(gx1 + 1, zmax), 5), round(tile_lat(gy0, zmax), 5)]
    addressed = sum(per_zoom.values())
    problems = []
    if (zooms[0], zmax) != (h["min_zoom"], h["max_zoom"]):
        problems.append(f"header zoom {h['min_zoom']}-{h['max_zoom']} but tiles hold {zooms[0]}-{zmax}")
    if h["addressed_tiles"] and h["addressed_tiles"] != addressed:
        problems.append(f"header says {h['addressed_tiles']} addressed tiles, directories hold {addressed}")
    missing = [z for z in range(zooms[0], zmax + 1) if z not in per_zoom]
    if missing:
        problems.append(f"no tiles at zoom {missing}")
    return {
        "file": path,
        "header": h,
        "real": {
            "min_zoom": zooms[0], "max_zoom": zmax,
            "tile_grid_bounds_at_max_zoom": real_bounds,
            "addressed_tiles": addressed, "leaf_directories": leaves,
            "tiles_per_zoom": {z: per_zoom[z] for z in zooms},
            "stored_bytes_per_zoom_before_dedup": {z: bytes_zoom[z] for z in zooms},
        },
        "metadata": {k: meta.get(k) for k in ("name", "attribution", "version",
                                              "description", "planetiler:version")
                     if k in meta},
        "vector_layers": sorted(l["id"] for l in meta.get("vector_layers", [])),
        "problems": problems,
    }


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    r = inspect(sys.argv[1])
    if "--json" in sys.argv:
        print(json.dumps(r, indent=2))
        return
    h, real = r["header"], r["real"]
    print(r["file"])
    print(f"  type {h['tile_type']}, tiles {h['tile_compression']}, "
          f"clustered {h['clustered']}, {h['tile_contents']} unique blobs")
    print(f"  REAL zoom {real['min_zoom']}-{real['max_zoom']} "
          f"(header {h['min_zoom']}-{h['max_zoom']})")
    print(f"  header bounds {h['bounds']}")
    print(f"  tile grid at z{real['max_zoom']} covers {real['tile_grid_bounds_at_max_zoom']}")
    print(f"  addressed tiles {real['addressed_tiles']}, leaf dirs {real['leaf_directories']}")
    print("  per zoom: " + "  ".join(f"z{z}:{n}" for z, n in real["tiles_per_zoom"].items()))
    print(f"  layers: {', '.join(r['vector_layers'])}")
    for k, v in r["metadata"].items():
        print(f"  {k}: {v}")
    print("  PROBLEMS: " + ("; ".join(r["problems"]) if r["problems"] else "none"))


if __name__ == "__main__":
    main()
