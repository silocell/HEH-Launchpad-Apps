// Rocket Rush 3D in SceneKit - the same rules as the web and Android versions.
// © 2026 silocell. All rights reserved.
//
// Fly forward through space and steer (drag, or arrow keys / WASD on iPad
// keyboards and Mac) round zappers, walls and asteroids. Stars are 5 points;
// power-ups are shield, magnet and boost; the zone changes every 1,500 m.
// iPad and Mac get the RICH look (bloom glow, 4x anti-aliasing, more dust
// and sparks); iPhone gets LIGHT, to stay smooth and save battery.
//
// World: the flying space is 12 wide and 7 tall (HW, HH are half of that);
// the rocket stays at z = 0 and everything else comes towards it, drawn at
// z = -(thing.z - dist). 1.5 units = 1 metre on the distance counter.
import SceneKit
import UIKit

final class RushView: SCNView {
    weak var game: RushGame?
    override var canBecomeFirstResponder: Bool { true }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { DispatchQueue.main.async { _ = self.becomeFirstResponder() } }
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let t = touches.first { game?.dragBegan(t.location(in: self)) }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let t = touches.first { game?.dragMoved(t.location(in: self), bounds.size) }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { game?.dragEnded() }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { game?.dragEnded() }
    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var used = false
        for p in presses { if let k = p.key, game?.key(k.keyCode, down: true) == true { used = true } }
        if !used { super.pressesBegan(presses, with: event) }
    }
    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var used = false
        for p in presses { if let k = p.key, game?.key(k.keyCode, down: false) == true { used = true } }
        if !used { super.pressesEnded(presses, with: event) }
    }
    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        game?.keysUp()
        super.pressesCancelled(presses, with: event)
    }
}

final class RushGame: NSObject {
    struct Zone { let name: String; let bg: UInt32; let zap: UInt32; let dust: UInt32; let planet: UInt32 }
    static let zones: [Zone] = [
        Zone(name: "Launch Bay", bg: 0x0B1030, zap: 0x38BDF8, dust: 0xC7D2FE, planet: 0x6366F1),
        Zone(name: "Asteroid Belt", bg: 0x1F120A, zap: 0xFB923C, dust: 0xFDE68A, planet: 0xB45309),
        Zone(name: "Pink Nebula", bg: 0x2A0A2E, zap: 0xF472B6, dust: 0xFBCFE8, planet: 0xDB2777),
        Zone(name: "Ion Storm", bg: 0x04201F, zap: 0x2DD4BF, dust: 0x99F6E4, planet: 0x0D9488),
        Zone(name: "Deep Space", bg: 0x03030C, zap: 0xA78BFA, dust: 0xFFFFFF, planet: 0x7C3AED)
    ]
    static func col(_ h: UInt32, _ a: CGFloat = 1) -> UIColor {
        UIColor(red: CGFloat((h >> 16) & 255) / 255, green: CGFloat((h >> 8) & 255) / 255, blue: CGFloat(h & 255) / 255, alpha: a)
    }

    enum Kind { case star, zap, rock, wall, pu, fx }
    enum Pu: CaseIterable { case shield, magnet, boost }
    final class Thing {
        let k: Kind
        var x: Float, y: Float, z: Float
        let node: SCNNode
        var len: Float = 0, a: Float = 0, spin: Float = 0, r: Float = 0
        var vx: Float = 0, vy: Float = 0, rx: Float = 0, ry: Float = 0
        var hx: Float = 0, hy: Float = 0, hs: Float = 0, t: Float = 0
        var pu: Pu = .shield, dead = false
        var beam: SCNNode?
        init(_ k: Kind, _ x: Float, _ y: Float, _ z: Float, _ node: SCNNode) { self.k = k; self.x = x; self.y = y; self.z = z; self.node = node }
    }

    // the rules
    let HW: Float = 6, HH: Float = 3.5, RR: Float = 0.55, U: Float = 1.5, VIEW: Float = 170
    let zoneLen = 1500

    let rich: Bool
    unowned let model: RushModel
    let view = RushView(frame: .zero)
    let scene = SCNScene()
    let camNode = SCNNode()
    let rocket = SCNNode(), ship = SCNNode()
    var flame = SCNNode(), flameIn = SCNNode(), shieldNode = SCNNode(), magnetNode = SCNNode(), shock = SCNNode()
    let exhaust = SCNParticleSystem()
    var frames: [SCNNode] = [], dustSlabs: [SCNNode] = []
    var frameZ: [Float] = [], slabZ: [Float] = []
    let frameGap: Float = 30, slabLen: Float = 52.5
    var glowImage = UIImage()

    // looks shared by many things (changing the colour changes them all)
    let zapMat = SCNMaterial(), coreMat = SCNMaterial(), trimMat = SCNMaterial(), frameMat = SCNMaterial()
    var wallMat = SCNMaterial(), podMat = SCNMaterial(), starMat = SCNMaterial(), rockMat = SCNMaterial(), dustMat = SCNMaterial()
    var beamGeo = SCNGeometry(), coreGeo = SCNGeometry(), podGeo = SCNGeometry(), wallGeo = SCNGeometry(), trimGeo = SCNGeometry(), frameGeo = SCNGeometry()
    var starGeo = SCNGeometry()
    var rockGeos: [SCNGeometry] = []

    // state
    var rx: Float = 0, ry: Float = 0, tx: Float = 0, ty: Float = 0, vx: Float = 0, vy: Float = 0
    var flying = false, dead = false, isPaused2 = false
    var dist: Float = 0, speed: Float = 16.1, starsGot = 0, zoneIx = 0, shake: Float = 0
    var things: [Thing] = []
    var nextZ: Float = 60
    var shieldOn = false, magnetT: Float = 0, boostT: Float = 0, invulnT: Float = 0, puGap: Float = 300
    var keys: [String: Float] = ["l": 0, "r": 0, "u": 0, "d": 0]
    var dragStart: (CGPoint, Float, Float)?
    var link: CADisplayLink?
    var last: CFTimeInterval = 0
    var frameCount = 0
    var deadAt: CFTimeInterval = 0
    var shockAge: Float = -1
    var lastAspect: CGFloat = 0
    let haptic = UIImpactFeedbackGenerator(style: .heavy)
    let tapFeel = UIImpactFeedbackGenerator(style: .light)

