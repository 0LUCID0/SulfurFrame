package dev.sulfurframe.idea;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.util.io.Decompressor;
import com.intellij.util.io.HttpRequests;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One click to a JetBrains Runtime for the game, so hotswap can add and remove methods and fields. Without it, Run
 * client says so and offers the download; the alternative was File | Project Structure | SDKs | Download JDK by hand.
 *
 * IntelliJ's own JDK installer (jdkDownloader.JdkInstaller, JdkItem) is internal API, so this reads the same public
 * feed (download.jetbrains.com/jdk/feed/v1/jdks.json), picks the plain JBR SDK of the game's Java version for this
 * OS and CPU, downloads it with the IDE's proxy settings (HttpRequests), checks its SHA-256 and unpacks it to
 * ~/.jdks/jbr-&lt;version&gt;: the folder and name IntelliJ's own download uses, where {@link Jbr#find} looks.
 * JBR exists for Java 17, 21 and 25 (Minecraft 1.18-1.20.4, 1.20.5-1.21.x, 26.x); 8 and 16 have none.
 */
final class JbrDownloader {
    static final String FEED = "https://download.jetbrains.com/jdk/feed/v1/jdks.json";

    /** A JBR SDK package from the feed. */
    record Pkg(String version, String url, String sha256, long size, String rootPrefix, String folder) {}

    /** Java versions JetBrains publishes a JBR SDK for, of those Minecraft needs. */
    static boolean available(int major) { return major == 17 || major == 21 || major == 25; }

    private static final Set<Integer> offered = ConcurrentHashMap.newKeySet();

    /** Was the download offered for this Java version in this IDE session (self-test)? */
    static boolean wasOffered(int major) { return offered.contains(major); }

    /**
     * Run client found no JBR for the game: offer to download one, once per Java version and IDE session.
     * @param restart starts the game again (the new runtime applies from the next start), or null
     */
    static void offer(int major, Consumer<String> log, Runnable restart) {
        if (!available(major) || !offered.add(major) || Boolean.getBoolean("sulfurframe.noJbrOffer")) return;
        var n = NotificationGroupManager.getInstance().getNotificationGroup("SulfurFrame").createNotification(
                "JetBrains Runtime " + major + " for structural hotswap",
                "Without it only method bodies swap in; with it you can also add or remove methods and fields while the"
                        + " game runs. About 230 MB, installed in ~/.jdks.", NotificationType.INFORMATION);
        n.addAction(NotificationAction.createSimpleExpiring("Download", () -> downloadInBackground(major, log, restart)));
        n.notify(null);
    }

    /** Find, download and install in a background task with a progress bar; notifies when done. */
    static void downloadInBackground(int major, Consumer<String> log, Runnable restart) {
        ProgressManager.getInstance().run(new Task.Backgroundable(null, "Downloading JetBrains Runtime " + major, true) {
            @Override public void run(ProgressIndicator ind) {
                try {
                    ind.setText("Looking up JetBrains Runtime " + major);
                    Pkg p = find(major);
                    if (p == null) { done("No JetBrains Runtime " + major + " for " + os() + "/" + arch() + " in the JetBrains feed", NotificationType.WARNING, null); return; }
                    Path home = install(p, jdksDir(), ind);
                    log.accept("JetBrains Runtime " + p.version() + " installed in " + home + ": the next start of the game uses it");
                    done("JetBrains Runtime " + p.version() + " installed. The game uses it from its next start.", NotificationType.INFORMATION, restart);
                } catch (com.intellij.openapi.progress.ProcessCanceledException c) {
                    log.accept("JetBrains Runtime download cancelled");
                    throw c;
                } catch (Exception e) {
                    log.accept("JetBrains Runtime download failed: " + e);
                    done("JetBrains Runtime download failed: " + e.getMessage(), NotificationType.ERROR, null);
                }
            }
        });
    }

    private static void done(String text, NotificationType type, Runnable restart) {
        var n = NotificationGroupManager.getInstance().getNotificationGroup("SulfurFrame").createNotification("SulfurFrame", text, type);
        if (restart != null) n.addAction(NotificationAction.createSimpleExpiring("Restart the game now", restart));
        n.notify(null);
    }

    static Path jdksDir() { return Path.of(System.getProperty("user.home"), ".jdks"); }

