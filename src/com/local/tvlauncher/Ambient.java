package com.local.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
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
 * - pauses while the remote is being used and stops completely when off screen.
 */
final class Ambient extends View {
    private static final long GRAIN_FRAME_MS = 1000 / 12;

    private final long frameMs;
    private final boolean beam;
    private final boolean bokeh;
    private final boolean grain;
    private final Random rnd = new Random(42);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final long start = SystemClock.uptimeMillis();

    private boolean running;
    private long pausedUntil;
    private long lastGrain;

    // Bokeh: soft discs drifting on slow Lissajous paths.
    private Bitmap disc;
    private final float[] bx = new float[11], by = new float[11], br = new float[11];
    private final float[] bs = new float[11], bp = new float[11], ba = new float[11];
    private final boolean[] warm = new boolean[11];
    private PorterDuffColorFilter accentTint, textTint;

    // Beam: one pre-rendered cone, swaying and breathing; dust specks inside it.
    private Bitmap cone;
    private final float[] dx = new float[40], dy = new float[40], ds = new float[40], dp = new float[40];

    // Grain: a small noise tile repeated over the screen, shifted every grain frame.
    private Paint grainPaint;
    private final Matrix grainShift = new Matrix();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (SystemClock.uptimeMillis() >= pausedUntil) invalidate();
            postDelayed(this, (beam || bokeh) ? frameMs : GRAIN_FRAME_MS);
        }
    };

    Ambient(Context c, int fps, boolean beam, boolean bokeh, boolean grain) {
        super(c);
        this.frameMs = 1000 / Math.max(1, fps);
        this.beam = beam;
        this.bokeh = bokeh;
        this.grain = grain;
        setWillNotDraw(false);
        if (bokeh) initBokeh();
        if (beam) initDust();
        if (grain) initGrain();
    }

    /** Start or stop the animation loop. */
    void setRunning(boolean on) {
        if (on == running) return;
        running = on;
        removeCallbacks(tick);
        if (on) post(tick);
    }

    /** Freeze for a moment, so moving across the tiles gets the whole GPU. */
    void pauseFor(long ms) {
        pausedUntil = SystemClock.uptimeMillis() + ms;
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

    private void initDust() {
        for (int i = 0; i < dx.length; i++) {
            dx[i] = rnd.nextFloat();
            dy[i] = rnd.nextFloat();
            ds[i] = 0.004f + rnd.nextFloat() * 0.012f;
            dp[i] = rnd.nextFloat() * 6.28f;
        }
    }

    private void initGrain() {
        int n = 192;
        int[] px = new int[n * n];
        for (int i = 0; i < px.length; i++) {
            int v = rnd.nextInt(256);
            px[i] = Color.argb(rnd.nextInt(56), v, v, v);
        }
        Bitmap tile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        grainPaint = new Paint();
        grainPaint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        grainPaint.setAlpha(70);
    }

    /** The beam cone, rendered once at a quarter of the screen size and stretched when drawn. */
    private Bitmap cone(int w, int h) {
        int cw = Math.max(64, w / 4), ch = Math.max(36, h / 4);
        Bitmap b = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        // Light comes from the top right corner and widens towards the lower left.
        Path path = new Path();
        path.moveTo(cw * 1.02f, -ch * 0.05f);
        path.lineTo(-cw * 0.05f, ch * 0.55f);
        path.lineTo(cw * 0.25f, ch * 1.05f);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(cw, 0, cw * 0.1f, ch * 0.8f,
                new int[]{Ui.alpha(Color.WHITE, 0.55f), Ui.alpha(Color.WHITE, 0.16f), 0},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        Blur.box(b, 6, 2);
        return b;
    }

    // ------------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;

        if (bokeh) drawBokeh(canvas, w, h, t);
        if (beam) drawBeam(canvas, w, h, t);
        if (grain) drawGrain(canvas, w, h);
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

    private void drawBeam(Canvas canvas, int w, int h, float t) {
        if (cone == null) cone = cone(w, h);
        float breathe = 0.75f + 0.25f * (float) Math.sin(t * 0.35f);
        float sway = 1.2f * (float) Math.sin(t * 0.12f);
        canvas.save();
        canvas.rotate(sway, w, 0);
        paint.setAlpha((int) (255 * 0.55f * breathe));
        canvas.drawBitmap(cone, null, new Rect(0, 0, w, h), paint);
        canvas.restore();

        // Dust: drifting specks that glint only inside the beam.
        Paint dot = paint;
        dot.setColor(Color.WHITE);
        for (int i = 0; i < dx.length; i++) {
            float x = (dx[i] + t * ds[i] * 0.5f) % 1f;
            float y = (dy[i] + 0.02f * (float) Math.sin(t * 0.4f + dp[i])) % 1f;
            float inBeam = beamStrength(x, y);
            if (inBeam <= 0) continue;
            float twinkle = 0.5f + 0.5f * (float) Math.sin(t * 1.3f + dp[i] * 3);
            dot.setAlpha((int) (255 * 0.55f * inBeam * twinkle * breathe));
            canvas.drawCircle(x * w, y * h, 1.2f + 1.6f * twinkle, dot);
        }
    }

    /** Roughly how much beam light is at (x, y), both 0..1: 1 near the source, 0 outside. */
    private static float beamStrength(float x, float y) {
        float along = (1f - x) * 0.7f + y * 0.3f;          // distance from the top right source
        float width = 0.12f + along * 0.55f;                 // the cone widens
        float centre = 0.05f + (1f - x) * 0.62f;            // beam centre line in y for this x
        float off = Math.abs(y - centre) / width;
        if (off >= 1f) return 0f;
        return (1f - off) * (1f - Math.min(1f, along));
    }

    private void drawGrain(Canvas canvas, int w, int h) {
        long now = SystemClock.uptimeMillis();
        if (now - lastGrain >= GRAIN_FRAME_MS) {
            lastGrain = now;
            grainShift.setTranslate(rnd.nextInt(192), rnd.nextInt(192));
            grainPaint.getShader().setLocalMatrix(grainShift);
        }
        canvas.drawRect(0, 0, w, h, grainPaint);
    }
}
