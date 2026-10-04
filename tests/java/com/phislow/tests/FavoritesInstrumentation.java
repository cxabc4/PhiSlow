package com.phislow.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.phislow.app.Favorites;
import com.phislow.app.Favorites.SavedRange;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent favorites checks without opening activities or touching resource packs. */
public final class FavoritesInstrumentation extends Instrumentation {
    private int passed, failed;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Context context = getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("phislow_favorites", Context.MODE_PRIVATE);
        Map<String, ?> saved = new HashMap<>(prefs.getAll());
        Map<String, ?> settings = new HashMap<>(context.getSharedPreferences("phislow", Context.MODE_PRIVATE).getAll());
        try {
            if (!prefs.edit().clear().commit()) throw new IllegalStateException("Could not isolate favorites");
            Favorites favorites = new Favorites(context);
            check(favorites.listRanges().isEmpty() && !favorites.isSongFavorite("song-a"), "Empty initial favorites");
            check(favorites.toggleSongFavorite("song-a"), "Song favorite commits");
            check(new Favorites(context).isSongFavorite("song-a"), "New service reads the saved song favorite");
            check(!favorites.isSongFavorite("song-b"), "Different song IDs stay separate even with the same display title");
            check(favorites.toggleSongFavorite("song-a") && !favorites.isSongFavorite("song-a"), "Toggling again removes song favorite");

            check(favorites.saveRange("song-a", "a/IN.json", 12000, 18000), "A/B range commits");
            check(favorites.saveRange("song-a", "a/IN.json", 12000, 18000)
                && favorites.listRanges().size() == 1, "Repeated save does not duplicate the same range");
            check(favorites.saveRange("song-a", "a/HD.json", 12000, 18000)
                && favorites.saveRange("song-b", "b/IN.json", 12000, 18000)
                && favorites.saveRange("song-a", "a/IN.json", 18000, 24000), "Other charts, songs and intervals commit independently");
            List<SavedRange> ranges = new Favorites(context).listRanges();
            check(ranges.size() == 4 && ranges.get(0).chartPath.equals("a/IN.json")
                && ranges.get(1).chartPath.equals("a/HD.json") && ranges.get(2).songId.equals("song-b")
                && ranges.get(3).startMs == 18000, "New service preserves values and insertion order");
            ranges.clear();
            check(favorites.listRanges().size() == 4, "Changing a returned list does not change stored favorites");
            check(favorites.removeRange(new SavedRange("song-a", "a/HD.json", 12000, 18000)), "Removal commits by exact range identity");
            ranges = new Favorites(context).listRanges();
            check(ranges.size() == 3 && ranges.get(0).chartPath.equals("a/IN.json")
                && ranges.get(1).songId.equals("song-b") && ranges.get(2).startMs == 18000,
                "Removal keeps other songs, difficulties and ranges in order");
            check(favorites.removeRange(new SavedRange("song-a", "a/HD.json", 12000, 18000))
                && favorites.listRanges().size() == 3, "Repeated removal leaves remaining ranges unchanged");
            check(!favorites.saveRange("song-a", "a/IN.json", -1, 100)
                && !favorites.saveRange("song-a", "a/IN.json", 100, 100)
                && !favorites.saveRange("song-a", "a/IN.json", 200, 100)
                && favorites.listRanges().size() == 3, "Invalid boundaries cannot overwrite saved ranges");
            check(favorites.toggleSongFavorite("song-b"), "Song state can coexist with A/B ranges");
            check(prefs.edit().putString("ranges", "[{broken JSON").commit()
                && favorites.listRanges().isEmpty() && favorites.isSongFavorite("song-b"),
                "Malformed range JSON reads safely without deleting song favorites");
            check(favorites.saveRange("song-b", "b/IN.json", 0, 1000)
                && new Favorites(context).listRanges().size() == 1, "A new range can be saved after malformed JSON");
            check(prefs.edit().putString("ranges", "[{\"songId\":\"song-a\"}]").commit()
                && favorites.listRanges().isEmpty(), "Incomplete saved rows do not crash loading");
            check(context.getSharedPreferences("phislow", Context.MODE_PRIVATE).getAll().equals(settings),
                "Practice settings are untouched by favorites");
        } catch (Throwable error) {
            check(false, error.toString());
        } finally {
            check(restore(prefs, saved), "Original favorites restored");
            check(prefs.getAll().equals(saved), "Restored favorite data matches the original snapshot");
        }
        Bundle result = new Bundle();
        result.putString("stream", "\n" + (failed == 0 ? "PASS" : "FAIL") + " favorites: "
            + passed + " passed, " + failed + " failed\n");
        result.putInt("passed", passed);
        result.putInt("failed", failed);
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void check(boolean condition, String message) {
        if (condition) passed++; else failed++;
        Bundle status = new Bundle();
        status.putString("stream", (condition ? "PASS " : "FAIL ") + message + "\n");
        sendStatus(condition ? 0 : -1, status);
    }

    @SuppressWarnings("unchecked") private static boolean restore(SharedPreferences prefs, Map<String, ?> saved) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : saved.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
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
