// Rocket Rush - the screen around the SpriteKit game: start, game-over and
// "time's up" cards, and the play-time rules (30 minutes free a day, then
// 5p for each half hour - the server keeps count).
// © 2026 silocell. All rights reserved.
import SwiftUI
import SpriteKit

final class RushModel: ObservableObject {
    enum Phase { case menu, fly, dead }
    @Published var phase: Phase = .menu
    @Published var showDeadCard = false
    @Published var payOpen = false
    @Published var payMsg = "You've had today's free half hour."
    @Published var buying = false
    @Published var deadWhy = ""
    @Published var final = (score: 0, meters: 0, stars: 0, zone: 1)
    @Published var newBest = false
    @Published var best = UserDefaults.standard.integer(forKey: "rush.best")
    @Published var pence = ""

    // play time
    var left: Double = -1
    var leftAt = Date()
    var free = 1
    var playing = false
    var played: Double = 0
    var lastTick = Date()
    var tokenSeen = false
    weak var scene: RushScene?

    var timeText: String {
        guard left >= 0 else { return Server.token == nil ? "⏱️ …" : "⏱️ …" }
        let l = max(0, left - (playing ? Date().timeIntervalSince(leftAt) : 0))
        return String(format: "⏱️ %d:%02d %@", Int(l) / 60, Int(l) % 60, free > 0 ? "free" : "left")
    }
    var timeLow: Bool { left >= 0 && left - (playing ? Date().timeIntervalSince(leftAt) : 0) < 120 }

