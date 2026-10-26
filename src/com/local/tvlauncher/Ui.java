package com.local.tvlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * View helpers shared by the home screen and the settings screen.
 * Home-cinema look: dark themes only (a projector shows black as "no light"),
 * one accent colour per theme, glass panels over a full-screen background.
 */
final class Ui {
    /** Dark themes only: a light background would light up the room. */
    static final String[] THEMES = {"Кинозал", "Полночь", "Изумруд", "Неон", "Рубин", "Графит"};

    // {background, card, focused card, accent, text, dim text}
    private static final int[][] PALETTES = {
            {rgb(6, 7, 9), rgb(22, 23, 27), rgb(38, 36, 32), rgb(227, 180, 100), rgb(236, 231, 223), rgb(132, 130, 126)},
            {rgb(5, 7, 12), rgb(18, 22, 32), rgb(30, 38, 56), rgb(110, 170, 255), rgb(230, 236, 245), rgb(128, 136, 152)},
            {rgb(5, 9, 8), rgb(17, 26, 24), rgb(26, 44, 40), rgb(80, 210, 160), rgb(228, 240, 236), rgb(125, 142, 137)},
            {rgb(8, 6, 12), rgb(24, 19, 32), rgb(42, 32, 58), rgb(190, 130, 255), rgb(238, 232, 248), rgb(140, 130, 155)},
            {rgb(10, 6, 7), rgb(28, 18, 20), rgb(50, 28, 32), rgb(255, 110, 120), rgb(245, 232, 234), rgb(150, 128, 132)},
            {rgb(8, 8, 8), rgb(24, 24, 24), rgb(44, 44, 44), rgb(240, 240, 240), rgb(235, 235, 235), rgb(135, 135, 135)},
    };

    static int BG, CARD, CARD_FOCUS, ACCENT, TEXT, TEXT_DIM;
    private static int applied = -1;

    static {
        apply(0);
    }

    /** Switches the colours used by every screen built after this call. */
    static void apply(int theme) {
        if (theme < 0 || theme >= PALETTES.length) theme = 0;
        if (theme == applied) return;
        int[] p = PALETTES[theme];
        BG = p[0];
        CARD = p[1];
        CARD_FOCUS = p[2];
        ACCENT = p[3];
        TEXT = p[4];
        TEXT_DIM = p[5];
        applied = theme;
    }

    private static int rgb(int r, int g, int b) {
        return Color.rgb(r, g, b);
    }

    static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    /** Unfocused tiles are a bit dimmer so the focused one stands out. */
    static final float DIM = 0.6f;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    /** Screen height in dp, to scale the layout down on short screens. */
    static float heightDp(Context c) {
        return c.getResources().getDisplayMetrics().heightPixels / c.getResources().getDisplayMetrics().density;
    }

    static float widthDp(Context c) {
        return c.getResources().getDisplayMetrics().widthPixels / c.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------ text

    /** Variable font shipped in assets, if present; otherwise the system font is used. */
    private static final String FONT = "Manrope.ttf";
    private static Boolean hasFont;
    private static final Typeface[] faces = new Typeface[10];

    /** Typeface of the given weight (100..900). */
    static Typeface face(Context c, int weight) {
        int i = Math.max(1, Math.min(9, weight / 100));
        if (faces[i] != null) return faces[i];
        Typeface t = null;
        if (hasFont == null) {
            try {
                c.getAssets().open(FONT).close();
                hasFont = true;
            } catch (Exception e) {
                hasFont = false;
            }
        }
        if (hasFont && Build.VERSION.SDK_INT >= 26) {
            try {
                t = new Typeface.Builder(c.getAssets(), FONT)
                        .setFontVariationSettings("'wght' " + (i * 100)).build();
            } catch (Exception ignored) {
            }
        }
        if (t == null) {
            if (i <= 2) t = Typeface.create("sans-serif-thin", Typeface.NORMAL);
            else if (i == 3) t = Typeface.create("sans-serif-light", Typeface.NORMAL);
            else if (i >= 6) t = Typeface.DEFAULT_BOLD;
            else t = Typeface.DEFAULT;
        }
        faces[i] = t;
        return t;
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face(c, bold ? 600 : 400));
        return t;
    }

    static TextView light(Context c, float sp, int color) {
        TextView t = text(c, sp, color, false);
        t.setTypeface(face(c, 300));
        return t;
    }

    /** Small spaced capitals for section titles. */
    static TextView caption(Context c, String s) {
        TextView t = text(c, 13, ACCENT, true);
        t.setText(s.toUpperCase());
        t.setLetterSpacing(0.18f);
        return t;
    }

