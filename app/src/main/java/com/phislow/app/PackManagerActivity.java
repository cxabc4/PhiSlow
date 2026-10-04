package com.phislow.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The resource pack manager: everything about the key artwork lives here rather than behind a
 * long-press on the workbench header. The list on the left is what to choose between; the stage on
 * the right shows the choice - the four keys falling down a judge line in that pack's art, so a
 * pack is picked by how it looks rather than by its file name.
 *
 * Applying is a choice between packs, not a one-way import - a pack stays in the list after being
 * replaced, so switching back is a tap rather than another trip through the file picker. Deleting
 * the pack in use falls back to the built-in art, so the keys can never end up missing.
 */
public final class PackManagerActivity extends Activity {
    private static final int PICK_REQUEST = 1;
    private static final int INK = Color.rgb(231, 240, 250), MUTED = Color.rgb(142, 162, 181);
    private static final int ACCENT = Color.rgb(92, 225, 207), PANEL = Color.rgb(17, 26, 37);
    /** Lane captions under the stage, in the order the lanes are drawn. */
    private static final String[] LANES = {"点按", "滑动", "长条", "甩动"};

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private PackPreviewView stage;
    /** The art that ships with the app: the fallback of every pack, and the built-in look itself. */
    private NoteSkin bundled;
    /** Which pack the stage is showing; a row is previewed by tapping it, applied by its button. */
    private String previewId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        setContentView(root);

        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "资源包管理", 20, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        Button add = Ui.button(this, "＋ 添加", true, view -> pickPack());
        add.setContentDescription("添加资源包");
        header.addView(add, Ui.sized(this, 76, 38, 6));
        Button back = Ui.button(this, "← 返回", false, view -> finish());
        back.setContentDescription("返回练习工作台");
        header.addView(back, Ui.sized(this, 76, 38, 0));
        root.addView(header);

        TextView hint = Ui.text(this, "点一行，右边就预览它的按键；点「应用」才真正换上。", 12, MUTED);
        LinearLayout.LayoutParams hintSize = new LinearLayout.LayoutParams(-1, -2);
        hintSize.bottomMargin = Ui.dp(this, 12);
        root.addView(hint, hintSize);

