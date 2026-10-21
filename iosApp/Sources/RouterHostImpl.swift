import Foundation
import Valhalla
import ValhallaConfigModels
import XnavKit

/**
 Valhalla on the phone, for the Kotlin side's Router (routing/Router.kt), which
 has already checked the archive against the Valhalla version this binding
 carries - Valhalla itself only warns about foreign tiles and then routes on
 misread data. Called off the main thread, one call at a time, so there is
 nothing to lock here; Valhalla holds its own lock across each request anyway.
 */
final class RouterHostImpl: NSObject, RouterHost {
    /** RouterHost.ERROR on the Kotlin side: what route() puts before Valhalla's own text. */
    private static let errorPrefix = "ERROR:"

    private var valhalla: Valhalla?

    func open(tarPath: String) -> String? {
        // New tiles (a reload) replace the old ones: let go of the archive the
        // last one has mapped before mapping this one.
        valhalla?.close()
        valhalla = nil
        do {
            let config = try ValhallaConfig(tileExtractTar: URL(fileURLWithPath: tarPath))
            valhalla = try Valhalla(config)
            return nil
        } catch {
            return Self.why(error)
        }
    }

    func route(request: String) -> String {
        guard let valhalla else { return Self.errorPrefix + "the router is not open" }
        do {
            return try valhalla.route(rawRequest: request)
        } catch {
            return Self.errorPrefix + Self.why(error)
        }
    }

    /**
     Valhalla's own words ("No path could be found for input"), which the
     Kotlin side reads to tell her what went wrong. Its errorDescription would
     wrap them as "Valhalla error 442: ...", and a wrapped message like that
     reached her screen once on Android.
     */
    private static func why(_ error: Error) -> String {
        if let valhallaError = error as? ValhallaError, case let .valhallaError(_, message) = valhallaError {
            return message
        }
        return error.localizedDescription
    }
}
