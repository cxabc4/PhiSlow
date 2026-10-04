package com.phislow.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The workbench's settings: what the practice stage writes over the chart, and how far off a hit
 * may be and still count. The display switches take nothing away but the number - the combo still
 * counts, it simply stops being announced - while the judgement windows change the judgement
 * itself, which is exactly what they are for.
 *
 * Each row is a switch over a stored preference, and the change lands the moment it is flipped:
 * the workbench re-reads the settings when it is returned to, so no restart is needed to see it.
 */
public final class SettingsActivity extends Activity {
    private static final int INK = Color.rgb(231, 240, 250), MUTED = Color.rgb(142, 162, 181);
    private static final int PANEL = Color.rgb(17, 26, 37);
    private static final int ACCENT = Ui.ACCENT, IDLE = Ui.IDLE, ON_ACCENT = Ui.ON_ACCENT;

    /** One setting: its name, what it governs, and how it is read and written. */
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "设置", 20, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        Button back = Ui.button(this, "← 返回", false, view -> finish());
        back.setContentDescription("返回选曲界面");
        header.addView(back, Ui.sized(this, 88, 38, 0));
        root.addView(header);

        TextView hint = Ui.text(this, "开关控制画面显示；「判定窗口」改变时间判定，「按键宽度」改变按键显示大小。", 12, MUTED);
        LinearLayout.LayoutParams hintSize = new LinearLayout.LayoutParams(-1, -2);
        hintSize.bottomMargin = Ui.dp(this, 12);
        root.addView(hint, hintSize);

