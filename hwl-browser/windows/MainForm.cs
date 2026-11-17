// HWL Browser's window: a toolbar (back, home, address, reload) and WebView2.
// Every website you open (typed, searched, a link inside a page, or a pop-up) is checked first by
// the Launchpad's AI safety check, unless it's a trusted learning site.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Net;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace HwlBrowser
{
    public class MainForm : Form
    {
        const string CheckUrl = "https://hehs-live.duckdns.org/functions/v1/linkcheck";
        const string AnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzkyODMyMjkwLCJleHAiOjIxMDgxOTIyOTB9.7gFhQa5RBwVtiOPLO7Qf2-aVEbkd5xgpLiN1J06Gybo";
        static readonly Regex Trusted = new Regex(@"(^|\.)(wikipedia\.org|wikimedia\.org|bbc\.co\.uk|civilinitiatives\.org|chausima\.com|kiddle\.co|nasa\.gov|nhm\.ac\.uk|britannica\.com|natgeokids\.com|mathsisfun\.com|gov\.uk|scratch\.mit\.edu|khanacademy\.org|developer\.mozilla\.org|w3schools\.com|duckdns\.org)$", RegexOptions.IgnoreCase);

        readonly string loaderDir;
        readonly WebView2 web = new WebView2();
        readonly TextBox addr = new TextBox();
        readonly Label cover = new Label();
        readonly HashSet<string> okUrls = new HashSet<string>();
        readonly Dictionary<string, string> badHosts = new Dictionary<string, string>();
        string homeFile, homeUrl;

        public MainForm(string loaderDir)
        {
            this.loaderDir = loaderDir;
            Text = "HWL Browser";
            try { Icon = Icon.ExtractAssociatedIcon(Assembly.GetExecutingAssembly().Location); } catch { }
            BackColor = Color.White;
            StartPosition = FormStartPosition.CenterScreen;
            var area = Screen.PrimaryScreen.WorkingArea;
            Size = new Size(Math.Min(1280, area.Width - 60), Math.Min(860, area.Height - 60));
            MinimumSize = new Size(480, 400);

            var bar = new TableLayoutPanel { Dock = DockStyle.Top, Height = 46, ColumnCount = 5, RowCount = 1, Padding = new Padding(6, 6, 6, 4), BackColor = Color.White };
            bar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 40));
            bar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 40));
            bar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            bar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 40));
            bar.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 40));
            Button B(string t, string tip, Action a)
            {
                var b = new Button { Text = t, Dock = DockStyle.Fill, FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI Symbol", 13f), Margin = new Padding(2), TabStop = false };
                b.FlatAppearance.BorderSize = 0; new ToolTip().SetToolTip(b, tip); b.Click += (s, e) => a(); return b;
            }
            bar.Controls.Add(B("←", "Back", () => { if (web.CanGoBack) web.GoBack(); }), 0, 0);
            bar.Controls.Add(B("⌂", "Home", () => web.CoreWebView2?.Navigate(homeUrl)), 1, 0);
            addr.Dock = DockStyle.Fill; addr.Font = new Font("Segoe UI", 11.5f); addr.Margin = new Padding(6, 5, 6, 0);
            addr.KeyDown += (s, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; Go(addr.Text); } };
            bar.Controls.Add(addr, 2, 0);
            bar.Controls.Add(B("↻", "Reload", () => web.Reload()), 3, 0);
            bar.Controls.Add(B("⤢", "Full screen (F11)", ToggleFull), 4, 0);

            cover.Text = "Checking this page is OK for school…"; cover.Dock = DockStyle.Fill; cover.TextAlign = ContentAlignment.MiddleCenter;
            cover.Font = new Font("Segoe UI", 15f, FontStyle.Bold); cover.BackColor = Color.White; cover.Visible = false;
            web.Dock = DockStyle.Fill;
            Controls.Add(cover); Controls.Add(web); Controls.Add(bar);
            KeyPreview = true;
            KeyDown += (s, e) => { if (e.KeyCode == Keys.F11) ToggleFull(); if (e.Control && e.KeyCode == Keys.L) { addr.Focus(); addr.SelectAll(); } };
            Load += async (s, e) => await Start();
        }

        async Task Start()
        {
            // the built-in HWL Search page
            homeFile = Path.Combine(Program.Home, "home.html");
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("home.html"))
            using (var f = File.Create(homeFile)) s.CopyTo(f);
            homeUrl = new Uri(homeFile).AbsoluteUri;
            try
            {
                CoreWebView2Environment.SetLoaderDllFolderPath(loaderDir);
                var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Program.Home, "web"));
                await web.EnsureCoreWebView2Async(env);
            }
            catch (WebView2RuntimeNotFoundException)
            {
                if (MessageBox.Show(this, "HWL Browser needs Microsoft's WebView2, which comes with Windows 10 and 11 but is missing on this PC.\n\nOpen the download page now?",
                        "HWL Browser", MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
                    System.Diagnostics.Process.Start("https://developer.microsoft.com/microsoft-edge/webview2/");
                Close(); return;
            }
            var core = web.CoreWebView2;
            core.Settings.UserAgent = core.Settings.UserAgent + " HWLBrowser/1.0";
            core.Settings.AreDevToolsEnabled = false;
            core.NavigationStarting += (s, e) => { if (!AllowedNow(e.Uri)) e.Cancel = true; };
            core.NewWindowRequested += (s, e) => { e.Handled = true; if (AllowedNow(e.Uri)) core.Navigate(e.Uri); };
            core.SourceChanged += (s, e) => ShowAddress(core.Source);
            core.DocumentTitleChanged += (s, e) => Text = (string.IsNullOrEmpty(core.DocumentTitle) ? "" : core.DocumentTitle + " - ") + "HWL Browser";
            core.Navigate(homeUrl);
        }

        void ShowAddress(string url)
        {
            if (addr.Focused) return;
            if (url.StartsWith(homeUrl, StringComparison.OrdinalIgnoreCase))
            {
                var i = url.IndexOf("#q=", StringComparison.Ordinal);
                addr.Text = i >= 0 ? Uri.UnescapeDataString(url.Substring(i + 3)) : "";
            }
            else if (!url.StartsWith("data:")) addr.Text = url;
        }

        void Go(string text)
        {
            var t = (text ?? "").Trim(); if (t.Length == 0) return;
            web.Focus();
            if (!t.Contains(" ") && t.Contains("."))
            {
                var u = Regex.IsMatch(t, "^[a-z]+://", RegexOptions.IgnoreCase) ? t : "https://" + t;
                if (AllowedNow(u)) web.CoreWebView2.Navigate(u);
            }
            else web.CoreWebView2.Navigate(homeUrl + "#q=" + Uri.EscapeDataString(t));
        }

        bool AllowedNow(string url)
        {
            if (string.IsNullOrEmpty(url)) return false;
            if ((homeUrl != null && url.StartsWith(homeUrl, StringComparison.OrdinalIgnoreCase)) || url.StartsWith("about:") || url.StartsWith("data:")) return true;
            Uri u;
            if (!Uri.TryCreate(url, UriKind.Absolute, out u) || (u.Scheme != "https" && u.Scheme != "http")) { Blocked(url, "HWL Browser only opens web pages."); return false; }
            var host = u.Host.ToLowerInvariant();
            if (Trusted.IsMatch(host) || okUrls.Contains(url)) return true;
            string why;
            if (badHosts.TryGetValue(host, out why)) { Blocked(url, why); return false; }
            CheckThenOpen(url, host);
            return false;
        }

        async void CheckThenOpen(string url, string host)
        {
            cover.Visible = true; cover.BringToFront(); addr.Text = url;
            bool ok = false; string reason = "The safety check isn't answering right now, so this page stays closed. Try again in a minute.";
            try
            {
                var res = await Task.Run(() =>
                {
                    ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
                    var req = (HttpWebRequest)WebRequest.Create(CheckUrl);
                    req.Method = "POST"; req.ContentType = "application/json"; req.Timeout = 25000;
                    req.Headers["apikey"] = AnonKey; req.Headers["Authorization"] = "Bearer " + AnonKey; req.Headers["x-hwl-app"] = "hwl-browser/1.0-windows";
                    var body = Encoding.UTF8.GetBytes("{\"url\":\"" + url.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"}");
                    using (var s = req.GetRequestStream()) s.Write(body, 0, body.Length);
                    try { using (var r = (HttpWebResponse)req.GetResponse()) using (var rd = new StreamReader(r.GetResponseStream())) return rd.ReadToEnd(); }
                    catch (WebException we) when (we.Response != null) { using (var rd = new StreamReader(we.Response.GetResponseStream())) return rd.ReadToEnd(); }
                });
                ok = Regex.IsMatch(res, "\"ok\"\\s*:\\s*true");
                var m = Regex.Match(res, "\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
                if (m.Success) reason = Regex.Unescape(m.Groups[1].Value);
            }
            catch { }
            cover.Visible = false;
            if (ok) { okUrls.Add(url); web.CoreWebView2.Navigate(url); }
            else { if (reason.Contains("isn't suitable")) badHosts[host] = reason; Blocked(url, reason); }
        }

        void Blocked(string url, string why)
        {
            Func<string, string> esc = s => WebUtility.HtmlEncode(s);
            web.CoreWebView2?.NavigateToString("<!doctype html><body style=\"font-family:Segoe UI,sans-serif;text-align:center;padding:70px 24px;color:#1d2433\">" +
                "<div style=\"font-size:56px\">🛡️</div><h2>Not opened</h2><p style=\"color:#5b6478\">" + esc(why) + "</p>" +
                "<p style=\"color:#9aa1b5;font-size:13px;word-break:break-all\">" + esc(url) + "</p></body>");
            addr.Text = url;
        }

        bool full; FormWindowState before;
        void ToggleFull()
        {
            if (!full) { before = WindowState; FormBorderStyle = FormBorderStyle.None; WindowState = FormWindowState.Normal; WindowState = FormWindowState.Maximized; }
            else { FormBorderStyle = FormBorderStyle.Sizable; WindowState = before; }
            full = !full;
        }
    }
}
