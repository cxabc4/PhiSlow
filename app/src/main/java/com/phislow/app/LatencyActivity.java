package com.phislow.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The headphone menu: audio latency lives here, tuned against the bundled beat sample. A positive
 * value says the sound reaches the ears late - phone processing, Bluetooth headphones - and the
 * chart clock runs that much behind the audio position, so notes cross the judge line when they
 * are heard rather than when the position clock passes them. The sample on this screen is the
 * ruler: the beat grid sits on the heard beats exactly when the value is right.
 */
public final class LatencyActivity extends Activity {
    private static final int INK = Color.rgb(231, 240, 250), MUTED = Color.rgb(142, 162, 181);
    private static final int PANEL = Color.rgb(17, 26, 37);
    private static final int ACCENT = Ui.ACCENT;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        setContentView(root);

        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "耳机 · 延迟校准", 20, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        android.widget.Button back = Ui.button(this, "← 返回", false, view -> finish());
        back.setContentDescription("返回选曲界面");
        header.addView(back, Ui.sized(this, 88, 38, 0));
        root.addView(header);

        TextView hint = Ui.text(this, "戴着耳机播放节拍示例，对准节拍听感：音效来得越晚，数值越大。", 12, MUTED);
        LinearLayout.LayoutParams hintSize = new LinearLayout.LayoutParams(-1, -2);
        hintSize.bottomMargin = Ui.dp(this, 12);
        root.addView(hint, hintSize);

        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        card.setBackground(Ui.card(this, PANEL, 12));
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout heading = Ui.row(this);
        TextView name = Ui.text(this, "音频延迟", 14, INK);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = Ui.text(this, latencyText(Settings.audioLatencyMs(this)), 13, ACCENT);
        heading.addView(value, new LinearLayout.LayoutParams(-2, -2));
        card.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        card.addView(Ui.text(this, "音效比画面晚多少毫秒，谱面就整体推迟多少（"
            + Settings.AUDIO_LATENCY_MIN + " 至 +" + Settings.AUDIO_LATENCY_MAX + "ms）", 11, MUTED),
            new LinearLayout.LayoutParams(-1, -2));

        SeekBar seek = new SeekBar(this);
        seek.setMax(Settings.AUDIO_LATENCY_MAX - Settings.AUDIO_LATENCY_MIN);
        seek.setProgress(Settings.audioLatencyMs(this) - Settings.AUDIO_LATENCY_MIN);
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        seek.setContentDescription("音频延迟调节");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int latency = progress + Settings.AUDIO_LATENCY_MIN;
                Settings.audioLatencyMs(LatencyActivity.this, latency);
                value.setText(latencyText(latency));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout.LayoutParams seekSize = new LinearLayout.LayoutParams(-1, -2);
        seekSize.topMargin = Ui.dp(this, 4);
        card.addView(seek, seekSize);

        LinearLayout sample = Ui.column(this);
        sample.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        sample.setBackground(Ui.card(this, PANEL, 12));
        LinearLayout.LayoutParams sampleSize = new LinearLayout.LayoutParams(-1, -2);
        sampleSize.topMargin = Ui.dp(this, 10);
        root.addView(sample, sampleSize);

        TextView sampleName = Ui.text(this, "节拍示例", 14, INK);
        sampleName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sample.addView(sampleName, new LinearLayout.LayoutParams(-1, -2));
        sample.addView(Ui.text(this, "一段自制的 16 秒 120 BPM 节拍，边听边调上面的数值，节拍线对上听到的鼓点即为合适。", 11, MUTED),
            new LinearLayout.LayoutParams(-1, -2));
        android.widget.Button open = Ui.button(this, "打开节拍示例", true, view -> startActivity(
            new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_TITLE, "原创节拍示例")
                .putExtra(MainActivity.EXTRA_SAMPLE, true)));
        open.setContentDescription("打开节拍示例校准");
        LinearLayout.LayoutParams openSize = new LinearLayout.LayoutParams(-1, Ui.dp(this, 42));
        openSize.topMargin = Ui.dp(this, 8);
        sample.addView(open, openSize);
    }

    /** The latency in milliseconds, signed, the way it reads against the clock. */
    private static String latencyText(int latencyMs) {
        return (latencyMs > 0 ? "+" : "") + latencyMs + "ms";
    }
}