    init(model: RushModel) {
        let idiom = UIDevice.current.userInterfaceIdiom
        rich = idiom == .pad || idiom == .mac
        self.model = model
        super.init()
        view.game = self
        view.scene = scene
        view.backgroundColor = .black
        view.antialiasingMode = rich ? .multisampling4X : .multisampling2X
        view.preferredFramesPerSecond = 60
        view.rendersContinuously = true
        view.isPlaying = true
        view.isMultipleTouchEnabled = false
        build()
        reset()
        setZone(0, banner: false)
        haptic.prepare()
        link = CADisplayLink(target: self, selector: #selector(frame(_:)))
        link?.add(to: .main, forMode: .common)
    }

    func stop() { link?.invalidate(); link = nil }

    // ---------------- helpers ----------------
    func rnd(_ a: Float, _ b: Float) -> Float { a + Float.random(in: 0...1) * (b - a) }
    var meters: Int { Int(dist / U) }
    var score: Int { meters + starsGot * 5 }
    var diff: Float { min(1, Float(meters) / 4000) }
    var speedNow: Float { speed * (boostT > 0 ? 2.1 : 1) }
    var zone: Zone { Self.zones[zoneIx % Self.zones.count] }

    func lit(_ hex: UInt32, metal: CGFloat = 0.25, rough: CGFloat = 0.45, emit: UInt32? = nil, emitAmount: CGFloat = 0) -> SCNMaterial {
        let m = SCNMaterial()
        m.lightingModel = rich ? .physicallyBased : .blinn
        m.diffuse.contents = Self.col(hex)
        m.metalness.contents = metal
        m.roughness.contents = rough
        if let e = emit { m.emission.contents = Self.col(e); m.emission.intensity = emitAmount }
        return m
    }
    func glowy(_ m: SCNMaterial, _ hex: UInt32, _ alpha: CGFloat) {
        m.lightingModel = .constant
        m.diffuse.contents = Self.col(hex, alpha)
        m.blendMode = .add
        m.writesToDepthBuffer = false
        m.isDoubleSided = true
    }
    func node(_ g: SCNGeometry, _ x: Float = 0, _ y: Float = 0, _ z: Float = 0) -> SCNNode {
        let n = SCNNode(geometry: g); n.position = SCNVector3(x, y, z); return n
    }

    static func glowTexture() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64)).image { ctx in
            let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                               colors: [UIColor.white.cgColor, UIColor.white.withAlphaComponent(0.55).cgColor, UIColor.white.withAlphaComponent(0).cgColor] as CFArray,
                               locations: [0, 0.25, 1])!
            ctx.cgContext.drawRadialGradient(g, startCenter: CGPoint(x: 32, y: 32), startRadius: 0, endCenter: CGPoint(x: 32, y: 32), endRadius: 32, options: [])
        }
    }

    /// A lumpy asteroid: an icosahedron (split once on iPad/Mac), each corner
    /// pushed in or out a little, with flat faces.
    static func rockGeometry(detail: Bool, material: SCNMaterial) -> SCNGeometry {
        let t = Float((1 + 5.0.squareRoot()) / 2)
        var v: [SIMD3<Float>] = [[-1, t, 0], [1, t, 0], [-1, -t, 0], [1, -t, 0], [0, -1, t], [0, 1, t], [0, -1, -t], [0, 1, -t], [t, 0, -1], [t, 0, 1], [-t, 0, -1], [-t, 0, 1]].map { simd_normalize($0) }
        var f: [(Int, Int, Int)] = [(0, 11, 5), (0, 5, 1), (0, 1, 7), (0, 7, 10), (0, 10, 11), (1, 5, 9), (5, 11, 4), (11, 10, 2), (10, 7, 6), (7, 1, 8),
                                    (3, 9, 4), (3, 4, 2), (3, 2, 6), (3, 6, 8), (3, 8, 9), (4, 9, 5), (2, 4, 11), (6, 2, 10), (8, 6, 7), (9, 8, 1)]
        if detail {
            var cache: [Int: Int] = [:]
            func mid(_ a: Int, _ b: Int) -> Int {
                let key = min(a, b) * 1000 + max(a, b)
                if let i = cache[key] { return i }
                v.append(simd_normalize((v[a] + v[b]) / 2)); cache[key] = v.count - 1; return v.count - 1
            }
            var nf: [(Int, Int, Int)] = []
            for (a, b, c) in f {
                let ab = mid(a, b), bc = mid(b, c), ca = mid(c, a)
                nf += [(a, ab, ca), (b, bc, ab), (c, ca, bc), (ab, bc, ca)]
            }
            f = nf
        }
        for i in 0..<v.count { v[i] *= Float.random(in: 0.75...1.13) }
        var pos: [SCNVector3] = [], nor: [SCNVector3] = [], idx: [Int32] = []
        for (a, b, c) in f {
            let n = simd_normalize(simd_cross(v[b] - v[a], v[c] - v[a]))
            for p in [v[a], v[b], v[c]] {
                pos.append(SCNVector3(p.x, p.y, p.z)); nor.append(SCNVector3(n.x, n.y, n.z)); idx.append(Int32(idx.count))
            }
        }
        let g = SCNGeometry(sources: [SCNGeometrySource(vertices: pos), SCNGeometrySource(normals: nor)],
                            elements: [SCNGeometryElement(indices: idx, primitiveType: .triangles)])
        g.materials = [material]
        return g
    }

    /// The sky behind everything: a gradient, far-away stars and the
    /// zone's planet. (Drawn as the scene background so fog doesn't hide it.)
    func backdrop(_ z: Zone) -> UIImage {
        let aspect = max(0.4, min(3, lastAspect > 0 ? lastAspect : 1.6))
        let size = CGSize(width: 900 * aspect, height: 900)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let c = ctx.cgContext
            let bg = Self.col(z.bg)
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            bg.getRed(&r, green: &g, blue: &b, alpha: &a)
            let top = UIColor(red: r * 0.7, green: g * 0.7, blue: b * 0.7, alpha: 1)
            let bottom = UIColor(red: min(1, r * 1.25 + 0.02), green: min(1, g * 1.25 + 0.02), blue: min(1, b * 1.25 + 0.04), alpha: 1)
            let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [top.cgColor, bg.cgColor, bottom.cgColor] as CFArray, locations: [0, 0.5, 1])!
            c.drawLinearGradient(grad, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            for _ in 0..<(rich ? 260 : 140) {
                let s = CGFloat.random(in: 0.8...2.4)
                c.setFillColor(UIColor.white.withAlphaComponent(CGFloat.random(in: 0.2...0.8)).cgColor)
                c.fillEllipse(in: CGRect(x: CGFloat.random(in: 0...size.width), y: CGFloat.random(in: 0...size.height), width: s, height: s))
            }
            let pc = CGPoint(x: size.width * 0.8, y: size.height * 0.2), pr: CGFloat = 80
            let pcol = Self.col(z.planet)
            let pg = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [UIColor.white.withAlphaComponent(0.85).cgColor, pcol.cgColor, UIColor(red: 0.02, green: 0.02, blue: 0.06, alpha: 1).cgColor] as CFArray, locations: [0, 0.25, 1])!
            c.saveGState()
            c.addEllipse(in: CGRect(x: pc.x - pr, y: pc.y - pr, width: pr * 2, height: pr * 2)); c.clip()
            c.drawRadialGradient(pg, startCenter: CGPoint(x: pc.x - pr * 0.45, y: pc.y - pr * 0.45), startRadius: pr * 0.1, endCenter: pc, endRadius: pr * 1.1, options: [])
            c.restoreGState()
            c.saveGState()
            c.translateBy(x: pc.x, y: pc.y); c.rotate(by: -0.35)
            c.setStrokeColor(Self.col(z.dust, 0.4).cgColor); c.setLineWidth(7)
            c.strokeEllipse(in: CGRect(x: -pr * 1.75, y: -pr * 0.33, width: pr * 3.5, height: pr * 0.66))
            c.restoreGState()
        }
    }

    // ---------------- building the world ----------------
    func build() {
        glowImage = Self.glowTexture()
        scene.fogStartDistance = 70
        scene.fogEndDistance = CGFloat(VIEW + 5)

        let cam = SCNCamera()
        cam.zNear = 0.1; cam.zFar = 400
        cam.projectionDirection = .vertical
        if rich { cam.wantsHDR = true; cam.bloomIntensity = 1.1; cam.bloomThreshold = 0.75; cam.bloomBlurRadius = 10; cam.wantsExposureAdaptation = false }
        camNode.camera = cam
        scene.rootNode.addChildNode(camNode)
        view.pointOfView = camNode

        let amb = SCNNode(); amb.light = SCNLight(); amb.light!.type = .ambient; amb.light!.color = UIColor(red: 0.55, green: 0.58, blue: 0.75, alpha: 1); amb.light!.intensity = rich ? 450 : 600
        let sun = SCNNode(); sun.light = SCNLight(); sun.light!.type = .directional; sun.light!.intensity = rich ? 1500 : 1000
        sun.eulerAngles = SCNVector3(-0.8, 0.5, 0)
        let rim = SCNNode(); rim.light = SCNLight(); rim.light!.type = .directional; rim.light!.color = Self.col(0x93C5FD); rim.light!.intensity = 400
        rim.eulerAngles = SCNVector3(0.3, Float.pi * 0.85, 0)
        for n in [amb, sun, rim] { scene.rootNode.addChildNode(n) }

        // shared looks
        glowy(zapMat, Self.zones[0].zap, 0.8)
        coreMat.lightingModel = .constant; coreMat.diffuse.contents = UIColor.white
        trimMat.lightingModel = .constant; trimMat.diffuse.contents = Self.col(Self.zones[0].zap)
        glowy(frameMat, Self.zones[0].zap, 0.35)
        wallMat = lit(0x4B5563, metal: 0.55, rough: 0.5, emit: 0x111827, emitAmount: 1)
        podMat = lit(0x1F2937, metal: 0.7, rough: 0.3, emit: Self.zones[0].zap, emitAmount: 0.5)
        starMat = lit(0xFDE047, metal: 0.5, rough: 0.3, emit: 0xF59E0B, emitAmount: 0.6)
        rockMat = lit(0x8B8178, metal: 0, rough: 0.95)
        dustMat.lightingModel = .constant; dustMat.diffuse.contents = Self.col(Self.zones[0].dust)

        let beam = SCNCylinder(radius: 0.16, height: 1); beam.radialSegmentCount = 10; beam.materials = [zapMat]; beamGeo = beam
        let core = SCNCylinder(radius: 0.05, height: 1); core.radialSegmentCount = 6; core.materials = [coreMat]; coreGeo = core
        let pod = SCNSphere(radius: 0.42); pod.segmentCount = rich ? 18 : 12; pod.materials = [podMat]; podGeo = pod
        let wb = SCNBox(width: 1, height: 1, length: 1, chamferRadius: 0); wb.materials = [wallMat]; wallGeo = wb
        let tb = SCNBox(width: 1, height: 1, length: 1, chamferRadius: 0); tb.materials = [trimMat]; trimGeo = tb
        let fb = SCNBox(width: 1, height: 1, length: 1, chamferRadius: 0); fb.materials = [frameMat]; frameGeo = fb
        let sp = UIBezierPath()
        for i in 0..<10 {
            let r: CGFloat = i % 2 == 1 ? 0.22 : 0.55, a = CGFloat(i) * .pi / 5 + .pi / 2
            let p = CGPoint(x: cos(a) * r, y: sin(a) * r)
            if i == 0 { sp.move(to: p) } else { sp.addLine(to: p) }
        }
        sp.close(); sp.flatness = 0.01
        let st = SCNShape(path: sp, extrusionDepth: 0.16); st.chamferRadius = 0.04; st.materials = [starMat]; starGeo = st
        rockGeos = (0..<4).map { _ in Self.rockGeometry(detail: rich, material: rockMat) }

        buildRocket()

        // flying-space frames that stream past, to feel the speed
        for i in 0..<(rich ? 8 : 5) {
            let g = SCNNode(), t: Float = 0.08, w = HW + 0.9, h = HH + 0.9
            for b in [(Float(0), h, w * 2 + t, t), (0, -h, w * 2 + t, t), (w, 0, t, h * 2), (-w, 0, t, h * 2)] {
                let m = node(frameGeo, b.0, b.1, 0); m.scale = SCNVector3(b.2, b.3, t); g.addChildNode(m)
            }
            frameZ.append(Float(i) * frameGap + 10)
            frames.append(g); scene.rootNode.addChildNode(g)
        }

        // space dust in slabs that wrap round
        let per = (rich ? 2200 : 700) / 8
        for i in 0..<8 {
            var pts: [SCNVector3] = [], idx: [Int32] = []
            for k in 0..<per {
                pts.append(SCNVector3(rnd(-80, 80), rnd(-50, 50), rnd(-slabLen, 0))); idx.append(Int32(k))
            }
            let el = SCNGeometryElement(indices: idx, primitiveType: .point)
            el.pointSize = rich ? 3 : 2.5
            el.minimumPointScreenSpaceRadius = 0.8
            el.maximumPointScreenSpaceRadius = rich ? 3 : 2.5
            let g = SCNGeometry(sources: [SCNGeometrySource(vertices: pts)], elements: [el])
            g.materials = [dustMat]
            let n = SCNNode(geometry: g)
            slabZ.append(Float(i) * slabLen)
            dustSlabs.append(n); scene.rootNode.addChildNode(n)
        }

        // the shockwave when you crash (iPad and Mac)
        let ring = SCNTube(innerRadius: 0.9, outerRadius: 1, height: 0.02)
        let rm = SCNMaterial(); glowy(rm, 0xFBBF24, 1); ring.materials = [rm]
        shock = SCNNode(geometry: ring); shock.eulerAngles.x = .pi / 2; shock.isHidden = true
        scene.rootNode.addChildNode(shock)
    }

    func buildRocket() {
        let white = lit(0xF8FAFC, metal: 0.35, rough: 0.35), red = lit(0xE11D48, metal: 0.3, rough: 0.4)
        let body = SCNCylinder(radius: 0.46, height: 1.5); body.radialSegmentCount = 20; body.materials = [white]
        let b = node(body); b.eulerAngles.x = .pi / 2; ship.addChildNode(b)
        let nose = SCNCone(topRadius: 0, bottomRadius: 0.46, height: 0.9); nose.radialSegmentCount = 20; nose.materials = [red]
        let n = node(nose, 0, 0, -1.2); n.eulerAngles.x = -.pi / 2; ship.addChildNode(n)
        let tail = SCNCylinder(radius: 0.33, height: 0.28); tail.materials = [lit(0x475569, metal: 0.8, rough: 0.3)]
        let tl = node(tail, 0, 0, 0.88); tl.eulerAngles.x = .pi / 2; ship.addChildNode(tl)
        let win = SCNSphere(radius: 0.2); win.materials = [lit(0x38BDF8, metal: 0.1, rough: 0.1, emit: 0x1D4ED8, emitAmount: 0.8)]
        let w = node(win, 0, 0.3, -0.35); w.scale = SCNVector3(1, 0.7, 1); ship.addChildNode(w)
        let fin = SCNBox(width: 0.06, height: 0.55, length: 0.7, chamferRadius: 0.01); fin.materials = [red]
        for i in 0..<4 {
            let a = Float(i) * .pi / 2 + .pi / 4
            let f = node(fin, cos(a) * 0.55, sin(a) * 0.55, 0.5); f.eulerAngles.z = a - .pi / 2; ship.addChildNode(f)
        }
        let fm = SCNMaterial(); glowy(fm, 0xFBBF24, 0.85)
        let fc = SCNCone(topRadius: 0.3, bottomRadius: 0, height: 1.4); fc.materials = [fm]
        flame = node(fc, 0, 0, 1.6); flame.eulerAngles.x = .pi / 2; ship.addChildNode(flame)
        let fim = SCNMaterial(); glowy(fim, 0xFFF7ED, 0.9)
        let fci = SCNCone(topRadius: 0.16, bottomRadius: 0, height: 0.9); fci.materials = [fim]
        flameIn = node(fci, 0, 0, 1.35); flameIn.eulerAngles.x = .pi / 2; ship.addChildNode(flameIn)
        rocket.addChildNode(ship)

        let sm = SCNMaterial(); glowy(sm, 0x60A5FA, 0.25)
        let s = SCNSphere(radius: 1.25); s.materials = [sm]
        shieldNode = SCNNode(geometry: s)
        let wm = SCNMaterial(); glowy(wm, 0x93C5FD, 0.5); wm.fillMode = .lines
        let wire = SCNSphere(radius: 1.3); wire.isGeodesic = true; wire.segmentCount = 2; wire.materials = [wm]
        shieldNode.addChildNode(SCNNode(geometry: wire))
        rocket.addChildNode(shieldNode)
        let mm = SCNMaterial(); glowy(mm, 0xF472B6, 0.7)
        let tor = SCNTorus(ringRadius: 1.6, pipeRadius: 0.05); tor.materials = [mm]
        magnetNode = SCNNode(geometry: tor); rocket.addChildNode(magnetNode)

        // exhaust sparks, streaming out behind
        exhaust.particleImage = glowImage
        exhaust.birthRate = rich ? 220 : 90
        exhaust.particleLifeSpan = 0.35; exhaust.particleLifeSpanVariation = 0.1
        exhaust.particleSize = 0.22; exhaust.particleSizeVariation = 0.1
        exhaust.particleColor = Self.col(0xFBBF24); exhaust.particleColorVariation = SCNVector4(0.05, 0.1, 0.2, 0)
        exhaust.blendMode = .additive
        exhaust.emitterShape = SCNSphere(radius: 0.12)
        exhaust.emittingDirection = SCNVector3(0, 0, 1)
        exhaust.spreadingAngle = 8
        exhaust.particleVelocity = 14
        exhaust.isAffectedByGravity = false
        exhaust.propertyControllers = [.opacity: fade()]
        let tailNode = SCNNode(); tailNode.position = SCNVector3(0, 0, 1.3)
        tailNode.addParticleSystem(exhaust)
        ship.addChildNode(tailNode)
        scene.rootNode.addChildNode(rocket)
    }
    func fade() -> SCNParticlePropertyController {
        let a = CABasicAnimation(); a.fromValue = 1.0; a.toValue = 0.0
        return SCNParticlePropertyController(animation: a)
    }

    // ---------------- zones ----------------
    func setZone(_ ix: Int, banner: Bool = true) {
        zoneIx = ix
        let z = zone
        scene.background.contents = backdrop(z)
        scene.fogColor = Self.col(z.bg)
        zapMat.diffuse.contents = Self.col(z.zap, 0.8)
        trimMat.diffuse.contents = Self.col(z.zap)
        frameMat.diffuse.contents = Self.col(z.zap, 0.35)
        podMat.emission.contents = Self.col(z.zap)
        dustMat.diffuse.contents = Self.col(z.dust)
        if banner {
            model.showBanner(small: "ZONE \(ix + 1)", big: z.name, color: Self.col(z.zap))
            tapFeel.impactOccurred()
        }
    }

    func resized(_ s: CGSize) {
        guard s.width > 0, s.height > 0 else { return }
        let aspect = s.width / s.height
        let want = 2 * atan(tan(35 * Double.pi / 180) * max(1, 1.45 / Double(aspect)))
        camNode.camera?.fieldOfView = CGFloat(min(100, want * 180 / .pi))
        if abs(aspect - lastAspect) > 0.05 { lastAspect = aspect; scene.background.contents = backdrop(zone) }
    }

    // ---------------- run control ----------------
    func clearThings() { for t in things { t.node.removeFromParentNode() }; things.removeAll() }
    func reset() {
        rx = 0; ry = 0; tx = 0; ty = 0; vx = 0; vy = 0
        dist = 0; speed = 16.1; starsGot = 0; shake = 0
        clearThings(); nextZ = 60
        shieldOn = false; magnetT = 0; boostT = 0; invulnT = 1.2; puGap = rnd(225, 375)
        ship.isHidden = false; shock.isHidden = true; shockAge = -1
        exhaust.birthRate = rich ? 220 : 90
        if zoneIx != 0 { setZone(0, banner: false) }
    }
    func startRun() {
        reset(); flying = true; dead = false; isPaused2 = false
        setZone(0, banner: true)
        model.hintOn = true
    }
    func paused(_ p: Bool) { isPaused2 = p; if p { keysUp(); dragStart = nil }; last = 0 }

    // ---------------- controls ----------------
    func clampT() { tx = max(-HW, min(HW, tx)); ty = max(-HH, min(HH, ty)) }
    func dragBegan(_ p: CGPoint) {
        guard flying && !isPaused2 else { return }
        dragStart = (p, tx, ty)
        if model.hintOn { model.hintOn = false }
    }
    func dragMoved(_ p: CGPoint, _ size: CGSize) {
        guard flying && !isPaused2, let d = dragStart else { return }
        let gain = Float((CGFloat(HW) * 2 * 1.7) / min(size.width, size.height * 1.6))
        tx = d.1 + Float(p.x - d.0.x) * gain
        ty = d.2 - Float(p.y - d.0.y) * gain
        clampT()
    }
    func dragEnded() { dragStart = nil }
    func keysUp() { for k in keys.keys { keys[k] = 0 } }
    func key(_ code: UIKeyboardHIDUsage, down: Bool) -> Bool {
        var k: String?
        switch code {
        case .keyboardLeftArrow, .keyboardA: k = "l"
        case .keyboardRightArrow, .keyboardD: k = "r"
        case .keyboardUpArrow, .keyboardW: k = "u"
        case .keyboardDownArrow, .keyboardS: k = "d"
        case .keyboardSpacebar, .keyboardReturnOrEnter:
            if down && !flying && !model.payOpen && (model.phase == .menu || model.showDeadCard) {
                DispatchQueue.main.async { self.model.start() }
            }
            return true
        default: return false
        }
        if let k = k { keys[k] = down ? 1 : 0; if down && model.hintOn { model.hintOn = false } }
        return true
    }

    // ---------------- making things ----------------
    func place(_ t: Thing) { t.node.position = SCNVector3(t.x, t.y, -(t.z - dist)) }
    @discardableResult func add(_ t: Thing) -> Thing { place(t); scene.rootNode.addChildNode(t.node); things.append(t); return t }
    func glowSprite(_ hex: UInt32, _ size: CGFloat, _ alpha: CGFloat) -> SCNNode {
        let p = SCNPlane(width: size, height: size)
        let m = SCNMaterial(); m.lightingModel = .constant; m.diffuse.contents = glowImage; m.multiply.contents = Self.col(hex)
        m.transparency = alpha; m.blendMode = .add; m.writesToDepthBuffer = false
        p.materials = [m]
        let n = SCNNode(geometry: p); n.constraints = [SCNBillboardConstraint()]
        return n
    }
    func addStar(_ x: Float, _ y: Float, _ z: Float) {
        let g = SCNNode(), s = node(starGeo); g.addChildNode(s)
        if rich { g.addChildNode(glowSprite(0xFDE047, 2.2, 0.5)) }
        let t = Thing(.star, x, y, z, g); t.t = rnd(0, 6); t.beam = s
        add(t)
    }
    @discardableResult func starLine(_ z: Float, _ x: Float, _ y: Float, _ n: Int) -> Float { for i in 0..<n { addStar(x, y, z + Float(i) * 3) }; return Float(n) * 3 }
    func starWave(_ z: Float, _ n: Int) -> Float {
        let cx = rnd(-HW + 3, HW - 3), cy = rnd(-HH + 1, HH - 1)
        for i in 0..<n { addStar(cx + sin(Float(i) / 1.6) * 3, cy, z + Float(i) * 3) }
        return Float(n) * 3
    }
    func starSpiral(_ z: Float, _ n: Int) -> Float {
        let cx = rnd(-HW + 3, HW - 3), cy = rnd(-1, 1)
        for i in 0..<n { addStar(cx + cos(Float(i) * 0.7) * 2.2, cy + sin(Float(i) * 0.7) * 2.2, z + Float(i) * 2.6) }
        return Float(n) * 2.6
    }
    func zapper(_ z: Float, _ x: Float, _ y: Float, _ len: Float, _ ang: Float, _ spin: Float) {
        let g = SCNNode()
        let beam = node(beamGeo); beam.eulerAngles.z = .pi / 2; beam.scale = SCNVector3(1, len, 1); g.addChildNode(beam)
        let core = node(coreGeo); core.eulerAngles.z = .pi / 2; core.scale = SCNVector3(1, len, 1); g.addChildNode(core)
        for s: Float in [-1, 1] {
            g.addChildNode(node(podGeo, s * len / 2, 0, 0))
            if rich { let gl = glowSprite(zone.zap, 2.4, 0.8); gl.position = SCNVector3(s * len / 2, 0, 0); g.addChildNode(gl) }
        }
        let t = Thing(.zap, x, y, z, g); t.len = len; t.a = ang; t.spin = spin; t.beam = beam
        g.eulerAngles.z = ang
        add(t)
    }
    func rock(_ z: Float, _ x: Float, _ y: Float, _ r: Float) {
        let m = SCNNode(geometry: rockGeos.randomElement()!)
        m.scale = SCNVector3(r, r, r); m.eulerAngles = SCNVector3(rnd(0, 6), rnd(0, 6), 0)
        let t = Thing(.rock, x, y, z, m); t.r = r
        let dd = 0.4 + diff
        t.vx = rnd(-2, 2) * dd; t.vy = rnd(-1.4, 1.4) * dd; t.rx = rnd(-1, 1); t.ry = rnd(-1, 1)
        add(t)
    }
    func wall(_ z: Float, _ hx: Float, _ hy: Float, _ hs: Float) {
        let g = SCNNode(), D: Float = 0.7, X = HW + 4, Y = HH + 4
        func slab(_ x0: Float, _ x1: Float, _ y0: Float, _ y1: Float) {
            let m = node(wallGeo, (x0 + x1) / 2, (y0 + y1) / 2, 0); m.scale = SCNVector3(x1 - x0, y1 - y0, D); g.addChildNode(m)
        }
        slab(-X, X, hy + hs, Y); slab(-X, X, -Y, hy - hs); slab(-X, hx - hs, hy - hs, hy + hs); slab(hx + hs, X, hy - hs, hy + hs)
        let tt: Float = 0.14
        for b in [(hx, hy + hs, hs * 2 + tt, tt), (hx, hy - hs, hs * 2 + tt, tt), (hx + hs, hy, tt, hs * 2), (hx - hs, hy, tt, hs * 2)] {
            let m = node(trimGeo, b.0, b.1, D / 2 + 0.02); m.scale = SCNVector3(b.2, b.3, 0.08); g.addChildNode(m)
        }
        let t = Thing(.wall, 0, 0, z, g); t.hx = hx; t.hy = hy; t.hs = hs
        add(t)
    }
    func power(_ z: Float, _ x: Float, _ y: Float) {
        let type = Pu.allCases.randomElement()!
        let hex: UInt32 = type == .shield ? 0x60A5FA : type == .magnet ? 0xF472B6 : 0xF97316
        let g = SCNNode()
        let wm = SCNMaterial(); wm.lightingModel = .constant; wm.diffuse.contents = Self.col(hex); wm.fillMode = .lines
        let cage = SCNSphere(radius: 0.75); cage.isGeodesic = true; cage.segmentCount = 1; cage.materials = [wm]
        g.addChildNode(SCNNode(geometry: cage))
        let inner: SCNGeometry
        switch type {
        case .shield: inner = SCNSphere(radius: 0.42)
        case .magnet: inner = SCNTorus(ringRadius: 0.36, pipeRadius: 0.12)
        case .boost: inner = SCNCone(topRadius: 0, bottomRadius: 0.35, height: 0.8)
        }
        inner.materials = [lit(hex, emit: hex, emitAmount: 0.7)]
        g.addChildNode(SCNNode(geometry: inner))
        if rich { g.addChildNode(glowSprite(hex, 3.2, 0.7)) }
        let t = Thing(.pu, x, y, z, g); t.pu = type
        add(t)
    }
    func burst(_ x: Float, _ y: Float, _ z: Float, _ hex: UInt32, _ n: Int, _ sp: Float) {
        let ps = SCNParticleSystem()
        let count = CGFloat(n) * (rich ? 1.6 : 0.7)
        ps.particleImage = glowImage
        ps.birthRate = count / 0.05; ps.emissionDuration = 0.05; ps.loops = false
        ps.particleLifeSpan = 0.75; ps.particleLifeSpanVariation = 0.3
        ps.particleSize = 0.32; ps.particleSizeVariation = 0.15
        ps.particleColor = Self.col(hex)
        ps.blendMode = .additive
        ps.particleVelocity = CGFloat(sp); ps.particleVelocityVariation = CGFloat(sp) * 0.7
        ps.spreadingAngle = 180
        ps.emittingDirection = SCNVector3(0, 0, -1)
        ps.isLocal = true
        ps.isAffectedByGravity = false
        ps.propertyControllers = [.opacity: fade()]
        let n = SCNNode(); n.addParticleSystem(ps)
        let t = Thing(.fx, x, y, z, n); t.t = 1.6
        add(t)
    }

    /// one "chunk" of space at a time, out ahead of the rocket
    func spawnChunk(_ z: Float) -> Float {
        let d = diff, roll = Float.random(in: 0...1)
        var w: Float = 0
        if puGap <= 0 { power(z + 6, rnd(-HW + 2, HW - 2), rnd(-HH + 1, HH - 1)); puGap = rnd(225, 375); return 22 }
        if roll < 0.18 {                                            // stars only - a breather
            let k = Float.random(in: 0...1)
            w = k < 0.4 ? starLine(z, rnd(-HW + 1.5, HW - 1.5), rnd(-HH + 1, HH - 1), 8) : k < 0.75 ? starWave(z, 9) : starSpiral(z, 10)
        } else if roll < 0.46 {                                     // a zapper, with stars to tempt you
            let kind = Float.random(in: 0...1)
            var len: Float, x: Float, y: Float, a: Float
            if kind < 0.35 { len = rnd(5, 7 + d * 3); a = 0; x = rnd(-HW + len / 3, HW - len / 3); y = rnd(-HH + 0.6, HH - 0.6) }
            else if kind < 0.7 { len = rnd(4.5, 6 + d * 1.5); a = .pi / 2; x = rnd(-HW + 1, HW - 1); y = rnd(-1, 1) }
            else { len = rnd(6, 8 + d * 3); a = [0.6, -0.6, 0.9, -0.9].randomElement()!; x = rnd(-3, 3); y = rnd(-1.2, 1.2) }
            let spin: Float = d > 0.3 && Float.random(in: 0...1) < d * 0.55 ? rnd(0.5, 1.2) * (Bool.random() ? -1 : 1) : 0
            zapper(z + 8, x, y, len, a, spin)
            if Float.random(in: 0...1) < 0.7 { starLine(z + 2, x > 0 ? x - 4 : x + 4, y > 0 ? y - 2 : y + 2, 5) }
            w = 24 - d * 5
        } else if roll < 0.66 {                                     // a wall with a hole
            let hs = 2.2 - d * 0.65, hx = rnd(-HW + hs + 0.3, HW - hs - 0.3), hy = rnd(-HH + hs + 0.2, HH - hs - 0.2)
            wall(z + 10, hx, hy, hs)
            starLine(z + 1, hx, hy, 3)
            w = 26 - d * 5
        } else if roll < 0.86 {                                     // asteroids
            let n = 3 + Int(d * 3 + Float.random(in: 0...2))
            var zz = z + 6
            for _ in 0..<n { rock(zz, rnd(-HW + 1, HW - 1), rnd(-HH + 0.8, HH - 0.8), rnd(0.85, 1.35 + d * 0.5)); zz += rnd(5, 8) }
            w = 10 + Float(n) * 7
        } else {                                                    // two zappers with a way through
            let gap = 2.7 - d * 0.6
            if Bool.random() {
                let cy = rnd(-HH + gap / 2 + 0.5, HH - gap / 2 - 0.5)
                zapper(z + 8, 0, cy + gap / 2 + 0.2, HW * 2 + 1, 0, 0); zapper(z + 8, 0, cy - gap / 2 - 0.2, HW * 2 + 1, 0, 0)
                addStar(rnd(-3, 3), cy, z + 8)
            } else {
                let cx = rnd(-HW + gap / 2 + 1, HW - gap / 2 - 1)
                zapper(z + 8, cx + gap / 2 + 0.2, 0, HH * 2 + 1, .pi / 2, 0); zapper(z + 8, cx - gap / 2 - 0.2, 0, HH * 2 + 1, .pi / 2, 0)
                addStar(cx, rnd(-1.5, 1.5), z + 8)
            }
            w = 24
        }
        return w + rnd(6, 11) * (1.15 - d * 0.5)
    }

    // ---------------- collisions ----------------
    func segDist(_ px: Float, _ py: Float, _ ax: Float, _ ay: Float, _ bx: Float, _ by: Float) -> Float {
        let dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy
        var t: Float = l2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / l2 : 0
        t = max(0, min(1, t))
        let qx = ax + t * dx - px, qy = ay + t * dy - py
        return (qx * qx + qy * qy).squareRoot()
    }
    func hits(_ t: Thing) -> Bool {
        let rel = t.z - dist
        switch t.k {
        case .zap:
            if rel < -1.1 || rel > 1.2 { return false }
            let c = cos(t.a) * t.len / 2, s = sin(t.a) * t.len / 2
            return segDist(rx, ry, t.x - c, t.y - s, t.x + c, t.y + s) < RR + 0.2
        case .wall:
            if rel < -1.1 || rel > 1.2 { return false }
            let m = t.hs - RR * 0.8
            return abs(rx - t.hx) > m || abs(ry - t.hy) > m
        case .rock:
            let dz = max(-0.9, min(0.9, rel)), ex = t.x - rx, ey = t.y - ry, ez = rel - dz
            let k = t.r * 0.82 + RR * 0.8
            return ex * ex + ey * ey + ez * ez < k * k
        default:
            return false
        }
    }
    func hit(_ t: Thing) {
        if boostT > 0 || invulnT > 0 {
            if boostT > 0 && t.k == .rock { t.dead = true; burst(t.x, t.y, t.z, 0xD6D3D1, 30, 9); shake = 0.25 }
            return
        }
        if shieldOn {
            shieldOn = false; invulnT = 1.2; shake = 0.45; haptic.impactOccurred()
            burst(rx, ry, dist, 0x60A5FA, 40, 10)
            if t.k == .rock || t.k == .zap { t.dead = true; burst(t.x, t.y, t.z, 0x9CA3AF, 30, 8) }
            model.showPop("🛡 Shield saved you!", Self.col(0x93C5FD))
            return
        }
        die(t.k == .zap ? "Zapped!" : t.k == .rock ? "Hit an asteroid!" : "Hit a wall!")
    }
    func die(_ why: String) {
        guard flying else { return }
        flying = false; dead = true; deadAt = CACurrentMediaTime()
        shake = 0.9; haptic.impactOccurred()
        burst(rx, ry, dist, 0xFBBF24, 90, 14); burst(rx, ry, dist, 0xF43F5E, 60, 10); burst(rx, ry, dist, 0xE5E7EB, 40, 6)
        ship.isHidden = true; shieldNode.isHidden = true; magnetNode.isHidden = true
        if rich { shock.isHidden = false; shock.position = SCNVector3(rx, ry, 0); shockAge = 0 }
        model.hintOn = false
        model.died(why: why, score: score, meters: meters, stars: starsGot, zone: zoneIx + 1)
    }
    func grab(_ t: Thing) {
        let hex: UInt32 = t.pu == .shield ? 0x60A5FA : t.pu == .magnet ? 0xF472B6 : 0xF97316
        burst(t.x, t.y, t.z, hex, 40, 8); tapFeel.impactOccurred()
        switch t.pu {
        case .shield: shieldOn = true; model.showPop("🛡 Shield!", Self.col(0x93C5FD))
        case .magnet: magnetT = 8; model.showPop("🧲 Magnet!", Self.col(0xF9A8D4))
        case .boost: boostT = 3; model.showPop("🔥 Boost!", Self.col(0xFDBA74))
        }
    }

    // ---------------- the frame ----------------
    @objc func frame(_ l: CADisplayLink) {
        let now = l.timestamp
        let dt = Float(last == 0 ? 1.0 / 60 : min(0.05, now - last))
        last = now
        frameCount += 1
        let before = dist
        if flying && !isPaused2 {
            if model.playing { model.played += Double(dt) }
            step(dt)
        } else if !flying && !dead {
            dist += 14 * dt
            let tn = now.truncatingRemainder(dividingBy: 3600)
            rx = Float(sin(tn / 0.9)) * 1.2; ry = Float(sin(tn / 0.65)) * 0.6; vx = Float(cos(tn / 0.9)) * 1.3; vy = 0
        }
        if !isPaused2 {
            var i = things.count - 1
            while i >= 0 {
                let t = things[i]
                if t.k == .fx { t.t -= dt; if t.t <= 0 { t.node.removeFromParentNode(); things.remove(at: i) } }
                i -= 1
            }
        }
        if shake > 0 { shake = max(0, shake - 1.6 * dt) }
        render(Float(now.truncatingRemainder(dividingBy: 3600)), dt, mv: dist - before)
        if frameCount % 6 == 0 { hud() }
    }

    func step(_ dt: Float) {
        if invulnT > 0 { invulnT -= dt }
        if magnetT > 0 { magnetT -= dt }
        if boostT > 0 { boostT -= dt; if boostT <= 0 { invulnT = 0.8 } }

        let target = (430 + min(520, Float(meters) * 0.13)) / 40 * U        // the same pace as every version
        speed += (target - speed) * min(1, dt * 0.5)
        let mv = speedNow * dt
        dist += mv; puGap -= mv
        while nextZ < dist + VIEW { nextZ += spawnChunk(nextZ) }
        let z = meters / zoneLen
        if z != zoneIx { setZone(z) }

        // steering: the rocket chases where you point
        let kx = (keys["r"] ?? 0) - (keys["l"] ?? 0), ky = (keys["u"] ?? 0) - (keys["d"] ?? 0)
        if kx != 0 || ky != 0 { tx += kx * 13 * dt; ty += ky * 11 * dt; clampT() }
        var ax = (tx - rx) * 9, ay = (ty - ry) * 9
        let l = (ax * ax + ay * ay).squareRoot(), MAXV: Float = 17
        if l > MAXV { ax *= MAXV / l; ay *= MAXV / l }
        vx += (ax - vx) * min(1, dt * 12); vy += (ay - vy) * min(1, dt * 12)
        rx = max(-HW, min(HW, rx + vx * dt)); ry = max(-HH, min(HH, ry + vy * dt))

        var i = things.count - 1
        while i >= 0 {
            let t = things[i]
            switch t.k {
            case .rock:
                t.x += t.vx * dt; t.y += t.vy * dt
                if abs(t.x) > HW + 0.5 { t.vx = -t.vx }
                if abs(t.y) > HH + 0.3 { t.vy = -t.vy }
                if !t.dead && hits(t) { hit(t) }
            case .zap:
                if t.spin != 0 { t.a += t.spin * dt }
                if !t.dead && hits(t) { hit(t) }
            case .wall:
                if hits(t) { hit(t) }
            case .star:
                t.t += dt * 3
                let rel = t.z - dist
                if magnetT > 0 && rel < 26 && rel > -2 {
                    let dx = rx - t.x, dy = ry - t.y, dz = -rel
                    let dd = max(0.001, (dx * dx + dy * dy + dz * dz).squareRoot())
                    t.x += dx / dd * 30 * dt; t.y += dy / dd * 30 * dt; t.z += dz / dd * 30 * dt
                }
                let cx = t.x - rx, cy = t.y - ry, cz = t.z - dist
                let extra: Float = (cz < 0 && cz > -mv - 1) ? 1 : 0
                if cx * cx + cy * cy + cz * cz < 1.45 * 1.45 + extra {
                    starsGot += 1; burst(t.x, t.y, t.z, 0xFDE047, 14, 5); t.dead = true
                }
            case .pu:
                t.t += dt
                let px = t.x - rx, py = t.y - ry, pz = t.z - dist
                if px * px + py * py + pz * pz < 2.1 * 2.1 { grab(t); t.dead = true }
            case .fx:
                break
            }
            if !flying { return }
            if t.dead || (t.k != .fx && t.z < dist - 12) { t.node.removeFromParentNode(); things.remove(at: i) }
            i -= 1
        }
    }

    func render(_ now: Float, _ dt: Float, mv: Float) {
        for t in things {
            place(t)
            switch t.k {
            case .star: t.beam?.eulerAngles.y = t.t
            case .zap:
                t.node.eulerAngles.z = t.a
                let fl = Float.random(in: 0.75...1.25)
                if let b = t.beam { b.scale = SCNVector3(fl, t.len, fl) }
            case .rock:
                t.node.eulerAngles.x += t.rx * dt; t.node.eulerAngles.y += t.ry * dt
            case .pu:
                t.node.eulerAngles.y = t.t * 2; t.node.position.y += sin(t.t * 4) * 0.2
            default: break
            }
        }
        // streaming frames and dust
        let span = frameGap * Float(frames.count)
        for (i, f) in frames.enumerated() {
            while frameZ[i] < dist - 8 { frameZ[i] += span }
            f.position = SCNVector3(0, 0, -(frameZ[i] - dist))
        }
        let dspan = slabLen * Float(dustSlabs.count)
        for (i, s) in dustSlabs.enumerated() {
            while slabZ[i] < dist - 20 { slabZ[i] += dspan }
            s.position = SCNVector3(0, 0, -(slabZ[i] - dist))
        }

        // the rocket leans into turns
        rocket.position = SCNVector3(rx, ry, 0)
        let k = min(1, dt * 10)
        ship.eulerAngles.z += (-vx * 0.055 - ship.eulerAngles.z) * k
        ship.eulerAngles.x += (vy * 0.035 - ship.eulerAngles.x) * k
        let blink = flying && invulnT > 0 && Int(invulnT * 12) % 2 == 0
        ship.isHidden = dead || blink
        let fs = Float(boostT > 0 ? 1.8 : 1) * Float.random(in: 0.85...1.15)
        flame.scale = SCNVector3(1, fs, 1); flameIn.scale = SCNVector3(1, fs, 1)
        exhaust.particleVelocity = CGFloat(min(26, speedNow * 0.6 + 4))
        exhaust.particleColor = boostT > 0 ? Self.col(0xF97316) : Self.col(0xFBBF24)
        exhaust.birthRate = dead ? 0 : (rich ? 220 : 90) * (boostT > 0 ? 2 : 1)
        if !dead {
            shieldNode.isHidden = !shieldOn
            if shieldOn { let s = 1 + sin(now * 8) * 0.04; shieldNode.scale = SCNVector3(s, s, s); shieldNode.eulerAngles.y += dt }
            magnetNode.isHidden = magnetT <= 0
            magnetNode.eulerAngles.x += dt * 3; magnetNode.eulerAngles.y += dt * 2
        }
        if shockAge >= 0 {
            shockAge += dt
            if shockAge > 0.9 { shock.isHidden = true; shockAge = -1 }
            else { let s = 1 + shockAge * 22; shock.scale = SCNVector3(s, s, s); shock.opacity = CGFloat(1 - shockAge / 0.9) }
        }

        // camera: behind and a little above, following the rocket
        let sx = shake > 0 ? rnd(-shake, shake) : 0, sy = shake > 0 ? rnd(-shake, shake) : 0
        if !flying && !dead {
            let a = now / 4
            camNode.position = SCNVector3(sin(a) * 4.5, 1.6 + sin(a * 1.3), 6.5 + cos(a) * 1.5)
            camNode.look(at: SCNVector3(0, 0, -2))
        } else {
            camNode.position = SCNVector3(rx * 0.55 + sx, ry * 0.55 + 2.3 + sy, 7.2 - (boostT > 0 ? 1 : 0))
            camNode.look(at: SCNVector3(rx * 0.75, ry * 0.75 - 0.2, -14))
        }
    }

    func hud() {
        model.hudMeters = meters
        model.hudStars = starsGot
        var pu: [String] = []
        if shieldOn { pu.append("🛡") }
        if magnetT > 0 { pu.append("🧲 \(Int(magnetT.rounded(.up)))") }
        if boostT > 0 { pu.append("🔥 \(Int(boostT.rounded(.up)))") }
        let s = pu.joined(separator: "  ")
        if s != model.hudPu { model.hudPu = s }
        model.hudTime = model.timeText
        model.hudTimeLow = model.timeLow
        model.checkTime()
    }
}
