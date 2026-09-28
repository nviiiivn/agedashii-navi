"""Tile-schema adapters: the same map, drawn from OpenMapTiles or Protomaps tiles.

The design lives in `Schema.layers()` and is shared; an adapter only says where
each thing is in its schema - which source-layer, which field, which values.
Every layer, field and value named here was read out of built tiles with
layer_values.py (openmaptiles 3.16.0 and protomaps basemap 4.15.2, both from
planetiler 0.10.2), not taken from documentation.

Deliberately absent, for a map read at 60 mph:
  POIs and house numbers - points of interest are Watudo's (spec rule 6). A
      tile POI layer is labels, not search, so dropping it costs search nothing.
  footpaths, cycleways, steps, boundaries, landuse other than green - clutter
      a rider cannot use.
  sprites/icons - text only, so there is no sprite sheet to ship or theme.
"""

SOURCE = "bayarea"

#: Road classes, least important first; the index is the line-sort-key so a
#: motorway always draws over the street it crosses.
CLASSES = ["track", "service", "minor", "tertiary", "secondary", "primary", "trunk", "motorway"]

#: Line width in px at each zoom. Wider than a desk map: these are read at a
#: glance through a visor. Stops past the tiles' maxzoom are overzoom.
WIDTHS = {
    "motorway":  {5: 0.6, 8: 1.4, 10: 2.2, 12: 3.4, 14: 6.0, 16: 12.0, 18: 26.0, 20: 52.0},
    "trunk":     {5: 0.5, 8: 1.2, 10: 2.0, 12: 3.0, 14: 5.4, 16: 11.0, 18: 24.0, 20: 48.0},
    "primary":   {8: 0.6, 10: 1.4, 12: 2.6, 14: 5.0, 16: 10.0, 18: 22.0, 20: 44.0},
    "secondary": {9: 0.5, 10: 0.9, 12: 2.0, 14: 4.2, 16: 9.0, 18: 20.0, 20: 40.0},
    "tertiary":  {10: 0.5, 12: 1.6, 14: 3.6, 16: 8.0, 18: 18.0, 20: 36.0},
    "minor":     {12: 0.5, 14: 2.2, 16: 6.0, 18: 14.0, 20: 28.0},
    "service":   {14: 0.6, 16: 2.5, 18: 7.0, 20: 14.0},
    "track":     {13: 0.6, 16: 2.0, 18: 4.0, 20: 8.0},
}
LINK_FACTOR = 0.6
#: Classes grouped by the zoom they appear at: one layer per group keeps the
#: style small and needs no zoom expression inside a filter.
GROUPS = [("major", 5, ["trunk", "motorway"]),
          ("main", 8, ["secondary", "primary"]),
          ("tertiary", 10, ["tertiary"]),
          ("minor", 12, ["minor"]),
          ("service", 14, ["service"])]


def _interp(ws, z):
    """Width at zoom z from a stop table, exponential base 1.5 like the style."""
    zs = sorted(ws)
    if z <= zs[0]:
        return ws[zs[0]]
    if z >= zs[-1]:
        return ws[zs[-1]]
    for a, b in zip(zs, zs[1:]):
        if a <= z <= b:
            t = (1.5 ** (z - a) - 1) / (1.5 ** (b - a) - 1)
            return ws[a] + (ws[b] - ws[a]) * t


def width(cls, k=1.0):
    """Zoom-interpolated width expression for one class, scaled by k."""
    stops = []
    for z, w in sorted(WIDTHS[cls].items()):
        stops += [z, round(w * k, 2)]
    return ["interpolate", ["exponential", 1.5], ["zoom"], *stops]


def _width_by_class(cls_expr, link_expr, classes, k):
    """One width expression for several classes: zoom outermost, class inside."""
    zooms = sorted({z for c in classes for z in WIDTHS[c]})
    stops = []
    for z in zooms:
        per = []
        for c in classes:
            per += [c, round(_interp(WIDTHS[c], z) * k, 2)]
        stops += [z, ["*", ["case", link_expr, LINK_FACTOR, 1], ["match", cls_expr, *per, 0]]]
    return ["interpolate", ["exponential", 1.5], ["zoom"], *stops]


def _match_colour(cls_expr, c, classes):
    out = ["match", cls_expr]
    for k in classes:
        out += [k, c[k]]
    return out + [c["minor"]]


def _sort_key(cls_expr):
    out = ["match", cls_expr]
    for i, k in enumerate(CLASSES):
        out += [k, i]
    return out + [0]


def _size(*zs):
    return ["interpolate", ["linear"], ["zoom"], *zs]


