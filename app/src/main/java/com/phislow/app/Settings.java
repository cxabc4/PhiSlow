package com.phislow.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The workbench's few standing preferences, kept in one place so a screen asks for a setting by
 * name instead of each one inventing its own file. Most are about what the practice stage writes
 * over the chart: the counter, the tallies, the song's name and its difficulty, and the two lines
 * of guidance shown while playback is stopped. The judgement windows are here too: how far off a
 * hit may be and still count.
 *
 * Writes are committed rather than applied. A preference that is changed on one screen and read on
 * the next has to be there by the time the next screen looks, and an asynchronous write leaves a
 * gap where the old value is still the one being read.
 */
public final class Settings {
    private static final String FILE = "phislow";
    /** The combo counter above the judge line, and the first thing a player looks for. */
    private static final String KEY_COMBO = "show_combo";
    /** The Perfect/Good/Bad/Miss tallies, which some players would rather not watch. */
    private static final String KEY_JUDGE = "show_judge";
    /** The song's name at the bottom left and its difficulty at the bottom right. */
    private static final String KEY_SONG = "show_song";
    /** The two lines of guidance drawn while playback is stopped. */
    private static final String KEY_HINT = "show_hint";
    /** How wide the judgement windows are: strict, regular, or widened for slow takes. */
    private static final String KEY_JUDGE_MODE = "judge_mode";
    /** How wide the keys are drawn, as a percentage of the base width: 100-250. */
    private static final String KEY_NOTE_SCALE = "note_scale_pct";
    /** The stage backdrop: a plain colour or the song's own cover art. */
    private static final String KEY_BACKGROUND = "background_mode";
    /** How late the sound arrives, in milliseconds; the chart clock shifts by it. */
    private static final String KEY_AUDIO_LATENCY = "audio_latency_ms";
    /** The chart's frame rate cap in fps; 0 rides the vsync without a cap. */
    private static final String KEY_FRAME_RATE = "frame_rate";
    /** Experimental compensation for note travel during playback slower than 1x. */
    private static final String KEY_KEEP_SCROLL_SPEED = "keep_scroll_speed";
    /** Noise zones: drawn over the stage and swallowing the touches that land on them. */
    private static final String KEY_NOISE = "show_noise";

    /** The settings, in the order the settings screen lists them. */
    public static final int COMBO = 0, JUDGE = 1, SONG = 2, HINT = 3;
    /** The judgement windows, in the order the settings screen offers them. */
    public static final int JUDGE_STRICT = 0, JUDGE_NORMAL = 1, JUDGE_WIDE = 2;
    /** The keys' normal display width. */
    public static final int NOTE_SCALE_DEFAULT = 150;
    /** The width slider's span, as percentages of the base width. */
    public static final int NOTE_SCALE_MIN = 100, NOTE_SCALE_MAX = 250;
    /** The backdrop choices, in the order the settings screen offers them. */
    public static final int BACKGROUND_SOLID = 0, BACKGROUND_COVER = 1;
    /** The audio latency slider's span, in milliseconds; positive means the sound arrives late. */
    public static final int AUDIO_LATENCY_MIN = -300, AUDIO_LATENCY_MAX = 300;
    /** The frame rate choices, in the order the settings screen offers them; 0 is 无上限. */
    public static final int[] FRAME_RATES = {60, 90, 120, 144, 0};

    private Settings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** One setting by its key, for a screen that walks them rather than naming each one. */
    public static boolean read(Context context, int which) {
        switch (which) {
            case JUDGE: return showJudge(context);
            case SONG: return showSong(context);
            case HINT: return showHint(context);
            default: return showCombo(context);
        }
    }

    public static void write(Context context, int which, boolean value) {
        switch (which) {
            case JUDGE: showJudge(context, value); break;
            case SONG: showSong(context, value); break;
            case HINT: showHint(context, value); break;
            default: showCombo(context, value); break;
        }
    }

    public static boolean showCombo(Context context) { return prefs(context).getBoolean(KEY_COMBO, true); }
    public static void showCombo(Context context, boolean value) { put(context, KEY_COMBO, value); }

    public static boolean showJudge(Context context) { return prefs(context).getBoolean(KEY_JUDGE, true); }
    public static void showJudge(Context context, boolean value) { put(context, KEY_JUDGE, value); }

    public static boolean showSong(Context context) { return prefs(context).getBoolean(KEY_SONG, true); }
    public static void showSong(Context context, boolean value) { put(context, KEY_SONG, value); }

    public static boolean showHint(Context context) { return prefs(context).getBoolean(KEY_HINT, true); }
    public static void showHint(Context context, boolean value) { put(context, KEY_HINT, value); }

    public static int judgeMode(Context context) { return prefs(context).getInt(KEY_JUDGE_MODE, JUDGE_NORMAL); }
    public static void judgeMode(Context context, int mode) { prefs(context).edit().putInt(KEY_JUDGE_MODE, mode).commit(); }

    public static int noteScalePct(Context context) { return prefs(context).getInt(KEY_NOTE_SCALE, NOTE_SCALE_DEFAULT); }
    public static void noteScalePct(Context context, int pct) {
        prefs(context).edit().putInt(KEY_NOTE_SCALE,
            Math.max(NOTE_SCALE_MIN, Math.min(NOTE_SCALE_MAX, pct))).commit();
    }

    public static int backgroundMode(Context context) { return prefs(context).getInt(KEY_BACKGROUND, BACKGROUND_COVER); }
    public static void backgroundMode(Context context, int mode) { prefs(context).edit().putInt(KEY_BACKGROUND, mode).commit(); }

    public static int audioLatencyMs(Context context) { return prefs(context).getInt(KEY_AUDIO_LATENCY, 0); }
    public static void audioLatencyMs(Context context, int latencyMs) {
        prefs(context).edit().putInt(KEY_AUDIO_LATENCY,
            Math.max(AUDIO_LATENCY_MIN, Math.min(AUDIO_LATENCY_MAX, latencyMs))).commit();
    }

    public static int frameRate(Context context) { return prefs(context).getInt(KEY_FRAME_RATE, 0); }
    public static void frameRate(Context context, int fps) { prefs(context).edit().putInt(KEY_FRAME_RATE, fps).commit(); }

    public static boolean keepScrollSpeed(Context context) { return prefs(context).getBoolean(KEY_KEEP_SCROLL_SPEED, false); }
    public static void keepScrollSpeed(Context context, boolean value) { put(context, KEY_KEEP_SCROLL_SPEED, value); }

    /**
     * Noise zones as the chart authored them. Off leaves a chart's zones drawn nowhere and
     * blocking nothing, which is what a player working through a blocked passage wants.
     */
    public static boolean showNoise(Context context) { return prefs(context).getBoolean(KEY_NOISE, true); }
    public static void showNoise(Context context, boolean value) { put(context, KEY_NOISE, value); }

    private static void put(Context context, String key, boolean value) {
        prefs(context).edit().putBoolean(key, value).commit();
    }
}
