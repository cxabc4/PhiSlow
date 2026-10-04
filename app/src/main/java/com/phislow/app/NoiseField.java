/* SPDX-License-Identifier: GPL-3.0-only
 * Geometry-mask composition adapted from Phira-Pro contributors, revision
 * 5c28e71955d4ee74fd81f220efdccbdec4d727ed, prpr/src/core/block_mask.rs.
 * Java drawing and animated noise are original; no exported official shaders/textures.
 */
package com.phislow.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.SystemClock;

/** Per-view low-resolution masks, with separate disabled and active composition. */
final class NoiseField {
    private final Layer disabled = new Layer(), active = new Layer();
    private final BlockArea.Transform geometry = new BlockArea.Transform();
    private final Paint stamp = new Paint(), blit = new Paint(), hover = new Paint(Paint.ANTI_ALIAS_FLAG);

    NoiseField() { stamp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD)); }

    private static final class Layer {
        Bitmap normal, subtract, green, output;
        Canvas n, s, g;
        int[] np, sp, gp, pixels, coverage;
        int width, height;
        void size(int w, int h) {
            if (w == width && h == height) return;
            if (normal != null) { normal.recycle(); subtract.recycle(); green.recycle(); output.recycle(); }
            width = w; height = h;
            normal = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            subtract = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            green = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            n = new Canvas(normal); s = new Canvas(subtract); g = new Canvas(green);
            np = new int[w * h]; sp = new int[w * h]; gp = new int[w * h];
            pixels = new int[w * h]; coverage = new int[w * h];
        }
    }

    boolean draw(Canvas canvas, BlockArea[] areas, double time, RectF view, int phase) {
        boolean visible = false;
        for (BlockArea area : areas) if (area.phase(time) == phase) { visible = true; break; }
        if (!visible || view.width() <= 0 || view.height() <= 0) return false;
        Layer layer = phase == BlockArea.ACTIVE ? active : disabled;
        // Native camera masks use 1/8 resolution. Bound the work on very large tablet displays.
        int w = Math.max(1, Math.min(360, Math.round(view.width() / 8)));
        int h = Math.max(1, Math.round(w * view.height() / view.width()));
        layer.size(w, h);
        layer.n.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        layer.s.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        layer.g.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        for (BlockArea area : areas) {
            if (area.phase(time) != phase) continue;
            area.transform(time, view, geometry);
            float width = Math.abs(geometry.width), height = Math.abs(geometry.height);
            if (width < 0.0001f || height < 0.0001f) continue;
            float opacity = phase == BlockArea.DISABLED && !area.isActive(area.appearTime)
                ? (float) Math.max(0, Math.min(1, (time - area.appearTime) / 500)) : 1;
            raster(area.subtract ? layer.s : layer.n, view, layer, width, height,
                area.subtract ? 26 : Math.round(255 * opacity));
            if (phase == BlockArea.DISABLED && area.subtract)
                raster(layer.g, view, layer, width, height, Math.round(26 * opacity));
        }
        layer.normal.getPixels(layer.np, 0, w, 0, 0, w, h);
        layer.subtract.getPixels(layer.sp, 0, w, 0, 0, w, h);
        if (phase == BlockArea.DISABLED) layer.green.getPixels(layer.gp, 0, w, 0, 0, w, h);
        for (int i = 0; i < layer.coverage.length; i++) {
            int normal = layer.np[i] >>> 24, subtract = layer.sp[i] >>> 24;
            float subtraction = subtract >= 23 && subtract < 31 ? 1 : 0;
            if (phase == BlockArea.DISABLED) {
                float green = (layer.gp[i] >>> 24) / 255f;
                float t = Math.max(0, Math.min(1, (green - 0.2f) * -10));
                float r = subtraction + t * t * (3 - 2 * t);
                subtraction = Math.min(1, r) * Math.min(1, green * r * 10);
            }
            layer.coverage[i] = Math.round(Math.abs(normal / 255f - subtraction) * 255);
        }
        // The texture phase follows real uptime; seeks only change authored region geometry.
        int tick = (int) (SystemClock.uptimeMillis() / 45);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = y * w + x, cover = layer.coverage[i], edge = 0;
            if (cover < 255) {
                for (int yy = Math.max(0, y - 1); yy <= Math.min(h - 1, y + 1); yy++)
                    for (int xx = Math.max(0, x - 1); xx <= Math.min(w - 1, x + 1); xx++)
                        edge = Math.max(edge, layer.coverage[yy * w + xx] - cover);
            }
            int seed = (x + tick) * 73856093 ^ (y - tick) * 19349663;
            seed ^= seed >>> 13;
            int noise = seed & 63;
            int alpha = phase == BlockArea.ACTIVE ? Math.max(cover * 2 / 3, edge * 4 / 5)
                : cover * 2 / 5;
            int red = phase == BlockArea.ACTIVE ? 140 + noise + edge / 5 : 105 + noise / 2;
            int green = phase == BlockArea.ACTIVE ? 32 + noise / 2 : 25 + noise / 4;
            layer.pixels[i] = (alpha << 24) | (Math.min(255, red) << 16) | (green << 8) | green;
        }
        layer.output.setPixels(layer.pixels, 0, w, 0, 0, w, h);
        canvas.drawBitmap(layer.output, null, view, blit);
        return true;
    }

    private void raster(Canvas canvas, RectF view, Layer layer, float w, float h, int alpha) {
        stamp.setColor(Color.WHITE); stamp.setAlpha(alpha);
        canvas.save();
        canvas.scale(layer.width / view.width(), layer.height / view.height());
        canvas.translate(geometry.x - view.left, geometry.y - view.top);
        canvas.rotate(geometry.rotation);
        canvas.drawRect(-w / 2, -h / 2, w / 2, h / 2, stamp);
        canvas.restore();
    }

    /** Original touch feedback; finger state remains owned by PracticeView. */
    void touch(Canvas canvas, float x, float y, RectF view) {
        hover.setStyle(Paint.Style.STROKE);
        hover.setColor(0xD0FF6868); hover.setStrokeWidth(Math.max(2, view.height() * 0.005f));
        canvas.drawCircle(x, y, view.height() * 0.035f, hover);
    }
}
