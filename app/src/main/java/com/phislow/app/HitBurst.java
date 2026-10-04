package com.phislow.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import java.util.ArrayList;

/**
 * The judgement bursts - the ring that answers a key meeting its judge line. Kept apart from any
 * one screen because the practice canvas shows them and so does the resource-pack manager's
 * preview stage, which plays the keys by itself: both get the same burst, from the same art.
 *
 * A burst owns its own clock from the moment it is added, so nothing here knows what time the
 * chart is at.
 */
final class HitBurst {
    /** A burst is yellow for Perfect and blue for Good, matching the legend on screen. */
    static final int PERFECT = 0xFFFFD34D, GOOD = 0xFF4FA8FF;
    /** How long a burst lasts when the art does not say: long enough to be seen, not to linger. */
    private static final long DRAWN_MS = 340;
    /** How much wider than its key the hit art is drawn, before the pack's own scale. */
    private static final float EFFECT_KEYS = 3f;
    /** The hit art is white, so tinting it with the judgement colour is all it needs. */
    private static final PorterDuffColorFilter PERFECT_FX =
        new PorterDuffColorFilter(PERFECT, PorterDuff.Mode.SRC_ATOP);
    private static final PorterDuffColorFilter GOOD_FX =
        new PorterDuffColorFilter(GOOD, PorterDuff.Mode.SRC_ATOP);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Rect src = new Rect();
    private final ArrayList<Burst> bursts = new ArrayList<>();
    private static final class Burst { float x, y; int color; long start; }

    void add(float x, float y, int color) {
        Burst burst = new Burst();
        burst.x = x; burst.y = y; burst.color = color; burst.start = SystemClock.uptimeMillis();
        bursts.add(burst);
    }

    void clear() { bursts.clear(); }

    /**
     * Draws every burst still playing, newest last.
     *
     * @param fx       the skin whose hit art is played, or null for the drawn rings
     * @param unit     the width of one key, which sizes both the art and the rings
     * @param ringBase the starting radius of a drawn ring
     * @return true while a burst is still on screen, so the caller can keep asking for frames
     */
    boolean draw(Canvas canvas, NoteSkin fx, float unit, float ringBase) {
        if (bursts.isEmpty()) return false;
        long wall = SystemClock.uptimeMillis();
        // The bundled atlas plays the pack's own burst frame by frame; the drawn rings stay for a
        // view that has no art, which is also what the tests read.
        Bitmap atlas = fx == null ? null : fx.hitFx();
        long millis = atlas != null && fx.hitFxSeconds() > 0
            ? (long) (fx.hitFxSeconds() * 1000) : DRAWN_MS;
        boolean alive = false;
        for (int index = bursts.size() - 1; index >= 0; index--) {
            Burst burst = bursts.get(index);
            float progress = (wall - burst.start) / (float) millis;
            if (progress >= 1) { bursts.remove(index); continue; }
            alive = true;
            if (atlas != null) frame(canvas, fx, atlas, burst, progress, unit);
            else ring(canvas, burst, progress, ringBase);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        return alive;
    }

    /** Plays one frame of the hit-effect atlas, tinted with the judgement colour. */
    private void frame(Canvas canvas, NoteSkin fx, Bitmap atlas, Burst burst, float progress, float unit) {
        int columns = Math.max(1, fx.hitFxColumns()), rows = Math.max(1, fx.hitFxRows());
        int frame = Math.min(columns * rows - 1, (int) (progress * columns * rows));
        float cellWidth = atlas.getWidth() / (float) columns, cellHeight = atlas.getHeight() / (float) rows;
        int column = frame % columns, row = frame / columns;
        src.set(Math.round(column * cellWidth), Math.round(row * cellHeight),
            Math.round((column + 1) * cellWidth), Math.round((row + 1) * cellHeight));
        float side = unit * EFFECT_KEYS * fx.hitFxScale();
        box.set(burst.x - side / 2, burst.y - side / 2, burst.x + side / 2, burst.y + side / 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setFilterBitmap(true);
        paint.setColorFilter(burst.color == GOOD ? GOOD_FX : PERFECT_FX);
        canvas.drawBitmap(atlas, src, box, paint);
        paint.setColorFilter(null);
        paint.setFilterBitmap(false);
    }

    /** The ring drawn when there is no hit art: a filled core inside a widening outline. */
    private void ring(Canvas canvas, Burst burst, float progress, float ringBase) {
        float radius = ringBase * (1 + 1.5f * progress);
        int alpha = (int) (255 * (1 - progress) * (1 - progress));
        paint.setColor(burst.color);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha((int) (alpha * 0.15f));
        canvas.drawCircle(burst.x, burst.y, radius * 0.6f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3, ringBase * 0.46f * (1 - progress * 0.6f)));
        paint.setAlpha(alpha);
        canvas.drawCircle(burst.x, burst.y, radius, paint);
    }
}
