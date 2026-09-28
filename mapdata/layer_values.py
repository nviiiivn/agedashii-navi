#!/usr/bin/env python3
"""Which layers, fields and values a PMTiles vector archive actually carries.

A style filter that names a class the tiles never contain draws nothing and
raises no error, so the style generator's schema adapters are checked against
this rather than against documentation.

    python3 layer_values.py archive.pmtiles [--zoom 14] [--sample 400] [--json]

Decodes Mapbox Vector Tile protobufs with the standard library only (layer=3,
feature=2, keys=3, values=4; feature tags=2). Prints, per layer, the feature
count in the sample and the distinct values of every string field with at most
40 distinct values - the enum-like ones a filter would test.
"""
import gzip
import json
import struct
import sys
from collections import Counter, defaultdict

from inspect_pmtiles import open_archive, zoom_of

MAX_ENUM = 40


def _varint(buf, pos):
    v = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        v |= (b & 0x7F) << shift
        if not b & 0x80:
            return v, pos
        shift += 7


def fields(buf):
    """Yield (field_number, value) over one protobuf message."""
    pos, n = 0, len(buf)
    while pos < n:
        key, pos = _varint(buf, pos)
        num, wt = key >> 3, key & 7
        if wt == 0:
            v, pos = _varint(buf, pos)
        elif wt == 2:
            ln, pos = _varint(buf, pos)
            v, pos = buf[pos:pos + ln], pos + ln
        elif wt == 5:
            v, pos = buf[pos:pos + 4], pos + 4
        elif wt == 1:
            v, pos = buf[pos:pos + 8], pos + 8
        else:
            raise ValueError(f"wire type {wt}")
        yield num, v


def packed(buf):
    out, pos = [], 0
    while pos < len(buf):
        v, pos = _varint(buf, pos)
        out.append(v)
    return out


def value(buf):
    for num, v in fields(buf):
        if num == 1:
            return v.decode("utf-8", "replace")
        if num == 2:
            return struct.unpack("<f", v)[0]
        if num == 3:
            return struct.unpack("<d", v)[0]
        if num in (4, 5):
            return v
        if num == 6:
            return (v >> 1) ^ -(v & 1)
        if num == 7:
            return bool(v)
    return None


def decode_tile(data):
    """{layer name: [properties dict per feature]}"""
    layers = {}
    for num, lbuf in fields(data):
        if num != 3:
            continue
        name, keys, vals, feats = None, [], [], []
        for n2, v in fields(lbuf):
            if n2 == 1:
                name = v.decode()
            elif n2 == 2:
                feats.append(v)
            elif n2 == 3:
                keys.append(v.decode())
            elif n2 == 4:
                vals.append(value(v))
        props = []
        for fbuf in feats:
            tags = []
            for n3, v in fields(fbuf):
                if n3 == 2:
                    tags = packed(v)
            props.append({keys[tags[i]]: vals[tags[i + 1]] for i in range(0, len(tags), 2)})
        layers.setdefault(name, []).extend(props)
    return layers


def survey(path, zoom, sample):
    with open(path, "rb") as f:
        h, _, entries, _ = open_archive(f)
        chosen = [e for e in entries if zoom_of(e[0]) == zoom]
        step = max(1, len(chosen) // sample)
        chosen = chosen[::step][:sample]
        counts = Counter()
        seen = defaultdict(lambda: defaultdict(Counter))
        for _, off, length, _ in chosen:
            f.seek(h["tile_data_offset"] + off)
            raw = f.read(length)
            if h["tile_compression"] == "gzip":
                raw = gzip.decompress(raw)
            for layer, feats in decode_tile(raw).items():
                counts[layer] += len(feats)
                for p in feats:
                    for k, v in p.items():
                        seen[layer][k][v if isinstance(v, (str, bool)) else type(v).__name__] += 1
    out = {}
    for layer in sorted(counts):
        fl = {}
        for k, c in sorted(seen[layer].items()):
            fl[k] = dict(c.most_common()) if len(c) <= MAX_ENUM else f"{len(c)} distinct"
        out[layer] = {"features": counts[layer], "fields": fl}
    return {"file": path, "zoom": zoom, "tiles_sampled": len(chosen), "layers": out}


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    zoom = int(args[args.index("--zoom") + 1]) if "--zoom" in args else 14
    sample = int(args[args.index("--sample") + 1]) if "--sample" in args else 400
    r = survey(args[0], zoom, sample)
    if "--json" in args:
        print(json.dumps(r, indent=1, ensure_ascii=False))
        return
    print(f"{r['file']}  z{r['zoom']}  {r['tiles_sampled']} tiles sampled")
    for layer, d in r["layers"].items():
        print(f"\n[{layer}] {d['features']} features")
        for k, v in d["fields"].items():
            if isinstance(v, dict):
                v = ", ".join(f"{a}:{b}" for a, b in v.items())
            print(f"    {k}: {v}")


if __name__ == "__main__":
    main()
