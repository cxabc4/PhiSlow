/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Phira-Pro prpr/src/core/block.rs (GNU GPL v3):
 * https://github.com/Phira-Pro/Phira-Pro/blob/5c28e71955d4ee74fd81f220efdccbdec4d727ed/prpr/src/core/block.rs
 * Field layout follows prpr/src/parse/pgr.rs of the same revision.
 * Source revision: 5c28e71955d4ee74fd81f220efdccbdec4d727ed.
 */
package com.phislow.app;

import android.graphics.RectF;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Comparator;

/**
 * A Phigros noise zone: a rectangle that covers the stage and swallows the touches that land on
 * it, so the notes beneath it go unplayed. Geometry and the touch rule follow the official
 * PreviewBlockControl / JudgeControl behaviour that Phira-Pro reconstructed:
 * <ul>
 *   <li>the zone is a rectangle in screen percentages, animated by rotate / move / scale tracks,
 *       each of which happens <em>around its own anchor</em>, so the anchor holds still while the
 *       rectangle grows or turns;</li>
 *   <li>it is on screen from {@code appearTime} to {@code disappearTime}, and only blocks touches
 *       between {@code enableTime} and {@code disableTime};</li>
 *   <li>a subtract zone <em>removes</em> coverage instead of adding it, which is how a chart cuts
 *       a window out of a full-screen zone.</li>
 * </ul>
 * Times in the chart file are seconds of song time; they are converted here to chart
 * milliseconds so that a zone and the notes it hides answer to the same clock.
 */
public final class BlockArea {
    /** Zone is off the stage entirely. */
    public static final int HIDDEN = 0;
    /** Zone is drawn as a dim preview but does not block touches. */
    public static final int DISABLED = 1;
    /** Zone is drawn at full strength and blocks touches. */
    public static final int ACTIVE = 2;

    /** Official JudgeControl.maxBlockTouchInsetLocal, as a fraction of the stage height. */
    private static final float TOUCH_INSET_LOCAL = 0.05f;
    /** Official JudgeControl.blockTouchInsetScreenHeightRatio: the clamp on that inset. */
    private static final float TOUCH_INSET_RATIO = 0.25f;

    public final double appearTime, enableTime, disableTime, disappearTime;
    public final boolean subtract;
    private final double centerX, centerY, width, height;
    private final Event[] move, rotate, scale;

    private static final class Event {
        final double time, x, y, anchorX, anchorY;
        final int easeX, easeY;
        Event(JSONObject json, String track) throws JSONException {
            time = json.getDouble("time") * 1000;
            if (track.equals("rotate")) {
                x = json.optDouble("rotation", 0); y = 0;
                easeX = json.optInt("easeType", 0); easeY = 0;
            } else {
                JSONObject value = json.getJSONObject(track.equals("move") ? "endPosition" : "scale");
                x = value.getDouble("x"); y = value.getDouble("y");
                easeX = json.optInt("easeTypeX", 0); easeY = json.optInt("easeTypeY", 0);
            }
            if (track.equals("move")) {
                anchorX = anchorY = 0;
            } else {
                JSONObject anchor = json.getJSONObject("anchor");
                anchorX = anchor.getDouble("x"); anchorY = anchor.getDouble("y");
            }
        }
    }

    public BlockArea(JSONObject json) throws JSONException {
        JSONObject bottom = json.getJSONObject("bottomLeftPercentage");
        JSONObject top = json.getJSONObject("topRightPercentage");
        centerX = (bottom.getDouble("x") + top.getDouble("x")) / 2;
        centerY = (bottom.getDouble("y") + top.getDouble("y")) / 2;
        width = top.getDouble("x") - bottom.getDouble("x");
        height = top.getDouble("y") - bottom.getDouble("y");
        appearTime = json.optDouble("appearTime", 0) * 1000;
        enableTime = json.optDouble("enableTime", 0) * 1000;
        disableTime = json.optDouble("disableTime", 0) * 1000;
        disappearTime = json.optDouble("disappearTime", 0) * 1000;
        subtract = json.optBoolean("isSubtract", false);
        move = events(json.optJSONArray("moveEvents"), "move");
        rotate = events(json.optJSONArray("rotateEvents"), "rotate");
        scale = events(json.optJSONArray("scaleEvents"), "scale");
    }

