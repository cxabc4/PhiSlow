package com.phislow.app;

import android.content.Context;
import android.os.StatFs;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The song library survives a code-only APK replacement in private persistent files. */
public final class LibraryAssets {
    public interface Progress { void update(int percent); }

    private static final String PREFIX = "library/", COMPLETE = ".complete";
    private static final String MISSING = "请先安装完整包并打开，完成曲库保存";
    private static volatile Manifest manifest;

    private LibraryAssets() {}

    /** Public builds have no bundled library; imports are independently available. */
    public static boolean hasBundledLibrary(Context context) throws IOException {
        for (String path : context.getAssets().list("")) if (path.equals("library-manifest.json")) return true;
        return false;
    }

    private static final class Entry {
        final String path, sha256;
        final long size;
        Entry(JSONObject row) throws JSONException, IOException {
            path = row.getString("path"); size = row.getLong("size"); sha256 = row.getString("sha256");
            if (!safePath(path) || size < 0 || !sha256.matches("[0-9a-fA-F]{64}"))
                throw new IOException("曲库清单包含无效文件");
        }
    }

    private static final class Manifest {
        final String generation;
        final long bytes;
        final List<Entry> files = new ArrayList<>();
        final Map<String, Entry> paths = new HashMap<>();
        Manifest(JSONObject data) throws JSONException, IOException {
            generation = data.getString("generation"); bytes = data.getLong("bytes");
            if (!generation.matches("[0-9a-f]{64}") || bytes < 0) throw new IOException("曲库版本清单无效");
            JSONArray rows = data.getJSONArray("files");
            long total = 0;
            for (int i = 0; i < rows.length(); i++) {
                Entry entry = new Entry(rows.getJSONObject(i));
                if (paths.put(entry.path, entry) != null || total > Long.MAX_VALUE - entry.size)
                    throw new IOException("曲库文件清单无效");
                files.add(entry); total += entry.size;
            }
            if (total != bytes || !paths.containsKey("index.json")) throw new IOException("曲库文件清单不完整");
        }
    }