        LinearLayout body = Ui.row(this);
        body.setGravity(Gravity.TOP);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        ScrollView scroll = new ScrollView(this);
        list = Ui.column(this);
        scroll.addView(list, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams listSize = new LinearLayout.LayoutParams(0, -1, 1);
        listSize.rightMargin = Ui.dp(this, 10);
        body.addView(scroll, listSize);

        LinearLayout aside = Ui.column(this);
        stage = new PackPreviewView(this);
        aside.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout captions = Ui.row(this);
        for (String lane : LANES) {
            TextView label = Ui.text(this, lane, 9, MUTED);
            label.setGravity(Gravity.CENTER);
            captions.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        }
        LinearLayout.LayoutParams captionSize = new LinearLayout.LayoutParams(-1, -2);
        captionSize.topMargin = Ui.dp(this, 4);
        aside.addView(captions, captionSize);
        // List and stage split the width evenly: a pack is picked by how its keys come down, and
        // the falling ones need as much room as the rows to be worth watching.
        body.addView(aside, new LinearLayout.LayoutParams(0, -1, 1));

        // Several megabytes of bitmaps, so they are decoded off the main thread; until they land
        // the stage draws the keys the app draws itself, which is still a true picture of a pack
        // carrying none of its own.
        previewId = NoteSkin.activeId(this);
        loader.execute(() -> {
            final NoteSkin art = NoteSkin.bundled(PackManagerActivity.this);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                bundled = art;
                showPreview(previewId);
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    @Override protected void onDestroy() {
        loader.shutdown();
        super.onDestroy();
    }

    /** Rebuilt from disk on every resume, so an import or a delete shows up immediately. */
    private void render() {
        list.removeAllViews();
        List<NoteSkin.Pack> packs = NoteSkin.list(this);
        boolean known = false;
        for (NoteSkin.Pack pack : packs) known = known || pack.id.equals(previewId);
        if (!known) showPreview(NoteSkin.activeId(this));
        for (NoteSkin.Pack pack : packs) list.addView(packRow(pack), rowSize());
    }

    private LinearLayout.LayoutParams rowSize() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = Ui.dp(this, 8);
        return size;
    }

    /**
     * Puts one pack on the stage. Its art is read off the main thread too, so rows can be tapped
     * through without the list stuttering behind them.
     */
    private void showPreview(String id) {
        previewId = id;
        if (bundled == null) return;
        if (NoteSkin.BUILTIN.equals(id)) {
            stage.setSkin(null, bundled);
            return;
        }
        loader.execute(() -> {
            final NoteSkin chosen = NoteSkin.load(PackManagerActivity.this, id);
            runOnUiThread(() -> {
                if (!isFinishing()) stage.setSkin(chosen, bundled);
            });
        });
    }

    private LinearLayout packRow(NoteSkin.Pack pack) {
        LinearLayout row = Ui.column(this);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        row.setBackground(Ui.card(this, PANEL, 12));
        row.setContentDescription("预览资源包 " + pack.name);
        row.setOnClickListener(view -> { showPreview(pack.id); render(); });
        boolean previewing = pack.id.equals(previewId);
        if (pack.active || previewing) {
            // A ring rather than a fill: the row still reads as a normal row, just the chosen one.
            GradientDrawable frame = Ui.card(this, PANEL, 12);
            frame.setStroke(Ui.dp(this, 2), pack.active ? ACCENT : MUTED);
            row.setBackground(frame);
        }

        LinearLayout nameLine = Ui.row(this);
        TextView name = Ui.text(this, pack.name, 14, INK);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setSingleLine(true);
        nameLine.addView(name, new LinearLayout.LayoutParams(-2, -2));
        if (pack.active) nameLine.addView(tag("使用中", ACCENT));
        else if (previewing) nameLine.addView(tag("预览中", MUTED));
        row.addView(nameLine, new LinearLayout.LayoutParams(-1, -2));

        TextView summary = Ui.text(this, pack.summary, 11, MUTED);
        summary.setSingleLine(true);
        LinearLayout.LayoutParams summarySize = new LinearLayout.LayoutParams(-1, -2);
        summarySize.topMargin = Ui.dp(this, 2);
        row.addView(summary, summarySize);

        LinearLayout actions = Ui.row(this);
        actions.setGravity(Gravity.RIGHT);
        if (!pack.active) {
            Button apply = Ui.button(this, "应用", true, view -> applyPack(pack));
            apply.setContentDescription("应用资源包 " + pack.name);
            actions.addView(apply, Ui.sized(this, 68, 34, 6));
        }
        if (!pack.builtin) {
            Button remove = Ui.button(this, "删除", false, view -> removePack(pack));
            remove.setContentDescription("删除资源包 " + pack.name);
            actions.addView(remove, Ui.sized(this, 68, 34, 0));
        }
        LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(-1, -2);
        actionSize.topMargin = Ui.dp(this, 8);
        row.addView(actions, actionSize);
        return row;
    }

    private TextView tag(String label, int color) {
        TextView view = Ui.text(this, label, 10, color);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.leftMargin = Ui.dp(this, 6);
        view.setLayoutParams(size);
        return view;
    }

    private void applyPack(NoteSkin.Pack pack) {
        NoteSkin.activate(this, pack.id);
        showPreview(pack.id);
        render();
        Toast.makeText(this, pack.builtin ? "已切回内置按键美术" : "已应用 " + pack.name,
            Toast.LENGTH_SHORT).show();
    }

    private void removePack(NoteSkin.Pack pack) {
        String name = pack.name;
        boolean wasActive = pack.active;
        if (!NoteSkin.remove(this, pack.id)) {
            Toast.makeText(this, "删除失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        render();
        Toast.makeText(this, "已删除 " + name + (wasActive ? "，已切回内置按键美术" : ""),
            Toast.LENGTH_SHORT).show();
    }

    private void pickPack() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_REQUEST);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        addPack(data.getData());
    }

    /**
     * Unzipping is a few hundred kilobytes of reads and image checks, so it runs off the main
     * thread; a file that turns out not to be a pack is reported rather than listed.
     */
    private void addPack(final Uri uri) {
        loader.execute(() -> {
            try {
                final String id = NoteSkin.importPack(PackManagerActivity.this, uri);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (id != null) showPreview(id);
                    render();
                    Toast.makeText(PackManagerActivity.this,
                        id == null ? "这个 zip 里没有可识别的按键图片" : "已添加并应用",
                        Toast.LENGTH_LONG).show();
                });
            } catch (final Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(PackManagerActivity.this,
                        "资源包读取失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