    func tokenReady() {
        if tokenSeen { return }
        tokenSeen = true
        Server.call(["action": "playTime", "sec": 0]) { [weak self] j, _ in if let j = j { self?.apply(j) } }
    }
    func apply(_ j: [String: Any]) {
        left = (j["left"] as? NSNumber)?.doubleValue ?? 1800
        free = (j["free"] as? NSNumber)?.intValue ?? 0
        leftAt = Date()
        if left <= 0 && playing { outOfTime() }
    }
    func tick() {
        let sec = Int(played.rounded()); played = 0; lastTick = Date()
        Server.call(["action": "playTime", "sec": sec]) { [weak self] j, _ in if let j = j { self?.apply(j) } }
    }
    func checkTime() {
        if playing && Date().timeIntervalSince(lastTick) > 30 { tick() }
        if playing && left >= 0 && left - Date().timeIntervalSince(leftAt) <= 0 { outOfTime() }
    }
    func outOfTime() {
        if payOpen { return }
        playing = false; scene?.paused(true); tick(); payOpen = true
    }
    func buy() {
        if buying { return }
        buying = true
        Server.call(["action": "playBuy"]) { [weak self] j, e in
            guard let self = self else { return }
            self.buying = false
            if let j = j { self.apply(j); self.payOpen = false; if self.phase == .fly { self.playing = true; self.scene?.paused(false) } }
            else { self.payMsg = e ?? "Couldn't do that." }
        }
    }
    func start() {
        if payOpen { return }
        showDeadCard = false
        phase = .fly; playing = true; leftAt = Date()
        scene?.startRun()
        if left >= 0 && left <= 0 { outOfTime() }
    }
    func died(why: String, score: Int, meters: Int, stars: Int, zone: Int) {
        phase = .dead; playing = false; tick()
        deadWhy = why; final = (score, meters, stars, zone)
        newBest = score > best
        if newBest { best = score; UserDefaults.standard.set(score, forKey: "rush.best") }
        pence = ""
        if score > 0 {
            Server.call(["action": "played", "game": "rush", "score": score]) { [weak self] j, _ in
                let g = (j?["gained"] as? NSNumber)?.intValue ?? 0
                if g > 0 { self?.pence = "+\(g)p for that run" }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in if self?.phase == .dead { self?.showDeadCard = true } }
    }
}

struct GameScreen: View {
    var onClose: () -> Void
    @StateObject private var model = RushModel()
    @State private var scene: RushScene?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if let s = scene {
                    SpriteView(scene: s, preferredFramesPerSecond: 60, options: [.ignoresSiblingOrder])
                        .ignoresSafeArea()
                } else {
                    Color(red: 0.03, green: 0.03, blue: 0.09).ignoresSafeArea()
                }
                TokenPage { t in if t != nil { model.tokenReady() } }
                    .frame(width: 1, height: 1).opacity(0.01).allowsHitTesting(false)
                if model.payOpen { payCard }
                else if model.phase == .menu { menuCard }
                else if model.phase == .dead && model.showDeadCard { deadCard }
            }
            .onAppear {
                if scene == nil {
                    let s = RushScene(size: geo.size, model: model, onClose: onClose)
                    model.scene = s
                    scene = s
                }
            }
            .onChange(of: geo.size) { sz in scene?.resized(sz) }
        }
        .statusBarHidden()
        .onChange(of: scenePhase) { ph in
            if ph != .active { model.playing = false; model.tick(); scene?.paused(true) }
            else if model.phase == .fly && !model.payOpen { model.playing = true; model.leftAt = Date(); scene?.paused(false) }
        }
    }

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        ZStack {
            Color.black.opacity(0.45).ignoresSafeArea()
            VStack(spacing: 10) { content() }
                .padding(.horizontal, 22).padding(.vertical, 26)
                .frame(maxWidth: 420)
                .background(RoundedRectangle(cornerRadius: 24).fill(Color(red: 0.06, green: 0.07, blue: 0.17).opacity(0.95)))
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color.white.opacity(0.12)))
                .padding(20)
                .foregroundColor(.white)
        }
    }
    private let soft = Color(red: 0.65, green: 0.68, blue: 0.81)
    private func bigButton(_ title: String, ghost: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 19, weight: .heavy)).padding(.horizontal, 28).padding(.vertical, 13)
                .background(ghost ? AnyView(Color.white.opacity(0.12)) : AnyView(LinearGradient(colors: [.orange, .pink], startPoint: .leading, endPoint: .trailing)))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .foregroundColor(.white)
        }
    }

    private var menuCard: some View {
        card {
            Text("🚀 Rocket Rush").font(.system(size: 34, weight: .heavy))
                .foregroundStyle(LinearGradient(colors: [.yellow, .pink, .purple], startPoint: .leading, endPoint: .trailing))
            Text("Hold anywhere to fly up. Let go to drop.").foregroundColor(soft)
            Text("⭐ stars are 5 points · ⚡🪨🧱 end your run").font(.callout).foregroundColor(soft)
            Text("Power-ups: 🛡 shield · 🧲 magnet · 🔥 boost").font(.callout).foregroundColor(soft)
            if model.best > 0 { Text("Your best: \(model.best)").bold().padding(.top, 4) }
            bigButton("Play") { model.start() }
            bigButton("Back to the Launchpad", ghost: true, action: onClose)
            Text("30 minutes free a day, then 5p per half hour").font(.caption).foregroundColor(soft).padding(.top, 4)
        }
    }

    private var deadCard: some View {
        card {
            Text(model.deadWhy).foregroundColor(soft)
            Text("\(model.final.score)").font(.system(size: 48, weight: .black)).monospacedDigit()
            Text("\(model.final.meters) m  ·  ⭐ \(model.final.stars)  ·  zone \(model.final.zone)").foregroundColor(soft)
            if model.newBest && model.final.score > 0 { Text("🏆 New best!").bold().foregroundColor(.yellow) }
            else { Text("Your best: \(model.best)") }
            bigButton("Fly again") { model.start() }
            bigButton("Back to the Launchpad", ghost: true, action: onClose)
            if !model.pence.isEmpty { Text(model.pence).font(.footnote).foregroundColor(.yellow) }
        }
    }

    private var payCard: some View {
        card {
            Text("Time's up for now").font(.system(size: 26, weight: .heavy)).foregroundColor(.yellow)
            Text(model.payMsg).foregroundColor(soft).multilineTextAlignment(.center)
            Text("Keep playing: 5p for 30 more minutes.")
            bigButton(model.buying ? "…" : "Pay 5p") { model.buy() }
            bigButton("Stop for today", ghost: true, action: onClose)
            Text("Earn pence in the learning games.").font(.caption).foregroundColor(soft)
        }
    }
}
