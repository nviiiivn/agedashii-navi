import CoreLocation
import MapLibre
import UIKit
import XnavKit

/**
 MapLibre's map view, driven by the Kotlin side (RideMap.kt) through MapHost:
 the iOS twin of what the Android app's RideMapController does to its MapView,
 minus the decisions, which stay in Kotlin. The style arrives complete - the
 route and marks sources and their layers already in it - so this only loads
 it, swaps GeoJSON by source id and moves the camera.

 Nothing in here touches the network: the style, glyphs and tiles are files in
 the app, and the map is never given a URL of its own (see blank).
 */
final class MapHostImpl: NSObject, MapHost, MLNMapViewDelegate {
    private let map: MLNMapView
    private let feed = FixFeed()
    private var events: MapEvents?

    // Kept so a style that loads later, or a theme change, gets them back.
    private var sourceData: [String: String] = [:]
    private var layerVisible: [String: Bool] = [:]
    private var styleLoading = false

    private var bike = "yellow"
    private var bikeView: BikeLocationView?
    private var lastFix: CLLocation?

    // follow() flies to her and then tracks; while it flies the map is not
    // tracking, and that is not her panning away (see follow).
    private var flight = 0
    private var flying = false
    private var reportedFollowing: Bool?

    /**
     The style the map starts with, until Kotlin's arrives. MLNMapView(frame:)
     alone loads MapLibre's default style, which is a web address - the one
     request this app must never make. Night's background, so the first frame
     is not a white flash.
     */
    private static let blank = ##"{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#06080b"}}]}"##

