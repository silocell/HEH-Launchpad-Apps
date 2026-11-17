// HWL Browser for Windows. The WebView2 libraries live inside this EXE (same set-up as the
// Homework Launchpad app): Main loads the managed ones from the EXE and unpacks the right
// WebView2Loader.dll (x64/x86/arm64) to %LOCALAPPDATA%\HWLBrowser\bin.
using System;
using System.IO;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace HwlBrowser
{
    static class Program
    {
        public static readonly string Home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HWLBrowser");

        public static string StartLink;

        [STAThread]
        static void Main(string[] args)
        {
            StartLink = args.Length > 0 ? args[0] : null;
            Register();
            AppDomain.CurrentDomain.AssemblyResolve += LoadEmbedded;
            Directory.CreateDirectory(Home);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Run();
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        static void Run() { Application.Run(new MainForm(UnpackLoader())); }

        // hwlbrowser:// links (from net.chausima.com) open this app; per user, no admin needed
        static void Register()
        {
            try
            {
                var exe = Assembly.GetExecutingAssembly().Location;
                using (var k = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(@"Software\Classes\hwlbrowser"))
                {
                    k.SetValue("", "URL:HWL Browser"); k.SetValue("URL Protocol", "");
                    using (var i = k.CreateSubKey("DefaultIcon")) i.SetValue("", "\"" + exe + "\",0");
                    using (var c = k.CreateSubKey(@"shell\open\command")) c.SetValue("", "\"" + exe + "\" \"%1\"");
                }
            }
            catch { }
        }

        static Assembly LoadEmbedded(object sender, ResolveEventArgs e)
        {
            var name = new AssemblyName(e.Name).Name + ".dll";
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("lib." + name))
            {
                if (s == null) return null;
                var buf = new byte[s.Length];
                s.Read(buf, 0, buf.Length);
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
            var dir = Path.Combine(Home, "bin", arch);
            var file = Path.Combine(dir, "WebView2Loader.dll");
            try
            {
                Directory.CreateDirectory(dir);
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("native." + arch + ".WebView2Loader.dll"))
                {
                    if (s != null && (!File.Exists(file) || new FileInfo(file).Length != s.Length))
                        using (var f = File.Create(file)) s.CopyTo(f);
                }
            }
            catch { }
            return dir;
        }
    }
}
