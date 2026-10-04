package com.phislow.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    /** Selection handed over by {@link SongSelectActivity}. */
    public static final String EXTRA_TITLE = "com.phislow.app.TITLE";
    public static final String EXTRA_SONG_ID = "com.phislow.app.SONG_ID";
    public static final String EXTRA_RANGE_START = "com.phislow.app.RANGE_START";
    public static final String EXTRA_RANGE_END = "com.phislow.app.RANGE_END";
    public static final String EXTRA_CHART = "com.phislow.app.CHART";
    public static final String EXTRA_COVER = "com.phislow.app.COVER";
    public static final String EXTRA_AUDIO_ASSET = "com.phislow.app.AUDIO_ASSET";
    public static final String EXTRA_SAMPLE = "com.phislow.app.SAMPLE";
    public static final String EXTRA_SPEED = "com.phislow.app.SPEED";
    public static final String EXTRA_AUTOPLAY = "com.phislow.app.AUTOPLAY";
    /** The chosen difficulty, shown at the bottom right of the stage and beside the song's name. */
    public static final String EXTRA_LEVEL = "com.phislow.app.LEVEL";
    private static final int PACK_REQUEST = 1;
    private static final int INK = Color.rgb(231, 240, 250), MUTED = Color.rgb(142, 162, 181);
    private static final int ACCENT = Color.rgb(92, 225, 207), PANEL = Color.rgb(17, 26, 37);
    private static final float[] SPEEDS = AudioEngine.SPEEDS;
    private final Handler handler = new Handler();
    /** Every speed preset button on both screens, restyled by selection in {@link #render}. */
    private final List<Button> speedChips = new ArrayList<>();
    private AudioEngine audio;
    private Favorites favorites;
    private String songId, chartPath;
    private int pendingRangeStart = -1, pendingRangeEnd = -1;
    private TimelineView timeline;
    private PracticeView practice;
    private NoteSkin skin;
    /** Decodes the current song's cover for the stage backdrop; cached and downsampled. */
    private CoverLoader covers;
    /** Cached so the render loop never touches the filesystem. */
    private boolean packInstalled;
    /** How late the sound arrives; the sample's beat ruler is drawn on the heard clock. */
    private int audioLatencyMs;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private int loadToken;
    private boolean chartLoading, engaged, playMode, autostart;
    private FrameLayout shell, stage, playHud;
    private LinearLayout root, workspace;
    /** Slot the stage returns to inside the workbench card, so timeline/clock keep their order. */
    private int stageSlot;
    private TextView footer;
    private Switch autoplay;
    private Button backButton;
    private TextView track, status, clock, speedLabel;
    private Button play, restart;
    private Button packButton;
    private SeekBar scrubber;
    private boolean dragging, resumeAfterDrag;
    private FrameLayout pauseOverlay;
    private View pauseScrim;
    private LinearLayout pauseCard, pauseControls, pausedRangeRows;
    private TextView pausedRangeSummary;
    /** Card parts that step aside (in place) while the scrubber drags, so the chart shows. */
    private final List<View> pauseChrome = new ArrayList<>();
    private TextView overlayTrack, overlayClock, overlayRange;
    private Button overlayResume;
    private Button saveRange;
    private SeekBar overlayScrubber;
    private boolean draggingOverlay, resumeAfterOverlayDrag;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); }
    };
    /**
     * Entering a song starts playback on the next main-thread turn. Posting (instead of calling
     * from {@code render}) matters: play() re-enters render through the engine listener, and the
     * outer pass would then write stale "paused" state over the fresh one.
     */
    private final Runnable autostartPlayback = new Runnable() {
        @Override public void run() {
            autostart = false;
            if (!isFinishing()) startPlayback();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        favorites = new Favorites(this);
        buildUi();
        // The pack lives in internal storage, so it is re-read on every entry to the workbench.
        skin = NoteSkin.load(this);
        practice.setSkin(skin);
        // The bundled art is the default look and the answer for any key the pack does not supply.
        // It is several megabytes of bitmaps, so it is decoded off the main thread; until it lands
        // the keys are drawn in code, exactly as they always were.
        loader.execute(() -> {
            final NoteSkin bundled = NoteSkin.bundled(this);
            runOnUiThread(() -> { if (!isFinishing()) practice.setFallbackSkin(bundled); });
        });
        refreshPackButton();
        covers = new CoverLoader(this);
        // After buildUi: the decor view has to exist before the window has an insets controller.
        Immersive.apply(getWindow());
        audio = new AudioEngine(this, message -> {
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            render();
        });
        practice.setPlaybackClock(audio);
        applyRequest(getIntent());
    }

    /**
     * Opens whatever {@link SongSelectActivity} asked for, falling back to the sample. The key
     * artwork is not part of the request: it is a stored setting, applied at creation.
     */
    private void applyRequest(Intent intent) {
        float speed = intent.getFloatExtra(EXTRA_SPEED, 1f);
        boolean auto = intent.getBooleanExtra(EXTRA_AUTOPLAY, false);
        String chart = intent.getStringExtra(EXTRA_CHART);
        songId = intent.getStringExtra(EXTRA_SONG_ID);
        chartPath = chart;
        pendingRangeStart = intent.getIntExtra(EXTRA_RANGE_START, -1);
        pendingRangeEnd = intent.getIntExtra(EXTRA_RANGE_END, -1);
        if (chart != null) {
            loadChart(intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_LEVEL), chart,
                intent.getStringExtra(EXTRA_COVER), intent.getStringExtra(EXTRA_AUDIO_ASSET));
        } else {
            loadSample();
        }
        audio.setSpeed(speed);
        autoplay.setChecked(auto);
        render();
    }

    private void buildUi() {
        shell = new FrameLayout(this);
        shell.setBackgroundColor(Color.rgb(9, 17, 27));
        root = column();
        root.setPadding(dp(20), dp(14), dp(20), dp(8));
        shell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        shell.addView(buildPauseOverlay(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
        LinearLayout header = row();
        TextView brand = text("PhiSlow", 24, INK);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(46), 1));
        TextView version = text("V0 / 练习工作台", 12, MUTED);
        header.addView(version, new LinearLayout.LayoutParams(dp(132), dp(46)));
        backButton = button("← 选曲", false, view -> finish());
        header.addView(backButton, sized(88, 42, 8));
        // Key artwork is managed on its own screen now: adding, applying and deleting all live
        // there, so the workbench header only opens it. The tick still reports which is in use.
        packButton = button("资源包", true, view -> openResourcePack());
        header.addView(packButton, sized(136, 42, 0));
        root.addView(header);

        LinearLayout body = row();
        LinearLayout.LayoutParams bodySize = new LinearLayout.LayoutParams(-1, 0, 1);
        bodySize.topMargin = dp(12);
        root.addView(body, bodySize);
        LinearLayout speedPanel = column();
        speedPanel.setPadding(dp(16), dp(12), dp(16), dp(12));
        speedPanel.setBackground(card(PANEL, 0));
        body.addView(speedPanel, new LinearLayout.LayoutParams(dp(210), -1));
        speedPanel.addView(text("训练速度", 13, MUTED));
        speedLabel = text("1.0×", 36, ACCENT);
        speedLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        speedPanel.addView(speedLabel);
        TextView pitch = text("保持音调 · 专注节奏", 11, MUTED);
        LinearLayout.LayoutParams pitchSize = new LinearLayout.LayoutParams(-1, dp(30));
        speedPanel.addView(pitch, pitchSize);
        for (int index = 0; index < SPEEDS.length; index += 3) {
            LinearLayout speedRow = row();
            for (int col = 0; col < 3 && index + col < SPEEDS.length; col++) {
                LinearLayout.LayoutParams chipSize = new LinearLayout.LayoutParams(0, dp(38), 1);
                chipSize.setMargins(0, 0, dp(4), dp(6));
                speedRow.addView(speedChip(SPEEDS[index + col]), chipSize);
            }
            speedPanel.addView(speedRow);
        }

        workspace = column();
        workspace.setPadding(dp(18), dp(10), dp(18), dp(12));
        workspace.setBackground(card(PANEL, 0));
        LinearLayout.LayoutParams workSize = new LinearLayout.LayoutParams(0, -1, 1);
        workSize.leftMargin = dp(12);
        body.addView(workspace, workSize);
        LinearLayout titleRow = row();
        track = text("原创节拍示例", 18, INK);
        track.setSingleLine(true);
        track.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(track, new LinearLayout.LayoutParams(0, dp(32), 1));
        status = text("已暂停", 12, ACCENT);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        titleRow.addView(status, new LinearLayout.LayoutParams(dp(84), dp(32)));
        workspace.addView(titleRow);
        timeline = new TimelineView(this);
        workspace.addView(timeline, new LinearLayout.LayoutParams(-1, 0, 1));
        /*
         * The stage owns the chart canvas and the playing HUD. It lives in this card while paused
         * and is re-parented into the window while playing, which is what makes playback truly
         * full screen: the canvas is measured against the window, not against a padded card.
         * Re-parenting the container (not the canvas) keeps the canvas instance - and with it the
         * judgement state - intact across pause/resume.
         */
        stageSlot = workspace.getChildCount();
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.rgb(9, 17, 27));
        stage.setContentDescription("谱面舞台");
        practice = new PracticeView(this);
        stage.addView(practice, new FrameLayout.LayoutParams(-1, -1));
        playHud = new FrameLayout(this);
        Button stagePause = circleButton("Ⅱ", false, view -> audio.pause());
        FrameLayout.LayoutParams pauseSize = new FrameLayout.LayoutParams(dp(56), dp(56));
        pauseSize.gravity = Gravity.TOP | Gravity.RIGHT;
        pauseSize.setMargins(0, dp(16), dp(20), 0);
        playHud.addView(stagePause, pauseSize);
        playHud.setVisibility(View.GONE);
        stage.addView(playHud, new FrameLayout.LayoutParams(-1, -1));
        workspace.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        clock = text("00:00.000 / 00:16.000", 16, INK);
        clock.setTypeface(Typeface.MONOSPACE);
        workspace.addView(clock, new LinearLayout.LayoutParams(-1, dp(28)));
        scrubber = new SeekBar(this);
        scrubber.setProgressTintList(ColorStateList.valueOf(ACCENT));
        scrubber.setThumbTintList(ColorStateList.valueOf(ACCENT));
        scrubber.setContentDescription("音频进度，可拖动定位练习起点");
        workspace.addView(scrubber, new LinearLayout.LayoutParams(-1, dp(32)));
        scrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                resumeAfterDrag = audio.isPlaying();
                audio.pause();
            }
            @Override public void onProgressChanged(SeekBar bar, int position, boolean fromUser) {
                if (fromUser) {
                    timeline.setPlayback(position - audioLatencyMs, audio.durationMs(), false, audio.speed());
                    clock.setText(time(position) + " / " + time(audio.durationMs()));
                    if (practice.chart() != null) practice.resetAt(position);
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (practice.chart() != null) practice.resetAt(bar.getProgress());
                audio.seek(bar.getProgress(), resumeAfterDrag);
            }
        });
        LinearLayout controls = row();
        play = button("▶ 播放", true, view -> {
            if (audio.isPlaying()) audio.pause(); else startPlayback();
        });
        restart = button("↺ 重试", false, view -> retryPractice());
        controls.addView(play, sized(104, 42, 8));
        controls.addView(restart, sized(90, 42, 8));
        autoplay = new Switch(this);
        autoplay.setText("Autoplay  "); autoplay.setTextColor(INK); autoplay.setTextSize(12);
        autoplay.setOnCheckedChangeListener((view, checked) -> {
            practice.setAutoplay(checked);
            if (checked && audio != null && audio.isReady()) startPlayback();
        });
        controls.addView(autoplay, new LinearLayout.LayoutParams(-2, dp(42)));
        workspace.addView(controls);
        footer = text("内置曲库选择歌曲与难度 · Autoplay 自动演示 · 关闭后触屏练习", 11, MUTED);
        footer.setSingleLine(true);
        footer.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(26)));
    }

    /**
     * Pause menu shown whenever playback is stopped, mirroring the reference build's pause bar
     * (three centred circular actions: back / retry / resume) plus the extra practice-range
     * controls this build adds: park the playhead, then mark its start and end. Speed lives here
     * too, because a slower take is chosen mid-practice, not from the workbench panel the menu
     * sits over.
     *
     * While the scrubber drags, the menu steps aside without moving - its chrome goes invisible
     * in place, so the thumb never loses the bar - and the scrim lifts: the chart behind redraws
     * at the dragged position, which is the picture the seek commits to on release.
     */
    private View buildPauseOverlay() {
        pauseOverlay = new FrameLayout(this);
        pauseOverlay.setContentDescription("暂停界面");
        pauseOverlay.setVisibility(View.GONE);
        pauseScrim = new View(this);
        // Light enough that the paused chart stays readable behind the menu; dragged lighter
        // still, see setPreviewChrome.
        pauseScrim.setBackgroundColor(0xB309111B);
        pauseOverlay.addView(pauseScrim, new FrameLayout.LayoutParams(-1, -1));

        pauseCard = row();
        pauseCard.setPadding(dp(26), dp(16), dp(26), dp(16));
        pauseCard.setBackground(card(PANEL, 0));
        int cardWidth = Math.min(dp(960), Math.max(dp(1), getResources().getDisplayMetrics().widthPixels - dp(32)));
        FrameLayout.LayoutParams cardSize = new FrameLayout.LayoutParams(cardWidth, -2);
        cardSize.gravity = Gravity.CENTER;
        pauseOverlay.addView(pauseCard, cardSize);

        int panelWidth = Math.min(dp(290), Math.max(dp(170), (cardWidth - dp(64)) * 34 / 100));
        pauseCard.addView(buildPausedRangePanel(), new LinearLayout.LayoutParams(panelWidth, dp(420)));
        pauseControls = column();
        LinearLayout.LayoutParams controlsSize = new LinearLayout.LayoutParams(0, -2, 1);
        controlsSize.leftMargin = dp(12);
        pauseCard.addView(pauseControls, controlsSize);

        TextView title = text("暂停", 26, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pauseControls.addView(title, new LinearLayout.LayoutParams(-1, dp(36)));
        pauseChrome.add(title);
        overlayTrack = text("", 13, MUTED);
        overlayTrack.setSingleLine(true);
        overlayTrack.setEllipsize(TextUtils.TruncateAt.END);
        pauseControls.addView(overlayTrack, new LinearLayout.LayoutParams(-1, dp(24)));
        pauseChrome.add(overlayTrack);

        overlayClock = text("00:00.000 / 00:00.000", 18, INK);
        overlayClock.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams clockSize = new LinearLayout.LayoutParams(-1, dp(28));
        clockSize.topMargin = dp(6);
        pauseControls.addView(overlayClock, clockSize);

        overlayScrubber = new SeekBar(this);
        overlayScrubber.setProgressTintList(ColorStateList.valueOf(ACCENT));
        overlayScrubber.setThumbTintList(ColorStateList.valueOf(ACCENT));
        overlayScrubber.setContentDescription("暂停时拖动定位，用于设置练习区间");
        pauseControls.addView(overlayScrubber, new LinearLayout.LayoutParams(-1, dp(32)));
        overlayScrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                draggingOverlay = true;
                resumeAfterOverlayDrag = audio.isPlaying();
                setPreviewChrome(true);
                audio.pause();
            }
            @Override public void onProgressChanged(SeekBar bar, int position, boolean fromUser) {
                if (!fromUser) return;
                overlayClock.setText(time(position) + " / " + time(audio.durationMs()));
                // Live preview: the chart behind redraws at the dragged position, so the segment
                // being parked on is chosen by eye, not by the number alone.
                if (practice.chart() != null) practice.resetAt(position);
                timeline.setPlayback(position - audioLatencyMs, audio.durationMs(), false, audio.speed());
                clock.setText(time(position) + " / " + time(audio.durationMs()));
                scrubber.setProgress(position);
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                draggingOverlay = false;
                setPreviewChrome(false);
                if (practice.chart() != null) practice.resetAt(bar.getProgress());
                audio.seek(bar.getProgress(), resumeAfterOverlayDrag);
            }
        });

        // One scrollable strip keeps every preset on the menu without stretching the card past
        // the height of a landscape phone; the freed room comes from the parts above and below.
        LinearLayout speedLine = row();
        speedLine.addView(text("速度", 13, MUTED), new LinearLayout.LayoutParams(-2, dp(30)));
        HorizontalScrollView speedScroll = new HorizontalScrollView(this);
        speedScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        for (float value : SPEEDS) {
            LinearLayout.LayoutParams chipSize = new LinearLayout.LayoutParams(dp(64), dp(30));
            chipSize.rightMargin = dp(4);
            chips.addView(speedChip(value), chipSize);
        }
        speedScroll.addView(chips);
        speedLine.addView(speedScroll, new LinearLayout.LayoutParams(0, dp(30), 1));
        LinearLayout.LayoutParams speedSize = new LinearLayout.LayoutParams(-1, dp(30));
        speedSize.topMargin = dp(8);
        pauseControls.addView(speedLine, speedSize);
        pauseChrome.add(speedLine);

        LinearLayout rangePanel = column();
        rangePanel.setPadding(dp(14), dp(10), dp(14), dp(10));
        rangePanel.setBackground(card(Color.rgb(12, 20, 31), 0));
        LinearLayout.LayoutParams rangeSize = new LinearLayout.LayoutParams(-1, -2);
        rangeSize.topMargin = dp(8);
        pauseControls.addView(rangePanel, rangeSize);
        pauseChrome.add(rangePanel);
        LinearLayout rangeHeader = row();
        rangeHeader.addView(text("练习区间（可选）", 13, MUTED), new LinearLayout.LayoutParams(0, dp(24), 1));
        overlayRange = text("未设置", 13, ACCENT);
        overlayRange.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        rangeHeader.addView(overlayRange, new LinearLayout.LayoutParams(dp(250), dp(24)));
        rangePanel.addView(rangeHeader);
        LinearLayout rangeActions = row();
        LinearLayout.LayoutParams actionsSize = new LinearLayout.LayoutParams(-1, dp(38));
        actionsSize.topMargin = dp(4);
        rangePanel.addView(rangeActions, actionsSize);
        rangeActions.addView(button("设为起点", false, view -> setRangeStart()), fill(6));
        rangeActions.addView(button("设为终点", false, view -> setRangeEnd()), fill(6));
        rangeActions.addView(button("清除区间", false, view -> audio.clearPracticeRange()), fill(6));
        saveRange = button("收藏区间", false, view -> promptRangeName());
        saveRange.setContentDescription("收藏当前练习区间");
        rangeActions.addView(saveRange, fill(6));
        rangePanel.addView(text("播放到终点后自动暂停 · 不循环", 11, MUTED), new LinearLayout.LayoutParams(-1, dp(18)));

        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsRow = new LinearLayout.LayoutParams(-1, dp(76));
        actionsRow.topMargin = dp(12);
        pauseControls.addView(actions, actionsRow);
        pauseChrome.add(actions);
        actions.addView(circleButton("选曲", false, view -> finish()), circleSize());
        actions.addView(circleButton("重试", false, view -> retryPractice()), circleSize());
        overlayResume = circleButton("继续", true, view -> startPlayback());
        actions.addView(overlayResume, circleSize());
        return pauseOverlay;
    }

    private View buildPausedRangePanel() {
        LinearLayout panel = column();
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        panel.setBackground(card(Color.rgb(12, 20, 31), 0));
        panel.addView(text("已收藏段落", 16, INK), new LinearLayout.LayoutParams(-1, dp(25)));
        pausedRangeSummary = text("当前谱面", 11, MUTED);
        panel.addView(pausedRangeSummary, new LinearLayout.LayoutParams(-1, dp(23)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        pausedRangeRows = column();
        scroll.addView(pausedRangeRows, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        pauseChrome.add(panel);
        return panel;
    }

    private void refreshPausedRangePanel() {
        if (pausedRangeRows == null) return;
        pausedRangeRows.removeAllViews();
        List<Favorites.SavedRange> ranges = new ArrayList<>();
        for (Favorites.SavedRange range : favorites.listRanges())
            if (range.songId.equals(songId) && range.chartPath.equals(chartPath)) ranges.add(range);
        pausedRangeSummary.setText(ranges.size() + " 段 · 当前谱面");
        if (ranges.isEmpty()) {
            TextView empty = text("暂无段落\n设置起点、终点后点“收藏区间”", 13, MUTED);
            empty.setGravity(Gravity.CENTER);
            pausedRangeRows.addView(empty, new LinearLayout.LayoutParams(-1, dp(108)));
            return;
        }
        for (Favorites.SavedRange range : ranges) {
            TextView item = text(range.name + "\n" + Ui.rangeLabel(range), 13, INK);
            item.setPadding(dp(10), 0, dp(10), 0);
            item.setBackground(card(Color.rgb(24, 36, 50), 0));
            item.setContentDescription("跳转到" + range.name + "，" + Ui.rangeLabel(range));
            item.setOnClickListener(view -> jumpToRange(range.startMs, range.endMs));
            item.setOnLongClickListener(view -> {
                confirmRemoveRange(range);
                return true;
            });
            LinearLayout.LayoutParams itemSize = new LinearLayout.LayoutParams(-1, dp(52));
            itemSize.bottomMargin = dp(6);
            pausedRangeRows.addView(item, itemSize);
        }
    }

    private void confirmRemoveRange(Favorites.SavedRange range) {
        new AlertDialog.Builder(this).setTitle("移除“" + range.name + "”？")
            .setMessage(Ui.rangeLabel(range))
            .setNegativeButton("取消", null)
            .setPositiveButton("移除", (dialog, which) -> {
                if (favorites.removeRange(range)) refreshPausedRangePanel();
                else Toast.makeText(this, "移除失败", Toast.LENGTH_SHORT).show();
            }).show();
    }

    /**
     * Drag preview: the scrim lifts and the card's own chrome goes invisible - in place, so the
     * card keeps its size and the scrubber stays under the finger. What is left is the clock, the
     * bar, and the chart behind drawing the dragged moment.
     */
    private void setPreviewChrome(boolean previewing) {
        pauseScrim.setBackgroundColor(previewing ? 0x4D09111B : 0xB309111B);
        pauseCard.setBackground(previewing ? null : card(PANEL, 0));
        for (View part : pauseChrome) part.setVisibility(previewing ? View.INVISIBLE : View.VISIBLE);
    }

    /** Marks the playhead as the practice start, keeping the range at least {@link AudioEngine#MIN_RANGE_MS}. */
    private void setRangeStart() {
        int end = audio.hasPracticeRange() ? audio.rangeEnd() : audio.durationMs();
        int start = Math.min(Math.max(0, audio.positionMs()), Math.max(0, end - AudioEngine.MIN_RANGE_MS));
        audio.setPracticeRange(start, end);
    }

    /** Marks the playhead as the practice end, keeping the range at least {@link AudioEngine#MIN_RANGE_MS}. */
    private void setRangeEnd() {
        int start = audio.hasPracticeRange() ? audio.rangeStart() : 0;
        int end = Math.max(audio.positionMs(), Math.min(audio.durationMs(), start + AudioEngine.MIN_RANGE_MS));
        audio.setPracticeRange(start, end);
    }

    private void promptRangeName() {
        if (songId == null || chartPath == null || !audio.hasPracticeRange()) return;
        int start = audio.rangeStart(), end = audio.rangeEnd();
        Favorites.SavedRange identity = new Favorites.SavedRange(songId, chartPath, start, end);
        String defaultName = favorites.nextRangeName();
        for (Favorites.SavedRange range : favorites.listRanges())
            if (range.equals(identity)) { defaultName = range.name; break; }
        final String suggestedName = defaultName;
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(32)});
        nameInput.setText(defaultName);
        nameInput.setSelection(nameInput.length());
        int padding = dp(24);
        nameInput.setPadding(padding, dp(8), padding, dp(8));
        new AlertDialog.Builder(this).setTitle("收藏区间 · 设置名称")
            .setView(nameInput)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (dialog, which) -> {
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty()) name = suggestedName;
                boolean saved = favorites.saveRange(songId, chartPath, start, end, name);
                Toast.makeText(this, saved ? "已保存“" + name + "”" : "名称已占用或保存失败", Toast.LENGTH_SHORT).show();
                if (saved) refreshPausedRangePanel();
            }).show();
    }

    /** Restoring a bookmark follows the same chart-reset and audio-seek path as the scrubber. */
    private void jumpToRange(int start, int end) {
        if (!audio.isReady() || audio.isSeeking()) return;
        autostart = false;
        handler.removeCallbacks(autostartPlayback);
        if (!audio.setPracticeRange(start, end)) return;
        audio.pause();
        practice.resetAt(audio.rangeStart());
        audio.seek(audio.rangeStart(), false);
        refreshPausedRangePanel();
    }

    /** Retry re-enters at the practice start when a range is set. */
    private void retryPractice() {
        int start = audio.hasPracticeRange() ? audio.rangeStart() : 0;
        if (practice.chart() != null) {
            practice.resetAt(start);
            engaged = true;
            setPlayMode(true);
        }
        audio.seek(start, true);
    }

    /** Starts or resumes playback; with a chart loaded that means taking over the whole window. */
    private void startPlayback() {
        if (audio == null || !audio.isReady()) return;
        if (practice.chart() != null) {
            engaged = true;
            setPlayMode(true);
        }
        resumePractice();
    }

    /** Resume; if the playhead sits outside the practice range it restarts at the range start. */
    private void resumePractice() {
        if (audio.needsRangeJump() && practice.chart() != null) practice.resetAt(audio.rangeStart());
        audio.play();
    }

    /**
     * Playing owns the whole window; stopping hands it back to the workbench, where the practice
     * range is set. Mirrors the reference build, where the song runs full screen and the pause
     * menu is the way back to the controls.
     */
    private void setPlayMode(boolean fullscreen) {
        if (playMode == fullscreen) return;
        playMode = fullscreen;
        ViewGroup parent = (ViewGroup) stage.getParent();
        if (parent != null) parent.removeView(stage);
        if (fullscreen) {
            // Index 1 keeps the stage below the pause menu, which is already a child of the shell.
            shell.addView(stage, 1, new FrameLayout.LayoutParams(-1, -1));
            root.setVisibility(View.GONE);
        } else {
            workspace.addView(stage, stageSlot, new LinearLayout.LayoutParams(-1, 0, 1));
            root.setVisibility(View.VISIBLE);
        }
        playHud.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
    }

    private void loadSample() {
        if (audio == null) return;
        try {
            boolean bundled = false;
            for (String path : getAssets().list("")) if (path.equals("practice-demo.wav")) bundled = true;
            if (!bundled) {
                Toast.makeText(this, "请先导入谱面和音乐，再选择曲目开始练习", Toast.LENGTH_LONG).show();
                finish(); return;
            }
        } catch (java.io.IOException error) { finish(); return; }
        loadToken++; chartLoading = false; autostart = false;
        setPlayMode(false);
        timeline.setVisibility(View.VISIBLE); stage.setVisibility(View.GONE);
        autoplay.setChecked(false);
        dragging = false; draggingOverlay = false; engaged = false;
        track.setText("原创节拍示例");
        practice.setSongInfo("原创节拍示例", null);
        practice.setCover(null);
        audio.load(null);
    }

    /** Opens the resource pack manager, where packs are added, applied and deleted. */
    private void openResourcePack() {
        audio.pause();
        startActivity(new Intent(this, PackManagerActivity.class));
    }

    /** The header button doubles as the status: a tick once an imported pack is in use. */
    private void refreshPackButton() {
        packInstalled = NoteSkin.isInstalled(this);
        packButton.setText(packInstalled ? "资源包 ✓" : "资源包");
        packButton.setContentDescription(packInstalled
            ? "已导入资源包，打开资源包管理" : "打开资源包管理");
    }

    private void render() {
        if (audio == null) return;
        if (pendingRangeStart >= 0 && pendingRangeEnd > pendingRangeStart
                && audio.isReady() && !chartLoading && !audio.isSeeking()) {
            int start = pendingRangeStart, end = pendingRangeEnd;
            pendingRangeStart = pendingRangeEnd = -1;
            jumpToRange(start, end);
            return;
        }
        boolean ready = audio.isReady(), playing = audio.isPlaying();
        boolean enabled = ready && !audio.isSeeking() && !dragging && !chartLoading;
        status.setText(chartLoading || audio.isLoading() ? "载入中…" : audio.isSeeking() ? "定位中…" : !ready ? "未载入" : playing ? "播放中" : "已暂停");
        play.setText(playing ? "Ⅱ 暂停" : "▶ 播放");
        play.setEnabled(enabled);
        restart.setEnabled(enabled);
        boolean canBookmark = enabled && !draggingOverlay && songId != null && chartPath != null;
        saveRange.setEnabled(canBookmark && audio.hasPracticeRange());
        scrubber.setEnabled(ready && !audio.isSeeking() && !chartLoading);
        practice.setEnabled(playing && !audio.isSeeking() && !dragging && !chartLoading);
        autoplay.setEnabled(enabled && stage.getVisibility() == View.VISIBLE);
        speedLabel.setText(speedText(audio.speed()));
        for (Button chip : speedChips) {
            boolean selected = (Float) chip.getTag() == audio.speed();
            chip.setTextColor(selected ? Color.rgb(9, 17, 27) : INK);
            chip.setBackground(card(selected ? ACCENT : Color.rgb(28, 41, 56), 0));
            chip.setSelected(selected);
        }
        if (playing) engaged = true;
        // Playback is full screen; the moment it stops the workbench comes back so the range can
        // be set. Seeks are excluded because seeking pauses and resumes underneath playback.
        boolean settling = audio.isSeeking() || audio.isLoading() || chartLoading;
        if (playMode && ready && !playing && !settling) setPlayMode(false);
        // A pending auto-start owns the next turn, so the pause menu must not flash before it.
        if (autostart && ready && !playing && !settling) {
            handler.removeCallbacks(autostartPlayback);
            handler.post(autostartPlayback);
        }
        boolean showPause = !playMode && !autostart && engaged && ready && !playing && !settling
            && !dragging;
        boolean enteringPause = showPause && pauseOverlay.getVisibility() != View.VISIBLE;
        pauseOverlay.setVisibility(showPause ? View.VISIBLE : View.GONE);
        if (enteringPause) refreshPausedRangePanel();
        if (showPause) {
            int pausePosition = audio.positionMs();
            overlayTrack.setText(track.getText());
            overlayClock.setText(time(pausePosition) + " / " + time(audio.durationMs()));
            overlayScrubber.setEnabled(!audio.isSeeking());
            overlayScrubber.setMax(Math.max(1, audio.durationMs()));
            if (!draggingOverlay) overlayScrubber.setProgress(pausePosition);
            overlayRange.setText(rangeText());
            overlayResume.setText(pausePosition <= 150 ? "开始" : "继续");
        }
        footer.setText(workspaceHint());
        // Either scrubber being dragged owns the playhead views - its handler previews the
        // dragged position - so a render pass must not write the audio's stale position over it.
        if (!dragging && !draggingOverlay && !audio.isSeeking()) {
            int position = audio.positionMs();
            scrubber.setMax(audio.durationMs());
            scrubber.setProgress(position);
            // The timeline is the sample's beat ruler, drawn on the heard clock: the latency says
            // how far behind the sound is, so the ruler runs that much ahead of the position.
            timeline.setPlayback(position - audioLatencyMs, audio.durationMs(), playing, audio.speed());
            if (stage.getVisibility() == View.VISIBLE && !audio.isSeeking() && !chartLoading)
                practice.setPlayback(position, playing, audio.speed());
            clock.setText(time(position) + " / " + time(audio.durationMs()));
        }
        if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(refresh);
        if (playing) handler.postDelayed(refresh, 33);
    }

    /**
     * The pack manager edits the pack list in place, so the artwork in use is re-read on the way
     * back rather than only at creation: a pack applied or deleted there shows up here at once.
     */
    @Override protected void onResume() {
        super.onResume();
        if (audio != null && practice != null) {
            skin = NoteSkin.load(this);
            practice.setSkin(skin);
            // The settings may have been changed while this screen was in the background.
            practice.reloadSettings();
            refreshPackButton();
            audioLatencyMs = Settings.audioLatencyMs(this);
        }
        handler.post(refresh);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Immersive.apply(getWindow());
    }
    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(autostartPlayback);
        autostart = false;
        resumeAfterDrag = false;
        resumeAfterOverlayDrag = false;
        draggingOverlay = false;
        if (pauseCard != null) setPreviewChrome(false);
        if (audio != null) audio.pause();
        super.onPause();
    }
    @Override protected void onDestroy() { loadToken++; loader.shutdownNow(); if (audio != null) audio.release(); super.onDestroy(); }

    /** Back out of the running chart pauses into the workbench instead of leaving the song. */
    @Override public void onBackPressed() {
        if (playMode && audio != null) {
            audio.pause();
            return;
        }
        super.onBackPressed();
    }

    private void loadChart(String title, String level, String chartPath, String coverPath, String audioAsset) {
        final int token = ++loadToken;
        chartLoading = true; dragging = false; draggingOverlay = false; engaged = false; autostart = false;
        setPlayMode(false); autoplay.setChecked(false); audio.release();
        track.setText(title == null ? "内置曲目" : level == null ? title : title + " / " + level);
        // The stage says which song and which difficulty is being played, so a recording of the
        // screen, or a player who has forgotten, can tell without going back to the song list.
        practice.setSongInfo(title == null ? "内置曲目" : title, level);
        render();
        loader.execute(() -> {
            try {
                Chart loaded = new Chart(SongLibrary.read(this, chartPath));
                // The cover decodes on this thread too: the stage needs it the moment the chart
                // lands, and a missing or undecodable one just leaves the plain colour behind.
                Bitmap cover = covers == null ? null : covers.get(coverPath,
                    getResources().getDisplayMetrics().widthPixels);
                runOnUiThread(() -> {
                    if (token != loadToken) return;
                    chartLoading = false; practice.setChart(loaded);
                    practice.setCover(cover);
                    timeline.setVisibility(View.GONE); stage.setVisibility(View.VISIBLE);
                    engaged = true;
                    if (audioAsset != null) audio.loadAsset(audioAsset); else audio.load(null);
                    // Entering a song plays straight away - full screen, at the chosen speed -
                    // as soon as the audio is prepared. See autostartPlayback.
                    autostart = pendingRangeStart < 0 || pendingRangeEnd <= pendingRangeStart;
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (token != loadToken) return;
                    chartLoading = false;
                    Toast.makeText(this, "谱面读取失败，请重新选择曲目", Toast.LENGTH_LONG).show(); render();
                });
            }
        });
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button view = new Button(this);
        view.setText(label); view.setTextSize(14); view.setAllCaps(false);
        view.setTextColor(primary ? Color.rgb(9, 17, 27) : INK);
        view.setBackground(card(primary ? ACCENT : Color.rgb(28, 41, 56), 0));
        view.setPadding(dp(4), 0, dp(4), 0); view.setMinWidth(0); view.setMinimumWidth(0);
        view.setMinHeight(0); view.setMinimumHeight(0); view.setOnClickListener(action);
        return view;
    }
    private GradientDrawable card(int color, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(12));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }
    private LinearLayout.LayoutParams sized(int width, int height, int rightMargin) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(width), dp(height));
        size.rightMargin = dp(rightMargin); return size;
    }
    private LinearLayout.LayoutParams fill(int rightMargin) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
        size.rightMargin = dp(rightMargin); return size;
    }
    private LinearLayout.LayoutParams circleSize() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(68), dp(68));
        size.leftMargin = dp(8); size.rightMargin = dp(8); return size;
    }

    /** One speed preset, shared by the workbench panel and the pause menu; render() styles it. */
    private Button speedChip(float value) {
        Button chip = button(speedText(value), false, view -> audio.setSpeed(value));
        chip.setTextSize(12);
        chip.setTag(value);
        speedChips.add(chip);
        return chip;
    }
    /** Plain circular action, matching the reference pause bar geometry (no shipped artwork). */
    private Button circleButton(String label, boolean primary, View.OnClickListener action) {
        Button view = new Button(this);
        view.setText(label); view.setTextSize(13); view.setAllCaps(false);
        view.setTextColor(primary ? Color.rgb(9, 17, 27) : INK);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(primary ? ACCENT : Color.rgb(28, 41, 56));
        view.setBackground(shape);
        view.setPadding(0, 0, 0, 0); view.setMinWidth(0); view.setMinimumWidth(0);
        view.setMinHeight(0); view.setMinimumHeight(0); view.setOnClickListener(action);
        return view;
    }
    private String rangeText() {
        if (!audio.hasPracticeRange()) return "未设置";
        return time(audio.rangeStart()) + " → " + time(audio.rangeEnd())
            + "  (" + time(audio.rangeEnd() - audio.rangeStart()) + ")";
    }
    /** The status line under the workbench: the practice range when set, plus the skin in use. */
    private String workspaceHint() {
        String hint = audio.hasPracticeRange()
            ? "练习区间 " + time(audio.rangeStart()) + " → " + time(audio.rangeEnd()) + " · 到达终点自动暂停"
            : "内置曲库选择歌曲与难度 · Autoplay 自动演示 · 关闭后触屏练习";
        return packInstalled ? hint + " · 按键美术：资源包（在“资源包”里可切换或删除）" : hint;
    }
    private static String speedText(float speed) { return AudioEngine.speedLabel(speed); }
    private static String time(int ms) { return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000); }
}
