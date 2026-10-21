"""Python's own rounding and float printing, for core/Py.kt to match.

    /home/nvii/venvs/flet/bin/python tools/golden_py.py

Writes core/src/jvmTest/resources/golden/py.json. Each value is stored as its
IEEE-754 bits in hex, so no JSON parser can round it on the way in.
"""
import json
import math
import os
import random
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "core", "src", "test", "resources", "golden",
                   "py.json")


def bits(x):
    return struct.pack(">d", x).hex()


def main():
    values = [0.0, -0.0, 1.0, -1.0, 0.1, 0.2, 0.1 + 0.2, 100.0 / 3, 2.5, 3.5,
              -2.5, 0.5, 1.5, 2.675, 0.125, 0.375, 1.005, -0.0004, -0.00049,
              37.8, -122.27, 37.800001, 1700000000.0, 1700000000.25,
              1234567890123456.0, 9999999999999998.0, 1e16, 1e17, 1.5e20,
              1e-4, 9.99e-5, 1e-5, 1.23e-7, 5e-324, 1.7976931348623157e308,
              19.58, 26.8224, 26.8224 * 2.2369362920544, 122.0 * 3.280839895,
              0.0005, 0.0015, 0.0025, 1e22, 123.456, 9.80665, 2.0 / 9.80665]
    random.seed(20260927)
    for _ in range(300):
        values.append(random.uniform(-200, 200))
        values.append(random.uniform(-1, 1) * 10 ** random.randint(-8, 17))
        values.append(round(random.uniform(-500, 500), random.randint(0, 6)))
    cases = []
    for x in values:
        case = {"bits": bits(x), "repr": repr(x), "str": str(x),
                "round": [bits(round(x, n)) for n in range(7)],
                "fixed0": f"{x:.0f}", "fixed1": f"{x:.1f}"}
        if abs(x) < 2 ** 53:
            case["round_int"] = round(x)
        cases.append(case)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump({"cases": cases}, fh)
    print(f"wrote {len(cases)} cases -> {OUT}")


if __name__ == "__main__":
    main()
