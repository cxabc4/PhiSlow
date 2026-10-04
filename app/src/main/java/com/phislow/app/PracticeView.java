package com.phislow.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import java.util.Arrays;
import java.util.Comparator;

/** Independent chart renderer and practice judgement. Audio position owns time. */
public final class PracticeView extends View {
    /** Two notes this close in chart milliseconds are pressed together, not one after the other. */
    private static final double SIMULTANEOUS_MS = 1;
    /**
     * How often a hold answers while it is held. A hold is held for as long as a finger is down,
     * which is far longer than the burst itself lasts, so it keeps giving one: without it a held
     * hold reads as a note that has simply stopped moving, which looks very like a missed one.
     */
    private static final long HOLD_BURST_MS = 220;
    /**
     * Note geometry as a fraction of the canvas at 100% width: chunky bars, the way the reference
     * charts read. The settings screen scales artwork independently of judgement reach.
     */
    private static final float NOTE_HALF = 0.040f, NOTE_THICKNESS = 0.021f;
    /** positionX to canvas width. */
    private static final double X_SCALE = 0.05625;
    /**
     * The perfect windows, in milliseconds, shared by every key type; the settings screen picks
     * between them. Good and Bad are absolute bounds rather than extras on the perfect window, so
     * narrowing the perfect window narrows nothing else: Bad is where a strike stops counting.
     */
    private static final float WINDOW_STRICT_MS = 40, WINDOW_NORMAL_MS = 80;
    private static final float GOOD_WINDOW_MS = 180, BAD_WINDOW_MS = 220;
    /**
     * Phira's late grace: a strike this far after the beat is read as on time. It scales with the
     * perfect window, so 常规 forgives the full 70ms and 严判 half of that - a strict mode that
     * forgave as much as the normal one would not be strict about anything.
     */
    private static final float LATE_FORGIVE_MS = 70;
    /** Phira's re-press tolerance, measured on the chart clock rather than wall time. */
    private static final float UP_GRACE_MS = 50;
    /** Phira's X_DIFF_MAX and distance-penalty core, converted from a 2-unit screen to width. */
    private static final float JUDGE_REACH = 0.118125f, JUDGE_CORE_REACH = 0.06587508f;
    private static final float DISTANCE_MS = 200;
    /** The grey a caption is written in, so the number beside it stays the thing read first. */
    private static final int LABEL = 0xFF8EA2B5;
    /**
     * A missed note keeps its own art at 45% until its time limit, regardless of scroll direction.
     */
    private static final int MISS_ALPHA = Math.round(255 * 0.45f);
    /** Extra real-time fade after a non-hold's late judgement window. */
    private static final float MISS_LINGER_MS = 200;
    /**
     * The song's cover, when the settings pick it for the backdrop, is drawn at this strength -
     * dimmed enough that the keys and the judge line stay the things seen first.
     */
    private static final int COVER_ALPHA = Math.round(255 * 0.20f);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The keys are drawn by {@link KeyArt} and their bursts by {@link HitBurst}, which the
     *  resource-pack manager's preview stage shares, so a pack previews as it plays. */
    private final KeyArt keys = new KeyArt();
    private final HitBurst bursts = new HitBurst();
    private final RectF viewport = new RectF();
    /** Reused scratch boxes for the cover backdrop: its centre-crop source and the destination. */
    private final Rect coverSource = new Rect();
    private final RectF coverBox = new RectF();
    private final SparseArray<Touch> touches = new SparseArray<>();
    private Chart chart;
    private NoteSkin skin;
    /** The art that ships with the app, used for any key the imported pack does not supply. */
    private NoteSkin fallback;
    private int[] state, holdGrade;
    private long[] lastHeld, lastBurst;
    /**
     * Chart time when this hold lost all matching touches; -1 while it is being held.
     */
    private double[] holdUpAt;
    /** Fixed chart-time deadlines, so changing speed cannot revive an expired Miss. */
    private double[] missUntil;
    /** Which notes share their beat with another, so they are pressed as a double. */
    private boolean[] paired;
    private int positionMs, combo, perfect, good, bad, miss;
    private boolean playing, autoplay;
    /** What the stage writes over the chart; empty until the workbench names the song. */
    private String songName, songLevel;
    /** Read once and cached: asking the preferences for them on every frame is a read a frame. */
    private boolean showCombo = true, showJudge = true, showSong = true, showHint = true;
    /** Which of the settings screen's judgement windows is in force; cached like the rest. */
    private int judgeMode;
    /** The keys' width scale from the settings screen: 1 at 100%, 1.5 at the default 150%. */
    private float noteScale = Settings.NOTE_SCALE_DEFAULT / 100f;
    /** Experimental display-only compensation; the chart and judgement clocks stay unchanged. */
    private boolean keepScrollSpeed;
    /** The current song's cover for the backdrop, or null when there is none. */
    private Bitmap cover;
    /** Whether the settings pick the cover for the backdrop rather than the plain colour. */
    private boolean coverBackground;
    /** Whether a chart's noise zones are drawn and whether they eat touches. */
    private boolean showNoise = true;
    /** The zones' own renderer, with mask buffers reused between frames. */
    private final NoiseField noiseField = new NoiseField();
    /** Geometry scratch for the touch rule, which resolves every zone against one point. */
    private final BlockArea.Transform zoneScratch = new BlockArea.Transform();
    /** How late the sound arrives; the chart's clock runs this much behind the position. */
    private int latencyMs;
    private AudioEngine playbackClock;
    private long framePeriodNs, nextFrameNs;
    private boolean framePending, animationActive;
    private final Runnable frameStep = new Runnable() {
        @Override public void run() {
            framePending = false;
            if (!isAttachedToWindow() || !animationActive) return;
            long now = System.nanoTime();
            if (framePeriodNs == 0 || now >= nextFrameNs) {
                nextFrameNs = framePeriodNs == 0 ? now : nextFrameNs + framePeriodNs;
                if (nextFrameNs < now) nextFrameNs = now + framePeriodNs;
                invalidate();
            } else scheduleFrame();
        }
    };
    private double time;
    private float playbackSpeed = 1;
    private static final class Touch { float x, y; }

