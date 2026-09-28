#!/usr/bin/env python3
"""Put her map data where the APK build picks it up (-PnaviData=OUT).

    make-datapack.py OUT --map bayarea.pmtiles --tiles bayarea-valhalla-3.6.3.tar --places places.sqlite

Writes OUT/data/ with the three files under the names the app looks for, and
OUT/data/MANIFEST: one line per file (name, size, sha256) and a first line,
"version <hash>", that changes whenever any file does. The app unpacks the
files on first start, and again only when the version in a new APK differs -
so an update with the same map does not copy 217 MB for nothing.
"""
import argparse
import hashlib
import os
import shutil
import sys

NAMES = {"map": "bayarea.pmtiles", "tiles": "bayarea-valhalla-3.6.3.tar", "places": "places.sqlite"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    for key in NAMES:
        ap.add_argument(f"--{key}", required=True)
    args = ap.parse_args()
    data = os.path.join(args.out, "data")
    os.makedirs(data, exist_ok=True)
    lines = []
    for key, name in NAMES.items():
        src = getattr(args, key)
        if not os.path.isfile(src):
            sys.exit(f"missing {key}: {src}")
        dst = os.path.join(data, name)
        shutil.copyfile(src, dst)
        lines.append(f"{name}\t{os.path.getsize(dst)}\t{sha256(dst)}")
    version = hashlib.sha256("\n".join(lines).encode()).hexdigest()[:16]
    with open(os.path.join(data, "MANIFEST"), "w") as f:
        f.write(f"version {version}\n" + "\n".join(lines) + "\n")
    total = sum(int(line.split("\t")[1]) for line in lines)
    print(f"{data}: version {version}, {total / 1e6:.0f} MB")


if __name__ == "__main__":
    main()
