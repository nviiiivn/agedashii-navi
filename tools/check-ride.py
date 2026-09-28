#!/usr/bin/env python3
"""Check one recorded ride: the JSONL log and the CSV/GPX/JSON beside it.

    tools/check-ride.py ride-20260927-214904.jsonl [--minutes 30]

Prints what the log holds and exits non-zero if the ride is not whole:
no footer, exports that disagree with the log, a sensor channel that never
reached disk (the Flet app's failure), or a ride shorter than --minutes.
Standard library only, so it runs on the Pi, Tower or a laptop.

WHAT A PASS ON THE EMULATOR DOES NOT MEAN. Tower's emulator ignores
`adb emu geo nmea` (verified 2026-09-27: positions never moved), and
`geo fix` reports speed 0 and bearing 0. So lean_deg and g_lat - the two
channels that make this motorcycle telemetry rather than a GPS logger - are
derived from nothing in any emulator ride. A PASS there proves the ride was
RECORDED; it says nothing about whether those channels are right. Only a ride
on her phone exercises them, and this checker does not judge their values.
"""
import argparse
import collections
import json
import pathlib
import sys

SENSOR_CHANNELS = ["g_vert", "g_peak", "g_bump", "tilt_deg", "pitch_deg",
                   "yaw_gyro_dps", "gyro_peak", "pressure_hpa", "alt_baro_m",
                   "climb_mps", "compass_deg", "battery_pct"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("log")
    ap.add_argument("--minutes", type=float, default=0.0)
    args = ap.parse_args()
    path = pathlib.Path(args.log)
    lines, torn = [], 0
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip():
            continue
        try:
            lines.append(json.loads(raw))
        except ValueError:
            torn += 1
    kinds = collections.Counter(l.get("kind", "sample") for l in lines)
    rows = [l for l in lines if l.get("kind") is None and "lat" in l]
    header = next((l for l in lines if l.get("kind") == "header"), {})
    footer = next((l for l in reversed(lines) if l.get("kind") == "footer"), None)
    problems = []

    print(f"ride   {header.get('name')}  ({path.name})")
    print(f"lines  {dict(kinds)}" + (f", {torn} unreadable" if torn else ""))
    if not rows:
        print("FAIL: no samples")
        return 1
    ts = [r["t"] for r in rows]
    span = ts[-1] - ts[0]
    gaps = [b - a for a, b in zip(ts, ts[1:])]
    print(f"span   {span / 60:.1f} min, {len(rows)} rows, "
          f"max gap {max(gaps, default=0):.1f} s, gaps > 3 s: {sum(g > 3 for g in gaps)}")
    print(f"dist   {rows[-1].get('dist_m')} m")
    for k in SENSOR_CHANNELS:
        n = sum(1 for r in rows if r.get(k) is not None)
        print(f"  {k:14s} {n:6d}/{len(rows)}")
    present = [k for k in SENSOR_CHANNELS if any(r.get(k) is not None for r in rows)]
    if not present:
        problems.append("no sensor channel reached the log")
    for k in present:
        n = sum(1 for r in rows if r.get(k) is not None)
        # The first row of a ride may precede the first sensor reading.
        if n < len(rows) - 1:
            problems.append(f"{k} missing from {len(rows) - n} rows")
    if footer is None:
        problems.append("no footer: the ride was not stopped cleanly")
    elif footer.get("samples") != len(rows):
        problems.append(f"footer says {footer.get('samples')} samples, log has {len(rows)}")
    if args.minutes and span < args.minutes * 60 - 60:
        problems.append(f"only {span / 60:.1f} min recorded, wanted {args.minutes}")

    base = path.with_suffix("")
    csv, gpx, js = (base.with_suffix(s) for s in (".csv", ".gpx", ".json"))
    if all(p.exists() for p in (csv, gpx, js)):
        csv_rows = len(csv.read_text(encoding="utf-8").splitlines()) - 1
        trkpt = gpx.read_text(encoding="utf-8").count("<trkpt")
        print(f"export csv {csv_rows} rows, gpx {trkpt} points, json {js.stat().st_size} bytes")
        if csv_rows != len(rows) or trkpt != len(rows):
            problems.append(f"exports disagree with the log ({csv_rows} csv, {trkpt} gpx, {len(rows)} log)")
    else:
        problems.append("exports missing (CSV/GPX/JSON are written when the ride stops)")

    for p in problems:
        print("FAIL:", p)
    print("PASS" if not problems else f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
