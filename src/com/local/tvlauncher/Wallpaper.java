package com.local.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The user's own background picture. It is copied, scaled to the screen, into device-protected
 * storage, so the home screen can show it even before the user is unlocked.
 */
final class Wallpaper {
    static final String[] MODES = {"Чёрный", "Свечение цвета темы", "Размытый баннер приложения", "Своя картинка",
            "Луч проектора (анимация)", "Огни зала (анимация)"};
    static final int BLACK = 0;
    static final int GLOW = 1;
    static final int BANNER = 2;
    static final int IMAGE = 3;
    static final int BEAM = 4;
    static final int BOKEH = 5;

    /** Modes whose picture changes on screen; only the shades are baked for them. */
    static boolean live(int mode) {
        return mode == BANNER || mode == BEAM || mode == BOKEH;
    }

    private static final String[] EXT = {".jpg", ".jpeg", ".png", ".webp"};

    private Wallpaper() {
    }

    static File file(Context c) {
        return new File(c.createDeviceProtectedStorageContext().getFilesDir(), "wallpaper.jpg");
    }

    /** Pictures the user can pick: Download, Pictures, DCIM and Movies, newest first. */
    static List<File> candidates() {
        File root = Environment.getExternalStorageDirectory();
        List<File> out = new ArrayList<>();
        for (String dir : new String[]{"Download", "Pictures", "DCIM", "Movies"}) {
            collect(new File(root, dir), out, 2);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    private static void collect(File dir, List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth > 0 && !f.getName().startsWith(".")) collect(f, out, depth - 1);
                continue;
            }
            String n = f.getName().toLowerCase();
            for (String e : EXT) {
                if (n.endsWith(e)) {
                    out.add(f);
                    break;
                }
            }
        }
    }

    /**
     * Decodes {@code src} cropped and scaled to exactly fill the screen (smaller pictures are
     * left as they are), or null if it is not a readable picture.
     * A 4000x3000 photo becomes 1920x1080 (8 MB) instead of 2000x1500 (12 MB).
     */
    static Bitmap decodeForScreen(Context c, File src) {
        int sw = c.getResources().getDisplayMetrics().widthPixels;
        int sh = c.getResources().getDisplayMetrics().heightPixels;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), o);
        if (o.outWidth <= 0) return null;
        // Fast first step: power-of-two subsampling while decoding, staying at least screen-sized.
        int sample = 1;
        while (o.outWidth / (sample * 2) >= sw && o.outHeight / (sample * 2) >= sh) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b;
        try {
            b = BitmapFactory.decodeFile(src.getPath(), o);
        } catch (OutOfMemoryError e) {
            return null;
        }
        return b == null ? null : fitScreen(b, sw, sh);
    }

    /** Centre-crops to the screen's shape and scales down to the screen size. Recycles {@code b}. */
    private static Bitmap fitScreen(Bitmap b, int sw, int sh) {
        float scale = Math.max(sw / (float) b.getWidth(), sh / (float) b.getHeight());
        if (scale >= 1f) return b;   // not bigger than the screen: nothing to save
        int cw = Math.min(b.getWidth(), Math.round(sw / scale));
        int ch = Math.min(b.getHeight(), Math.round(sh / scale));
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        Bitmap out;
        try {
            out = Bitmap.createBitmap(b, (b.getWidth() - cw) / 2, (b.getHeight() - ch) / 2, cw, ch, m, true);
        } catch (OutOfMemoryError e) {
            return b;
        }
        if (out != b) b.recycle();
        return out;
    }

    /** Decodes {@code src} scaled down to about the screen size and stores it as the wallpaper. */
    static void set(Context c, File src) throws Exception {
        Bitmap b = decodeForScreen(c, src);
        if (b == null) throw new Exception("не удалось прочитать картинку");
        File tmp = new File(file(c).getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            b.recycle();
        }
        if (!tmp.renameTo(file(c))) throw new Exception("не удалось сохранить");
    }

    /**
     * The stored wallpaper, or null. Full 8-bit colour: 565 would band in the dark gradients.
     * Also fitted to the screen, for a picture stored by an older version at a larger size.
     */
    static Bitmap load(Context c) {
        File f = file(c);
        return f.exists() ? decodeForScreen(c, f) : null;
    }
}