    public PracticeView(Context context) {
        super(context);
        setContentDescription("触屏谱面练习区域");
        reloadSettings();
    }
    /** Re-reads the display settings, so a change made on the settings screen shows at once. */
    public void reloadSettings() {
        showCombo = Settings.showCombo(getContext());
        showJudge = Settings.showJudge(getContext());
        showSong = Settings.showSong(getContext());
        showHint = Settings.showHint(getContext());
        judgeMode = Settings.judgeMode(getContext());
        noteScale = Settings.noteScalePct(getContext()) / 100f;
        keepScrollSpeed = Settings.keepScrollSpeed(getContext());
        coverBackground = Settings.backgroundMode(getContext()) == Settings.BACKGROUND_COVER;
        showNoise = Settings.showNoise(getContext());
        latencyMs = Settings.audioLatencyMs(getContext());
        int frameRate = Settings.frameRate(getContext());
        framePeriodNs = frameRate > 0 ? Math.round(1_000_000_000d / frameRate) : 0;
        nextFrameNs = System.nanoTime();
        invalidate();
    }
    public void setPlaybackClock(AudioEngine clock) { playbackClock = clock; }
    private void syncPlaybackClock() {
        if (playbackClock == null || chart == null || !playing || playbackClock.isSeeking()) return;
        positionMs = playbackClock.positionMs();
        playbackSpeed = playbackClock.speed();
        playing = playbackClock.isPlaying();
        time = chartTime(positionMs);
        if (playing) updateJudgement();
    }
    private void scheduleFrame() {
        if (!framePending && isAttachedToWindow()) {
            framePending = true;
            postOnAnimation(frameStep);
        }
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frameStep);
        framePending = false;
        super.onDetachedFromWindow();
    }
    /** The song's name for the bottom left of the stage and its difficulty for the bottom right. */
    public void setSongInfo(String name, String level) {
        songName = name; songLevel = level; invalidate();
    }
    /** The current song's cover art for the backdrop, or null when the song has none. */
    public void setCover(Bitmap cover) {
        this.cover = cover;
        invalidate();
    }
    public void setChart(Chart chart) {
        this.chart = chart;
        state = new int[chart.notes.length]; holdGrade = new int[state.length];
        lastHeld = new long[state.length]; lastBurst = new long[state.length];
        holdUpAt = new double[state.length];
        missUntil = new double[state.length];
        paired = markDoubles(chart);
        resetAt(0);
    }
    public Chart chart() { return chart; }
    /** Whether this note is one of two or more landing on the same beat. */
    public boolean isDoublePress(int index) {
        return paired != null && index >= 0 && index < paired.length && paired[index];
    }
    /**
     * Marks every note that shares its beat with another. A property of the chart, worked out once
     * when the chart is set: doing it per frame would compare every note against every other one
     * thirty times a second for a picture that never changes. Notes are grouped by time rather than
     * by line, because a note on one line and a note on another still have to go down together.
     */
    private static boolean[] markDoubles(Chart chart) {
        boolean[] paired = new boolean[chart.notes.length];
        Chart.Note[] sorted = chart.notes.clone();
        Arrays.sort(sorted, Comparator.comparingDouble(note -> note.time));
        for (int start = 0; start < sorted.length; ) {
            int end = start + 1;
            while (end < sorted.length && sorted[end].time - sorted[start].time <= SIMULTANEOUS_MS) end++;
            if (end - start >= 2) for (int i = start; i < end; i++) paired[sorted[i].index] = true;
            start = end;
        }
        return paired;
    }
    /** The imported key artwork, or null while the keys are drawn in code. */
    public NoteSkin skin() { return skin; }
    public void setSkin(NoteSkin skin) { this.skin = skin; invalidate(); }
    /** The bundled art, which any key the imported pack covers overrides. */
    public void setFallbackSkin(NoteSkin skin) { this.fallback = skin; invalidate(); }
    public int perfectCount() { return perfect; }
    public int goodCount() { return good; }
    public int badCount() { return bad; }
    public int missCount() { return miss; }
    public void setAutoplay(boolean enabled) { autoplay = enabled; resetAt(positionMs); }
    /** The chart's clock: the audio position minus the chart's offset and the headphone latency. */
    private double chartTime(int position) {
        return position - chart.offsetMs - latencyMs;
    }
    public void resetAt(int position) {
        touches.clear(); bursts.clear(); combo = perfect = good = bad = miss = 0;
        positionMs = position;
        if (chart != null) {
            time = chartTime(position);
            Arrays.fill(holdUpAt, -1d);
            Arrays.fill(missUntil, 0);
            for (Chart.Note note : chart.notes) {
                state[note.index] = note.end < time ? 5 : 0;
                if (note.type == 3 && note.time < time && time < note.end) {
                    state[note.index] = 1; holdGrade[note.index] = 2;
                    lastHeld[note.index] = SystemClock.uptimeMillis();
                    lastBurst[note.index] = SystemClock.uptimeMillis();
                }
            }
        }
        invalidate();
    }
    /**
     * The perfect window in chart milliseconds. The playback speed is already inside it - the
     * chart's clock runs at the audio's pace, so a window fixed in chart time would tighten in
     * real time as the take slows. 宽判 answers that: the slower the take, the wider the window,
     * by (1 - speed) of 常规, so 0.5× judges against ±120ms and 0.1× against ±152ms; at 1× and
     * above it is simply 常规.
     */
    private float perfectWindow() {
        float base = judgeMode == Settings.JUDGE_STRICT ? WINDOW_STRICT_MS : WINDOW_NORMAL_MS;
        if (judgeMode == Settings.JUDGE_WIDE) base *= Math.max(1f, 2 - playbackSpeed);
        return base * playbackSpeed;
    }
    /** Where a strike stops counting at all, in chart milliseconds. Fixed by every judgement mode. */
    private float badWindow() { return BAD_WINDOW_MS * playbackSpeed; }
    /**
     * The late grace in chart milliseconds, scaled off the perfect window: 常规 forgives the whole
     * {@link #LATE_FORGIVE_MS} and 严判 half of it. It rides on the perfect window rather than the
     * playback speed, so the strict mode stays strict at every speed instead of only at 1×.
     */
    private float lateGrace() { return LATE_FORGIVE_MS * perfectWindow() / WINDOW_NORMAL_MS; }
    /**
     * How far a strike is off the beat, in chart milliseconds, as a magnitude - with Phira's late
     * grace already folded in. A strike up to {@link #lateGrace()} after the beat reads as on time
     * and collapses to zero, the way Phira collapses it to zero at its own 70ms. Early strikes are
     * left alone: they are not the ones a player cannot hear themselves miss.
     */
    private double strikeError(double noteTime) {
        double delta = time - noteTime;
        return delta <= 0 ? -delta : Math.max(0, delta - lateGrace());
    }
    public void setPlayback(int position, boolean playing, float speed) {
        boolean changed = this.playing != playing;
        if (position < positionMs - 200) resetAt(position);
        if (this.playing && !playing) touches.clear();
        if (!this.playing && playing && chart != null) {
            for (Chart.Note note : chart.notes) if (state[note.index] == 1) {
                lastHeld[note.index] = SystemClock.uptimeMillis();
                lastBurst[note.index] = SystemClock.uptimeMillis();
                holdUpAt[note.index] = -1;
            }
        }
        this.playing = playing; this.playbackSpeed = speed; this.positionMs = position;
        if (chart != null) {
            time = chartTime(position);
            if (playing) updateJudgement();
        }
        if (playbackClock == null || !playing || changed) invalidate();
    }
    private void updateJudgement() {
        long wall = SystemClock.uptimeMillis();
        for (Chart.Note note : chart.notes) {
            int i = note.index;
            if (state[i] >= 2) continue;
            if (autoplay && time >= note.time) {
                if (note.type == 3 && time < note.end) {
                    if (state[i] == 0 || wall - lastBurst[i] >= HOLD_BURST_MS) {
                        lastBurst[i] = wall;
                        burst(note, true);
                    }
                    state[i] = 1;
                }
                else judge(i, 2);
                continue;
            }
            if (state[i] == 1) {
                // Inside the last stretch of a hold Phira stops asking: whatever the finger is
                // doing there, everything of the hold that can be seen has already been held.
                if (note.end - time <= badWindow() || matchesAny(note)) {
                    holdUpAt[i] = -1;
                    lastHeld[i] = wall;
                } else if (holdUpAt[i] < 0) holdUpAt[i] = time;
                else if (time - holdUpAt[i] > UP_GRACE_MS) { judge(i, 4); continue; }
                if (wall - lastBurst[i] >= HOLD_BURST_MS) {
                    lastBurst[i] = wall;
                    burst(note, holdGrade[i] == 2);
                }
                if (time >= note.end) judge(i, holdGrade[i]);
            } else if (time - note.time > badWindow()) {
                judge(i, 4);
            } else if (note.type == 2 && time >= note.time && matchesAny(note)) {
                // A drag accepts any finger in its column as it reaches the line.
                judge(i, 2);
            }
        }
    }
    private void judge(int index, int grade) {
        state[index] = grade;
        if (grade == 4) {
            Chart.Note note = chart.notes[index];
            missUntil[index] = note.end + (note.type == 3 ? 0
                : (BAD_WINDOW_MS + MISS_LINGER_MS) * playbackSpeed);
        }
        if (grade == 4 || grade == 6) { if (grade == 4) miss++; else bad++; combo = 0; return; }
        if (grade == 2) perfect++; else good++;
        combo++;
        burst(chart.notes[index], grade == 2);
    }
    /** Spawns the judgement ring where the note meets its judge line. */
    private void burst(Chart.Note note, boolean isPerfect) {
        Chart.Line line = chart.lines[note.line];
        double angle = Math.toRadians(-line.rotation(time));
        float originX = viewport.left + (float) (line.x(time) * viewport.width());
        float originY = viewport.top + (float) ((1 - line.y(time)) * viewport.height());
        // The note sits on the judge line at the moment it is judged, so only its x offset applies.
        float x = originX + (float) (note.x * X_SCALE * viewport.width() * Math.cos(angle));
        float y = originY + (float) (note.x * X_SCALE * viewport.width() * Math.sin(angle));
        bursts.add(x, y, isPerfect ? HitBurst.PERFECT : HitBurst.GOOD);
    }
    /** A flick accepts movement in any direction within its judgement column. */
    private void swipe(float x, float y) {
        if (chart == null || blocked(x, y)) return;
        Chart.Note closest = null;
        double best = Double.MAX_VALUE;
        for (Chart.Note note : chart.notes) {
            if (note.type != 4 || state[note.index] != 0 || time < note.time || !matches(note, x, y)) continue;
            double delta = time - note.time;
            double score = delta + distancePenalty(note, x, y);
            if (delta <= badWindow() && score < best) { best = score; closest = note; }
        }
        if (closest != null) judge(closest.index, 2);
    }
    private boolean matchesAny(Chart.Note note) {
        for (int i = 0; i < touches.size(); i++) {
            Touch touch = touches.valueAt(i);
            if (!blocked(touch.x, touch.y) && matches(note, touch.x, touch.y)) return true;
        }
        return false;
    }
    /** The drawn key's half width. */
    private float keyHalf() {
        return viewport.width() * NOTE_HALF * noteScale;
    }

    /** All note types share Phira's along-line reach; perpendicular distance is not checked. */
    private boolean matches(Chart.Note note, float x, float y) {
        return touchDistance(note, x, y) <= JUDGE_REACH * viewport.width();
    }
    private double touchDistance(Chart.Note note, float x, float y) {
        Chart.Line line = chart.lines[note.line];
        double angle = Math.toRadians(-line.rotation(time));
        double dx = x - viewport.left - line.x(time) * viewport.width();
        double dy = y - viewport.top - (1 - line.y(time)) * viewport.height();
        double localX = dx * Math.cos(angle) + dy * Math.sin(angle);
        return Math.abs(localX - note.x * X_SCALE * viewport.width());
    }
    private double distancePenalty(Chart.Note note, float x, float y) {
        return Math.max(0, touchDistance(note, x, y) / (JUDGE_CORE_REACH * viewport.width()) - 1)
            * DISTANCE_MS * playbackSpeed;
    }
    private void tap(float x, float y) {
        if (blocked(x, y)) return;
        Chart.Note closest = null;
        double best = Double.MAX_VALUE, bestScore = Double.MAX_VALUE;
        for (Chart.Note note : chart.notes) {
            if (state[note.index] != 0 || (note.type != 1 && note.type != 3) || !matches(note, x, y)) continue;
            double error = strikeError(note.time);
            double score = error + distancePenalty(note, x, y);
            // A tap still counts a strike out to Bad; a hold's head stops at Good, because a tap
            // that late on a long note is a different mistake from a tap that is merely loose.
            if (error <= (note.type == 1 ? BAD_WINDOW_MS : GOOD_WINDOW_MS) * playbackSpeed
                    && score < bestScore) { best = error; bestScore = score; closest = note; }
        }
        if (closest == null) return;
        float window = perfectWindow();
        int grade = best <= window ? 2 : best <= GOOD_WINDOW_MS * playbackSpeed ? 3 : 6;
        if (closest.type == 3) {
            state[closest.index] = 1; holdGrade[closest.index] = grade;
            lastHeld[closest.index] = SystemClock.uptimeMillis();
            lastBurst[closest.index] = SystemClock.uptimeMillis();
            holdUpAt[closest.index] = -1;
            burst(closest, grade == 2);
        } else judge(closest.index, grade);
    }
    /** Phira-Pro input parity is separate from the visual subtract threshold. */
    private boolean blocked(float x, float y) {
        return showNoise && !autoplay && chart != null && viewport.height() > 0
            && BlockArea.blocked(chart.blockAreas, x, y, time, viewport, zoneScratch);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        syncPlaybackClock();
        if (!isEnabled() || chart == null || !playing || autoplay) return true;
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            Touch touch = new Touch();
            touch.x = event.getX(index); touch.y = event.getY(index);
            touches.put(event.getPointerId(index), touch);
            tap(touch.x, touch.y);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                Touch touch = touches.get(event.getPointerId(i));
                if (touch == null) continue;
                float x = event.getX(i), y = event.getY(i);
                swipe(x, y);
                touch.x = x; touch.y = y;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            touches.remove(event.getPointerId(index));
        } else if (action == MotionEvent.ACTION_CANCEL) touches.clear();
        updateJudgement();
        if (playbackClock == null) invalidate(); else scheduleFrame();
        return true;
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float h = Math.min(height, width * 9f / 16), w = h * 16f / 9;
        viewport.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2);
    }
    @Override protected void onDraw(Canvas canvas) {
        syncPlaybackClock();
        canvas.drawColor(0xFF0C1420);
        if (coverBackground && cover != null) drawCover(canvas);
        if (chart == null || viewport.width() == 0) return;
        float scrollFactor = keepScrollSpeed && playbackSpeed < 1 ? 1 / playbackSpeed : 1;
        float scale = viewport.height() * 0.6f * scrollFactor;
        if (showNoise) noiseField.draw(canvas, chart.blockAreas, time, viewport, BlockArea.DISABLED);
        canvas.save(); canvas.clipRect(viewport);
        for (Chart.Line line : chart.lines) {
            double opacity = line.opacity(time);
            // Phira hides a whole line - stroke and notes alike - only once its alpha goes
            // *negative*. At alpha 0 the stroke alone goes: charts park their performance notes
            // on invisible lines, and those notes are meant to be seen and played. prpr draws the
            // line with alpha.max(0) and only then returns on alpha < 0, so a chart that fades a
            // line to nothing still has its notes coming down it.
            if (opacity < 0) continue;
            float alpha = (float) Math.min(1, Math.max(0, opacity));
            canvas.save();
            canvas.translate(viewport.left + (float) line.x(time) * viewport.width(),
                viewport.top + (1 - (float) line.y(time)) * viewport.height());
            canvas.rotate((float) -line.rotation(time));
            paint.setColor(0xFFEEE8CB); paint.setAlpha((int) (alpha * 255));
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(Math.max(2, viewport.height() * 0.004f));
            canvas.drawLine(-viewport.width(), 0, viewport.width(), 0, paint);
            double floor = line.floor(time);
            for (Chart.Note note : line.notes) {
                int judged = state[note.index];
                // Misses also have a time limit: a stopped/reversing line may never scroll them out.
                boolean missed = judged == 4;
                if (judged >= 2 && !missed) continue;
                double visibleUntil = missed ? missUntil[note.index]
                    : note.end + (note.type == 3 ? 0 : badWindow());
                if (time >= visibleUntil) continue;
                double startFloor = Double.isNaN(note.floor) ? line.floor(note.time) : note.floor;
                double approach = (startFloor - floor) * (note.type == 3 ? 1 : note.speed);
                // PGR charts cover future notes that have crossed to the wrong side of the line.
                // An already pressed Hold keeps its head on the line, including an early press.
                if (time < note.time && judged != 1
                    && approach * scrollFactor <= -0.001 * viewport.width() / viewport.height()) continue;
                float side = note.above ? -1 : 1;
                float y = (float) (approach * scale * side);
                // A held hold's head sits on its judge line from the moment it is pressed, even
                // a little before its time - the player's finger, not the chart clock, put it there.
                if (note.type == 3 && (time >= note.time || state[note.index] == 1)) y = 0;
                float x = (float) (note.x * X_SCALE * viewport.width());
                // One width for every key: a hold's ribbon runs at the same half as its own ends
                // and as the tap, drag and flick keys, so no type reads as narrower than another.
                float half = keyHalf();
                float thickness = Math.max(6, viewport.height() * NOTE_THICKNESS);
                float tail = (float) ((line.floor(note.end) - floor) * scale * side);
                // Reverse scroll can put the tail behind the head. Phira skips the negative-length
                // body, while the real head and tail remain independently visible. Let the canvas
                // clip their actual geometry: a distant head can still have a legitimate visible tail.
                boolean holdBody = (tail - y) * side > 0;
                boolean doubled = paired[note.index];
                // The imported pack speaks first; the bundled art answers for the keys it does not
                // supply. A double takes the pack's own gold-framed variant when it has one, and
                // only then does the drawn ring step aside - two gold rings would read as one thick.
                NoteSkin source = skin != null && skin.note(note.type) != null ? skin : fallback;
                // A note keeps its own alpha, the way prpr renders one: a note on a line that has
                // faded to nothing is still there to be hit, so only a miss dims it. Riding the
                // line's alpha instead would make every note on an invisible line invisible too,
                // which is exactly the performance note the chart is asking for.
                keys.draw(canvas, source, note.type, doubled, x, half, y, tail, thickness,
                    missed ? MISS_ALPHA : 255, holdBody);
            }
            canvas.restore();
        }
        canvas.restore();
        animationActive = drawHits(canvas) || playing;
        drawHud(canvas);
        // Noise zones go on last. The official zone is composited after the whole forward pass,
        // so it covers the keys, the bursts and the counters alike - that covering is the point.
        if (showNoise && chart.blockAreas.length != 0) {
            canvas.save();
            canvas.clipRect(viewport);
            noiseField.draw(canvas, chart.blockAreas, time, viewport, BlockArea.ACTIVE);
            for (int i = 0; i < touches.size(); i++) {
                Touch touch = touches.valueAt(i);
                if (blocked(touch.x, touch.y)) noiseField.touch(canvas, touch.x, touch.y, viewport);
            }
            canvas.restore();
        }
        if (animationActive) scheduleFrame();
    }

    /**
     * The song's own cover behind the chart: centre-cropped to fill the stage the way an album
     * backdrop would, at {@link #COVER_ALPHA} so the keys coming down the middle stay the things
     * seen first. Songs without a cover never reach here - the plain colour stands in.
     */
    private void drawCover(Canvas canvas) {
        int width = getWidth(), height = getHeight();
        int coverWidth = cover.getWidth(), coverHeight = cover.getHeight();
        float scale = Math.max(width / (float) coverWidth, height / (float) coverHeight);
        int cropWidth = (int) (width / scale), cropHeight = (int) (height / scale);
        int left = (coverWidth - cropWidth) / 2, top = (coverHeight - cropHeight) / 2;
        coverSource.set(left, top, left + cropWidth, top + cropHeight);
        coverBox.set(0, 0, width, height);
        paint.setAlpha(COVER_ALPHA);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(cover, coverSource, coverBox, paint);
        paint.setFilterBitmap(false);
        paint.setAlpha(255);
    }

    /**
     * What the stage writes over the chart. The combo is the one number a player watches, so it
     * takes the middle of the top edge - the word above, the count below - instead of a slot in a
     * line of text over in a corner. The song's name and its difficulty sit in the bottom corners,
     * clear of the keys coming down the middle of the stage.
     *
     * Each part is a setting rather than a fixture: a player who knows the chart does not need to
     * be told its name again, and one who is recording the screen does not want a counter over the
     * picture. Turning one off hides only that part - the combo still counts, it stops being said.
     * The top-left line leads with the playback speed, and the speed is playback state rather than
     * a tally, so it leads whatever the switches hide.
     */
    private void drawHud(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        // The speed first, always: a take slowed to half has to say so even with the tallies off.
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(12));
        paint.setTextAlign(Paint.Align.LEFT);
        String topLeft = AudioEngine.speedLabel(playbackSpeed);
        if (showJudge) topLeft += (autoplay ? "  AUTO" : "  手动")
            + "   P " + perfect + "  G " + good + "  B " + bad + "  M " + miss;
        canvas.drawText(topLeft, dp(12), dp(20), paint);
        if (showCombo) {
            paint.setTextAlign(Paint.Align.CENTER);
            // Grey word, white number: the word is a caption on the number, not a second reading.
            paint.setColor(LABEL);
            paint.setTextSize(dp(13));
            canvas.drawText("COMBO", getWidth() / 2f, dp(24), paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(dp(34));
            paint.setFakeBoldText(true);
            canvas.drawText(String.valueOf(combo), getWidth() / 2f, dp(58), paint);
            paint.setFakeBoldText(false);
        }
        if (showSong) {
            float bottom = getHeight() - dp(12);
            paint.setTextSize(dp(14));
            paint.setColor(LABEL);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(songName == null ? "" : songName, dp(12), bottom, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(songLevel == null ? "" : songLevel, getWidth() - dp(12), bottom, paint);
        }
        if (!playing && showHint) {
            // Above the song's own line, so the two never land on top of each other.
            paint.setColor(0xFF98ABC1);
            paint.setTextSize(dp(14));
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("点击播放开始练习 · 蓝点按 / 黄滑动 / 长条长按 / 粉键任意方向滑动",
                dp(12), getHeight() - dp(30), paint);
            canvas.drawText("判定环：黄=P  蓝=G · 金边白边=双押", dp(12), getHeight() - dp(50), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }
    /** Draws the live bursts; true while one is still animating and wants the next frame. */
    private boolean drawHits(Canvas canvas) {
        return bursts.draw(canvas, fallback, viewport.width() * NOTE_HALF * 2,
            Math.max(viewport.height(), viewport.width()) * 0.035f);
    }
    private float dp(int value) { return value * getResources().getDisplayMetrics().density; }
}
