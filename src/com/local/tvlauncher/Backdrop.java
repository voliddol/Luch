package com.local.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;

/**
 * The home screen background baked into one screen-sized bitmap: base colour, glow or picture,
 * and the shades that keep text readable. The GPU then fills the screen once per frame instead
 * of once per layer, which matters on a weak projector chip. Kept in memory between screens.
 */
final class Backdrop {
    private static Bitmap cached;
    private static String cachedKey;

    private Backdrop() {
    }

    /**
     * The baked background, or null for plain black. For the banner mode only the shades are baked
     * (transparent elsewhere), because the banner under them changes with the focus.
     */
    static synchronized Bitmap get(Context c, Prefs prefs) {
        int mode = prefs.background();
        if (mode == Wallpaper.BLACK) return null;
        // Moving pictures (banner, animations, Ken Burns) lie under this layer: bake only the shades.
        boolean overlayOnly = Wallpaper.live(mode) || (mode == Wallpaper.IMAGE && prefs.kenBurns());
        // Only a still picture needs every pixel. Shades and glow are smooth gradients: a quarter
        // of the resolution, stretched with filtering, looks the same and takes 1/16 of the memory.
        boolean fullRes = mode == Wallpaper.IMAGE && !overlayOnly;
        int w = c.getResources().getDisplayMetrics().widthPixels;
        int h = c.getResources().getDisplayMetrics().heightPixels;
        if (!fullRes) {
            w = Math.max(1, w / 4);
            h = Math.max(1, h / 4);
        }
        String key = prefs.backgroundStamp() + "/" + prefs.theme() + "/" + w + "x" + h;
        if (key.equals(cachedKey) && cached != null && !cached.isRecycled()) return cached;

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (!overlayOnly) canvas.drawColor(Ui.BG);

        if (mode == Wallpaper.GLOW) {
            glow(canvas, w, h, 0.78f, 0.18f, 0.85f, 0.30f);
            glow(canvas, w, h, 0.12f, 0.95f, 0.55f, 0.10f);
        } else if (mode == Wallpaper.IMAGE && !overlayOnly) {
            Bitmap pic = Wallpaper.load(c);
            if (pic != null) {
                drawCenterCrop(canvas, pic, w, h);
                pic.recycle();
            }
        }

        // The beam is light in a dark hall: lighter shades, or they would hide most of it.
        float k = mode == Wallpaper.BEAM ? 0.6f : 1f;
        shade(canvas, GradientDrawable.Orientation.LEFT_RIGHT, 0.85f * k, 0, 0, w * 65 / 100, h);
        shade(canvas, GradientDrawable.Orientation.BOTTOM_TOP, 0.95f, 0, h - h * 60 / 100, w, h);
        shade(canvas, GradientDrawable.Orientation.TOP_BOTTOM, 0.55f * k * k, 0, 0, w, h / 4);

        cached = out;
        cachedKey = key;
        return out;
    }

    /** Forget the cached bitmap, so its memory can be returned to the system. */
    static synchronized void release() {
        cached = null;
        cachedKey = null;
    }

    private static void glow(Canvas canvas, int w, int h, float cx, float cy, float radius, float strength) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{Ui.alpha(Ui.ACCENT, strength), Ui.alpha(Ui.ACCENT, strength * 0.35f), 0});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(w * radius);
        g.setDither(true);
        g.setBounds(0, 0, w, h);
        g.draw(canvas);
    }

    private static void shade(Canvas canvas, GradientDrawable.Orientation o, float strength,
                              int l, int t, int r, int b) {
        GradientDrawable g = new GradientDrawable(o, new int[]{Ui.alpha(Ui.BG, strength), Ui.alpha(Ui.BG, 0)});
        g.setDither(true);
        g.setBounds(l, t, r, b);
        g.draw(canvas);
    }

    private static void drawCenterCrop(Canvas canvas, Bitmap pic, int w, int h) {
        float scale = Math.max(w / (float) pic.getWidth(), h / (float) pic.getHeight());
        int sw = Math.round(w / scale);
        int sh = Math.round(h / scale);
        int sx = (pic.getWidth() - sw) / 2;
        int sy = (pic.getHeight() - sh) / 2;
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawBitmap(pic, new Rect(sx, sy, sx + sw, sy + sh), new Rect(0, 0, w, h), p);
    }
}