    override init() {
        map = MLNMapView(frame: .zero, styleJSON: MapHostImpl.blank)
        super.init()
        map.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        map.delegate = self
        map.showsLogoView = false
        // No credit button on the map: her decision, 2026-09-28 - "this is a
        // personal project ... i don't want it on my screen". The credits are
        // in Settings > About (KNOWN.md).
        map.showsAttributionButton = false
        map.showsCompassView = false
        // The top inset is hers to set (setTopInset); left on, MapLibre would
        // replace it with the safe area whenever the layout changes.
        map.automaticallyAdjustsContentInset = false
        // Her fixes come from the app's own CLLocationManager (Telemetry) via
        // showFix; MapLibre's default manager would be a second GPS stream and
        // a second permission prompt.
        map.shouldRequestAuthorizationToUseLocationServices = false
        map.locationManager = feed
        map.showsUserLocation = true
        // A LONG press, so a pan or a stray tap never offers a destination.
        map.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:))))
    }

    // MARK: MapHost

    func view() -> UIView { map }

    func setEvents(events: MapEvents) {
        self.events = events
    }

    func loadStyle(json: String) {
        onMain { [self] in
            // A document MapLibre cannot parse would come back as a failed load
            // anyway; checked here so the reason is a plain one.
            guard let data = json.data(using: .utf8), (try? JSONSerialization.jsonObject(with: data)) != nil else {
                events?.onStyleFailed(why: "the style is not valid JSON")
                return
            }
            styleLoading = true
            map.styleJSON = json
        }
    }

    func setSourceData(sourceId: String, geojson: String) {
        onMain { [self] in
            sourceData[sourceId] = geojson
            if let style = map.style, !styleLoading { apply(sourceId, geojson, to: style) }
        }
    }

    func setLayerVisible(layerId: String, visible: Bool) {
        onMain { [self] in
            layerVisible[layerId] = visible
            if !styleLoading { map.style?.layer(withIdentifier: layerId)?.isVisible = visible }
        }
    }

    func setBike(colour: String) {
        onMain { [self] in
            bike = colour
            bikeView?.look = BikeMarker.look(colour)
        }
    }

    func showFix(lat: Double, lon: Double, course: Double, accuracy: Double) {
        onMain { [self] in
            // CLLocation reads a negative accuracy as "this coordinate is
            // invalid", which ours is not; 0 draws no accuracy ring.
            let fix = CLLocation(
                coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                altitude: 0,
                horizontalAccuracy: max(accuracy, 0),
                verticalAccuracy: -1,
                course: course >= 0 ? course : -1,
                speed: -1,
                timestamp: Date()
            )
            lastFix = fix
            feed.push(fix)
        }
    }

    func follow(northUp: Bool, zoom: Double) {
        onMain { [self] in
            let mode: MLNUserTrackingMode = northUp ? .follow : .followWithCourse
            flight += 1
            flying = false
            guard let fix = lastFix else {
                // No fix yet: follow, but keep the overview (Kotlin sends a
                // negative zoom for that) - riding zoom at the map's centre was
                // a blank patch of the Bay that looked broken. MapLibre goes to
                // her itself when the first fix comes.
                if northUp && map.direction != 0 { map.setDirection(0, animated: true) }
                map.setUserTrackingMode(mode, animated: true, completionHandler: nil)
                return
            }
            let target = zoom >= 0 ? zoom : map.zoomLevel
            if map.userTrackingMode == mode && abs(map.zoomLevel - target) < 0.01 { return }
            // Tracking keeps whatever zoom the map has, and the next fix's
            // camera move cuts short any zoom started on its own. So: fly to
            // her at the zoom first, then track from there. The fly stops
            // tracking for its 0.3 s, which is not her panning away, so that
            // change is not reported.
            let heading = northUp ? 0 : (fix.course >= 0 ? fix.course : map.direction)
            let token = flight
            flying = true
            map.setCenter(fix.coordinate, zoomLevel: target, direction: heading, animated: true) { [weak self] in
                guard let self, self.flight == token else { return }
                self.flying = false
                // A touch ends the fly early. Her hand still on the map is her
                // taking over, which stops following as any pan does.
                let handOnMap = self.map.gestureRecognizers?.contains { $0.state == .began || $0.state == .changed } ?? false
                if handOnMap {
                    self.report(following: false)
                } else {
                    self.map.setUserTrackingMode(mode, animated: false, completionHandler: nil)
                }
            }
        }
    }

    func setCamera(lat: Double, lon: Double, zoom: Double) {
        onMain { [self] in
            map.setCenter(CLLocationCoordinate2D(latitude: lat, longitude: lon), zoomLevel: zoom, animated: false)
        }
    }

    func setLimits(south: Double, west: Double, north: Double, east: Double, minZoom: Double, maxZoom: Double) {
        onMain { [self] in
            // Each zoom limit is undefined while min > max, so widen before narrowing.
            if minZoom > map.maximumZoomLevel {
                map.maximumZoomLevel = maxZoom
                map.minimumZoomLevel = minZoom
            } else {
                map.minimumZoomLevel = minZoom
                map.maximumZoomLevel = maxZoom
            }
            // Stricter than Android's camera-target box: this keeps the whole
            // SCREEN inside the map, so near its edge the bike sits off-centre
            // rather than beside a blank. iOS has no centre-only bound.
            map.maximumScreenBounds = MLNCoordinateBounds(
                sw: CLLocationCoordinate2D(latitude: south, longitude: west),
                ne: CLLocationCoordinate2D(latitude: north, longitude: east)
            )
        }
    }

    func setTopInset(points: Double) {
        onMain { [self] in
            // Keeps her bike out from under the turn banner: MapLibre centres
            // the tracked location in the map BELOW the inset. In landscape the
            // banner covers nearly half the screen (RideMapController).
            let inset = UIEdgeInsets(top: CGFloat(points), left: 0, bottom: 0, right: 0)
            map.setContentInset(inset, animated: true, completionHandler: nil)
        }
    }

    // MARK: MLNMapViewDelegate

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        // The blank style loads too; only the Kotlin side's counts.
        guard styleLoading else { return }
        styleLoading = false
        // Both bikes go into every style by name, as on Android, so a style
        // layer can draw either.
        for colour in BikeMarker.colours {
            style.setImage(BikeMarker.look(colour).image, forName: BikeMarker.imageName(colour))
        }
        for (id, geojson) in sourceData { apply(id, geojson, to: style) }
        for (id, visible) in layerVisible { style.layer(withIdentifier: id)?.isVisible = visible }
        events?.onStyleLoaded()
    }

    func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
        // MapLibre reports only style failures here (a parse error, a missing
        // file), never a tile.
        styleLoading = false
        events?.onStyleFailed(why: error.localizedDescription)
    }

    func mapView(_ mapView: MLNMapView, didChange mode: MLNUserTrackingMode, animated: Bool) {
        // A pan sets the mode to none: she has panned away, and the Kotlin
        // side offers to follow again.
        if !flying { report(following: mode != .none) }
    }

    func mapView(_ mapView: MLNMapView, viewFor annotation: MLNAnnotation) -> MLNAnnotationView? {
        guard annotation is MLNUserLocation else { return nil }
        let view = BikeLocationView(look: BikeMarker.look(bike))
        bikeView = view
        return view
    }

    // MARK: -

    private func report(following: Bool) {
        guard following != reportedFollowing else { return }
        reportedFollowing = following
        events?.onFollowingChanged(following: following)
    }

    private func apply(_ sourceId: String, _ geojson: String, to style: MLNStyle) {
        guard let source = style.source(withIdentifier: sourceId) as? MLNShapeSource,
              let data = geojson.data(using: .utf8) else { return }
        do {
            source.shape = try MLNShape(data: data, encoding: String.Encoding.utf8.rawValue)
        } catch {
            print("[map] \(sourceId): GeoJSON refused: \(error.localizedDescription)")
        }
    }

    @objc private func longPressed(_ press: UILongPressGestureRecognizer) {
        guard press.state == .began else { return }
        let point = map.convert(press.location(in: map), toCoordinateFrom: map)
        events?.onLongPress(lat: point.latitude, lon: point.longitude)
    }

    /** Runs [work] on the main thread: now if already there, else next. UIKit and MapLibre are main-thread only. */
    private func onMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
    }
}

