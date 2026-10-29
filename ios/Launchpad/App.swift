// Homework Launchpad for iPad and iPhone (and Mac, through Mac Catalyst).
// © 2026 silocell. All rights reserved.
//
// The Launchpad itself is shown in the app's own web view - same pages,
// same design as the website. Rocket Rush is written natively
// in 3D with SceneKit (RushGame.swift): richer on iPad and Mac, lighter on iPhone.
import SwiftUI

@main
struct LaunchpadApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onOpenURL { url in model.incoming(url) }
        }
    }
}

final class AppModel: ObservableObject {
    @Published var showGame = false
    /// A sign-in link (hehlaunchpad://login#at=..&rt=..) for the web view to load.
    @Published var pendingSession: URL?

    func incoming(_ url: URL) {
        if let s = AppModel.sessionURL(from: url) { pendingSession = s }
    }

    /// hehlaunchpad://login#at=..&rt=..  ->  https://civilinitiatives.org/?appsession=1#at=..&rt=..
    static func sessionURL(from url: URL) -> URL? {
        guard url.scheme == "hehlaunchpad", let frag = url.fragment else { return nil }
        var at: String?, rt: String?
        for part in frag.split(separator: "&") {
            let kv = part.split(separator: "=", maxSplits: 1).map(String.init)
            guard kv.count == 2 else { continue }
            if kv[0] == "at" { at = kv[1].removingPercentEncoding }
            if kv[0] == "rt" { rt = kv[1].removingPercentEncoding }
        }
        guard let a = at, let r = rt, !a.isEmpty, !r.isEmpty else { return nil }
        let enc: (String) -> String = { $0.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? $0 }
        return URL(string: "https://civilinitiatives.org/?appsession=1#at=\(enc(a))&rt=\(enc(r))")
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        ZStack {
            Color(red: 0.216, green: 0.188, blue: 0.639).ignoresSafeArea()
            LaunchpadWebView(model: model)
                .ignoresSafeArea(edges: .bottom)
        }
        .fullScreenCover(isPresented: $model.showGame) {
            GameScreen(onClose: { model.showGame = false })
        }
    }
}
