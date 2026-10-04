package com.phislow.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

public class TimelineView extends View {
    private static final int BACKGROUND = Color.rgb(17, 26, 37);
    private static final int ACCENT = Color.rgb(92, 225, 207);
    private static final int MUTED = Color.rgb(143, 162, 182);
    private static final int GRID = Color.rgb(42, 59, 77);
    private static final int HALF_WINDOW_MS = 4000;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int positionMs;
    private int durationMs;
    private boolean playing;
    private float speed = 1f;

    public TimelineView(Context context) {
        super(context);
        setContentDescription("练习时间轴，节拍参考为 120 BPM");
    }

    public void setPlayback(int positionMs, int durationMs, boolean playing, float speed) {
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.playing = playing;
        this.speed = speed;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize((int) dp(640), widthMeasureSpec),
                resolveSize((int) dp(220), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BACKGROUND);
        float padding = dp(18);
        float left = padding;
        float right = getWidth() - padding;
        float center = (left + right) / 2f;
        float width = Math.max(1f, right - left);
        float laneTop = getHeight() * 0.30f;
        float laneBottom = getHeight() * 0.64f;
        int duration = Math.max(0, durationMs);
        int position = Math.max(0, Math.min(positionMs, duration));

        label(canvas, "节拍参考 · 120 BPM", left, dp(27), dp(13), MUTED,
                Paint.Align.LEFT);
        String state = duration == 0 ? "等待音频" : (playing ? "播放中" : "已暂停");
        label(canvas, state + "  " + String.format(Locale.US, "%.2f ×", speed),
                right, dp(27), dp(13), ACCENT, Paint.Align.RIGHT);

        paint.setColor(GRID);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(left, laneBottom, right, laneBottom, paint);
        canvas.drawLine(left, laneTop, right, laneTop, paint);

        if (duration > 0) {
            long firstBeat = Math.max(0L, ((long) position - HALF_WINDOW_MS) / 500L * 500L);
            long lastBeat = Math.min((long) duration, (long) position + HALF_WINDOW_MS);
            for (long time = firstBeat; time <= lastBeat; time += 500L) {
                float x = center + (time - position) * width / (2f * HALF_WINDOW_MS);
                if (x < left || x > right) continue;
                boolean major = time % 2000L == 0;
                paint.setColor(time <= position ? ACCENT : GRID);
                paint.setAlpha(major ? 200 : 100);
                paint.setStrokeWidth(dp(major ? 2 : 1));
                canvas.drawLine(x, laneTop + dp(major ? 7 : 20), x,
                        laneBottom - dp(major ? 7 : 20), paint);
                paint.setAlpha(255);
            }
        } else {
            label(canvas, "节拍示例载入中", center,
                    (laneTop + laneBottom) / 2f + dp(5), dp(15), MUTED,
                    Paint.Align.CENTER);
        }

        if (duration > 0) {
            paint.setColor(ACCENT);
            paint.setStrokeWidth(dp(3));
            canvas.drawLine(center, laneTop - dp(5), center, laneBottom + dp(5), paint);
            canvas.drawCircle(center, laneTop - dp(5), dp(4), paint);
            label(canvas, timeLabel(Math.max(0L, (long) position - HALF_WINDOW_MS)),
                    left, laneBottom + dp(24), dp(12), MUTED, Paint.Align.LEFT);
            label(canvas, timeLabel(position), center, laneBottom + dp(24), dp(13),
                    ACCENT, Paint.Align.CENTER);
            label(canvas, timeLabel(Math.min((long) duration, (long) position + HALF_WINDOW_MS)),
                    right, laneBottom + dp(24), dp(12), MUTED, Paint.Align.RIGHT);
        }

        float progressY = getHeight() - dp(26);
        paint.setColor(GRID);
        paint.setStrokeWidth(dp(4));
        canvas.drawLine(left, progressY, right, progressY, paint);
        if (duration > 0) {
            paint.setColor(ACCENT);
            canvas.drawLine(left, progressY, left + width * position / duration, progressY, paint);
        }
        label(canvas, timeLabel(position) + " / " + timeLabel(duration), right,
                getHeight() - dp(7), dp(11), MUTED, Paint.Align.RIGHT);
    }

    private void label(Canvas canvas, String text, float x, float y, float size,
                       int color, Paint.Align align) {
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTextAlign(align);
        canvas.drawText(text, x, y, paint);
    }

    private String timeLabel(long milliseconds) {
        long seconds = milliseconds / 1000L;
        return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
