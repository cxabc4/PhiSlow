package com.phislow.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Song favorites and chart-specific A/B ranges, separate from practice settings. */
public final class Favorites {
    private static final String FILE = "phislow_favorites";
    private static final String SONGS = "songs", RANGES = "ranges";
    private final SharedPreferences prefs;

    public Favorites(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static final class SavedRange {
        public final String songId, chartPath, name;
        public final int startMs, endMs;

        public SavedRange(String songId, String chartPath, int startMs, int endMs) {
            this(songId, chartPath, startMs, endMs, null);
        }

        public SavedRange(String songId, String chartPath, int startMs, int endMs, String name) {
            this.songId = songId;
            this.chartPath = chartPath;
            this.startMs = startMs;
            this.endMs = endMs;
            this.name = name;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof SavedRange)) return false;
            SavedRange range = (SavedRange) other;
            return songId.equals(range.songId) && chartPath.equals(range.chartPath)
                && startMs == range.startMs && endMs == range.endMs;
        }

        @Override public int hashCode() {
            return ((songId.hashCode() * 31 + chartPath.hashCode()) * 31 + startMs) * 31 + endMs;
        }
    }

    public boolean isSongFavorite(String songId) {
        return prefs.getStringSet(SONGS, Collections.emptySet()).contains(songId);
    }

    /** Returns whether the change was persisted; read isSongFavorite for the new state. */
    public boolean toggleSongFavorite(String songId) {
        Set<String> songs = new HashSet<>(prefs.getStringSet(SONGS, Collections.emptySet()));
        if (!songs.remove(songId)) songs.add(songId);
        return prefs.edit().putStringSet(SONGS, songs).commit();
    }

    /** A fresh list in the order ranges were saved. */
    public List<SavedRange> listRanges() {
        List<SavedRange> ranges = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(prefs.getString(RANGES, "[]"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                SavedRange range = new SavedRange(row.getString("songId"), row.getString("chartPath"),
                    row.getInt("startMs"), row.getInt("endMs"), row.optString("name", "段落" + (i + 1)));
                if (!valid(range)) return new ArrayList<>();
                ranges.add(range);
            }
        } catch (JSONException error) {
            return new ArrayList<>();
        }
        return ranges;
    }

    public boolean saveRange(String songId, String chartPath, int startMs, int endMs) {
        List<SavedRange> ranges = listRanges();
        SavedRange range = new SavedRange(songId, chartPath, startMs, endMs, null);
        if (!validInterval(range)) return false;
        if (ranges.contains(range)) return writeRanges(ranges);
        return saveRange(songId, chartPath, startMs, endMs, nextRangeName(ranges));
    }

    /** Saves a named bookmark, updating its name when the same chart interval already exists. */
    public boolean saveRange(String songId, String chartPath, int startMs, int endMs, String name) {
        List<SavedRange> ranges = listRanges();
        String normalizedName = name == null ? "" : name.trim();
        SavedRange range = new SavedRange(songId, chartPath, startMs, endMs, normalizedName);
        if (!valid(range) || normalizedName.isEmpty()) return false;
        for (SavedRange existing : ranges)
            if (existing.name.equals(normalizedName) && !existing.equals(range)) return false;
        for (int i = 0; i < ranges.size(); i++) {
            SavedRange existing = ranges.get(i);
            if (existing.equals(range)) { ranges.set(i, range); return writeRanges(ranges); }
        }
        ranges.add(range);
        return writeRanges(ranges);
    }

    public String nextRangeName() { return nextRangeName(listRanges()); }

    private static String nextRangeName(List<SavedRange> ranges) {
        int largest = 0;
        for (SavedRange range : ranges) {
            if (range.name != null && range.name.matches("段落[0-9]+")) {
                try { largest = Math.max(largest, Integer.parseInt(range.name.substring(2))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return "段落" + (largest + 1);
    }

    public boolean removeRange(SavedRange range) {
        List<SavedRange> ranges = listRanges();
        ranges.remove(range);
        return writeRanges(ranges);
    }

    private static boolean valid(SavedRange range) {
        return validInterval(range) && range.name != null && !range.name.trim().isEmpty();
    }

    private static boolean validInterval(SavedRange range) {
        return range.songId != null && !range.songId.isEmpty()
            && range.chartPath != null && !range.chartPath.isEmpty()
            && range.startMs >= 0 && range.endMs > range.startMs;
    }

    private boolean writeRanges(List<SavedRange> ranges) {
        JSONArray rows = new JSONArray();
        try {
            for (SavedRange range : ranges) {
                rows.put(new JSONObject().put("songId", range.songId).put("chartPath", range.chartPath)
                    .put("startMs", range.startMs).put("endMs", range.endMs).put("name", range.name));
            }
        } catch (JSONException error) {
            return false;
        }
        return prefs.edit().putString(RANGES, rows.toString()).commit();
    }
}
