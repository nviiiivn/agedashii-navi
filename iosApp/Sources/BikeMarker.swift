import UIKit

/**
 Her bike on the map, drawn here from the same shapes as the Android app's
 vector drawables (res/drawable/bike_yellow.xml and bike_black.xml): a disc in
 her colour with a contrasting edge so it is findable on light tiles and dark
 ones, a pointer at the top for heading, and the motorcycle glyph (Material
 Icons "two_wheeler", Apache-2.0). There is no SVG renderer on iOS to hand the
 XML to, so the glyph's path data is copied verbatim and parsed below - a
 redrawn bike would drift from the Android one, an SF Symbol would be a
 different bike.
 */
enum BikeMarker {
    static let size: CGFloat = 56

    struct Look {
        let body: UIColor
        let edge: UIColor
        let image: UIImage
    }

    static func look(_ colour: String) -> Look { colour == "black" ? black : yellow }

    /** The style-image name the Android app uses for the same bike. */
    static func imageName(_ colour: String) -> String { "agedashii-bike-\(colour == "black" ? "black" : "yellow")" }

    static let colours = ["yellow", "black"]

    // The pointer is in the body colour with the edge round it: a dark pointer
    // vanished against the Night map (bike_yellow.xml). The light edge keeps
    // the black bike visible on dark tiles.
    private static let yellow = make(body: rgb(0xffd93b), edge: rgb(0x1a1200))
    private static let black = make(body: rgb(0x101418), edge: rgb(0xf2f4f8))

    private static func make(body: UIColor, edge: UIColor) -> Look {
        let image = UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { context in
            let cg = context.cgContext
            // The drawables' viewport is 48 units across a 56 dp image.
            cg.scaleBy(x: size / 48, y: size / 48)
            body.setFill()
            edge.setStroke()

            let pointer = UIBezierPath()
            pointer.move(to: CGPoint(x: 24, y: 1))
            pointer.addLine(to: CGPoint(x: 30, y: 9))
            pointer.addLine(to: CGPoint(x: 18, y: 9))
            pointer.close()
            pointer.lineWidth = 1.5
            pointer.miterLimit = 4    // Android's default; UIKit's is 10
            pointer.fill()
            pointer.stroke()

            // "M24,7 A17,17 0 1,1 23.99,7 Z" is a whole circle round (24, 24).
            let disc = UIBezierPath(arcCenter: CGPoint(x: 24, y: 24), radius: 17, startAngle: 0, endAngle: 2 * .pi, clockwise: true)
            disc.lineWidth = 2
            disc.fill()
            disc.stroke()

            // The drawable's <group>: translate, then scale about the origin.
            cg.translateBy(x: 10.2, y: 10.4)
            cg.scaleBy(x: 1.15, y: 1.15)
            edge.setFill()
            glyph.fill()
        }
        return Look(body: body, edge: edge, image: image)
    }

    private static let glyph = PathData.parse(
        "M20,11c-0.18,0 -0.36,0.03 -0.53,0.05L17.41,9H20V6l-3.72,1.86L13.41,5H9v2h3.59l2,2H11l-4,2L5,9H0v2h4c-2.21,0 -4,1.79 -4,4c0,2.21 1.79,4 4,4c2.21,0 4,-1.79 4,-4l2,2h3l3.49,-6.1l1.01,1.01C16.59,12.64 16,13.75 16,15c0,2.21 1.79,4 4,4c2.21,0 4,-1.79 4,-4C24,12.79 22.21,11 20,11zM4,17c-1.1,0 -2,-0.9 -2,-2c0,-1.1 0.9,-2 2,-2c1.1,0 2,0.9 2,2C6,16.1 5.1,17 4,17zM20,17c-1.1,0 -2,-0.9 -2,-2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2C22,16.1 21.1,17 20,17z"
    )