    /** Small outlined label, like a genre tag. */
    static TextView chip(Context c, String s) {
        TextView t = text(c, 12, TEXT, false);
        t.setText(s);
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dp(c, 5));
        d.setStroke(dp(c, 1), alpha(TEXT, 0.55f));
        t.setBackground(d);
        return t;
    }

    // -------------------------------------------------------------- surfaces

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }


    /** Background that lights up when the view has focus. */
    static StateListDrawable focusable(Context c, int normal, int focused, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, focused, radiusDp));
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, focused, radiusDp));
        s.addState(new int[]{}, rounded(c, normal, radiusDp));
        return s;
    }

    /**
     * Top navigation pill. Focused: filled glass; current tab: bright text; others: dim text.
     */
    static TextView pill(Context c, String s) {
        final TextView t = text(c, 15, TEXT_DIM, false);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setPadding(dp(c, 18), dp(c, 8), dp(c, 18), dp(c, 8));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(c, alpha(Color.WHITE, 0.22f), 10));
        bg.addState(new int[]{android.R.attr.state_selected}, rounded(c, alpha(Color.WHITE, 0.08f), 10));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        t.setBackground(bg);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                pillColor(t);
            }
        });
        return t;
    }

    static void pillColor(TextView t) {
        t.setTextColor(t.isFocused() || t.isSelected() ? TEXT : TEXT_DIM);
    }

    /** Accent frame with a thin dark line inside, so it shows even on a white banner. */
    private static Drawable outline(Context c, float radiusDp) {
        int outer = dp(c, 3);
        GradientDrawable accent = new GradientDrawable();
        accent.setColor(Color.TRANSPARENT);
        accent.setCornerRadius(dp(c, radiusDp));
        accent.setStroke(outer, ACCENT);
        GradientDrawable dark = new GradientDrawable();
        dark.setColor(Color.TRANSPARENT);
        dark.setCornerRadius(Math.max(0, dp(c, radiusDp) - outer));
        dark.setStroke(dp(c, 2), BG);
        LayerDrawable both = new LayerDrawable(new Drawable[]{accent, dark});
        both.setLayerInset(1, outer, outer, outer, outer);
        return both;
    }

    // ----------------------------------------------------------------- tiles

    /**
     * A 16:9 app tile {@code hDp} tall. TV apps show their banner; others show the icon with the
     * name inside. On focus it grows and gets an accent frame; {@code onFocus} is told about it too.
     */
    static FrameLayout tile(Context c, Apps.App app, int hDp, final View.OnFocusChangeListener onFocus) {
        final FrameLayout card = new FrameLayout(c);
        float radius = hDp < 100 ? 12 : 16;
        card.setBackground(rounded(c, CARD, radius));
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        StateListDrawable fg = new StateListDrawable();
        fg.addState(new int[]{android.R.attr.state_focused}, outline(c, radius));
        fg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        card.setForeground(fg);

        if (app.banner != null) {
            ImageView banner = new ImageView(c);
            banner.setImageDrawable(app.banner);
            banner.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(banner, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            if (app.icon != null) {
                ImageView icon = new ImageView(c);
                icon.setImageDrawable(app.icon);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int s = dp(c, hDp * 0.4f);
                box.addView(icon, new LinearLayout.LayoutParams(s, s));
            }
            TextView name = app.icon != null ? text(c, hDp < 100 ? 12 : 14, TEXT, false)
                    : light(c, hDp * 0.24f, TEXT);
            name.setText(app.label);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(c, 8), dp(c, 5), dp(c, 8), 0);
            box.addView(name, new LinearLayout.LayoutParams(-1, -2));
            card.addView(box, new FrameLayout.LayoutParams(-1, -1));
        }

        // Unfocused tiles are darkened, not made transparent: the background must not show through.
        final View shade = new View(c);
        shade.setBackgroundColor(Color.BLACK);
        shade.setAlpha(1f - DIM);
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                float s = has ? 1.1f : 1f;
                // withLayer: the GPU scales a ready picture instead of redrawing the tile every frame.
                card.animate().scaleX(s).scaleY(s).setDuration(150).withLayer().start();
                shade.animate().alpha(has ? 0f : 1f - DIM).setDuration(150).start();
                if (onFocus != null) onFocus.onFocusChange(v, has);
            }
        });
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(c, hDp * 16 / 9f), dp(c, hDp)));
        return card;
    }
}
