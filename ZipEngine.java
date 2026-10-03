package com.compressor;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.*;

/**
 * Core compression / extraction logic (no UI code).
 * Uses ZipOutputStream for compression and ZipInputStream for extraction.
 */
public class ZipEngine {

    /** Progress callback: bytes processed so far, total bytes, current file name. */
    public interface ProgressListener {
        void onProgress(long done, long total, String currentFile);
    }

    /** Result statistics of one operation. */
    public static class Stats {
        public int fileCount;
        public long originalBytes;
        public long compressedBytes;
        public long elapsedMs;
        public boolean cancelled;

        public double ratioPercent() {
            return originalBytes == 0 ? 0 : (1.0 - (double) compressedBytes / originalBytes) * 100.0;
        }
    }

    private static final int BUFFER = 64 * 1024;
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    public void cancel() { cancelFlag.set(true); }

    // ------------------------------------------------------------------ COMPRESS

    /**
     * Compresses files and folders (recursively) into one zip.
     * @param level 0 (store) .. 9 (best); Deflater.DEFAULT_COMPRESSION = -1
     */
    public Stats compress(List<File> inputs, File zipFile, int level,
                          ProgressListener progress, Consumer<String> log) throws IOException {
        cancelFlag.set(false);
        long start = System.currentTimeMillis();
        Stats stats = new Stats();

        // Collect every file with its relative name inside the archive
        LinkedHashMap<File, String> entries = new LinkedHashMap<>();
        for (File f : inputs) collect(f, f.getName(), entries);
        long total = 0;
        for (File f : entries.keySet()) total += f.length();
        stats.originalBytes = total;
        log.accept("Found " + entries.size() + " file(s), " + format(total) + " total");

        long done = 0;
        byte[] buf = new byte[BUFFER];
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            zos.setLevel(level);
            for (Map.Entry<File, String> e : entries.entrySet()) {
                if (cancelFlag.get()) { stats.cancelled = true; break; }
                File f = e.getKey();
                ZipEntry ze = new ZipEntry(e.getValue());
                ze.setTime(f.lastModified());
                zos.putNextEntry(ze);
                try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelFlag.get()) { stats.cancelled = true; break; }
                        zos.write(buf, 0, n);
                        done += n;
                        progress.onProgress(done, total, e.getValue());
                    }
                }
                zos.closeEntry();
                if (stats.cancelled) break;
                stats.fileCount++;
                log.accept("  + " + e.getValue() + " (" + format(f.length()) + ")");
            }
        }
        if (stats.cancelled) {
            Files.deleteIfExists(zipFile.toPath());
            log.accept("Cancelled - partial archive deleted.");
        }
        stats.compressedBytes = zipFile.exists() ? zipFile.length() : 0;
        stats.elapsedMs = System.currentTimeMillis() - start;
        return stats;
    }

    private void collect(File f, String name, Map<File, String> out) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) return;
            Arrays.sort(kids);
            for (File k : kids) collect(k, name + "/" + k.getName(), out);
        } else if (f.isFile()) {
            out.put(f, name);
        }
    }

    // ------------------------------------------------------------------ EXTRACT

    public Stats extract(File zipFile, File destDir, ProgressListener progress,
                         Consumer<String> log) throws IOException {
        cancelFlag.set(false);
        long start = System.currentTimeMillis();
        Stats stats = new Stats();
        stats.compressedBytes = zipFile.length();

        // Total uncompressed size (central directory read) so the progress bar is accurate
        long total = 0;
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                long s = en.nextElement().getSize();
                if (s > 0) total += s;
            }
        }
        log.accept("Archive: " + zipFile.getName() + " (" + format(zipFile.length()) + ")");

        Files.createDirectories(destDir.toPath());
        Path root = destDir.toPath().toAbsolutePath().normalize();
        long done = 0;
        byte[] buf = new byte[BUFFER];

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (cancelFlag.get()) { stats.cancelled = true; break; }
                Path target = root.resolve(ze.getName()).normalize();
                // Zip-slip protection: never write outside destination
                if (!target.startsWith(root)) {
                    log.accept("  ! Skipped unsafe entry: " + ze.getName());
                    continue;
                }
                if (ze.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        if (cancelFlag.get()) { stats.cancelled = true; break; }
                        out.write(buf, 0, n);
                        done += n;
                        stats.originalBytes += n;
                        progress.onProgress(done, Math.max(total, done), ze.getName());
                    }
                }
                if (stats.cancelled) break;
                stats.fileCount++;
                log.accept("  - " + ze.getName());
            }
        }
        if (stats.cancelled) log.accept("Cancelled - extracted files so far were kept.");
        stats.elapsedMs = System.currentTimeMillis() - start;
        return stats;
    }

    // ------------------------------------------------------------------ HELPERS

    public static String format(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format("%.2f %s", v, u[i]);
    }
}
