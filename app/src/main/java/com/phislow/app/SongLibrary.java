package com.phislow.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class SongLibrary {
    public static final class Selection {
        public final String difficulty, path, audio;
        public final int notes;
        Selection(JSONObject json, String defaultAudio) throws Exception {
            difficulty = json.getString("difficulty"); path = json.getString("path");
            audio = json.optString("audio", defaultAudio); notes = json.getInt("notes");
        }
        @Override public String toString() { return difficulty + " · " + notes + " notes"; }
    }
    public static final class Song {
        public final String title, id, artist, cover, coverThumb, coverBlur;
        public final List<Selection> charts = new ArrayList<>();
        Song(JSONObject json) throws Exception {
            title = json.getString("title"); id = json.getString("id");
            artist = optional(json, "artist");
            cover = optional(json, "cover");
            coverThumb = optional(json, "coverThumb");
            coverBlur = optional(json, "coverBlur");
            JSONArray values = json.getJSONArray("charts");
            for (int i = 0; i < values.length(); i++) charts.add(new Selection(values.getJSONObject(i), json.getString("audio")));
        }
        @Override public String toString() { return title; }
    }
    /** Null-tolerant string read: absent or JSON null both become null. */
    private static String optional(JSONObject json, String name) {
        if (json.isNull(name)) return null;
        String value = json.optString(name, null);
        return value == null || value.isEmpty() ? null : value;
    }
    public final List<Song> songs = new ArrayList<>();
    public SongLibrary(Context context) throws Exception {
        this(context, null);
    }
    public SongLibrary(Context context, LibraryAssets.Progress progress) throws Exception {
        if (LibraryAssets.hasBundledLibrary(context)) {
            LibraryAssets.ensure(context, progress);
            JSONArray values = new JSONArray(read(context, "library/index.json"));
            for (int i = 0; i < values.length(); i++) songs.add(new Song(values.getJSONObject(i)));
        }
        songs.addAll(ImportedCharts.list(context));
    }
    public static String read(Context context, String path) throws Exception {
        try (InputStream input = LibraryAssets.open(context, path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
}
