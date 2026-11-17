package dev.sulfurframe.idea.embed;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.*;

/**
 * The exact command line of another process, argument by argument. Java's ProcessHandle.Info has no arguments on
 * Windows, so there it comes from the OS: NtQueryInformationProcess(ProcessCommandLineInformation) for the string
 * and CommandLineToArgvW to split it the way the C runtime of java.exe did. On Linux, /proc/&lt;pid&gt;/cmdline.
 */
public final class ProcessArgs {
    /** Arguments including argv[0], or null if they cannot be read. */
    public static List<String> of(long pid) {
        try {
            if (GameView.linux()) {
                byte[] raw = Files.readAllBytes(Path.of("/proc", Long.toString(pid), "cmdline"));
                var out = new ArrayList<String>();
                int start = 0;
                for (int i = 0; i < raw.length; i++)
                    if (raw[i] == 0) { out.add(new String(raw, start, i - start, StandardCharsets.UTF_8)); start = i + 1; }
                return out.isEmpty() ? null : out;
            }
            return Win.argv(pid);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Win {
        static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000, ProcessCommandLineInformation = 60;
        static final Linker L = Linker.nativeLinker();
        static final SymbolLookup K32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
        static final SymbolLookup NT = SymbolLookup.libraryLookup("ntdll", Arena.global());
        static final SymbolLookup SH = SymbolLookup.libraryLookup("shell32", Arena.global());

        static MethodHandle fn(SymbolLookup lib, String name, MemoryLayout ret, MemoryLayout... args) {
            return L.downcallHandle(lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name)), FunctionDescriptor.of(ret, args));
        }

        static final MethodHandle OpenProcess = fn(K32, "OpenProcess", JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT),
                CloseHandle = fn(K32, "CloseHandle", JAVA_INT, JAVA_LONG),
                LocalFree = fn(K32, "LocalFree", JAVA_LONG, ADDRESS),
                NtQuery = fn(NT, "NtQueryInformationProcess", JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                ToArgv = fn(SH, "CommandLineToArgvW", ADDRESS, ADDRESS, ADDRESS);

        static List<String> argv(long pid) throws Throwable {
            long h = (long) OpenProcess.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, (int) pid);
            if (h == 0) return null;
            try (Arena a = Arena.ofConfined()) {
                var len = a.allocate(JAVA_INT);
                int size = 4096;
                MemorySegment buf = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    buf = a.allocate(size, 8);
                    int status = (int) NtQuery.invokeExact(h, ProcessCommandLineInformation, buf, size, len);
                    if (status == 0) break;
                    if (len.get(JAVA_INT, 0) <= size) return null;   // a real error, not "buffer too small"
                    size = len.get(JAVA_INT, 0);
                    buf = null;
                }
                if (buf == null) return null;
                // UNICODE_STRING { USHORT Length; USHORT MaximumLength; PWSTR Buffer } (Buffer at offset 8 on x64)
                int bytes = Short.toUnsignedInt(buf.get(JAVA_SHORT, 0));
                MemorySegment chars = buf.get(ADDRESS, 8).reinterpret(bytes);
                var line = a.allocate(bytes + 2L);
                MemorySegment.copy(chars, 0, line, 0, bytes);
                line.set(JAVA_SHORT, bytes, (short) 0);
                var argc = a.allocate(JAVA_INT);
                MemorySegment arr = (MemorySegment) ToArgv.invokeExact(line, argc);
                if (arr.address() == 0) return null;
                try {
                    int n = argc.get(JAVA_INT, 0);
                    arr = arr.reinterpret((long) n * ADDRESS.byteSize());
                    var out = new ArrayList<String>(n);
                    for (int i = 0; i < n; i++) out.add(wide(arr.getAtIndex(ADDRESS, i)));
                    return out;
                } finally {
                    long ignored = (long) LocalFree.invokeExact(arr);
                }
            } finally {
                int ignored = (int) CloseHandle.invokeExact(h);
            }
        }

        /** NUL-terminated UTF-16 string at {@code p}. */
        static String wide(MemorySegment p) {
            var s = p.reinterpret(Integer.MAX_VALUE);
            int n = 0;
            while (s.get(JAVA_SHORT, 2L * n) != 0) n++;
            char[] c = new char[n];
            for (int i = 0; i < n; i++) c[i] = (char) s.get(JAVA_SHORT, 2L * i);
            return new String(c);
        }
    }

    private ProcessArgs() {}
}
