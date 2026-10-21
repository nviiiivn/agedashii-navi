"""Golden outputs from the Python RPM chain, for the Kotlin port to match.

Run from anywhere with the flet venv (audio.py imports flet):

    PYTHONPATH=<NVII-DASH>/apps/navi:<NVII-DASH>/packages/agedashii-washi \
        /home/nvii/venvs/flet/bin/python tools/golden_rpm.py

Writes core/src/jvmTest/resources/golden/rpm.json. Every input that Kotlin cannot
regenerate bit-for-bit (anything from Python's `random`) is written out as
samples, so both sides analyse exactly the same numbers.
"""
import json
import math
import os
import random

from agedashii_navi import audio, bikes, dsp

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "core", "src", "test", "resources", "golden",
                   "rpm.json")


def engine_note(rpm, machine, rate=8000, n=4096,
                harmonics=((1, 0.5), (2, 0.35), (3, 0.2)), noise=0.0, seed=7):
    """Verbatim from tests/test_core.py:256-264."""
    hz = bikes.hz_from_rpm(rpm, machine)
    random.seed(seed)
    return [sum(amp * math.sin(2 * math.pi * hz * h * i / rate)
                for h, amp in harmonics)
            + (random.uniform(-noise, noise) if noise else 0.0)
            for i in range(n)]


def analysed(name, samples, machine, previous_rpm=None, rate=8000):
    got = audio.analyse(samples, rate, machine, previous_rpm=previous_rpm)
    return {"name": name, "machine": machine, "rate": rate,
            "previous_rpm": previous_rpm, "samples": samples,
            "hz": got["hz"], "rpm": got["rpm"], "rejected": got["rejected"],
            "contrast": got["contrast"], "engine_db": got["engine_db"],
            "bands": [[label, value] for label, value in got["bands"]]}


def main():
    cases = []
    for machine, speeds in (("cb750", (1500, 3000, 5000, 6500, 9000)),
                            ("ex250", (1500, 4000, 8000, 11000, 13000))):
        for rpm in speeds:
            cases.append(analysed(f"{machine}@{rpm}",
                                  engine_note(rpm, machine), machine))
    cases.append(analysed("octave trap", engine_note(
        4000, "cb750", harmonics=((1, 0.2), (2, 0.9), (3, 0.3))), "cb750"))
    cases.append(analysed("heavy wind", engine_note(
        5000, "cb750", noise=0.9), "cb750"))
    random.seed(1)
    cases.append(analysed("pure wind",
                          [random.uniform(-0.6, 0.6) for _ in range(4096)],
                          "cb750"))
    cases.append(analysed("jump", engine_note(8000, "cb750"), "cb750",
                          previous_rpm=1200))
    cases.append(analysed("twin note on the four", engine_note(3000, "ex250"),
                          "cb750"))

    # The spectrum and the picker alone, on the inputs test_core.py:408-417 uses.
    tone = [math.sin(2 * math.pi * 100 * i / 1000) for i in range(512)]
    random.seed(3)
    noise = [random.uniform(-1, 1) for _ in range(512)]
    spectra = []
    for name, samples in (("tone", tone), ("noise", noise)):
        bins = dsp.spectrum(samples, 1000, 20, 400)
        pick = dsp.harmonic_pick(bins)
        spectra.append({"name": name, "samples": samples, "rate": 1000,
                        "lo": 20, "hi": 400,
                        "bins": [[hz, p] for hz, p in bins], "pick": pick,
                        "contrast": dsp.contrast(bins, pick or 100.0)})

    # The filters, on a note plus a slow drift.
    mixed = [0.5 * math.sin(2 * math.pi * 2 * i / 8000)
             + 0.5 * math.sin(2 * math.pi * 120 * i / 8000) for i in range(4096)]
    high = dsp.highpass(mixed, 8000)
    narrow, narrow_rate = dsp.decimate(high, 8000)
    filters = {"samples": mixed, "rate": 8000, "highpass": high,
               "decimated": narrow, "decimated_rate": narrow_rate}

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump({"analyse": cases, "spectra": spectra, "filters": filters},
                  fh)
    print(f"wrote {len(cases)} analyse cases, {len(spectra)} spectra -> {OUT}")


if __name__ == "__main__":
    main()
