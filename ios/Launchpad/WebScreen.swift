// The Launchpad in a WKWebView.
// © 2026 silocell. All rights reserved.
//
// Google and Microsoft sign-in: Google refuses app web views, so that step
// runs in Apple's sign-in sheet (ASWebAuthenticationSession, which is
// Safari). The site's ?applogin page signs in there and finishes on
// hehlaunchpad://login#at=..&rt=.., which the sheet hands back to us.
import SwiftUI
import WebKit
import AuthenticationServices

struct LaunchpadWebView: UIViewRepresentable {
    let model: AppModel
    static let home = URL(string: "https://civilinitiatives.org/")!

    func makeCoordinator() -> Coordinator { Coordinator(model: model) }

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.applicationNameForUserAgent = "Mobile/15E148 LaunchpadApp/2.0 (iOS)"
        let js = """
        window.LaunchpadApp = {
          platform: function(){ return 'ios'; },
          version: function(){ return '2.0'; },
          openGame: function(n){ window.webkit.messageHandlers.launchpadGame.postMessage(String(n)); }
        };
        """
        cfg.userContentController.addUserScript(WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        cfg.userContentController.add(context.coordinator, name: "launchpadGame")
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = true
        web.isOpaque = false
        web.backgroundColor = UIColor(red: 0.216, green: 0.188, blue: 0.639, alpha: 1)
        web.scrollView.contentInsetAdjustmentBehavior = .automatic
        let refresh = UIRefreshControl()
        refresh.addTarget(context.coordinator, action: #selector(Coordinator.pull(_:)), for: .valueChanged)
        web.scrollView.refreshControl = refresh
        context.coordinator.web = web
        web.load(URLRequest(url: Self.home))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        if let s = model.pendingSession {
            DispatchQueue.main.async { model.pendingSession = nil }
            web.load(URLRequest(url: s))
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, ASWebAuthenticationPresentationContextProviding {
        let model: AppModel
        weak var web: WKWebView?
        private var auth: ASWebAuthenticationSession?
        private var signingIn = false
        private let ours = ["civilinitiatives.org", "hehs-live.duckdns.org"]

        init(model: AppModel) { self.model = model }

        private func isOurs(_ u: URL) -> Bool {
            guard let h = u.host else { return false }
            return ours.contains { h == $0 || h.hasSuffix("." + $0) }
        }

        @objc func pull(_ r: UIRefreshControl) { web?.reload(); r.endRefreshing() }

        func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
            if m.name == "launchpadGame", (m.body as? String) == "rush" {
                DispatchQueue.main.async { self.model.showGame = true }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let u = action.request.url else { decisionHandler(.allow); return }
            let scheme = u.scheme ?? ""
            if scheme == "about" || scheme == "data" || scheme == "blob" { decisionHandler(.allow); return }
            /* Microsoft (and a school's own sign-in pages) work fine in here;
               only Google goes through Apple's sign-in sheet. */
            if u.host == "hehs-live.duckdns.org" && u.path.hasPrefix("/auth/v1/authorize") {
                let provider = URLComponents(url: u, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "provider" }?.value ?? "google"
                if provider == "google" { decisionHandler(.cancel); signIn(provider); return }
                signingIn = true
                decisionHandler(.allow); return
            }
            if scheme != "https" && scheme != "http" { decisionHandler(.cancel); UIApplication.shared.open(u); return }
            if isOurs(u) {
                if (u.host ?? "").hasSuffix("civilinitiatives.org") { signingIn = false }
                decisionHandler(.allow); return
            }
            if signingIn { decisionHandler(.allow); return }
            decisionHandler(.cancel)
            UIApplication.shared.open(u)
        }

        /* target="_blank" links */
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let u = action.request.url {
                if isOurs(u) { webView.load(action.request) } else { UIApplication.shared.open(u) }
            }
            return nil
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation nav: WKNavigation!, withError error: Error) {
            let e = error as NSError
            if e.code == NSURLErrorCancelled { return }
            webView.loadHTMLString(Self.offline, baseURL: nil)
        }

        func signIn(_ provider: String) {
            let p = provider.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "google"
            guard let url = URL(string: "https://civilinitiatives.org/?applogin=\(p)&ret=ios") else { return }
            let s = ASWebAuthenticationSession(url: url, callbackURLScheme: "hehlaunchpad") { [weak self] back, _ in
                guard let self = self, let back = back, let target = AppModel.sessionURL(from: back) else { return }
                DispatchQueue.main.async { self.web?.load(URLRequest(url: target)) }
            }
            s.presentationContextProvider = self
            s.prefersEphemeralWebBrowserSession = false
            auth = s
            s.start()
        }

        func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
            web?.window ?? ASPresentationAnchor()
        }

        static let offline = """
        <html><head><meta name=viewport content="width=device-width,initial-scale=1"></head>
        <body style="margin:0;height:100vh;display:grid;place-items:center;background:#3730A3;color:#fff;font-family:-apple-system,sans-serif;text-align:center">
        <div><div style="font-size:64px">🚀</div><h2>Can't reach the Launchpad</h2>
        <p style="color:#c7d2fe">Check your Wi-Fi, then pull down to try again.</p>
        <a href="https://civilinitiatives.org/" style="display:inline-block;padding:12px 22px;border-radius:12px;background:#f59e0b;color:#fff;font-weight:700;text-decoration:none">Try again</a></div></body></html>
        """
    }
}