    /** Called by the library's background loader; only a full APK can populate missing files. */
    public static synchronized void ensure(Context context, Progress progress) throws IOException {
        Manifest data = metadata(context);
        File base = new File(context.getFilesDir(), "library-cache");
        File directory = new File(base, data.generation);
        File complete = new File(directory, COMPLETE);
        if (complete.isFile() && allPresent(directory, data)) {
            cleanupOld(base, directory);
            if (progress != null) progress.update(100);
            return;
        }
        if (complete.exists() && !complete.delete()) throw new IOException("无法准备曲库保存目录");
        try (InputStream bundled = context.getAssets().open(PREFIX + "index.json")) {
            // The manifest is also shipped by the update APK; the actual index identifies a full APK.
        } catch (IOException error) {
            throw new IOException(MISSING, error);
        }
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建曲库保存目录");
        long remaining = 0, saved = 0;
        for (Entry entry : data.files) {
            File destination = ownedFile(directory, entry.path);
            if (present(destination, entry)) saved += entry.size;
            else {
                remaining += entry.size;
                File partial = new File(destination.getPath() + ".part");
                if (partial.exists() && !partial.delete()) throw new IOException("无法清理未完成的曲库文件");
            }
        }
        if (new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes() < remaining)
            throw new IOException("空间不足，保存曲库还需要 " + ((remaining + 1048575) / 1048576) + " MB");

        byte[] buffer = new byte[65536];
        int lastPercent = -1;
        for (Entry entry : data.files) {
            File destination = ownedFile(directory, entry.path);
            // Only successfully hashed files are ever renamed to their final names.
            if (present(destination, entry)) continue;
            File parent = destination.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建曲库文件目录");
            File partial = new File(destination.getPath() + ".part");
            MessageDigest digest = sha256();
            long copied = 0;
            try (InputStream input = context.getAssets().open(PREFIX + entry.path);
                    FileOutputStream output = new FileOutputStream(partial)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > entry.size - copied) throw new IOException("曲库文件长度与清单不符：" + entry.path);
                    output.write(buffer, 0, count); digest.update(buffer, 0, count); copied += count;
                    int percent = (int) Math.min(99, (saved + copied) * 100d / Math.max(1, data.bytes));
                    if (progress != null && percent != lastPercent) { progress.update(percent); lastPercent = percent; }
                }
                output.getFD().sync();
            }
            if (copied != entry.size || !hex(digest.digest()).equalsIgnoreCase(entry.sha256)) {
                partial.delete();
                throw new IOException("曲库文件校验失败：" + entry.path);
            }
            if (destination.exists() && !destination.delete()) throw new IOException("无法替换曲库文件");
            if (!partial.renameTo(destination)) throw new IOException("无法保存曲库文件");
            saved += entry.size;
        }
        File marker = new File(directory, COMPLETE + ".part");
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write((data.generation + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!marker.renameTo(complete)) throw new IOException("无法完成曲库保存");
        cleanupOld(base, directory);
        if (progress != null) progress.update(100);
    }

    /** Opens library data only after preparation; unrelated app artwork stays inside the APK. */
    public static InputStream open(Context context, String path) throws IOException {
        if (path != null && path.startsWith(ImportedCharts.PREFIX)) return new FileInputStream(ImportedCharts.file(context, path));
        if (path != null && path.startsWith(PREFIX)) return new FileInputStream(file(context, path));
        return context.getAssets().open(path);
    }

    /** The audio loader can pass this absolute path directly to MediaPlayer without copying. */
    public static File file(Context context, String libraryAssetPath) throws IOException {
        if (libraryAssetPath == null || !libraryAssetPath.startsWith(PREFIX)) throw new IOException("无效的曲库路径");
        Manifest data = metadata(context);
        String path = libraryAssetPath.substring(PREFIX.length());
        Entry entry = data.paths.get(path);
        if (!safePath(path) || entry == null) throw new IOException("无效的曲库路径");
        File directory = new File(new File(context.getFilesDir(), "library-cache"), data.generation);
        File destination = ownedFile(directory, path);
        if (!new File(directory, COMPLETE).isFile() || !present(destination, entry)) throw new IOException(MISSING);
        return destination;
    }

    private static Manifest metadata(Context context) throws IOException {
        Manifest cached = manifest;
        if (cached != null) return cached;
        synchronized (LibraryAssets.class) {
            if (manifest == null) {
                try (InputStream input = context.getAssets().open("library-manifest.json");
                        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    manifest = new Manifest(new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)));
                } catch (JSONException error) {
                    throw new IOException("曲库版本清单无法读取", error);
                }
            }
            return manifest;
        }
    }

    private static boolean safePath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\")) return false;
        for (String part : path.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static File ownedFile(File directory, String path) throws IOException {
        File file = new File(directory, path);
        if (!file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator))
            throw new IOException("曲库路径超出保存目录");
        return file;
    }

    private static boolean present(File file, Entry entry) { return file.isFile() && file.length() == entry.size; }

    private static boolean allPresent(File directory, Manifest data) throws IOException {
        for (Entry entry : data.files) if (!present(ownedFile(directory, entry.path), entry)) return false;
        return true;
    }

    /** Cleanup is confined to sibling content generations; preferences and user packs are elsewhere. */
    private static void cleanupOld(File base, File current) {
        File[] directories = base.listFiles();
        if (directories == null) return;
        for (File directory : directories)
            if (!directory.equals(current) && directory.getName().matches("[0-9a-f]{64}")) deleteOwned(directory, base);
    }

    private static void deleteOwned(File file, File base) {
        try {
            if (!file.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) return;
            File[] children = file.isDirectory() ? file.listFiles() : null;
            if (children != null) for (File child : children) deleteOwned(child, base);
            file.delete();
        } catch (IOException ignored) {
            // A failed cleanup does not make the newly verified library unusable.
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray(), result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) { result[i * 2] = digits[(bytes[i] & 255) >>> 4]; result[i * 2 + 1] = digits[bytes[i] & 15]; }
        return new String(result);
    }
}
