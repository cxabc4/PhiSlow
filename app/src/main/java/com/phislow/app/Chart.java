package com.phislow.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Read-only chart data; judgement belongs to each practice view/session. */
public final class Chart {
    public final double offsetMs;
    public final Line[] lines;
    public final Note[] notes;
    /** Noise zones, animated on the same clock as the notes; empty when the chart has none. */
    public final BlockArea[] blockAreas;

    public static final class Note {
        public final int type, line, index;
        public final double time, end, x, speed, floor;
        public final boolean above;
        private Note(JSONObject json, double tick, int line, int index, boolean above) throws JSONException {
            type = json.getInt("type");
            if (type < 1 || type > 4) throw new JSONException("Unknown note type: " + type);
            this.line = line; this.index = index; this.above = above;
            time = json.getDouble("time") * tick;
            end = time + (type == 3 ? json.optDouble("holdTime", 0) * tick : 0);
            x = json.getDouble("positionX");
            speed = json.optDouble("speed", 1);
            floor = json.optDouble("floorPosition", Double.NaN);
        }
    }

    private static final class Event {
        double from, to, a, b, c, d;
        double interpolate(double time, boolean second) {
            double amount = to == from ? 1 : Math.max(0, Math.min(1, (time - from) / (to - from)));
            return second ? c + (d - c) * amount : a + (b - a) * amount;
        }
    }

    public static final class Line {
        private final Event[] move, rotate, alpha, speeds;
        private final double[] floors;
        public final Note[] notes;
        private Line(JSONObject json, int version, int lineIndex, List<Note> all) throws JSONException {
            double bpm = json.getDouble("bpm");
            if (!(bpm > 0)) throw new JSONException("Invalid BPM");
            double tick = 60000d / bpm / 32d;
            move = events(json.optJSONArray("judgeLineMoveEvents"), tick);
            if (version == 1) for (Event event : move) {
                double start = event.a, end = event.b;
                event.a = Math.floor(start / 1000) / 880d;
                event.b = Math.floor(end / 1000) / 880d;
                event.c = (start % 1000) / 520d;
                event.d = (end % 1000) / 520d;
            }
            rotate = events(json.optJSONArray("judgeLineRotateEvents"), tick);
            alpha = events(json.optJSONArray("judgeLineDisappearEvents"), tick);
            speeds = events(json.optJSONArray("speedEvents"), tick);
            floors = new double[speeds.length];
            double floor = 0;
            for (int i = 0; i < speeds.length; i++) {
                floors[i] = floor;
                floor += Math.max(0, speeds[i].to - Math.max(0, speeds[i].from)) / 1000d * speeds[i].a;
            }
            List<Note> local = new ArrayList<>();
            for (String side : new String[] {"notesAbove", "notesBelow"}) {
                JSONArray list = json.optJSONArray(side);
                if (list == null) continue;
                for (int i = 0; i < list.length(); i++) {
                    Note note = new Note(list.getJSONObject(i), tick, lineIndex, all.size(), side.equals("notesAbove"));
                    local.add(note); all.add(note);
                }
            }
            notes = local.toArray(new Note[0]);
            Arrays.sort(notes, Comparator.comparingDouble(note -> note.time));
        }
        public double x(double time) { return value(move, time, false, 0.5); }
        public double y(double time) { return value(move, time, true, 0.5); }
        public double rotation(double time) { return value(rotate, time, false, 0); }
        public double opacity(double time) { return value(alpha, time, false, 1); }
        public double floor(double time) {
            if (speeds.length == 0) return Math.max(0, time) / 1000d;
            int i = eventIndex(speeds, time);
            Event event = speeds[i];
            return floors[i] + (Math.max(0, time) - Math.max(0, event.from)) / 1000d * event.a;
        }
    }

    public Chart(String data) throws JSONException {
        JSONObject json = new JSONObject(data);
        int version = json.getInt("formatVersion");
        if (version != 1 && version != 3) throw new JSONException("Unsupported chart version: " + version);
        offsetMs = json.optDouble("offset", 0) * 1000;
        JSONArray input = json.getJSONArray("judgeLineList");
        lines = new Line[input.length()];
        List<Note> all = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) lines[i] = new Line(input.getJSONObject(i), version, i, all);
        notes = all.toArray(new Note[0]);
        blockAreas = BlockArea.list(json.optJSONArray("blockAreaList"));
    }

    private static Event[] events(JSONArray array, double tick) throws JSONException {
        if (array == null) return new Event[0];
        List<Event> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.getJSONObject(i);
            Event event = new Event();
            event.from = json.getDouble("startTime") * tick;
            event.to = json.getDouble("endTime") * tick;
            if (event.to < event.from) continue;
            event.a = json.optDouble("start", json.optDouble("value", 0));
            event.b = json.optDouble("end", event.a);
            event.c = json.optDouble("start2", 0); event.d = json.optDouble("end2", event.c);
            result.add(event);
        }
        Event[] output = result.toArray(new Event[0]);
        Arrays.sort(output, Comparator.comparingDouble(event -> event.from));
        return output;
    }
    private static int eventIndex(Event[] events, double time) {
        int low = 0, high = events.length - 1;
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (events[mid].from <= time) low = mid; else high = mid - 1;
        }
        return low;
    }
    private static double value(Event[] events, double time, boolean second, double fallback) {
        return events.length == 0 ? fallback : events[eventIndex(events, time)].interpolate(time, second);
    }
}