    /** The plain JBR SDK of this Java version for this OS and CPU (tar.gz), newest first in the feed; null if none. */
    static Pkg find(int major) throws IOException {
        String os = os(), arch = arch();
        JsonObject root = JsonParser.parseString(HttpRequests.request(FEED).productNameAsUserAgent().readString()).getAsJsonObject();
        for (JsonElement e : root.getAsJsonArray("jdks")) {
            JsonObject j = e.getAsJsonObject();
            if (!"JetBrains".equals(str(j, "vendor")) || !"Runtime".equals(str(j, "product"))) continue;
            if (!str(j, "flavour").isEmpty() || j.has("preview") && j.get("preview").getAsBoolean()) continue;   // JCEF, DCEVM; previews
            if (!j.has("jdk_version_major") || j.get("jdk_version_major").getAsInt() != major) continue;
            for (JsonElement pe : j.getAsJsonArray("packages")) {
                JsonObject p = pe.getAsJsonObject();
                if (!os.equals(str(p, "os")) || !arch.equals(str(p, "arch")) || !"targz".equals(str(p, "package_type"))) continue;
                return new Pkg(str(j, "jdk_vendor_version"), str(p, "url"), str(p, "sha256"),
                        p.has("archive_size") ? p.get("archive_size").getAsLong() : -1, str(p, "package_root_prefix"), str(p, "install_folder_name"));
            }
        }
        return null;
    }

    /**
     * Download, check the SHA-256 and unpack into {@code dir}/&lt;install folder&gt;. Unpacked into a ".part" folder
     * first and moved in place at the end, so a cancelled or broken download never looks like an installed JBR.
     * An existing folder of the same name is kept if it already is that JBR.
     */
    static Path install(Pkg p, Path dir, ProgressIndicator ind) throws IOException {
        if (p.folder().isEmpty() || p.folder().contains("/") || p.folder().contains("\\") || p.folder().contains(".."))
            throw new IOException("unexpected install folder name: " + p.folder());
        Files.createDirectories(dir);
        Path target = dir.resolve(p.folder());
        if (Files.isDirectory(target)) {
            if (Jbr.isJbr(target, major(p.version()))) return target;
            target = dir.resolve(p.folder() + "-sulfurframe");
        }
        Path tmp = Files.createTempFile(dir, "sf-jbr-", ".tar.gz");
        Path part = dir.resolve(target.getFileName() + ".part");
        try {
            ind.setIndeterminate(false);
            ind.setText("Downloading JetBrains Runtime " + p.version() + (p.size() > 0 ? " (" + p.size() / (1024 * 1024) + " MB)" : ""));
            HttpRequests.request(p.url()).productNameAsUserAgent().saveToFile(tmp.toFile(), ind);
            ind.checkCanceled();
            ind.setText("Checking the download");
            ind.setIndeterminate(true);
            String got = sha256(tmp);
            if (!got.equalsIgnoreCase(p.sha256())) throw new IOException("checksum mismatch (expected " + p.sha256() + ", got " + got + ")");
            ind.setText("Unpacking JetBrains Runtime " + p.version());
            deleteTree(part);
            Decompressor d = new Decompressor.Tar(tmp);
            if (!p.rootPrefix().isEmpty()) d.removePrefixPath(p.rootPrefix());
            d.extract(part);
            if (!Jbr.isJbr(part, major(p.version()))) throw new IOException("the archive did not unpack into a JetBrains Runtime");
            Files.move(part, target);
            return target;
        } finally {
            Files.deleteIfExists(tmp);
            deleteTree(part);
        }
    }

    /** Delete a folder and everything in it, if it exists (FileUtil.delete(Path) is scheduled for removal). */
    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
        try (var s = Files.walk(p)) {
            for (Path f : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
        }
    }

    private static int major(String version) {
        String v = version.split("[.\\-_+]")[0];
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return -1; }
    }

    private static String sha256(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }

    /** The feed's OS names. */
    static String os() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return n.startsWith("windows") ? "windows" : n.startsWith("mac") ? "macOS" : "linux";
    }

    /** The feed's CPU names. */
    static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return a.equals("amd64") || a.equals("x86_64") ? "x86_64" : a.equals("aarch64") || a.equals("arm64") ? "aarch64" : a;
    }

    private JbrDownloader() {}
}
