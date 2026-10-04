package com.phislow.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Optional key artwork ("按键美术") supplied by an imported resource pack.
 *
 * A resource pack is a ZIP holding one image per note type. An entry is matched on its file name
 * only - the folder it sits in, the letter case and any separators are ignored - so
 * {@code tap.png}, {@code Note_Tap.png} and {@code skin/蓝色.png} all supply the tap key. A pack
 * may cover any subset of the four types: whatever it omits keeps the key the view draws itself,
 * so a partial pack is still usable.
 *
 * Installation is staged and fail-closed. A pack that contributes no key art at all (an empty
 * ZIP, a renamed photo album, a file that is not a ZIP) is refused outright rather than wiping
 * anything, and an entry whose bytes do not decode as an image is skipped instead of producing
 * a broken key.
 *
 * Any number of packs may be installed; each keeps its own directory under {@code packs/} and
 * exactly one of them is the active one, remembered by id. The art that ships with the app is
 * id {@link #BUILTIN}: applying it means "no imported pack", which is also the state a fresh
 * install starts in. Packs are copied into internal storage, so they survive a restart.
 */
public final class NoteSkin {
    /** Note types, matching {@link Chart.Note#type}. */
    public static final int TAP = 1, DRAG = 2, HOLD = 3, FLICK = 4;
    private static final int[] NOTE_TYPES = {TAP, DRAG, HOLD, FLICK};
    /** Base names accepted per note type, already normalised (see {@link #normalise}). */
    private static final String[][] NOTE_NAMES = {
        {"tap", "click", "notetap", "1", "蓝键", "蓝色", "点按"},
        {"drag", "notedrag", "2", "黄键", "黄色", "滑动"},
        {"hold", "notehold", "holdhead", "3", "长条", "长按", "长条头"},
        {"flick", "noteflick", "4", "粉键", "粉色", "甩动"},
    };
    /** The long body behind a hold head, optional; the head image is stretched when it is absent. */
    private static final String[] BODY_NAMES = {
        "holdbody", "holdbar", "holdtail", "长条身", "长条尾", "按键身",
    };
    private static final String[] IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp"};
    private static final int BODY_SLOT = 5;
    private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;
    /** Canonical on-disk name per slot: 1-4 are the note types, 5 is the hold body. */
    private static final String[] SLOT_FILES =
        {"", "tap.png", "drag.png", "hold.png", "flick.png", "hold-body.png"};
    private static final String[] SLOT_LABELS = {"", "点按", "滑动", "长条", "甩动", "长条身"};

    /** Id of the art that ships with the app. Applying it means "no imported pack". */
    public static final String BUILTIN = "builtin";
    private static final String PACKS_DIR = "packs";
    /** Directory an older build used for its one and only pack; folded in on first use. */
    private static final String LEGACY_DIR = "note-skin";
    private static final String LEGACY_ID = "pack-1";
    private static final String NAME_FILE = "name.txt";
    private static final String ACTIVE_FILE = "active-pack";
    private static final String DEFAULT_NAME = "导入的资源包";

    /** One entry of the management screen: a pack on disk, or the art the app was built with. */
    public static final class Pack {
        public final String id, name, summary;
        public final boolean builtin, active;
        /** How many of the four keys the pack actually supplies. */
        public final int keys;
        Pack(String id, String name, String summary, boolean builtin, int keys, boolean active) {
            this.id = id; this.name = name; this.summary = summary;
            this.builtin = builtin; this.keys = keys; this.active = active;
        }
    }

    /**
     * The art that ships with the app, under assets/{@value #BUNDLED_DIR}. It keeps the resource
     * pack's own file names (click.png and friends), plus a layout.json saying where the coloured
     * key body sits inside each canvas - the padding around it carries the white outline, the end
     * markers and the gold frame of the double variants, all of which are meant to overhang the
     * key rather than be squeezed into it.
     */
    private static final String BUNDLED_DIR = "noteskin";
    private static final String[] BUNDLED_NOTE_FILES =
        {"", "click.png", "drag.png", "hold.png", "flick.png", ""};
    private static final String[] BUNDLED_DOUBLE_FILES =
        {"", "click_mh.png", "drag_mh.png", "hold_mh.png", "flick_mh.png", ""};
    /** How much to shrink each bundled canvas before decoding: they are far larger than a key. */
    private static final int BAR_SAMPLE = 4, HOLD_SAMPLE = 2, EFFECT_SAMPLE = 4;

    private final Bitmap[] notes = new Bitmap[5];
    private final Bitmap[] doubles = new Bitmap[5];
    private Bitmap holdBody;
    /** Source-pixel rect of the coloured key body: {left, top, right, bottom}; null = whole image. */
    private final float[][] bodies = new float[6][];
    private final float[][] doubleBodies = new float[6][];
    /** Hold cap heights in source pixels, {tail, head}; null when the art is a plain stretch. */
    private int[] holdCaps, holdCapsDouble;
    private Bitmap hitFx;
    private int hitFxColumns = 1, hitFxRows = 1;
    private float hitFxScale = 1f, hitFxSeconds;
    private boolean hitFxTinted = true;

    private NoteSkin() {}

    /** The imported artwork for a note type, or null to keep the built-in key. */
    public Bitmap note(int type) { return type >= TAP && type <= FLICK ? notes[type] : null; }
    /** The imported long-note body, or null to stretch the hold key instead. */
    public Bitmap holdBody() { return holdBody; }
    /** The art for a key that shares its beat with another, or null when the pack has none. */
    public Bitmap noteDouble(int type) { return type >= TAP && type <= FLICK ? doubles[type] : null; }

    /** Where the coloured key body sits inside {@code note(type)}, in that bitmap's pixels. */
    public float[] bodyRect(int type) { return type >= TAP && type <= FLICK ? bodies[type] : null; }
    /** The same for the double variant, which keeps the body and adds the frame in the padding. */
    public float[] bodyRectDouble(int type) { return type >= TAP && type <= FLICK ? doubleBodies[type] : null; }

    /** Hold cap heights in the hold art's pixels, {tail, head}; null when it stretches as a whole. */
    public int[] holdCaps() { return holdCaps; }
    public int[] holdCapsDouble() { return holdCapsDouble; }

    /** The hit-effect atlas and its grid, or null to keep the drawn rings. */
    public Bitmap hitFx() { return hitFx; }
    public int hitFxColumns() { return hitFxColumns; }
    public int hitFxRows() { return hitFxRows; }
    public float hitFxScale() { return hitFxScale; }
    public float hitFxSeconds() { return hitFxSeconds; }
    public boolean hitFxTinted() { return hitFxTinted; }

    public boolean isActive() {
        for (int type : NOTE_TYPES) if (notes[type] != null) return true;
        return false;
    }

    /** Names the key slots the pack supplied, for the status toast. */
    public String summary() {
        List<String> found = new ArrayList<>();
        for (int type : NOTE_TYPES) if (notes[type] != null) found.add(SLOT_LABELS[type]);
        if (holdBody != null && notes[HOLD] == null) found.add(SLOT_LABELS[BODY_SLOT]);
        if (found.isEmpty()) return "未提供按键图片";
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < found.size(); index++) {
            if (index > 0) text.append(" / ");
            text.append(found.get(index));
        }
        return text.toString();
    }

    /** Loads the pack in use; the built-in look yields an inactive skin that defers to the view. */
    public static NoteSkin load(Context context) { return load(context, null); }

    /**
     * Loads one pack by id, or the one in use when no id is given. An id that is {@link #BUILTIN}
     * - or one that no longer exists, which is what happens when the pack being previewed is
     * deleted - yields an inactive skin, so the caller falls back to the art that ships with the
     * app rather than to nothing.
     */
    public static NoteSkin load(Context context, String id) {
        migrate(context);
        String wanted = id == null || id.isEmpty() ? activeId(context) : id;
        NoteSkin skin = new NoteSkin();
        if (BUILTIN.equals(wanted)) return skin;
        File directory = new File(packsDir(context), wanted);
        if (!directory.isDirectory()) return skin;
        for (int slot = TAP; slot <= BODY_SLOT; slot++) {
            Bitmap bitmap = decode(new File(directory, SLOT_FILES[slot]));
            if (slot == BODY_SLOT) skin.holdBody = bitmap; else skin.notes[slot] = bitmap;
        }
        return skin;
    }

    /**
     * Every pack the management screen should offer: the built-in look first, then the imported
     * ones in id order. Only slot files are inspected, so listing never decodes artwork.
     */
    public static List<Pack> list(Context context) {
        migrate(context);
        String active = activeId(context);
        List<Pack> packs = new ArrayList<>();
        packs.add(new Pack(BUILTIN, "内置按键美术", "应用自带 · 四种按键 + 双押金边 + 打击特效",
            true, NOTE_TYPES.length, BUILTIN.equals(active)));
        File[] children = packsDir(context).listFiles();
        if (children == null) return packs;
        // File order is unspecified; sorting by id keeps the list stable between visits.
        Arrays.sort(children);
        for (File child : children) {
            if (!child.isDirectory()) continue;
            int keys = 0;
            StringBuilder found = new StringBuilder();
            for (int type : NOTE_TYPES) {
                if (!new File(child, SLOT_FILES[type]).isFile()) continue;
                keys++;
                if (found.length() > 0) found.append(" / ");
                found.append(SLOT_LABELS[type]);
            }
            if (keys == 0) continue;  // a directory with no key art in it is not a pack
            packs.add(new Pack(child.getName(), readName(child), found.toString(),
                false, keys, child.getName().equals(active)));
        }
        return packs;
    }

    /** The id of the pack in use, or {@link #BUILTIN} when none is. */
    public static String activeId(Context context) {
        try (InputStream input = new FileInputStream(activeFile(context))) {
            byte[] bytes = readAll(input, 256);
            String id = bytes == null ? "" : new String(bytes, "UTF-8").trim();
            return id.isEmpty() ? BUILTIN : id;
        } catch (IOException error) {
            return BUILTIN;
        }
    }

    /** Makes one pack the one in use; {@link #BUILTIN} hands the keys back to the app's own art. */
    public static void activate(Context context, String id) {
        String chosen = id == null || id.isEmpty() ? BUILTIN : id;
        try {
            write(activeFile(context), chosen.getBytes("UTF-8"));
        } catch (IOException error) {
            throw new IllegalStateException("无法记录当前资源包", error);
        }
    }

    /**
     * Drops one pack. Removing the pack in use falls back to the built-in art, so the keys never
     * disappear: the built-in entry itself cannot be removed.
     */
    public static boolean remove(Context context, String id) {
        if (id == null || BUILTIN.equals(id)) return false;
        boolean active = id.equals(activeId(context));
        deleteTree(new File(packsDir(context), id));
        boolean gone = !new File(packsDir(context), id).exists();
        if (gone && active) activate(context, BUILTIN);
        return gone;
    }

    /**
     * Adds a pack read from an open stream, under the given display name, and makes it the one in
     * use. Returns its id, or null when the file carries no key art at all - a file that is not a
     * pack must neither be listed nor disturb what is already installed.
     */
    public static String add(Context context, InputStream zip, String name) throws IOException {
        migrate(context);
        File staging = new File(context.getCacheDir(), "note-skin-staging");
        deleteTree(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) throw new IOException("无法写入暂存目录");
        int supplied = 0;
        try (ZipInputStream input = new ZipInputStream(zip)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String path = entry.getName().replace('\\', '/');
                if (path.startsWith("__MACOSX/") || path.contains("/__MACOSX/")) continue;
                int slot = slotFor(path);
                if (slot == 0) continue;
                // The entry is kept only once its bytes are known to decode, so a file merely
                // named "tap.png" cannot replace a working key with an unreadable one.
                byte[] bytes = readAll(input, MAX_ENTRY_BYTES);
                if (bytes == null || BitmapFactory.decodeByteArray(bytes, 0, bytes.length) == null) continue;
                write(new File(staging, SLOT_FILES[slot]), bytes);
                if (slot != BODY_SLOT) supplied++;
            }
        }
        if (supplied == 0) {
            deleteTree(staging);
            return null;
        }
        File root = packsDir(context);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("无法写入资源包目录");
        String id = idFor(root, name);
        File destination = new File(root, id);
        deleteTree(destination);
        if (!staging.renameTo(destination)) {
            copyTree(staging, destination);
            deleteTree(staging);
        }
        writeName(destination, name);
        activate(context, id);
        return id;
    }

    /** Re-importing under a name already in the list updates that pack instead of piling up copies. */
    private static String idFor(File root, String name) {
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && name.equals(readName(child))) return child.getName();
            }
            int highest = 0;
            for (File child : children) {
                if (!child.getName().startsWith("pack-")) continue;
                try {
                    highest = Math.max(highest, Integer.parseInt(child.getName().substring(5)));
                } catch (NumberFormatException ignored) {
                    // A directory we did not write is skipped rather than being given a number.
                }
            }
            return "pack-" + (highest + 1);
        }
        return "pack-1";
    }

    private static File packsDir(Context context) { return new File(context.getFilesDir(), PACKS_DIR); }
    private static File activeFile(Context context) { return new File(context.getFilesDir(), ACTIVE_FILE); }

    private static void writeName(File directory, String name) throws IOException {
        write(new File(directory, NAME_FILE), name.getBytes("UTF-8"));
    }

    private static String readName(File directory) {
        try (InputStream input = new FileInputStream(new File(directory, NAME_FILE))) {
            byte[] bytes = readAll(input, 4096);
            String name = bytes == null ? "" : new String(bytes, "UTF-8").trim();
            return name.isEmpty() ? directory.getName() : name;
        } catch (IOException error) {
            return directory.getName();
        }
    }

    /**
     * Folds the single pack an older build kept in {@code note-skin/} into the pack list, so a pack
     * the user already imported is carried over instead of being silently dropped. The old
     * directory is only cleared once the copy is in place.
     */
    private static void migrate(Context context) {
        File legacy = new File(context.getFilesDir(), LEGACY_DIR);
        if (!legacy.isDirectory()) return;
        boolean hasArt = false;
        for (int slot = TAP; slot <= BODY_SLOT && !hasArt; slot++) {
            hasArt = new File(legacy, SLOT_FILES[slot]).isFile();
        }
        if (hasArt) {
            try {
                File root = packsDir(context);
                if (!root.mkdirs() && !root.isDirectory()) throw new IOException("无法写入资源包目录");
                File target = new File(root, LEGACY_ID);
                deleteTree(target);
                copyTree(legacy, target);
                writeName(target, DEFAULT_NAME);
                File marker = activeFile(context);
                if (!marker.isFile()) activate(context, LEGACY_ID);
            } catch (IOException error) {
                // Left alone: the old directory stays put and the next start can try again.
                return;
            }
        }
        deleteTree(legacy);
    }

    /**
     * The art that ships with the app. It is the default look: a view falls back to it for any
     * key the imported pack does not supply, so the imported pack still wins where it speaks.
     * The canvases are shrunk before decoding - a 1024-wide bar is drawn about 56 pixels wide,
     * and a 36-megabyte hit-effect atlas would be memory spent on nothing.
     */
    public static NoteSkin bundled(Context context) {
        NoteSkin skin = new NoteSkin();
        try {
            for (int slot = TAP; slot <= FLICK; slot++) {
                int sample = slot == HOLD ? HOLD_SAMPLE : BAR_SAMPLE;
                skin.notes[slot] = decodeAsset(context, BUNDLED_NOTE_FILES[slot], sample);
                skin.doubles[slot] = decodeAsset(context, BUNDLED_DOUBLE_FILES[slot], sample);
            }
            skin.hitFx = decodeAsset(context, "hit_fx.png", EFFECT_SAMPLE);
            readLayout(context, skin);
        } catch (IOException error) {
            return new NoteSkin();  // the art is part of the build; without it, draw the keys
        }
        return skin;
    }

    private static Bitmap decodeAsset(Context context, String name, int sample) {
        try {
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            InputStream input = context.getAssets().open(BUNDLED_DIR + "/" + name);
            try {
                return BitmapFactory.decodeStream(input, null, options);
            } finally {
                input.close();
            }
        } catch (IOException error) {
            return null;
        }
    }

    /**
     * Reads the bundled alignment spec. Body rects come out in the decoded bitmap's pixels, which
     * are the source pixels divided by the shrink factor, and the hold caps are stored in the same
     * space so the view can size them without knowing how far the art was shrunk.
     */
    private static void readLayout(Context context, NoteSkin skin) throws IOException {
        JSONObject layout;
        try {
            layout = new JSONObject(readAsset(context, BUNDLED_DIR + "/layout.json"));
        } catch (Exception error) {
            return;  // without the spec the art stretches over the whole key box, as a pack's does
        }
        int[] bodyX = intPair(layout.optJSONArray("bodyX"));
        JSONObject bodyY = layout.optJSONObject("bodyY");
        if (bodyX == null || bodyY == null) return;
        String[] bases = {"click", "drag", "hold", "flick"};
        int[] samples = {BAR_SAMPLE, BAR_SAMPLE, HOLD_SAMPLE, BAR_SAMPLE};
        for (int index = 0; index < bases.length; index++) {
            int slot = index + TAP;
            int[] top = intPair(bodyY.optJSONArray(bases[index]));
            if (top != null) {
                float scale = 1f / samples[index];
                skin.bodies[slot] = new float[] {bodyX[0] * scale, top[0] * scale, bodyX[1] * scale, top[1] * scale};
                skin.doubleBodies[slot] = skin.bodies[slot];
            }
        }
        // The hold's rows are the caps' job: bodyY says where the straight ribbon runs, and the
        // caps are cut off it, so both come out in the same shrunken space.
        int[] atlas = intPair(layout.optJSONArray("holdAtlas"));
        if (atlas != null) {
            float scale = 1f / HOLD_SAMPLE;
            skin.holdCaps = new int[] {Math.round(atlas[0] * scale), Math.round(atlas[1] * scale)};
        }
        int[] atlasMH = intPair(layout.optJSONArray("holdAtlasMH"));
        if (atlasMH != null) {
            float scale = 1f / HOLD_SAMPLE;
            skin.holdCapsDouble = new int[] {Math.round(atlasMH[0] * scale), Math.round(atlasMH[1] * scale)};
        }
        int[] grid = intPair(layout.optJSONArray("hitFx"));
        if (grid != null && skin.hitFx != null) {
            skin.hitFxColumns = Math.max(1, grid[0]);
            skin.hitFxRows = Math.max(1, grid[1]);
        }
        skin.hitFxScale = (float) layout.optDouble("hitFxScale", 1);
        skin.hitFxSeconds = (float) layout.optDouble("hitFxDuration", 0);
        skin.hitFxTinted = layout.optBoolean("hitFxTinted", true);
    }

    private static int[] intPair(JSONArray array) {
        if (array == null || array.length() != 2) return null;
        return new int[] {array.optInt(0), array.optInt(1)};
    }

    private static String readAsset(Context context, String name) throws IOException {
        InputStream input = context.getAssets().open(name);
        try {
            byte[] bytes = readAll(input, 1024 * 1024);
            return bytes == null ? "" : new String(bytes, "UTF-8");
        } finally {
            input.close();
        }
    }

    /** True when any imported pack holds key art, without decoding it. */
    public static boolean isInstalled(Context context) {
        migrate(context);
        File[] children = packsDir(context).listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            for (int slot = TAP; slot <= BODY_SLOT; slot++) {
                if (new File(child, SLOT_FILES[slot]).isFile()) return true;
            }
        }
        return false;
    }

    public static NoteSkin importZip(Context context, Uri uri) throws IOException {
        return importZip(context, uri, nameFor(uri));
    }

    /**
     * Installs the document the picker handed over, named after it. The name is what the
     * management screen lists, so "Phiround.zip" reads as "Phiround" rather than as a cache path.
     */
    public static NoteSkin importZip(Context context, Uri uri, String name) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("无法读取所选文件");
        try {
            add(context, input, name == null || name.trim().isEmpty() ? DEFAULT_NAME : name);
            return load(context);
        } finally {
            input.close();
        }
    }

    /**
     * Installs the document the picker handed over and returns the id of the pack it became, or
     * null when the file carries no key art. The id is what tells "added" from "ignored", which
     * the loaded skin cannot express: a file that is not a pack leaves whatever was already in use
     * exactly as it was, so the skin looks the same either way.
     */
    public static String importPack(Context context, Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("无法读取所选文件");
        try {
            return add(context, input, nameFor(uri));
        } finally {
            input.close();
        }
    }

    /** Adds a pack and returns the keys now in use; a non-pack leaves the keys as they are. */
    public static NoteSkin install(Context context, InputStream zip) throws IOException {
        add(context, zip, DEFAULT_NAME);
        return load(context);
    }

    /** Removes every imported pack so the built-in keys come back. */
    public static void clear(Context context) {
        deleteTree(packsDir(context));
        deleteTree(new File(context.getFilesDir(), LEGACY_DIR));
        activate(context, BUILTIN);
    }

    /** A display name for a picked document: its file name without the extension. */
    private static String nameFor(Uri uri) {
        String raw = uri.getLastPathSegment();
        if (raw == null) return DEFAULT_NAME;
        String name = raw.substring(raw.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.trim();
        return name.isEmpty() ? DEFAULT_NAME : name;
    }

    private static Bitmap decode(File file) {
        return file.isFile() ? BitmapFactory.decodeFile(file.getAbsolutePath()) : null;
    }

    /** Maps a ZIP entry path to a slot number, or 0 when the name means nothing to the pack. */
    private static int slotFor(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : IMAGE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        String key = normalise(name);
        if (key.isEmpty()) return 0;
        for (int index = 0; index < NOTE_TYPES.length; index++) {
            for (String alias : NOTE_NAMES[index]) if (key.equals(alias)) return NOTE_TYPES[index];
        }
        for (String alias : BODY_NAMES) if (key.equals(alias)) return BODY_SLOT;
        return 0;
    }

    /** Lower-cases and drops the separators, so "Note_Tap", "note-tap" and "note tap" agree. */
    private static String normalise(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            if (current == '_' || current == '-' || current == ' ' || current == '.') continue;
            out.append(Character.toLowerCase(current));
        }
        return out.toString();
    }

    /** Reads an entry into memory, refusing anything past {@code limit} so a zip bomb cannot OOM. */
    private static byte[] readAll(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > limit) return null;
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }

    private static void copyTree(File from, File to) throws IOException {
        if (!to.mkdirs() && !to.isDirectory()) throw new IOException("无法写入 " + to);
        File[] children = from.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                copyTree(child, target);
            } else {
                try (InputStream input = new FileInputStream(child)) {
                    write(target, readAll(input, MAX_ENTRY_BYTES));
                }
            }
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
