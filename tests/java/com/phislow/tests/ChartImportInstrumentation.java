package com.phislow.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.phislow.app.AudioEngine;
import com.phislow.app.Chart;
import com.phislow.app.ChartImportActivity;
import com.phislow.app.ImportedCharts;
import com.phislow.app.LibraryAssets;
import com.phislow.app.MainActivity;
import com.phislow.app.PracticeView;
import com.phislow.app.SongLibrary;
import com.phislow.app.SongSelectActivity;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Import checks use generated fixtures and isolated storage, with no bundled music or charts. */
public final class ChartImportInstrumentation extends Instrumentation {
    private int passed, failed;
    private AudioEngine audio;
    private String audioError;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Context target = getTargetContext();
        Map<String, ?> settings = new HashMap<>(target.getSharedPreferences("phislow", Context.MODE_PRIVATE).getAll());
        Map<String, ?> favorites = new HashMap<>(target.getSharedPreferences("phislow_favorites", Context.MODE_PRIVATE).getAll());
        File isolated = new File(target.getFilesDir(), "chart-import-tests-" + System.nanoTime());
        try {
            File[] abandoned = target.getFilesDir().listFiles();
            String storagePrefix = target.getFilesDir().getCanonicalPath() + File.separator;
            if (abandoned != null) for (File directory : abandoned)
                if (directory.isDirectory() && directory.getName().matches("chart-import-tests-[0-9]+")
                        && directory.getCanonicalPath().startsWith(storagePrefix)) deleteFixture(directory, directory);
            if (!isolated.mkdirs()) throw new IOException("Could not create isolated import storage");
            File cache = new File(isolated, "cache");
            if (!cache.mkdir()) throw new IOException("Could not create isolated import cache");
            Context fixture = new ContextWrapper(target) {
                @Override public File getFilesDir() { return isolated; }
                @Override public File getCacheDir() { return cache; }
                @Override public Context getApplicationContext() { return this; }
            };
            check(ImportedCharts.list(fixture).isEmpty(), "Empty import storage lists no songs");
            byte[] chart = chart();
            byte[] music = wav(3, 0);
            String metadata = "name: Import fixture\ncomposer: Generated test\nlevel: IN Lv.1\n"
                + "chart: data/play.json\nmusic: audio/test.wav\n";
            String zipId = ImportedCharts.addZip(fixture, input(zip(
                "audio/test.wav", music,
                "ignored.json", bytes("invalid chart, not selected"),
                "data/play.json", chart,
                "info.yml", bytes(metadata))), "archive.zip");
            SongLibrary.Song imported = find(fixture, zipId);
            check(imported != null && imported.title.equals("Import fixture"),
                "ZIP metadata selects its chart/music paths and song title");
            check(imported != null && imported.charts.size() == 1 && imported.charts.get(0).notes == 1,
                "ZIP chart note count is read from the selected JSON");
            if (imported == null || imported.charts.isEmpty()) throw new IOException("Import did not publish a playable selection");
            SongLibrary.Selection selection = imported.charts.get(0);
            check(new Chart(SongLibrary.read(fixture, selection.path)).notes.length == 1,
                "Imported chart opens through the same SongLibrary reader as gameplay");
            try (InputStream stored = LibraryAssets.open(fixture, selection.audio)) {
                check(java.util.Arrays.equals(read(stored), music),
                    "LibraryAssets opens the exact imported audio bytes");
            }
            File audioFile = ImportedCharts.file(fixture, selection.audio);
            check(audioFile.isFile() && audioFile.getCanonicalPath().startsWith(isolated.getCanonicalPath() + File.separator),
                "Imported playback resolves to persistent app-private storage");
            playAudio(fixture, selection.audio);

            String pairId = ImportedCharts.addPair(fixture, input(chart), input(music), "same chart.json", "test.wav");
            check(pairId.equals(zipId) && ImportedCharts.list(fixture).size() == 1,
                "Reimporting identical chart and music through the pair picker adds no duplicate");
            check(ImportedCharts.addZip(fixture, input(zip("chart.json", chart, "music.wav", music)), "simple.zip").equals(zipId),
                "A ZIP with one JSON and music file works without metadata");
            String secondId = ImportedCharts.addPair(fixture, input(chart), input(wav(3, 1)), "Pair fixture.json", "second.wav");
            check(!secondId.equals(zipId) && ImportedCharts.list(fixture).size() == 2,
                "The same chart paired with different audio is a distinct import");
            SongLibrary.Song second = find(fixture, secondId);
            check(second != null && new Chart(SongLibrary.read(fixture, second.charts.get(0).path)).notes.length == 1,
                "Separate JSON and audio selection publishes a playable chart");
            check(find(fixture, zipId) != null && find(fixture, secondId) != null,
                "Reenumerating persisted imports retains both songs");
            if (!hasAsset(target, "library-manifest.json")) {
                SongLibrary library = new SongLibrary(fixture);
                check(library.songs.size() == 2 && contains(library.songs, zipId) && contains(library.songs, secondId),
                    "The public APK loads imported songs without an official library manifest");
            }
            checkWrappedPackage(fixture, chart, music);

            reject(fixture, () -> ImportedCharts.addPair(fixture, input(bytes("{bad json")), input(music), "bad.json", "test.wav"),
                "Malformed JSON cannot publish an import");
            reject(fixture, () -> ImportedCharts.addPair(fixture, input(bytes("{\"META\":{},\"judgeLineList\":[]}")), input(music), "rpe.json", "test.wav"),
                "Unsupported RPE JSON is rejected before publishing");
            byte[] hugeOffset = bytes(new JSONObject(new String(chart, StandardCharsets.UTF_8)).put("offset", 1e308).toString());
            reject(fixture, () -> ImportedCharts.addPair(fixture, input(hugeOffset), input(music), "huge-offset.json", "test.wav"),
                "A finite seconds offset that overflows the chart millisecond clock is rejected");
            StringBuilder longMetadata = new StringBuilder("name: x");
            for (int i = 0; i < 33000; i++) longMetadata.append('\\');
            longMetadata.append("\nchart: chart.json\nmusic: audio.wav\n");
            byte[] longInfo = bytes(longMetadata.toString());
            check(longInfo.length < 65536, "Oversized song-record fixture fits the input metadata read limit");
            reject(fixture, () -> ImportedCharts.addZip(fixture, input(zip(
                "chart.json", chart, "audio.wav", music, "info.yml", longInfo)), "large-record.zip"),
                "Metadata that expands beyond the persisted song-record read limit is rejected before publishing");
            reject(fixture, () -> ImportedCharts.addZip(fixture, input(zip("chart.json", chart)), "missing-audio.zip"),
                "A ZIP without music cannot publish an import");
            reject(fixture, () -> ImportedCharts.addZip(fixture, input(zip(
                "chart.json", chart, "audio.wav", music, "../escape.txt", bytes("escape"))), "traversal.zip"),
                "ZIP parent traversal is rejected even for an unused entry");
            check(!new File(isolated.getParentFile(), "escape.txt").exists(),
                "A rejected ZIP cannot write outside its import directory");
            reject(fixture, () -> ImportedCharts.addZip(fixture, input(zip(
                "chart.json", chart, "audio.wav", music,
                "info.yml", bytes("name: Broken metadata\nchart: missing.json\nmusic: audio.wav\n"))), "missing-path.zip"),
                "Missing metadata-selected chart does not silently fall back to another file");
            reject(fixture, () -> ImportedCharts.addZip(fixture, input(zip(
                "chart.json", chart, "audio.wav", music,
                "info.yml", bytes("name: Broken metadata\nchart: chart.json\nmusic: missing.wav\n"))), "missing-music.zip"),
                "Missing metadata-selected music does not silently fall back to another file");
            reject(fixture, () -> ImportedCharts.addZip(fixture, input(zip(
                "chart.json", chart, "audio.wav", music,
                "info.yml", bytes("name: Broken metadata\nchart: chart.json\nmusic: ../audio.wav\n"))), "metadata-traversal.zip"),
                "Metadata resource paths cannot escape the package");
            for (String path : new String[] {"imports/../chart.json", "imports/" + zipId + "/../../escape.txt", "imports//chart.json"}) {
                boolean rejected = false;
                try { ImportedCharts.file(fixture, path); } catch (IOException expected) { rejected = true; }
                check(rejected, "Imported file lookup rejects unsafe path " + path);
            }
            check(ImportedCharts.remove(fixture, zipId) && find(fixture, zipId) == null && find(fixture, secondId) != null,
                "Removing one import leaves another playable song intact");
            check(!ImportedCharts.remove(fixture, zipId), "Removing an already absent import returns false");
            check(ImportedCharts.remove(fixture, secondId) && ImportedCharts.list(fixture).isEmpty(),
                "Removing the remaining generated import restores an empty list");
            if (!hasAsset(target, "library-manifest.json")) checkImportUi(target, fixture, isolated, chart, music);
        } catch (Throwable error) {
            check(false, error.toString());
        } finally {
            if (audio != null) runOnMainSync(() -> audio.release());
            deleteFixture(isolated, isolated);
            check(!isolated.exists(), "Generated chart and audio fixtures are removed");
            check(target.getSharedPreferences("phislow", Context.MODE_PRIVATE).getAll().equals(settings)
                && target.getSharedPreferences("phislow_favorites", Context.MODE_PRIVATE).getAll().equals(favorites),
                "Import verification leaves user settings and favorites unchanged");
        }
        Bundle result = new Bundle();
        result.putString("stream", "\n" + (failed == 0 ? "PASS" : "FAIL") + " chart import: "
            + passed + " passed, " + failed + " failed\n");
        result.putInt("passed", passed); result.putInt("failed", failed);
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void checkWrappedPackage(Context context, byte[] chart, byte[] music) throws Exception {
        JSONObject json = new JSONObject(new String(chart, StandardCharsets.UTF_8)).put("offset", 0.125);
        Bitmap image = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xff2266aa); image.setPixel(0, 0, 0xffffcc44);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG, 100, encoded); image.recycle();
        String metadata = "name: \"Wrapped fixture\" # inline comment\n"
            + "chart: \"charts/play.json\" # chart path\nmusic: 'music/play.wav'\n"
            + "illustration: art/cover.png\nlevel: IN Lv.1\noffset: 0.375\n";
        byte[] archive = zip("wrapper/info.yml", bytes(metadata), "wrapper/charts/play.json", bytes(json.toString()),
            "wrapper/music/play.wav", music, "wrapper/art/cover.png", encoded.toByteArray());
        String id = ImportedCharts.addZip(context, input(archive), "wrapped.zip");
        SongLibrary.Song song = find(context, id);
        check(song != null && song.title.equals("Wrapped fixture"), "A wrapper folder and quoted YAML comments resolve correctly");
        if (song == null) throw new IOException("Wrapped song missing");
        Chart imported = new Chart(SongLibrary.read(context, song.charts.get(0).path));
        check(Math.abs(imported.offsetMs - 500) < 0.001, "Metadata offset is added to chart offset exactly once, in seconds");
        check(song.cover != null && song.coverBlur != null && !song.cover.equals(song.coverBlur),
            "Optional cover publishes a separate gameplay backdrop");
        try (InputStream original = LibraryAssets.open(context, song.cover); InputStream blurred = LibraryAssets.open(context, song.coverBlur)) {
            Bitmap cover = BitmapFactory.decodeStream(original), blur = BitmapFactory.decodeStream(blurred);
            check(cover != null && cover.getWidth() == 4 && cover.getHeight() == 3 && blur != null,
                "The original cover and generated blur both decode through the resource reader");
            if (cover != null) cover.recycle(); if (blur != null) blur.recycle();
        }
        check(ImportedCharts.addZip(context, input(archive), "wrapped-again.zip").equals(id)
            && find(context, id).coverBlur.equals(song.coverBlur), "Reimporting an offset package retains its identity and cover paths");
        check(ImportedCharts.remove(context, id), "The generated wrapper package is removed independently");
    }

    /** SAF request/result integration is exercised with generated file URIs, without a system picker. */
    private void checkImportUi(Context target, Context fixture, File directory, byte[] chart, byte[] music) throws Exception {
        Set<String> originalIds = new HashSet<>(), generatedIds = new HashSet<>();
        for (SongLibrary.Song song : ImportedCharts.list(target)) originalIds.add(song.id);
        Activity select = null, importing = null, playing = null;
        ActivityMonitor picker = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                return Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())
                    ? new ActivityResult(Activity.RESULT_CANCELED, null) : null;
            }
        };
        addMonitor(picker);
        try {
            String token = Long.toString(System.nanoTime());
            byte[] uiChart = bytes(new JSONObject(new String(chart, StandardCharsets.UTF_8)).put("fixtureRun", token).toString());
            String expectedZipId = ImportedCharts.addPair(fixture, input(uiChart), input(music), "UI ZIP fixture", "play.wav");
            ImportedCharts.remove(fixture, expectedZipId); generatedIds.add(expectedZipId);
            File archive = new File(directory, "ui-fixture.zip");
            write(archive, zip("info.yml", bytes("name: UI ZIP " + token + "\nchart: play.json\nmusic: play.wav\n"),
                "play.json", uiChart, "play.wav", music));
            ActivityMonitor selectionMonitor = addMonitor(SongSelectActivity.class.getName(), null, false);
            try {
                // Shell bootstrap avoids OEM restrictions on an instrumentation background start.
                String command = "am start -n " + target.getPackageName() + "/" + SongSelectActivity.class.getName();
                try (InputStream shell = new ParcelFileDescriptor.AutoCloseInputStream(
                        getUiAutomation().executeShellCommand(command))) { read(shell); }
                select = waitForMonitorWithTimeout(selectionMonitor, 8000);
            } finally { removeMonitor(selectionMonitor); }
            if (select == null) throw new IOException("Song selection activity did not open within eight seconds");
            final Activity songSelect = select;
            check(await(() -> value(songSelect, "library") != null, 8000), "The real song selection screen loads the import-only library");
            if (originalIds.isEmpty()) check(hasText(select, "曲库为空\n点击「导入谱面」添加谱面和音乐"),
                "A public APK with no imports shows the import-first empty state");
            ActivityMonitor importMonitor = addMonitor(ChartImportActivity.class.getName(), null, false);
            try {
                check(click(select, "导入谱面"), "The main-screen import button opens the import activity");
                importing = waitForMonitorWithTimeout(importMonitor, 8000);
            } finally { removeMonitor(importMonitor); }
            if (importing == null) throw new IOException("Import activity did not open");
            check(click(importing, "选择谱面包"), "ZIP selection issues a system document request");
            deliver(importing, 1, archive);
            check(click(importing, "导入"), "A selected ZIP enables the actual import button");
            check(await(() -> selectedId(songSelect).equals(expectedZipId), 8000),
                "Asynchronous ZIP import returns to song selection and selects its new song");
            importing = null;
            check(find(target, expectedZipId) != null && hasText(select, "UI ZIP " + token),
                "The actual song list displays the imported ZIP title");

            byte[] pairChart = bytes(new JSONObject(new String(chart, StandardCharsets.UTF_8)).put("fixtureRun", token + "-pair").toString());
            String expectedPairId = ImportedCharts.addPair(fixture, input(pairChart), input(music), "UI Pair fixture", "play.wav");
            ImportedCharts.remove(fixture, expectedPairId); generatedIds.add(expectedPairId);
            File chartFile = new File(directory, "ui-pair.json"), musicFile = new File(directory, "ui-pair.wav");
            write(chartFile, pairChart); write(musicFile, music);
            importMonitor = addMonitor(ChartImportActivity.class.getName(), null, false);
            try {
                click(select, "导入谱面"); importing = waitForMonitorWithTimeout(importMonitor, 8000);
            } finally { removeMonitor(importMonitor); }
            if (importing == null) throw new IOException("Pair import activity did not open");
            check(click(importing, "选择 JSON 谱面"), "JSON selection issues a system document request");
            deliver(importing, 2, chartFile);
            check(!enabled(importing, "导入"), "Selecting only JSON keeps import disabled until music is chosen");
            deliver(importing, 3, musicFile);
            final Activity pairImport = importing;
            runOnMainSync(() -> {
                EditText name = edit(pairImport.getWindow().getDecorView());
                if (name != null) name.setText("UI Pair " + token);
            });
            check(click(importing, "导入"), "A JSON/audio pair enables the actual import button");
            check(await(() -> selectedId(songSelect).equals(expectedPairId), 8000),
                "Asynchronous pair import reloads the library and selects its new song");
            importing = null;
            check(find(target, expectedPairId) != null && hasText(select, "UI Pair " + token),
                "Pair import preserves the user-edited song name in the actual list");
            ActivityMonitor gameMonitor = addMonitor(MainActivity.class.getName(), null, false);
            try {
                check(click(select, "开始游戏"), "The imported song enables the main-screen Start button");
                playing = waitForMonitorWithTimeout(gameMonitor, 8000);
            } finally { removeMonitor(gameMonitor); }
            if (playing == null) throw new IOException("Practice activity did not open");
            final Activity game = playing;
            check(expectedPairId.equals(game.getIntent().getStringExtra(MainActivity.EXTRA_SONG_ID)),
                "The selection hands the imported song identity to the real gameplay activity");
            check(await(() -> {
                AudioEngine engine = (AudioEngine) value(game, "audio");
                PracticeView view = (PracticeView) value(game, "practice");
                Chart loaded = view == null ? null : (Chart) value(view, "chart");
                return engine != null && engine.isReady() && engine.positionMs() > 0 && loaded != null && loaded.notes.length == 1;
            }, 8000), "Imported JSON notes and audio load and advance together in the actual gameplay screen");
            check(picker.getHits() >= 3, "ZIP, JSON, and music choices all travel through document requests");
        } finally {
            final Activity game = playing, add = importing, list = select;
            runOnMainSync(() -> { if (game != null) game.finish(); if (add != null) add.finish(); if (list != null) list.finish(); });
            waitForIdleSync(); removeMonitor(picker);
            for (String id : generatedIds) if (!originalIds.contains(id)) ImportedCharts.remove(target, id);
            Set<String> remaining = new HashSet<>();
            for (SongLibrary.Song song : ImportedCharts.list(target)) remaining.add(song.id);
            check(remaining.equals(originalIds), "UI verification removes only generated imports and preserves prior user songs");
        }
    }

    private interface Condition { boolean get() throws Exception; }

    private boolean await(Condition condition, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        do {
            final boolean[] result = {false}; final Exception[] error = {null};
            runOnMainSync(() -> { try { result[0] = condition.get(); } catch (Exception exception) { error[0] = exception; } });
            if (error[0] != null) throw error[0];
            if (result[0]) return true;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < end);
        return false;
    }

    private static Object value(Object owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(owner);
    }

    private static String selectedId(Activity activity) throws Exception {
        SongLibrary.Song selected = (SongLibrary.Song) value(activity, "selected");
        return selected == null ? "" : selected.id;
    }

    private void deliver(Activity activity, int request, File file) throws Exception {
        java.lang.reflect.Method method = ChartImportActivity.class.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
        method.setAccessible(true);
        final Throwable[] error = {null};
        runOnMainSync(() -> {
            try { method.invoke(activity, request, Activity.RESULT_OK, new Intent().setData(Uri.fromFile(file))); }
            catch (Throwable exception) { error[0] = exception; }
        });
        if (error[0] != null) throw new IOException("Document result delivery failed", error[0]);
    }

    private boolean click(Activity activity, String text) {
        final boolean[] clicked = {false};
        runOnMainSync(() -> {
            View button = text(activity.getWindow().getDecorView(), text);
            clicked[0] = button instanceof Button && button.isEnabled() && button.performClick();
        });
        return clicked[0];
    }

    private boolean enabled(Activity activity, String text) {
        final boolean[] enabled = {false};
        runOnMainSync(() -> { View view = text(activity.getWindow().getDecorView(), text); enabled[0] = view != null && view.isEnabled(); });
        return enabled[0];
    }

    private boolean hasText(Activity activity, String text) {
        final boolean[] found = {false};
        runOnMainSync(() -> found[0] = text(activity.getWindow().getDecorView(), text) != null);
        return found[0];
    }

    private static View text(View view, String caption) {
        if (view instanceof TextView && caption.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = text(((ViewGroup) view).getChildAt(i), caption); if (found != null) return found;
        }
        return null;
    }

    private static EditText edit(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText found = edit(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(data); }
    }

    private void playAudio(Context context, String path) throws Exception {
        runOnMainSync(() -> {
            audio = new AudioEngine(context, message -> { if (message != null) audioError = message; });
            audio.loadAsset(path);
        });
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!ready() && audioError == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
        check(ready() && audioError == null, "Imported audio prepares in the real AudioEngine; error=" + audioError);
        final int[] duration = {0};
        runOnMainSync(() -> duration[0] = audio.durationMs());
        check(duration[0] >= 2900 && duration[0] <= 3100, "Generated three-second WAV has the expected playback duration");
        runOnMainSync(() -> audio.play());
        deadline = SystemClock.elapsedRealtime() + 2000;
        final int[] position = {0};
        do {
            SystemClock.sleep(25);
            runOnMainSync(() -> position[0] = audio.positionMs());
        } while (position[0] == 0 && SystemClock.elapsedRealtime() < deadline);
        check(position[0] > 0 && audioError == null, "The imported audio playback clock actually advances");
        runOnMainSync(() -> audio.release());
    }

    private boolean ready() {
        final boolean[] value = {false};
        runOnMainSync(() -> value[0] = audio.isReady());
        return value[0];
    }

    private interface ImportOperation { String run() throws Exception; }

    private void reject(Context context, ImportOperation operation, String message) throws Exception {
        List<SongLibrary.Song> before = ImportedCharts.list(context);
        boolean rejected = false;
        try { operation.run(); } catch (Exception expected) { rejected = true; }
        List<SongLibrary.Song> after = ImportedCharts.list(context);
        boolean unchanged = before.size() == after.size();
        for (SongLibrary.Song song : before) unchanged &= contains(after, song.id);
        check(rejected && unchanged, message + "; visible import list stays unchanged");
    }

    private static SongLibrary.Song find(Context context, String id) throws Exception {
        for (SongLibrary.Song song : ImportedCharts.list(context)) if (song.id.equals(id)) return song;
        return null;
    }

    private static boolean contains(List<SongLibrary.Song> songs, String id) {
        for (SongLibrary.Song song : songs) if (song.id.equals(id)) return true;
        return false;
    }

    private void check(boolean condition, String message) {
        if (condition) passed++; else failed++;
        Bundle status = new Bundle();
        status.putString("stream", (condition ? "PASS " : "FAIL ") + message + "\n");
        sendStatus(condition ? 0 : -1, status);
    }

    private static byte[] chart() throws Exception {
        JSONObject note = new JSONObject().put("type", 1).put("time", 32).put("positionX", 0)
            .put("speed", 1).put("floorPosition", 0.5);
        JSONObject line = new JSONObject().put("bpm", 120).put("notesAbove", new JSONArray().put(note))
            .put("notesBelow", new JSONArray());
        return bytes(new JSONObject().put("formatVersion", 3).put("offset", 0)
            .put("judgeLineList", new JSONArray().put(line)).toString());
    }

    private static byte[] wav(int seconds, int variant) {
        int sampleRate = 8000, samples = seconds * sampleRate, size = samples * 2;
        byte[] data = new byte[44 + size];
        ascii(data, 0, "RIFF"); little(data, 4, 36 + size, 4); ascii(data, 8, "WAVEfmt ");
        little(data, 16, 16, 4); little(data, 20, 1, 2); little(data, 22, 1, 2);
        little(data, 24, sampleRate, 4); little(data, 28, sampleRate * 2, 4);
        little(data, 32, 2, 2); little(data, 34, 16, 2); ascii(data, 36, "data"); little(data, 40, size, 4);
        if (variant != 0) data[data.length - 2] = (byte) variant;
        return data;
    }

    private static void ascii(byte[] data, int offset, String text) {
        byte[] value = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(value, 0, data, offset, value.length);
    }

    private static void little(byte[] data, int offset, int value, int length) {
        for (int i = 0; i < length; i++) data[offset + i] = (byte) (value >>> (i * 8));
    }

    private static byte[] zip(Object... entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                zip.write((byte[]) entries[i + 1]);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static InputStream input(byte[] value) { return new ByteArrayInputStream(value); }

    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static boolean hasAsset(Context context, String path) {
        try (InputStream input = context.getAssets().open(path)) { return true; }
        catch (IOException missing) { return false; }
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