        root.addView(row("COMBO 计数", "顶部中央显示 COMBO 和连击数", Settings.COMBO), rowSize());
        root.addView(row("判定统计", "左上角显示 P / G / B / M，倍速仍常显", Settings.JUDGE), rowSize());
        root.addView(row("曲名与难度", "左下角曲名，右下角难度", Settings.SONG), rowSize());
        root.addView(row("操作提示", "停止播放时显示按键与判定说明", Settings.HINT), rowSize());
        root.addView(judgementRow(), rowSize());
        root.addView(widthRow(), rowSize());
        root.addView(backgroundRow(), rowSize());
        root.addView(frameRateRow(), rowSize());
        root.addView(scrollSpeedRow(), rowSize());
        root.addView(noiseRow(), rowSize());
        root.addView(Ui.button(this, "关于与开源许可", false,
            view -> startActivity(new Intent(this, AboutActivity.class))), rowSize());
    }

    private LinearLayout noiseRow() {
        LinearLayout line = Ui.row(this);
        line.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        line.setBackground(Ui.card(this, PANEL, 12));
        line.addView(Ui.text(this, "噪域演出与触点拦截", 14, INK),
            new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setContentDescription("开关 噪域演出与触点拦截");
        toggle.setChecked(Settings.showNoise(this));
        toggle.setOnCheckedChangeListener((view, checked) -> Settings.showNoise(this, checked));
        line.addView(toggle);
        return line;
    }

    private LinearLayout.LayoutParams rowSize() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = Ui.dp(this, 8);
        return size;
    }

    /**
     * One setting: its name and what it governs on the left, the switch on the right. The whole row
     * carries a description naming the setting, so it can be found and flipped by what it is rather
     * than by where it happens to sit.
     */
    private LinearLayout row(final String name, String note, final int which) {
        LinearLayout line = Ui.row(this);
        line.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        line.setBackground(Ui.card(this, PANEL, 12));

        LinearLayout labels = Ui.column(this);
        TextView heading = Ui.text(this, name, 14, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        labels.addView(Ui.text(this, note, 11, MUTED), new LinearLayout.LayoutParams(-1, -2));
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        Switch toggle = new Switch(this);
        toggle.setContentDescription("开关 " + name);
        toggle.setChecked(Settings.read(this, which));
        toggle.setOnCheckedChangeListener((view, checked) -> Settings.write(this, which, checked));
        line.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        // Tapping the name is the same as reaching for the switch: fewer pixels to aim at.
        line.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
        return line;
    }

    /**
     * The judgement windows: three choices on one card, shared by every key type. 严判 and 常规
     * are fixed windows; 宽判 widens 常规 as the take slows, so a 0.5× practice judges against
     * ±120ms and a 0.1× one against ±152ms instead of the ±80ms a full-speed take gets. The
     * choice lands the moment it is tapped, and the one in force reads as the speed chips do:
     * filled, with dark text.
     */
    private LinearLayout judgementRow() {
        LinearLayout line = Ui.column(this);
        line.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        line.setBackground(Ui.card(this, PANEL, 12));

        TextView heading = Ui.text(this, "判定窗口", 14, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        line.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        line.addView(Ui.text(this, "严判 ±40ms · 常规 ±80ms · 宽判随慢速放宽", 11, MUTED),
            new LinearLayout.LayoutParams(-1, -2));

        LinearLayout choices = Ui.row(this);
        LinearLayout.LayoutParams choicesSize = new LinearLayout.LayoutParams(-1, -2);
        choicesSize.topMargin = Ui.dp(this, 8);
        line.addView(choices, choicesSize);
        int[] modes = {Settings.JUDGE_STRICT, Settings.JUDGE_NORMAL, Settings.JUDGE_WIDE};
        String[] names = {"严判", "常规", "宽判"};
        Button[] buttons = new Button[modes.length];
        for (int index = 0; index < modes.length; index++) {
            final int mode = modes[index];
            Button choice = Ui.button(this, names[index], false, view -> {
                Settings.judgeMode(this, mode);
                for (int other = 0; other < buttons.length; other++) styleJudgement(buttons[other], modes[other]);
            });
            choice.setContentDescription("判定窗口 " + names[index]);
            buttons[index] = choice;
            LinearLayout.LayoutParams choiceSize = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1);
            choiceSize.rightMargin = Ui.dp(this, 6);
            choices.addView(choice, choiceSize);
        }
        for (int index = 0; index < buttons.length; index++) styleJudgement(buttons[index], modes[index]);
        return line;
    }

    /** Filled accent for the window in force, plain panel grey for the others. */
    private void styleJudgement(Button button, int mode) {
        boolean selected = Settings.judgeMode(this) == mode;
        button.setTextColor(selected ? ON_ACCENT : INK);
        button.setBackground(Ui.card(this, selected ? ACCENT : IDLE, 12));
    }

    /**
     * The keys' display width: a slider from the base width up to two and a half times it, the
     * default being the base widened by half. The change lands as the thumb moves, and the hit
     * range moves with the drawing, so what you see is what you can hit at any width.
     */
    private LinearLayout widthRow() {
        LinearLayout line = Ui.column(this);
        line.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        line.setBackground(Ui.card(this, PANEL, 12));

        LinearLayout heading = Ui.row(this);
        TextView name = Ui.text(this, "按键宽度", 14, INK);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = Ui.text(this, widthText(Settings.noteScalePct(this)), 13, ACCENT);
        heading.addView(value, new LinearLayout.LayoutParams(-2, -2));
        line.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        line.addView(Ui.text(this, "按键显示大小 " + Settings.NOTE_SCALE_MIN + "%–" + Settings.NOTE_SCALE_MAX
            + "%，命中范围固定，不随显示大小变化", 11, MUTED), new LinearLayout.LayoutParams(-1, -2));

        SeekBar seek = new SeekBar(this);
        seek.setMax(Settings.NOTE_SCALE_MAX - Settings.NOTE_SCALE_MIN);
        seek.setProgress(Settings.noteScalePct(this) - Settings.NOTE_SCALE_MIN);
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        seek.setContentDescription("按键宽度调节");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int pct = progress + Settings.NOTE_SCALE_MIN;
                Settings.noteScalePct(SettingsActivity.this, pct);
                value.setText(widthText(pct));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout.LayoutParams seekSize = new LinearLayout.LayoutParams(-1, -2);
        seekSize.topMargin = Ui.dp(this, 4);
        line.addView(seek, seekSize);
        return line;
    }

    /** The width as a plain percentage. */
    private static String widthText(int pct) {
        return pct + "%";
    }

    /**
     * The stage backdrop: a plain colour or the song's own cover art, dimmed so the keys coming
     * down the middle stay the things seen first. The choice lands the moment it is tapped, and a
     * song without a cover falls back to the colour whatever is picked here.
     */
    private LinearLayout backgroundRow() {
        LinearLayout line = Ui.column(this);
        line.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        line.setBackground(Ui.card(this, PANEL, 12));

        TextView heading = Ui.text(this, "背景", 14, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        line.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        line.addView(Ui.text(this, "纯色，或曲绘降低透明度垫在谱面下（无曲绘的曲目用纯色）", 11, MUTED),
            new LinearLayout.LayoutParams(-1, -2));

        LinearLayout choices = Ui.row(this);
        LinearLayout.LayoutParams choicesSize = new LinearLayout.LayoutParams(-1, -2);
        choicesSize.topMargin = Ui.dp(this, 8);
        line.addView(choices, choicesSize);
        int[] modes = {Settings.BACKGROUND_SOLID, Settings.BACKGROUND_COVER};
        String[] names = {"纯色", "曲绘"};
        Button[] buttons = new Button[modes.length];
        for (int index = 0; index < modes.length; index++) {
            final int mode = modes[index];
            Button choice = Ui.button(this, names[index], false, view -> {
                Settings.backgroundMode(this, mode);
                for (int other = 0; other < buttons.length; other++) styleBackground(buttons[other], modes[other]);
            });
            choice.setContentDescription("背景 " + names[index]);
            buttons[index] = choice;
            LinearLayout.LayoutParams choiceSize = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1);
            choiceSize.rightMargin = Ui.dp(this, 6);
            choices.addView(choice, choiceSize);
        }
        for (int index = 0; index < buttons.length; index++) styleBackground(buttons[index], modes[index]);
        return line;
    }

    /** Filled accent for the backdrop in force, plain panel grey for the other. */
    private void styleBackground(Button button, int mode) {
        boolean selected = Settings.backgroundMode(this) == mode;
        button.setTextColor(selected ? ON_ACCENT : INK);
        button.setBackground(Ui.card(this, selected ? ACCENT : IDLE, 12));
    }

    /**
     * The chart's frame rate: a cap or the raw vsync. The chart canvas paces itself against this,
     * so a 90/120Hz screen is not forced to choose between the display's rate and nothing.
     */
    private LinearLayout frameRateRow() {
        LinearLayout line = Ui.column(this);
        line.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        line.setBackground(Ui.card(this, PANEL, 12));

        TextView heading = Ui.text(this, "帧率", 14, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        line.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        line.addView(Ui.text(this, "谱面画布的渲染上限；无上限即跟随屏幕垂直同步", 11, MUTED),
            new LinearLayout.LayoutParams(-1, -2));

        LinearLayout choices = Ui.row(this);
        LinearLayout.LayoutParams choicesSize = new LinearLayout.LayoutParams(-1, -2);
        choicesSize.topMargin = Ui.dp(this, 8);
        line.addView(choices, choicesSize);
        int[] modes = Settings.FRAME_RATES;
        Button[] buttons = new Button[modes.length];
        for (int index = 0; index < modes.length; index++) {
            final int mode = modes[index];
            Button choice = Ui.button(this, mode == 0 ? "无上限" : Integer.toString(mode), false, view -> {
                Settings.frameRate(this, mode);
                for (int other = 0; other < buttons.length; other++) styleFrameRate(buttons[other], modes[other]);
            });
            choice.setContentDescription("帧率 " + (mode == 0 ? "无上限" : Integer.toString(mode)));
            buttons[index] = choice;
            LinearLayout.LayoutParams choiceSize = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1);
            choiceSize.rightMargin = Ui.dp(this, 6);
            choices.addView(choice, choiceSize);
        }
        for (int index = 0; index < buttons.length; index++) styleFrameRate(buttons[index], modes[index]);
        return line;
    }

    /** Filled accent for the rate in force, plain panel grey for the others. */
    private void styleFrameRate(Button button, int fps) {
        boolean selected = Settings.frameRate(this) == fps;
        button.setTextColor(selected ? ON_ACCENT : INK);
        button.setBackground(Ui.card(this, selected ? ACCENT : IDLE, 12));
    }

    private LinearLayout scrollSpeedRow() {
        LinearLayout line = Ui.column(this);
        line.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        line.setBackground(Ui.card(this, PANEL, 12));
        TextView heading = Ui.text(this, "流速（实验性）", 14, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        line.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        line.addView(Ui.text(this, "慢放时可保持 1× 的音符流速；音乐与判定仍跟随倍速", 11, MUTED),
            new LinearLayout.LayoutParams(-1, -2));

        LinearLayout choices = Ui.row(this);
        LinearLayout.LayoutParams choicesSize = new LinearLayout.LayoutParams(-1, -2);
        choicesSize.topMargin = Ui.dp(this, 8);
        line.addView(choices, choicesSize);
        boolean[] modes = {false, true};
        String[] names = {"随倍速降低", "不降低"};
        Button[] buttons = new Button[modes.length];
        for (int index = 0; index < modes.length; index++) {
            final boolean keepSpeed = modes[index];
            Button choice = Ui.button(this, names[index], false, view -> {
                Settings.keepScrollSpeed(this, keepSpeed);
                for (int other = 0; other < buttons.length; other++) styleScrollSpeed(buttons[other], modes[other]);
            });
            choice.setContentDescription("流速 " + names[index]);
            buttons[index] = choice;
            LinearLayout.LayoutParams choiceSize = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1);
            choiceSize.rightMargin = Ui.dp(this, 6);
            choices.addView(choice, choiceSize);
        }
        for (int index = 0; index < buttons.length; index++) styleScrollSpeed(buttons[index], modes[index]);
        return line;
    }

    private void styleScrollSpeed(Button button, boolean keepSpeed) {
        boolean selected = Settings.keepScrollSpeed(this) == keepSpeed;
        button.setSelected(selected);
        button.setTextColor(selected ? ON_ACCENT : INK);
        button.setBackground(Ui.card(this, selected ? ACCENT : IDLE, 12));
    }
}
