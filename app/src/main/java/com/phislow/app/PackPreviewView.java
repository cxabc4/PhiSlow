package com.phislow.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * The resource-pack manager's preview stage: one lane per key, all four falling together onto a
 * judge line, played by themselves - a tap is struck when it arrives, a hold is held down from
 * the moment its head lands until its tail comes down to the line, and each one answers with the
 * same burst it would give in play. Then it starts again.
 *
 * A stage rather than a row of thumbnails because a pack is chosen for how its keys look coming
 * down at you, which a still picture of one key cannot show. The keys are drawn by {@link KeyArt}
 * and the bursts by {@link HitBurst}, the very same drawing the practice canvas uses, so a pack
 * previews exactly as it plays - including the keys it leaves to the art the app ships with.
 */
public final class PackPreviewView extends View {
    /** Lane order, left to right, as the captions under the stage name them. */
    private static final int[] TYPES = {NoteSkin.TAP, NoteSkin.DRAG, NoteSkin.HOLD, NoteSkin.FLICK};
    /** One round: the fall, the hold, a pause, then the fall again. */
    private static final long ROUND_MS = 3600;
    /** Where the judge line sits, and how far a hold's ribbon reaches back, of the stage height. */
    private static final float JUDGE = 0.70f, HOLD_SPAN = 0.22f;
    /** A key's half width as a fraction of its lane, and its thickness next to that half. */
    private static final float KEY_HALF = 0.34f, KEY_THICKNESS = 0.42f;
    /** How often a held hold answers while it is held; see PracticeView's HOLD_BURST_MS. */
    private static final long HOLD_BURST_MS = 220;
    private static final int STAGE = 0xFF0C1420, LINE = 0xFFEEE8CB;

    private final KeyArt keys = new KeyArt();
    private final HitBurst bursts = new HitBurst();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Which lanes have been struck this round, so a key is played once and not again. */
    private final boolean[] struck = new boolean[TYPES.length];
    private NoteSkin skin, fallback;
    private long started, round, heldSince;

    public PackPreviewView(Context context) {
        super(context);
        setContentDescription("资源包按键预览");
    }

    /**
     * @param skin     the pack being previewed, or null to leave every key to the art that ships
     *                 with the app
     * @param fallback that art, which also answers for the keys a pack does not carry, and whose
     *                 hit effect is the one the stage plays
     */
    public void setSkin(NoteSkin skin, NoteSkin fallback) {
        this.skin = skin;
        this.fallback = fallback;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth(), height = getHeight();
        canvas.drawColor(STAGE);
        if (width == 0 || height == 0) return;
        long now = SystemClock.uptimeMillis();
        if (started == 0) started = now;

        float judge = height * JUDGE;
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setColor(LINE);
        paint.setStrokeWidth(Math.max(1.5f, height * 0.005f));
        canvas.drawLine(0, judge, width, judge, paint);

        float lane = width / (float) TYPES.length;
        float half = lane * KEY_HALF;
        float thickness = Math.max(3f, half * KEY_THICKNESS);
        long elapsed = now - started;
        if (round != elapsed / ROUND_MS) {
            round = elapsed / ROUND_MS;
            for (int index = 0; index < struck.length; index++) struck[index] = false;
            heldSince = 0;
        }
        float fallen = (float) (elapsed % ROUND_MS) / ROUND_MS;
        float y = -thickness + fallen * (height + thickness * 2);
        float span = height * HOLD_SPAN;

        for (int index = 0; index < TYPES.length; index++) {
            if (struck[index]) continue;
            int type = TYPES[index];
            float center = lane * (index + 0.5f);
            boolean held = false;
            if (type == NoteSkin.HOLD) {
                // A hold is held from the moment its head lands until its tail reaches the line:
                // the head stays put, the ribbon shortens as the tail comes down after it. It
                // answers all the while, so being held never looks like being missed.
                if (y >= judge) {
                    if (y - span >= judge) {
                        struck[index] = true;
                        bursts.add(center, judge, HitBurst.PERFECT);
                        continue;
                    }
                    held = true;
                    if (now - heldSince >= HOLD_BURST_MS) {
                        heldSince = now;
                        bursts.add(center, judge, HitBurst.PERFECT);
                    }
                }
            } else if (y >= judge) {
                struck[index] = true;
                bursts.add(center, judge, HitBurst.PERFECT);
                continue;
            }
            // Which art answers is decided the same way as in play: the pack where it speaks,
            // the built-in art where it does not.
            NoteSkin source = skin != null && skin.note(type) != null ? skin : fallback;
            keys.draw(canvas, source, type, false, center, half, held ? judge : y, y - span,
                thickness, 255, true);
        }

        bursts.draw(canvas, fallback, half * 2, Math.max(width, height) * 0.02f);
        // A stage is only worth having if it keeps moving, so ask for the next frame.
        postInvalidateOnAnimation();
    }
}
