"""Golden outputs from the Python daylight and place-parsing code.

    PYTHONPATH=<NVII-DASH>/apps/navi:<NVII-DASH>/packages/agedashii-washi \
        /home/nvii/venvs/flet/bin/python tools/golden_places.py

Writes core/src/test/resources/golden/places.json: the sun's altitude and the
next sunrise and sunset for a spread of places and times, and what
parse_point and parse_gpx made of a list of awkward inputs.
"""
import json
import os
import random

from agedashii_navi import daylight, places

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "core", "src", "test", "resources", "golden",
                   "places.json")

POINTS = [
    "37.8044, -122.2712", "37.8044,-122.2712", "37.8044 -122.2712",
    "  37.8044 ,  -122.2712  ", "-33.8688, 151.2093", "91.0, 0.0",
    "0.0, 181.0", "90, 180", "-90, -180", "37, -122",
    "geo:37.8044,-122.2712?z=15", "GEO:37.8044,-122.2712", "geo:0,0",
    "geo:abc", "https://www.google.com/maps/place/Somewhere/"
    "@37.8044,-122.2712,15z/data=!3m1!4b1",
    "https://maps.apple.com/?q=37.8044,-122.2712&t=m",
    "https://maps.apple.com/?ll=37.8044,-122.2712",
    "https://www.google.com/maps?daddr=37.8044,-122.2712",
    "https://www.google.com/maps/dir/?api=1&destination=37.8044%2C-122.2712",
    "https://example.com/?query=37.8044+-122.2712#frag",
    "https://example.com/?q=Grand+Ave&ll=37.8,-122.2",
    "https://example.com/path?x=1&q=&q=37.1,-122.1",
    "http://maps.example/@91,0", "see https://x.test/?q=12.5,13.5 now",
    """37°48'15.8"N 122°16'16.3"W""", """122°16'16.3"W 37°48'15.8"N""",
    "37 48 15.8 N, 122 16 16.3 W", "37d48m15.8sN 122d16m16.3sW",
    """33°51'54.5"S 151°12'35.6"E""", "37°48'1.2.3\"N 122°16'16.3\"W",
    "somewhere nice", "---", "", "   ", "1,2,3,4", "abc 12.5, 13.5 def",
    "12.5, 13.5 and 14.5, 15.5", "٣٧.٨, -١٢٢.٢", "37.8044\t-122.2712",
    "1234.5, 12.0", "12.345678901, -99.1",
]

GPX = [
    """<?xml version="1.0"?><gpx version="1.1"><trk><name>Grizzly Peak</name>
    <trkseg><trkpt lat="37.8800" lon="-122.2400"><ele>380</ele></trkpt>
    <trkpt lat="37.8810" lon="-122.2390"></trkpt></trkseg></trk></gpx>""",
    """<gpx><trk><name>Both</name><trkseg><trkpt lat="37.1" lon="-122.1"/>
    </trkseg></trk><wpt lat="1.0" lon="2.0"></wpt></gpx>""",
    """<gpx><rte><name>Plan</name><rtept lat="37.1" lon="-122.1"></rtept>
    <rtept lat='37.2' lon='-122.2'></rtept></rte></gpx>""",
    """<gpx><wpt lat="37.5" lon="-122.5"><name>Only a pin</name></wpt></gpx>""",
    """<gpx><trk><trkseg><trkpt lon="-122.1" lat="37.1"/></trkseg></trk></gpx>""",
    """<GPX><TRK><NAME>  Shouting  </NAME><TRKSEG><TRKPT LAT="37.1" LON="-122.1">
    </TRKPT></TRKSEG></TRK></GPX>""",
    """<gpx><metadata><name>Meta</name></metadata><trk><name>Track</name>
    <trkseg><trkpt lat = "37.1"
      lon = "-122.1"/><trkpt lat="95.0" lon="1.0"/><trkpt lat="1.2.3" lon="1"/>
    </trkseg></trk></gpx>""",
    """<gpx><trk><trkseg><trkptx lat="1" lon="2"/><trkpt lat="3" lon="4"/>
    </trkseg></trk></gpx>""",
    "hello", "", "<gpx></gpx>",
    # Attribute order, fixed in places.py 5baa7e78.
    """<gpx><trk><trkseg><trkpt ele="12" lon="-122.2712" lat="37.8044" t="x"/>
    <trkpt lat="37.8044"/><trkpt lon='-122.1'
      lat='37.2'></trkpt><TRKPT LON="-122.3" LAT="37.3"/><trkpt/>
    <trkpt:x lat="1" lon="2"/><trkpt flat="9" lat="37.4" lon="-122.4"/>
    </trkseg></trk><wpt lon="2.0" lat="1.0"/></gpx>""",
    """<gpx><rte><rtept lon="-122.5" lat="37.5"/></rte><wpt lat="3" lon="4"/></gpx>""",
]


def main():
    rng = random.Random(20260927)
    places_list = [(37.8044, -122.2712), (0.0, 0.0), (64.8378, -147.7164),
                   (-33.8688, 151.2093), (51.4779, -0.0015), (78.2232, 15.6267),
                   (-54.8019, -68.3030)]
    suns = []
    for lat, lon in places_list:
        for _ in range(40):
            when = rng.uniform(1.6e9, 1.9e9)
            suns.append({"lat": lat, "lon": lon, "when": when,
                         "altitude": daylight.solar_altitude(lat, lon, when)})
    times = []
    for lat, lon in places_list:
        for when in (1750000000.0, 1766000000.0, 1735689600.0):
            rise, fall = daylight.sun_times(lat, lon, when)
            times.append({"lat": lat, "lon": lon, "when": when,
                          "rise": rise, "fall": fall})
    phases = [{"altitude": a, "phase": daylight.phase(altitude=a)}
              for a in (90.0, 6.0, 5.999, 0.0, -0.001, -6.0, -6.001, -90.0)]
    points = []
    for text in POINTS:
        got = places.parse_point(text)
        points.append({"text": text, "result": list(got) if got else None})
    gpx = []
    for text in GPX:
        name, pts = places.parse_gpx(text)
        gpx.append({"text": text, "name": name, "points": [list(p) for p in pts]})
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump({"suns": suns, "sun_times": times, "phases": phases,
                   "points": points, "gpx": gpx}, fh, ensure_ascii=False)
    print(f"wrote {len(suns)} altitudes, {len(times)} sun-time pairs, "
          f"{len(points)} points, {len(gpx)} gpx -> {OUT}")


if __name__ == "__main__":
    main()
