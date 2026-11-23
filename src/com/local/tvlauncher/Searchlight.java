package com.local.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Random;

/**
 * A handheld spotlight sweeps past the clock, comes back and holds it in the light.
 * The digits stay fixed during the sweep and only appear inside the pool of light.
 */
final class Searchlight extends View {
    private static final long MINUTE_MS = 60_000;
    private static final int[] STOPS_MS = {0, 2070, 3780, 5400};
    private static final int LAST = STOPS_MS.length - 1;

    private final Random rnd = new Random();
    private final Paint overlayPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect viewport = new Rect();
    private final ProjectorBeam beam;
    private final boolean showGrain;
    private Ambient.Grain grain;
    private final Matrix lightingMatrix = new Matrix();
    private Bitmap shades;
    private final float[] wx = new float[STOPS_MS.length], wy = new float[STOPS_MS.length];
    private final int margin;
    private final long frameMs;
    private long start;
    private long clockMinute = -1;
    private String time;
    private int corner = -1;
    private float sx, sy, textWidth;

    Searchlight(Context c, Prefs prefs, ProjectorBeam beam) {
        super(c);
        this.beam = beam;
        margin = prefs.safeMargin();
        showGrain = prefs.background() == Wallpaper.BEAM && prefs.grain();
        int ivory = Color.rgb(250, 242, 225);
        int clear = ivory & 0x00ffffff;
        digits.setShader(new LinearGradient(0, -1f, 0, 1f,
                new int[]{clear, ivory, ivory, clear}, new float[]{0f, 0.19f, 0.81f, 1f},
                Shader.TileMode.CLAMP));
        setBackgroundColor(Color.BLACK);
        frameMs = 1000 / Math.max(1, prefs.animFps());
        digits.setTypeface(Ui.face(c, 200));
        digits.setTextAlign(Paint.Align.CENTER);
    }

    void restart() {
        clockMinute = -1;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        shades = null;
        viewport.set(0, 0, w, h);
        // One size for the whole screen, regardless of time, position or beam width.
        digits.setTextSize(Math.min(w * 52f / 960f, h * 52f / 540f));
        float widest = 0;
        for (char c = '0'; c <= '9'; c++) widest = Math.max(widest, digits.measureText(String.valueOf(c)));
        textWidth = 4f * widest + digits.measureText(":");
        restart();
    }

    private void scene(int w, int h) {
        boolean previous = corner >= 0;
        corner = previous ? (corner + 1 + rnd.nextInt(3)) % 4 : rnd.nextInt(4);
        boolean right = (corner & 1) != 0, bottom = (corner & 2) != 0;
        sx = w * (right ? 1.035f : -0.035f);
        sy = h * (bottom ? 1.06f : -0.06f);
        float mx = Math.min(w / 2f, w * (0.035f + margin / 100f) + textWidth / 2f);
        float my = Math.min(h / 2f, h * (0.035f + margin / 100f) + digits.getTextSize() * 0.55f);
        float oldX = wx[LAST], oldY = wy[LAST];
        // Even with the narrowest beam, the far corner is a legible fallback.
        float x = right ? mx : w - mx;
        float y = bottom ? my : h - my;
        for (int i = 0; i < 40; i++) {
            float tx = mx + rnd.nextFloat() * (w - 2 * mx);
            float ty = my + rnd.nextFloat() * (h - 2 * my);
            if (previous && Math.hypot((tx - oldX) / w, (ty - oldY) / h) < 0.23) continue;
            float dx = tx - sx, dy = ty - sy;
            // Compare the perpendicular half-width of the text to the lit part of the cone.
            float footprint = Math.abs(dy) * textWidth * 0.5f + Math.abs(dx) * digits.getTextSize() * 0.4f;
            if (footprint <= (dx * dx + dy * dy) * beam.spread() * 0.55f) {
                x = tx;
                y = ty;
                break;
            }
        }
        wx[LAST] = x;
        wy[LAST] = y;
        float horizontal = right ? -1 : 1, vertical = bottom ? -1 : 1;
        wx[0] = w * (right ? 0.82f : 0.18f);
        wy[0] = h * (bottom ? 0.28f : 0.72f);
        wx[1] = x - horizontal * w * 0.18f;
        wy[1] = y - vertical * h * 0.24f;
        wx[2] = x + horizontal * w * 0.14f;
        wy[2] = y + vertical * h * 0.12f;
        start = SystemClock.uptimeMillis();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long wallTime = System.currentTimeMillis();
        long minute = wallTime / MINUTE_MS;
        if (minute != clockMinute) {
            scene(w, h);
            time = DateFormat.format("HH:mm", wallTime).toString();
            clockMinute = minute;
        }
        // Uptime controls only the sweep; the next scene always starts at civil HH:mm:00.
        long elapsed = SystemClock.uptimeMillis() - start;
        float x = wx[LAST], y = wy[LAST];
        if (elapsed < STOPS_MS[LAST]) {
            int i = 0;
            while (elapsed >= STOPS_MS[i + 1]) i++;
            float f = (elapsed - STOPS_MS[i]) / (float) (STOPS_MS[i + 1] - STOPS_MS[i]);
            f = f * f * (3 - 2 * f);
            x = wx[i] + (wx[i + 1] - wx[i]) * f;
            y = wy[i] + (wy[i + 1] - wy[i]) * f;
        }
        long untilMinute = MINUTE_MS - wallTime % MINUTE_MS;
        float alpha = Math.min(1f, elapsed / 450f);
        drawScene(canvas, w, h, x, y, alpha, Math.min(1f, untilMinute / 450f));
        if (isShown() && getWindowVisibility() == VISIBLE) {
            postInvalidateDelayed(Math.min(frameMs, untilMinute));
        }
    }

    private void drawScene(Canvas canvas, int w, int h, float x, float y, float alpha, float ink) {
        if (shades == null) {
            int sw = Math.max(1, w / 4), sh = Math.max(1, h / 4);
            shades = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Backdrop.drawShades(new Canvas(shades), Wallpaper.BEAM, sw, sh);
            if (showGrain && grain == null) grain = new Ambient.Grain();
        }
        canvas.drawColor(Ui.alpha(Ui.BG, alpha));
        beam.drawFrom(canvas, w, h, sx, sy, x, y, alpha);
        overlayPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawBitmap(shades, null, viewport, overlayPaint);
        if (grain != null) grain.draw(canvas, w, h, alpha);
        beam.clockMask(lightingMatrix, wx[LAST], wy[LAST]);
        digits.getShader().setLocalMatrix(lightingMatrix);
        digits.setAlpha(Math.round(255 * alpha * ink));
        canvas.drawText(time, wx[LAST], wy[LAST] + digits.getTextSize() * 0.35f, digits);
    }
}
