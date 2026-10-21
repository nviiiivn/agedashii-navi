"""Golden outputs from the Python ride recorder, for Kotlin's Ride to match.

    PYTHONPATH=<NVII-DASH>/apps/navi:<NVII-DASH>/packages/agedashii-washi \
        /home/nvii/projects/agedashii/venv/bin/python tools/golden_telemetry.py

Writes core/src/jvmTest/resources/golden/telemetry.json. Each scenario is a list
of operations on one telemetry.Ride, replayed exactly by the Kotlin test, and
everything the Ride produced: the row each call returned, the in-memory
window, the summary, the CSV, GPX and JSON exports, the JSONL file byte for
byte, and what read_log, load_ride and header_name make of that file. The log's
directory differs between the two runs, so it is written as <PATH>.
Built against telemetry.py md5 f3a7163e (settled 2026-09-27 20:23).
"""
import hashlib
import json
import os
import shutil
import tempfile

from agedashii_navi import telemetry

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "core", "src", "test", "resources", "golden",
                   "telemetry.json")
SCRATCH = "/home/nvii/Sandbox/tmp"

MACHINE = {"machine": "cb750", "machine_name": "Nighthawk CB750",
           "cylinders": 4, "strokes": 4, "firing_per_rev": 2.0,
           "redline_rpm": 9500}

SHORT = [
    ["start", {"when": 1000.0}],
    ["add_fix", {"lat": 37.8, "lon": -122.27, "t": 1000.0, "speed_ms": 0.0,
                 "heading": None, "alt_m": 10.0, "acc_m": 4.0}],
    ["feed_extra", {"values": {"g_vert": 1.02, "g_peak": 1.3, "tilt_deg": -3.1,
                               "rpm_est": 1100, "battery_pct": 81,
                               "exhaust_hz": 36.67}}],
    # ~1.1 m north: GPS jitter, no distance.
    ["add_fix", {"lat": 37.80001, "lon": -122.27, "t": 1001.0, "speed_ms": 1.0,
                 "heading": 359.0, "alt_m": 10.4, "acc_m": 4.0}],
    # 359 -> 1 across north: a two-degree turn, not 358.
    ["add_fix", {"lat": 37.8003, "lon": -122.2699, "t": 1002.0,
                 "speed_ms": 6.0, "heading": 1.0, "alt_m": 12.5, "acc_m": 4.0}],
    ["feed_extra", {"values": {"climb_mps": 1.25, "pressure_hpa": 1012.8,
                               "alt_baro_m": 3.55, "bad key": 1, "odd<tag": 2,
                               "fine_one": "a<b\x02", "a_list": [1, 2],
                               "x²": 3, "ª": 4, "none_one": None,
                               "g_bump": 0.42, "flag": True}}],
    ["add_fix", {"lat": 37.8003, "lon": -122.2699, "t": 1002.0}],
    ["add_fix", {"lat": 37.9, "lon": -122.27, "t": 1003.0, "acc_m": 120.0}],
    ["add_fix", {"lat": 37.801, "lon": -122.269, "t": 1003.5,
                 "speed_ms": 14.123456, "heading": 35.5, "alt_m": 9.0,
                 "acc_m": 3.0}],
    ["note_incident", {"what": "hard brake", "t": 1003.6}],
    ["add_fix", {"lat": 37.8021, "lon": -122.2675, "t": 1004.5,
                 "speed_ms": 9.0, "heading": 80.0, "alt_m": None,
                 "acc_m": None}],
    ["feed_motion", {"g_vert": 1.23456, "tilt_deg": 12.345}],
    ["add_fix", {"lat": 37.8035, "lon": -122.266, "t": 1005.5,
                 "speed_ms": 22.0, "heading": 300.0, "alt_m": 5.0}],
    ["feed_extra", {"values": {"rpm_est": 5200, "climb_mps": -2.5,
                               "g_vert": 0.9}}],
    ["add_fix", {"lat": None, "lon": -122.2, "t": 1006.0}],
    ["add_fix", {"lat": 37.805, "lon": -122.264, "t": 1006.5,
                 "speed_ms": 22.5, "heading": 180.0, "alt_m": 4.0,
                 "acc_m": 50.0}],
    ["note_incident", {"what": "impact 2.4 g", "t": 1007.0}],
    ["stop", {"when": 1010.0}],
]


