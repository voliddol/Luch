package com.local.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Animated cinema background: a projector beam with floating dust, drifting bokeh lights and
 * film grain. Built for a weak projector GPU:
 * - 12 or 24 frames per second instead of 60; the motion is so slow that 12 looks smooth;
 * - every shape is a pre-rendered sprite, so a frame is only a few bitmap draws;
 * - stops completely when off screen.
 */
final class Ambient extends View {
    private static final long GRAIN_FRAME_MS = 1000 / 12;

    private final long frameMs;
    private final ProjectorBeam beam;
    private final boolean bokeh;
    private final Grain grain;
    private final Random rnd = new Random(42);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final long start = SystemClock.uptimeMillis();

    private boolean running;

    // Bokeh: soft discs drifting on slow Lissajous paths.
    private Bitmap disc;
    private final float[] bx = new float[11], by = new float[11], br = new float[11];
    private final float[] bs = new float[11], bp = new float[11], ba = new float[11];
    private final boolean[] warm = new boolean[11];
    private PorterDuffColorFilter accentTint, textTint;

    // Beam: a cinema projector's light seen from the side. A soft cone and a few brighter shafts
    // inside it (two sprites, so the shafts can shimmer on their own) and dust.
    static final float[] BRIGHTNESS = {0.55f, 1f, 1.6f, 2.4f};
    static final String[] BRIGHTNESS_NAMES = {"Мягкий", "Обычный", "Яркий", "Очень яркий"};
    static final String[] SOURCES = {"Справа сверху", "Сверху по центру", "Слева сверху"};
    static final String[] COLORS = {"Тёплый, как лампа", "Белый, как ксенон", "Цвет темы"};

    static final float[] WIDTHS = {0.65f, 1f, 1.45f};
    static final String[] WIDTH_NAMES = {"Узкий", "Обычный", "Широкий"};
    static final int[] DUST = {0, 30, 70, 150};
    static final String[] DUST_NAMES = {"Нет", "Мало", "Обычно", "Много"};
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, (beam != null || bokeh) ? frameMs : GRAIN_FRAME_MS);
        }
    };

    Ambient(Context c, int fps, ProjectorBeam beam, boolean bokeh, boolean grain) {
        super(c);
        this.frameMs = 1000 / Math.max(1, fps);
        this.beam = beam;
        this.bokeh = bokeh;
        this.grain = grain ? new Grain() : null;
        setWillNotDraw(false);
        if (bokeh) initBokeh();
    }

    /** Start or stop the animation loop. */
    void setRunning(boolean on) {
        if (on == running) return;
        running = on;
        removeCallbacks(tick);
        if (on) post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setRunning(false);
    }

    // ------------------------------------------------------------------ setup

    private void initBokeh() {
        disc = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(64, 64, 64,
                new int[]{Color.WHITE, Color.WHITE, Ui.alpha(Color.WHITE, 0.35f), 0},
                new float[]{0f, 0.55f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(disc).drawCircle(64, 64, 64, p);
        accentTint = new PorterDuffColorFilter(Ui.ACCENT, PorterDuff.Mode.SRC_IN);
        textTint = new PorterDuffColorFilter(Color.rgb(255, 214, 160), PorterDuff.Mode.SRC_IN);
        for (int i = 0; i < bx.length; i++) {
            bx[i] = rnd.nextFloat();
            by[i] = 0.1f + rnd.nextFloat() * 0.8f;
            br[i] = 0.04f + rnd.nextFloat() * 0.09f;     // radius, fraction of screen width
            bs[i] = 0.02f + rnd.nextFloat() * 0.05f;     // speed
            bp[i] = rnd.nextFloat() * 6.28f;             // phase
            ba[i] = 0.10f + rnd.nextFloat() * 0.16f;     // alpha
            warm[i] = rnd.nextInt(3) == 0;
        }
    }

    // ------------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;

        if (bokeh) drawBokeh(canvas, w, h, t);
        if (beam != null) beam.drawHome(canvas, w, h);
        if (grain != null) grain.draw(canvas, w, h, 1f);
    }

    private void drawBokeh(Canvas canvas, int w, int h, float t) {
        Rect src = new Rect(0, 0, disc.getWidth(), disc.getHeight());
        for (int i = 0; i < bx.length; i++) {
            float x = (bx[i] + 0.06f * (float) Math.sin(t * bs[i] + bp[i])) * w;
            float y = (by[i] + 0.05f * (float) Math.cos(t * bs[i] * 0.8f + bp[i])) * h;
            float r = br[i] * w * (1f + 0.06f * (float) Math.sin(t * 0.3f + bp[i]));
            float pulse = 0.75f + 0.25f * (float) Math.sin(t * 0.25f + bp[i] * 2);
            paint.setColorFilter(warm[i] ? textTint : accentTint);
            paint.setAlpha((int) (255 * ba[i] * pulse));
            canvas.drawBitmap(disc, src, new Rect((int) (x - r), (int) (y - r), (int) (x + r), (int) (y + r)), paint);
        }
        paint.setColorFilter(null);
    }

    /** The same low-resolution grain tile is used over the menu and the clock. */
    static final class Grain {
        private final Matrix shift = new Matrix();
        private final Random random = new Random(42);
        private final Paint paint = new Paint();
        private long lastGrain;

        Grain() {
            int n = 192;
            int[] pixels = new int[n * n];
            for (int i = 0; i < pixels.length; i++) {
                int v = random.nextInt(256);
                pixels[i] = Color.argb(random.nextInt(56), v, v, v);
            }
            Bitmap tile = Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888);
            paint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }

        void draw(Canvas canvas, int w, int h, float fade) {
            long now = SystemClock.uptimeMillis();
            if (now - lastGrain >= GRAIN_FRAME_MS) {
                lastGrain = now;
                shift.setTranslate(random.nextInt(192), random.nextInt(192));
                paint.getShader().setLocalMatrix(shift);
            }
            paint.setAlpha(Math.round(70 * fade));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }
}
