// Window capture to raw video: PrintWindow(PW_RENDERFULLCONTENT) of one process's main window, so the frames have the
// window's true colours (HDR desktops make screen grabs darker) and nothing that covers it gets in.
//   wincapvid.exe <pid> <title substring> <fps> <seconds>   -> raw BGRA frames on stdout, "W H" on stderr first
// Pipe into: ffmpeg -f rawvideo -pix_fmt bgra -s WxH -r <fps> -i - ...
// Build: C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /O /unsafe /r:System.Drawing.dll wincapvid.cs
using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

class WinCapVid {
    [StructLayout(LayoutKind.Sequential)] struct R { public int l, t, r, b; }
    delegate bool EnumProc(IntPtr h, IntPtr p);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr p);
    [DllImport("user32.dll")] static extern int GetWindowThreadProcessId(IntPtr h, out int pid);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out R r);
    [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();

    static void Main(string[] a) {
        SetProcessDPIAware();
        int pid = int.Parse(a[0]); string title = a[1]; int fps = int.Parse(a[2]); double seconds = double.Parse(a[3]);
        IntPtr win = IntPtr.Zero;
        EnumWindows((h, p) => {
            int wp; GetWindowThreadProcessId(h, out wp);
            var sb = new StringBuilder(512); GetWindowText(h, sb, 512);
            if (wp == pid && IsWindowVisible(h) && sb.ToString().Contains(title)) win = h;
            return true;
        }, IntPtr.Zero);
        if (win == IntPtr.Zero) { Console.Error.WriteLine("window not found"); Environment.Exit(1); }
        R r; GetWindowRect(win, out r);
        int w = (r.r - r.l) & ~1, hgt = (r.b - r.t) & ~1;
        Console.Error.WriteLine(w + " " + hgt);
        Console.Error.WriteLine("start " + (DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalSeconds.ToString("F3"));
        var bmp = new Bitmap(w, hgt, PixelFormat.Format32bppArgb);
        var buf = new byte[w * hgt * 4];
        var outs = Console.OpenStandardOutput();
        var clock = Stopwatch.StartNew();
        long frames = (long)(fps * seconds), late = 0;
        for (long i = 0; i < frames; i++) {
            using (var g = Graphics.FromImage(bmp)) {
                IntPtr dc = g.GetHdc();
                PrintWindow(win, dc, 2);
                g.ReleaseHdc(dc);
            }
            var d = bmp.LockBits(new Rectangle(0, 0, w, hgt), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
            Marshal.Copy(d.Scan0, buf, 0, buf.Length);
            bmp.UnlockBits(d);
            outs.Write(buf, 0, buf.Length);
            // one frame per 1/fps of wall time: when capture is slow, the same picture repeats so timing stays real
            long due = (long)((i + 1) * 1000.0 / fps), now = clock.ElapsedMilliseconds;
            if (now < due) System.Threading.Thread.Sleep((int)(due - now));
            else while (clock.ElapsedMilliseconds > (long)((i + 2) * 1000.0 / fps) && i + 1 < frames) { outs.Write(buf, 0, buf.Length); i++; late++; }
        }
        outs.Flush();
        Console.Error.WriteLine("frames " + frames + ", repeated " + late);
    }
}
