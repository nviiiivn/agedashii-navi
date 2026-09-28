#!/usr/bin/env python3
"""Copy a PMTiles vector archive, leaving out whole layers.

Why this exists: planetiler's --exclude_layers is read by ForwardingProfile's
config constructor, but the Protomaps Basemap constructor (Basemap.java:43 at
protomaps/basemaps 42ffaaa) calls the no-argument one, which hard-sets the
list empty (planetiler v0.10.2 ForwardingProfile.java). The flag is accepted
and ignored: measured, pm-z14 and pm-z14 --exclude_layers=pois,buildings,
landcover came out byte-identical at 59,115,734 B.

An MVT tile is a protobuf whose top-level field 3 is repeated once per layer,
so a layer is dropped by skipping its bytes - no feature is decoded or
re-encoded, and every kept layer is copied bit for bit.

The output is written the way planetiler writes it: tiles in TileID order
(clustered), identical tiles stored once, consecutive identical tiles
run-length encoded, gzip directories, and leaf directories only if the root
would pass the spec's 16,384-byte header+root limit.

    python3 strip_layers.py IN.pmtiles OUT.pmtiles --drop pois,buildings,landcover
"""
import argparse
import gzip
import hashlib
import json
import struct
import sys

from inspect_pmtiles import HEADER_LEN, open_archive

ROOT_LIMIT = 16384 - HEADER_LEN  # spec section 4: header + root <= 16 KiB


def _varint(buf, pos):
    v = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        v |= (b & 0x7F) << shift
        if not b & 0x80:
            return v, pos
        shift += 7


def _enc_varint(v, out):
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def layer_name(layer_bytes):
    pos = 0
    while pos < len(layer_bytes):
        key, pos = _varint(layer_bytes, pos)
        num, wt = key >> 3, key & 7
        if wt == 2:
            ln, pos = _varint(layer_bytes, pos)
            if num == 1:
                return layer_bytes[pos:pos + ln].decode()
            pos += ln
        elif wt == 0:
            _, pos = _varint(layer_bytes, pos)
        elif wt == 5:
            pos += 4
        elif wt == 1:
            pos += 8
        else:
            raise ValueError(f"wire type {wt} in layer")
    raise ValueError("layer without a name")


def strip_tile(data, drop):
    """(new tile bytes, names of layers dropped) - kept layers copied verbatim."""
    out, pos, dropped = bytearray(), 0, []
    while pos < len(data):
        start = pos
        key, pos = _varint(data, pos)
        num, wt = key >> 3, key & 7
        if wt != 2:
            raise ValueError(f"unexpected wire type {wt} at tile top level")
        ln, pos = _varint(data, pos)
        body = data[pos:pos + ln]
        pos += ln
        if num == 3 and layer_name(body) in drop:
            dropped.append(layer_name(body))
            continue
        out += data[start:pos]
    return bytes(out), dropped


def encode_directory(entries):
    """entries: [(tile_id, offset, length, run_length)] sorted by tile_id."""
    b = bytearray()
    _enc_varint(len(entries), b)
    last = 0
    for tid, _, _, _ in entries:
        _enc_varint(tid - last, b)
        last = tid
    for _, _, _, run in entries:
        _enc_varint(run, b)
    for _, _, ln, _ in entries:
        _enc_varint(ln, b)
    nxt = None
    for i, (_, off, ln, _) in enumerate(entries):
        _enc_varint(0 if i > 0 and off == nxt else off + 1, b)
        nxt = off + ln
    return gzip.compress(bytes(b), compresslevel=9, mtime=0)


def build_directories(entries):
    """(root bytes, leaves bytes) - leaves only when the root will not fit."""
    root = encode_directory(entries)
    if len(root) <= ROOT_LIMIT:
        return root, b""
    leaf_size = 4096
    while True:
        leaves, root_entries = bytearray(), []
        for i in range(0, len(entries), leaf_size):
            chunk = entries[i:i + leaf_size]
            enc = encode_directory(chunk)
            root_entries.append((chunk[0][0], len(leaves), len(enc), 0))
            leaves += enc
        root = encode_directory(root_entries)
        if len(root) <= ROOT_LIMIT:
            return root, bytes(leaves)
        leaf_size *= 2


def strip(src, dst, drop):
    with open(src, "rb") as f:
        raw_header = f.read(HEADER_LEN)
        f.seek(0)
        h, meta, entries, _ = open_archive(f)
        if h["tile_compression"] != "gzip" or h["tile_type"] != "mvt":
            sys.exit(f"{src}: expected gzip MVT, got {h['tile_compression']} {h['tile_type']}")
        entries.sort()
        blobs = {}        # sha256 of new gzip bytes -> (offset, length) in the new data section
        data = bytearray()
        out_entries = []  # (tile_id, offset, length, run_length)
        seen_drop = set()
        cache = {}        # old offset -> new (offset, length): a shared blob is stripped once
        for tid, off, ln, run in entries:
            if off not in cache:
                f.seek(h["tile_data_offset"] + off)
                tile, dropped = strip_tile(gzip.decompress(f.read(ln)), drop)
                seen_drop.update(dropped)
                z = gzip.compress(tile, compresslevel=9, mtime=0)
                key = hashlib.sha256(z).digest()
                if key not in blobs:
                    blobs[key] = (len(data), len(z))
                    data += z
                cache[off] = blobs[key]
            noff, nlen = cache[off]
            prev = out_entries[-1] if out_entries else None
            if prev and prev[1] == noff and prev[0] + prev[3] == tid:
                out_entries[-1] = (prev[0], noff, nlen, prev[3] + run)
            else:
                out_entries.append((tid, noff, nlen, run))

    meta = dict(meta)
    meta["vector_layers"] = [l for l in meta.get("vector_layers", []) if l["id"] not in drop]
    meta["agedashii:stripped_layers"] = sorted(drop)
    meta_z = gzip.compress(json.dumps(meta, separators=(",", ":")).encode(), compresslevel=9, mtime=0)
    root, leaves = build_directories(out_entries)

    root_off = HEADER_LEN
    meta_off = root_off + len(root)
    leaf_off = meta_off + len(meta_z)
    data_off = leaf_off + len(leaves)
    addressed = sum(e[3] for e in out_entries)
    header = bytearray(raw_header)
    struct.pack_into("<11Q", header, 8, root_off, len(root), meta_off, len(meta_z), leaf_off, len(leaves),
                     data_off, len(data), addressed, len(out_entries), len(blobs))
    header[96] = 1  # clustered: written in TileID order
    with open(dst, "wb") as o:
        o.write(header)
        o.write(root)
        o.write(meta_z)
        o.write(leaves)
        o.write(data)
    return {"dropped_layers_found": sorted(seen_drop), "addressed": addressed,
            "entries": len(out_entries), "blobs": len(blobs), "bytes": data_off + len(data),
            "leaf_dirs": bool(leaves)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--drop", required=True, help="comma-separated layer names")
    a = ap.parse_args()
    drop = {x.strip() for x in a.drop.split(",") if x.strip()}
    r = strip(a.src, a.dst, drop)
    print(json.dumps(r))
    missing = drop - set(r["dropped_layers_found"])
    if missing:
        print(f"note: never saw layers {sorted(missing)} in {a.src}", file=sys.stderr)


if __name__ == "__main__":
    main()
