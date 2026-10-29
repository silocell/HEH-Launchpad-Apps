// Rocket Rush in SpriteKit - the same rules as the web and Android versions.
// © 2026 silocell. All rights reserved.
//
// Hold to fly, let go to fall. Zappers, asteroids and gates end the run;
// stars are 5 points; power-ups are shield, magnet and boost; the zone
// changes every 1,500 m. iPad and Mac get the RICH look (glow, nebula
// clouds, more particles); iPhone gets LIGHT, to stay smooth and save battery.
//
// World units: the screen is always 1000 units tall, y pointing DOWN like
// the other versions; P(x, y) turns that into SpriteKit's y-up points.
import SpriteKit
import UIKit

final class RushScene: SKScene {
    struct Quality { let maxParts: Int; let bgStars: Int; let glow: Bool; let exhaust: Int; let nebula: Bool }
    static let light = Quality(maxParts: 110, bgStars: 70, glow: false, exhaust: 1, nebula: false)
    static let rich = Quality(maxParts: 300, bgStars: 170, glow: true, exhaust: 3, nebula: true)

    struct Zone { let name: String; let top: UIColor; let bot: UIColor; let zap: UIColor; let star: UIColor; let planet: UIColor }
    static func c(_ hex: UInt32) -> UIColor { UIColor(red: CGFloat((hex >> 16) & 255) / 255, green: CGFloat((hex >> 8) & 255) / 255, blue: CGFloat(hex & 255) / 255, alpha: 1) }
    let zones: [Zone] = [
        Zone(name: "Launch Bay", top: c(0x0B1030), bot: c(0x1B1F4F), zap: c(0x38BDF8), star: c(0xC7D2FE), planet: c(0x6366F1)),
        Zone(name: "Asteroid Belt", top: c(0x1A0F0A), bot: c(0x3B2414), zap: c(0xFB923C), star: c(0xFDE68A), planet: c(0xB45309)),
        Zone(name: "Pink Nebula", top: c(0x2A0A2E), bot: c(0x5B1150), zap: c(0xF472B6), star: c(0xFBCFE8), planet: c(0xDB2777)),
        Zone(name: "Ion Storm", top: c(0x04201F), bot: c(0x0B3B3A), zap: c(0x2DD4BF), star: c(0x99F6E4), planet: c(0x0D9488)),
        Zone(name: "Deep Space", top: c(0x020208), bot: c(0x0C0C22), zap: c(0xA78BFA), star: c(0xFFFFFF), planet: c(0x4C1D95))
    ]

    enum Kind { case star, zap, rock, gate, pu }
    enum Pu: CaseIterable { case shield, magnet, boost }
    final class Thing {
        let k: Kind; var x: CGFloat; var y: CGFloat
        var r: CGFloat = 0, t: CGFloat = 0, len: CGFloat = 0, a: CGFloat = 0, spin: CGFloat = 0, vy: CGFloat = 0, va: CGFloat = 0
        var w: CGFloat = 70, gapY: CGFloat = 0, gap: CGFloat = 0, pu: Pu = .shield, dead = false
        var node = SKNode()
        var bolt: SKShapeNode?
        init(_ k: Kind, _ x: CGFloat, _ y: CGFloat) { self.k = k; self.x = x; self.y = y }
    }
    final class Part { let node: SKSpriteNode; var x: CGFloat; var y: CGFloat; var vx: CGFloat; var vy: CGFloat; var t: CGFloat; let t0: CGFloat; let s: CGFloat
        init(_ n: SKSpriteNode, _ x: CGFloat, _ y: CGFloat, _ vx: CGFloat, _ vy: CGFloat, _ t: CGFloat, _ t0: CGFloat, _ s: CGFloat) { node = n; self.x = x; self.y = y; self.vx = vx; self.vy = vy; self.t = t; self.t0 = t0; self.s = s } }

    let q: Quality
    unowned let model: RushModel
    let onClose: () -> Void

    let H: CGFloat = 1000
    var W: CGFloat = 1600
    var sc: CGFloat = 1
    let M: CGFloat = 40
    let zoneLen = 1500

    // the world
    let world = SKNode()
    let bg = SKSpriteNode()
    let layerNode = SKNode()
    let planet = SKNode()
    let thingsNode = SKNode()
    let partsNode = SKNode()
    let rocket = SKNode()
    let flame = SKShapeNode()
    let shield = SKShapeNode(circleOfRadius: 58)
    let hud = SKNode()
    var bgStars: [(SKSpriteNode, CGFloat, CGFloat, CGFloat, CGFloat)] = []   // node, x, y, size, speed
    var nebulas: [SKSpriteNode] = []
    var dotTex: SKTexture!
    var starTex: SKTexture!
    var glowTex: SKTexture!

    // state
    var rx: CGFloat = 0, ry: CGFloat = 500, vy: CGFloat = 0, tilt: CGFloat = 0, thrust = false
    let rr: CGFloat = 34
    var flying = false, dead = false, isPaused2 = false
    var dist: CGFloat = 0, speed: CGFloat = 0, starsGot = 0, zoneIx = -1, shake: CGFloat = 0
    var things: [Thing] = []
    var parts: [Part] = []
    var nextAt: CGFloat = 0
    var shieldOn = false, magnetT: CGFloat = 0, boostT: CGFloat = 0, invulnT: CGFloat = 0, puGap: CGFloat = 0
    var last: TimeInterval = 0
    var zoneBanner: CGFloat = 0