/**
 The location manager MapLibre uses for its user-location marker, fed with the
 app's own fixes instead of starting GPS of its own. MapLibre stops and starts
 it as the app goes to the background and back; fixes are held meanwhile and
 the latest is handed over on the way back, as a real manager's first update.
 */
private final class FixFeed: NSObject, MLNLocationManager {
    weak var delegate: MLNLocationManagerDelegate?
    var headingOrientation: CLDeviceOrientation = .portrait

    // The app's own manager asked for permission; a fix only reaches here
    // once it was given.
    var authorizationStatus: CLAuthorizationStatus { .authorizedWhenInUse }

    private var running = false
    private var last: CLLocation?

    func push(_ fix: CLLocation) {
        last = fix
        if running { delegate?.locationManager(self, didUpdate: [fix]) }
    }

    func startUpdatingLocation() {
        running = true
        // Not inline: MapLibre is mid-way through turning the marker on when
        // it calls this, and a fix handed over then does not move a
        // following camera (maplibre/swiftui-dsl's StaticLocationManager).
        DispatchQueue.main.async { [weak self] in
            guard let self, self.running, let last = self.last else { return }
            self.delegate?.locationManager(self, didUpdate: [last])
        }
    }

    func stopUpdatingLocation() { running = false }
    func requestAlwaysAuthorization() {}
    func requestWhenInUseAuthorization() {}
    // The map turns with her course, never the compass: nothing to start.
    func startUpdatingHeading() {}
    func stopUpdatingHeading() {}
    func dismissHeadingCalibrationDisplay() {}
}

/**
 Her bike as MapLibre's user-location marker. The pointer is the way she is
 going: straight up while the map turns with her, her course against the
 map's own rotation otherwise. A stopped bike has no course, so it keeps
 pointing the way it last went rather than snapping north.
 */
private final class BikeLocationView: MLNUserLocationAnnotationView {
    var look: BikeMarker.Look {
        didSet { apply() }
    }

    private let bike = UIImageView()
    private let ring = CAShapeLayer()
    private var lastCourse: CLLocationDirection = 0
    private var ringRadius: CGFloat = 0

    init(look: BikeMarker.Look) {
        self.look = look
        super.init(frame: CGRect(x: 0, y: 0, width: BikeMarker.size, height: BikeMarker.size))
        ring.frame = bounds
        layer.addSublayer(ring)
        bike.frame = bounds
        addSubview(bike)
        apply()
    }

    required init?(coder: NSCoder) { return nil }

    override func update() {
        guard let mapView = mapView, let location = userLocation?.location else { return }
        if location.course >= 0 { lastCourse = location.course }
        let turn = mapView.userTrackingMode == .followWithCourse ? 0 : lastCourse - mapView.direction
        bike.transform = CGAffineTransform(rotationAngle: CGFloat(turn * .pi / 180))

        // How far off the fix may be, as a faint disc in her colour (Android:
        // accuracyAlpha 0.12). Only when it is wider than the bike.
        var radius: CGFloat = 0
        if location.horizontalAccuracy > 0 {
            radius = CGFloat(location.horizontalAccuracy / mapView.metersPerPoint(atLatitude: location.coordinate.latitude))
        }
        if radius <= BikeMarker.size / 2 { radius = 0 }
        guard abs(radius - ringRadius) > 0.5 else { return }
        ringRadius = radius
        // Without this the ring eases to each new size and lags the zoom.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let centre = CGPoint(x: bounds.midX, y: bounds.midY)
        ring.path = radius > 0
            ? UIBezierPath(arcCenter: centre, radius: radius, startAngle: 0, endAngle: 2 * .pi, clockwise: true).cgPath
            : nil
        CATransaction.commit()
    }

    private func apply() {
        bike.image = look.image
        ring.fillColor = look.body.withAlphaComponent(0.12).cgColor
    }
}
