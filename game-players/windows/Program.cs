// A Launchpad Game for Windows.
//
// The game is not inside the program when it's built: the Game Maker adds
// it to the END of this EXE when someone downloads their game, like this:
//   [the EXE] [game JSON, UTF-8] [JSON length: 4 bytes, little-endian] ["HLGAME01"]
// The JSON is { "title": "...", "by": "...", "html": "..." }. Windows runs
// an EXE with extra bytes on the end without noticing them.
//
// The game runs in WebView2 with the same lock as on the Launchpad: no
// internet at all, so a game can only ever be a game.
using System;
using System.IO;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace LaunchpadGame
{
    static class Program
    {
        const string Magic = "HLGAME01";
        const string Lock = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; form-action 'none'\">";
        static string Home;

        [STAThread]
        static void Main()
        {
            AppDomain.CurrentDomain.AssemblyResolve += LoadEmbedded;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            string title, by, html;
            if (!ReadGame(out title, out by, out html))
            {
                MessageBox.Show("This copy has no game in it. Download your game again from the Game Maker on the Homework Launchpad.", "Launchpad Game");
                return;
            }
            string id;
            using (var sha = SHA256.Create()) id = BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(title + "\n" + html))).Replace("-", "").Substring(0, 16);
            Home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LaunchpadGames", id);
            Directory.CreateDirectory(Home);
            Run(title, by, html);
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        static void Run(string title, string by, string html) { Application.Run(new GameForm(title, by, html, UnpackLoader(), Home)); }

        static bool ReadGame(out string title, out string by, out string html)
        {
            title = by = html = null;
            try
            {
                var bytes = File.ReadAllBytes(Assembly.GetExecutingAssembly().Location);
                int n = bytes.Length;
                if (n < 12 || Encoding.ASCII.GetString(bytes, n - 8, 8) != Magic) return false;
                int len = BitConverter.ToInt32(bytes, n - 12);
                if (len <= 0 || len > n - 12) return false;
                var json = Encoding.UTF8.GetString(bytes, n - 12 - len, len);
                title = Field(json, "title") ?? "Launchpad Game";
                by = Field(json, "by") ?? "";
                html = Field(json, "html");
                if (html == null) return false;
                var m = Regex.Match(html, @"^\s*<!doctype[^>]*>", RegexOptions.IgnoreCase);
                html = m.Success ? m.Value + Lock + html.Substring(m.Length) : Lock + html;
                return true;
            }
            catch { return false; }
        }

        /* A tiny reader for one string field of flat JSON written by the Game Maker. */
        static string Field(string json, string name)
        {
            var key = "\"" + name + "\":\"";
            int i = json.IndexOf(key, StringComparison.Ordinal);
            if (i < 0) return null;
            i += key.Length;
            var sb = new StringBuilder();
            while (i < json.Length)
            {
                char c = json[i++];
                if (c == '"') return sb.ToString();
                if (c != '\\') { sb.Append(c); continue; }
                char e = json[i++];
                switch (e)
                {
                    case 'n': sb.Append('\n'); break;
                    case 'r': sb.Append('\r'); break;
                    case 't': sb.Append('\t'); break;
                    case 'b': sb.Append('\b'); break;
                    case 'f': sb.Append('\f'); break;
                    case 'u': sb.Append((char)Convert.ToInt32(json.Substring(i, 4), 16)); i += 4; break;
                    default: sb.Append(e); break;
                }
            }
            return null;
        }

        static Assembly LoadEmbedded(object sender, ResolveEventArgs e)
        {
            var name = new AssemblyName(e.Name).Name + ".dll";
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("lib." + name))
            {
                if (s == null) return null;
                var buf = new byte[s.Length]; s.Read(buf, 0, buf.Length);
                return Assembly.Load(buf);
            }
        }

        static string UnpackLoader()
        {
            string arch;
            switch (RuntimeInformation.ProcessArchitecture)
            {
                case Architecture.Arm64: arch = "arm64"; break;
                case Architecture.X86: arch = "x86"; break;
                default: arch = "x64"; break;
            }
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LaunchpadGames", "bin", arch);
            var file = Path.Combine(dir, "WebView2Loader.dll");
            try
            {
                Directory.CreateDirectory(dir);
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("native." + arch + ".WebView2Loader.dll"))
                    if (s != null && (!File.Exists(file) || new FileInfo(file).Length != s.Length))
                        using (var f = File.Create(file)) s.CopyTo(f);
            }
            catch { }
            return dir;
        }
    }

    class GameForm : Form
    {
        readonly WebView2 web = new WebView2 { Dock = DockStyle.Fill };
        readonly string html, loaderDir, home;

        public GameForm(string title, string by, string html, string loaderDir, string home)
        {
            this.html = html; this.loaderDir = loaderDir; this.home = home;
            Text = title + (string.IsNullOrEmpty(by) ? "" : " - by " + by) + " (Homework Launchpad)";
            try { Icon = System.Drawing.Icon.ExtractAssociatedIcon(Assembly.GetExecutingAssembly().Location); } catch { }
            ClientSize = new System.Drawing.Size(1100, 750);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = System.Drawing.Color.Black;
            KeyPreview = true;
            KeyDown += (s, e) => { if (e.KeyCode == Keys.F11) ToggleFull(); };
            Controls.Add(web);
            Load += async (s, e) =>
            {
                try
                {
                    CoreWebView2Environment.SetLoaderDllFolderPath(loaderDir);
                    var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(home, "web"));
                    await web.EnsureCoreWebView2Async(env);
                    var c = web.CoreWebView2;
                    c.Settings.AreDevToolsEnabled = false;
                    c.Settings.IsStatusBarEnabled = false;
                    c.Settings.AreDefaultContextMenusEnabled = false;
                    /* The game stays the game: no going anywhere else, no new windows. */
                    c.NewWindowRequested += (o, a) => { a.Handled = true; };
                    c.NavigationStarting += (o, a) => { if (!a.Uri.StartsWith("https://game.launchpad/")) a.Cancel = true; };
                    File.WriteAllText(Path.Combine(home, "index.html"), html, new UTF8Encoding(false));
                    c.SetVirtualHostNameToFolderMapping("game.launchpad", home, CoreWebView2HostResourceAccessKind.Deny);
                    c.Navigate("https://game.launchpad/index.html");
                }
                catch (Exception ex)
                {
                    MessageBox.Show("This game needs Microsoft Edge WebView2, which comes with Windows 10 and 11. If it's missing, get it free from Microsoft, then try again.\n\n" + ex.Message, "Launchpad Game");
                    Close();
                }
            };
        }

        FormWindowState was;
        void ToggleFull()
        {
            if (FormBorderStyle == FormBorderStyle.None) { FormBorderStyle = FormBorderStyle.Sizable; WindowState = was; }
            else { was = WindowState; FormBorderStyle = FormBorderStyle.None; WindowState = FormWindowState.Maximized; }
        }
    }
}
