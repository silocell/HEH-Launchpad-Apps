// Play time and pence for the native game - the same calls the website makes.
// © 2026 silocell. All rights reserved.
import Foundation
import WebKit
import SwiftUI

enum Server {
    static let fn = URL(string: "https://hehs-live.duckdns.org/functions/v1/profile")!
    static let key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzkyODMyMjkwLCJleHAiOjIxMDgxOTIyOTB9.7gFhQa5RBwVtiOPLO7Qf2-aVEbkd5xgpLiN1J06Gybo"
    /// Kept fresh by TokenPage (a hidden page reading the Launchpad's own sign-in).
    static var token: String?

    static func call(_ body: [String: Any], done: @escaping ([String: Any]?, String?) -> Void) {
        guard let t = token else { done(nil, "Sign in on the Launchpad first."); return }
        var r = URLRequest(url: fn)
        r.httpMethod = "POST"
        r.timeoutInterval = 15
        r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
        r.setValue(key, forHTTPHeaderField: "apikey")
        r.setValue("application/json", forHTTPHeaderField: "content-type")
        r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: r) { data, resp, _ in
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            let j = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            DispatchQueue.main.async {
                if let j = j, code < 400, j["error"] == nil { done(j, nil) }
                else { done(nil, (j?["error"] as? String) ?? "Can't reach the Launchpad.") }
            }
        }.resume()
    }
}

/// A tiny hidden web view on app-token.html: it shares the Launchpad's
/// sign-in (same cookies) and posts a fresh token every few minutes.
struct TokenPage: UIViewRepresentable {
    var onToken: (String?) -> Void
    func makeCoordinator() -> Coord { Coord(onToken: onToken) }
    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        cfg.userContentController.add(context.coordinator, name: "launchpad")
        let w = WKWebView(frame: CGRect(x: 0, y: 0, width: 1, height: 1), configuration: cfg)
        w.load(URLRequest(url: URL(string: "https://civilinitiatives.org/app-token.html")!))
        return w
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
    final class Coord: NSObject, WKScriptMessageHandler {
        let onToken: (String?) -> Void
        init(onToken: @escaping (String?) -> Void) { self.onToken = onToken }
        func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
            guard let s = m.body as? String else { return }
            if s == "null" { Server.token = nil; onToken(nil); return }
            if let d = s.data(using: .utf8), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any], let at = j["at"] as? String {
                Server.token = at; onToken(at)
            }
        }
    }
}
