// Homework Launchpad for Windows.
//
// Start-up: the WebView2 libraries live inside this EXE. Before anything
// touches them, Main hooks AssemblyResolve to load the managed ones from
// the EXE, and unpacks the right WebView2Loader.dll (x64/x86/arm64) to
// %LOCALAPPDATA%\HomeworkLaunchpad\bin.
//
// One window only: a second copy (for example started by the browser with
// hehlaunchpad://login#... after signing in) hands its link to the first
// copy through a named pipe and closes.
using System;
using System.IO;
using System.IO.Pipes;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

namespace Launchpad
{
    static class Program
    {
        public const string PipeName = "HomeworkLaunchpad.Pipe.v2";
        public static readonly string Home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HomeworkLaunchpad");

        [STAThread]
        static void Main(string[] args)
        {
            string link = args.Length > 0 ? args[0] : null;
            bool first;
            using (var only = new Mutex(true, "HomeworkLaunchpad.Single.v2", out first))
            {
                if (!first) { SendToFirst(link); return; }
                AppDomain.CurrentDomain.AssemblyResolve += LoadEmbedded;
                Directory.CreateDirectory(Home);
                if (!Updater.FromStore) RegisterProtocol();   // the Store copy declares it in its package instead
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Run(link);
            }
        }

        [MethodImpl(MethodImplOptions.NoInlining)]
        static void Run(string link)
        {
            var loaderDir = UnpackLoader();
            Application.Run(new MainForm(link, loaderDir));
        }

        static void SendToFirst(string link)
        {
            try
            {
                using (var c = new NamedPipeClientStream(".", PipeName, PipeDirection.Out))
                {
                    c.Connect(3000);
                    using (var w = new StreamWriter(c)) { w.Write(link ?? "show"); }
                }
            }
            catch { }
        }

        /* hehlaunchpad:// opens this app - for the user only, no admin needed. */
        static void RegisterProtocol()
        {
            try
            {
                var exe = Assembly.GetExecutingAssembly().Location;
                using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\hehlaunchpad"))
                {
                    k.SetValue("", "URL:Homework Launchpad");
                    k.SetValue("URL Protocol", "");
                    using (var ic = k.CreateSubKey("DefaultIcon")) ic.SetValue("", "\"" + exe + "\",0");
                    using (var cmd = k.CreateSubKey(@"shell\open\command")) cmd.SetValue("", "\"" + exe + "\" \"%1\"");
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
