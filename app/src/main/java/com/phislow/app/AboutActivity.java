// SPDX-License-Identifier: GPL-3.0-only
package com.phislow.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.util.Linkify;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Offline attribution and the full program licence, available in both APK variants. */
public final class AboutActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));

        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "关于与开源许可", 20, Ui.INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        Button back = Ui.button(this, "← 返回", false, view -> finish());
        back.setContentDescription("返回设置");
        header.addView(back, Ui.sized(this, 88, 38, 0));
        root.addView(header);

        LinearLayout content = Ui.column(this);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addText(content, readAsset("NOTICE.txt"), 14, false);
        addText(content, "GNU GPL v3 · 完整许可文本", 18, true);
        addText(content, readAsset("GPL-3.0.txt"), 13, false);
        setContentView(root);
        Immersive.apply(getWindow());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Immersive.apply(getWindow());
    }

    private void addText(LinearLayout content, String text, float size, boolean heading) {
        TextView label = Ui.text(this, text, size, Ui.INK);
        label.setTextIsSelectable(true);
        label.setLineSpacing(Ui.dp(this, 3), 1);
        if (heading) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        Linkify.addLinks(label, Linkify.WEB_URLS);
        label.setLinkTextColor(Ui.ACCENT);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = Ui.dp(this, 12);
        layout.bottomMargin = Ui.dp(this, 12);
        content.addView(label, layout);
    }

    private String readAsset(String name) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("licenses/" + name), StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
            return text.toString();
        } catch (IOException error) {
            return "许可文件读取失败：" + name;
        }
    }
}
