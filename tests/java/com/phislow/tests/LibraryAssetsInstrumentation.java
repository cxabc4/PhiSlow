package com.phislow.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import com.phislow.app.Chart;
import com.phislow.app.LibraryAssets;
import com.phislow.app.SongLibrary;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Checks prepared-library reads and interrupted import with an isolated index and sparse files. */
public final class LibraryAssetsInstrumentation extends Instrumentation {
    private int passed, failed;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Context context = getTargetContext();
        Map<String, ?> settings = new HashMap<>(context.getSharedPreferences("phislow", Context.MODE_PRIVATE).getAll());
        Map<String, ?> favorites = new HashMap<>(context.getSharedPreferences("phislow_favorites", Context.MODE_PRIVATE).getAll());
        File isolated = new File(context.getFilesDir(), "library-tests-" + System.nanoTime());
        try {
            if (!isolated.mkdirs()) throw new IOException("Could not isolate library test files");
            Context fixture = new ContextWrapper(context) {
                @Override public File getFilesDir() { return isolated; }
            };
            checkMissing(() -> LibraryAssets.file(fixture, "library/index.json"),
                "Reading an unprepared library gives the full-package instruction");
            check(!new File(isolated, "library-cache").exists(), "Reads cannot silently copy the library");
            try (InputStream input = LibraryAssets.open(fixture, "practice-demo.wav")) {
                byte[] header = new byte[4];
                check(input.read(header) == 4 && new String(header, StandardCharsets.US_ASCII).equals("RIFF"),
                    "Non-library sample remains readable from the update APK");
            }
            for (String path : new String[] {"library/../index.json", "library/a/../../index.json", "library//index.json", "practice-demo.wav"}) {
                boolean rejected = false;
                try { LibraryAssets.file(fixture, path); } catch (IOException expected) { rejected = true; }
                check(rejected, "File lookup rejects invalid library path " + path);
            }
            checkRealLibrary(context);

            JSONObject manifest;
            try (InputStream input = context.getAssets().open("library-manifest.json")) {
                manifest = new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
            }
            String generation = manifest.getString("generation");
            JSONArray files = manifest.getJSONArray("files");
            File sourceIndex = LibraryAssets.file(context, "library/index.json");
            File sourceDirectory = sourceIndex.getParentFile();
            File directory = new File(new File(isolated, "library-cache"), generation);
            if (!directory.mkdirs()) throw new IOException("Could not create library fixture");
            String indexHash = null;
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i);
                String path = entry.getString("path");
                File destination = new File(directory, path);
                File parent = destination.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create fixture parent");
                if (path.equals("index.json")) {
                    copy(sourceIndex, destination);
                    indexHash = entry.getString("sha256");
                } else {
                    // These placeholders exercise the ready-marker length check, without copying payloads.
                    try (RandomAccessFile placeholder = new RandomAccessFile(destination, "rw")) {
                        placeholder.setLength(entry.getLong("size"));
                    }
                }
            }
            copy(new File(sourceDirectory, ".complete"), new File(directory, ".complete"));
            File sentinel = new File(isolated, "outside-library.txt");
            write(sentinel, "preserved");
            String oldGeneration = (generation.startsWith("0") ? "1" : "0") + generation.substring(1);
            File oldDirectory = new File(directory.getParentFile(), oldGeneration);
            if (!oldDirectory.mkdir()) throw new IOException("Could not create old-generation fixture");
            write(new File(oldDirectory, "old.txt"), "old library");
            final int[] percent = {-1};
            LibraryAssets.ensure(fixture, value -> percent[0] = value);
            check(percent[0] == 100, "An already prepared library finishes validation at 100 percent");
            check(!oldDirectory.exists() && sentinel.isFile(), "Old content cleanup stays inside library-cache");
            File index = LibraryAssets.file(fixture, "library/index.json");
            check(index.getCanonicalPath().startsWith(isolated.getCanonicalPath() + File.separator),
                "Library lookup selects persistent data rather than APK assets");
            try (InputStream saved = LibraryAssets.open(fixture, "library/index.json");
                    InputStream original = new FileInputStream(sourceIndex)) {
                check(saved.read() == original.read(), "Library stream reads the prepared index");
            }

            // Remove only the isolated index; the real library remains read-only throughout.
            if (!index.delete()) throw new IOException("Could not remove isolated index");
            write(new File(directory, "index.json.part"), "interrupted copy");
            boolean full = hasBundledLibrary(context);
            if (full) {
                LibraryAssets.ensure(fixture, value -> percent[0] = value);
                check(index.isFile() && hash(index).equalsIgnoreCase(indexHash),
                    "Full APK resumes an interrupted index import with verified bytes");
                check(!new File(directory, "index.json.part").exists() && percent[0] == 100,
                    "Temporary import is replaced before the completion marker is published");
            } else {
                checkMissing(() -> LibraryAssets.ensure(fixture, null),
                    "Update APK reports a damaged or missing library without importing assets");
                check(!new File(directory, ".complete").exists(), "Incomplete data cannot keep a ready marker");
            }
            check(sourceIndex.isFile() && hash(sourceIndex).equalsIgnoreCase(indexHash),
                "Isolated recovery leaves the real cached index unchanged");
        } catch (Throwable error) {
            check(false, error.toString());
        } finally {
            deleteFixture(isolated, isolated);
            check(!isolated.exists(), "Isolated library test data is removed");
            check(context.getSharedPreferences("phislow", Context.MODE_PRIVATE).getAll().equals(settings)
                && context.getSharedPreferences("phislow_favorites", Context.MODE_PRIVATE).getAll().equals(favorites),
                "Settings and favorites remain untouched");
        }
        Bundle result = new Bundle();
        result.putString("stream", "\n" + (failed == 0 ? "PASS" : "FAIL") + " library storage: "
            + passed + " passed, " + failed + " failed\n");
        result.putInt("passed", passed); result.putInt("failed", failed);
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private interface Operation { void run() throws IOException; }

    private void checkRealLibrary(Context context) throws Exception {
        SongLibrary library = new SongLibrary(context);
        check(!library.songs.isEmpty(), "Real saved library opens from both full and update packages");
        SongLibrary.Song song = null;
        SongLibrary.Selection selected = null;
        for (SongLibrary.Song candidate : library.songs) {
            for (SongLibrary.Selection chart : candidate.charts) {
                if (chart.audio != null && !chart.audio.isEmpty() && !chart.path.isEmpty()) {
                    song = candidate; selected = chart; break;
                }
            }
            if (selected != null) break;
        }
        if (selected == null) throw new IOException("Saved library contains no playable chart");
        Chart chart = new Chart(SongLibrary.read(context, selected.path));
        check(chart.notes.length > 0, "Real cached chart parses notes through SongLibrary.read");
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(LibraryAssets.file(context, selected.audio).getAbsolutePath());
            String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            check(duration != null && Long.parseLong(duration) > 0,
                "Real cached audio reports a positive duration without playback");
        } finally { metadata.release(); }
        String cover = song.cover;
        if (cover == null) for (SongLibrary.Song candidate : library.songs)
            if (candidate.cover != null) { cover = candidate.cover; break; }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(LibraryAssets.file(context, cover).getAbsolutePath(), bounds);
        check(bounds.outWidth > 0 && bounds.outHeight > 0, "Real cached cover decodes its image dimensions");
    }

    private void checkMissing(Operation operation, String message) {
        boolean reported = false;
        try { operation.run(); }
        catch (IOException expected) { reported = expected.getMessage().contains("请先安装完整包并打开"); }
        check(reported, message);
    }

    private void check(boolean condition, String message) {
        if (condition) passed++; else failed++;
        Bundle status = new Bundle();
        status.putString("stream", (condition ? "PASS " : "FAIL ") + message + "\n");
        sendStatus(condition ? 0 : -1, status);
    }

    private static boolean hasBundledLibrary(Context context) {
        try (InputStream input = context.getAssets().open("library/index.json")) { return true; }
        catch (IOException missing) { return false; }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static void copy(File source, File destination) throws IOException {
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private static void write(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(value.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return hex.toString();
    }

    private static void deleteFixture(File file, File root) {
        try {
            String path = file.getCanonicalPath(), prefix = root.getCanonicalPath();
            if (!path.equals(prefix) && !path.startsWith(prefix + File.separator)) return;
            File[] children = file.isDirectory() ? file.listFiles() : null;
            if (children != null) for (File child : children) deleteFixture(child, root);
            file.delete();
        } catch (IOException ignored) {}
    }
}
