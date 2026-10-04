// SPDX-License-Identifier: GPL-3.0-only
package com.phislow.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** User-owned chart/audio pairs. A directory becomes visible only after all validation succeeds. */
public final class ImportedCharts {
    public static final String PREFIX = "imports/";
    private static final long MAX_AUDIO = 256L * 1024 * 1024, MAX_ZIP = 512L * 1024 * 1024;
    private static final int MAX_CHART = 16 * 1024 * 1024;

    private ImportedCharts() {}

    public static synchronized String addPair(Context context, InputStream chart, InputStream audio,
            String displayName, String audioName) throws Exception {
        File stage = staging(context);
        try {
            copy(chart, new File(stage, "chart.json"), MAX_CHART);
            String audioFile = "audio" + audioExtension(audioName);
            copy(audio, new File(stage, audioFile), MAX_AUDIO);
            return publish(context, stage, displayName, "导入", "", audioFile, null, 0);
        } finally { delete(stage); }
    }

    /** ZIP/PEZ, optionally with the flat info.yml fields documented by Phira. */
    public static synchronized String addZip(Context context, InputStream zip, String displayName) throws Exception {
        File stage = staging(context), sources = new File(stage, "sources");
        if (!sources.mkdir()) { delete(stage); throw new IOException("无法准备导入目录"); }
        try {
            List<String> paths = new ArrayList<>();
            long total = 0;
            int entries = 0;
            try (ZipInputStream input = new ZipInputStream(zip)) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    if (++entries > 256) throw new IOException("谱面包文件过多（最多 256 个）");
                    String path = entry.getName();
                    if (entry.isDirectory()) { owned(sources, path.endsWith("/") ? path.substring(0, path.length() - 1) : path); continue; }
                    File target = owned(sources, path);
                    if (paths.contains(path)) throw new IOException("谱面包包含重复文件：" + path);
                    if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
                        throw new IOException("无法保存谱面文件");
                    total += copy(input, target, Math.min(MAX_AUDIO, MAX_ZIP - total));
                    paths.add(path);
                }
            }
            if (paths.isEmpty()) throw new IOException("请选择包含谱面和音乐的 ZIP / PEZ 文件");
            String info = unique(paths, "info.yml");
            if (info == null) info = unique(paths, "info.txt");
            Map<String, String> meta = info == null ? new HashMap<>() : metadata(read(owned(sources, info), 65536));
            String base = info == null || !info.contains("/") ? "" : info.substring(0, info.lastIndexOf('/') + 1);
            String chartPath = selected(paths, base, meta.get("chart"), "chart");
            String audioPath = selected(paths, base, meta.get("music"), "audio");
            String coverPath = selected(paths, base, meta.get("illustration"), "cover");
            copyFile(owned(sources, chartPath), new File(stage, "chart.json"), MAX_CHART);
            String audioFile = "audio" + audioExtension(audioPath);
            copyFile(owned(sources, audioPath), new File(stage, audioFile), MAX_AUDIO);
            String coverFile = null;
            if (coverPath != null) {
                coverFile = "cover" + extension(coverPath);
                copyFile(owned(sources, coverPath), new File(stage, coverFile), MAX_CHART);
            }
            double offset = meta.containsKey("offset") ? Double.parseDouble(meta.get("offset")) : 0;
            delete(sources);
            return publish(context, stage, meta.getOrDefault("name", displayName),
                meta.getOrDefault("level", "导入"), meta.getOrDefault("composer", ""), audioFile, coverFile, offset);
        } finally { delete(stage); }
    }

    public static synchronized List<SongLibrary.Song> list(Context context) throws Exception {
        List<SongLibrary.Song> songs = new ArrayList<>();
        File[] directories = root(context).listFiles();
        if (directories == null) return songs;
        Arrays.sort(directories);
        for (File directory : directories) {
            if (!directory.getName().matches("[0-9a-f]{64}")) continue;
            File data = new File(directory, "song.json");
            if (data.isFile()) songs.add(new SongLibrary.Song(new JSONObject(read(data, 65536))));
        }
        return songs;
    }

    public static File file(Context context, String path) throws IOException {
        if (path == null || !path.startsWith(PREFIX)) throw new IOException("无效的导入文件路径");
        String relative = path.substring(PREFIX.length());
        if (!relative.matches("[0-9a-f]{64}/[^/]+")) throw new IOException("无效的导入文件路径");
        File destination = owned(root(context), relative);
        if (!destination.isFile() || !new File(destination.getParentFile(), "song.json").isFile())
            throw new IOException("导入文件已不存在，请重新导入");
        return destination;
    }

    public static synchronized boolean remove(Context context, String id) throws IOException {
        if (id == null || !id.matches("import-[0-9a-f]{64}")) return false;
        File directory = owned(root(context), id.substring(7));
        if (!directory.exists()) return false;
        delete(directory);
        return !directory.exists();
    }

    private static String publish(Context context, File stage, String title, String level, String artist,
            String audioFile, String coverFile, double extraOffset) throws Exception {
        JSONObject json = new JSONObject(read(new File(stage, "chart.json"), MAX_CHART));
        int version = json.optInt("formatVersion", -1);
        if (version != 1 && version != 3)
            throw new IOException("暂时仅支持 PGR JSON v1/v3 谱面，尚不支持 RPE / PEC");
        double offset = json.optDouble("offset", 0) + extraOffset;
        if (!Double.isFinite(offset)) throw new IOException("谱面偏移量无效");
        json.put("offset", offset);
        String chartData = json.toString();
        Chart chart = new Chart(chartData);
        if (!Double.isFinite(chart.offsetMs)) throw new IOException("谱面偏移量超出支持范围");
        if (chart.lines.length == 0) throw new IOException("谱面没有判定线");
        for (Chart.Note note : chart.notes)
            if (!Double.isFinite(note.time) || !Double.isFinite(note.end) || !Double.isFinite(note.x)
                    || !Double.isFinite(note.speed) || note.end < note.time)
                throw new IOException("谱面包含无效音符");
        write(new File(stage, "chart.json"), chartData.getBytes(StandardCharsets.UTF_8));
        MediaMetadataRetriever media = new MediaMetadataRetriever();
        try {
            media.setDataSource(new File(stage, audioFile).getAbsolutePath());
            String duration = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null || Long.parseLong(duration) <= 0) throw new IOException("音频为空或无法读取");
        } catch (RuntimeException error) { throw new IOException("音频无法读取，请选择支持的音乐文件", error); }
        finally { media.release(); }
        if (coverFile != null) makeBlur(stage, coverFile);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String name : new String[] {"chart.json", audioFile}) {
            File inputFile = new File(stage, name);
            digest.update(Long.toString(inputFile.length()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = new FileInputStream(inputFile)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        String hash = hex.toString(), id = "import-" + hash, prefix = PREFIX + hash + "/";
        JSONObject selection = new JSONObject().put("difficulty", level).put("path", prefix + "chart.json")
            .put("audio", prefix + audioFile).put("notes", chart.notes.length);
        JSONObject song = new JSONObject().put("id", id).put("title", title == null || title.trim().isEmpty() ? "导入曲目" : title)
            .put("artist", artist).put("audio", prefix + audioFile).put("charts", new JSONArray().put(selection));
        if (coverFile != null) song.put("cover", prefix + coverFile).put("coverThumb", prefix + coverFile)
            .put("coverBlur", prefix + "blur.jpg");
        byte[] record = song.toString().getBytes(StandardCharsets.UTF_8);
        if (record.length > 65536) throw new IOException("曲名或作者信息过长，请缩短 info.yml 中的字段");
        write(new File(stage, "song.json"), record);
        File destination = owned(root(context), hash);
        if (destination.isDirectory()) return id;
        if (!stage.renameTo(destination)) throw new IOException("无法完成谱面保存");
        return id;
    }

    private static void makeBlur(File stage, String coverFile) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        String path = new File(stage, coverFile).getAbsolutePath();
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("曲绘无法读取");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
        Bitmap image = BitmapFactory.decodeFile(path, options);
        if (image == null) throw new IOException("曲绘无法读取");
        float ratio = 32f / Math.max(image.getWidth(), image.getHeight());
        Bitmap small = Bitmap.createScaledBitmap(image, Math.max(1, Math.round(image.getWidth() * ratio)),
            Math.max(1, Math.round(image.getHeight() * ratio)), true);
        Bitmap blur = Bitmap.createScaledBitmap(small, small.getWidth() * 8, small.getHeight() * 8, true);
        try (FileOutputStream output = new FileOutputStream(new File(stage, "blur.jpg"))) {
            if (!blur.compress(Bitmap.CompressFormat.JPEG, 85, output)) throw new IOException("曲绘保存失败");
        } finally { if (small != image) small.recycle(); if (blur != small && blur != image) blur.recycle(); image.recycle(); }
    }

    private static String selected(List<String> paths, String base, String named, String type) throws IOException {
        if (named != null && !named.isEmpty()) {
            String path = base + named;
            if (!paths.contains(path)) throw new IOException("谱面包缺少指定文件：" + named);
            return path;
        }
        List<String> candidates = new ArrayList<>();
        for (String path : paths) {
            String ext = extension(path);
            if (type.equals("chart") && (ext.equals(".json") || ext.equals(".pec"))
                    && !path.endsWith("extra.json") && !path.endsWith("info.json")) candidates.add(path);
            if (type.equals("audio") && isAudio(ext)) candidates.add(path);
            if (type.equals("cover") && (ext.equals(".png") || ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".webp"))) candidates.add(path);
        }
        if (candidates.size() == 1) return candidates.get(0);
        if (type.equals("cover") && candidates.isEmpty()) return null;
        String label = type.equals("chart") ? "谱面" : type.equals("audio") ? "音乐" : "曲绘";
        throw new IOException(candidates.isEmpty() ? "谱面包缺少" + label
            : "谱面包包含多个" + label + "文件，请用 info.yml 指定所需文件");
    }

    private static String unique(List<String> paths, String name) throws IOException {
        String found = null;
        for (String path : paths) if (path.equals(name) || path.endsWith("/" + name)) {
            if (found != null) throw new IOException("谱面包含有多个 " + name);
            found = path;
        }
        return found;
    }

    /** Small, flat metadata subset; advanced YAML constructs are rejected for relevant fields. */
    private static Map<String, String> metadata(String text) throws Exception {
        Map<String, String> result = new HashMap<>();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        for (String line : text.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (key.equals("song")) key = "music";
            if (key.equals("picture")) key = "illustration";
            if (!Arrays.asList("name", "chart", "music", "illustration", "level", "composer", "offset").contains(key)) continue;
            String value = line.substring(colon + 1).trim();
            if (value.startsWith("\"")) {
                int end = 1;
                for (; end < value.length(); end++) {
                    if (value.charAt(end) == '\\') end++;
                    else if (value.charAt(end) == '"') break;
                }
                if (end >= value.length() || !(value.substring(end + 1).trim().isEmpty()
                        || value.substring(end + 1).trim().startsWith("#")))
                    throw new IOException("info.yml 字符串格式无效");
                JSONArray quoted = new JSONArray("[" + value.substring(0, end + 1) + "]"); value = quoted.getString(0);
            } else if (value.startsWith("'")) {
                int end = 1;
                for (; end < value.length(); end++) if (value.charAt(end) == '\'') {
                    if (end + 1 < value.length() && value.charAt(end + 1) == '\'') end++;
                    else break;
                }
                if (end >= value.length() || !(value.substring(end + 1).trim().isEmpty()
                        || value.substring(end + 1).trim().startsWith("#")))
                    throw new IOException("info.yml 字符串格式无效");
                value = value.substring(1, end).replace("''", "'");
            } else {
                int comment = value.indexOf(" #");
                if (comment >= 0) value = value.substring(0, comment).trim();
                if (value.matches("^[>|&*!\\[\\{].*")) throw new IOException("info.yml 请使用普通单行字段");
            }
            if (!value.isEmpty() && !value.equals("null") && !value.equals("~")) result.put(key, value);
        }
        return result;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
    private static boolean isAudio(String ext) {
        return Arrays.asList(".ogg", ".mp3", ".wav", ".flac", ".m4a", ".aac", ".mp4", ".opus").contains(ext);
    }
    private static String audioExtension(String name) throws IOException {
        String ext = extension(name == null ? "" : name);
        if (!isAudio(ext)) throw new IOException("请选择 MP3 / OGG / WAV / FLAC / M4A / AAC / OPUS 音频");
        return ext;
    }
    private static File root(Context context) { return new File(context.getFilesDir(), "imported-charts"); }
    private static File staging(Context context) throws IOException {
        File stage = new File(root(context), ".staging-" + UUID.randomUUID());
        if (!stage.mkdirs()) throw new IOException("无法创建导入目录");
        return stage;
    }
    private static File owned(File directory, String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")) throw new IOException("谱面包路径无效");
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("谱面包路径无效");
        File file = new File(directory, path);
        if (!file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) throw new IOException("谱面包路径超出保存目录");
        return file;
    }
    private static long copy(InputStream input, File destination, long limit) throws IOException {
        long total = 0;
        try (FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > limit - total) throw new IOException("导入文件过大");
                output.write(buffer, 0, count); total += count;
            }
            output.getFD().sync();
        }
        return total;
    }
    private static void copyFile(File source, File destination, long limit) throws IOException {
        try (InputStream input = new FileInputStream(source)) { copy(input, destination, limit); }
    }
    private static String read(File file, int limit) throws IOException {
        if (file.length() > limit) throw new IOException("谱面或信息文件过大");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); output.getFD().sync(); }
    }
    private static void delete(File file) {
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