class Schema:
    NAME = ATTRIBUTION = ROAD_LAYER = NAME_LAYER = PLACE_LAYER = ""
    SOURCE = SOURCE
    BACKGROUND = "land"   # which derived colour the background layer takes
    road_class = link = bridge = tunnel = name = None

    @staticmethod
    def width(cls, k=1.0):
        return width(cls, k)

    def base(self, c):
        return []

    def _l(self, id_, type_, source_layer, minzoom=None, filter_=None, paint=None, layout=None):
        d = {"id": id_, "type": type_, "source": SOURCE, "source-layer": source_layer}
        if minzoom is not None:
            d["minzoom"] = minzoom
        if filter_ is not None:
            d["filter"] = filter_
        if layout:
            d["layout"] = layout
        d["paint"] = paint or {}
        return d

    def roads(self, c, k, sun):
        cls, link = self.road_class, self.link
        out = []
        for part, cond, opacity in (("tunnel", self.tunnel, 0.45),
                                    ("ground", ["!", ["any", self.tunnel, self.bridge]], 1.0),
                                    ("bridge", self.bridge, 1.0)):
            for gname, minz, classes in GROUPS:
                f = ["all", ["in", cls, ["literal", classes]], cond]
                w = _width_by_class(cls, link, classes, k)
                layout = {"line-cap": "butt" if part == "tunnel" else "round",
                          "line-join": "round", "line-sort-key": _sort_key(cls)}
                if part == "bridge":
                    # A gap of land either side, so a bridge reads as passing over.
                    out.append(self._l(f"road-{gname}-bridge-casing", "line", self.ROAD_LAYER, minz, f,
                                       {"line-color": c["land"], "line-width": _widened(w, 4 if sun else 3)},
                                       layout))
                paint = {"line-color": _match_colour(cls, c, classes), "line-width": w}
                if opacity < 1:
                    paint["line-opacity"] = opacity
                out.append(self._l(f"road-{gname}-{part}", "line", self.ROAD_LAYER, minz, f, paint, layout))
        return out

    def layers(self, c, font, wide=1.0, sun=False):
        halo_w = 3 if sun else 2
        heavy, semi, med = font["heavy"], font["semibold"], font["medium"]
        grow = 2 if sun else 0
        L = list(self.base(c))
        if c["green"]:
            for i, g in enumerate(self.greens()):
                L.append(self._l(f"green-{i}", "fill", *g, paint={"fill-color": c["green"]}))
        L.append(self._l("water", "fill", *self.water_fill(), paint={"fill-color": c["water"]}))
        if c["shore"]:
            L.append(self._l("shore", "line", *self.water_fill(),
                             paint={"line-color": c["shore"], "line-width": 1.2}))
        river_w = ["interpolate", ["linear"], ["zoom"], 9, 0.6, 14, 1.6, 18, 4]
        L.append(self._l("river", "line", *self.rivers(), paint={"line-color": c["waterway"], "line-width": river_w}))
        L.append(self._l("stream", "line", *self.streams(),
                         paint={"line-color": c["waterway"], "line-width": ["interpolate", ["linear"], ["zoom"], 13, 0.5, 18, 2]}))
        L.append(self._l("runway", "line", *self.runway(),
                         paint={"line-color": c["aeroway"],
                                "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 11, 2, 14, 10, 18, 60]}))
        if c["building"]:
            L.append(self._l("building", "fill", *self.building(), paint={"fill-color": c["building"]}))
        L.append(self._l("rail", "line", *self.rail(),
                         paint={"line-color": c["rail"], "line-dasharray": [3, 2],
                                "line-width": ["interpolate", ["linear"], ["zoom"], 11, 0.8, 16, 2]}))
        L.append(self._l("track", "line", self.ROAD_LAYER, 13, self.track(),
                         paint={"line-color": c["track"], "line-dasharray": [2, 1.5],
                                "line-width": width("track", wide)}))
        L += self.roads(c, wide, sun)

        # Labels, bottom to top. The first symbol layer is where the app's
        # route goes beneath (metadata agedashii:route-below).
        def sym(id_, layer_, minz, filt, text, size, fnt, colour, halo, extra=None):
            layout = {"text-field": text, "text-font": [fnt], "text-size": size}
            layout.update(extra or {})
            return self._l(id_, "symbol", layer_, minz, filt, {
                "text-color": colour, "text-halo-color": halo, "text-halo-width": halo_w}, layout)

        L.append(sym("label-water", *self.water_label(), self.name, _size(9, 12 + grow, 14, 15 + grow),
                     med, c["label_water"], c["label_water_halo"],
                     {"text-letter-spacing": 0.1, "text-max-width": 8}))
        L.append(sym("label-road", self.NAME_LAYER, 13, self.named_roads(), self.name,
                     _size(13, 12 + grow, 16, 15 + grow, 19, 18 + grow), heavy if sun else semi,
                     c["label_minor"], c["halo"],
                     {"symbol-placement": "line", "text-max-angle": 30, "symbol-spacing": 320,
                      "text-padding": 4, "text-rotation-alignment": "map"}))
        L.append(sym("label-ref", *self.ref_label(), _size(10, 11 + grow, 14, 13 + grow, 17, 15 + grow),
                     heavy, c["label_ref"], c["label_ref_halo"],
                     {"symbol-placement": "line", "symbol-spacing": 420,
                      "text-rotation-alignment": "viewport", "text-padding": 6}))
        for id_, tier, size, fnt, col in (
                ("label-neighbourhood", "neighbourhood", _size(13, 11 + grow, 16, 13 + grow), med, "label_suburb"),
                ("label-suburb", "suburb", _size(11, 11 + grow, 15, 14 + grow), semi, "label_suburb"),
                ("label-town", "town", _size(10, 12 + grow, 14, 16 + grow), semi, "label_place"),
                ("label-city", "city", _size(8, 13 + grow, 12, 19 + grow, 15, 23 + grow), heavy, "label_place")):
            minz, filt = self.places(tier)
            L.append(sym(id_, self.PLACE_LAYER, minz, filt, self.name, size, fnt, c[col], c["halo"],
                         {"text-max-width": 7, "text-padding": 6}))
        return L


def _widened(w_expr, extra):
    """The same zoom-interpolated width with `extra` px added at every stop."""
    out = w_expr[:3]
    for i in range(3, len(w_expr), 2):
        out += [w_expr[i], ["+", w_expr[i + 1], extra]]
    return out


class OpenMapTiles(Schema):
    NAME = "openmaptiles"
    #: Mandatory and visible: openmaptiles/planetiler-openmaptiles LICENSE.md:45-52.
    ATTRIBUTION = "© OpenMapTiles © OpenStreetMap contributors"
    ROAD_LAYER = "transportation"
    NAME_LAYER = "transportation_name"
    PLACE_LAYER = "place"
    BACKGROUND = "land"   # ocean arrives as water class=ocean
    road_class = ["get", "class"]
    link = ["==", ["get", "ramp"], 1]
    bridge = ["==", ["get", "brunnel"], "bridge"]
    tunnel = ["==", ["get", "brunnel"], "tunnel"]
    name = ["coalesce", ["get", "name_en"], ["get", "name"]]
    _not_tunnel = ["!=", ["coalesce", ["get", "brunnel"], ""], "tunnel"]

    def greens(self):
        # OMT splits green in two: landcover (wood, grass - which includes
        # parks' lawns) and the park layer (reserves, national parks).
        return [("landcover", 6, ["in", ["get", "class"], ["literal", ["wood", "grass"]]]),
                ("park", 6, None)]

    def water_fill(self):
        return "water", None, ["all", ["!=", ["get", "class"], "swimming_pool"], self._not_tunnel]

    def rivers(self):
        return "waterway", 9, ["all", ["in", ["get", "class"], ["literal", ["river", "canal"]]], self._not_tunnel]

    def streams(self):
        return "waterway", 13, ["all", ["==", ["get", "class"], "stream"], self._not_tunnel]

    def runway(self):
        return "aeroway", 11, ["all", ["==", ["get", "class"], "runway"], ["==", ["geometry-type"], "LineString"]]

    def building(self):
        return "building", 14, None

    def rail(self):
        return "transportation", 11, ["all", ["in", ["get", "class"], ["literal", ["rail", "transit"]]],
                                      self._not_tunnel]

    def track(self):
        return ["==", ["get", "class"], "track"]

    def named_roads(self):
        return ["all", ["in", ["get", "class"],
                        ["literal", ["motorway", "trunk", "primary", "secondary", "tertiary", "minor"]]],
                ["has", "name"]]

    def ref_label(self):
        net, ref = ["get", "network"], ["get", "ref"]
        text = ["case",
                ["==", net, "us-interstate"], ["concat", "I-", ref],
                ["==", net, "us-highway"], ["concat", "US ", ref],
                ["==", net, "us-state"], ["concat", "CA ", ref], ref]
        return ("transportation_name", 10,
                ["all", ["in", ["get", "class"], ["literal", ["motorway", "trunk", "primary"]]], ["has", "ref"]],
                text)

    def water_label(self):
        return "water_name", 9, ["==", ["get", "class"], "bay"]

    def places(self, tier):
        minz, classes = {"city": (8, ["city"]), "town": (10, ["town"]),
                         "suburb": (11, ["village", "suburb"]),
                         "neighbourhood": (13, ["neighbourhood", "quarter", "hamlet"])}[tier]
        return minz, ["in", ["get", "class"], ["literal", classes]]


class Protomaps(Schema):
    NAME = "protomaps"
    #: protomaps/basemaps LICENSE.md: code BSD-3, visual design CC0. Data is
    #: ODbL (LICENSE_DATA.md), so the OSM credit is the only one owed - and
    #: the ESA WorldCover credit only if the `landcover` layer is shown, which
    #: this build excludes from the tiles altogether.
    ATTRIBUTION = "© OpenStreetMap"
    ROAD_LAYER = "roads"
    NAME_LAYER = "roads"
    PLACE_LAYER = "places"
    #: Land is drawn as `earth` polygons, so the background is water or the
    #: open Bay would render as land.
    BACKGROUND = "water"
    road_class = ["match", ["get", "kind_detail"],
                  ["motorway", "motorway_link"], "motorway",
                  ["trunk", "trunk_link"], "trunk",
                  ["primary", "primary_link"], "primary",
                  ["secondary", "secondary_link"], "secondary",
                  ["tertiary", "tertiary_link"], "tertiary",
                  ["residential", "unclassified", "living_street", "residential_link"], "minor",
                  ["service", "alley", "driveway"], "service",
                  "other"]
    link = ["==", ["get", "is_link"], True]
    bridge = ["==", ["get", "is_bridge"], True]
    tunnel = ["==", ["get", "is_tunnel"], True]
    name = ["coalesce", ["get", "name:en"], ["get", "name"]]
    GREEN = ["park", "wood", "forest", "grass", "scrub", "grassland", "nature_reserve", "meadow",
             "garden", "recreation_ground", "golf_course", "national_park", "protected_area"]

    def base(self, c):
        return [self._l("earth", "fill", "earth", None, ["==", ["geometry-type"], "Polygon"],
                        {"fill-color": c["land"]})]

    def greens(self):
        return [("landuse", 6, ["in", ["get", "kind"], ["literal", self.GREEN]])]

    def water_fill(self):
        return "water", None, ["all", ["==", ["geometry-type"], "Polygon"],
                               ["!", ["in", ["get", "kind"], ["literal", ["swimming_pool", "fountain"]]]]]

    def rivers(self):
        return "water", 9, ["all", ["==", ["geometry-type"], "LineString"],
                            ["in", ["get", "kind"], ["literal", ["river", "canal"]]]]

    def streams(self):
        return "water", 13, ["all", ["==", ["geometry-type"], "LineString"], ["==", ["get", "kind"], "stream"]]

    def runway(self):
        return "roads", 11, ["all", ["==", ["get", "kind"], "aeroway"], ["==", ["get", "kind_detail"], "runway"]]

    def building(self):
        return "buildings", 14, None

    def rail(self):
        return "roads", 11, ["all", ["==", ["get", "kind"], "rail"],
                             ["in", ["get", "kind_detail"], ["literal", ["rail", "light_rail", "subway"]]],
                             ["!=", ["get", "is_tunnel"], True]]

    def track(self):
        return ["==", ["get", "kind_detail"], "track"]

    def named_roads(self):
        return ["all", ["in", ["get", "kind"], ["literal", ["highway", "major_road", "minor_road"]]],
                ["!=", ["get", "kind_detail"], "service"], ["has", "name"]]

    def ref_label(self):
        net, st = ["get", "network"], ["get", "shield_text"]
        text = ["match", net, "US:I", ["concat", "I-", st], "US:US", ["concat", "US ", st],
                "US:CA", ["concat", "CA ", st], st]
        return ("roads", 10,
                ["all", ["in", ["get", "kind"], ["literal", ["highway", "major_road"]]],
                 ["has", "shield_text"], ["!=", ["get", "is_link"], True]],
                text)

    def water_label(self):
        return "water", 9, ["all", ["in", ["get", "kind"], ["literal", ["bay", "strait", "ocean"]]],
                            ["has", "name"]]

    def places(self, tier):
        minz, kd = {"city": (8, ["city"]), "town": (10, ["town"]),
                    "suburb": (11, ["village", "quarter", "suburb"]),
                    "neighbourhood": (13, ["neighbourhood", "hamlet"])}[tier]
        return minz, ["in", ["get", "kind_detail"], ["literal", kd]]


ADAPTERS = {"openmaptiles": OpenMapTiles(), "protomaps": Protomaps()}