    /** Every zone of a chart, or a shared empty array for the charts that have none. */
    public static BlockArea[] list(JSONArray input) throws JSONException {
        if (input == null) return new BlockArea[0];
        BlockArea[] output = new BlockArea[input.length()];
        for (int i = 0; i < output.length; i++) output[i] = new BlockArea(input.getJSONObject(i));
        return output;
    }

    /** {@link #HIDDEN}, {@link #DISABLED} or {@link #ACTIVE}. Endpoints are half-open. */
    public int phase(double timeMs) {
        if (timeMs < appearTime || timeMs >= disappearTime) return HIDDEN;
        return timeMs >= enableTime && timeMs < disableTime ? ACTIVE : DISABLED;
    }

    /** Whether this zone blocks touches right now, which is what the touch rule asks first. */
    public boolean isActive(double timeMs) {
        return timeMs >= enableTime && timeMs < disableTime;
    }

    /** Resolve in screen pixels; reflection of chart Y also reverses rotation. */
    public void transform(double timeMs, RectF viewport, Transform out) {
        int si = index(scale, timeMs), ri = index(rotate, timeMs);
        double sx = value(scale, si, timeMs, false, 1);
        double sy = value(scale, si, timeMs, true, 1);
        double angle = -value(rotate, ri, timeMs, false, 0);
        double cx = viewport.left + centerX * viewport.width();
        double cy = viewport.top + (1 - centerY) * viewport.height();
        if (move.length != 0) {
            int mi = index(move, timeMs);
            cx = viewport.left + value(move, mi, timeMs, false, centerX) * viewport.width();
            cy = viewport.top + (1 - value(move, mi, timeMs, true, centerY)) * viewport.height();
        } else {
            double ax = scale.length == 0 ? cx : viewport.left + scale[si].anchorX * viewport.width();
            double ay = scale.length == 0 ? cy : viewport.top + (1 - scale[si].anchorY) * viewport.height();
            double rx = rotate.length == 0 ? cx : viewport.left + rotate[ri].anchorX * viewport.width();
            double ry = rotate.length == 0 ? cy : viewport.top + (1 - rotate[ri].anchorY) * viewport.height();
            double dx = ax + sx * (cx - ax) - rx;
            double dy = ay + sy * (cy - ay) - ry;
            double radians = Math.toRadians(angle), cos = Math.cos(radians), sin = Math.sin(radians);
            cx = rx + cos * dx - sin * dy;
            cy = ry + sin * dx + cos * dy;
        }
        out.x = (float) cx; out.y = (float) cy;
        out.width = (float) (width * viewport.width() * sx);
        out.height = (float) (height * viewport.height() * sy);
        out.rotation = (float) angle;
    }

    /** Scratch geometry belongs to the view, never to the shared chart. */
    public static final class Transform {
        public float x, y, width, height, rotation;

        /**
         * Whether a point lies inside this rectangle. {@code insetPx} is the official touch inset
         * in pixels: the tested area <em>shrinks</em> for a normal zone and <em>grows</em> for a
         * subtract one, so a zone's cut-out is a little more forgiving than its cover.
         */
        public boolean contains(float px, float py, float insetPx, boolean subtract) {
            float w = Math.abs(width), h = Math.abs(height);
            if (w < 1e-6f || h < 1e-6f) return false;
            double radians = Math.toRadians(rotation), cos = Math.cos(radians), sin = Math.sin(radians);
            double dx = px - x, dy = py - y;
            double localX = cos * dx + sin * dy, localY = -sin * dx + cos * dy;
            float sign = subtract ? 1 : -1;
            float halfX = w * (0.5f + sign * insetRatio(w, insetPx));
            float halfY = h * (0.5f + sign * insetRatio(h, insetPx));
            return Math.abs(localX) <= halfX && Math.abs(localY) <= halfY;
        }

