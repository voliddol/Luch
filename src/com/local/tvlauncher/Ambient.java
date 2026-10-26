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
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
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

    // Beam: a cinema projector's light seen from the side. A soft cone and a few brighter shafts
    // inside it (two sprites, so the shafts can shimmer on their own) and dust.
    static final float[] BRIGHTNESS = {0.55f, 1f, 1.6f, 2.4f};
    static final String[] BRIGHTNESS_NAMES = {"Мягкий", "Обычный", "Яркий", "Очень яркий"};
    static final String[] SOURCES = {"Справа сверху", "Сверху по центру", "Слева сверху"};
    static final String[] COLORS = {"Тёплый, как лампа", "Белый, как ксенон", "Цвет темы"};

    private float bright = 1f;
    private int source;
    private int beamColor = Color.rgb(255, 214, 160);
    private boolean flicker = true;
    private Bitmap cone, shafts;
    /** Half-width of the cone: tan of its half angle. */
    private float spread;
    private float sx, sy, ux, uy, len, angle;
    private final Paint light = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final float[] dx = new float[70], dy = new float[70], ds = new float[70], dp = new float[70];

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
        // Light adds up like light does: the beam brightens what is under it instead of covering it.
        light.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        light.setColorFilter(new PorterDuffColorFilter(beamColor, PorterDuff.Mode.SRC_IN));
        if (bokeh) initBokeh();
        if (beam) initDust();
        if (grain) initGrain();
    }

    /**
     * Beam look: {@code brightness} index into {@link #BRIGHTNESS}, {@code from} into
     * {@link #SOURCES}, {@code color} into {@link #COLORS}; {@code flicker} adds a film projector's
     * faint shutter flicker.
     */
    void beamStyle(int brightness, int from, int color, boolean flicker) {
        bright = BRIGHTNESS[Math.max(0, Math.min(BRIGHTNESS.length - 1, brightness))];
        source = Math.max(0, Math.min(SOURCES.length - 1, from));
        beamColor = color == 1 ? Color.rgb(225, 235, 255) : color == 2 ? Ui.ACCENT : Color.rgb(255, 214, 160);
        this.flicker = flicker;
        light.setColorFilter(new PorterDuffColorFilter(beamColor, PorterDuff.Mode.SRC_IN));
        cone = null;
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

    /**
     * Places the projector for this screen size and renders the sprites. They are drawn along
     * the x axis (lens at the left, light going right) at a quarter of the size, then rotated
     * and stretched into place when drawn.
     */
    private void layoutBeam(int w, int h) {
        float tx, ty;
        // The lens sits above the screen, so only the light is seen, already spread out.
        // Straight down the beam is short, so it opens wider to fill the screen.
        if (source == 1) { sx = w * 0.5f; sy = -h * 0.25f; tx = w * 0.5f; ty = h; spread = 0.6f; }
        else if (source == 2) { sx = w * 0.06f; sy = -h * 0.12f; tx = w * 0.8f; ty = h; spread = 0.26f; }
        else { sx = w * 0.94f; sy = -h * 0.12f; tx = w * 0.2f; ty = h; spread = 0.26f; }
        float vx = tx - sx, vy = ty - sy, d = (float) Math.hypot(vx, vy);
        ux = vx / d;
        uy = vy / d;
        len = d * 1.15f;
        angle = (float) Math.toDegrees(Math.atan2(vy, vx));

        int cw = Math.max(64, Math.round(len / 4));
        int ch = Math.max(32, Math.round(len * spread * 2.3f / 4));
        float mid = ch / 2f, half = cw * spread;

        // The cone: brightest at the lens, fading out before it reaches the far end.
        cone = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Path path = new Path();
        path.moveTo(0, mid - 1);
        path.lineTo(cw, mid - half);
        path.lineTo(cw, mid + half);
        path.lineTo(0, mid + 1);
        path.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, cw, 0,
                new int[]{Ui.alpha(Color.WHITE, 0.95f), Ui.alpha(Color.WHITE, 0.5f),
                        Ui.alpha(Color.WHITE, 0.18f), 0},
                new float[]{0f, 0.25f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(cone).drawPath(path, p);
        Blur.box(cone, Math.max(2, ch / 16), 2);

        // Shafts: thin brighter wedges from the lens, the "rays" seen in a smoky hall.
        shafts = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(shafts);
        p.setShader(new LinearGradient(0, 0, cw, 0,
                new int[]{Ui.alpha(Color.WHITE, 0.6f), Ui.alpha(Color.WHITE, 0.22f), 0},
                new float[]{0f, 0.35f, 0.85f}, Shader.TileMode.CLAMP));
        Random r = new Random(7);
        for (int i = 0; i < 7; i++) {
            float at = (r.nextFloat() * 2 - 1) * spread * 0.8f;   // direction inside the cone
            float wd = spread * (0.03f + r.nextFloat() * 0.06f);  // shaft width
            Path s = new Path();
            s.moveTo(0, mid);
            s.lineTo(cw, mid + cw * (at - wd));
            s.lineTo(cw, mid + cw * (at + wd));
            s.close();
            sc.drawPath(s, p);
        }
        Blur.box(shafts, Math.max(1, ch / 60), 2);

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
        if (cone == null) layoutBeam(w, h);
        // A projector lamp is steady: a slow, shallow breath, and with film a faint shutter flicker.
        float level = bright * (0.9f + 0.1f * (float) Math.sin(t * 0.35f));
        if (flicker) level *= 0.93f + 0.07f * rnd.nextFloat();
        float sway = 0.6f * (float) Math.sin(t * 0.12f);
        float half = len * spread * 1.15f;
        RectF where = new RectF(0, -half, len, half);

        canvas.save();
        canvas.translate(sx, sy);
        canvas.rotate(angle + sway);
        drawLight(canvas, cone, where, 0.8f * level);
        // The shafts drift a little against the cone and shimmer, like light through moving smoke.
        canvas.rotate(0.5f * (float) Math.sin(t * 0.21f + 1f));
        drawLight(canvas, shafts, where, 0.4f * level * (0.65f + 0.35f * (float) Math.sin(t * 0.5f)));
        canvas.restore();

        // Dust: drifting specks that glint only inside the beam.
        Paint dot = paint;
        dot.setColor(beamColor);
        for (int i = 0; i < dx.length; i++) {
            float x = ((dx[i] + t * ds[i] * 0.5f) % 1f) * w;
            float y = ((dy[i] + 0.02f * (float) Math.sin(t * 0.4f + dp[i]) + 1f) % 1f) * h;
            float inBeam = beamStrength(x, y);
            if (inBeam <= 0) continue;
            float twinkle = 0.5f + 0.5f * (float) Math.sin(t * 1.3f + dp[i] * 3);
            dot.setAlpha((int) Math.min(255, 255 * 0.7f * inBeam * twinkle * level));
            canvas.drawCircle(x, y, 1.2f + 1.8f * twinkle, dot);
        }
    }

    /** Draws a light sprite; above full strength it is added once more, as light would be. */
    private void drawLight(Canvas canvas, Bitmap b, RectF where, float strength) {
        while (strength > 0.01f) {
            light.setAlpha((int) (255 * Math.min(1f, strength)));
            canvas.drawBitmap(b, null, where, light);
            strength -= 1f;
        }
    }

    /** Roughly how much beam light is at screen point (x, y): 1 near the lens, 0 outside the cone. */
    private float beamStrength(float x, float y) {
        float vx = x - sx, vy = y - sy;
        float along = vx * ux + vy * uy;
        if (along <= 0) return 0f;
        float off = Math.abs(vx * uy - vy * ux) / (along * spread);
        if (off >= 1f) return 0f;
        return (1f - off) * Math.max(0f, 1f - along / len);
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
