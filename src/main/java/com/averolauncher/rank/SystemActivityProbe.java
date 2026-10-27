package com.averolauncher.rank;

import com.averolauncher.logging.AppLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort OS presence probe using only tools that already ship with the OS (no native libraries):
 * <ul>
 *   <li>Windows: one long-lived PowerShell process polling GetLastInputInfo / GetForegroundWindow.</li>
 *   <li>macOS: {@code ioreg} (HIDIdleTime) and {@code lsappinfo} (frontmost pid).</li>
 *   <li>Linux: {@code xprintidle} or GNOME Mutter's IdleMonitor over gdbus; {@code xdotool} for focus.</li>
 * </ul>
 * Anything unavailable is reported as unknown, never as "active" or "idle". Never throws.
 */
public final class SystemActivityProbe implements ActivityProbe {

    private static final String TAG = "ActivityProbe";
    private static final long STALE_NS = 25_000_000_000L;

    private enum Os { WINDOWS, MAC, LINUX, OTHER }

    private final Os os;
    private Process psProcess;
    private volatile long psIdleMs = -1, psFgPid = -1, psAtNano;
    private boolean psTried;

    public SystemActivityProbe() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        os = n.contains("win") ? Os.WINDOWS : n.contains("mac") ? Os.MAC : n.contains("nux") || n.contains("nix") ? Os.LINUX : Os.OTHER;
    }

    @Override
    public ActivitySample sample(long pid) {
        try {
            return switch (os) {
                case WINDOWS -> windows(pid);
                case MAC -> mac(pid);
                case LINUX -> linux(pid);
                default -> ActivitySample.unknown();
            };
        } catch (Throwable t) {
            AppLogger.get().warn(TAG, "probe failed: " + t.getMessage());
            return ActivitySample.unknown();
        }
    }

    @Override
    public synchronized void close() {
        if (psProcess != null) { psProcess.destroy(); psProcess = null; }
    }

    // ---------------------------------------------------------------- Windows

    private static final String PS_SCRIPT = String.join("\n",
            "$sig = @'",
            "using System; using System.Runtime.InteropServices;",
            "public static class AvIdle {",
            "  [StructLayout(LayoutKind.Sequential)] struct LII { public uint cbSize; public uint dwTime; }",
            "  [DllImport(\"user32.dll\")] static extern bool GetLastInputInfo(ref LII p);",
            "  [DllImport(\"user32.dll\")] static extern IntPtr GetForegroundWindow();",
            "  [DllImport(\"user32.dll\")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);",
            "  public static long Idle() { LII i = new LII(); i.cbSize = (uint)Marshal.SizeOf(i); GetLastInputInfo(ref i); return (long)(uint)((uint)Environment.TickCount - i.dwTime); }",
            "  public static long Fg() { uint p; GetWindowThreadProcessId(GetForegroundWindow(), out p); return (long)p; }",
            "}",
            "'@",
            "Add-Type -TypeDefinition $sig",
            "while ($true) { [Console]::Out.WriteLine(([AvIdle]::Idle()).ToString() + '|' + ([AvIdle]::Fg()).ToString()); [Console]::Out.Flush(); Start-Sleep -Seconds 5 }");

    private synchronized void ensurePowerShell() {
        if (psTried) return;
        psTried = true;
        try {
            String enc = Base64.getEncoder().encodeToString(PS_SCRIPT.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc).redirectErrorStream(true).start();
            psProcess = p;
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int bar = line.indexOf('|');
                        if (bar < 0) continue;
                        try {
                            long idle = Long.parseLong(line.substring(0, bar).trim());
                            long fg = Long.parseLong(line.substring(bar + 1).trim());
                            psIdleMs = idle; psFgPid = fg; psAtNano = System.nanoTime();
                        } catch (NumberFormatException ignored) { }
                    }
                } catch (Exception ignored) { }
            }, "avero-rank-activity-ps");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            AppLogger.get().info(TAG, "PowerShell probe unavailable: " + e.getMessage());
        }
    }

    private ActivitySample windows(long pid) {
        ensurePowerShell();
        long at = psAtNano;
        if (at == 0 || System.nanoTime() - at > STALE_NS) return ActivitySample.unknown();
        long age = (System.nanoTime() - at) / 1_000_000L;
        return new ActivitySample(psIdleMs + age, psFgPid == pid, "windows-ps");
    }

    // ---------------------------------------------------------------- macOS

    private ActivitySample mac(long pid) {
        Long idle = null;
        String io = run(2500, "ioreg", "-c", "IOHIDSystem");
        if (io != null) {
            int i = io.indexOf("\"HIDIdleTime\"");
            if (i >= 0) {
                int eq = io.indexOf('=', i);
                if (eq > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = eq + 1; k < io.length(); k++) {
                        char c = io.charAt(k);
                        if (Character.isDigit(c)) sb.append(c); else if (sb.length() > 0) break;
                    }
                    if (sb.length() > 0) idle = Long.parseLong(sb.toString()) / 1_000_000L;
                }
            }
        }
        Boolean focused = null;
        String front = run(2500, "lsappinfo", "front");
        if (front != null && !front.isBlank()) {
            String info = run(2500, "lsappinfo", "info", "-only", "pid", front.trim());
            Long fp = digitsAfterEquals(info);
            if (fp != null) focused = fp == pid;
        }
        return new ActivitySample(idle, focused, "macos-ioreg");
    }

    // ---------------------------------------------------------------- Linux

    private ActivitySample linux(long pid) {
        Long idle = null;
        String x = run(2000, "xprintidle");
        if (x != null && x.trim().matches("\\d+")) idle = Long.parseLong(x.trim());
        if (idle == null) {
            String g = run(2500, "gdbus", "call", "--session", "--dest", "org.gnome.Mutter.IdleMonitor",
                    "--object-path", "/org/gnome/Mutter/IdleMonitor/Core", "--method", "org.gnome.Mutter.IdleMonitor.GetIdletime");
            if (g != null) {
                StringBuilder sb = new StringBuilder();
                for (char c : g.toCharArray()) if (Character.isDigit(c)) sb.append(c);
                if (sb.length() > 0 && sb.length() < 15) idle = Long.parseLong(sb.toString());
            }
        }
        Boolean focused = null;
        String w = run(2000, "xdotool", "getactivewindow", "getwindowpid");
        if (w != null && w.trim().matches("\\d+")) focused = Long.parseLong(w.trim()) == pid;
        return new ActivitySample(idle, focused, idle != null || focused != null ? "linux" : "none");
    }

    // ---------------------------------------------------------------- helpers

    private static Long digitsAfterEquals(String s) {
        if (s == null) return null;
        int eq = s.indexOf('=');
        if (eq < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = eq + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) sb.append(c); else if (sb.length() > 0) break;
        }
        return sb.length() == 0 ? null : Long.parseLong(sb.toString());
    }

    /** Runs a command with a hard timeout and returns stdout, or null on any failure. */
    private static String run(long timeoutMs, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null && out.length() < 200_000) out.append(l).append('\n');
                } catch (Exception ignored) { }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return null; }
            reader.join(500);
            return p.exitValue() == 0 ? out.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