    // HUD
    let distL = SKLabelNode(), starL = SKLabelNode(), puL = SKLabelNode(), timeL = SKLabelNode()
    let zoneSmall = SKLabelNode(), zoneBig = SKLabelNode(), hint = SKLabelNode()
    let closeB = SKShapeNode(circleOfRadius: 20)
    let haptic = UIImpactFeedbackGenerator(style: .heavy)
    let tap = UIImpactFeedbackGenerator(style: .light)

    init(size: CGSize, model: RushModel, onClose: @escaping () -> Void) {
        let idiom = UIDevice.current.userInterfaceIdiom
        q = (idiom == .pad || idiom == .mac) ? RushScene.rich : RushScene.light
        self.model = model
        self.onClose = onClose
        super.init(size: size)
        scaleMode = .resizeFill
        backgroundColor = .black
    }
    required init?(coder: NSCoder) { fatalError() }

    func P(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: H - y) }
    func rnd(_ a: CGFloat, _ b: CGFloat) -> CGFloat { a + CGFloat.random(in: 0...1) * (b - a) }
    var meters: Int { Int(dist / M) }
    var score: Int { meters + starsGot * 5 }
    var diff: CGFloat { min(1, CGFloat(meters) / 4000) }
    var zone: Zone { zones[max(0, zoneIx) % zones.count] }

    override func didMove(to view: SKView) {
        view.isMultipleTouchEnabled = true
        dotTex = Self.circleTexture(16)
        glowTex = Self.glowTexture(64)
        starTex = Self.starTexture()
        bg.anchorPoint = .zero; bg.zPosition = -10
        addChild(bg)
        world.zPosition = 0
        addChild(world)
        for n in [planet, layerNode, thingsNode, partsNode, rocket] { world.addChild(n) }
        planet.zPosition = -5; layerNode.zPosition = -4; thingsNode.zPosition = 1; rocket.zPosition = 3; partsNode.zPosition = 4
        buildRocket()
        buildBackground()
        buildHUD()
        hud.zPosition = 10
        addChild(hud)
        resized(size)
        reset()
        setZone(0)
        haptic.prepare()
    }

    func resized(_ s: CGSize) {
        guard s.height > 0 else { return }
        size = s
        sc = s.height / H
        W = s.width / sc
        world.setScale(sc)
        bg.size = s
        if !flying { rx = max(220, W * 0.24) }
        layoutHUD()
    }

    // ---------------- textures ----------------
    static func image(_ size: CGSize, _ draw: (CGContext) -> Void) -> UIImage {
        UIGraphicsImageRenderer(size: size).image { draw($0.cgContext) }
    }
    static func circleTexture(_ d: CGFloat) -> SKTexture {
        SKTexture(image: image(CGSize(width: d, height: d)) { c in c.setFillColor(UIColor.white.cgColor); c.fillEllipse(in: CGRect(x: 0, y: 0, width: d, height: d)) })
    }
    static func glowTexture(_ d: CGFloat) -> SKTexture {
        SKTexture(image: image(CGSize(width: d, height: d)) { c in
            let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [UIColor.white.withAlphaComponent(0.9).cgColor, UIColor.white.withAlphaComponent(0).cgColor] as CFArray, locations: [0, 1])!
            c.drawRadialGradient(g, startCenter: CGPoint(x: d / 2, y: d / 2), startRadius: 0, endCenter: CGPoint(x: d / 2, y: d / 2), endRadius: d / 2, options: [])
        })
    }
    static func starTexture() -> SKTexture {
        SKTexture(image: image(CGSize(width: 48, height: 48)) { c in
            let p = UIBezierPath()
            for i in 0..<10 {
                let r: CGFloat = i % 2 == 1 ? 9 : 21, a = CGFloat(i) * .pi / 5 - .pi / 2
                let pt = CGPoint(x: 24 + cos(a) * r, y: 24 + sin(a) * r)
                if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
            }
            p.close()
            UIColor(red: 0.99, green: 0.88, blue: 0.28, alpha: 1).setFill(); p.fill()
            UIColor(red: 1, green: 0.98, blue: 0.92, alpha: 1).setFill()
            UIBezierPath(ovalIn: CGRect(x: 17, y: 16, width: 8, height: 8)).fill()
        })
    }
    func gradientImage(_ z: Zone) -> SKTexture {
        SKTexture(image: Self.image(CGSize(width: 8, height: 256)) { c in
            let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [z.top.cgColor, z.bot.cgColor] as CFArray, locations: [0, 1])!
            c.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: 256), options: [])
        })
    }

    // ---------------- building ----------------
    func buildRocket() {
        // shapes drawn in y-up local space, nose pointing right
        let fins = SKShapeNode()
        let fp = CGMutablePath()
        fp.move(to: CGPoint(x: -18, y: 14)); fp.addLine(to: CGPoint(x: -40, y: 34)); fp.addLine(to: CGPoint(x: -34, y: 10)); fp.closeSubpath()
        fp.move(to: CGPoint(x: -18, y: -14)); fp.addLine(to: CGPoint(x: -40, y: -34)); fp.addLine(to: CGPoint(x: -34, y: -10)); fp.closeSubpath()
        fins.path = fp; fins.fillColor = Self.c(0xE11D48); fins.strokeColor = .clear
        let body = SKShapeNode()
        let bp = CGMutablePath()
        bp.move(to: CGPoint(x: 48, y: 0)); bp.addCurve(to: CGPoint(x: -36, y: 14), control1: CGPoint(x: 30, y: 24), control2: CGPoint(x: -10, y: 22))
        bp.addLine(to: CGPoint(x: -36, y: -14)); bp.addCurve(to: CGPoint(x: 48, y: 0), control1: CGPoint(x: -10, y: -22), control2: CGPoint(x: 30, y: -24)); bp.closeSubpath()
        body.path = bp; body.fillColor = UIColor(red: 0.93, green: 0.94, blue: 1, alpha: 1); body.strokeColor = .clear
        let nose = SKShapeNode()
        let np = CGMutablePath()
        np.move(to: CGPoint(x: 48, y: 0)); np.addCurve(to: CGPoint(x: 24, y: 16), control1: CGPoint(x: 40, y: 10), control2: CGPoint(x: 30, y: 15))
        np.addLine(to: CGPoint(x: 24, y: -16)); np.addCurve(to: CGPoint(x: 48, y: 0), control1: CGPoint(x: 30, y: -15), control2: CGPoint(x: 40, y: -10)); np.closeSubpath()
        nose.path = np; nose.fillColor = Self.c(0xE11D48); nose.strokeColor = .clear
        let win = SKShapeNode(circleOfRadius: 10); win.position = CGPoint(x: 4, y: 0); win.fillColor = Self.c(0x1E3A8A); win.strokeColor = .clear
        let glass = SKShapeNode(circleOfRadius: 7); glass.position = CGPoint(x: 4, y: 0); glass.fillColor = Self.c(0x38BDF8); glass.strokeColor = .clear
        let shine = SKShapeNode(circleOfRadius: 2.5); shine.position = CGPoint(x: 1.5, y: 2.5); shine.fillColor = UIColor.white.withAlphaComponent(0.8); shine.strokeColor = .clear
        flame.fillColor = Self.c(0xFBBF24); flame.strokeColor = .clear; flame.zPosition = -1
        shield.fillColor = Self.c(0x60A5FA).withAlphaComponent(0.14); shield.strokeColor = Self.c(0x60A5FA).withAlphaComponent(0.9); shield.lineWidth = 4
        shield.position = CGPoint(x: 4, y: 0); shield.isHidden = true
        for n in [flame, fins, body, nose, win, glass, shine, shield] { rocket.addChild(n) }
        rocket.setScale(1.2)
    }

    func buildBackground() {
        let speeds: [CGFloat] = q.nebula ? [0.04, 0.08, 0.22, 0.5] : [0.08, 0.22, 0.5]
        for (k, sp) in speeds.enumerated() {
            for _ in 0..<(q.bgStars / speeds.count + k * 8) {
                let s = SKSpriteNode(texture: dotTex)
                let sz = 1 + CGFloat(k) * 0.9 + CGFloat.random(in: 0...1.2)
                s.size = CGSize(width: sz * 1.4, height: sz * 1.4)
                s.alpha = 0.25 + sp
                layerNode.addChild(s)
                bgStars.append((s, CGFloat.random(in: 0...3000), CGFloat.random(in: 0...1000), sz, sp))
            }
        }
        if q.nebula {
            for _ in 0..<3 {
                let n = SKSpriteNode(texture: glowTex)
                n.size = CGSize(width: 1040, height: 1040); n.alpha = 0.16; n.blendMode = .add
                layerNode.addChild(n); nebulas.append(n)
            }
        }
        let pr: CGFloat = 140
        if q.glow {
            let g = SKSpriteNode(texture: glowTex); g.size = CGSize(width: pr * 3.4, height: pr * 3.4); g.alpha = 0.35; g.name = "glow"
            planet.addChild(g)
        }
        let ball = SKShapeNode(circleOfRadius: pr); ball.strokeColor = .clear; ball.alpha = 0.55; ball.name = "ball"
        planet.addChild(ball)
        let ring = SKShapeNode(ellipseOf: CGSize(width: pr * 3.4, height: pr * 0.64)); ring.fillColor = .clear; ring.lineWidth = 6; ring.alpha = 0.35
        ring.zRotation = 0.35; ring.name = "ring"
        planet.addChild(ring)
    }

    func label(_ l: SKLabelNode, _ size: CGFloat, _ color: UIColor = .white) {
        l.fontName = "AvenirNext-Heavy"; l.fontSize = size; l.fontColor = color; l.verticalAlignmentMode = .center
    }
    func buildHUD() {
        for l in [distL, starL, puL] { label(l, 17); l.horizontalAlignmentMode = .left; hud.addChild(l) }
        label(timeL, 17); timeL.horizontalAlignmentMode = .right; hud.addChild(timeL)
        label(zoneSmall, 14, UIColor(red: 0.65, green: 0.68, blue: 0.81, alpha: 1)); hud.addChild(zoneSmall)
        label(zoneBig, 32); hud.addChild(zoneBig)
        label(hint, 16, UIColor(red: 0.65, green: 0.68, blue: 0.81, alpha: 1)); hint.text = "Hold to fly · let go to drop"; hud.addChild(hint)
        closeB.fillColor = UIColor(red: 0.04, green: 0.05, blue: 0.12, alpha: 0.6); closeB.strokeColor = UIColor.white.withAlphaComponent(0.15); closeB.name = "close"
        let x = SKLabelNode(text: "✕"); label(x, 18); x.name = "close"; closeB.addChild(x)
        hud.addChild(closeB)
    }
    func layoutHUD() {
        let inset = view?.safeAreaInsets ?? .zero
        let top = size.height - max(24, inset.top + 18)
        distL.position = CGPoint(x: 18 + inset.left, y: top)
        starL.position = CGPoint(x: 120 + inset.left, y: top)
        puL.position = CGPoint(x: 200 + inset.left, y: top)
        closeB.position = CGPoint(x: size.width - 30 - inset.right, y: top)
        timeL.position = CGPoint(x: size.width - 62 - inset.right, y: top)
        zoneSmall.position = CGPoint(x: size.width / 2, y: size.height * 0.68)
        zoneBig.position = CGPoint(x: size.width / 2, y: size.height * 0.68 - 34)
        hint.position = CGPoint(x: size.width / 2, y: 30 + inset.bottom)
    }

    func setZone(_ z: Int) {
        zoneIx = z
        let zn = zone
        bg.texture = gradientImage(zn)
        for (s, _, _, _, _) in bgStars { s.color = zn.star; s.colorBlendFactor = 1 }
        for n in nebulas { n.color = zn.zap; n.colorBlendFactor = 1 }
        (planet.childNode(withName: "ball") as? SKShapeNode)?.fillColor = zn.planet
        (planet.childNode(withName: "ring") as? SKShapeNode)?.strokeColor = zn.star
        if let g = planet.childNode(withName: "glow") as? SKSpriteNode { g.color = zn.planet; g.colorBlendFactor = 1 }
        for t in things where t.k == .zap || t.k == .gate { recolor(t) }
    }

    // ---------------- run control ----------------
    func reset() {
        rx = max(220, W * 0.24); ry = 520; vy = 0; tilt = 0; thrust = false
        dist = 0; speed = 430; starsGot = 0; shake = 0
        for t in things { t.node.removeFromParent() }
        things.removeAll()
        for p in parts { p.node.removeFromParent() }
        parts.removeAll()
        nextAt = W + 300
        shieldOn = false; magnetT = 0; boostT = 0; invulnT = 1.2; puGap = 2200
        rocket.isHidden = false
        if zoneIx != 0 { setZone(0) }
    }
    func startRun() {
        reset(); flying = true; dead = false; isPaused2 = false
        zoneBanner = 1.8
        showBanner()
    }
    func paused(_ p: Bool) { isPaused2 = p; if p { thrust = false }; last = 0 }

    func showBanner() {
        zoneSmall.text = "ZONE \(zoneIx + 1)"; zoneBig.text = zone.name; zoneBig.fontColor = zone.zap
    }

    // ---------------- spawning ----------------
    func addStar(_ x: CGFloat, _ y: CGFloat) {
        let t = Thing(.star, x, y); t.r = 22; t.t = CGFloat.random(in: 0...6)
        let s = SKSpriteNode(texture: starTex); s.size = CGSize(width: 48, height: 48)
        if q.glow { let g = SKSpriteNode(texture: glowTex); g.size = CGSize(width: 70, height: 70); g.color = Self.c(0xFDE047); g.colorBlendFactor = 1; g.alpha = 0.35; g.blendMode = .add; t.node.addChild(g) }
        t.node.addChild(s)
        add(t)
    }
    func add(_ t: Thing) { t.node.position = P(t.x, t.y); thingsNode.addChild(t.node); things.append(t) }
    @discardableResult func starLine(_ x: CGFloat, _ y: CGFloat, _ n: Int) -> CGFloat { for i in 0..<n { addStar(x + CGFloat(i) * 60, y) }; return CGFloat(n) * 60 }
    @discardableResult func starWave(_ x: CGFloat, _ y: CGFloat, _ n: Int) -> CGFloat { for i in 0..<n { addStar(x + CGFloat(i) * 58, y + sin(CGFloat(i) / 2) * 120) }; return CGFloat(n) * 58 }

    func zapper(_ x: CGFloat, _ y: CGFloat, _ len: CGFloat, _ ang: CGFloat, _ spin: CGFloat) {
        let t = Thing(.zap, x, y); t.len = len; t.a = ang; t.spin = spin
        let halo = SKShapeNode(); halo.lineWidth = q.glow ? 20 : 6; halo.lineCap = .round; halo.name = "halo"
        let bolt = SKShapeNode(); bolt.strokeColor = .white; bolt.lineWidth = 3; bolt.lineJoin = .round
        t.node.addChild(halo); t.node.addChild(bolt); t.bolt = bolt
        for nm in ["e1", "e2"] {
            let e = SKShapeNode(circleOfRadius: 19); e.name = nm; e.strokeColor = .white; e.lineWidth = 3
            t.node.addChild(e)
        }
        recolor(t); shapeZap(t)
        add(t)
    }
    func recolor(_ t: Thing) {
        let col = zone.zap
        if t.k == .zap {
            (t.node.childNode(withName: "halo") as? SKShapeNode)?.strokeColor = col.withAlphaComponent(q.glow ? 0.3 : 0.55)
            for nm in ["e1", "e2"] { (t.node.childNode(withName: nm) as? SKShapeNode)?.fillColor = col }
        } else if t.k == .gate {
            for nm in ["cap1", "cap2"] { (t.node.childNode(withName: nm) as? SKSpriteNode)?.color = col }
        }
    }
    /// A zapper's line and jagged bolt, in the zapper's own (y-up) space.
    func shapeZap(_ t: Thing) {
        let c = cos(t.a) * t.len / 2, s = -sin(t.a) * t.len / 2   // y flipped
        let a = CGPoint(x: -c, y: -s), b = CGPoint(x: c, y: s)
        let line = CGMutablePath(); line.move(to: a); line.addLine(to: b)
        (t.node.childNode(withName: "halo") as? SKShapeNode)?.path = line
        let n = max(4, Int(t.len / 34)); let nx = -s / (t.len / 2), ny = c / (t.len / 2)
        let p = CGMutablePath(); p.move(to: a)
        for i in 1..<n { let f = CGFloat(i) / CGFloat(n), j = rnd(-12, 12); p.addLine(to: CGPoint(x: a.x + (b.x - a.x) * f + nx * j, y: a.y + (b.y - a.y) * f + ny * j)) }
        p.addLine(to: b)
        t.bolt?.path = p
        t.node.childNode(withName: "e1")?.position = a
        t.node.childNode(withName: "e2")?.position = b
    }
    func rock(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ vy: CGFloat) {
        let t = Thing(.rock, x, y); t.r = r; t.vy = vy; t.a = CGFloat.random(in: 0...6); t.va = rnd(-1, 1)
        let p = CGMutablePath()
        for i in 0..<11 { let a = CGFloat(i) / 11 * 2 * .pi, rr = r * rnd(0.78, 1.08); let pt = CGPoint(x: cos(a) * rr, y: sin(a) * rr); if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) } }
        p.closeSubpath()
        let s = SKShapeNode(path: p); s.fillColor = Self.c(0x78716C); s.strokeColor = Self.c(0x44403C); s.lineWidth = 4
        let c1 = SKShapeNode(circleOfRadius: r * 0.22); c1.position = CGPoint(x: r * 0.25, y: r * 0.2); c1.fillColor = UIColor.black.withAlphaComponent(0.22); c1.strokeColor = .clear
        let c2 = SKShapeNode(circleOfRadius: r * 0.15); c2.position = CGPoint(x: -r * 0.3, y: -r * 0.3); c2.fillColor = UIColor.black.withAlphaComponent(0.22); c2.strokeColor = .clear
        let hi = SKShapeNode(circleOfRadius: r * 0.5); hi.position = CGPoint(x: -r * 0.3, y: r * 0.3); hi.fillColor = UIColor.white.withAlphaComponent(0.08); hi.strokeColor = .clear
        for n in [s, hi, c1, c2] { t.node.addChild(n) }
        add(t)
    }
    func gate(_ x: CGFloat, _ gy: CGFloat, _ gap: CGFloat) {
        let t = Thing(.gate, x, 0); t.gapY = gy; t.gap = gap
        let top = gy - gap / 2, bot = gy + gap / 2
        // local space: node sits at (x, H/2); y-up
        let up = SKSpriteNode(color: Self.c(0x374151), size: CGSize(width: t.w, height: top + 20))
        up.position = CGPoint(x: 0, y: H / 2 - (top - 20) / 2 + 10 - 10 + 0)
        up.position = CGPoint(x: 0, y: (H - (top - 20) / 2 - 20 + 0) - H / 2 + 0 + (20 / 2) - 10)
        // simpler: place by world y converted
        up.position = CGPoint(x: 0, y: (H - (top - 20) / 2 - 20) - H / 2 + 10)
        let dn = SKSpriteNode(color: Self.c(0x374151), size: CGSize(width: t.w, height: H - bot + 20))
        dn.position = CGPoint(x: 0, y: (H - (bot + (H - bot + 20) / 2)) - H / 2)
        let cap1 = SKSpriteNode(color: zone.zap, size: CGSize(width: t.w + 12, height: 14)); cap1.name = "cap1"
        cap1.position = CGPoint(x: 0, y: (H - (top - 7)) - H / 2)
        let cap2 = SKSpriteNode(color: zone.zap, size: CGSize(width: t.w + 12, height: 14)); cap2.name = "cap2"
        cap2.position = CGPoint(x: 0, y: (H - (bot + 7)) - H / 2)
        for n in [up, dn, cap1, cap2] { t.node.addChild(n) }
        if q.glow {
            for cp in [cap1, cap2] { let g = SKSpriteNode(texture: glowTex); g.size = CGSize(width: t.w * 2.4, height: 60); g.color = zone.zap; g.colorBlendFactor = 1; g.alpha = 0.4; g.blendMode = .add; g.position = cp.position; t.node.addChild(g) }
        }
        t.node.position = P(x, H / 2)
        thingsNode.addChild(t.node); things.append(t)
    }
    func power(_ x: CGFloat, _ y: CGFloat) {
        let t = Thing(.pu, x, y); t.r = 34; t.pu = Pu.allCases.randomElement()!
        let col: UIColor = t.pu == .shield ? Self.c(0x60A5FA) : t.pu == .magnet ? Self.c(0xF472B6) : Self.c(0xF97316)
        if q.glow { let g = SKSpriteNode(texture: glowTex); g.size = CGSize(width: 110, height: 110); g.color = col; g.colorBlendFactor = 1; g.alpha = 0.5; g.blendMode = .add; t.node.addChild(g) }
        let ring = SKShapeNode(circleOfRadius: 34); ring.strokeColor = col; ring.lineWidth = 4; ring.fillColor = UIColor.white.withAlphaComponent(0.1)
        let l = SKLabelNode(text: t.pu == .shield ? "🛡" : t.pu == .magnet ? "🧲" : "🔥"); l.fontSize = 34; l.verticalAlignmentMode = .center
        t.node.addChild(ring); t.node.addChild(l)
        add(t)
    }

    func spawnChunk(_ x: CGFloat) -> CGFloat {
        let d = diff
        if puGap <= 0 { power(x + 120, rnd(220, 780)); puGap = rnd(6000, 10000); return 360 }
        let roll = CGFloat.random(in: 0...1)
        var w: CGFloat
        if roll < 0.2 {
            w = Bool.random() ? starLine(x, rnd(200, 800), 8) : starWave(x, rnd(300, 700), 10)
        } else if roll < 0.5 {
            let vert = CGFloat.random(in: 0...1) < 0.45
            let len = rnd(240, 360 + d * 140)
            let y = vert ? rnd(len / 2 + 60, H - len / 2 - 60) : rnd(160, 840)
            let ang: CGFloat = vert ? .pi / 2 : [0, 0.7, -0.7].randomElement()!
            let spin: CGFloat = (d > 0.35 && CGFloat.random(in: 0...1) < d * 0.5) ? rnd(0.6, 1.4) * (Bool.random() ? -1 : 1) : 0
            zapper(x + 120, y, len, ang, spin)
            if CGFloat.random(in: 0...1) < 0.7 { starLine(x + 40, y < 500 ? min(900, y + len / 2 + 110) : max(100, y - len / 2 - 110), 5) }
            w = 520 - d * 120
        } else if roll < 0.7 {
            let gap = 330 - d * 90, gy = rnd(gap / 2 + 90, H - gap / 2 - 90)
            gate(x + 140, gy, gap); starLine(x + 70, gy, 4)
            w = 560 - d * 120
        } else if roll < 0.88 {
            let n = 2 + Int(d * 2.5 + CGFloat.random(in: 0...1.5))
            for i in 0..<n { rock(x + 100 + CGFloat(i) * rnd(150, 230), rnd(120, 880), rnd(38, 64 + d * 20), rnd(-90, 90) * (0.4 + d)) }
            w = 160 + CGFloat(n) * 200
        } else {
            let gp = 320 - d * 70, cy = rnd(gp / 2 + 120, H - gp / 2 - 120)
            zapper(x + 120, (cy - gp / 2) / 2, cy - gp / 2 - 40, .pi / 2, 0)
            zapper(x + 120, cy + gp / 2 + (H - cy - gp / 2) / 2, H - cy - gp / 2 - 40, .pi / 2, 0)
            addStar(x + 120, cy)
            w = 520
        }
        return w + rnd(140, 260) * (1.15 - d * 0.5)
    }

    // ---------------- collisions and effects ----------------
    func segDist(_ px: CGFloat, _ py: CGFloat, _ ax: CGFloat, _ ay: CGFloat, _ bx: CGFloat, _ by: CGFloat) -> CGFloat {
        let dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy
        var t = l2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / l2 : 0
        t = max(0, min(1, t))
        let qx = ax + t * dx - px, qy = ay + t * dy - py
        return sqrt(qx * qx + qy * qy)
    }
    func hits(_ t: Thing) -> Bool {
        let r = rr * 0.82
        switch t.k {
        case .zap:
            let c = cos(t.a) * t.len / 2, s = sin(t.a) * t.len / 2
            return segDist(rx, ry, t.x - c, t.y - s, t.x + c, t.y + s) < r + 14
        case .rock:
            let dx = t.x - rx, dy = t.y - ry, k = t.r * 0.85 + r
            return dx * dx + dy * dy < k * k
        case .gate:
            if rx + r < t.x - t.w / 2 || rx - r > t.x + t.w / 2 { return false }
            return ry - r < t.gapY - t.gap / 2 || ry + r > t.gapY + t.gap / 2
        default: return false
        }
    }
    func spark(_ x: CGFloat, _ y: CGFloat, _ vx: CGFloat, _ vy: CGFloat, _ life: CGFloat, _ t0: CGFloat, _ col: UIColor, _ s: CGFloat) {
        if parts.count >= q.maxParts, let old = parts.first { old.node.removeFromParent(); parts.removeFirst() }
        let n = SKSpriteNode(texture: dotTex); n.color = col; n.colorBlendFactor = 1
        if q.glow { n.blendMode = .add }
        partsNode.addChild(n)
        parts.append(Part(n, x, y, vx, vy, life, t0, s))
    }
    func burst(_ x: CGFloat, _ y: CGFloat, _ col: UIColor, _ n: Int, _ sp: CGFloat) {
        let count = q.glow ? n : n / 2
        for _ in 0..<count { let a = CGFloat.random(in: 0...6.283), v = rnd(0.3, 1) * sp; spark(x, y, cos(a) * v, sin(a) * v, rnd(0.4, 0.9), 1, col, rnd(3, 7)) }
    }
    func popText(_ x: CGFloat, _ y: CGFloat, _ s: String) {
        let l = SKLabelNode(text: s); label(l, 30, Self.c(0xFDE68A)); l.position = P(x, y)
        partsNode.addChild(l)
        l.run(.sequence([.group([.moveBy(x: 0, y: 60, duration: 0.9), .fadeOut(withDuration: 0.9)]), .removeFromParent()]))
    }

    func hit(_ t: Thing) {
        if boostT > 0 || invulnT > 0 { return }
        if shieldOn {
            shieldOn = false; invulnT = 1.2; shake = 14; haptic.impactOccurred()
            burst(rx, ry, Self.c(0x60A5FA), 30, 520)
            if t.k == .rock { t.dead = true; burst(t.x, t.y, Self.c(0x9CA3AF), 24, 380) }
            if t.k == .zap { t.dead = true }
            popText(rx, ry - 60, "Shield saved you!")
            return
        }
        die(t.k == .zap ? "Zapped!" : t.k == .rock ? "Hit an asteroid!" : "Hit a gate!")
    }
    func die(_ why: String) {
        guard flying else { return }
        flying = false; dead = true; thrust = false
        shake = 26; haptic.impactOccurred()
        burst(rx, ry, Self.c(0xFBBF24), 60, 700); burst(rx, ry, Self.c(0xF43F5E), 40, 500)
        if q.nebula {
            let ring = SKShapeNode(circleOfRadius: 20); ring.strokeColor = Self.c(0xFBBF24); ring.lineWidth = 8; ring.position = P(rx, ry)
            partsNode.addChild(ring)
            ring.run(.sequence([.group([.scale(to: 30, duration: 0.8), .fadeOut(withDuration: 0.8)]), .removeFromParent()]))
        }
        rocket.isHidden = true
        model.died(why: why, score: score, meters: meters, stars: starsGot, zone: max(0, zoneIx) + 1)
    }
    func grab(_ t: Thing) {
        burst(t.x, t.y, Self.c(0xA78BFA), 26, 420); tap.impactOccurred()
        switch t.pu {
        case .shield: shieldOn = true; popText(t.x, t.y - 50, "🛡 Shield!")
        case .magnet: magnetT = 8; popText(t.x, t.y - 50, "🧲 Magnet!")
        case .boost: boostT = 3; popText(t.x, t.y - 50, "🔥 Boost!")
        }
    }

    // ---------------- the frame ----------------
    override func update(_ now: TimeInterval) {
        let dt = CGFloat(last == 0 ? 1.0 / 60 : min(0.05, now - last))
        last = now
        if flying && !isPaused2 {
            if model.playing { model.played += Double(dt) }
            step(dt)
        } else if !flying && !dead {
            dist += 120 * dt
            ry = 500 + CGFloat(sin(now * 2)) * 40; tilt = CGFloat(sin(now * 1.4)) * 0.1
        }
        model.checkTime()
        fx(dt)
        render(now)
    }

    func step(_ dt: CGFloat) {
        if invulnT > 0 { invulnT -= dt }
        if magnetT > 0 { magnetT -= dt }
        if boostT > 0 { boostT -= dt; if boostT <= 0 { invulnT = 0.8 } }
        if zoneBanner > 0 { zoneBanner -= dt }
        let target = 430 + min(520, CGFloat(meters) * 0.13)
        speed += (target - speed) * min(1, dt * 0.5)
        let v = speed * (boostT > 0 ? 2.1 : 1), mv = v * dt
        dist += mv; nextAt -= mv; puGap -= mv
        while nextAt < W + 400 { nextAt += spawnChunk(nextAt) }
        let z = meters / zoneLen
        if z != zoneIx { setZone(z); zoneBanner = 1.8; showBanner() }

        let g: CGFloat = 2500, up: CGFloat = 5600
        if boostT > 0 { vy += ((500 - ry) * 6 - vy) * min(1, dt * 4) } else { vy += (thrust ? -up + g : g) * dt }
        vy = max(-950, min(1050, vy))
        ry += vy * dt
        if ry < 50 { ry = 50; vy = max(0, vy) }
        if ry > H - 50 { ry = H - 50; vy = min(0, vy) }
        tilt += ((vy / 1000) * 0.5 - tilt) * min(1, dt * 10)

        if thrust || boostT > 0 {
            let n = boostT > 0 ? q.exhaust + 1 : q.exhaust
            for _ in 0..<n {
                let col = boostT > 0 ? Self.c(0xF97316) : [Self.c(0xFBBF24), Self.c(0xF97316), Self.c(0xFDE68A)].randomElement()!
                spark(rx - 52, ry + rnd(-8, 8), -rnd(300, 600) - v * 0.3, rnd(-60, 60) + 200, rnd(0.2, 0.45), 0.45, col, rnd(5, 10))
            }
        }

        var i = things.count - 1
        while i >= 0 {
            let t = things[i]
            t.x -= mv
            switch t.k {
            case .rock:
                t.y += t.vy * dt; t.a += t.va * dt
                if t.y < t.r || t.y > H - t.r { t.vy = -t.vy }
                if !t.dead && hits(t) { hit(t) }
            case .zap:
                if t.spin != 0 { t.a += t.spin * dt }
                if !t.dead && hits(t) { hit(t) }
            case .gate:
                if hits(t) { hit(t) }
            case .star:
                t.t += dt * 4
                if magnetT > 0 {
                    let dx = rx - t.x, dy = ry - t.y, dd = sqrt(dx * dx + dy * dy)
                    if dd < 360 && dd > 0 { t.x += dx / dd * 900 * dt; t.y += dy / dd * 900 * dt }
                }
                let cx = t.x - rx, cy = t.y - ry, k = t.r + rr + 6
                if cx * cx + cy * cy < k * k { starsGot += 1; burst(t.x, t.y, Self.c(0xFDE047), 10, 260); t.dead = true }
            case .pu:
                t.t += dt
                let px = t.x - rx, py = t.y - ry, k = t.r + rr
                if px * px + py * py < k * k { grab(t); t.dead = true }
            }
            if !flying { return }
            if t.dead || t.x < -500 { t.node.removeFromParent(); things.remove(at: i) }
            i -= 1
        }
    }

    func fx(_ dt: CGFloat) {
        var i = parts.count - 1
        while i >= 0 {
            let p = parts[i]
            p.x += p.vx * dt; p.y += p.vy * dt; p.vy += 400 * dt; p.t -= dt
            if p.t <= 0 { p.node.removeFromParent(); parts.remove(at: i) }
            i -= 1
        }
        if shake > 0 { shake = max(0, shake - 60 * dt) }
    }

    var frameCount = 0
    func render(_ now: TimeInterval) {
        frameCount += 1
        let sx = shake > 0 ? rnd(-shake, shake) : 0, sy = shake > 0 ? rnd(-shake, shake) : 0
        world.position = CGPoint(x: sx * sc, y: -sy * sc)
        // planet and background
        var px = W * 0.78 - dist.truncatingRemainder(dividingBy: W + 600) * 0.02 * 50
        px = W * 0.78 - (dist * 0.02).truncatingRemainder(dividingBy: W + 600)
        if px < -140 { px += W + 600 }
        planet.position = P(px, 300)
        for (s, x, y, sz, sp) in bgStars {
            let xx = ((x - dist * sp).truncatingRemainder(dividingBy: 3000) + 3000).truncatingRemainder(dividingBy: 3000)
            s.isHidden = xx > W + 10
            s.position = P(xx, y + sz / 2)
        }
        for (k, n) in nebulas.enumerated() {
            var nx = (CGFloat(k) * 900 + 300 - dist * (0.03 + CGFloat(k) * 0.015)).truncatingRemainder(dividingBy: W + 1400)
            if nx < -700 { nx += W + 1400 }
            n.position = P(nx, 200 + CGFloat(k) * 280)
        }
        // things
        for t in things {
            switch t.k {
            case .star:
                t.node.position = P(t.x, t.y)
                let s = 1 + sin(t.t) * 0.12
                t.node.setScale(s); t.node.zRotation = -t.t * 0.3
            case .zap:
                t.node.position = P(t.x, t.y)
                if t.spin != 0 || frameCount % (q.glow ? 1 : 3) == 0 { shapeZap(t) }
            case .rock:
                t.node.position = P(t.x, t.y); t.node.zRotation = -t.a
            case .gate:
                t.node.position = P(t.x, H / 2)
            case .pu:
                t.node.position = P(t.x, t.y + sin(t.t * 4) * 6)
            }
        }
        // rocket
        rocket.position = P(rx, ry)
        rocket.zRotation = -tilt
        rocket.alpha = (invulnT > 0 && Int(invulnT * 12) % 2 == 0) ? 0.45 : 1
        let showFlame = (thrust || boostT > 0 || (!flying && !dead))
        flame.isHidden = !showFlame
        if showFlame {
            let fl = (boostT > 0 ? 70 : 40) + CGFloat.random(in: 0...18)
            let p = CGMutablePath(); p.move(to: CGPoint(x: -34, y: 13)); p.addQuadCurve(to: CGPoint(x: -34, y: -13), control: CGPoint(x: -36 - fl, y: 0)); p.closeSubpath()
            flame.path = p
        }
        shield.isHidden = !shieldOn
        if shieldOn { shield.setScale(1 + CGFloat(sin(now * 8)) * 0.05) }
        // particles
        for p in parts {
            let k = max(0, p.t / p.t0)
            p.node.position = P(p.x, p.y)
            let d = p.s * 2 * (k + 0.3)
            p.node.size = CGSize(width: d, height: d)
            p.node.alpha = k
        }
        // HUD
        distL.text = "\(meters) m"
        starL.text = "⭐ \(starsGot)"
        starL.position.x = distL.position.x + distL.frame.width + 18
        var pu: [String] = []
        if shieldOn { pu.append("🛡") }
        if magnetT > 0 { pu.append("🧲 \(Int(magnetT.rounded(.up)))") }
        if boostT > 0 { pu.append("🔥 \(Int(boostT.rounded(.up)))") }
        puL.text = pu.joined(separator: "  ")
        puL.position.x = starL.position.x + starL.frame.width + 18
        timeL.text = model.timeText
        timeL.fontColor = model.timeLow ? Self.c(0xF59E0B) : .white
        let showBanner = flying && zoneBanner > 0
        zoneSmall.isHidden = !showBanner; zoneBig.isHidden = !showBanner
        if showBanner { let a = min(1, zoneBanner); zoneSmall.alpha = a; zoneBig.alpha = a }
        hint.isHidden = !(flying && dist < 40 * M && !thrust)
        let hudOn = flying || dead
        distL.isHidden = !hudOn; starL.isHidden = !hudOn; puL.isHidden = !hudOn
    }

    // ---------------- touch ----------------
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            let p = t.location(in: self)
            if nodes(at: p).contains(where: { $0.name == "close" }) { onClose(); return }
        }
        if flying && !isPaused2 && !model.payOpen { thrust = true }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        let stillDown = event?.allTouches?.contains { $0.phase == .began || $0.phase == .moved || $0.phase == .stationary } ?? false
        if !stillDown { thrust = false }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { thrust = false }

    // keyboard on iPad / Mac: Space or ↑
    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        for p in presses {
            if let k = p.key, k.keyCode == .keyboardSpacebar || k.keyCode == .keyboardUpArrow {
                if flying { thrust = true } else if model.phase != .fly && !model.payOpen && (model.phase == .menu || model.showDeadCard) { model.start() }
                return
            }
        }
        super.pressesBegan(presses, with: event)
    }
    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        for p in presses { if let k = p.key, k.keyCode == .keyboardSpacebar || k.keyCode == .keyboardUpArrow { thrust = false; return } }
        super.pressesEnded(presses, with: event)
    }
}
