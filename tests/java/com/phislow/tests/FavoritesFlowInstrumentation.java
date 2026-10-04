package com.phislow.tests;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import com.phislow.app.Favorites;
import com.phislow.app.Favorites.SavedRange;
import com.phislow.app.MainActivity;
import com.phislow.app.SongLibrary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exercises loading and seeking bookmarked ranges through the real pause interface. */
public final class FavoritesFlowInstrumentation extends Instrumentation {
    private int passed, failed;
    private Activity activity;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.callActivityOnCreate(activity, state);
    }

    @Override public void onStart() {
        Context context = getTargetContext();
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
            check(false, "Screen pinning blocks UI verification; favorite data was not touched");
            report();
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences("phislow_favorites", Context.MODE_PRIVATE);
        Map<String, ?> saved = new HashMap<>(prefs.getAll());
        Map<String, ?> settings = new HashMap<>(context.getSharedPreferences("phislow", Context.MODE_PRIVATE).getAll());
        try {
            if (!prefs.edit().clear().commit()) throw new IllegalStateException("Could not isolate favorites");
            SongLibrary.Song song = null;
            for (SongLibrary.Song candidate : new SongLibrary(context).songs) {
                if (candidate.charts.size() >= 2) { song = candidate; break; }
            }
            if (song == null) throw new IllegalStateException("Need one song with two charts");
            SongLibrary.Selection chart = song.charts.get(0), otherChart = song.charts.get(1);
            Intent request = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_SONG_ID, song.id).putExtra(MainActivity.EXTRA_TITLE, song.title)
                .putExtra(MainActivity.EXTRA_LEVEL, chart.difficulty).putExtra(MainActivity.EXTRA_CHART, chart.path)
                .putExtra(MainActivity.EXTRA_AUDIO_ASSET, "practice-demo.wav")
                .putExtra(MainActivity.EXTRA_RANGE_START, 4000).putExtra(MainActivity.EXTRA_RANGE_END, 6000);
            ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
            try {
                context.startActivity(request);
                activity = waitForMonitorWithTimeout(monitor, 15000);
            } finally { removeMonitor(monitor); }
            if (activity == null) throw new IllegalStateException("Practice activity did not launch within 15 seconds");
            check(awaitRange(4000, 6000, 15000), "Asynchronous chart/audio load opens paused at 4 seconds with range 4 to 6");
            if (!pausedAt(4000, 6000)) throw new IllegalStateException("Initial bookmarked seek did not settle");
            SystemClock.sleep(400);
            check(pausedAt(4000, 6000), "Loaded range stays paused rather than auto-starting");
            check(clickDescription("收藏当前练习区间"), "Current range favorite button is visible and enabled");
            Favorites favorites = new Favorites(context);
            SavedRange first = new SavedRange(song.id, chart.path, 4000, 6000);
            check(new Favorites(context).listRanges().contains(first), "Pause button saves the exact song, chart and boundaries");
            check(clickDescription("收藏当前练习区间") && favorites.listRanges().size() == 1,
                "Repeated pause-button save keeps one copy");
            check(favorites.saveRange(song.id, chart.path, 8000, 10000)
                && favorites.saveRange(song.id, otherChart.path, 12000, 14000), "Other interval and difficulty are saved independently");
            check(clickDescription("打开当前谱面段落收藏"), "Current-chart favorite list opens from the pause menu");
            check(awaitNode("00:08.000 → 00:10.000", 5000), "The saved 8 to 10 range appears in the actual dialog");
            check(findNode("00:12.000 → 00:14.000") == null, "Current chart list excludes the other difficulty's range");
            check(clickNode("00:08.000 → 00:10.000"), "Clicking the saved range selects it through the dialog");
            check(awaitRange(8000, 10000, 10000), "Favorite click updates A/B and seeks to 8 seconds while paused");
            SystemClock.sleep(400);
            check(pausedAt(8000, 10000), "Restored range remains paused after the seek completes");
            List<SavedRange> after = new Favorites(context).listRanges();
            check(after.size() == 3 && after.contains(first), "Jumping preserves existing favorite entries");
        } catch (Throwable error) {
            check(false, error.toString());
        } finally {
            if (activity != null) runOnMainSync(() -> activity.finish());
            check(restore(prefs, saved), "Original favorites restored after UI checks");
            check(prefs.getAll().equals(saved), "Restored favorites match their original snapshot");
            check(context.getSharedPreferences("phislow", Context.MODE_PRIVATE).getAll().equals(settings),
                "UI checks leave practice settings unchanged");
        }
        report();
    }

    private boolean awaitRange(int start, int end, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            if (pausedAt(start, end)) return true;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private boolean pausedAt(int start, int end) {
        final boolean[] ready = {false};
        runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            View pause = description(decor, "暂停界面");
            ready[0] = pause != null && pause.isShown() && hasText(decor, "已暂停")
                && hasText(pause, time(start) + " → " + time(end) + "  (" + time(end - start) + ")")
                && hasClock(pause, start);
        });
        return ready[0];
    }

    private boolean clickDescription(String caption) {
        final boolean[] clicked = {false};
        runOnMainSync(() -> {
            View target = description(activity.getWindow().getDecorView(), caption);
            clicked[0] = target != null && target.isShown() && target.isEnabled() && target.performClick();
        });
        return clicked[0];
    }

    private static View description(View view, String caption) {
        if (caption.contentEquals(String.valueOf(view.getContentDescription()))) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = description(group.getChildAt(i), caption);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasText(View view, String caption) {
        if (!view.isShown()) return false;
        if (view instanceof TextView && caption.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (hasText(group.getChildAt(i), caption)) return true;
        }
        return false;
    }

    private static boolean hasClock(View view, int position) {
        if (!view.isShown()) return false;
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            if (text.matches("\\d\\d:\\d\\d\\.\\d\\d\\d / 00:16\\.000")) {
                int actual = Integer.parseInt(text.substring(0, 2)) * 60000
                    + Integer.parseInt(text.substring(3, 5)) * 1000 + Integer.parseInt(text.substring(6, 9));
                return Math.abs(actual - position) <= 150;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (hasClock(group.getChildAt(i), position)) return true;
        }
        return false;
    }

    private boolean awaitNode(String text, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            if (findNode(text) != null) return true;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private AccessibilityNodeInfo findNode(String text) {
        AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        for (AccessibilityNodeInfo node : found)
            if (node.isVisibleToUser() && text.contentEquals(String.valueOf(node.getText()))) return node;
        return null;
    }

    private boolean clickNode(String text) {
        AccessibilityNodeInfo node = findNode(text);
        while (node != null) {
            if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            node = node.getParent();
        }
        return false;
    }

    private static String time(int ms) {
        return String.format(java.util.Locale.US, "%02d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000);
    }

    private void check(boolean condition, String message) {
        if (condition) passed++; else failed++;
        Bundle status = new Bundle();
        status.putString("stream", (condition ? "PASS " : "FAIL ") + message + "\n");
        sendStatus(condition ? 0 : -1, status);
    }

    private void report() {
        Bundle result = new Bundle();
        result.putString("stream", "\n" + (failed == 0 ? "PASS" : "FAIL") + " favorite UI flow: "
            + passed + " passed, " + failed + " failed\n");
        result.putInt("passed", passed); result.putInt("failed", failed);
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    @SuppressWarnings("unchecked") private static boolean restore(SharedPreferences prefs, Map<String, ?> saved) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
        }
        return editor.commit();
    }
}
