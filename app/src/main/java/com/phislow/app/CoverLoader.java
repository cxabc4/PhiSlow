package com.phislow.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes saved library cover art on demand.
 *
 * Illustrations are 1280x675 JPEGs, so every decode is downsampled to the size
 * the caller actually needs and cached in a small LRU keyed by path and target
 * width. RGB_565 keeps a cached bitmap at half the memory of ARGB_8888, which is
 * safe because the illustrations have no transparency.
 */
final class CoverLoader {
    private final Context context;
    private final LruCache<String, Bitmap> cache;

    CoverLoader(Context context) {
        this.context = context.getApplicationContext();
        int maxKilobytes = (int) Math.min(20 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(maxKilobytes) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    /** Returns null when the path is missing or undecodable; callers show a placeholder. */
    Bitmap get(String path, int targetWidth) {
        if (path == null) return null;
        String key = path + '@' + targetWidth;
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;
        Bitmap decoded = decode(path, targetWidth);
        if (decoded != null) cache.put(key, decoded);
        return decoded;
    }

    private Bitmap decode(String path, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = LibraryAssets.open(context, path)) {
            BitmapFactory.decodeStream(input, null, bounds);
        } catch (IOException error) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, targetWidth);
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (InputStream input = LibraryAssets.open(context, path)) {
            return BitmapFactory.decodeStream(input, null, options);
        } catch (IOException error) {
            return null;
        }
    }

    private static int sampleSize(int width, int target) {
        if (target <= 0) return 1;
        int sample = 1;
        while (width / (sample * 2) >= target) sample *= 2;
        return sample;
    }
}
