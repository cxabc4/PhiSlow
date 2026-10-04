package com.phislow.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Shared styling helpers so every screen keeps the same plain, art-free controls.
 * The reference build's own button artwork is not shipped in the APK (its UI
 * scenes are baked into assets/bin/Data and reference sprites that are not part
 * of the Addressables catalog), so controls stay deliberately plain.
 */
final class Ui {
    static final int BACKGROUND = Color.rgb(9, 17, 27);
    static final int PANEL = Color.rgb(17, 26, 37);
    static final int INK = Color.rgb(231, 240, 250);
    static final int MUTED = Color.rgb(142, 162, 181);
    static final int ACCENT = Color.rgb(92, 225, 207);
    static final int IDLE = Color.rgb(28, 41, 56);
    static final int ON_ACCENT = Color.rgb(9, 17, 27);

    private Ui() {}

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static TextView text(Context context, String label, float size, int color) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static Button button(Context context, String label, boolean primary, View.OnClickListener action) {
        Button view = new Button(context);
        view.setText(label);
        view.setTextSize(14);
        view.setAllCaps(false);
        view.setTextColor(primary ? ON_ACCENT : INK);
        view.setBackground(card(context, primary ? ACCENT : IDLE, 12));
        view.setPadding(dp(context, 4), 0, dp(context, 4), 0);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(0);
        view.setMinimumHeight(0);
        view.setOnClickListener(action);
        return view;
    }

    static GradientDrawable card(Context context, int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable circle(Context context, int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    static String rangeLabel(Favorites.SavedRange range) {
        return positionLabel(range.startMs) + " → " + positionLabel(range.endMs);
    }

    private static String positionLabel(int ms) {
        return String.format(Locale.US, "%02d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000);
    }

    static void showSavedRanges(Context context, Favorites favorites, List<Favorites.SavedRange> saved,
            String[] labels, Consumer<Favorites.SavedRange> open) {
        if (saved.isEmpty()) {
            Toast.makeText(context, "暂无已收藏段落，可在暂停时收藏当前区间", Toast.LENGTH_LONG).show(); return;
        }
        List<Favorites.SavedRange> ranges = new ArrayList<>(saved);
        List<String> captions = new ArrayList<>(Arrays.asList(labels));
        ArrayAdapter<String> rows = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1,
            captions);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle("已收藏段落 · 长按删除")
            .setAdapter(rows, (selection, index) -> open.accept(ranges.get(index)))
            .setNegativeButton("关闭", null).create();
        dialog.show();
        dialog.getListView().setOnItemLongClickListener((parent, view, index, id) -> {
            Favorites.SavedRange range = ranges.get(index);
            new AlertDialog.Builder(context).setTitle("删除段落收藏？").setMessage(rows.getItem(index))
                .setNegativeButton("取消", null).setPositiveButton("删除", (confirmation, which) -> {
                    if (!favorites.removeRange(range)) {
                        Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show(); return;
                    }
                    captions.remove(index); ranges.remove(index); rows.notifyDataSetChanged();
                    if (ranges.isEmpty()) dialog.dismiss();
                }).show();
            return true;
        });
    }

    static LinearLayout.LayoutParams sized(Context context, int width, int height, int rightMargin) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(context, width), dp(context, height));
        size.rightMargin = dp(context, rightMargin);
        return size;
    }
}
