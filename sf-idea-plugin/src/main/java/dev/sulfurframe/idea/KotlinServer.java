package dev.sulfurframe.idea;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * A tiny compile server for Kotlin, run as its own JVM with the project's Kotlin compiler jars on the class path:
 *   java -cp <plugin jar>;<kotlinCompilerClasspath> dev.sulfurframe.idea.KotlinServer
 * One request per line on stdin: the compiler arguments separated by TAB. Answer on stdout: the compiler's messages,
 * then "SF-EXIT <ExitCode>". The compiler stays loaded and warm between requests.
 *
 * Why a separate process: inside the IDE the Kotlin compiler (which bundles its own IntelliJ core) failed, presumably
 * picking up the IDE's environment; out of process it behaves exactly as under Gradle.
 */
public final class KotlinServer {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        Object compiler = c.getConstructor().newInstance();
        Method exec = c.getMethod("exec", PrintStream.class, String[].class);
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        out.println("SF-READY");
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isEmpty()) continue;
            Object code;
            try { code = exec.invoke(compiler, out, (Object) line.split("\t")); }
            catch (Throwable t) { out.println("e: " + t); code = "INTERNAL_ERROR"; }
            out.println("SF-EXIT " + code);
        }
    }

    private KotlinServer() {}
}
