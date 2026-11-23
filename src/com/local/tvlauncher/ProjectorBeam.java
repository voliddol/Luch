package com.local.tvlauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

import java.util.Random;

/** The projector lamp shared by the home background and the clock. Only its aim changes. */
final class ProjectorBeam {
    private final int source, beamColor, dustCount;
    private final float bright, spread;
    private final boolean flicker;
    private final long start = SystemClock.uptimeMillis();
    private final Random rnd = new Random(42);
    private final Paint light = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint dot = new Paint(Paint.DITHER_FLAG);
    private final RectF where = new RectF();
    private final float[] dx = new float[150], dy = new float[150], ds = new float[150], dp = new float[150];
    private Bitmap cone, shafts;
    private int lastWidth, lastHeight;
    private float sx, sy, ux, uy, len, angle, homeLength;

    ProjectorBeam(Prefs prefs) {
        boolean custom = prefs.background() == Wallpaper.BEAM;
        source = custom ? Math.max(0, Math.min(Ambient.SOURCES.length - 1, prefs.beamFrom())) : Prefs.DEFAULT_BEAM_FROM;
        int brightness = custom ? prefs.beamBright() : Prefs.DEFAULT_BEAM_BRIGHT;
        int width = custom ? prefs.beamWidth() : Prefs.DEFAULT_BEAM_WIDTH;
        int dust = custom ? prefs.beamDust() : Prefs.DEFAULT_BEAM_DUST;
        bright = Ambient.BRIGHTNESS[Math.max(0, Math.min(Ambient.BRIGHTNESS.length - 1, brightness))];
        spread = (source == 1 ? 0.6f : 0.26f) * Ambient.WIDTHS[Math.max(0, Math.min(Ambient.WIDTHS.length - 1, width))];
        dustCount = Ambient.DUST[Math.max(0, Math.min(Ambient.DUST.length - 1, dust))];
        int index = custom ? prefs.beamColor() : Prefs.DEFAULT_BEAM_COLOR;
        beamColor = index == 1 ? Color.rgb(225, 235, 255) : index == 2 ? Ui.ACCENT : Color.rgb(255, 214, 160);
        flicker = custom ? prefs.flicker() : Prefs.DEFAULT_FLICKER;
        light.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        light.setColorFilter(new PorterDuffColorFilter(beamColor, PorterDuff.Mode.SRC_IN));
        initDust();
    }

    float sourceX(int w) { return w * (source == 0 ? 0.94f : source == 1 ? 0.5f : 0.06f); }

    float sourceY(int h) { return h * (source == 1 ? -0.25f : -0.12f); }

    private float homeX(int w) { return w * (source == 0 ? 0.2f : source == 1 ? 0.5f : 0.8f); }

    float spread() { return spread; }

    private void initDust() {
        for (int i = 0; i < dx.length; i++) {
            dx[i] = rnd.nextFloat();
            dy[i] = rnd.nextFloat();
            ds[i] = 0.004f + rnd.nextFloat() * 0.012f;
            dp[i] = rnd.nextFloat() * 6.28f;
        }
    }

    /** The sprites are static even while the clock sweeps the lamp across the room. */
    private void layout(int w, int h) {
        sx = sourceX(w);
        sy = sourceY(h);
        homeLength = (float) Math.hypot(homeX(w) - sx, h - sy) * 1.15f;
        lastWidth = w;
        lastHeight = h;
        // The soft shafts do not need full panel resolution. Bound both sprite dimensions
        // on 4K panels so the temporary buffers for the blur also stay small.
        float spriteScale = Math.min(0.25f, 768f / (homeLength * Math.max(1f, spread * 2.3f)));
        int cw = Math.max(64, Math.round(homeLength * spriteScale));
        int ch = Math.max(32, Math.round(homeLength * spread * 2.3f * spriteScale));
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

    void drawHome(Canvas canvas, int w, int h) {
        drawFrom(canvas, w, h, sourceX(w), sourceY(h), homeX(w), h, 1f);
    }

    void drawFrom(Canvas canvas, int w, int h, float originX, float originY, float xAim, float yAim, float fade) {
        if (w != lastWidth || h != lastHeight) layout(w, h);
        sx = originX;
        sy = originY;
        float vx = xAim - sx, vy = yAim - sy, distance = (float) Math.hypot(vx, vy);
        len = Math.max(homeLength, distance * 1.15f);
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        // A projector lamp is steady: a slow, shallow breath, and with film a faint shutter flicker.
        float level = fade * bright * (0.9f + 0.1f * (float) Math.sin(t * 0.35f));
        if (flicker) level *= 0.93f + 0.07f * rnd.nextFloat();
        float sway = 0.6f * (float) Math.sin(t * 0.12f);
        double direction = Math.atan2(vy, vx) + Math.toRadians(sway);
        ux = (float) Math.cos(direction);
        uy = (float) Math.sin(direction);
        angle = (float) Math.toDegrees(direction);
        float half = len * spread * 1.15f;
        where.set(0, -half, len, half);

        canvas.save();
        canvas.translate(sx, sy);
        canvas.rotate(angle);
        drawLight(canvas, cone, where, 0.8f * level);
        // The shafts drift a little against the cone and shimmer, like light through moving smoke.
        canvas.rotate(0.5f * (float) Math.sin(t * 0.21f + 1f));
        drawLight(canvas, shafts, where, 0.4f * level * (0.65f + 0.35f * (float) Math.sin(t * 0.5f)));
        canvas.restore();

        // Dust: drifting specks that glint only inside the beam.
        dot.setColor(beamColor);
        for (int i = 0; i < dustCount; i++) {
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

    /** Place a soft stripe through the clock along the axis of the last beam drawn. */
    void clockMask(Matrix matrix, float x, float y) {
        float along = (x - sx) * ux + (y - sy) * uy;
        matrix.setScale(1f, Math.max(1f, along * spread));
        matrix.postRotate(angle);
        matrix.postTranslate(sx + along * ux, sy + along * uy);
    }
}
