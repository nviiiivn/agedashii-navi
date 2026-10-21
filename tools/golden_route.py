"""Golden outputs from the Python route maths and turn-by-turn follower.

    PYTHONPATH=<NVII-DASH>/apps/navi:<NVII-DASH>/packages/agedashii-washi \
        /home/nvii/venvs/flet/bin/python tools/golden_route.py

Writes core/src/jvmTest/resources/golden/route.json: a winding route, every
position asked about, and what routing.progress / next_maneuver / remaining and
guidance.Follower answered. All inputs are written out, so Kotlin replays
exactly what Python saw. `eta` is left out - it is local clock time and would
tie the file to one time zone.
"""
import json
import math
import os
import random

from agedashii_washi import routing

from agedashii_navi import guidance

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "core", "src", "test", "resources", "golden",
                   "route.json")
M_PER_DEG = 111_320.0


def offset(point, north_m, east_m):
    lat, lon = point
    return (lat + north_m / M_PER_DEG,
            lon + east_m / (M_PER_DEG * math.cos(math.radians(lat))))


def winding_route(rng):
    """Shape points 40-450 m apart, like real OSM ways, plus the awkward cases:
    a duplicated point (a zero-length segment) and a very short one."""
    pts = [(37.8000, -122.2700)]
    heading = 20.0
    for i in range(26):
        heading += rng.uniform(-70, 70)
        step = rng.uniform(40, 450) if i != 9 else 3.0
        pts.append(offset(pts[-1], step * math.cos(math.radians(heading)),
                          step * math.sin(math.radians(heading))))
        if i == 14:
            pts.append(pts[-1])
    return pts


def maneuvers_for(pts, cum):
    words = ["head north", "turn left", "turn right", "bear right",
             "turn sharp left", "make a U-turn", "continue", "turn slight left"]
    out = [routing.Maneuver("head north", "First St", 0.0, pts[0], 0, 0.0)]
    for n, k in enumerate((4, 8, 13, 17, 21, 24)):
        out.append(routing.Maneuver(words[(n + 1) % len(words)],
                                    f"Street {n}", 0.0, pts[k], k, cum[k]))
    out.append(routing.Maneuver("arrive at your destination", "", 0.0,
                                pts[-1], len(pts) - 1, cum[-1]))
    return out


def status_of(status, follower):
    return {"ok": status["ok"], "reason": status["reason"],
            "say": status["say"],
            "maneuver_index": (status["maneuver"].index
                               if status.get("maneuver") else None),
            "instruction": status.get("instruction", ""),
            "street": status.get("street", ""), "icon": status.get("icon", ""),
            "turn_in_m": status.get("turn_in_m"),
            "off_route_m": status.get("off_route_m"),
            "off_route": status.get("off_route", False),
            "arrived": status.get("arrived", False),
            "left_m": status.get("left_m"), "left_s": status.get("left_s"),
            "source": status.get("source", ""),
            "should_reroute": follower.should_reroute()}


def ride(route, rng, speed_ms, excursions, jitter_m=4.0, stride_s=1.0):
    """Fixes along the line at `speed_ms`, with GPS jitter, and some stretches
    pushed off to the side by `excursions` metres."""
    fixes = []
    along, t = 0.0, 1000.0
    pts, cum = route.points, route.cum_m
    while along <= cum[-1] + 30.0:
        k = max(i for i in range(len(cum)) if cum[i] <= min(along, cum[-1]))
        k = min(k, len(pts) - 2)
        seg = cum[k + 1] - cum[k]
        f = 0.0 if seg <= 0 else min(1.0, (along - cum[k]) / seg)
        lat = pts[k][0] + (pts[k + 1][0] - pts[k][0]) * f
        lon = pts[k][1] + (pts[k + 1][1] - pts[k][1]) * f
        side = excursions.get(int(t - 1000.0), 0.0)
        p = offset((lat, lon), rng.uniform(-jitter_m, jitter_m),
                   side + rng.uniform(-jitter_m, jitter_m))
        fixes.append({"lat": p[0], "lon": p[1], "speed_ms": speed_ms, "t": t})
        along += speed_ms * stride_s
        t += stride_s
    return fixes


def replay(route, fixes, offroute_m=45, reroute_at=None):
    follower = guidance.Follower(route, offroute_m=offroute_m)
    out = []
    for n, fix in enumerate(fixes):
        if reroute_at is not None and n == reroute_at:
            follower.set_route(route)
            follower.note_reroute()
        status = follower.update((fix["lat"], fix["lon"]),
                                 speed_ms=fix["speed_ms"], now=fix["t"])
        out.append(status_of(status, follower))
    return out


def main():
    rng = random.Random(20260927)
    pts = winding_route(rng)
    cum = routing.cumulative_m(pts)
    route = routing.Route(ok=True, source="offline", points=pts, cum_m=cum,
                          maneuvers=maneuvers_for(pts, cum),
                          distance_m=cum[-1], duration_s=cum[-1] / 11.0)

    probes = []
    for _ in range(400):
        base = pts[rng.randrange(len(pts))]
        p = offset(base, rng.uniform(-600, 600), rng.uniform(-600, 600))
        probes.append(p)
    probes += [pts[0], pts[-1], offset(pts[-1], 900, 900),
               offset(pts[5], 0.0, 0.0)]
    answers = []
    for p in probes:
        index, along, off = routing.progress(route, p)
        man, metres, man_off = routing.next_maneuver(route, p)
        left_m, left_s = routing.remaining(route, p)
        answers.append({"lat": p[0], "lon": p[1], "index": index,
                        "along": along, "off": off,
                        "man_index": man.index if man else None,
                        "man_m": metres, "man_off": man_off,
                        "left_m": left_m, "left_s": left_s})

    single = routing.Route(ok=True, points=[pts[3]], cum_m=[0.0],
                           maneuvers=[], distance_m=0.0, duration_s=0.0)
    single_p = offset(pts[3], 120.0, -50.0)
    single_answer = list(routing.progress(single, single_p))

    rides = []
    for name, speed, excursions, reroute_at in (
            ("cruise", 15.0, {}, None),
            ("fast", 30.0, {}, None),
            ("crawl", 2.0, {}, None),
            ("lost and found", 13.0,
             {40: 70.0, 41: 90.0, 42: 120.0, 43: 150.0, 44: 60.0, 45: 30.0,
              46: 20.0}, None),
            ("flicker", 13.0, {30: 60.0, 32: 60.0, 34: 60.0}, None),
            ("rerouted", 13.0, {50: 200.0, 51: 200.0, 52: 200.0}, 54)):
        fixes = ride(route, rng, speed, excursions)
        rides.append({"name": name, "fixes": fixes,
                      "statuses": replay(route, fixes, reroute_at=reroute_at),
                      "reroute_at": reroute_at})

    blob = {"points": [list(p) for p in pts], "cum_m": cum,
            "maneuvers": [{"instruction": m.instruction, "street": m.street,
                           "distance_m": m.distance_m, "at": list(m.at),
                           "index": m.index, "cum_m": m.cum_m}
                          for m in route.maneuvers],
            "distance_m": route.distance_m, "duration_s": route.duration_s,
            "probes": answers,
            "single": {"point": list(pts[3]), "probe": list(single_p),
                       "answer": single_answer},
            "rides": rides}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(blob, fh)
    said = sum(1 for r in rides for s in r["statuses"] if s["say"])
    print(f"wrote {len(answers)} probes, {len(rides)} rides, {said} prompts "
          f"-> {OUT}")


if __name__ == "__main__":
    main()
