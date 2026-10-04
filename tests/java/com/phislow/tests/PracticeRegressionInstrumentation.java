package com.phislow.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.ViewTreeObserver;
import com.phislow.app.AudioEngine;
import com.phislow.app.Chart;
import com.phislow.app.PracticeView;
import com.phislow.app.NoteSkin;
import com.phislow.app.Settings;
import com.phislow.app.SettingsActivity;
import com.phislow.app.SongLibrary;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Focused device probes; every check reports, including baseline failures. No test libraries. */
public final class PracticeRegressionInstrumentation extends Instrumentation {
    private static final int PERFECT = 0xFFFFD34D, GOOD = 0xFF4FA8FF;
    private static final int TAP_FADED = 0xFF387282, HOLD_FADED = 0xFF3D6B7D;
    private String group = "all", current;
    private int passed, failed;
    private long downTime;
    private interface Probe { void run() throws Exception; }

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) group = arguments.getString("group", "all");
        start();
    }
    @Override public void onStart() {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("phislow", Context.MODE_PRIVATE);
        Map<String, ?> saved = new HashMap<>(prefs.getAll());
        Bundle result = new Bundle();
        try {
            if (group.equals("all") || group.equals("render")) com.phislow.app.LibraryAssets.ensure(getTargetContext(), null);
            for (int setting = 0; setting < 4; setting++) Settings.write(getTargetContext(), setting, false);
            Settings.judgeMode(getTargetContext(), Settings.JUDGE_NORMAL);
            Settings.noteScalePct(getTargetContext(), 150);
            Settings.backgroundMode(getTargetContext(), Settings.BACKGROUND_SOLID);
            Settings.audioLatencyMs(getTargetContext(), 0);
            Settings.keepScrollSpeed(getTargetContext(), false);
            if (group.equals("all") || group.equals("render")) rendering();
            if (group.equals("all") || group.equals("hold")) holds();
            if (group.equals("all") || group.equals("frames")) frames();
            if (group.equals("all") || group.equals("space")) spatialReach();
            if (group.equals("all") || group.equals("scroll")) scrollSpeed();
            if (!group.equals("all") && !group.equals("render") && !group.equals("hold") && !group.equals("frames") && !group.equals("space") && !group.equals("scroll"))
                probe("arguments", () -> check(false, "unknown group " + group));
        } catch (Throwable error) {
            current = "runner"; check(false, error.toString());
        } finally {
            restore(prefs, saved);
        }
        result.putString("stream", "\n" + (failed == 0 ? "PASS" : "FAIL") + " group=" + group
            + ": " + passed + " passed, " + failed + " failed\n");
        result.putInt("passed", passed); result.putInt("failed", failed);
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private void rendering() {
        for (float speed : new float[] {0.1f, 0.5f, 1, 2}) {
            probe("Miss tap lifetime " + speed + "x", () -> {
                PracticeView view = view(1, 0, 0, true);
                play(view, 1000, speed);
                play(view, 1000 + Math.round(250 * speed), speed);
                check(pixels(view, TAP_FADED) > 100, "Miss remains briefly at 45% strength");
                play(view, 1000 + Math.round(421 * speed) + 1, speed);
                check(pixels(view, TAP_FADED) == 0, "frozen/zero-scroll Miss clears after 420 real ms");
                play(view, 5000, speed);
                check(pixels(view, TAP_FADED) == 0, "Miss stays absent several seconds later");
            });
        }
        probe("negative-speed performance note", () -> {
            PracticeView view = view(1, 0, -0.1, false);
            play(view, 1500, 1);
            check(pixels(view, TAP_FADED) == 0, "negative-speed Miss has a finite screen lifetime");
        });
        probe("frozen-scroll performance note", () -> {
            PracticeView view = view(1, 0, 1, true);
            play(view, 1500, 1);
            check(pixels(view, TAP_FADED) == 0, "frozen-line Miss cannot obstruct the stage forever");
        });
        probe("speed changes cannot revive a Miss", () -> {
            PracticeView view = view(1, 0, 0, true);
            play(view, 1125, 0.5f);
            check(pixels(view, TAP_FADED) > 100, "Miss is visible during its original fade");
            play(view, 1220, 0.5f);
            check(pixels(view, TAP_FADED) == 0, "original fade expires");
            main(() -> view.setPlayback(1220, false, 2));
            check(pixels(view, TAP_FADED) == 0, "paused speed change cannot bring the old note back");
            play(view, 1220, 2);
            check(pixels(view, TAP_FADED) == 0, "resume at faster speed keeps expired Miss hidden");
        });
        probe("Miss Hold ends instead of reversing", () -> {
            PracticeView view = view(3, 600, 1, false);
            play(view, 1000, 1); single(view, MotionEvent.ACTION_DOWN, 360);
            play(view, 1050, 1); single(view, MotionEvent.ACTION_UP, 360);
            advance(view, 1050, 1120, 1);
            check(misses(view) == 1 && pixels(view, HOLD_FADED) > 100,
                "broken Hold keeps its own art at 45% before its end");
            play(view, 1601, 1);
            check(pixels(view, HOLD_FADED) == 0, "Miss Hold disappears at its tail time");
            play(view, 1800, 1);
            check(pixels(view, HOLD_FADED) == 0, "past-tail Hold cannot grow backwards");
        });
        probe("Entrance IN opening Hold geometry", () -> {
            JSONObject input = new JSONObject(SongLibrary.read(getTargetContext(), "library/349399ceb7f5/Chart_IN.json"));
            JSONObject line = input.getJSONArray("judgeLineList").getJSONObject(3);
            JSONObject hold = line.getJSONArray("notesBelow").getJSONObject(1);
            line.put("notesAbove", new JSONArray());
            line.put("notesBelow", new JSONArray().put(hold));
            input.put("judgeLineList", new JSONArray().put(line));
            Chart chart = new Chart(input.toString());
            check(chart.notes[0].time > 136000 && chart.lines[0].floor(chart.notes[0].end) < chart.notes[0].floor,
                "real opening sample has a future Hold with a negative-length body");
            PracticeView view = view(chart);
            main(() -> view.setPlayback(0, false, 1));
            check(pixels(view, 0xFF78D5EF) == 0, "offscreen ends do not join into a screen-covering ribbon");
        });
        probe("Hold body direction and actual viewport", () -> {
            String base = "{\"formatVersion\":3,\"judgeLineList\":[{\"bpm\":120,"
                + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0.5,\"end\":0.5,\"start2\":%s,\"end2\":%s}],"
                + "\"speedEvents\":%s,\"notesAbove\":[{\"type\":3,\"time\":%s,\"holdTime\":32,\"positionX\":0}],\"notesBelow\":[]}]}";
            PracticeView backwards = view(new Chart(String.format(java.util.Locale.ROOT, base, "0.5", "0.5",
                "[{\"startTime\":0,\"endTime\":64,\"value\":0.3},{\"startTime\":64,\"endTime\":32000,\"value\":-1}]", "64")));
            main(() -> backwards.setPlayback(0, false, 1));
            check(pixels(backwards, 0xFF78D5EF, 150, 225) == 0, "negative body does not connect head to tail through the stage");
            NoteSkin bundled = NoteSkin.bundled(getTargetContext());
            main(() -> backwards.setFallbackSkin(bundled));
            check(pixels(backwards, 0xFF55F5F7, 150, 225) == 0, "bundled caps also omit the negative-length body");
            check(pixels(backwards, 0xFF55F5F7, 120, 140) > 30 && pixels(backwards, 0xFF55F5F7, 240, 265) > 30,
                "negative-length Hold still draws its visible head and tail separately");
            PracticeView shifted = view(new Chart(String.format(java.util.Locale.ROOT, base, "-1", "-1",
                "[{\"startTime\":0,\"endTime\":32000,\"value\":1}]", "181.333333")));
            main(() -> shifted.setPlayback(0, false, 1));
            check(pixels(shifted, 0xFF78D5EF, 75, 120) > 500,
                "valid Hold on a shifted line remains visible even with local head beyond 1.5 stages");
            PracticeView frozen = view(3, 600, 1, true);
            main(() -> { frozen.setFallbackSkin(bundled); frozen.setPlayback(0, false, 1); });
            check(pixels(frozen, 0xFF55F5F7) > 30, "zero-length scrolling body keeps its visible head and tail caps");
        });
        probe("Entrance IN future-note cover", () -> {
            JSONObject input = new JSONObject(SongLibrary.read(getTargetContext(), "library/349399ceb7f5/Chart_IN.json"));
            JSONObject line = input.getJSONArray("judgeLineList").getJSONObject(0);
            JSONObject hold = line.getJSONArray("notesAbove").getJSONObject(57);
            line.put("notesAbove", new JSONArray().put(hold));
            line.put("notesBelow", new JSONArray());
            input.put("judgeLineList", new JSONArray().put(line));
            PracticeView view = view(new Chart(input.toString()));
            main(() -> view.setPlayback(35000, false, 1));
            check(pixels(view, 0xFF78D5EF, 330, 405) == 0,
                "real future Hold behind its judge line is covered instead of appearing early");
        });
    }
    private void holds() {
        probe("100 ms Hold immediate Perfect feedback", () -> {
            PracticeView view = view(3, 100, 1, false);
            play(view, 1000, 1); single(view, MotionEvent.ACTION_DOWN, 360);
            check(pixels(view, PERFECT) > 30, "head press immediately shows Perfect burst");
            check(totalHits(view) == 0, "head feedback does not count the Hold early");
            single(view, MotionEvent.ACTION_UP, 360);
            advance(view, 1000, 1101, 1);
            check(perfects(view) == 1 && misses(view) == 0, "short Hold accepts early release and counts one Perfect");
            play(view, 1150, 1); single(view, MotionEvent.ACTION_UP, 360);
            check(totalHits(view) == 1, "later frames and release cannot double-count");
        });
        probe("short Hold early Good feedback", () -> {
            PracticeView view = view(3, 100, 1, false);
            play(view, 840, 1); single(view, MotionEvent.ACTION_DOWN, 360);
            check(pixels(view, GOOD) > 30, "early Good head immediately shows blue burst");
            check(totalHits(view) == 0, "Good head also waits for tail to count");
            single(view, MotionEvent.ACTION_UP, 360);
            advance(view, 840, 1101, 1);
            check(goods(view) == 1 && perfects(view) == 0, "tail keeps the head's Good grade");
        });
        probe("short Hold Autoplay head feedback", () -> {
            PracticeView view = view(3, 100, 1, false);
            main(() -> view.setAutoplay(true));
            play(view, 1000, 1);
            check(pixels(view, PERFECT) > 30, "Autoplay also lights the head immediately");
            check(totalHits(view) == 0, "Autoplay head does not count early");
            advance(view, 1000, 1101, 1);
            check(perfects(view) == 1, "Autoplay counts the Hold once at its tail");
        });
        for (float speed : new float[] {0.1f, 0.5f, 1, 2}) {
            probe("legal Hold tail release " + speed + "x", () -> {
                PracticeView view = view(3, 1000, 1, false);
                play(view, 1000, speed); single(view, MotionEvent.ACTION_DOWN, 360);
                int release = 2000 - Math.round(180 * speed);
                play(view, release, speed); single(view, MotionEvent.ACTION_UP, 360);
                advance(view, release, 2001, speed);
                check(perfects(view) == 1 && misses(view) == 0,
                    "release inside last 220 real ms completes on head grade");
            });
            probe("Hold 40 chart-ms release/reconnect " + speed + "x", () -> {
                PracticeView view = view(3, 1000, 1, false);
                play(view, 1000, speed); single(view, MotionEvent.ACTION_DOWN, 360);
                play(view, 1010, speed); single(view, MotionEvent.ACTION_UP, 360);
                advance(view, 1010, 1050, speed);
                check(misses(view) == 0, "less than 50 chart ms detached survives at this speed");
                single(view, MotionEvent.ACTION_DOWN, 360);
                play(view, 2001, speed);
                check(perfects(view) == 1 && misses(view) == 0, "reconnected Hold completes once");
            });
            probe("Hold 60 chart-ms release timeout " + speed + "x", () -> {
                PracticeView view = view(3, 1000, 1, false);
                play(view, 1000, speed); single(view, MotionEvent.ACTION_DOWN, 360);
                play(view, 1010, speed); single(view, MotionEvent.ACTION_UP, 360);
                advance(view, 1010, 1070, speed);
                check(misses(view) == 1, "more than 50 chart ms detached breaks outside tail window");
                single(view, MotionEvent.ACTION_DOWN, 360); play(view, 2001, speed);
                check(totalHits(view) == 0 && misses(view) == 1, "timed-out Hold cannot resurrect");
            });
        }
        probe("unrelated finger cannot continue Hold", () -> {
            PracticeView view = view(3, 1000, 1, false);
            play(view, 1000, 1); fingers(view, MotionEvent.ACTION_DOWN, new int[] {0}, new float[] {360});
            fingers(view, MotionEvent.ACTION_POINTER_DOWN | 1 << 8, new int[] {0, 1}, new float[] {360, 80});
            fingers(view, MotionEvent.ACTION_POINTER_UP, new int[] {0, 1}, new float[] {360, 80});
            advance(view, 1000, 1070, 1);
            check(misses(view) == 1, "remaining finger outside key column does not sustain Hold");
        });
        probe("Hold handover between fingers", () -> {
            PracticeView view = view(3, 1000, 1, false);
            play(view, 1000, 1); fingers(view, MotionEvent.ACTION_DOWN, new int[] {0}, new float[] {360});
            fingers(view, MotionEvent.ACTION_POINTER_DOWN | 1 << 8, new int[] {0, 1}, new float[] {360, 360});
            fingers(view, MotionEvent.ACTION_POINTER_UP, new int[] {0, 1}, new float[] {360, 360});
            advance(view, 1000, 1070, 1); play(view, 2001, 1);
            check(perfects(view) == 1 && misses(view) == 0, "matching replacement finger sustains Hold");
        });
        probe("pause and reset state", () -> {
            PracticeView view = view(3, 1000, 1, false);
            play(view, 1000, 1); single(view, MotionEvent.ACTION_DOWN, 360);
            main(() -> view.setPlayback(1040, false, 1)); SystemClock.sleep(80);
            play(view, 1040, 1); single(view, MotionEvent.ACTION_DOWN, 360);
            advance(view, 1040, 1100, 1);
            check(misses(view) == 0, "pause wall time does not consume Hold grace");
            main(() -> view.resetAt(0)); play(view, 1000, 1);
            advance(view, 1000, 1230, 1);
            check(totalHits(view) == 0 && misses(view) == 1,
                "reset clears previous touch and Hold state rather than silently resuming it");
        });
        probe("pause clears touches", () -> {
            PracticeView view = view(3, 1000, 1, false);
            play(view, 1000, 1); single(view, MotionEvent.ACTION_DOWN, 360);
            main(() -> view.setPlayback(1010, false, 1)); play(view, 1010, 1);
            advance(view, 1010, 1070, 1);
            check(misses(view) == 1, "resume without a fresh touch does not keep stale finger down");
        });
    }
    private void spatialReach() {
        for (int scale : new int[] {100, 150, 250}) {
            for (int type : new int[] {1, 2, 3, 4}) {
                probe("type " + type + " reach at " + scale + "% art width", () -> {
                    Settings.noteScalePct(getTargetContext(), scale);
                    for (float x : new float[] {275, 445, 274, 446}) {
                        PracticeView view = view(type, type == 3 ? 1000 : 0, 1, false);
                        play(view, 1000, 1);
                        single(view, MotionEvent.ACTION_DOWN, type == 4 ? 360 : x);
                        if (type == 4) single(view, MotionEvent.ACTION_MOVE, x);
                        if (type == 3) advance(view, 1000, 2001, 1);
                        boolean inside = x == 275 || x == 445;
                        check(inside ? perfects(view) == 1 : totalHits(view) == 0,
                            "offset " + (x - 360) + "px " + (inside ? "receives Perfect" : "is outside the 85.05px half-reach"));
                    }
                });
            }
        }
        Settings.noteScalePct(getTargetContext(), 150);
        probe("Hold continuation at the same spatial boundary", () -> {
            for (float x : new float[] {275, 445, 274, 446}) {
                PracticeView view = view(3, 1000, 1, false);
                play(view, 1000, 1); single(view, MotionEvent.ACTION_DOWN, 360);
                play(view, 1010, 1); single(view, MotionEvent.ACTION_MOVE, x);
                advance(view, 1010, 2001, 1);
                boolean inside = x == 275 || x == 445;
                check(inside ? perfects(view) == 1 : misses(view) == 1,
                    "held offset " + (x - 360) + "px " + (inside ? "continues" : "breaks after release grace"));
            }
        });
        probe("Tap spatial priority with adjacent notes", () -> {
            Chart chart = new Chart("{\"formatVersion\":3,\"judgeLineList\":[{\"bpm\":120,"
                + "\"notesAbove\":[{\"type\":1,\"time\":64,\"positionX\":-2},"
                + "{\"type\":1,\"time\":65.6,\"positionX\":0}],\"notesBelow\":[]}]}");
            PracticeView view = view(chart);
            play(view, 1000, 1); single(view, MotionEvent.ACTION_DOWN, 360);
            final int[] left = {0};
            main(() -> {
                Bitmap bitmap = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(bitmap)); left[0] = bitmap.getPixel(279, 202); bitmap.recycle();
            });
            check(left[0] == 0xFF6DE4FA, "farther, exactly timed neighbor stays visible instead of stealing the centered press");
            single(view, MotionEvent.ACTION_UP, 360);
            single(view, MotionEvent.ACTION_DOWN, 195);
            check(perfects(view) == 2, "remaining neighbor can be pressed at its own edge");
        });
        for (int type : new int[] {1, 2, 3, 4}) probe("type " + type + " on a rotated judge line", () -> {
            Chart chart = new Chart("{\"formatVersion\":3,\"judgeLineList\":[{\"bpm\":120,"
                + "\"judgeLineRotateEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":90,\"end\":90}],"
                + "\"notesAbove\":[{\"type\":" + type + ",\"time\":64,\"holdTime\":64,\"positionX\":0}],\"notesBelow\":[]}]}");
            for (float offset : new float[] {85, 86}) {
                PracticeView view = view(chart);
                play(view, 1000, 1);
                main(() -> {
                    MotionEvent event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN,
                        360, type == 4 ? 202.5f : 202.5f + offset, 0);
                    view.onTouchEvent(event); event.recycle();
                    if (type == 4) {
                        event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                            360, 202.5f + offset, 0);
                        view.onTouchEvent(event); event.recycle();
                    }
                });
                if (type == 3) advance(view, 1000, 2001, 1);
                check(offset == 85 ? perfects(view) == 1 : totalHits(view) == 0,
                    "offset " + offset + "px follows the rotated line's axis");
            }
        });
        probe("Flick uses the current movement point", () -> {
            PracticeView view = view(4, 0, 1, false);
            play(view, 1000, 1); single(view, MotionEvent.ACTION_DOWN, 446);
            single(view, MotionEvent.ACTION_MOVE, 445);
            check(perfects(view) == 1, "moving from outside into the column accepts the new point");
        });
    }
    /** Compare drawings at equal real-time distances, without duplicating the scroll formula. */
    private void scrollSpeed() {
        probe("scroll setting defaults to follow and reloads", () -> {
            SharedPreferences prefs = getTargetContext().getSharedPreferences("phislow", Context.MODE_PRIVATE);
            prefs.edit().remove("keep_scroll_speed").commit();
            check(!Settings.keepScrollSpeed(getTargetContext()), "absent preference follows playback speed by default");
            Settings.keepScrollSpeed(getTargetContext(), true);
            check(prefs.getBoolean("keep_scroll_speed", false), "experimental choice is committed to preferences");
            PracticeView stage = view(1, 0, 1, false);
            main(() -> stage.setPlayback(875, false, 0.5f));
            Rect kept = keyBounds(stage, 1);
            Settings.keepScrollSpeed(getTargetContext(), false);
            check(sameBounds(kept, keyBounds(stage, 1)), "existing stage keeps its cached setting until reload");
            main(stage::reloadSettings);
            check(keyBounds(stage, 1).top > kept.top + 20, "reload restores slower travel at half speed");
            Settings.keepScrollSpeed(getTargetContext(), true);
            main(stage::reloadSettings);
            check(sameBounds(kept, keyBounds(stage, 1)), "reload enables compensation on the existing stage");
        });
        for (int type : new int[] {1, 2, 3, 4}) {
            for (float speed : new float[] {0.1f, 0.25f, 0.5f}) {
                probe("type " + type + " equal real-time travel " + speed + "x", () -> {
                    Settings.keepScrollSpeed(getTargetContext(), false);
                    PracticeView normal = view(type, type == 3 ? 400 : 0, 1, false);
                    main(() -> normal.setPlayback(750, false, 1));
                    Rect expected = keyBounds(normal, type);
                    Settings.keepScrollSpeed(getTargetContext(), true);
                    PracticeView slow = view(type, type == 3 ? Math.round(400 * speed) : 0, 1, false);
                    main(() -> slow.setPlayback(1000 - Math.round(250 * speed), false, speed));
                    Rect kept = keyBounds(slow, type);
                    check(!expected.isEmpty() && sameBounds(expected, kept),
                        "250 real ms before the head matches 1x art bounds, including Hold's tail");
                    Settings.keepScrollSpeed(getTargetContext(), false);
                    main(slow::reloadSettings);
                    Rect followed = keyBounds(slow, type);
                    check(!followed.isEmpty() && followed.top > kept.top + 20 && followed.bottom >= kept.bottom,
                        "follow mode moves the same note closer to its line");
                });
            }
            for (float speed : new float[] {1, 2}) probe("type " + type + " unchanged at " + speed + "x", () -> {
                Settings.keepScrollSpeed(getTargetContext(), false);
                PracticeView stage = view(type, type == 3 ? 400 : 0, 1, false);
                main(() -> stage.setPlayback(750, false, speed));
                Rect original = keyBounds(stage, type);
                Settings.keepScrollSpeed(getTargetContext(), true);
                main(stage::reloadSettings);
                check(!original.isEmpty() && sameBounds(original, keyBounds(stage, type)),
                    "experimental mode changes no geometry at or above 1x");
            });
        }
        probe("future-note cover uses compensated screen distance", () -> {
            Settings.keepScrollSpeed(getTargetContext(), false);
            PracticeView stage = view(new Chart("{\"formatVersion\":3,\"judgeLineList\":[{\"bpm\":120,"
                + "\"speedEvents\":[{\"startTime\":0,\"endTime\":32000,\"value\":0}],"
                + "\"notesAbove\":[{\"type\":1,\"time\":64,\"positionX\":0,\"floorPosition\":-0.0003}],\"notesBelow\":[]}]}"));
            main(() -> stage.setPlayback(0, false, 0.1f));
            check(!keyBounds(stage, 1).isEmpty(), "follow mode keeps the tiny wrong-side distance within its screen tolerance");
            Settings.keepScrollSpeed(getTargetContext(), true);
            main(stage::reloadSettings);
            check(keyBounds(stage, 1).isEmpty(), "compensated wrong-side distance exceeds the screen tolerance and is covered");
        });
        probe("early held head stays on the line with a long tail", () -> {
            Settings.keepScrollSpeed(getTargetContext(), true);
            for (float speed : new float[] {0.1f, 0.25f, 0.5f}) {
                PracticeView stage = view(3, 1000, 1, false);
                play(stage, 1000 - Math.round(50 * speed), speed);
                single(stage, MotionEvent.ACTION_DOWN, 360);
                Rect held = keyBounds(stage, 3);
                check(held.top == 0 && held.bottom >= 205 && held.bottom <= 208,
                    speed + "x early head is pinned to the center line while its tail clips at the viewport");
                check(totalHits(stage) == 0 && misses(stage) == 0, "early feedback does not complete the Hold");
            }
        });
        for (int type : new int[] {1, 2, 3, 4}) probe("type " + type + " time judgement ignores scroll setting", () -> {
            for (boolean keep : new boolean[] {false, true}) {
                Settings.keepScrollSpeed(getTargetContext(), keep);
                PracticeView stage = view(type, type == 3 ? 400 : 0, 1, false);
                play(stage, 970, 0.25f); // 120 real ms early: Good for Tap/Hold, still before Drag/Flick.
                single(stage, MotionEvent.ACTION_DOWN, 360);
                if (type == 4) single(stage, MotionEvent.ACTION_MOVE, 361);
                if (type == 2 || type == 4) {
                    check(totalHits(stage) == 0, "Drag/Flick cannot be judged before its chart time, keep=" + keep);
                    play(stage, 1000, 0.25f);
                    if (type == 4) single(stage, MotionEvent.ACTION_MOVE, 360);
                }
                if (type == 3) play(stage, 1401, 0.25f);
                check(misses(stage) == 0 && (type == 1 || type == 3 ? goods(stage) == 1 : perfects(stage) == 1),
                    "same touch timing keeps the same grade, keep=" + keep);
            }
        });
        probe("pause, retry and seek leave source chart unchanged", () -> {
            Settings.keepScrollSpeed(getTargetContext(), true);
            PracticeView stage = view(3, 400, 1, false);
            Chart source = stage.chart();
            PracticeView another = view(source);
            double floor = source.lines[0].floor(875), tailFloor = source.lines[0].floor(1400);
            main(() -> stage.setPlayback(875, false, 0.5f));
            Rect initial = keyBounds(stage, 3);
            main(() -> { stage.setPlayback(900, true, 0.5f); stage.setPlayback(900, false, 0.5f);
                stage.resetAt(0); stage.setPlayback(875, false, 0.5f); });
            check(sameBounds(initial, keyBounds(stage, 3)), "pause and retry recover the original compensated geometry");
            main(() -> { stage.resetAt(1250); stage.setPlayback(1250, false, 0.25f);
                stage.resetAt(875); stage.setPlayback(875, false, 0.5f);
                another.setPlayback(875, false, 0.5f); });
            check(sameBounds(initial, keyBounds(stage, 3)) && sameBounds(initial, keyBounds(another, 3)),
                "seeking inside a Hold does not pollute a second view using the same source");
            check(source.notes[0].time == 1000 && source.notes[0].end == 1400
                && source.notes[0].floor == 1 && source.lines[0].floor(875) == floor
                && source.lines[0].floor(1400) == tailFloor, "note times and source scroll positions are unchanged");
        });
    }
    /** Attached drawing must advance on the audio clock without the workbench's 33ms refresh. */
    private void frames() {
        probe("frame pacing and live audio clock", () -> {
            Intent intent = new Intent(getTargetContext(), SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity host = startActivitySync(intent);
            main(() -> host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            waitForIdleSync();
            for (int fps : Settings.FRAME_RATES) {
                String description = "帧率 " + (fps == 0 ? "无上限" : Integer.toString(fps));
                final boolean[] usable = {false};
                main(() -> {
                    View choice = described(host.getWindow().getDecorView(), description);
                    if (choice == null) return;
                    choice.requestRectangleOnScreen(new Rect(0, 0, choice.getWidth(), choice.getHeight()), true);
                    Rect shown = new Rect();
                    usable[0] = choice.getGlobalVisibleRect(shown) && shown.height() >= choice.getHeight() * 0.9;
                    choice.performClick();
                });
                check(usable[0] && Settings.frameRate(getTargetContext()) == fps,
                    "settings choice is reachable and persists: " + description);
            }
            final AudioEngine[] engine = {null};
            PracticeView stage = view(1, 0, 1, false);
            long[] draws = new long[3]; // count, first draw, last draw
            ViewTreeObserver.OnDrawListener observer = () -> {
                long now = System.nanoTime();
                if (draws[0]++ == 0) draws[1] = now;
                draws[2] = now;
            };
            try {
                main(() -> {
                    engine[0] = new AudioEngine(host, message -> {});
                    engine[0].load(null);
                    stage.setPlaybackClock(engine[0]);
                    stage.setAutoplay(true);
                    host.setContentView(stage);
                    stage.getViewTreeObserver().addOnDrawListener(observer);
                });
                long deadline = SystemClock.uptimeMillis() + 5000;
                final boolean[] ready = {false};
                do {
                    main(() -> ready[0] = engine[0].isReady());
                    if (!ready[0]) SystemClock.sleep(20);
                } while (!ready[0] && SystemClock.uptimeMillis() < deadline);
                if (!ready[0]) throw new AssertionError("sample did not prepare");
                main(() -> {
                    engine[0].play();
                    stage.setPlayback(engine[0].positionMs(), true, 1);
                });
                SystemClock.sleep(1600);
                check(perfects(stage) == 1, "attached drawing judges the 1s note with no external clock updates");
                for (int fps : Settings.FRAME_RATES) {
                    final float[] refresh = {0};
                    main(() -> {
                        Settings.frameRate(getTargetContext(), fps);
                        stage.reloadSettings();
                        refresh[0] = stage.getDisplay().getRefreshRate();
                    });
                    SystemClock.sleep(400);
                    main(() -> draws[0] = draws[1] = draws[2] = 0);
                    SystemClock.sleep(1800);
                    final double[] measured = {0};
                    main(() -> measured[0] = (draws[0] - 1) * 1_000_000_000d / (draws[2] - draws[1]));
                    double target = fps == 0 ? refresh[0] : Math.min(fps, refresh[0]);
                    check(measured[0] >= target * 0.72 && measured[0] <= target * 1.10,
                        String.format(java.util.Locale.ROOT, "setting=%s, display=%.1fHz, measured=%.1f FPS",
                            fps == 0 ? "uncapped" : Integer.toString(fps), refresh[0], measured[0]));
                }
                main(() -> { engine[0].pause(); stage.setPlayback(engine[0].positionMs(), false, 1); });
                SystemClock.sleep(800);
                main(() -> draws[0] = 0);
                SystemClock.sleep(400);
                final long[] stopped = {0};
                main(() -> stopped[0] = draws[0]);
                check(stopped[0] == 0, "paused stage stops requesting frames after effects finish");
            } finally {
                main(() -> {
                    stage.getViewTreeObserver().removeOnDrawListener(observer);
                    if (engine[0] != null) engine[0].release();
                    host.finish();
                });
            }
        });
    }
    private PracticeView view(int type, int duration, double noteSpeed, boolean frozen) throws Exception {
        double tick = 60000d / 120 / 32;
        String json = "{\"formatVersion\":3,\"offset\":0,\"judgeLineList\":[{\"bpm\":120,"
            + "\"speedEvents\":[{\"startTime\":0,\"endTime\":32000,\"value\":" + (frozen ? 0 : 1) + "}],"
            + "\"judgeLineMoveEvents\":[{\"startTime\":0,\"endTime\":32000,\"start\":0.5,\"end\":0.5,\"start2\":0.5,\"end2\":0.5}],"
            + "\"notesAbove\":[{\"type\":" + type + ",\"time\":64,\"holdTime\":" + duration / tick
            + ",\"positionX\":0,\"speed\":" + noteSpeed + ",\"floorPosition\":" + (frozen ? 0 : 1)
            + "}],\"notesBelow\":[]}]}";
        return view(new Chart(json));
    }
    private PracticeView view(Chart chart) {
        final PracticeView[] result = {null};
        main(() -> {
            result[0] = new PracticeView(getTargetContext()); result[0].layout(0, 0, 720, 405);
            result[0].setChart(chart); result[0].setSkin(null); result[0].setFallbackSkin(null);
        });
        return result[0];
    }
    private void play(PracticeView view, int position, float speed) {
        main(() -> view.setPlayback(position, true, speed));
    }
    /** Advance every small frame after release; a jump straight into the tail would mask timeout. */
    private void advance(PracticeView view, int from, int to, float speed) {
        int step = Math.max(1, Math.round(8 * speed));
        for (int position = from; position < to; ) {
            int next = Math.min(to, position + step);
            SystemClock.sleep(Math.max(1, Math.round((next - position) / speed)));
            play(view, next, speed); position = next;
        }
    }
    private void single(PracticeView view, int action, float x) {
        fingers(view, action, new int[] {0}, new float[] {x});
    }
    private void fingers(PracticeView view, int action, int[] ids, float[] xs) {
        main(() -> {
            long now = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) downTime = now;
            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[ids.length];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
            for (int i = 0; i < ids.length; i++) {
                props[i] = new MotionEvent.PointerProperties(); props[i].id = ids[i];
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coords[i] = new MotionEvent.PointerCoords(); coords[i].x = xs[i]; coords[i].y = 202;
                coords[i].pressure = 1; coords[i].size = 1;
            }
            MotionEvent event = MotionEvent.obtain(downTime, now, action, ids.length, props, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { view.onTouchEvent(event); } finally { event.recycle(); }
        });
    }
    private int pixels(PracticeView view, int color) {
        return pixels(view, color, 75, 355);
    }
    private int pixels(PracticeView view, int color, int from, int to) {
        final Bitmap[] image = {null};
        main(() -> { image[0] = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(image[0])); });
        int count = 0;
        for (int y = from; y < to; y++) for (int x = 200; x < 520; x++) {
            int p = image[0].getPixel(x, y);
            if (Math.abs((p >> 16 & 255) - (color >> 16 & 255)) <= 5
                && Math.abs((p >> 8 & 255) - (color >> 8 & 255)) <= 5
                && Math.abs((p & 255) - (color & 255)) <= 5) count++;
        }
        image[0].recycle(); return count;
    }
    private Rect keyBounds(PracticeView view, int type) {
        int color = new int[] {0, 0xFF6DE4FA, 0xFFF4CE68, 0xFF78D5EF, 0xFFFF87BB}[type];
        final Bitmap[] image = {null};
        main(() -> { image[0] = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(image[0])); });
        Rect bounds = new Rect(720, 405, 0, 0);
        for (int y = 0; y < 405; y++) for (int x = 200; x < 520; x++) {
            int pixel = image[0].getPixel(x, y);
            if (Math.abs((pixel >> 16 & 255) - (color >> 16 & 255)) <= 5
                && Math.abs((pixel >> 8 & 255) - (color >> 8 & 255)) <= 5
                && Math.abs((pixel & 255) - (color & 255)) <= 5) {
                bounds.left = Math.min(bounds.left, x); bounds.right = Math.max(bounds.right, x + 1);
                bounds.top = Math.min(bounds.top, y); bounds.bottom = Math.max(bounds.bottom, y + 1);
            }
        }
        image[0].recycle(); return bounds;
    }
    private boolean sameBounds(Rect first, Rect second) {
        return Math.abs(first.left - second.left) <= 1 && Math.abs(first.right - second.right) <= 1
            && Math.abs(first.top - second.top) <= 1 && Math.abs(first.bottom - second.bottom) <= 1;
    }
    private int perfects(PracticeView view) { final int[] n = {0}; main(() -> n[0] = view.perfectCount()); return n[0]; }
    private int goods(PracticeView view) { final int[] n = {0}; main(() -> n[0] = view.goodCount()); return n[0]; }
    private int misses(PracticeView view) { final int[] n = {0}; main(() -> n[0] = view.missCount()); return n[0]; }
    private int totalHits(PracticeView view) { return perfects(view) + goods(view); }
    private void main(Runnable action) { runOnMainSync(action); }
    private View described(View view, String description) {
        if (description.equals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = described(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
    private void probe(String name, Probe probe) {
        current = name;
        try { probe.run(); } catch (Throwable error) { check(false, "exception: " + error); }
    }
    private void check(boolean success, String detail) {
        if (success) passed++; else failed++;
        Bundle progress = new Bundle();
        progress.putString("stream", (success ? "PASS " : "FAIL ") + current + ": " + detail + "\n");
        sendStatus(0, progress);
    }
    @SuppressWarnings("unchecked") static void restore(SharedPreferences prefs, Map<String, ?> saved) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
        }
        edit.commit();
    }
}
