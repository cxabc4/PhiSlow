// SPDX-License-Identifier: GPL-3.0-only
package com.phislow.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Imports user-selected chart and audio files into the app's persistent library. */
public final class ChartImportActivity extends Activity {
    public static final String EXTRA_SONG_ID = "song_id";
    private static final int PICK_ZIP = 1, PICK_CHART = 2, PICK_AUDIO = 3;
    private Uri source, audio;
    private String sourceName = "", audioName = "";
    private boolean zip;
    private EditText title;
    private TextView selection, status, titleLabel;
    private Button pickZip, pickChart, pickAudio, add;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            source = uri(state.getString("source")); audio = uri(state.getString("audio"));
            sourceName = state.getString("sourceName", ""); audioName = state.getString("audioName", "");
            zip = state.getBoolean("zip");
        }
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
        setContentView(root);
        LinearLayout header = Ui.row(this);
        header.addView(Ui.text(this, "导入谱面与音乐", 22, Ui.INK), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(Ui.button(this, "返回", false, view -> onBackPressed()), Ui.sized(this, 80, 40, 0));
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Ui.column(this);
        content.setPadding(0, Ui.dp(this, 14), 0, 0);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(Ui.text(this, "选择 ZIP / PEZ 谱面包，或分别选择谱面 JSON 和音乐。", 14, Ui.MUTED));
        content.addView(Ui.text(this, "目前支持 Phigros JSON v1 / v3；RPE、PEC 暂不支持。曲绘为可选内容。", 12, Ui.MUTED));
        LinearLayout choices = Ui.row(this);
        pickZip = Ui.button(this, "选择谱面包", true, view -> pick(PICK_ZIP));
        pickChart = Ui.button(this, "选择 JSON 谱面", false, view -> pick(PICK_CHART));
        choices.addView(pickZip, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
        LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
        other.leftMargin = Ui.dp(this, 10);
        choices.addView(pickChart, other);
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2);
        rowSize.topMargin = Ui.dp(this, 16); rowSize.bottomMargin = Ui.dp(this, 12);
        content.addView(choices, rowSize);
        pickAudio = Ui.button(this, "选择配套音乐", false, view -> pick(PICK_AUDIO));
        content.addView(pickAudio, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        selection = Ui.text(this, "", 14, Ui.INK);
        selection.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        content.addView(selection);
        titleLabel = Ui.text(this, "曲名（可修改）", 13, Ui.MUTED);
        content.addView(titleLabel);
        title = new EditText(this);
        title.setSingleLine(true); title.setTextColor(Ui.INK); title.setHintTextColor(Ui.MUTED);
        title.setHint("默认使用文件名"); title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        title.setBackground(Ui.card(this, Ui.IDLE, 10));
        title.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        if (state != null) title.setText(state.getString("title", ""));
        content.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));
        add = Ui.button(this, "导入", true, view -> importSelection());
        LinearLayout.LayoutParams addSize = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        addSize.topMargin = Ui.dp(this, 16);
        content.addView(add, addSize);
        status = Ui.text(this, "导入后可在选曲页打开，更新应用时会保留已导入的内容。", 12, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 10), 0, 0); status.setGravity(Gravity.LEFT);
        content.addView(status);
        render();
        Immersive.apply(getWindow());
    }

    private void pick(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, request);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri chosen = data.getData();
        if (request == PICK_AUDIO) {
            audio = chosen; audioName = displayName(chosen);
        } else if (request == PICK_ZIP || request == PICK_CHART) {
            String previousDefault = defaultTitle(sourceName);
            source = chosen; sourceName = displayName(chosen); zip = request == PICK_ZIP;
            audio = null; audioName = "";
            if (title.getText().toString().trim().isEmpty() || title.getText().toString().equals(previousDefault))
                title.setText(defaultTitle(sourceName));
        } else return;
        render();
        if (request == PICK_CHART) pick(PICK_AUDIO);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {}
        String name = uri.getLastPathSegment();
        return name == null ? "导入谱面" : name;
    }

    private void render() {
        pickZip.setEnabled(!busy); pickChart.setEnabled(!busy); title.setEnabled(!busy);
        title.setVisibility(zip ? View.GONE : View.VISIBLE);
        titleLabel.setVisibility(zip ? View.GONE : View.VISIBLE);
        pickAudio.setEnabled(!busy && source != null && !zip);
        add.setEnabled(!busy && source != null && (zip || audio != null));
        add.setText(busy ? "正在导入…" : "导入");
        selection.setText(source == null ? "尚未选择文件" : zip ? "谱面包：" + sourceName
            : "谱面：" + sourceName + "\n音乐：" + (audio == null ? "尚未选择" : audioName));
        if (busy) status.setText("正在读取并保存文件，请稍候…");
    }

    private void importSelection() {
        String name = zip ? defaultTitle(sourceName) : title.getText().toString().trim();
        if (name.isEmpty()) name = defaultTitle(sourceName);
        final String chosenName = name, chosenAudioName = audioName;
        final Uri chosenSource = source, chosenAudio = audio;
        final boolean packageImport = zip;
        final Context context = getApplicationContext();
        busy = true; render();
        loader.execute(() -> {
            try (InputStream chart = context.getContentResolver().openInputStream(chosenSource)) {
                if (chart == null) throw new java.io.IOException("无法读取选中的文件");
                final String id;
                if (packageImport) id = ImportedCharts.addZip(context, chart, chosenName);
                else try (InputStream music = context.getContentResolver().openInputStream(chosenAudio)) {
                    if (music == null) throw new java.io.IOException("无法读取选中的音乐");
                    id = ImportedCharts.addPair(context, chart, music, chosenName, chosenAudioName);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy = false;
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_SONG_ID, id));
                    Toast.makeText(this, "已导入谱面与音乐", Toast.LENGTH_SHORT).show(); finish();
                });
            } catch (Exception exception) {
                final String error = exception.getMessage() == null ? "文件读取失败，请重新选择" : exception.getMessage();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    busy = false; status.setText("导入失败：" + error); render();
                });
            }
        });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("source", source == null ? null : source.toString());
        state.putString("audio", audio == null ? null : audio.toString());
        state.putString("sourceName", sourceName); state.putString("audioName", audioName);
        state.putBoolean("zip", zip); state.putString("title", title.getText().toString());
    }

    @Override protected void onDestroy() {
        loader.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (busy) Toast.makeText(this, "正在保存谱面，请稍候", Toast.LENGTH_SHORT).show();
        else super.onBackPressed();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) Immersive.apply(getWindow());
    }

    private static Uri uri(String value) { return value == null ? null : Uri.parse(value); }
    private static String defaultTitle(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

}
