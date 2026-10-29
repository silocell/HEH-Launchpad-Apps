// Keeps the downloaded Windows app up to date.
//
// A little after start-up it reads https://civilinitiatives.org/app/version.json.
// If there's a newer version it downloads the new EXE in the background,
// checks its SHA-256 against version.json (so a broken or swapped download
// is never used), then asks "Restart now?". Saying "Later" is fine: the new
// version is swapped in when the app closes.
//
// The swap: this EXE can't overwrite itself while it runs, so a tiny script
// waits for it to close, copies the new EXE over the old one (or, if that
// folder can't be written to, into %LOCALAPPDATA%\HomeworkLaunchpad) and
// starts it again if asked.
//
// The Microsoft Store copy never does any of this - the Store updates it.
using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Launchpad
{
    static class Updater
    {
        const string Feed = "https://civilinitiatives.org/app/version.json";
        static string pending;          // a downloaded, checked new EXE waiting to be swapped in
        static string pendingVersion;

        public static Version Current => Assembly.GetExecutingAssembly().GetName().Version;
        static string Exe => Assembly.GetExecutingAssembly().Location;
        public static bool FromStore => Exe.IndexOf(@"\WindowsApps\", StringComparison.OrdinalIgnoreCase) >= 0;

        public static void CheckLater(Form owner)
        {
            if (FromStore) return;
            var t = new Timer { Interval = 8000 };
            t.Tick += async (s, e) => { t.Stop(); t.Dispose(); await Check(owner); };
            t.Start();
        }

        static async Task Check(Form owner)
        {
            try
            {
                ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
                string json;
                using (var wc = new WebClient()) json = await wc.DownloadStringTaskAsync(Feed + "?t=" + DateTime.UtcNow.Ticks);
                var ver = Field(json, "version"); var url = Field(json, "url"); var sha = Field(json, "sha256");
                if (ver == null || url == null || sha == null) return;
                if (!Version.TryParse(ver, out var latest) || Normal(latest) <= Normal(Current)) return;
                if (!url.StartsWith("https://civilinitiatives.org/", StringComparison.OrdinalIgnoreCase)) return;

                var dir = Path.Combine(Program.Home, "update");
                Directory.CreateDirectory(dir);
                var file = Path.Combine(dir, "HomeworkLaunchpad-" + ver + ".exe");
                if (!File.Exists(file) || Sha256(file) != sha.ToLowerInvariant())
                {
                    var tmp = file + ".part";
                    using (var wc = new WebClient()) await wc.DownloadFileTaskAsync(url, tmp);
                    if (Sha256(tmp) != sha.ToLowerInvariant()) { File.Delete(tmp); return; }
                    if (File.Exists(file)) File.Delete(file);
                    File.Move(tmp, file);
                }
                pending = file; pendingVersion = ver;

                if (owner.IsDisposed) return;
                var r = MessageBox.Show(owner,
                    "A new version of the Homework Launchpad (" + ver + ") is ready.\n\nRestart now to use it? If you choose No, it will update next time you close the app.",
                    "Update ready", MessageBoxButtons.YesNo, MessageBoxIcon.Information);
                if (r == DialogResult.Yes) { Swap(true); Application.Exit(); }
            }
            catch { /* no internet, or the site is busy - try again next time */ }
        }

        /* Called when the app is closing. */
        public static void OnExit() { if (pending != null) Swap(false); }

        static void Swap(bool relaunch)
        {
            if (pending == null || !File.Exists(pending)) return;
            var target = Exe;
            if (!CanWrite(Path.GetDirectoryName(target))) target = Path.Combine(Program.Home, "HomeworkLaunchpad.exe");
            var pid = Process.GetCurrentProcess().Id;
            var bat = Path.Combine(Path.GetTempPath(), "hl-update-" + pid + ".cmd");
            var sb = new StringBuilder();
            sb.AppendLine("@echo off");
            sb.AppendLine(":wait");
            sb.AppendLine("tasklist /FI \"PID eq " + pid + "\" 2>nul | find \"" + pid + "\" >nul && (timeout /t 1 /nobreak >nul & goto wait)");
            sb.AppendLine("copy /y \"" + pending + "\" \"" + target + "\" >nul");
            if (relaunch) sb.AppendLine("start \"\" \"" + target + "\"");
            sb.AppendLine("del \"" + pending + "\" >nul 2>nul");
            sb.AppendLine("(goto) 2>nul & del \"%~f0\"");
            File.WriteAllText(bat, sb.ToString());
            Process.Start(new ProcessStartInfo("cmd.exe", "/c \"" + bat + "\"") { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
            pending = null;
        }

        static bool CanWrite(string dir)
        {
            try { var f = Path.Combine(dir, ".hl-write-test"); File.WriteAllText(f, "x"); File.Delete(f); return true; }
            catch { return false; }
        }

        static Version Normal(Version v) => new Version(v.Major, Math.Max(0, v.Minor), Math.Max(0, v.Build));

        static string Field(string json, string name)
        {
            var m = Regex.Match(json, "\"windows\"\\s*:\\s*\\{[^}]*\"" + name + "\"\\s*:\\s*\"([^\"]+)\"");
            return m.Success ? m.Groups[1].Value : null;
        }

        static string Sha256(string file)
        {
            using (var s = File.OpenRead(file)) using (var h = SHA256.Create())
                return BitConverter.ToString(h.ComputeHash(s)).Replace("-", "").ToLowerInvariant();
        }
    }
}
