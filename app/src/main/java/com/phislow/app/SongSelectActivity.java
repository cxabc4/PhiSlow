package com.phislow.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Song select screen.
 *
 * Geometry follows the reference build's own song-detail scene: a large
 * illustration next to the song name, artist and difficulty badge, with the
 * blurred illustration painted behind everything. The reference build's button
 * artwork is not present in the APK, so controls stay plain.
 */
public final class SongSelectActivity extends Activity {
    private static final int IMPORT_REQUEST = 10;
    private static final float[] SPEEDS = AudioEngine.SPEEDS;
    /** Chips per row in the two-row speed grid, so 0.1×–2× stays tappable on narrow screens. */
    private static final int SPEED_COLUMNS = 7;
    private static final float COVER_ASPECT = 975.6f / 514.5f;

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final List<SongLibrary.Song> visible = new ArrayList<>();
    private final List<Button> difficultyChips = new ArrayList<>();
    private final List<Button> speedChips = new ArrayList<>();
    private CoverLoader covers;
    private SongAdapter adapter;
    private SongLibrary library;
    private Favorites favorites;
    private boolean favoritesOnly;
    private int libraryLoadToken;
    private SongLibrary.Song selected;
    private SongLibrary.Selection selectedChart;
    private float speed = 1f;

    private LinearLayout root, difficultyRow, speedRow, speedTail;
    private ImageView cover;
    private TextView coverHint, songTitle, songArtist, libraryCount, emptyList;
    private EditText search;
    private Button start, clearSearch, favorite, favoriteFilter;
    private Button packButton;
    private Switch autoplay;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        favorites = new Favorites(this);
        covers = new CoverLoader(this);
        buildUi();
        // After buildUi: the decor view has to exist before the window has an insets controller.
        Immersive.apply(getWindow());
        reloadLibrary(null);
    }

    private void reloadLibrary(String selectedId) {
        final int token = ++libraryLoadToken;
        loader.execute(() -> {
            try {
                SongLibrary loaded = new SongLibrary(this, percent -> runOnUiThread(() -> {
                    if (token == libraryLoadToken && !isFinishing() && !isDestroyed()) {
                        libraryCount.setText("保存曲库 " + percent + "%");
                        emptyList.setText("正在保存曲库，完成后可使用增量包更新");
                    }
                }));
                runOnUiThread(() -> {
                    if (token != libraryLoadToken || isFinishing() || isDestroyed()) return;
                    adoptLibrary(loaded);
                    if (selectedId != null) for (SongLibrary.Song song : loaded.songs)
                        if (selectedId.equals(song.id)) { selectSong(song); break; }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (token != libraryLoadToken || isFinishing() || isDestroyed()) return;
                    libraryCount.setText("曲库未就绪");
                    emptyList.setText(error.getMessage() == null ? "曲库保存失败，请重新打开完整包" : error.getMessage());
                });
            }
        });
    }

    private void buildUi() {
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 10));
        setContentView(root);

        LinearLayout header = Ui.row(this);
        TextView brand = Ui.text(this, "PhiSlow", 24, Ui.INK);
        brand.setTypeface(brand.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1));
        libraryCount = Ui.text(this, "载入曲库…", 12, Ui.MUTED);
        header.addView(libraryCount, new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 44)));
        Button importButton = Ui.button(this, "导入谱面", true,
            view -> startActivityForResult(new Intent(this, ChartImportActivity.class), IMPORT_REQUEST));
        header.addView(importButton, Ui.sized(this, 100, 40, 8));
        // Settings and the headphone menu live on the main interface: the workbench header keeps
        // only what a running session needs.
        android.widget.Button settingsButton = Ui.button(this, "设置", false,
            view -> startActivity(new android.content.Intent(this, SettingsActivity.class)));
        settingsButton.setContentDescription("打开设置");
        header.addView(settingsButton, Ui.sized(this, 88, 40, 8));
        android.widget.Button headphoneButton = Ui.button(this, "耳机", false,
            view -> startActivity(new android.content.Intent(this, LatencyActivity.class)));
        headphoneButton.setContentDescription("打开耳机延迟校准");
        header.addView(headphoneButton, Ui.sized(this, 88, 40, 8));
        // Key artwork is managed on its own screen, so this only opens it; the tick still says
        // whether an imported pack is the one in use.
        packButton = Ui.button(this, "资源包", true, view -> openResourcePack());
        header.addView(packButton, Ui.sized(this, 128, 40, 0));
        root.addView(header);

        LinearLayout body = Ui.row(this);
        body.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams bodySize = new LinearLayout.LayoutParams(-1, 0, 1);
        bodySize.topMargin = Ui.dp(this, 10);
        root.addView(body, bodySize);

        LinearLayout listPanel = Ui.column(this);
        listPanel.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        listPanel.setBackground(Ui.card(this, Ui.PANEL, 12));
        body.addView(listPanel, new LinearLayout.LayoutParams(0, -1, 4));

        // The field and its clear button share one surface, so the x reads as part of the box.
        LinearLayout searchRow = Ui.row(this);
        searchRow.setBackground(Ui.card(this, Ui.IDLE, 10));
        search = new EditText(this);
        search.setHint("搜索曲名");
        search.setSingleLine(true);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setBackground(null);
        search.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        clearSearch = Ui.button(this, "×", false, view -> search.setText(""));
        clearSearch.setTextSize(18);
        clearSearch.setContentDescription("清除搜索内容");
        clearSearch.setVisibility(View.GONE);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
        searchRow.addView(clearSearch, Ui.sized(this, 40, 32, 4));
        listPanel.addView(searchRow, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        LinearLayout collectionRow = Ui.row(this);
        favoriteFilter = Ui.button(this, "收藏曲目", false, view -> {
            favoritesOnly = !favoritesOnly;
            favoriteFilter.setText(favoritesOnly ? "显示全部" : "收藏曲目");
            filter(search.getText().toString());
        });
        favoriteFilter.setContentDescription("切换收藏曲目列表");
        collectionRow.addView(favoriteFilter, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1));
        Button ranges = Ui.button(this, "我的段落", false, view -> showSavedRanges());
        ranges.setContentDescription("打开全部段落收藏");
        LinearLayout.LayoutParams rangesSize = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1);
        rangesSize.leftMargin = Ui.dp(this, 6);
        collectionRow.addView(ranges, rangesSize);
        LinearLayout.LayoutParams collectionSize = new LinearLayout.LayoutParams(-1, -2);
        collectionSize.topMargin = Ui.dp(this, 6);
        collectionSize.bottomMargin = Ui.dp(this, 6);
        listPanel.addView(collectionRow, collectionSize);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(0);
        adapter = new SongAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> selectSong(visible.get(position)));
        listPanel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        emptyList = Ui.text(this, "没有匹配的曲目", 13, Ui.MUTED);
        emptyList.setGravity(Gravity.CENTER);
        listPanel.addView(emptyList, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setEmptyView(emptyList);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter(text.toString()); }
            // The clear button is only useful while there is something to clear.
            @Override public void afterTextChanged(Editable text) {
                clearSearch.setVisibility(text != null && text.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });

        LinearLayout detail = Ui.column(this);
        detail.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        detail.setBackground(Ui.card(this, Ui.PANEL, 12));
        LinearLayout.LayoutParams detailSize = new LinearLayout.LayoutParams(0, -1, 6);
        detailSize.leftMargin = Ui.dp(this, 12);
        body.addView(detail, detailSize);

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setAdjustViewBounds(false);
        detail.addView(cover, new LinearLayout.LayoutParams(-1, 0, 1));
        coverHint = Ui.text(this, "此曲目没有曲绘", 13, Ui.MUTED);
        coverHint.setGravity(Gravity.CENTER);
        coverHint.setVisibility(View.GONE);
        detail.addView(coverHint, new LinearLayout.LayoutParams(-1, 0, 1));

        songTitle = Ui.text(this, "选择一首曲目", 22, Ui.INK);
        songTitle.setSingleLine(true);
        songTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(-1, Ui.dp(this, 34));
        titleSize.topMargin = Ui.dp(this, 10);
        LinearLayout titleRow = Ui.row(this);
        titleRow.addView(songTitle, new LinearLayout.LayoutParams(0, -1, 1));
        favorite = Ui.button(this, "☆ 收藏", false, view -> {
            if (selected == null) return;
            if (!favorites.toggleSongFavorite(selected.id)) {
                Toast.makeText(this, "收藏保存失败", Toast.LENGTH_SHORT).show(); return;
            }
            paintFavorite();
            filter(search.getText().toString());
        });
        favorite.setEnabled(false);
        titleRow.addView(favorite, new LinearLayout.LayoutParams(Ui.dp(this, 100), -1));
        detail.addView(titleRow, titleSize);
        songArtist = Ui.text(this, "", 13, Ui.MUTED);
        songArtist.setSingleLine(true);
        detail.addView(songArtist, new LinearLayout.LayoutParams(-1, Ui.dp(this, 22)));

        difficultyRow = Ui.row(this);
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        rowSize.topMargin = Ui.dp(this, 6);
        detail.addView(difficultyRow, rowSize);

        LinearLayout options = Ui.column(this);
        LinearLayout.LayoutParams optionsSize = new LinearLayout.LayoutParams(-1, Ui.dp(this, 78));
        optionsSize.topMargin = Ui.dp(this, 4);
        detail.addView(options, optionsSize);
        speedRow = Ui.row(this);
        options.addView(speedRow, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout optionRow = Ui.row(this);
        options.addView(optionRow, new LinearLayout.LayoutParams(-1, 0, 1));
        speedTail = Ui.row(this);
        optionRow.addView(speedTail, new LinearLayout.LayoutParams(0, -1, 1));
        autoplay = new Switch(this);
        autoplay.setText("  Autoplay");
        autoplay.setTextColor(Ui.INK);
        autoplay.setTextSize(12);
        optionRow.addView(autoplay, new LinearLayout.LayoutParams(-2, -1));

        buildSpeedRow();
        start = Ui.button(this, "开始游戏", true, view -> openLevel());
        start.setEnabled(false);
        LinearLayout.LayoutParams startSize = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        startSize.topMargin = Ui.dp(this, 8);
        detail.addView(start, startSize);
        refreshPackButton();
    }

    /** Two rows of chips so every 0.1×–2× preset stays wide enough to tap. */
    private void buildSpeedRow() {
        speedRow.removeAllViews();
        speedTail.removeAllViews();
        speedChips.clear();
        speedRow.addView(Ui.text(this, "倍速", 12, Ui.MUTED), new LinearLayout.LayoutParams(Ui.dp(this, 34), -1));
        speedTail.addView(new View(this), new LinearLayout.LayoutParams(Ui.dp(this, 34), -1));
        for (int index = 0; index < SPEEDS.length; index++) {
            final float value = SPEEDS[index];
            Button chip = Ui.button(this, AudioEngine.speedLabel(value), false, view -> { speed = value; paintSpeed(); });
            chip.setTextSize(11);
            chip.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
            size.rightMargin = Ui.dp(this, 4);
            (index < SPEED_COLUMNS ? speedRow : speedTail).addView(chip, size);
            speedChips.add(chip);
        }
        paintSpeed();
    }

    private void paintSpeed() {
        for (int index = 0; index < speedChips.size(); index++) {
            boolean chosen = SPEEDS[index] == speed;
            Button chip = speedChips.get(index);
            chip.setTextColor(chosen ? Ui.ON_ACCENT : Ui.INK);
            chip.setBackground(Ui.card(this, chosen ? Ui.ACCENT : Ui.IDLE, 12));
        }
    }

    private void adoptLibrary(SongLibrary loaded) {
        library = loaded;
        libraryCount.setText("曲库 " + loaded.songs.size() + " 首");
        filter(search.getText().toString());
        if (!loaded.songs.isEmpty()) {
            int index = indexOf(loaded.songs, "996");
            selectSong(loaded.songs.get(index < 0 ? 0 : index));
        }
    }

    private static int indexOf(List<SongLibrary.Song> songs, String title) {
        for (int index = 0; index < songs.size(); index++) if (songs.get(index).title.equals(title)) return index;
        return -1;
    }

    private void filter(String keyword) {
        visible.clear();
        emptyList.setText(library != null && library.songs.isEmpty()
            ? "曲库为空\n点击「导入谱面」添加谱面和音乐"
            : favoritesOnly ? "暂无匹配的收藏曲目" : "没有匹配的曲目");
        if (library != null) {
            String needle = keyword == null ? "" : keyword.trim().toLowerCase();
            for (SongLibrary.Song song : library.songs) {
                if (favoritesOnly && !favorites.isSongFavorite(song.id)) continue;
                if (needle.isEmpty() || song.title.toLowerCase().contains(needle)
                        || (song.artist != null && song.artist.toLowerCase().contains(needle)))
                    visible.add(song);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void selectSong(SongLibrary.Song song) {
        selected = song;
        selectedChart = song.charts.isEmpty() ? null : song.charts.get(0);
        songTitle.setText(song.title);
        paintFavorite();
        songArtist.setText(song.artist == null || song.artist.isEmpty() ? "未知曲师" : song.artist);
        buildDifficultyRow();
        paintCover(song);
        start.setEnabled(selectedChart != null);
        adapter.notifyDataSetChanged();
    }

    private void buildDifficultyRow() {
        difficultyRow.removeAllViews();
        difficultyChips.clear();
        if (selected == null) return;
        for (SongLibrary.Selection chart : selected.charts) {
            Button chip = Ui.button(this, chart.toString(), false, view -> {
                selectedChart = chart;
                paintDifficulty();
            });
            chip.setTextSize(11);
            chip.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
            size.rightMargin = Ui.dp(this, 6);
            difficultyRow.addView(chip, size);
            difficultyChips.add(chip);
        }
        paintDifficulty();
    }

    private void paintDifficulty() {
        if (selected == null) return;
        for (int index = 0; index < difficultyChips.size(); index++) {
            boolean chosen = selected.charts.get(index) == selectedChart;
            Button chip = difficultyChips.get(index);
            chip.setTextColor(chosen ? Ui.ON_ACCENT : Ui.INK);
            chip.setBackground(Ui.card(this, chosen ? Ui.ACCENT : Ui.IDLE, 12));
        }
    }

    /** Mirrors the reference build: blurred illustration behind, crisp one in front. */
    private void paintCover(SongLibrary.Song song) {
        Bitmap blur = covers.get(song.coverBlur, 256);
        if (blur != null) {
            Drawable[] layers = {new ColorDrawable(0xD909111B), new BitmapDrawable(getResources(), blur)};
            root.setBackground(new LayerDrawable(layers));
        } else {
            root.setBackgroundColor(Ui.BACKGROUND);
        }
        Bitmap full = covers.get(song.cover, 1024);
        boolean hasCover = full != null;
        cover.setImageBitmap(full);
        cover.setVisibility(hasCover ? View.VISIBLE : View.GONE);
        coverHint.setVisibility(hasCover ? View.GONE : View.VISIBLE);
    }

    private void openLevel() {
        openLevel(null);
    }

    private void openLevel(Favorites.SavedRange range) {
        if (selectedChart == null) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_SONG_ID, selected.id);
        // The name and the difficulty travel apart: the workbench puts one at each bottom corner
        // of the stage, and only it knows how much room each has.
        intent.putExtra(MainActivity.EXTRA_TITLE, selected.title);
        intent.putExtra(MainActivity.EXTRA_LEVEL, selectedChart.difficulty);
        intent.putExtra(MainActivity.EXTRA_CHART, selectedChart.path);
        // Use the preblurred art on the stage; the song selection keeps its crisp cover.
        intent.putExtra(MainActivity.EXTRA_COVER, selected.coverBlur);
        intent.putExtra(MainActivity.EXTRA_AUDIO_ASSET, selectedChart.audio);
        intent.putExtra(MainActivity.EXTRA_SPEED, speed);
        intent.putExtra(MainActivity.EXTRA_AUTOPLAY, autoplay.isChecked());
        if (range != null) {
            intent.putExtra(MainActivity.EXTRA_RANGE_START, range.startMs);
            intent.putExtra(MainActivity.EXTRA_RANGE_END, range.endMs);
        }
        startActivity(intent);
    }

    private void paintFavorite() {
        boolean saved = selected != null && favorites.isSongFavorite(selected.id);
        favorite.setEnabled(selected != null);
        favorite.setText(saved ? "★ 已收藏" : "☆ 收藏");
        favorite.setContentDescription(saved ? "取消曲目收藏" : "收藏当前曲目");
    }

    private void showSavedRanges() {
        if (library == null) return;
        List<Favorites.SavedRange> ranges = favorites.listRanges();
        String[] labels = new String[ranges.size()];
        for (int i = 0; i < ranges.size(); i++) {
            Favorites.SavedRange range = ranges.get(i);
            String title = range.songId, level = "谱面已不在曲库";
            for (SongLibrary.Song song : library.songs) if (song.id.equals(range.songId)) {
                title = song.title;
                for (SongLibrary.Selection chart : song.charts)
                    if (chart.path.equals(range.chartPath)) level = chart.difficulty;
            }
            labels[i] = title + " / " + level + "\n" + range.name + " · " + Ui.rangeLabel(range);
        }
        Ui.showSavedRanges(this, favorites, ranges, labels, range -> {
            for (SongLibrary.Song song : library.songs) if (song.id.equals(range.songId)) {
                for (SongLibrary.Selection chart : song.charts) if (chart.path.equals(range.chartPath)) {
                    selectSong(song); selectedChart = chart; paintDifficulty(); openLevel(range); return;
                }
            }
            Toast.makeText(this, "此谱面已不在曲库，可长按删除收藏", Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Imports a resource pack (a ZIP of key images) that replaces the built-in key artwork. The
     * pack is stored app-wide, so it applies to every chart from here on; long-press drops it.
     */
    /** Opens the resource pack manager, where packs are added, applied and deleted. */
    private void openResourcePack() {
        startActivity(new Intent(this, PackManagerActivity.class));
    }

    /**
     * The manager edits the pack list in place, so a pack applied or deleted there shows up on the
     * way back instead of only when this screen is first built.
     */
    @Override protected void onResume() {
        super.onResume();
        refreshPackButton();
        if (favorites != null && adapter != null) {
            paintFavorite(); filter(search.getText().toString());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_REQUEST && resultCode == RESULT_OK) {
            favoritesOnly = false; favoriteFilter.setText("收藏曲目"); search.setText("");
            reloadLibrary(data == null ? null : data.getStringExtra("song_id"));
        }
    }

    /** The header button doubles as the status: a tick once an imported pack is in use. */
    private void refreshPackButton() {
        boolean installed = NoteSkin.isInstalled(this);
        packButton.setText(installed ? "资源包 ✓" : "资源包");
        packButton.setContentDescription(installed
            ? "已导入资源包，打开资源包管理" : "打开资源包管理");
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Immersive.apply(getWindow());
    }

    @Override protected void onDestroy() {
        libraryLoadToken++;
        loader.shutdownNow();
        super.onDestroy();
    }

    /** Song rows: cover thumbnail plus title and artist, like the reference list card. */
    private final class SongAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            ImageView thumb;
            TextView title, artist;
            if (recycled == null) {
                row = Ui.row(parent.getContext());
                row.setPadding(Ui.dp(parent.getContext(), 6), Ui.dp(parent.getContext(), 6),
                    Ui.dp(parent.getContext(), 6), Ui.dp(parent.getContext(), 6));
                thumb = new ImageView(parent.getContext());
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(parent.getContext(), 44), Ui.dp(parent.getContext(), 25)));
                LinearLayout labels = Ui.column(parent.getContext());
                labels.setPadding(Ui.dp(parent.getContext(), 10), 0, 0, 0);
                title = Ui.text(parent.getContext(), "", 14, Ui.INK);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                artist = Ui.text(parent.getContext(), "", 11, Ui.MUTED);
                artist.setSingleLine(true);
                // The lines wrap their own text and the row grows with them. Sharing a weighted
                // height inside a match-parent column made both lines as tall as the 44x25
                // thumbnail, which cut the bottom half off every song title.
                labels.addView(title, new LinearLayout.LayoutParams(-1, -2));
                labels.addView(artist, new LinearLayout.LayoutParams(-1, -2));
                row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                row.setTag(new Holder(thumb, title, artist));
            } else {
                row = (LinearLayout) recycled;
            }
            Holder holder = (Holder) row.getTag();
            SongLibrary.Song song = visible.get(position);
            holder.title.setText(song.title);
            holder.artist.setText(song.artist == null || song.artist.isEmpty() ? "未知曲师" : song.artist);
            Bitmap bitmap = covers.get(song.coverThumb, 96);
            holder.thumb.setImageBitmap(bitmap);
            holder.thumb.setBackground(Ui.card(parent.getContext(), Ui.IDLE, 4));
            boolean chosen = song == selected;
            row.setBackground(Ui.card(parent.getContext(), chosen ? Ui.IDLE : 0x00000000, 10));
            holder.title.setTextColor(chosen ? Ui.ACCENT : Ui.INK);
            return row;
        }
    }

    private static final class Holder {
        final ImageView thumb;
        final TextView title, artist;
        Holder(ImageView thumb, TextView title, TextView artist) {
            this.thumb = thumb; this.title = title; this.artist = artist;
        }
    }
}
