// The Launchpad window: the same pages and design as the website, in
// WebView2, without a browser around them. Games run at the "high"
// quality - PCs can take the extra glow, particles and nebula clouds.
//
// Signing in with Google or Microsoft happens in the normal browser
// (Google refuses app web views): the site's ?applogin page signs in there
// and hands back hehlaunchpad://login#at=..&rt=.., which Windows passes to
// this app; we then open ?appsession=1#at=..&rt=.. here.
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Pipes;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace Launchpad
{
    public class MainForm : Form
    {
        const string HomeUrl = "https://civilinitiatives.org/";
        static readonly string[] Ours = { "civilinitiatives.org", "hehs-live.duckdns.org" };

        readonly WebView2 web = new WebView2();
        readonly string startLink;
        readonly string loaderDir;
        bool full;
        bool signingIn;
        FormWindowState before;

        public MainForm(string link, string loaderDir)
        {
            startLink = link;
            this.loaderDir = loaderDir;
            Text = "Homework Launchpad";
            try { Icon = Icon.ExtractAssociatedIcon(Assembly.GetExecutingAssembly().Location); } catch { }
            BackColor = Color.FromArgb(0x37, 0x30, 0xA3);
            StartPosition = FormStartPosition.CenterScreen;
            var area = Screen.PrimaryScreen.WorkingArea;
            Size = new Size(Math.Min(1300, area.Width - 60), Math.Min(880, area.Height - 60));
            MinimumSize = new Size(480, 400);
            KeyPreview = true;
            web.Dock = DockStyle.Fill;
            web.DefaultBackgroundColor = BackColor;
            Controls.Add(web);
            Load += async (s, e) => await Start();
            KeyDown += (s, e) => { if (e.KeyCode == Keys.F11) ToggleFull(); };
            FormClosed += (s, e) => Updater.OnExit();
            ListenForLinks();
        }

        async Task Start()
        {
            try
            {
                CoreWebView2Environment.SetLoaderDllFolderPath(loaderDir);
                var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Program.Home, "web"));
                await web.EnsureCoreWebView2Async(env);
            }
            catch (WebView2RuntimeNotFoundException)
            {
                if (MessageBox.Show(this,
                    "The Launchpad needs Microsoft's WebView2, which comes with Windows 10 and 11 but is missing on this PC.\n\nOpen the download page now?",
                    "Homework Launchpad", MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
                    Open("https://developer.microsoft.com/microsoft-edge/webview2/");
                Close();
                return;
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "The Launchpad couldn't start: " + ex.Message, "Homework Launchpad");
                Close();
                return;
            }

            var core = web.CoreWebView2;
            core.Settings.UserAgent = core.Settings.UserAgent + " LaunchpadApp/2.0 (Windows)";
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = true;
            /* What the pages can ask the app: which platform, and to open a game. */
            await core.AddScriptToExecuteOnDocumentCreatedAsync(
                "window.LaunchpadApp={platform:function(){return 'windows'},version:function(){return '2.0'}," +
                "openGame:function(n){ if(n==='rush') location.href='https://arcade.civilinitiatives.org/rush.html?q=high'; }};");
            core.NavigationStarting += OnNavigate;
            core.NewWindowRequested += (s, e) =>
            {
                e.Handled = true;
                var u = new Uri(e.Uri);
                if (IsOurs(u)) core.Navigate(e.Uri); else Open(e.Uri);
            };
            core.DocumentTitleChanged += (s, e) =>
            {
                var t = core.DocumentTitle;
                Text = string.IsNullOrWhiteSpace(t) || t == "Homework Launchpad" ? "Homework Launchpad" : t + " - Homework Launchpad";
            };
            core.NavigationCompleted += (s, e) =>
            {
                if (!e.IsSuccess && e.WebErrorStatus != CoreWebView2WebErrorStatus.OperationCanceled)
                    core.NavigateToString(Offline());
            };
            core.WebMessageReceived += (s, e) =>
            {
                if (e.TryGetWebMessageAsString() == "retry") core.Navigate(HomeUrl);
            };
            if (!HandleLink(startLink)) core.Navigate(HomeUrl);
            Updater.CheckLater(this);
        }

        static bool IsOurs(Uri u)
        {
            foreach (var h in Ours) if (u.Host == h || u.Host.EndsWith("." + h)) return true;
            return false;
        }

        void OnNavigate(object sender, CoreWebView2NavigationStartingEventArgs e)
        {
            if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out var u)) return;
            if (u.Scheme == "data" || u.Scheme == "about") return;
            /* Google / Microsoft sign-in: in the real browser, then back here. */
            /* Microsoft (and a school's own sign-in pages) work fine in here, so
               that whole trip stays in the app; only Google goes to the browser. */
            if (u.Host == "hehs-live.duckdns.org" && u.AbsolutePath.StartsWith("/auth/v1/authorize"))
            {
                var provider = Query(u, "provider") ?? "google";
                if (provider == "google")
                {
                    e.Cancel = true;
                    Open(HomeUrl + "?applogin=google&ret=win");
                    return;
                }
                signingIn = true;
                return;
            }
            if (u.Scheme != "https" && u.Scheme != "http") { e.Cancel = true; Open(e.Uri); return; }
            if (IsOurs(u)) { if (u.Host.EndsWith("civilinitiatives.org")) signingIn = false; return; }
            if (signingIn) return;
            e.Cancel = true; Open(e.Uri);
        }

        static string Query(Uri u, string key)
        {
            foreach (var part in u.Query.TrimStart('?').Split('&'))
            {
                var kv = part.Split(new[] { '=' }, 2);
                if (kv.Length == 2 && kv[0] == key) return Uri.UnescapeDataString(kv[1]);
            }
            return null;
        }

        /* hehlaunchpad://login#at=..&rt=.. from the browser */
        bool HandleLink(string link)
        {
            if (string.IsNullOrEmpty(link) || web.CoreWebView2 == null) return false;
            if (!link.StartsWith("hehlaunchpad:", StringComparison.OrdinalIgnoreCase)) return false;
            var hash = link.IndexOf('#');
            if (hash < 0) return false;
            string at = null, rt = null;
            foreach (var part in link.Substring(hash + 1).Split('&'))
            {
                var kv = part.Split(new[] { '=' }, 2);
                if (kv.Length != 2) continue;
                if (kv[0] == "at") at = Uri.UnescapeDataString(kv[1]);
                if (kv[0] == "rt") rt = Uri.UnescapeDataString(kv[1]);
            }
            if (string.IsNullOrEmpty(at) || string.IsNullOrEmpty(rt)) return false;
            web.CoreWebView2.Navigate(HomeUrl + "?appsession=1#at=" + Uri.EscapeDataString(at) + "&rt=" + Uri.EscapeDataString(rt));
            return true;
        }

        void ListenForLinks()
        {
            var t = new Thread(() =>
            {
                while (!IsDisposed)
                {
                    try
                    {
                        using (var server = new NamedPipeServerStream(Program.PipeName, PipeDirection.In))
                        {
                            server.WaitForConnection();
                            string msg;
                            using (var r = new StreamReader(server)) msg = r.ReadToEnd();
                            BeginInvoke(new Action(() =>
                            {
                                if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
                                Activate(); BringToFront();
                                HandleLink(msg);
                            }));
                        }
                    }
                    catch { Thread.Sleep(500); }
                }
            }) { IsBackground = true };
            t.Start();
        }

        void ToggleFull()
        {
            if (!full) { before = WindowState; FormBorderStyle = FormBorderStyle.None; WindowState = FormWindowState.Normal; WindowState = FormWindowState.Maximized; }
            else { FormBorderStyle = FormBorderStyle.Sizable; WindowState = before; }
            full = !full;
        }

        static void Open(string url)
        {
            try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
        }

        static string Offline() =>
            "<html><body style='margin:0;height:100vh;display:grid;place-items:center;background:#3730A3;color:#fff;font-family:Segoe UI,sans-serif;text-align:center'>" +
            "<div><div style='font-size:64px'>🚀</div><h2>Can't reach the Launchpad</h2><p style='color:#c7d2fe'>Check the internet connection, then try again.</p>" +
            "<button onclick=\"chrome.webview.postMessage('retry')\" style='font-size:16px;padding:12px 22px;border:0;border-radius:12px;background:#f59e0b;color:#fff;font-weight:700;cursor:pointer'>Try again</button></div></body></html>";
    }
}
