package com.phislow.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * The drawing of one key, kept apart from any one screen because more than one shows it: the
 * practice canvas, and the resource-pack manager's preview stage. Both come through here, so a
 * pack looks in the manager exactly as it will look in play - the coloured body of the art lined
 * up with the key box so the outline and the frame overhang it, a hold's caps kept at their own
 * height however far it stretches, a double's gold frame taken from the art when it carries one.
 *
 * Nothing here knows where a key is or what time it is: the caller owns the clock and the layout
 * and passes a finished box.
 */
final class KeyArt {
    /** Key colours, indexed by {@link Chart.Note#type}, used when there is no art to draw. */
    private static final int[] COLORS = {0, 0xFF6DE4FA, 0xFFF4CE68, 0xFF78D5EF, 0xFFFF87BB};
    /**
     * Two notes on one beat are pressed together, and the key says so before it is hit: a gold
     * ring with a white lining, drawn just outside the key so the key's own shape and colour are
     * left alone. The lining sits against the key with the gold outside it, and the gold is the
     * one the bundled art frames a double with, so a drawn ring matches the keys the app ships
     * with. It is kept apart from the Perfect ring's tone so a double never reads as a judgement.
     */
    private static final int DOUBLE_GOLD = 0xFFFDD146, DOUBLE_WHITE = 0xFFFFFFFF;
    /**
     * A hold being held is drawn at full strength, never dimmed. Dimming it was the obvious way to
     * say "this one is being held", and it reads as the opposite: against the dark playfield a
     * half-transparent key goes grey, so a hold being held looks like one that has been missed.
     * What says "held" is the burst it keeps giving while it is held, and the fact that it stays
     * on its judge line instead of passing through it.
     */

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Reused scratch boxes: the destination of a key, and the source region inside its art. */
    private final RectF box = new RectF();
    private final Rect src = new Rect();

    /**
     * Draws one key of any type: the art {@code source} supplies, the plain bar the app draws
     * itself when it supplies none, and the rings of a double press whenever the art does not
     * already carry them.
     *
     * @param source the skin to draw from; null leaves every key to the drawn bar
     * @param tail   the far end of a hold, in the same coordinates as {@code y}; unused otherwise
     * @param alpha  the strength to draw at: full for a live or held key, less for a missed one
     *               kept on the stage as its own ghost - the same art faded, never a grey variant
     *               and never a greyscale conversion
     */
    void draw(Canvas canvas, NoteSkin source, int type, boolean doubled,
            float x, float half, float y, float tail, float thickness, int alpha, boolean holdBody) {
        Bitmap frame = doubled && source != null ? source.noteDouble(type) : null;
        Bitmap art = source == null ? null : (frame != null ? frame : source.note(type));
        if (art != null) {
            if (type == NoteSkin.HOLD) hold(canvas, source, art, doubled, x, half, y, tail, thickness, alpha, holdBody);
            else key(canvas, source, art, type, doubled, x, half, y, thickness, alpha);
        } else {
            bar(canvas, type, x, half, y, tail, thickness, alpha, holdBody);
        }
        if (doubled && frame == null) doubleFrame(canvas, x, half, y, thickness, alpha);
    }

    /**
     * Draws a key's art so its coloured body lands exactly on the key box: the padding around the
     * body - the white outline, the end markers, the gold frame of a double - is meant to overhang
     * the key, so the box maps the body rather than the whole canvas. Without a spec the art
     * simply fills the box, as a pack without one always has.
     */
    private void key(Canvas canvas, NoteSkin source, Bitmap art, int type, boolean doubled,
            float x, float half, float y, float thickness, int alpha) {
        float[] body = doubled ? source.bodyRectDouble(type) : source.bodyRect(type);
        paint.setAlpha(alpha);
        if (body == null) {
            drawArt(canvas, art, x - half, y - thickness / 2, x + half, y + thickness / 2);
            return;
        }
        float scaleX = half * 2 / (body[2] - body[0]);
        float scaleY = thickness / (body[3] - body[1]);
        float left = x - half - body[0] * scaleX;
        float top = y - thickness / 2 - body[1] * scaleY;
        drawArt(canvas, art, left, top, left + art.getWidth() * scaleX, top + art.getHeight() * scaleY);
    }

