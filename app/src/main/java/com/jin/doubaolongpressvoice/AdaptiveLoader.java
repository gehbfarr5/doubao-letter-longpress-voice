package com.jin.doubaolongpressvoice;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.AtomicFile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import org.luckypray.dexkit.DexKitBridge;

/** Runs only on resolver worker. Cache is private to the host app and bound to APK content. */
final class AdaptiveLoader {
    static DoubaoCompatAdapter load(Context context, ClassLoader loader, Consumer<String> log) throws Exception {
        long start = System.nanoTime();
        ApplicationInfo info = context.getApplicationInfo();
        List<String> paths = new ArrayList<>();
        paths.add(info.sourceDir);
        if (info.splitSourceDirs != null) Collections.addAll(paths, info.splitSourceDirs);
        String fingerprint = fingerprint(paths, context.getPackageName(),
                versionCode(context));
        AtomicFile cache = new AtomicFile(new File(context.getCodeCacheDir(), "doubao-longpress-semantic.properties"));
        Properties mapping = new Properties();
        if (cache.getBaseFile().exists()) {
            try (InputStream in = cache.openRead()) {
                mapping.load(in);
                if (fingerprint.equals(mapping.getProperty("fingerprint"))) {
                    DoubaoCompatAdapter bound = new DoubaoCompatAdapter(mapping, loader, "cache-hit " + fingerprint);
                    log.accept("resolver cache-hit ms=" + elapsed(start));
                    return bound;
                }
                log.accept("resolver cache invalidated: APK/rules changed");
            } catch (Exception e) {
                // Cache is only an optimization. Explicitly report corruption before recomputing evidence.
                log.accept("resolver cache rejected: " + e);
            }
        }
        System.loadLibrary("dexkit");
        // The APK path API cannot see split dex. Combine dex entries when splits are present.
        try (DexKitBridge bridge = paths.size() == 1 ? DexKitBridge.create(info.sourceDir)
                : DexKitBridge.create(readDex(paths))) {
            mapping = new SemanticResolver(bridge).resolve();
        }
        mapping.setProperty("fingerprint", fingerprint);
        DoubaoCompatAdapter bound = new DoubaoCompatAdapter(mapping, loader, "resolved " + fingerprint);
        FileOutputStream out = cache.startWrite();
        try {
            mapping.store(out, "Semantic descriptors only; no user text");
            cache.finishWrite(out);
        } catch (Exception e) {
            cache.failWrite(out);
            throw e;
        }
        for (String k : new java.util.TreeSet<>(mapping.stringPropertyNames()))
            log.accept("capability " + k + "=" + mapping.getProperty(k));
        log.accept("resolver cold ms=" + elapsed(start));
        return bound;
    }
    private static long versionCode(Context context) throws Exception {
        android.content.pm.PackageInfo pkg = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return android.os.Build.VERSION.SDK_INT >= 28 ? pkg.getLongVersionCode() : pkg.versionCode;
    }
    static String fingerprint(List<String> paths, String pkg, long version) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((pkg + ":" + version + ":" + SemanticResolver.RULE_VERSION).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] buffer = new byte[128 * 1024];
        for (String path : paths) {
            MessageDigest fileDigest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(path)) {
                int n;
                while ((n = in.read(buffer)) != -1) fileDigest.update(buffer, 0, n);
            }
            digest.update(fileDigest.digest());
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
    private static byte[][] readDex(List<String> paths) throws IOException {
        List<byte[]> dex = new ArrayList<>();
        for (String path : paths) try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path)) {
            Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    dex.add(out.toByteArray());
                }
            }
        }
        return dex.toArray(new byte[0][]);
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
