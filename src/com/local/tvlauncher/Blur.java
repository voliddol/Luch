package com.local.tvlauncher;

import android.graphics.Bitmap;

/** Box blur for tiny bitmaps; a few passes approximate a gaussian blur. */
final class Blur {
    private Blur() {
    }

    static void box(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] a = new int[w * h];
        int[] b = new int[w * h];
        bmp.getPixels(a, 0, w, 0, 0, w, h);
        for (int p = 0; p < passes; p++) {
            pass(a, b, w, h, radius, 1, w);   // horizontal: a -> b
            pass(b, a, h, w, radius, w, 1);   // vertical:   b -> a
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h);
    }

    /**
     * Blurs every line of {@code lines} pixels along one axis.
     * {@code step} moves along the line, {@code lineStep} moves to the next line.
     * Edge pixels are repeated, so the borders do not darken.
     */
    private static void pass(int[] src, int[] dst, int len, int lines, int r, int step, int lineStep) {
        int n = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[base + clamp(i, len) * step];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = (sa / n) << 24 | (sr / n) << 16 | (sg / n) << 8 | (sb / n);
                int out = src[base + clamp(i - r, len) * step];
                int in = src[base + clamp(i + r + 1, len) * step];
                sa += (in >>> 24) - (out >>> 24);
                sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                sb += (in & 255) - (out & 255);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }
}