    /**
     * A hold is a ribbon, not a bar: its caps keep their own height and only the stretch between
     * them grows, so a long hold does not squash its rounded ends flat. The head cap sits on the
     * note, the tail cap on the far end, whatever way round that is. A pack without cap info keeps
     * the whole-image stretch with its separate body picture.
     */
    private void hold(Canvas canvas, NoteSkin source, Bitmap art, boolean doubled,
            float x, float half, float y, float tail, float thickness, int alpha, boolean holdBody) {
        int[] caps = doubled ? source.holdCapsDouble() : source.holdCaps();
        float[] body = doubled ? source.bodyRectDouble(NoteSkin.HOLD) : source.bodyRect(NoteSkin.HOLD);
        paint.setAlpha(alpha);
        if (caps == null || body == null) {
            Bitmap ribbon = source.holdBody() == null ? art : source.holdBody();
            if (holdBody) drawArt(canvas, ribbon, x - half, Math.min(y, tail), x + half, Math.max(y, tail));
            key(canvas, source, art, NoteSkin.HOLD, doubled, x, half, y, thickness, alpha);
            return;
        }
        float scaleX = half * 2 / (body[2] - body[0]);
        float capHeight = caps[1] * scaleX;
        if (holdBody && Math.abs(tail - y) < capHeight * 2) {  // too short to show both caps: one squeezed key
            drawArt(canvas, art, x - half, Math.min(y, tail), x + half, Math.max(y, tail),
                body[0], caps[0], body[2], art.getHeight() - caps[1]);
            return;
        }
        float headTop = y - capHeight / 2, headBottom = y + capHeight / 2;
        float tailTop = tail - capHeight / 2, tailBottom = tail + capHeight / 2;
        if (holdBody) drawArt(canvas, art, x - half, Math.min(headBottom, tailBottom), x + half,
            Math.max(headTop, tailTop), body[0], caps[0], body[2], art.getHeight() - caps[1]);
        drawArt(canvas, art, x - half, tailTop, x + half, tailBottom, body[0], 0, body[2], caps[0]);
        drawArt(canvas, art, x - half, headTop, x + half, headBottom,
            body[0], art.getHeight() - caps[1], body[2], art.getHeight());
    }

    /** The key the app draws itself: a bar, plus the arrow that marks a flick. */
    private void bar(Canvas canvas, int type, float x, float half, float y, float tail,
            float thickness, int alpha, boolean holdBody) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLORS[type]);
        paint.setAlpha(alpha);
        if (type == NoteSkin.HOLD && holdBody) {
            canvas.drawRect(x - half, Math.min(y, tail), x + half, Math.max(y, tail), paint);
        }
        canvas.drawRoundRect(x - half, y - thickness / 2, x + half, y + thickness / 2, 3, 3, paint);
        if (type == NoteSkin.FLICK) {
            paint.setStrokeWidth(2);
            canvas.drawLine(x - half / 3, y - thickness, x, y - thickness * 2, paint);
            canvas.drawLine(x, y - thickness * 2, x + half / 3, y - thickness, paint);
        }
    }

    private void doubleFrame(Canvas canvas, float x, float half, float y, float thickness, int alpha) {
        float border = Math.max(2f, thickness * 0.3f);
        float inner = border * 0.5f, outer = border * 1.5f;
        float top = y - thickness / 2, bottom = y + thickness / 2;
        paint.setStyle(Paint.Style.STROKE);
        paint.setAlpha(alpha);
        paint.setStrokeWidth(border);
        // A stroke straddles its path, so each ring's path sits half a width further out to land
        // exactly next to the one inside it: key, then the white lining, then the gold ring.
        paint.setColor(DOUBLE_WHITE);
        canvas.drawRoundRect(x - half - inner, top - inner, x + half + inner, bottom + inner,
            3 + inner, 3 + inner, paint);
        paint.setColor(DOUBLE_GOLD);
        canvas.drawRoundRect(x - half - outer, top - outer, x + half + outer, bottom + outer,
            3 + outer, 3 + outer, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Stretches imported key art into a box, honouring the paint's alpha for held notes. */
    private void drawArt(Canvas canvas, Bitmap art, float left, float top, float right, float bottom) {
        if (right - left < 1 || bottom - top < 1) return;
        box.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(art, null, box, paint);
        paint.setFilterBitmap(false);
    }

    /** The same, for a chosen part of the art: the coloured body, a hold's cap, a ribbon. */
    private void drawArt(Canvas canvas, Bitmap art, float left, float top, float right, float bottom,
            float srcLeft, float srcTop, float srcRight, float srcBottom) {
        if (right - left < 1 || bottom - top < 1 || srcRight - srcLeft < 1 || srcBottom - srcTop < 1) return;
        src.set(Math.round(srcLeft), Math.round(srcTop), Math.round(srcRight), Math.round(srcBottom));
        box.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(art, src, box, paint);
        paint.setFilterBitmap(false);
    }
}
