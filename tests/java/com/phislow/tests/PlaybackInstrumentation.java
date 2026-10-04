package com.phislow.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import com.phislow.app.AudioEngine;
import com.phislow.app.BlockArea;
import com.phislow.app.Chart;
import com.phislow.app.LibraryAssets;
import com.phislow.app.MainActivity;
import com.phislow.app.NoteSkin;
import com.phislow.app.PackManagerActivity;
import com.phislow.app.PracticeView;
import com.phislow.app.Settings;
import com.phislow.app.SettingsActivity;
import com.phislow.app.SongLibrary;
import com.phislow.app.SongSelectActivity;
import java.util.ArrayList;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.File;

/** Device tests exercise the actual decoder and playback clock, without test libraries. */
public final class PlaybackInstrumentation extends Instrumentation {
    private AudioEngine audio;
    private String lastError;
    private boolean observingSeekCall, sawUnannouncedSeek;
    private int checks;
    private boolean noiseOnly;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        noiseOnly = arguments != null && "noise".equals(arguments.getString("group"));
        start();
    }

    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        super.callActivityOnCreate(activity, state);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        Context context = getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("phislow", Context.MODE_PRIVATE);
        Map<String, ?> savedPrefs = new HashMap<>(prefs.getAll());
        if (noiseOnly) {
            try {
                prefs.edit().clear().commit();
                checkNoiseZones();
                int charts = 0, noisy = 0;
                for (SongLibrary.Song song : new SongLibrary(context).songs)
                    for (SongLibrary.Selection selection : song.charts) {
                        Chart parsed = new Chart(SongLibrary.read(context, selection.path));
                        charts++; if (parsed.blockAreas.length != 0) noisy++;
                    }
                check(charts == 390 && noisy == 13, "All 390 library charts parse, including 13 with nonempty noise areas");
                checkNoisePlayback();
                resultCode = Activity.RESULT_OK;
                result.putString("stream", "\nPASS noise: " + checks + " checks\n");
            } catch (Throwable error) {
                result.putString("stream", "\nFAIL noise after " + checks + ": " + error + "\n");
            } finally { PracticeRegressionInstrumentation.restore(prefs, savedPrefs); }
            finish(resultCode, result);
            return;
        }
        Map<File, File> savedPacks = new HashMap<>();
        boolean packsIsolated = false;
        try {
            // Pack tests use empty storage; preserve the user's existing packs and selection.
            for (String name : new String[] {"packs", "note-skin", "active-pack"}) {
                File original = new File(context.getFilesDir(), name);
                if (!original.exists()) continue;
                File backup = new File(context.getCacheDir(), "practice-backup-" + System.nanoTime() + "-" + name);
                if (!original.renameTo(backup)) throw new java.io.IOException("Cannot preserve " + name);
                savedPacks.put(original, backup);
            }
            packsIsolated = true;
            prefs.edit().clear().commit();
            check(Settings.frameRate(context) == 0, "Fresh settings default to uncapped chart rendering");
            main(() -> {
                audio = new AudioEngine(getTargetContext(), message -> {
                    if (message != null) lastError = message;
                    if (observingSeekCall && !audio.isSeeking()) sawUnannouncedSeek = true;
                });
                audio.load(null);
            });
            awaitReady();
            check(readDuration() == 16000, "Original sample is 16 seconds");
            for (float speed : AudioEngine.SPEEDS) {
                main(() -> { audio.pause(); audio.seek(0, false); });
                awaitSeek();
                main(() -> audio.setSpeed(speed));
                SystemClock.sleep(250);
                check(!readPlaying(), "Changing speed while paused stays paused: " + speed);
                main(() -> audio.play());
                SystemClock.sleep(350);
                check(readPlaying(), "Can play at " + speed + "x; error=" + lastError);
                // After starting, getCurrentPosition() can stay pinned at zero for up to a second
                // at the slowest presets while the platform time-stretcher spins up. Wait for the
                // clock to actually move, then measure the steady-state rate so the spin-up does
                // not masquerade as a wrong speed.
                long spinUp = SystemClock.elapsedRealtime() + 3000;
                while (readPosition() == 0 && SystemClock.elapsedRealtime() < spinUp) SystemClock.sleep(50);
                check(readPosition() > 0, "Playback clock starts advancing at " + speed + "x");
                long wallStart = SystemClock.elapsedRealtime();
                int first = readPosition();
                int[] samples = new int[6];
                long[] walls = new long[6];
                for (int step = 0; step < samples.length; step++) {
                    SystemClock.sleep(300);
                    walls[step] = SystemClock.elapsedRealtime() - wallStart;
                    samples[step] = readPosition();
                }
                int second = samples[samples.length - 1];
                long wallElapsed = walls[walls.length - 1];
                float measured = (second - first) / (float) wallElapsed;
                StringBuilder trace = new StringBuilder();
                for (int step = 0; step < samples.length; step++) {
                    trace.append(' ').append(walls[step]).append("ms->").append(samples[step]).append("ms");
                }
                check(Math.abs(measured - speed) <= Math.max(0.05f, speed * 0.18f),
                    String.format(Locale.ROOT, "Playback clock %.2fx: observed %.3fx (%d ms / %d ms); position%s",
                        speed, measured, second - first, wallElapsed, trace));
            }
            main(() -> audio.pause());
            int paused = readPosition();
            SystemClock.sleep(400);
            check(Math.abs(readPosition() - paused) <= 80, "Paused position remains fixed");
            main(() -> {
                observingSeekCall = true;
                audio.seek(4000, false);
                observingSeekCall = false;
            });
            check(!sawUnannouncedSeek, "Seek state is announced before observers receive paused position");
            awaitSeek();
            check(!readPlaying() && Math.abs(readPosition() - 4000) < 100, "Paused seek stays paused at 4 seconds");
            main(() -> { audio.play(); audio.seek(7000, true); });
            awaitSeek();
            check(readPlaying() && Math.abs(readPosition() - 7000) < 400, "Playing seek resumes at 7 seconds");
            main(() -> audio.seek(15900, true));
            awaitSeek();
            SystemClock.sleep(600);
            check(!readPlaying(), "Completion stops playback");
            main(() -> audio.play());
            awaitSeek();
            check(readPlaying() && readPosition() < 3000, "Play after completion restarts from zero");
            main(() -> { audio.load(null); audio.load(null); audio.load(null); });
            awaitReady();
            check(readDuration() == 16000 && !readPlaying(), "Rapid track replacement keeps new track paused");
            main(() -> audio.load(Uri.parse("file:///data/local/tmp/phislow-missing-test-audio.wav")));
            SystemClock.sleep(300);
            check(!readReady(), "Unreadable file does not leave old playback state active");
            main(() -> audio.load(null));
            awaitReady();
            check(readReady(), "Can recover by importing a readable track");
            checkNoiseZones();
            checkPracticeRange();
            checkChartsAndTouches();
            checkNoteRendering();
            checkKeyWidths();
            checkBundledSkin();
            checkPerformanceNotes();
            checkFarHold();
            checkDoublePress();
            checkResourcePack();
            checkPackManager();
            checkPackPreview();
            checkHudAndSettings();
            checkScreensLaunch();
            checkSongListLayout();
            checkFullscreenFlow();
            result.putString("stream", "\nPASS: " + checks + " checks; playback, all "
                + AudioEngine.SPEEDS.length + " speed presets, practice range, bundled charts, cover art, "
                + "four touch types, autoplay, one key width for every note type, note size, the "
                + "Perfect/Good hit rings, any-direction flick, the bundled key art, "
                + "the double-press ring, the key-art resource pack, adding, applying and deleting "
                + "packs in the manager, the preview stage falling and playing a pack's keys, a "
                + "hold held down keeping its colour, performance notes on lines faded to nothing, "
                + "a hold whose head is minutes away staying off the stage, the combo counter above "
                + "the stage, the song's "
                + "name and difficulty in its bottom corners, hiding each of them from the settings, "
                + "the song list layout and its "
                + "clear button, state reset and the full-screen play/pause flow.\n");
            resultCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL after " + checks + " checks: " + error + "\n");
        } finally {
            if (audio != null) main(() -> audio.release());
            PracticeRegressionInstrumentation.restore(prefs, savedPrefs);
            if (packsIsolated) NoteSkin.clear(context);
            for (Map.Entry<File, File> saved : savedPacks.entrySet()) {
                if (!saved.getValue().renameTo(saved.getKey())) {
                    resultCode = Activity.RESULT_CANCELED;
                    result.putString("stream", result.getString("stream", "") + "\nCould not restore " + saved.getKey());
                }
            }
        }
        finish(resultCode, result);
    }

    private void main(Runnable action) { runOnMainSync(action); }
    private boolean readReady() { final boolean[] value = {false}; main(() -> value[0] = audio.isReady()); return value[0]; }
    private boolean readPlaying() { final boolean[] value = {false}; main(() -> value[0] = audio.isPlaying()); return value[0]; }
    private int readPosition() { final int[] value = {0}; main(() -> value[0] = audio.positionMs()); return value[0]; }
    private int readDuration() { final int[] value = {0}; main(() -> value[0] = audio.durationMs()); return value[0]; }
    private boolean readHasRange() { final boolean[] value = {false}; main(() -> value[0] = audio.hasPracticeRange()); return value[0]; }
    private boolean readNeedsJump() { final boolean[] value = {false}; main(() -> value[0] = audio.needsRangeJump()); return value[0]; }
    private int readRangeStart() { final int[] value = {0}; main(() -> value[0] = audio.rangeStart()); return value[0]; }
    private int readRangeEnd() { final int[] value = {0}; main(() -> value[0] = audio.rangeEnd()); return value[0]; }
    private void awaitReady() {
        long deadline = SystemClock.elapsedRealtime() + 6000;
        while (!readReady() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40);
        check(readReady(), "Audio prepared; error=" + lastError);
    }
    private void awaitSeek() {
        final boolean[] seeking = {true};
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            main(() -> seeking[0] = audio.isSeeking());
            if (seeking[0]) SystemClock.sleep(30);
        } while (seeking[0] && SystemClock.elapsedRealtime() < deadline);
        check(!seeking[0], "Seek completes");
    }
    private void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
        Bundle progress = new Bundle();
        progress.putString("stream", "PASS " + message + "\n");
        sendStatus(0, progress);
    }

    /**
     * The practice range is the extra feature this build adds to the pause screen: playback is
     * confined to [start, end], stops at the end instead of the track end, and re-enters at the
     * start rather than looping back over the whole song.
     */
    private void checkPracticeRange() {
        main(() -> { audio.setSpeed(1f); audio.clearPracticeRange(); });
        check(!readHasRange(), "No practice range is set by default");
        check(!readNeedsJump(), "Without a range the playhead never needs to jump");
        main(() -> audio.setPracticeRange(4000, 7000));
        check(readHasRange() && readRangeStart() == 4000 && readRangeEnd() == 7000,
            "Practice range stores an explicit start and end");
        main(() -> audio.seek(0, false));
        awaitSeek();
        check(readNeedsJump(), "Playhead before the range needs to jump to the range start");
        main(() -> audio.play());
        awaitSeek();
        SystemClock.sleep(600);
        check(readPlaying() && readPosition() >= 4200 && readPosition() < 5200,
            "Play enters at the practice start instead of at zero");
        SystemClock.sleep(3000);
        check(!readPlaying(), "Playback stops at the practice end instead of the track end");
        check(readPosition() >= 6800 && readPosition() <= 7400,
            "Playback stops at the practice end, not at 16 seconds");
        check(readNeedsJump(), "Playhead at the range end needs to jump back to the range start");
        main(() -> audio.play());
        awaitSeek();
        SystemClock.sleep(400);
        check(readPlaying() && readPosition() < 5200,
            "Resume after the range end restarts at the practice start rather than looping to zero");
        main(() -> { audio.pause(); audio.clearPracticeRange(); });
        check(!readHasRange(), "Practice range can be cleared so full-song playback returns");
        main(() -> audio.setPracticeRange(1000, 1200));
        check(!readHasRange(), "Ranges shorter than the engine minimum are rejected");
        main(() -> audio.clearPracticeRange());
    }

    /**
     * What the stage writes over the chart: the combo in the middle of the top edge, the song's
     * name in one bottom corner and its difficulty in the other, and a settings screen that can
     * take any of them away.
     *
     * Each part is counted in the patch of canvas it owns, and each reading is compared with the
     * same patch with that one part switched off - the chart underneath is frozen on the same frame
     * in both, so whatever the count loses is the HUD itself, not a note that happened to be there.
     * That is what stops the counter being quietly moved back into a line of text in the corner.
     */
    private void checkHudAndSettings() throws Exception {
        final Context context = getTargetContext();
        Settings.showCombo(context, true);
        Settings.showSong(context, true);
        SongLibrary library = new SongLibrary(context);
        if (library.songs.isEmpty()) throw new AssertionError("Library is empty");
        SongLibrary.Song song = library.songs.get(0);
        if (song.charts.isEmpty()) throw new AssertionError("No chart for " + song.id);
        SongLibrary.Selection selection = song.charts.get(0);

        Activity workbench = startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_TITLE, song.title)
            .putExtra(MainActivity.EXTRA_LEVEL, selection.difficulty)
            .putExtra(MainActivity.EXTRA_CHART, selection.path)
            .putExtra(MainActivity.EXTRA_AUDIO_ASSET, selection.audio));
        try {
            check(awaitText(workbench, "播放中", 20000), "A song starts playing on entry");
            check(click(workbench, "Ⅱ") && awaitShown(workbench, "暂停", 8000),
                "Pausing brings the workbench back, where the HUD can be read calmly");
            // A burst or two may still be animating; let them finish so both readings share a frame.
            SystemClock.sleep(700);
            int[] withAll = hudBands(workbench);
            check(withAll[0] > 200, "The combo is written in the middle of the top edge, where a "
                + "player looks for it (" + withAll[0] + "px of it)");
            check(withAll[1] > 60, "The song's name is written in the bottom left corner ("
                + withAll[1] + "px of it)");
            check(withAll[2] > 30, "The difficulty is written in the bottom right corner ("
                + withAll[2] + "px of it)");

            Activity settings = openSettings(workbench);
            try {
                check(awaitShown(settings, "设置", 8000), "The settings screen opens over the workbench");
                check(shown(settings, "COMBO 计数") && shown(settings, "判定统计")
                    && shown(settings, "曲名与难度") && shown(settings, "操作提示"),
                    "The settings list every part of the stage's display");
                check(clickDescription(settings, "开关 COMBO 计数"),
                    "The combo has a switch of its own");
                check(!Settings.showCombo(context), "Flipping the switch stores the choice");
            } finally {
                settings.finish();
            }
            SystemClock.sleep(700);
            int[] withoutCombo = hudBands(workbench);
            check(withoutCombo[0] + 150 < withAll[0], "Turning the combo off takes it off the stage"
                + " (" + withoutCombo[0] + "px left of " + withAll[0] + "px)");
            check(Math.abs(withoutCombo[1] - withAll[1]) < 30
                && Math.abs(withoutCombo[2] - withAll[2]) < 30,
                "Turning the combo off leaves the song's name and difficulty alone");

            Activity again = openSettings(workbench);
            try {
                check(clickDescription(again, "开关 曲名与难度"),
                    "The song's name and difficulty have a switch of their own");
                check(!Settings.showSong(context), "Flipping that switch stores the choice too");
            } finally {
                again.finish();
            }
            SystemClock.sleep(700);
            int[] withoutSong = hudBands(workbench);
            check(withoutSong[1] + 30 < withAll[1] && withoutSong[2] + 20 < withAll[2],
                "Turning the song's name and difficulty off clears both bottom corners ("
                + withoutSong[1] + "px and " + withoutSong[2] + "px left)");
            check(Math.abs(withoutSong[0] - withoutCombo[0]) < 30,
                "Turning the song's name off leaves the combo as it was");

            // Back on through the same switches, so the app is left as it was found and every part
            // is proved to come back rather than merely to have gone away.
            Activity restore = openSettings(workbench);
            try {
                check(clickDescription(restore, "开关 COMBO 计数")
                    && clickDescription(restore, "开关 曲名与难度"), "The switches go back on");
            } finally {
                restore.finish();
            }
            SystemClock.sleep(700);
            int[] restored = hudBands(workbench);
            check(restored[0] + 150 > withAll[0] && restored[1] + 30 > withAll[1]
                && restored[2] + 20 > withAll[2],
                "Turning the display back on brings every part of it to the stage again");
            check(Settings.showCombo(context) && Settings.showSong(context),
                "The restored choices are the ones stored");
        } finally {
            main(() -> workbench.finish());
        }
    }

    /**
     * Opens the settings over the given activity. The screen lives on the main interface now, so
     * the walks launch it directly rather than hunting for a button that is no longer there; the
     * switches themselves, and the workbench re-reading them on return, are what is under test.
     */
    private Activity openSettings(Activity over) {
        return startActivitySync(new Intent(getTargetContext(), SettingsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * Pixels that are not the stage background, in three patches: {top middle, bottom left,
     * bottom right}. The counters are grey or white over a dark stage, so anything painted in a
     * patch is ink, and the chart's own keys are the same in every reading taken from one frame.
     */
    private int[] hudBands(final Activity activity) {
        final int[] bands = new int[3];
        main(() -> {
            View view = findDescription(activity.getWindow().getDecorView(), "触屏谱面练习区域");
            if (view == null || view.getWidth() == 0 || view.getHeight() == 0) return;
            android.graphics.Bitmap bitmap = snapshot(view);
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            bands[0] = inkIn(bitmap, (int) (width * 0.36f), (int) (width * 0.64f), 0,
                (int) (height * 0.18f));
            bands[1] = inkIn(bitmap, 0, (int) (width * 0.34f), (int) (height * 0.84f), height);
            bands[2] = inkIn(bitmap, (int) (width * 0.66f), width, (int) (height * 0.84f), height);
            bitmap.recycle();
        });
        return bands;
    }

    /** Pixels inside a box that differ from the stage background by more than a trace of shading. */
    private static int inkIn(android.graphics.Bitmap bitmap, int fromX, int toX, int fromY, int toY) {
        int count = 0;
        for (int y = Math.max(0, fromY); y < Math.min(bitmap.getHeight(), toY); y++) {
            for (int x = Math.max(0, fromX); x < Math.min(bitmap.getWidth(), toX); x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Math.abs(((pixel >> 16) & 0xFF) - 0x0C) > 18
                        || Math.abs(((pixel >> 8) & 0xFF) - 0x14) > 18
                        || Math.abs((pixel & 0xFF) - 0x20) > 18) count++;
            }
        }
        return count;
    }

    /**
     * Both screens must survive launch. The immersive window setup runs during onCreate, where the
     * decor view and its insets controller do not exist yet, so an unguarded call crashes the app
     * before it can draw anything - which is exactly what happened once and is cheap to pin down.
     */
    private void checkScreensLaunch() {
        Intent intent = new Intent(getTargetContext(), SongSelectActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity select = startActivitySync(intent);
        try {
            check(awaitText(select, "开始游戏", 15000), "Song select launches and draws its controls");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                check(awaitSystemBarsHidden(select, 8000),
                    "Song select hides the system bars too, not only the chart screen");
            }
        } finally {
            main(() -> select.finish());
        }
    }

    /**
     * Two faults reported from the device. Every song title was cut off at the bottom, because the
     * two label lines shared a weighted height inside a match-parent column and came out as tall as
     * the 44x25 thumbnail; that is checked by measuring the rows that were really laid out. And the
     * search box could only be emptied by deleting the query by hand, so it now needs a one-tap
     * clear - driven here through the real control, not by reading the source.
     */
    private void checkSongListLayout() {
        Intent intent = new Intent(getTargetContext(), SongSelectActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity select = startActivitySync(intent);
        try {
            check(awaitText(select, "开始游戏", 15000), "Song select reaches its detail panel");
            check(awaitList(select, 15000), "The song list fills with rows");
            check(shown(select, "设置") && shown(select, "耳机"),
                "The main interface carries the settings and the headphone menu");

            final int[] rows = {0}, squeezed = {0};
            main(() -> {
                ListView list = listOf(select.getWindow().getDecorView());
                if (list == null) return;
                rows[0] = list.getChildCount();
                for (int index = 0; index < list.getChildCount(); index++) {
                    squeezed[0] += countSqueezed(list.getChildAt(index));
                }
            });
            check(squeezed[0] == 0, "All " + rows[0] + " laid-out rows give their labels a full line, "
                + "so no song title is cut off (" + squeezed[0] + " squeezed)");

            final EditText[] box = {null};
            final boolean[] typed = {false};
            main(() -> {
                View found = findEditText(select.getWindow().getDecorView(), "搜索曲名");
                box[0] = found instanceof EditText ? (EditText) found : null;
                if (box[0] != null) { box[0].setText("Poison"); typed[0] = true; }
            });
            check(typed[0], "The search box is on screen and accepts a query");

            final boolean[] showed = {false};
            main(() -> {
                View found = findDescription(select.getWindow().getDecorView(), "清除搜索内容");
                showed[0] = found != null && found.isShown();
            });
            check(showed[0], "A query makes the clear button appear next to the box");

            final boolean[] cleared = {false};
            main(() -> {
                View target = findDescription(select.getWindow().getDecorView(), "清除搜索内容");
                if (target != null) target.performClick();
                View after = findDescription(select.getWindow().getDecorView(), "清除搜索内容");
                cleared[0] = box[0] != null && box[0].getText().length() == 0
                    && (after == null || !after.isShown());
            });
            check(cleared[0], "One tap on the clear button empties the box and hides the button again");
        } finally {
            main(() -> select.finish());
        }
    }

    private boolean awaitList(Activity activity, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            final boolean[] ready = {false};
            main(() -> {
                ListView list = listOf(activity.getWindow().getDecorView());
                ready[0] = list != null && list.getChildCount() > 0;
            });
            if (ready[0]) return true;
            SystemClock.sleep(200);
        }
        return false;
    }

    private static ListView listOf(View node) {
        if (node instanceof ListView) return (ListView) node;
        View found = firstMatch(node, child -> listOf(child));
        return found instanceof ListView ? (ListView) found : null;
    }

    private static View findEditText(View node, String hint) {
        if (node instanceof EditText && node.isShown()
                && hint.contentEquals(String.valueOf(((EditText) node).getHint()))) return node;
        return firstMatch(node, child -> findEditText(child, hint));
    }

    /**
     * Counts laid-out labels whose box is shorter than one line of their own text. A squeezed label
     * means the bottom of the text is clipped, which is exactly the fault that was reported.
     */
    private static int countSqueezed(View node) {
        int count = 0;
        if (node instanceof TextView) {
            TextView label = (TextView) node;
            if (label.isShown() && label.getText() != null && label.getText().length() > 0
                    && label.getHeight() > 0 && label.getHeight() < label.getLineHeight()) count++;
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int index = 0; index < group.getChildCount(); index++) {
                count += countSqueezed(group.getChildAt(index));
            }
        }
        return count;
    }

    /**
     * Entering a song must start it immediately, full screen, at the speed picked on the song
     * screen; pausing must hand the window back to the practice workbench, and resuming must take
     * it over again. Driven through the real controls - the full-screen pause control and the
     * pause menu's resume button - on a real MainActivity, not by poking at internals.
     */
    private void checkFullscreenFlow() throws Exception {
        SongLibrary library = new SongLibrary(getTargetContext());
        if (library.songs.isEmpty()) throw new AssertionError("Library is empty");
        SongLibrary.Song song = library.songs.get(0);
        if (song.charts.isEmpty()) throw new AssertionError("No chart for " + song.id);
        SongLibrary.Selection selection = song.charts.get(0);
        Intent intent = new Intent(getTargetContext(), MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_TITLE, song.title)
            .putExtra(MainActivity.EXTRA_CHART, selection.path)
            .putExtra(MainActivity.EXTRA_AUDIO_ASSET, selection.audio)
            .putExtra(MainActivity.EXTRA_SPEED, 1.5f);
        Activity activity = startActivitySync(intent);
        try {
            check(awaitText(activity, "播放中", 20000),
                "Entering a song starts playing without any further tap");
            check(awaitShown(activity, "Ⅱ", 20000),
                "Playing puts the full-screen pause control on screen");
            check(!shown(activity, "▶ 播放"), "Playing hides the workbench transport controls");
            check(awaitCanvasFillsWindow(activity, 8000),
                "Playing measures the chart canvas against the whole window, not a padded card");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                check(awaitSystemBarsHidden(activity, 8000),
                    "The window hides the system bars, so the chart occupies the whole screen");
            }

            check(click(activity, "Ⅱ"), "The full-screen pause control is reachable");
            check(awaitShown(activity, "暂停", 8000), "Pausing brings the practice workbench back");
            check(awaitText(activity, "已暂停", 8000), "Pausing stops the playback clock");
            check(!shown(activity, "Ⅱ"), "The full-screen control is hidden while paused");
            check(canvasIsCardSized(activity), "Paused chart canvas sits in the workbench card again");

            check(click(activity, "继续", "开始"), "The pause menu resume control is reachable");
            check(awaitText(activity, "播放中", 8000), "Resuming restarts playback");
            check(awaitShown(activity, "Ⅱ", 8000), "Resuming returns to full screen");
            check(awaitCanvasFillsWindow(activity, 8000),
                "Resumed playback fills the window again");
        } finally {
            main(() -> activity.finish());
        }
    }

    /** The chart canvas is shown and exactly covers the window's content area. */
    private boolean canvasFillsWindow(Activity activity) {
        final boolean[] value = {false};
        runOnMainSync(() -> {
            View canvas = findDescription(activity.getWindow().getDecorView(), "触屏谱面练习区域");
            View content = activity.findViewById(android.R.id.content);
            value[0] = canvas != null && canvas.isShown() && content != null
                && canvas.getWidth() == content.getWidth() && canvas.getHeight() == content.getHeight();
        });
        return value[0];
    }

    /** The canvas is shown but only partly fills the window, i.e. it is back in the workbench. */
    private boolean canvasIsCardSized(Activity activity) {
        final boolean[] value = {false};
        runOnMainSync(() -> {
            View canvas = findDescription(activity.getWindow().getDecorView(), "触屏谱面练习区域");
            View content = activity.findViewById(android.R.id.content);
            value[0] = canvas != null && canvas.isShown() && content != null
                && canvas.getWidth() < content.getWidth();
        });
        return value[0];
    }

    private boolean awaitText(Activity activity, String label, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            if (hasText(activity, label)) return true;
            SystemClock.sleep(120);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private boolean awaitShown(Activity activity, String label, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            if (shown(activity, label)) return true;
            SystemClock.sleep(120);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private boolean awaitCanvasFillsWindow(Activity activity, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            if (canvasFillsWindow(activity)) return true;
            SystemClock.sleep(120);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private boolean awaitSystemBarsHidden(Activity activity, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            if (systemBarsHidden(activity)) return true;
            SystemClock.sleep(120);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    /**
     * The window owns the whole screen: the insets say the system bars are gone, rather than the
     * app merely drawing something dark behind them. Only meaningful from API 30, where the legacy
     * SYSTEM_UI_FLAG_* pair stopped working and the insets controller took over.
     */
    private boolean systemBarsHidden(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        final boolean[] value = {false};
        runOnMainSync(() -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            value[0] = insets != null
                && !insets.isVisible(WindowInsets.Type.statusBars())
                && !insets.isVisible(WindowInsets.Type.navigationBars());
        });
        return value[0];
    }

    private boolean hasText(Activity activity, String label) {
        final boolean[] value = {false};
        runOnMainSync(() -> value[0] = findText(activity.getWindow().getDecorView(), label, false) != null);
        return value[0];
    }

    private boolean shown(Activity activity, String label) {
        final boolean[] value = {false};
        runOnMainSync(() -> {
            View found = findText(activity.getWindow().getDecorView(), label, true);
            value[0] = found != null && found.isShown();
        });
        return value[0];
    }

    /** Clicks the first visible button whose label matches exactly, like a real tap would. */
    private boolean click(Activity activity, final String... labels) {
        final boolean[] clicked = {false};
        runOnMainSync(() -> {
            View target = findButton(activity.getWindow().getDecorView(), labels);
            if (target != null) {
                target.performClick();
                clicked[0] = true;
            }
        });
        return clicked[0];
    }

    /** Exact text match, so "暂停" never matches the workbench's "Ⅱ 暂停" affordance. */
    private static View findText(View node, String label, boolean visibleOnly) {
        if (node instanceof TextView && (!visibleOnly || node.isShown())
                && label.equals(String.valueOf(((TextView) node).getText()))) return node;
        return firstMatch(node, child -> findText(child, label, visibleOnly));
    }

    private static View findButton(View node, String[] labels) {
        if (node instanceof Button && node.isShown()) {
            String text = String.valueOf(((Button) node).getText());
            for (String label : labels) if (text.equals(label)) return node;
        }
        return firstMatch(node, child -> findButton(child, labels));
    }

    private static View findDescription(View node, String description) {
        CharSequence own = node.getContentDescription();
        if (own != null && description.contentEquals(own)) return node;
        return firstMatch(node, child -> findDescription(child, description));
    }

    /** Applies {@code search} to each direct child; the search itself recurses. */
    private static View firstMatch(View node, NodeSearch search) {
        if (!(node instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) node;
        for (int index = 0; index < group.getChildCount(); index++) {
            View found = search.on(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }

    private interface NodeSearch { View on(View node); }

    /**
     * The chart has to read at a glance: notes chunky enough to aim at, and a hit effect whose ring
     * colour tells Perfect from Good, as the legend on screen promises. Measured off the pixels the
     * view actually paints, so shrinking a note back or dropping the ring cannot stay green.
     */
    private void checkNoteRendering() throws Exception {
        final String tapOnly = "{\"formatVersion\":3,\"offset\":0,\"judgeLineList\":[{\"bpm\":120,"
            + "\"speedEvents\":[{\"startTime\":0,\"endTime\":32000,\"value\":1}],"
            + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0.5,\"end\":0.5,\"start2\":0.5,\"end2\":0.5}],"
            + "\"notesAbove\":[{\"type\":1,\"time\":32,\"positionX\":0}],\"notesBelow\":[]}]}";
        final Chart chart = new Chart(tapOnly);
        final int[] wide = {0};
        main(() -> {
            PracticeView view = chartView(chart);
            view.setPlayback(500, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            wide[0] = widestRunAt(bitmap, 0xFF6DE4FA, 202);
            bitmap.recycle();
        });
        check(wide[0] >= 50, "A tap note spans " + wide[0]
            + "px of the 720px canvas, so the keys read large (a 0.025 half-width note spanned 36)");

        final int[] perfectRing = {0}, goodRing = {0};
        main(() -> {
            PracticeView view = chartView(chart);
            view.setPlayback(500, true, 1);
            touch(view, MotionEvent.ACTION_DOWN, 360, 202);
            touch(view, MotionEvent.ACTION_UP, 360, 202);
            android.graphics.Bitmap bitmap = snapshot(view);
            perfectRing[0] = countNear(bitmap, 0xFFFFD34D, 26);
            goodRing[0] = countNear(bitmap, 0xFF4FA8FF, 26);
            bitmap.recycle();
        });
        check(perfectRing[0] > 200 && goodRing[0] == 0,
            "A Perfect leaves a yellow hit ring (" + perfectRing[0] + "px) and no blue one ("
                + goodRing[0] + "px)");

        final int[] goodBlue = {0}, goodYellow = {0};
        main(() -> {
            PracticeView view = chartView(chart);
            view.setPlayback(700, true, 1);
            touch(view, MotionEvent.ACTION_DOWN, 360, 202);
            touch(view, MotionEvent.ACTION_UP, 360, 202);
            android.graphics.Bitmap bitmap = snapshot(view);
            goodBlue[0] = countNear(bitmap, 0xFF4FA8FF, 26);
            goodYellow[0] = countNear(bitmap, 0xFFFFD34D, 26);
            bitmap.recycle();
        });
        check(goodBlue[0] > 200 && goodYellow[0] == 0,
            "A Good leaves a blue hit ring (" + goodBlue[0] + "px) and no yellow one ("
                + goodYellow[0] + "px)");
    }

    /**
     * Two keys on one beat have to go down together, and the chart has to say so before the keys
     * arrive. Both halves are checked: the chart reading, and the painted ring. The ring is counted
     * off the pixels, so dropping it - or letting it eat into the key - cannot stay green.
     */
    private void checkDoublePress() throws Exception {
        final Chart mixed = noteChart(new double[][] {{0, 32}, {4, 32}, {8, 33}});
        final boolean[] flags = new boolean[3];
        main(() -> {
            PracticeView view = chartView(mixed);
            for (int i = 0; i < flags.length; i++) flags[i] = view.isDoublePress(i);
        });
        check(flags[0] && flags[1] && !flags[2],
            "Two notes on the same beat are read as a double press, and a note a tick later is not");

        final Chart chord = noteChart(new double[][] {{0, 32}, {4, 32}, {8, 32}});
        final int[] marked = {0};
        main(() -> {
            PracticeView view = chartView(chord);
            for (int i = 0; i < chord.notes.length; i++) if (view.isDoublePress(i)) marked[0]++;
        });
        check(marked[0] == 3,
            "Every note of a three-note chord is marked, so a chord is not read as a single double");

        final Chart pair = noteChart(new double[][] {{0, 32}, {4, 32}});
        final int[] gold = {0}, lining = {0}, width = {0};
        main(() -> {
            PracticeView view = chartView(pair);
            view.setPlayback(500, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            // Rows around the judge line only: the HUD text above is drawn in white as well.
            gold[0] = countNearInBand(bitmap, 0xFFFDD146, 12, 180, 225);
            lining[0] = countNearInBand(bitmap, 0xFFFFFFFF, 6, 180, 225);
            width[0] = widestRunAt(bitmap, 0xFF6DE4FA, 202);
            bitmap.recycle();
        });
        check(gold[0] > 60 && lining[0] > 40, "A double press draws a gold ring (" + gold[0]
            + "px) with a white lining (" + lining[0] + "px) around the key");
        check(width[0] >= 50, "The double-press ring sits outside the key, which keeps its full "
            + width[0] + "px width");

        final Chart solo = noteChart(new double[][] {{0, 32}});
        final int[] lone = {0};
        main(() -> {
            PracticeView view = chartView(solo);
            view.setPlayback(500, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            lone[0] = countNearInBand(bitmap, 0xFFFDD146, 12, 180, 225);
            bitmap.recycle();
        });
        check(lone[0] == 0,
            "A lone note gets no gold ring (" + lone[0] + "px), so the ring really means a double");
    }

    /**
     * One width for every key. The hold used to run its ribbon at 0.65 of the width its own ends
     * and every other key used, so a hold read as thinner than the rest. Widths are read off the
     * pixels for each type in turn, and a hold is also measured down its whole length, so a ribbon
     * narrower than the head cannot stay green.
     */
    private void checkKeyWidths() throws Exception {
        final Chart four = typedChart(new int[] {1, 2, 3, 4});
        final int[] widths = new int[4];
        final int[] holdMin = {0}, holdMax = {0};
        main(() -> {
            PracticeView view = chartView(four);
            view.setPlayback(500, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            widths[0] = widestRunAt(bitmap, 0xFF6DE4FA, 202);
            widths[1] = widestRunAt(bitmap, 0xFFF4CE68, 202);
            widths[2] = widestRunAt(bitmap, 0xFF78D5EF, 202);
            widths[3] = widestRunAt(bitmap, 0xFFFF87BB, 202);
            // Every row the hold is painted on, not just the row its head lands on.
            int min = Integer.MAX_VALUE, max = 0;
            for (int y = 0; y < bitmap.getHeight(); y++) {
                int wide = widestRunAt(bitmap, 0xFF78D5EF, y);
                if (wide > 0) { if (wide < min) min = wide; if (wide > max) max = wide; }
            }
            holdMin[0] = min; holdMax[0] = max;
            bitmap.recycle();
        });
        check(widths[0] > 0 && widths[0] == widths[1] && widths[0] == widths[2]
                && widths[0] == widths[3],
            "Tap, drag, hold and flick are painted the same width (" + widths[0] + "/"
                + widths[1] + "/" + widths[2] + "/" + widths[3] + "px)");
        check(holdMax[0] == widths[0] && holdMin[0] >= widths[0] - 4,
            "A hold keeps that width along its whole ribbon (" + holdMin[0] + ".." + holdMax[0]
                + "px against a tap key's " + widths[0] + "px), so it is not narrower than its ends");
    }

    /**
     * The art that ships with the app is the default look: every key and its gold-framed double
     * come from it without an import, the frame lives in the art's padding outside the key body,
     * and the drawn ring steps aside where the art already carries one. The body width is read off
     * the pixels of a lone key and of a double, so a frame that ate into the body cannot stay green.
     */
    private void checkBundledSkin() throws Exception {
        final Context context = getTargetContext();
        final NoteSkin bundled = NoteSkin.bundled(context);
        check(bundled.note(NoteSkin.TAP) != null && bundled.note(NoteSkin.DRAG) != null
                && bundled.note(NoteSkin.HOLD) != null && bundled.note(NoteSkin.FLICK) != null,
            "The bundled art supplies all four key types without an import");
        check(bundled.noteDouble(NoteSkin.TAP) != null && bundled.noteDouble(NoteSkin.DRAG) != null
                && bundled.noteDouble(NoteSkin.HOLD) != null && bundled.noteDouble(NoteSkin.FLICK) != null,
            "The bundled art supplies a gold-framed double for every key type");
        check(bundled.bodyRect(NoteSkin.TAP) != null && bundled.holdCaps() != null
                && bundled.holdCaps()[0] > 0 && bundled.holdCaps()[1] > 0,
            "The bundled art says where its key body sits and how its hold caps are cut");
        check(bundled.hitFx() != null && bundled.hitFxColumns() == 6 && bundled.hitFxRows() == 8
                && bundled.hitFxSeconds() > 0,
            "The bundled art carries the hit-effect atlas and how long it plays");

        final Chart pair = noteChart(new double[][] {{0, 32}, {4, 32}});
        final Chart solo = noteChart(new double[][] {{0, 32}});
        final int[] bodyColor = {0}, soloWidth = {0}, pairWidth = {0};
        final int[] framed = {0}, bare = {0}, overhang = {0};
        main(() -> {
            PracticeView view = chartView(solo);
            view.setFallbackSkin(bundled);
            view.setPlayback(500, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            bodyColor[0] = bitmap.getPixel(360, 202);
            soloWidth[0] = widestRunNear(bitmap, bodyColor[0], 202, 24);
            bare[0] = countNearInBand(bitmap, 0xFFFDD146, 12, 180, 225);
            bitmap.recycle();
        });
        main(() -> {
            PracticeView view = chartView(pair);
            view.setFallbackSkin(bundled);
            view.setPlayback(500, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            pairWidth[0] = widestRunNear(bitmap, bodyColor[0], 202, 24);
            framed[0] = countNearInBand(bitmap, 0xFFFDD146, 12, 180, 225);
            // Gold further from the key's centre than the key box reaches: the frame is in the
            // padding the art carries for it, not squeezed inside the body.
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Math.abs(x - 360) <= 29) continue;
                if (countNearInBand(bitmap, 0xFFFDD146, 12, x, x + 1) > 0) overhang[0]++;
            }
            bitmap.recycle();
        });
        check(soloWidth[0] >= 50, "The bundled art paints a key body " + soloWidth[0]
            + "px wide, the width the key box gives it");
        check(pairWidth[0] == soloWidth[0], "A double keeps the same " + pairWidth[0]
            + "px body as a lone key, so the frame adds nothing to the key itself");
        check(framed[0] > 60 && bare[0] == 0, "A double shows the art's gold frame (" + framed[0]
            + "px) and a lone key shows none (" + bare[0] + "px)");
        check(overhang[0] > 0, "The gold frame reaches outside the key box (" + overhang[0]
            + " columns), so the art's padding is doing its job");

        // Clearing an imported pack lands back on this art, not on the drawn keys: the same note
        // painted with and without the bundled art reads as two different colours.
        final int[] withArt = {0}, drawn = {0};
        main(() -> {
            PracticeView view = chartView(solo);
            view.setSkin(NoteSkin.load(context));
            view.setFallbackSkin(bundled);
            view.setPlayback(500, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            withArt[0] = bitmap.getPixel(360, 202);
            bitmap.recycle();
            PracticeView plain = chartView(solo);
            plain.setPlayback(500, false, 1);
            android.graphics.Bitmap plainShot = snapshot(plain);
            drawn[0] = plainShot.getPixel(360, 202);
            plainShot.recycle();
        });
        check(withArt[0] != drawn[0],
            "With no pack installed the view paints the bundled art rather than the drawn key");
    }

    /**
     * A one-line chart with one note of each type, all on the same beat. The line sits in the
     * middle of the canvas, so the notes are spread either side of it: negative and positive
     * positionX both work, and the spacing keeps the keys from touching.
     */
    private static Chart typedChart(int[] types) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) body.append(',');
            body.append("{\"type\":").append(types[i]).append(",\"time\":32,\"positionX\":")
                .append(-6 + i * 4);
            // A hold needs a length, otherwise it is a tap with a different colour.
            if (types[i] == 3) body.append(",\"holdTime\":32");
            body.append('}');
        }
        return new Chart("{\"formatVersion\":3,\"offset\":0,\"judgeLineList\":[{\"bpm\":120,"
            + "\"speedEvents\":[{\"startTime\":0,\"endTime\":32000,\"value\":1}],"
            + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0.5,\"end\":0.5,\"start2\":0.5,\"end2\":0.5}],"
            + "\"notesAbove\":[" + body + "],\"notesBelow\":[]}]}");
    }

    /** A one-line chart of tap notes, given as {positionX, tick} pairs. */
    private static Chart noteChart(double[][] notes) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < notes.length; i++) {
            if (i > 0) body.append(',');
            body.append("{\"type\":1,\"time\":").append((long) notes[i][1])
                .append(",\"positionX\":").append(notes[i][0]).append('}');
        }
        return new Chart("{\"formatVersion\":3,\"offset\":0,\"judgeLineList\":[{\"bpm\":120,"
            + "\"speedEvents\":[{\"startTime\":0,\"endTime\":32000,\"value\":1}],"
            + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0.5,\"end\":0.5,\"start2\":0.5,\"end2\":0.5}],"
            + "\"notesAbove\":[" + body + "],\"notesBelow\":[]}]}");
    }

    /**
     * The resource pack is what replaces the shipped button artwork the reference build never
     * carried. Driven through the real import path, because the parts that actually fail are the
     * file-name matching, a partial pack, and refusing to wipe a working pack when the chosen file
     * turns out not to be one.
     */
    private void checkResourcePack() throws Exception {
        final Context context = getTargetContext();
        NoteSkin.clear(context);
        check(!NoteSkin.isInstalled(context), "No key-art resource pack ships with the build");
        check(!NoteSkin.install(context, stream("not a zip at all".getBytes("UTF-8"))).isActive(),
            "A file that is not a resource pack is rejected and leaves the built-in keys in place");

        byte[] art = solidPng(0xFFFF00FF, 96, 24);
        // The picker hands the app a document, so the pack goes in through the very same
        // content-resolver read the picker result feeds, not through a shortcut only tests can use.
        // The names walk the whole matcher: an English name behind a folder with the wrong case, a
        // Chinese alias per the on-screen legend (蓝色 = tap, 黄色 = drag, 粉键 = flick), and a file
        // that means nothing. Hold is deliberately absent, so the pack is also a partial one.
        java.io.File chosen = new java.io.File(context.getCacheDir(), "phislow-test-pack.zip");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(chosen)) {
            output.write(pack(new String[] {"skin/Tap.PNG", "黄色.png", "粉键.png", "readme.txt"},
                art, art, art, "ignore me".getBytes("UTF-8")));
        }
        NoteSkin skin = NoteSkin.importZip(context, Uri.fromFile(chosen));
        check(skin.isActive() && skin.note(NoteSkin.TAP) != null && skin.note(NoteSkin.DRAG) != null
                && skin.note(NoteSkin.FLICK) != null,
            "The chosen document maps the tap key by name and the drag and flick keys by their "
                + "Chinese names, ignoring the case and the folder");
        check(skin.note(NoteSkin.HOLD) == null,
            "Note types the pack omits keep the built-in key instead of failing the whole pack");

        final Chart tapChart = oneTapChart();
        final int[] painted = {0};
        main(() -> {
            PracticeView view = chartView(tapChart);
            view.setSkin(NoteSkin.load(getTargetContext()));
            view.setPlayback(500, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            painted[0] = countNear(bitmap, 0xFFFF00FF, 12);
            bitmap.recycle();
        });
        check(painted[0] > 200, "The live canvas paints the imported key art instead of the built-in bar ("
            + painted[0] + "px of the artwork's colour)");

        NoteSkin kept = NoteSkin.install(context,
            stream(pack(new String[] {"notes.txt"}, "nothing here".getBytes("UTF-8"))));
        check(kept.note(NoteSkin.TAP) != null,
            "A pack with no recognisable key image keeps the installed one rather than wiping it");

        NoteSkin replaced = NoteSkin.install(context, stream(pack(
            new String[] {"hold.png", "hold_body.png"},
            solidPng(0xFF00FFFF, 96, 24), solidPng(0xFF00FF00, 8, 8))));
        check(replaced.note(NoteSkin.HOLD) != null && replaced.holdBody() != null
                && replaced.note(NoteSkin.TAP) == null,
            "Importing a new pack replaces the previous one instead of merging the two, "
                + "and takes a separate hold body");

        // The header button doubles as the status, so a fresh workbench must show the tick.
        Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity workbench = startActivitySync(intent);
        try {
            check(awaitShown(workbench, "资源包 ✓", 15000),
                "The workbench header reports the installed resource pack (installed="
                    + NoteSkin.isInstalled(getTargetContext()) + ", active="
                    + NoteSkin.activeId(getTargetContext()) + ", plain header shown="
                    + shown(workbench, "资源包") + ")");
        } finally {
            main(() -> workbench.finish());
        }

        NoteSkin.clear(context);
        check(!NoteSkin.isInstalled(context) && !NoteSkin.load(context).isActive(),
            "Clearing the pack restores the built-in keys");
        chosen.delete();
    }

    /**
     * Packs are managed on their own screen: added, applied and deleted there, one at a time.
     * The storage is walked first - two packs side by side, one applied, one deleted - and then
     * the screen itself, driven through the buttons a user actually reaches for.
     */
    private void checkPackManager() throws Exception {
        final Context context = getTargetContext();
        NoteSkin.clear(context);
        check(NoteSkin.BUILTIN.equals(NoteSkin.activeId(context)),
            "With nothing imported the app uses the art it was built with");
        java.util.List<NoteSkin.Pack> fresh = NoteSkin.list(context);
        check(fresh.size() == 1 && fresh.get(0).builtin && fresh.get(0).active,
            "The manager offers only the built-in art until something is imported");

        byte[] magenta = solidPng(0xFFFF00FF, 96, 24);
        String first = NoteSkin.add(context,
            stream(pack(new String[] {"tap.png", "drag.png"}, magenta, magenta)), "测试包甲");
        String second = NoteSkin.add(context,
            stream(pack(new String[] {"hold.png"}, solidPng(0xFF00FFFF, 96, 24))), "测试包乙");
        check(first != null && second != null && !first.equals(second),
            "Two packs sit side by side instead of the newer one replacing the older");
        check(NoteSkin.list(context).size() == 3,
            "Both imported packs are listed next to the built-in one");
        check(second.equals(NoteSkin.activeId(context)), "Adding a pack makes it the one in use");

        NoteSkin.activate(context, first);
        NoteSkin now = NoteSkin.load(context);
        check(now.note(NoteSkin.TAP) != null && now.note(NoteSkin.HOLD) == null,
            "Applying a pack hands over the keys that pack carries, not a merge of the two");

        check(!NoteSkin.remove(context, NoteSkin.BUILTIN),
            "The built-in art is not something the manager can delete");
        check(NoteSkin.remove(context, first)
                && NoteSkin.BUILTIN.equals(NoteSkin.activeId(context))
                && !NoteSkin.load(context).isActive(),
            "Deleting the pack in use falls back to the built-in art rather than leaving no keys");
        check(NoteSkin.add(context, stream("not a zip at all".getBytes("UTF-8")), "不是资源包") == null
                && NoteSkin.list(context).size() == 2,
            "A file with no key art in it is refused and never reaches the list");

        Activity manager = startActivitySync(new Intent(context, PackManagerActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            check(awaitShown(manager, "资源包管理", 15000), "The workbench header opens the pack manager");
            check(shown(manager, "测试包乙"), "An imported pack is listed under the name it was added with");
            check(clickDescription(manager, "应用资源包 测试包乙"),
                "Each row carries its own apply button, so the right pack can be chosen");
            SystemClock.sleep(400);
            check(second.equals(NoteSkin.activeId(context)),
                "Tapping apply makes that pack the one the keys are drawn from");
            check(shown(manager, "使用中"), "The row of the pack in use says so");
            check(clickDescription(manager, "删除资源包 测试包乙"),
                "Each imported row carries its own delete button");
            SystemClock.sleep(400);
            check(NoteSkin.list(context).size() == 1 && !shown(manager, "测试包乙"),
                "Deleting a pack drops it from the list and from the screen");
        } finally {
            main(() -> manager.finish());
        }

        NoteSkin.clear(context);
        check(!NoteSkin.isInstalled(context) && NoteSkin.list(context).size() == 1,
            "Clearing leaves nothing behind but the built-in art");
    }

    /**
     * The manager previews a pack the way it plays: its own keys falling down a judge line on the
     * stage beside the list, played by themselves. Walked with a pack that carries one key only,
     * which is what proves the rest of the lanes are the built-in art and not the pack's - the
     * same mix the game draws. The stage has to be moving and it has to be played, not merely
     * painted: a key that is struck leaves the stage at the judge line, where one that is not
     * played falls away off the bottom, so where it disappears tells the two apart.
     */
    private void checkPackPreview() throws Exception {
        final Context context = getTargetContext();
        NoteSkin.clear(context);
        final int magenta = 0xFFFF00FF;
        String id = NoteSkin.add(context,
            stream(pack(new String[] {"tap.png"}, solidPng(magenta, 96, 24))), "预览测试包");
        check(id != null, "A pack can be added to preview");

        Activity manager = startActivitySync(new Intent(context, PackManagerActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            check(awaitShown(manager, "资源包管理", 15000), "The manager opens for the preview walk");
            check(clickDescription(manager, "预览资源包 预览测试包"),
                "A pack is previewed by tapping its row, without applying it");

            // The pack's own key, in its own lane, and the built-in art in the lane it has none for.
            int[] tap = awaitStage(manager, 0, magenta, 12000, 0, 20);
            check(tap[0] > 20, "The stage falls the pack's own key in its lane (" + tap[0] + "px of it)");
            int[] hold = awaitStage(manager, 2, magenta, 12000, 3, 200);
            check(hold[0] == 0,
                "The lanes a pack leaves empty are fallen with the built-in art, not with the pack's");

            // Falling, not standing: follow the key down through a handful of frames.
            int lowest = tap[1], highest = tap[1];
            for (int frame = 0; frame < 8; frame++) {
                SystemClock.sleep(140);
                int[] probe = stageLane(manager, 0, magenta);
                if (probe[0] <= 20) continue;   // struck and waiting for the next round
                lowest = Math.min(lowest, probe[1]);
                highest = Math.max(highest, probe[1]);
            }
            check(highest - lowest > 6, "The previewed keys are falling down the stage, not sitting "
                + "still (" + lowest + "px to " + highest + "px)");

            // Played, not merely fallen: a struck key leaves at the judge line, well above the
            // bottom of the stage, and the stage keeps its height while it happens.
            int height = tap[2] == 0 ? 1 : tap[2];
            check(highest > height * 0.45f && highest < height * 0.85f,
                "A key that reaches the judge line is played there instead of falling off the "
                + "stage (" + highest + "px of " + height + "px)");

            check(clickDescription(manager, "预览资源包 内置按键美术"), "The built-in row previews too");
            int[] builtin = awaitStage(manager, 0, magenta, 12000, 3, 200);
            check(builtin[0] == 0 && builtin[3] > 200,
                "Previewing the built-in row puts the art that ships with the app back on the stage");
        } finally {
            main(() -> manager.finish());
        }

        NoteSkin.clear(context);
        check(NoteSkin.list(context).size() == 1, "The preview walk leaves nothing but the built-in art");
    }

    /**
     * One lane of the preview stage, as {pixels of {@code colour}, their centre row, stage height,
     * pixels that are neither the stage nor its judge line}. The stage is empty until the pack's
     * art has been decoded and a key is actually on it, so a walk waits on one of those counts
     * rather than reading the lane the moment the view exists.
     */
    private int[] awaitStage(final Activity activity, final int lane, final int colour, long millis,
            final int field, final int least) {
        long deadline = SystemClock.uptimeMillis() + millis;
        int[] probe = stageLane(activity, lane, colour);
        while (probe[field] < least && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(150);
            probe = stageLane(activity, lane, colour);
        }
        return probe;
    }

    private int[] stageLane(final Activity activity, final int lane, final int colour) {
        final int[] probe = new int[4];
        main(() -> {
            View view = findDescription(activity.getWindow().getDecorView(), "资源包按键预览");
            if (view == null || view.getWidth() == 0 || view.getHeight() == 0) return;
            android.graphics.Bitmap bitmap = snapshot(view);
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int from = width * lane / 4, to = width * (lane + 1) / 4;
            int count = 0, ink = 0;
            long rows = 0;
            for (int y = 0; y < height; y++) {
                for (int x = from; x < to; x++) {
                    int pixel = bitmap.getPixel(x, y);
                    int red = (pixel >> 16) & 0xFF, green = (pixel >> 8) & 0xFF, blue = pixel & 0xFF;
                    if (Math.abs(red - ((colour >> 16) & 0xFF)) <= 12
                            && Math.abs(green - ((colour >> 8) & 0xFF)) <= 12
                            && Math.abs(blue - (colour & 0xFF)) <= 12) { count++; rows += y; }
                    if (Math.abs(red - 0x0C) > 40 || Math.abs(green - 0x14) > 40
                            || Math.abs(blue - 0x20) > 40) ink++;
                }
            }
            probe[0] = count;
            probe[1] = count == 0 ? 0 : (int) (rows / count);
            probe[2] = height;
            probe[3] = ink;
            bitmap.recycle();
        });
        return probe;
    }

    private static java.io.InputStream stream(byte[] bytes) {
        return new java.io.ByteArrayInputStream(bytes);
    }

    /** Clicks a control by its content description, so one row's button is never another's. */
    private boolean clickDescription(Activity activity, final String description) {
        final boolean[] clicked = {false};
        runOnMainSync(() -> {
            View target = findDescription(activity.getWindow().getDecorView(), description);
            if (target != null) {
                target.performClick();
                clicked[0] = true;
            }
        });
        return clicked[0];
    }

    /** A minimal ZIP whose entries carry the given names and payloads. */
    private static byte[] pack(String[] names, byte[]... parts) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output);
        for (int index = 0; index < names.length && index < parts.length; index++) {
            zip.putNextEntry(new java.util.zip.ZipEntry(names[index]));
            zip.write(parts[index]);
            zip.closeEntry();
        }
        zip.close();
        return output.toByteArray();
    }

    private static byte[] solidPng(int color, int width, int height) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    private static Chart oneTapChart() throws Exception {
        return new Chart("{\"formatVersion\":3,\"offset\":0,\"judgeLineList\":[{\"bpm\":120,"
            + "\"speedEvents\":[{\"startTime\":0,\"endTime\":32000,\"value\":1}],"
            + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0.5,\"end\":0.5,\"start2\":0.5,\"end2\":0.5}],"
            + "\"notesAbove\":[{\"type\":1,\"time\":32,\"positionX\":0}],\"notesBelow\":[]}]}");
    }

    private PracticeView chartView(Chart chart) {
        PracticeView view = new PracticeView(getTargetContext());
        view.layout(0, 0, 720, 405);
        view.setChart(chart);
        return view;
    }

    private static android.graphics.Bitmap snapshot(View view) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
            view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        return bitmap;
    }

    /** The longest horizontal run of an exact colour on one row: how wide a note is painted. */
    private static int widestRunAt(android.graphics.Bitmap bitmap, int color, int y) {
        return widestRunNear(bitmap, color, y, 0);
    }

    /** The same with a tolerance, for art whose body shading shifts as it is scaled down. */
    private static int widestRunNear(android.graphics.Bitmap bitmap, int color, int y, int tolerance) {
        int best = 0, run = 0;
        for (int x = 0; x < bitmap.getWidth(); x++) {
            int pixel = bitmap.getPixel(x, y);
            boolean near = Math.abs(((pixel >> 16) & 0xFF) - ((color >> 16) & 0xFF)) <= tolerance
                && Math.abs(((pixel >> 8) & 0xFF) - ((color >> 8) & 0xFF)) <= tolerance
                && Math.abs((pixel & 0xFF) - (color & 0xFF)) <= tolerance;
            if (near) { run++; if (run > best) best = run; } else run = 0;
        }
        return best;
    }

    /** Pixels close to a colour, so a burst drawn over the dark background still counts. */
    private static int countNear(android.graphics.Bitmap bitmap, int color, int tolerance) {
        return countNearInBand(bitmap, color, tolerance, 0, bitmap.getHeight());
    }

    /** The same count over rows [from, to) only, which keeps the white HUD text out of it. */
    private static int countNearInBand(android.graphics.Bitmap bitmap, int color, int tolerance,
            int from, int to) {
        int count = 0;
        for (int y = Math.max(0, from); y < Math.min(bitmap.getHeight(), to); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                if (Math.abs(((pixel >> 16) & 0xFF) - ((color >> 16) & 0xFF)) <= tolerance
                        && Math.abs(((pixel >> 8) & 0xFF) - ((color >> 8) & 0xFF)) <= tolerance
                        && Math.abs((pixel & 0xFF) - (color & 0xFF)) <= tolerance) count++;
            }
        }
        return count;
    }

    /** A vertical slice of a snapshot, so two lines drawn on top of each other can be told apart. */
    private static android.graphics.Bitmap band(android.graphics.Bitmap bitmap, int from, int to) {
        return android.graphics.Bitmap.createBitmap(bitmap, 0, from, bitmap.getWidth(), to - from);
    }

    /**
     * What a chart means by a note it does not let the player see the line under. Performance
     * notes are parked on lines the chart has faded to nothing: the stroke goes, the note stays,
     * and it is still meant to be hit. Phira draws the stroke with alpha.max(0) and only drops a
     * line and its notes together once the alpha goes negative, so hiding a note on an alpha-0
     * line drops a note the chart is asking for.
     */
    private void checkPerformanceNotes() throws Exception {
        final int TAP_BLUE = 0xFF6DE4FA, LINE = 0xFFEEE8CB;
        final String base = "{\"formatVersion\":3,\"offset\":0,\"judgeLineList\":["
            + "{\"bpm\":120,\"speedEvents\":[{\"startTime\":0,\"endTime\":32000,\"value\":1}],"
            + "%s"
            + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0.5,\"end\":0.5,"
            + "\"start2\":0.25,\"end2\":0.25}],"
            + "\"notesAbove\":[{\"type\":1,\"time\":0,\"positionX\":0}],\"notesBelow\":[]},"
            + "{\"bpm\":120,\"speedEvents\":[{\"startTime\":0,\"endTime\":32000,\"value\":1}],"
            + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0.5,\"end\":0.5,"
            + "\"start2\":0.75,\"end2\":0.75}],"
            + "\"notesAbove\":[{\"type\":1,\"time\":0,\"positionX\":0}],\"notesBelow\":[]}]}";
        final Chart faded = new Chart(String.format(base,
            "\"judgeLineDisappearEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0,\"end\":0}],"));
        final Chart gone = new Chart(String.format(base,
            "\"judgeLineDisappearEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":-1,\"end\":-1}],"));

        final int[] fadedNote = {0}, fadedLine = {0}, solidNote = {0}, solidLine = {0};
        main(() -> {
            PracticeView view = chartView(faded);
            view.setPlayback(0, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            android.graphics.Bitmap low = band(bitmap, 280, 330);
            android.graphics.Bitmap high = band(bitmap, 80, 130);
            fadedNote[0] = countNear(low, TAP_BLUE, 12);
            fadedLine[0] = countNear(low, LINE, 12);
            solidNote[0] = countNear(high, TAP_BLUE, 12);
            solidLine[0] = countNear(high, LINE, 12);
            low.recycle(); high.recycle(); bitmap.recycle();
        });
        check(solidLine[0] > 100 && solidNote[0] > 100, "A solid line draws both its stroke and"
            + " its note, which is what the faded one is compared against");
        check(fadedNote[0] > 100, "A note on a line faded to nothing still comes down the stage ("
            + fadedNote[0] + "px of it), the way Phira keeps a performance note playable");
        check(fadedLine[0] < 50, "That line's own stroke is gone, though (" + fadedLine[0]
            + "px of it): it is the note that stays, not the line");

        final int[] goneNote = {0}, goneLine = {0};
        main(() -> {
            PracticeView view = chartView(gone);
            view.setPlayback(0, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            android.graphics.Bitmap low = band(bitmap, 280, 330);
            goneNote[0] = countNear(low, TAP_BLUE, 12);
            goneLine[0] = countNear(low, LINE, 12);
            low.recycle(); bitmap.recycle();
        });
        check(goneNote[0] == 0 && goneLine[0] == 0, "A line whose alpha has gone negative takes its"
            + " notes with it (" + goneNote[0] + "px of note, " + goneLine[0] + "px of stroke)");
    }

    /**
     * A reversing line can give a hold a negative-length body. Its offscreen head and tail must
     * not be joined through the viewport. The separate regressions also check the real Entrance
     * sample and a valid ribbon on a shifted line, which local-head culling would wrongly hide.
     */
    private void checkFarHold() throws Exception {
        final Chart far = new Chart("{\"formatVersion\":3,\"offset\":0,\"judgeLineList\":[{"
            + "\"bpm\":120,"
            // Backwards at first, then a sprint: a hold sitting at 136s has its head 163 stages
            // away while its tail crosses this one.
            + "\"speedEvents\":[{\"startTime\":0,\"endTime\":8704,\"value\":-2},"
            + "{\"startTime\":8704,\"endTime\":9024,\"value\":60},"
            + "{\"startTime\":9024,\"endTime\":1000000000,\"value\":1}],"
            + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":1000000000,\"start\":0.5,"
            + "\"end\":0.5,\"start2\":0.5,\"end2\":0.5}],"
            + "\"notesAbove\":[{\"type\":3,\"time\":8704,\"holdTime\":320,\"positionX\":0}],"
            + "\"notesBelow\":[]}]}");
        final int[] hold = {0};
        main(() -> {
            PracticeView view = chartView(far);
            view.setPlayback(0, false, 1);
            android.graphics.Bitmap bitmap = snapshot(view);
            hold[0] = countNearInBand(bitmap, 0xFF78D5EF, 12, 60, 340);
            bitmap.recycle();
        });
        check(hold[0] == 0, "A hold whose head is still minutes away is not drawn across the stage"
            + " (" + hold[0] + "px of ribbon on the first frame)");
    }

    /**
     * Phigros noise zones: the rectangles that cover the stage and swallow the touches landing on
     * them, so the notes underneath go unplayed. The geometry, the easing and the even-odd touch
     * rule follow Phira-Pro's reconstruction of the official behaviour in prpr/src/core/block.rs.
     * The live sample is the real Desultory Signals AT chart, at the very passage Phira-Pro checked
     * against the official recording: a full-screen subtract zone with rectangles cut into it.
     */
    private void checkNoiseZones() throws Exception {
        final RectF stage = new RectF(0, 0, 1600, 900);
        final BlockArea.Transform scratch = new BlockArea.Transform();

        // The official easing enum: 13 holds the outgoing key, 14 jumps straight to the next one.
        check(BlockArea.eased(13, 0.3) == 0, "Zone easing 13 (HoldStart) holds the outgoing value");
        check(BlockArea.eased(14, 0.3) == 1, "Zone easing 14 (JumpToEnd) jumps straight to the next");
        check(Math.abs(BlockArea.eased(0, 0.3) - 0.3) < 1e-6, "Zone easing 0 is linear");
        check(Math.abs(BlockArea.eased(1, 0.5) - 0.25) < 1e-6, "Zone easing 1 is quadratic in");
        check(Math.abs(BlockArea.eased(4, 0.5) - 0.125) < 1e-6, "Zone easing 4 is cubic in");

        BlockArea centered = zone("{\"bottomLeftPercentage\":{\"x\":0.48,\"y\":0.48},"
            + "\"topRightPercentage\":{\"x\":0.52,\"y\":0.52},\"appearTime\":0,\"enableTime\":0,"
            + "\"disableTime\":100,\"disappearTime\":100}");
        centered.transform(50, stage, scratch);
        check(Math.abs(scratch.x - 800) < 0.5 && Math.abs(scratch.y - 450) < 0.5,
            "A zone centred in its file resolves to the middle of the stage ("
            + (int) scratch.x + "," + (int) scratch.y + ")");
        check(Math.abs(scratch.width - 64) < 0.5 && Math.abs(scratch.height - 36) < 0.5,
            "Its size is its own percentage of the stage, so one file reads the same on any screen ("
            + Math.round(scratch.width) + "x" + Math.round(scratch.height) + ")");
        check(centered.phase(-1) == BlockArea.HIDDEN && centered.phase(100000) == BlockArea.HIDDEN,
            "A zone is off the stage entirely outside its appear and disappear times");
        check(centered.phase(0) == BlockArea.ACTIVE && centered.phase(50) == BlockArea.ACTIVE,
            "A zone blocks through its whole enable-to-disable window");

        BlockArea preview = zone("{\"bottomLeftPercentage\":{\"x\":0.48,\"y\":0.48},"
            + "\"topRightPercentage\":{\"x\":0.52,\"y\":0.52},\"appearTime\":0,\"enableTime\":40,"
            + "\"disableTime\":60,\"disappearTime\":100}");
        check(preview.phase(20000) == BlockArea.DISABLED && preview.phase(80000) == BlockArea.DISABLED,
            "Outside its active window a zone is only a preview, and blocks nothing");
        check(!BlockArea.blocked(new BlockArea[] {preview}, 800, 450, 20000, stage, scratch),
            "A touch on a zone that is only a preview still reaches the chart");

        // A move track replaces the centre outright rather than offsetting the file's own centre.
        BlockArea moved = zone("{\"bottomLeftPercentage\":{\"x\":0.48,\"y\":0.48},"
            + "\"topRightPercentage\":{\"x\":0.52,\"y\":0.52},\"appearTime\":0,\"enableTime\":0,"
            + "\"disableTime\":100,\"disappearTime\":100,\"moveEvents\":[{\"time\":0,"
            + "\"endPosition\":{\"x\":1,\"y\":0.5}}]}");
        moved.transform(1, stage, scratch);
        check(Math.abs(scratch.x - 1600) < 0.5 && Math.abs(scratch.y - 450) < 0.5,
            "A move track puts the centre exactly where it says, not offset from the file's own");

        // Rotation happens about its own anchor, so the centre orbits that anchor, not itself.
        BlockArea turned = zone("{\"bottomLeftPercentage\":{\"x\":0.48,\"y\":0.48},"
            + "\"topRightPercentage\":{\"x\":0.52,\"y\":0.52},\"appearTime\":0,\"enableTime\":0,"
            + "\"disableTime\":100,\"disappearTime\":100,\"rotateEvents\":["
            + "{\"time\":0,\"rotation\":0,\"anchor\":{\"x\":1,\"y\":0.5}},"
            + "{\"time\":10,\"rotation\":90,\"anchor\":{\"x\":1,\"y\":0.5}}]}");
        turned.transform(10000, stage, scratch);
        check(Math.abs(scratch.x - 1600) < 1 && Math.abs(scratch.y - 1250) < 1,
            "A quarter turn about a corner anchor swings the centre a stage-height away ("
            + (int) scratch.x + "," + (int) scratch.y + ")");

        // Even-odd: either zone blocks alone, but a subtract zone cuts a hole through a normal one.
        String box = "{\"bottomLeftPercentage\":{\"x\":0.48,\"y\":0.48},"
            + "\"topRightPercentage\":{\"x\":0.52,\"y\":0.52},\"appearTime\":0,\"enableTime\":0,"
            + "\"disableTime\":100,\"disappearTime\":100";
        BlockArea normal = zone(box + "}");
        BlockArea cut = zone(box + ",\"isSubtract\":true}");
        check(BlockArea.blocked(new BlockArea[] {normal}, 800, 450, 50, stage, scratch),
            "A normal zone swallows a touch that lands inside it");
        check(BlockArea.blocked(new BlockArea[] {cut}, 800, 450, 50, stage, scratch),
            "A subtract zone on its own blocks just the same");
        check(!BlockArea.blocked(new BlockArea[] {normal, cut}, 800, 450, 50, stage, scratch),
            "A subtract zone cut out of a normal one leaves a hole, which is how a chart opens a window");
        check(BlockArea.blocked(new BlockArea[] {normal, cut, cut}, 800, 450, 50, stage, scratch),
            "Two subtract zones over each other cancel, so the cover is back");
        check(!BlockArea.blocked(new BlockArea[] {normal}, 100, 100, 50, stage, scratch),
            "A touch outside every zone still reaches the chart");
        check(!BlockArea.blocked(new BlockArea[0], 800, 450, 50, stage, scratch),
            "A chart with no zones blocks nothing at all");

        // The real chart, at the passage Phira-Pro measured against the official recording.
        LibraryAssets.ensure(getTargetContext(), null);
        SongLibrary library = new SongLibrary(getTargetContext());
        String path = null;
        for (SongLibrary.Song song : library.songs) {
            if (!song.title.toLowerCase(Locale.US).contains("desultory")) continue;
            for (SongLibrary.Selection selection : song.charts) {
                if ("AT".equals(selection.difficulty)) path = selection.path;
            }
        }
        check(path != null, "The bundled library carries Desultory Signals with its AT chart");
        if (path == null) return;
        Chart real = new Chart(SongLibrary.read(getTargetContext(), path));
        check(real.blockAreas.length == 160,
            "Its AT chart carries all 160 noise zones (" + real.blockAreas.length + " parsed)");
        // Zone times are seconds of song time; the chart clock reads the chart's offset earlier.
        check(blockedSamples(real.blockAreas, 20 * 1000 - real.offsetMs, stage) == 0,
            "Twenty seconds into the song no zone is on the stage at all");
        int covered = blockedSamples(real.blockAreas, 67.7 * 1000 - real.offsetMs, stage);
        check(covered > 0, "At 67.7s the full-screen zone is covering the stage (" + covered
            + " of 960 samples blocked)");
        List<BlockArea> subtracts = new ArrayList<>();
        for (BlockArea area : real.blockAreas) if (area.subtract) subtracts.add(area);
        int bare = blockedSamples(subtracts.toArray(new BlockArea[0]), 67.7 * 1000 - real.offsetMs, stage);
        check(bare > covered, "The rectangles cut into that zone let the stage show through: "
            + covered + " samples blocked against " + bare + " for the bare zone");
    }

    /** One zone straight from its file's JSON, with no chart offset to shift its times. */
    private static BlockArea zone(String json) throws Exception {
        return new BlockArea(new org.json.JSONObject(json));
    }

    private void checkNoisePlayback() throws Exception {
        final String normal = "{\"bottomLeftPercentage\":{\"x\":0,\"y\":0},"
            + "\"topRightPercentage\":{\"x\":1,\"y\":1},\"appearTime\":0,\"enableTime\":0,"
            + "\"disableTime\":5,\"disappearTime\":5}";
        final String subtract = normal.replace("\"appearTime\":0", "\"isSubtract\":true,\"appearTime\":0");
        for (int setting = 0; setting < 4; setting++) Settings.write(getTargetContext(), setting, false);
        Settings.backgroundMode(getTargetContext(), Settings.BACKGROUND_SOLID);
        Settings.showNoise(getTargetContext(), true);
        PracticeView covered = noiseView(normal);
        main(() -> covered.setPlayback(1000, true, 1));
        main(() -> touch(covered, MotionEvent.ACTION_DOWN, 360, 202));
        check(covered.perfectCount() == 0, "A blocked manual Tap never reaches note judgement");
        main(() -> { covered.setAutoplay(true); covered.setPlayback(1000, true, 1); });
        check(covered.perfectCount() == 1, "Autoplay bypasses noise touch interception");
        main(() -> { covered.setAutoplay(false); covered.resetAt(0); covered.setPlayback(1000, true, 1); });
        Settings.showNoise(getTargetContext(), false);
        main(() -> { covered.reloadSettings(); touch(covered, MotionEvent.ACTION_DOWN, 360, 202); });
        check(covered.perfectCount() == 1, "Turning noise off restores the ordinary manual judgement");
        Settings.showNoise(getTargetContext(), true);
        for (int type : new int[] {2, 3, 4}) {
            PracticeView target = noiseView(normal, type);
            main(() -> {
                target.setPlayback(1000, true, 1);
                touch(target, MotionEvent.ACTION_DOWN, 360, 202);
                touch(target, MotionEvent.ACTION_MOVE, 380, 202);
            });
            check(target.perfectCount() == 0, "Noise intercepts type " + type + " without granting a hit");
        }
        PracticeView held = noiseView(normal.replace("\"enableTime\":0", "\"enableTime\":1.1"), 3);
        main(() -> {
            held.setPlayback(1000, true, 1); touch(held, MotionEvent.ACTION_DOWN, 360, 202);
            held.setPlayback(1200, true, 1); held.setPlayback(1260, true, 1);
        });
        check(held.missCount() == 1, "A field activating under a held finger stops Hold continuation after its existing grace");
        int[] colors = new int[4];
        String[] masks = {subtract, subtract + "," + subtract, subtract + "," + subtract + "," + subtract,
            normal + "," + subtract};
        for (int i = 0; i < masks.length; i++) {
            PracticeView stage = noiseView(masks[i]);
            final int index = i;
            main(() -> {
                stage.setPlayback(1000, false, 1);
                android.graphics.Bitmap frame = snapshot(stage);
                colors[index] = frame.getPixel(200, 100); frame.recycle();
            });
        }
        check(android.graphics.Color.red(colors[0]) > android.graphics.Color.blue(colors[0]) + 30,
            "One subtract layer renders a red noise field");
        check(colors[1] == 0xFF0C1420 && colors[2] == 0xFF0C1420,
            "Two and three subtract layers are visually absent, independently of input parity");
        check(colors[3] == 0xFF0C1420, "A normal rectangle opens a window in a subtract field");
        PracticeView seek = noiseView(normal);
        main(() -> {
            seek.setPlayback(1000, false, 0.5f);
            android.graphics.Bitmap first = snapshot(seek); first.recycle();
            seek.resetAt(6000); seek.setPlayback(6000, false, 0.5f);
        });
        final int[] after = {0};
        main(() -> { android.graphics.Bitmap frame = snapshot(seek); after[0] = frame.getPixel(200, 100); frame.recycle(); });
        check(after[0] == 0xFF0C1420, "Seeking after disappearance clears old masks even when paused");
        for (String level : new String[] {"IN", "AT"}) {
            Chart real = new Chart(SongLibrary.read(getTargetContext(), "library/19b7f96f151d/Chart_" + level + ".json"));
            check(real.blockAreas.length == (level.equals("IN") ? 27 : 160), "Desultory " + level + " retains its authored regions");
            final PracticeView[] view = {null};
            main(() -> {
                view[0] = new PracticeView(getTargetContext()); view[0].layout(0, 0, 1280, 720);
                view[0].setChart(real); view[0].setPlayback(67700, false, 1);
                android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(1280, 720, android.graphics.Bitmap.Config.ARGB_8888);
                view[0].draw(new android.graphics.Canvas(frame));
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), "noise-" + level + ".png"))) {
                    frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
                frame.recycle();
            });
            main(() -> { view[0].resetAt(0); view[0].setPlayback(0, false, 0.5f); });
            check(real.blockAreas.length == (level.equals("IN") ? 27 : 160), "Retry leaves shared source geometry intact for " + level);
        }
    }

    private PracticeView noiseView(String areas) throws Exception {
        return noiseView(areas, 1);
    }

    private PracticeView noiseView(String areas, int type) throws Exception {
        String json = "{\"formatVersion\":3,\"offset\":0,\"blockAreaList\":[" + areas + "],\"judgeLineList\":["
            + "{\"bpm\":120,\"judgeLineDisappearEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0,\"end\":0}],"
            + "\"notesAbove\":[{\"type\":" + type + ",\"time\":64,\"holdTime\":128,\"positionX\":0}],\"notesBelow\":[]}]}";
        Chart chart = new Chart(json);
        final PracticeView[] view = {null};
        main(() -> { view[0] = new PracticeView(getTargetContext()); view[0].layout(0, 0, 720, 405); view[0].setChart(chart); });
        return view[0];
    }

    /** How many points of a 40x24 grid over the stage these zones are swallowing at this moment. */
    private static int blockedSamples(BlockArea[] areas, double timeMs, RectF stage) {
        BlockArea.Transform scratch = new BlockArea.Transform();
        int hit = 0;
        for (int i = 0; i < 40; i++) {
            for (int j = 0; j < 24; j++) {
                if (BlockArea.blocked(areas, (i + 0.5f) / 40 * stage.width(),
                    (j + 0.5f) / 24 * stage.height(), timeMs, stage, scratch)) hit++;
            }
        }
        return hit;
    }

    private void checkChartsAndTouches() throws Exception {
        SongLibrary library = new SongLibrary(getTargetContext());
        int chartCount = 0;
        for (SongLibrary.Song song : library.songs) {
            if (song.charts.isEmpty()) throw new AssertionError("Song has no playable chart: " + song.id);
            for (SongLibrary.Selection selection : song.charts) {
                if (song.charts.size() > 1
                        && (selection.difficulty.startsWith("EZ") || selection.difficulty.startsWith("HD")))
                    throw new AssertionError("Easy chart should have been dropped: " + selection.path);
                Chart chart = new Chart(SongLibrary.read(getTargetContext(), selection.path));
                if (chart.notes.length != selection.notes) throw new AssertionError("Note count mismatch: " + selection.path);
                if (com.phislow.app.LibraryAssets.file(getTargetContext(), selection.audio).length() <= 0)
                    throw new AssertionError("Missing audio: " + selection.audio);
                chartCount++;
            }
        }
        check(chartCount >= library.songs.size(),
            "Every song keeps a playable chart; all " + chartCount + " bundled charts parse and resolve to readable audio ("
                + library.songs.size() + " songs)");
        int coverCount = 0;
        for (SongLibrary.Song song : library.songs) {
            if (song.cover == null) continue;
            for (String path : new String[] {song.cover, song.coverThumb, song.coverBlur}) {
                if (com.phislow.app.LibraryAssets.file(getTargetContext(), path).length() <= 0)
                    throw new AssertionError("Empty cover asset: " + path);
            }
            coverCount++;
        }
        check(coverCount > 300, "Cover art resolves for " + coverCount + " songs");
        String sample = "{\"formatVersion\":3,\"offset\":0,\"judgeLineList\":[{\"bpm\":120,\"speedEvents\":[{\"startTime\":0,\"endTime\":32000,\"value\":1}],\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0.5,\"end\":0.5,\"start2\":0.5,\"end2\":0.5}],\"notesAbove\":[{\"type\":1,\"time\":32,\"positionX\":0},{\"type\":2,\"time\":64,\"positionX\":0},{\"type\":3,\"time\":96,\"holdTime\":64,\"positionX\":0},{\"type\":4,\"time\":192,\"positionX\":0}],\"notesBelow\":[]}]}";
        Chart chart = new Chart(sample);
        check(chart.notes[0].time == 500 && chart.notes[2].end == 2500, "BPM/32 timing and hold duration conversion");
        check(Math.abs(chart.lines[0].floor(2000) - 2) < 0.001, "Scroll distance integrates line speed in chart seconds");
        final PracticeView[] view = {null};
        main(() -> { view[0] = new PracticeView(getTargetContext()); view[0].layout(0, 0, 720, 405); view[0].setChart(chart); });
        main(() -> {
            view[0].setPlayback(500, true, 1); view[0].setEnabled(false);
            touch(view[0], MotionEvent.ACTION_DOWN, 360, 202); touch(view[0], MotionEvent.ACTION_UP, 360, 202);
            view[0].setEnabled(true);
        });
        check(countPerfect(view[0]) == 0, "Disabled practice input cannot judge while the player is seeking or dragging");
        main(() -> { view[0].setPlayback(500, true, 1); touch(view[0], MotionEvent.ACTION_DOWN, 360, 202); touch(view[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(view[0]) == 1, "Tap touch receives Perfect");
        main(() -> { view[0].setPlayback(1000, true, 1); touch(view[0], MotionEvent.ACTION_DOWN, 360, 202); touch(view[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(view[0]) == 2, "Drag touch receives Perfect");
        main(() -> { view[0].setPlayback(1500, true, 1); touch(view[0], MotionEvent.ACTION_DOWN, 360, 202); view[0].setPlayback(2000, true, 1); view[0].setPlayback(2500, true, 1); touch(view[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(view[0]) == 3, "Sustained hold completes with Perfect");
        // A hold let go inside its last stretch is forgiven: Phira stops asking whether the note is
        // still held once only the last bad window of it is left to see, so a release there still
        // finishes the hold on the grade its head earned. Let go further out than that, the finger
        // has only Phira's 50ms to come back before the hold is missed. Each gets its own view so
        // the counts above stay untouched. The separate practice regressions advance every frame
        // outside the tail window so a skipped frame cannot hide an expired release grace.
        final PracticeView[] tail = {null};
        main(() -> {
            tail[0] = new PracticeView(getTargetContext());
            tail[0].layout(0, 0, 720, 405);
            tail[0].setChart(chart);
            // Seek first, the way the workbench does, so the notes before this point are skipped
            // rather than judged: a view asked to start at 1500 has not played through them.
            tail[0].resetAt(1500);
            tail[0].setPlayback(1500, true, 1);
            touch(tail[0], MotionEvent.ACTION_DOWN, 360, 202);
            tail[0].setPlayback(2320, true, 1);
            touch(tail[0], MotionEvent.ACTION_UP, 360, 202);
        });
        SystemClock.sleep(200);
        main(() -> { tail[0].setPlayback(2400, true, 1); tail[0].setPlayback(2500, true, 1); });
        check(countPerfect(tail[0]) == 1,
            "A hold let go inside its last stretch completes Perfect instead of breaking");
        final PracticeView[] dropped = {null};
        main(() -> {
            dropped[0] = new PracticeView(getTargetContext());
            dropped[0].layout(0, 0, 720, 405);
            dropped[0].setChart(chart);
            dropped[0].resetAt(1500);
            dropped[0].setPlayback(1500, true, 1);
            touch(dropped[0], MotionEvent.ACTION_DOWN, 360, 202);
            dropped[0].setPlayback(2000, true, 1);
            touch(dropped[0], MotionEvent.ACTION_UP, 360, 202);
        });
        SystemClock.sleep(150);
        main(() -> dropped[0].setPlayback(2200, true, 1));
        final int[] droppedMiss = {0};
        main(() -> droppedMiss[0] = dropped[0].missCount());
        check(droppedMiss[0] == 1,
            "A hold let go long before its end is missed once the 50ms re-press has run out (miss="
                + droppedMiss[0] + ", perfect=" + dropped[0].perfectCount() + ")");
        main(() -> dropped[0].setPlayback(2500, true, 1));
        check(countPerfect(dropped[0]) == 0,
            "A missed hold does not come back to complete on its own grade when its tail arrives");
        // A hold is held for as long as the finger is down, and it has to read as held all that
        // time. It used to be drawn half-transparent then, which against the dark playfield goes
        // grey - a hold being held looked like one that had been missed. It keeps its own colour
        // now, and keeps answering, so there is something to see for the whole of the hold.
        // Its own view, so the judgement counts above are left alone.
        final PracticeView[] heldView = {null};
        final int[] held = {0, 0};
        main(() -> {
            heldView[0] = new PracticeView(getTargetContext());
            heldView[0].layout(0, 0, 720, 405);
            heldView[0].setChart(chart);
            heldView[0].setPlayback(1500, true, 1);
            touch(heldView[0], MotionEvent.ACTION_DOWN, 360, 202);
        });
        SystemClock.sleep(300);
        main(() -> {
            heldView[0].setPlayback(1800, true, 1);   // still held, and a fresh answer is due
            android.graphics.Bitmap bitmap = snapshot(heldView[0]);
            held[0] = countNear(bitmap, 0xFF78D5EF, 12);
            held[1] = countNear(bitmap, 0xFFFFD34D, 12);
            bitmap.recycle();
        });
        check(held[0] > 1000, "A hold being held keeps its own colour at full strength instead of "
            + "going grey (" + held[0] + "px of it)");
        check(held[1] > 100, "A held hold keeps answering while it is held, not only when it ends ("
            + held[1] + "px of the burst)");
        main(() -> { view[0].setPlayback(3000, true, 1); touch(view[0], MotionEvent.ACTION_DOWN, 360, 202); touch(view[0], MotionEvent.ACTION_MOVE, 380, 202); touch(view[0], MotionEvent.ACTION_UP, 380, 202); });
        check(countPerfect(view[0]) == 4, "Flick requires motion and receives Perfect");
        main(() -> {
            view[0].resetAt(0); view[0].setPlayback(3000, true, 1);
            touch(view[0], MotionEvent.ACTION_DOWN, 120, 380);
            touch(view[0], MotionEvent.ACTION_MOVE, 620, 60);
            touch(view[0], MotionEvent.ACTION_UP, 620, 60);
        });
        check(countPerfect(view[0]) == 0, "A flick outside its judgement column does not take the note");
        main(() -> {
            view[0].resetAt(0); view[0].setPlayback(3000, true, 1);
            touch(view[0], MotionEvent.ACTION_DOWN, 420, 380);
            touch(view[0], MotionEvent.ACTION_MOVE, 440, 60);
            touch(view[0], MotionEvent.ACTION_UP, 440, 60);
        });
        check(countPerfect(view[0]) == 1,
            "A flick answers any swipe direction inside its column, beyond the drawn key and far above the line");
        main(() -> { view[0].resetAt(0); view[0].setAutoplay(true); view[0].setPlayback(3500, true, 1); });
        check(countPerfect(view[0]) == 4, "Autoplay judges all four types without touches");
        main(() -> { view[0].setAutoplay(false); view[0].resetAt(0); view[0].setPlayback(3500, true, 1); });
        final int[] missed = {0}; main(() -> missed[0] = view[0].missCount());
        check(missed[0] == 4, "Manual mode cannot silently autoplay missed notes");
        main(() -> view[0].resetAt(0));
        check(countPerfect(view[0]) == 0, "Retry clears previous judgment state");
        final PracticeView[] other = {null};
        main(() -> { other[0] = new PracticeView(getTargetContext()); other[0].setChart(chart); other[0].setAutoplay(true); other[0].setPlayback(3500, true, 1); });
        check(countPerfect(view[0]) == 0 && countPerfect(other[0]) == 4, "Two sessions do not share mutable judgment state");
        // The settings screen's judgement windows, and Phira's late grace folded into them. The
        // chart's one tap sits on 500ms, so every offset below is measured late against that note,
        // and each mode gets its own view so the counts above stay untouched. Every mode is pinned
        // twice: once inside its grace plus its perfect window, once just outside it. That way the
        // grace is proved to be there and proved not to swallow everything - one "is it Perfect"
        // check cannot tell a working grace from a window that simply happens to be wide.
        final PracticeView[] judged = {null};
        main(() -> {
            Settings.judgeMode(getTargetContext(), Settings.JUDGE_STRICT);
            judged[0] = new PracticeView(getTargetContext());
            judged[0].layout(0, 0, 720, 405);
            judged[0].setChart(chart);
        });
        // 严判 pairs 40ms of perfect window with half of the 70ms grace, forgiving up to 75ms late.
        // At 60ms the grace covers what is left of the error and the tap is still Perfect; with no
        // grace at all those 60ms would have failed a 40ms window outright.
        main(() -> { judged[0].setPlayback(560, true, 1); touch(judged[0], MotionEvent.ACTION_DOWN, 360, 202); touch(judged[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(judged[0]) == 1, "严判 forgives the 60ms-late tap down to a 25ms error, still Perfect");
        main(() -> { judged[0].resetAt(0); judged[0].setPlayback(580, true, 1); touch(judged[0], MotionEvent.ACTION_DOWN, 360, 202); touch(judged[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(judged[0]) == 0, "严判 stops forgiving at 80ms late, past its 35ms grace and its 40ms window");
        main(() -> {
            Settings.judgeMode(getTargetContext(), Settings.JUDGE_NORMAL);
            judged[0].reloadSettings();
        });
        // 常规 pairs 80ms with the whole 70ms grace, forgiving up to 150ms late.
        main(() -> { judged[0].resetAt(0); judged[0].setPlayback(560, true, 1); touch(judged[0], MotionEvent.ACTION_DOWN, 360, 202); touch(judged[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(judged[0]) == 1, "常规 forgives all 70ms of the same 60ms-late tap, landing it dead on time");
        main(() -> { judged[0].resetAt(0); judged[0].setPlayback(660, true, 1); touch(judged[0], MotionEvent.ACTION_DOWN, 360, 202); touch(judged[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(judged[0]) == 0, "常规 still refuses a 160ms-late tap, past 70ms of grace and 80ms of window");
        // The windows are fixed in real time and carry the chart's own pace, so at 0.5× the 常规
        // pair is 35ms of grace and 40ms of window in chart time: 70 chart-ms late is 140ms of
        // real lateness, and 80 chart-ms is 160.
        main(() -> { judged[0].resetAt(0); judged[0].setPlayback(570, true, 0.5f); touch(judged[0], MotionEvent.ACTION_DOWN, 360, 202); touch(judged[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(judged[0]) == 1, "常规 at 0.5× forgives 70 chart-ms of lateness, 140ms of real time");
        main(() -> { judged[0].resetAt(0); judged[0].setPlayback(580, true, 0.5f); touch(judged[0], MotionEvent.ACTION_DOWN, 360, 202); touch(judged[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(judged[0]) == 0, "常规 at 0.5× stops forgiving at 80 chart-ms, 160ms of real time");
        main(() -> {
            Settings.judgeMode(getTargetContext(), Settings.JUDGE_WIDE);
            judged[0].reloadSettings();
        });
        // 宽判 at 0.5× widens the pair to 52.5ms of grace and 60ms of window in chart time, which is
        // wider than its own Bad bound: every tap that is not a Miss is a Perfect one.
        main(() -> { judged[0].resetAt(0); judged[0].setPlayback(600, true, 0.5f); touch(judged[0], MotionEvent.ACTION_DOWN, 360, 202); touch(judged[0], MotionEvent.ACTION_UP, 360, 202); });
        check(countPerfect(judged[0]) == 1, "宽判 at 0.5× takes a 100 chart-ms late tap that 常规 refused");
        main(() -> {
            Settings.judgeMode(getTargetContext(), Settings.JUDGE_NORMAL);
            judged[0].reloadSettings();
        });
        // A missed note stays on the stage: the same art, faded to 45%, never a grey variant and
        // never a greyscale conversion. The tap is struck 250 chart-ms after its beat - judged
        // Miss, still scrolling out - so its colour blends with the playfield at a known value,
        // while the live hold behind it must keep drawing at full strength beside it.
        main(() -> { view[0].resetAt(0); view[0].setPlayback(750, true, 1); });
        final int[] fadedTap = {0}, fullTap = {0}, fullHold = {0};
        main(() -> {
            android.graphics.Bitmap bitmap = snapshot(view[0]);
            fadedTap[0] = countNear(bitmap, 0xFF387282, 12);
            fullTap[0] = countNear(bitmap, 0xFF6DE4FA, 12);
            fullHold[0] = countNear(bitmap, 0xFF78D5EF, 12);
            bitmap.recycle();
        });
        check(fadedTap[0] > 50, "A missed tap stays on the stage, its own colour faded to 45% ("
            + fadedTap[0] + "px of it)");
        check(fullTap[0] == 0, "The missed tap keeps no full-strength pixels: faded, not still live");
        check(fullHold[0] > 1000, "The live hold behind it keeps drawing at full strength");
        // Skipped is not missed: notes left behind by a seek are the seek's artefact, not notes
        // the player failed to hit, and they leave the stage as they always did.
        main(() -> view[0].resetAt(750));
        final int[] skippedTap = {0}, skippedAnywhere = {0};
        main(() -> {
            android.graphics.Bitmap bitmap = snapshot(view[0]);
            // The skipped tap met its judge line, so that is where a lingering one would be. The
            // live hold coming down the same line sits far above it, and its own antialiased edge
            // blends to a colour very like a faded tap's - counted stage-wide it would speak for
            // the tap whether or not the tap is there.
            skippedAnywhere[0] = countNear(bitmap, 0xFF387282, 12);
            skippedTap[0] = countNearInBand(bitmap, 0xFF387282, 12, 185, 225);
            bitmap.recycle();
        });
        check(skippedTap[0] == 0, "A note skipped by adjusting the progress bar does not linger on"
            + " the stage (" + skippedTap[0] + "px at its judge line, " + skippedAnywhere[0]
            + "px anywhere on the stage)");
        // The top-left line leads with the playback speed, and the speed is playback state rather
        // than a tally: with the judge statistics switched off it is still there to be read.
        main(() -> {
            Settings.showJudge(getTargetContext(), false);
            judged[0].reloadSettings();
            judged[0].resetAt(0);
            judged[0].setPlayback(0, false, 0.5f);
        });
        final int[] cornerInk = {0};
        main(() -> {
            android.graphics.Bitmap bitmap = snapshot(judged[0]);
            int ink = 0;
            for (int y = 0; y < 100 && y < bitmap.getHeight(); y++) {
                for (int x = 0; x < 200 && x < bitmap.getWidth(); x++) {
                    int pixel = bitmap.getPixel(x, y);
                    if (Math.abs(((pixel >> 16) & 0xFF) - 0x0C) > 40
                            || Math.abs(((pixel >> 8) & 0xFF) - 0x14) > 40
                            || Math.abs((pixel & 0xFF) - 0x20) > 40) ink++;
                }
            }
            cornerInk[0] = ink;
            bitmap.recycle();
        });
        check(cornerInk[0] > 20, "The top-left corner still names the speed (0.5×) with the "
            + "tallies switched off (" + cornerInk[0] + "px of it)");
        // Tap keeps the wider Phira reach even when the player makes its artwork smaller.
        // The vertical stays open, and a tap far above the line still counts.
        final PracticeView[] range = {null};
        main(() -> {
            Settings.noteScalePct(getTargetContext(), 100);
            range[0] = new PracticeView(getTargetContext());
            range[0].layout(0, 0, 720, 405);
            range[0].setChart(chart);
            range[0].setPlayback(500, true, 1);
        });
        main(() -> { touch(range[0], MotionEvent.ACTION_DOWN, 400, 202); touch(range[0], MotionEvent.ACTION_UP, 400, 202); });
        check(countPerfect(range[0]) == 1, "At 100% art width a tap 40px off centre receives edge tolerance");
        main(() -> { range[0].resetAt(0); range[0].setPlayback(500, true, 1); touch(range[0], MotionEvent.ACTION_DOWN, 385, 202); touch(range[0], MotionEvent.ACTION_UP, 385, 202); });
        check(countPerfect(range[0]) == 1, "At 100% width a tap 25px off centre, inside the key, counts");
        main(() -> { range[0].resetAt(0); range[0].setPlayback(500, true, 1); touch(range[0], MotionEvent.ACTION_DOWN, 360, 60); touch(range[0], MotionEvent.ACTION_UP, 360, 60); });
        // The seek resets the tallies, as it does on the workbench, so this strike counts from
        // zero rather than adding to the one before it.
        check(countPerfect(range[0]) == 1, "A tap far above the line, in the key's column, still counts");
        main(() -> {
            Settings.noteScalePct(getTargetContext(), Settings.NOTE_SCALE_DEFAULT);
            range[0].reloadSettings();
            range[0].resetAt(0);
            range[0].setPlayback(500, true, 1);
            touch(range[0], MotionEvent.ACTION_DOWN, 400, 202);
            touch(range[0], MotionEvent.ACTION_UP, 400, 202);
        });
        check(countPerfect(range[0]) == 1, "At default art width the same tap keeps its edge tolerance");
        // The stage backdrop: the settings pick the song's cover, dimmed over the plain colour, or
        // the plain colour alone. A solid red cover blends with the playfield at a known value -
        // 20% of red over the dark base - so the pixel count proves the picture is really there.
        final PracticeView[] backed = {null};
        main(() -> {
            Settings.backgroundMode(getTargetContext(), Settings.BACKGROUND_COVER);
            backed[0] = new PracticeView(getTargetContext());
            backed[0].layout(0, 0, 720, 405);
            backed[0].setChart(chart);
            android.graphics.Bitmap red = android.graphics.Bitmap.createBitmap(64, 64,
                android.graphics.Bitmap.Config.ARGB_8888);
            red.eraseColor(0xFFFF0000);
            backed[0].setCover(red);
            backed[0].resetAt(0);
            backed[0].setPlayback(0, false, 1);
        });
        final int[] covered = {0}, plain = {0};
        main(() -> {
            android.graphics.Bitmap bitmap = snapshot(backed[0]);
            covered[0] = countNear(bitmap, 0xFF3D101A, 12);
            bitmap.recycle();
        });
        check(covered[0] > 200000, "The cover backdrop fills the stage, dimmed to its known blend ("
            + covered[0] + "px of it)");
        main(() -> {
            Settings.backgroundMode(getTargetContext(), Settings.BACKGROUND_SOLID);
            backed[0].reloadSettings();
        });
        main(() -> {
            android.graphics.Bitmap bitmap = snapshot(backed[0]);
            plain[0] = countNear(bitmap, 0xFF3D101A, 12);
            bitmap.recycle();
        });
        check(plain[0] == 0, "The solid-colour backdrop leaves no trace of the cover blend");
        main(() -> Settings.backgroundMode(getTargetContext(), Settings.BACKGROUND_COVER));
        main(() -> {
            Settings.showJudge(getTargetContext(), true);
            judged[0].reloadSettings();
        });
        // Audio latency shifts the chart's clock against the audio's: positive means the sound
        // arrives late, so notes cross the judge line that much later on the position clock, and
        // Autoplay judges them only when the shifted clock reaches them.
        main(() -> Settings.audioLatencyMs(getTargetContext(), 300));
        final PracticeView[] lagged = {null};
        main(() -> {
            lagged[0] = new PracticeView(getTargetContext());
            lagged[0].layout(0, 0, 720, 405);
            lagged[0].setChart(chart);
            lagged[0].setAutoplay(true);
            lagged[0].setPlayback(800, true, 1);
        });
        check(countPerfect(lagged[0]) == 1, "With +300ms latency the tap at 500 is judged only "
            + "when the position reaches 800");
        main(() -> {
            Settings.audioLatencyMs(getTargetContext(), 0);
            lagged[0].reloadSettings();
            lagged[0].resetAt(0);
            lagged[0].setAutoplay(true);
            lagged[0].setPlayback(3500, true, 1);
        });
        check(countPerfect(lagged[0]) == 4, "With the latency back at zero the same pass judges "
            + "everything up to 3500");
        main(() -> { view[0].resetAt(2000); view[0].setAutoplay(true); view[0].setPlayback(3500, true, 1); });
        final int[] skipMiss = {0}; main(() -> skipMiss[0] = view[0].missCount());
        check(countPerfect(view[0]) == 2 && skipMiss[0] == 0, "Forward practice start skips previous notes without counting misses");
        // An early-pressed hold plants its head on the judge line straight away: the finger put
        // it there, so it must not keep floating down toward a line it has already met.
        final PracticeView[] early = {null};
        main(() -> {
            early[0] = new PracticeView(getTargetContext());
            early[0].layout(0, 0, 720, 405); early[0].setChart(chart); early[0].setPlayback(1400, true, 1);
            touch(early[0], MotionEvent.ACTION_DOWN, 360, 202);
        });
        final int[] headOnLine = {0};
        main(() -> {
            android.graphics.Bitmap bitmap = snapshot(early[0]);
            int count = 0;
            for (int y = 195; y <= 210 && y < bitmap.getHeight(); y++) {
                for (int x = 300; x <= 420 && x < bitmap.getWidth(); x++) {
                    int pixel = bitmap.getPixel(x, y);
                    if (Math.abs(((pixel >> 16) & 0xFF) - 0x78) <= 12
                            && Math.abs(((pixel >> 8) & 0xFF) - 0xD5) <= 12
                            && Math.abs((pixel & 0xFF) - 0xEF) <= 12) count++;
                }
            }
            headOnLine[0] = count;
            bitmap.recycle();
        });
        check(headOnLine[0] > 100, "An early-pressed hold draws its head on the judge line ("
            + headOnLine[0] + "px of it there)");
    }
    private int countPerfect(PracticeView view) { final int[] result = {0}; main(() -> result[0] = view.perfectCount()); return result[0]; }
    private void touch(PracticeView view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        view.onTouchEvent(event); event.recycle();
    }
}