    private static func rgb(_ hex: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((hex >> 16) & 0xff) / 255,
            green: CGFloat((hex >> 8) & 0xff) / 255,
            blue: CGFloat(hex & 0xff) / 255,
            alpha: 1
        )
    }
}

/**
 Android pathData, for the commands the bike's glyph uses: M L H V C S Z, each
 absolute or relative, with the implicit repeats SVG allows. Filled non-zero,
 as Android fills by default - the wheels' holes are wound the other way.
 */
private enum PathData {
    static func parse(_ data: String) -> UIBezierPath {
        let path = UIBezierPath()
        path.usesEvenOddFillRule = false
        var t = Tokens(Array(data))
        var current = CGPoint.zero
        var start = CGPoint.zero
        var lastControl: CGPoint?    // second control point of the previous C or S, for S

        while let command = t.command() {
            let relative = command.isLowercase
            var first = true
            repeat {
                let origin = relative ? current : .zero
                var control: CGPoint?
                switch command.uppercased() {
                case "M":
                    guard let p = t.point(origin) else { return path }
                    // Pairs after the first of an M are line-tos.
                    if first { path.move(to: p); start = p } else { path.addLine(to: p) }
                    current = p
                case "L":
                    guard let p = t.point(origin) else { return path }
                    path.addLine(to: p)
                    current = p
                case "H":
                    guard let x = t.number() else { return path }
                    current = CGPoint(x: x + origin.x, y: current.y)
                    path.addLine(to: current)
                case "V":
                    guard let y = t.number() else { return path }
                    current = CGPoint(x: current.x, y: y + origin.y)
                    path.addLine(to: current)
                case "C":
                    guard let c1 = t.point(origin), let c2 = t.point(origin), let p = t.point(origin) else { return path }
                    path.addCurve(to: p, controlPoint1: c1, controlPoint2: c2)
                    control = c2
                    current = p
                case "S":
                    // The first control point mirrors the last curve's second one.
                    let c1 = lastControl.map { CGPoint(x: 2 * current.x - $0.x, y: 2 * current.y - $0.y) } ?? current
                    guard let c2 = t.point(origin), let p = t.point(origin) else { return path }
                    path.addCurve(to: p, controlPoint1: c1, controlPoint2: c2)
                    control = c2
                    current = p
                case "Z":
                    path.close()
                    current = start
                default:
                    // Not in the glyph; stop rather than draw something else.
                    return path
                }
                lastControl = control
                first = false
            } while command.uppercased() != "Z" && t.atNumber
        }
        return path
    }

    private struct Tokens {
        let chars: [Character]
        var i = 0

        init(_ chars: [Character]) { self.chars = chars }

        mutating func skipSeparators() {
            while i < chars.count, chars[i] == "," || chars[i].isWhitespace { i += 1 }
        }

        mutating func command() -> Character? {
            skipSeparators()
            guard i < chars.count, chars[i].isLetter else { return nil }
            defer { i += 1 }
            return chars[i]
        }

        var atNumber: Bool {
            mutating get {
                skipSeparators()
                guard i < chars.count else { return false }
                let c = chars[i]
                return c == "-" || c == "+" || c == "." || (c.isASCII && c.isNumber)
            }
        }

        mutating func number() -> CGFloat? {
            skipSeparators()
            let from = i
            if i < chars.count, chars[i] == "-" || chars[i] == "+" { i += 1 }
            var dot = false
            while i < chars.count {
                let c = chars[i]
                if c.isASCII && c.isNumber {
                    i += 1
                } else if c == ".", !dot {
                    dot = true
                    i += 1
                } else {
                    break
                }
            }
            guard i > from, let value = Double(String(chars[from..<i])) else {
                i = from
                return nil
            }
            return CGFloat(value)
        }

        mutating func point(_ origin: CGPoint) -> CGPoint? {
            guard let x = number(), let y = number() else { return nil }
            return CGPoint(x: x + origin.x, y: y + origin.y)
        }
    }
}