        /** Official TryGetBlockTouchHalfSize: the inset as a fraction of the local half-size. */
        private static float insetRatio(float size, float insetPx) {
            float ratio = Math.abs(insetPx / size);
            return Math.min(TOUCH_INSET_RATIO, ratio);
        }
    }

    /** The official touch inset in stage pixels: 5% of the stage height. */
    public static float touchInsetPx(RectF viewport) {
        return TOUCH_INSET_LOCAL * viewport.height();
    }

    /**
     * Whether a touch at this point is swallowed, which is the official
     * JudgeControl.TryGetBlockingBlock rule: a point is blocked when a normal zone covers it
     * <em>XOR</em> an odd number of subtract zones cover it - and the same again for the inset
     * rectangle. Both have to agree, which is what keeps a lone subtract zone blocking while a
     * subtract zone cut out of a normal one leaves a hole.
     */
    public static boolean blocked(BlockArea[] areas, float px, float py, double timeMs,
                                  RectF viewport, Transform scratch) {
        if (areas == null || areas.length == 0) return false;
        float inset = touchInsetPx(viewport);
        boolean outerNormal = false, innerNormal = false;
        int outerSubtract = 0, innerSubtract = 0;
        for (BlockArea area : areas) {
            if (area.phase(timeMs) != ACTIVE) continue;
            area.transform(timeMs, viewport, scratch);
            if (area.subtract) {
                if (scratch.contains(px, py, 0, true)) outerSubtract++;
                if (scratch.contains(px, py, inset, true)) innerSubtract++;
            } else {
                outerNormal |= scratch.contains(px, py, 0, false);
                innerNormal |= scratch.contains(px, py, inset, false);
            }
        }
        return outerNormal != (outerSubtract % 2 == 1) && innerNormal != (innerSubtract % 2 == 1);
    }

    private static Event[] events(JSONArray input, String track) throws JSONException {
        if (input == null) return new Event[0];
        Event[] output = new Event[input.length()];
        for (int i = 0; i < output.length; i++) output[i] = new Event(input.getJSONObject(i), track);
        Arrays.sort(output, Comparator.comparingDouble(event -> event.time));
        return output;
    }

    /** Before the first key use its value and anchor; otherwise take the last <= time. */
    private static int index(Event[] events, double time) {
        int low = 0, high = events.length - 1;
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (events[mid].time <= time) low = mid; else high = mid - 1;
        }
        return low;
    }

    private static double value(Event[] events, int i, double time, boolean second, double fallback) {
        if (events.length == 0) return fallback;
        Event current = events[i];
        double from = second ? current.y : current.x;
        if (time < current.time || i + 1 == events.length) return from;
        Event next = events[i + 1];
        double to = second ? next.y : next.x;
        double progress = next.time <= current.time ? 1 : (time - current.time) / (next.time - current.time);
        return from + (to - from) * eased(second ? current.easeY : current.easeX, progress);
    }

    /** Official zone easing enum; it differs from chart line-event formats. Public for the probes. */
    public static double eased(int ease, double progress) {
        double u = Math.max(0, Math.min(1, progress));
        if (ease == 13) return 0;
        if (ease == 14) return 1;
        if (ease < 1 || ease > 12) return u;
        int power = (ease - 1) / 3 + 2;
        switch ((ease - 1) % 3) {
            case 0: return Math.pow(u, power);
            case 1: return 1 - Math.pow(1 - u, power);
            default: return u < 0.5 ? Math.pow(2, power - 1) * Math.pow(u, power)
                    : 1 - Math.pow(2 - 2 * u, power) / 2;
        }
    }
}