def long_ops(n):
    ops = [["start", {"when": 5000.0}]]
    for i in range(n):
        ops.append(["add_fix", {"lat": 37.8 + i * 0.0002, "lon": -122.27,
                                "t": 5000.0 + i, "speed_ms": 12.0 + (i % 7),
                                "heading": (i * 7.5) % 360.0,
                                "alt_m": 5.0 + (i % 30) * 0.8}])
        ops.append(["feed_extra", {"values": {"rpm_est": 4000 + i,
                                              "climb_mps": 0.5 + (i % 5),
                                              "g_vert": 1.0 + (i % 3) * 0.1}}])
    ops.append(["stop", {"when": 5000.0 + n + 1}])
    return ops


def run(name, ops, keep_text):
    folder = tempfile.mkdtemp(dir=SCRATCH)
    path = os.path.join(folder, "ride.jsonl")
    try:
        ride = telemetry.Ride(path=path, name=name, hz=1, machine=MACHINE)
        returns = []
        for op, args in ops:
            if op == "start":
                returns.append(ride.start(when=args["when"]))
            elif op == "stop":
                returns.append(ride.stop(when=args["when"]))
            elif op == "add_fix":
                got = ride.add_fix(**args)
                returns.append(json.loads(json.dumps(got)))
            elif op == "feed_extra":
                got = ride.feed_extra(args["values"])
                returns.append(json.loads(json.dumps(got)))
            elif op == "feed_motion":
                got = ride.feed_motion(**args)
                returns.append(json.loads(json.dumps(got)))
            elif op == "note_incident":
                returns.append(ride.note_incident(args["what"], t=args["t"]))
        with open(path, encoding="utf-8") as fh:
            log = fh.read()
        loaded = telemetry.load_ride(path)
        header, samples, incidents, footer = telemetry.read_log(path)

        def out(text):
            text = text.replace(folder, "<PATH>")
            if keep_text:
                return text
            return {"sha256": hashlib.sha256(text.encode()).hexdigest(),
                    "length": len(text)}

        return {
            "name": name, "ops": ops, "returns": returns if keep_text else None,
            "count": ride.count, "window": len(ride.samples),
            "samples": ride.samples if keep_text else None,
            "summary": out(json.dumps(ride.summary())),
            "csv": out(ride.to_csv()), "gpx": out(ride.to_gpx()),
            "json": out(ride.to_json()), "log": out(log),
            "read_log": out(json.dumps([header, samples, incidents, footer])),
            "loaded_summary": out(json.dumps(loaded.summary())),
            "loaded_gpx": out(loaded.to_gpx()),
            "loaded_csv": out(loaded.to_csv()),
            "header_name": telemetry.header_name(path),
            "errors": ride.errors,
        }
    finally:
        shutil.rmtree(folder)


def main():
    scenarios = [run("Grizzly & Wildcat <\x01> café", SHORT, True),
                 run("long", long_ops(telemetry.WINDOW + 50), False)]
    blob = {"channels": [[k, v[0], v[1], v[2]]
                         for k, v in telemetry.CHANNELS.items()],
            "window": telemetry.WINDOW, "flush_every": telemetry.FLUSH_EVERY,
            "ele_note": telemetry.ELE_NOTE, "machine": MACHINE,
            "scenarios": scenarios}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(blob, fh)
    print(f"wrote {len(scenarios)} scenarios -> {OUT}")


if __name__ == "__main__":
    main()
