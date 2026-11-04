package com.local.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The home screen. Full-screen background (baked into one bitmap), clock and tab pills on top,
 * the focused app's logo and name in large type, and the favorites in a glass dock at the bottom.
 * The "Приложения" tab shows all apps as a grid, frequently used ones first.
 */
public class HomeActivity extends Activity {
    private static final String ROW_FAV = "fav";
    private static final String ROW_DOCK = "dock";     // dock showing all apps while there are no favorites
    private static final String ROW_OFTEN = "often";
    private static final String ROW_ALL = "all";
    private static final int TAB_HOME = 0;
    private static final int TAB_APPS = 1;
    /** Autostart only right after power-on, not when the launcher is opened later. */
    private static final long AUTOSTART_WINDOW_MS = 5 * 60_000;
    /** An app needs this many launches to show up under "Часто открываете". */
    private static final int OFTEN_MIN = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    private ImageView bannerBg;
    private ImageView kenBurns;
    private ImageView bakedView;
    private boolean bgReleased;
    private boolean hidden;
    private long kenBurnsPausedUntil;
    private Ambient ambient;
    private Ambient grainView;
    private TextView heroTitle;
    private LinearLayout heroChips;
    private TextView dockLabel;
    private LinearLayout dock;
    private HorizontalScrollView dockScroll;
    private View homePane;
    private ScrollView appsPane;
    private LinearLayout grid;
    private TextView appsTitle;
    private TextView pillHome;
    private TextView pillApps;
    private TextView pillSettings;
    private TextView network;
    private View idleOverlay;

    private int tab = TAB_HOME;
    private List<Apps.App> apps = new ArrayList<>();
    private boolean dirty = true;
    private boolean loading;
    private boolean gridDirty = true;
    private boolean dimmed;
    private String focusTag;
    private boolean leaving;
    private int themeOn;
    private long backgroundOn;
    private int marginOn;
    private int builtVersion = -1;
    private final Map<String, Drawable> softCache = new HashMap<>();
    private ConnectivityManager.NetworkCallback netCallback;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            dirty = true;
            softCache.clear();
            if (hasWindowFocus()) reload();
        }
    };

    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            leaveBootScreen();
        }
    };

    /** Backup for the unlock broadcast: poll, so the boot screen can never get stuck. */
    private final Runnable unlockPoll = new Runnable() {
        @Override public void run() {
            if (isUnlocked()) leaveBootScreen(); else handler.postDelayed(this, 1000);
        }
    };

    private final Runnable idle = new Runnable() {
        @Override public void run() {
            dim();
        }
    };

    // =============================================================== lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        themeOn = prefs.theme();
        Ui.apply(themeOn);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);

        if (!isUnlocked()) {
            // Before unlock: background and a clock. Nothing here may touch credential-protected storage.
            try {
                showBootScreen(root);
            } catch (Throwable ignored) {
                // A plain black screen is fine too.
            }
            registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            handler.postDelayed(unlockPoll, 1000);
            return;
        }

        backgroundOn = prefs.backgroundStamp();
        addBackground(root, true);

        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int m = marginOn = prefs.safeMargin();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding(Ui.dp(this, 48) + w * m / 100, Ui.dp(this, 26) + h * m / 100,
                w * m / 100, Ui.dp(this, 22) + h * m / 100);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        page.addView(topBar());

        FrameLayout content = new FrameLayout(this);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        homePane = homePane();
        appsPane = appsPane();
        content.addView(homePane, new FrameLayout.LayoutParams(-1, -1));
        content.addView(appsPane, new FrameLayout.LayoutParams(-1, -1));
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        idleOverlay = idleOverlay();
        root.addView(idleOverlay, new FrameLayout.LayoutParams(-1, -1));
        setTab(TAB_HOME, false);

        // Show the last known apps at once; the fresh list is loaded in the background.
        List<Apps.App> cached = Apps.cached();
        if (cached != null) apps = cached;

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(dock == null ? unlocked : packagesChanged);
        } catch (Exception ignored) {
        }
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hidden = false;
        if (network != null) watchNetwork(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        hidden = true;
        if (network != null) watchNetwork(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dock == null) return;
        if (prefs.theme() != themeOn || prefs.backgroundStamp() != backgroundOn || prefs.safeMargin() != marginOn) {
            recreate();
            return;
        }
        // Rebuilding the tiles is the expensive part: do it only when something changed.
        if (!apps.isEmpty() && builtVersion != Prefs.version()) rebuild();
        if (dirty) reload();
        restoreBackground();
        wake();
        animate(true);
        maybeAutostart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(idle);
        animate(false);
        stopSlides();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the first favorite.
        focusTag = null;
        if (dock != null) {
            wake();
            setTab(TAB_HOME, true);
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen: "Back" only leaves the apps grid.
        if (tab == TAB_APPS) setTab(TAB_HOME, true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (idleOverlay != null) {
            if (dimmed) {
                // The first key only wakes the menu; it must not also open something.
                if (e.getAction() == KeyEvent.ACTION_UP) wake();
                return true;
            }
            restartIdleTimer();
            // Moving across the tiles gets the whole GPU: the background holds still meanwhile.
            if (ambient != null) ambient.pauseFor(900);
            kenBurnsPausedUntil = SystemClock.uptimeMillis() + 900;
        }
        return super.dispatchKeyEvent(e);
    }

    private void leaveBootScreen() {
        if (leaving || isFinishing()) return;
        leaving = true;
        handler.removeCallbacks(unlockPoll);
        recreate();
    }

    private boolean isUnlocked() {
        UserManager um = (UserManager) getSystemService(USER_SERVICE);
        return um == null || um.isUserUnlocked();
    }


    // ============================================================== background

    /**
     * The background, bottom to top: the moving layer of the current mode (banner, animation or
     * the slowly zooming picture), then the baked picture or shades (see {@link Backdrop}), then
     * the optional film grain. On the boot screen ({@code async} false) nothing moves.
     */
    private void addBackground(FrameLayout root, boolean async) {
        int mode = prefs.background();
        if (mode == Wallpaper.BANNER) {
            bannerBg = new ImageView(this);
            bannerBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bannerBg.setAlpha(0f);
            root.addView(bannerBg, new FrameLayout.LayoutParams(-1, -1));
        } else if (async && (mode == Wallpaper.BEAM || mode == Wallpaper.BOKEH)) {
            ambient = new Ambient(this, prefs.animFps(), mode == Wallpaper.BEAM, mode == Wallpaper.BOKEH, false);
            ambient.beamStyle(prefs.beamBright(), prefs.beamFrom(), prefs.beamColor(), prefs.flicker());
            root.addView(ambient, new FrameLayout.LayoutParams(-1, -1));
        } else if (mode == Wallpaper.IMAGE && prefs.kenBurns()) {
            kenBurns = new ImageView(this);
            kenBurns.setScaleType(ImageView.ScaleType.CENTER_CROP);
            kenBurns.setAlpha(0f);
            root.addView(kenBurns, new FrameLayout.LayoutParams(-1, -1));
            loadKenBurns(async);
        }
        if (mode != Wallpaper.BLACK) addBaked(root, async);
        if (async && prefs.grain()) {
            grainView = new Ambient(this, prefs.animFps(), false, false, true);
            root.addView(grainView, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private void loadKenBurns(boolean async) {
        if (!async) {
            Bitmap b = Wallpaper.load(this);
            if (b != null) {
                kenBurns.setImageDrawable(dithered(b));
                kenBurns.setAlpha(1f);
            }
            return;
        }
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap b = Wallpaper.load(app);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (b == null || isDestroyed()) return;
                        kenBurns.setImageDrawable(dithered(b));
                        kenBurns.animate().alpha(1f).setDuration(400).start();
                    }
                });
            }
        }, "kenburns").start();
    }

    /** Starts or stops everything that moves in the background. */
    private void animate(boolean on) {
        boolean run = on && !dimmed;
        if (ambient != null) ambient.setRunning(run);
        if (grainView != null) grainView.setRunning(run);
        handler.removeCallbacks(kenBurnsTick);
        if (run && kenBurns != null) handler.post(kenBurnsTick);
    }

    /**
     * Slow zoom and pan at the animation frame rate (12 or 24 fps): one minute in, one minute out, drifting sideways.
     * Done by hand instead of with an animator, which would redraw at the full 60 fps.
     */
    private final Runnable kenBurnsTick = new Runnable() {
        @Override public void run() {
            if (kenBurns == null) return;
            long now = SystemClock.uptimeMillis();
            if (now >= kenBurnsPausedUntil) {
                double t = now / 1000.0;
                float zoom = 1.06f + 0.06f * (float) Math.sin(t * 2 * Math.PI / 120);
                kenBurns.setScaleX(zoom);
                kenBurns.setScaleY(zoom);
                kenBurns.setTranslationX(kenBurns.getWidth() * 0.025f * (float) Math.sin(t * 2 * Math.PI / 170));
                kenBurns.setTranslationY(kenBurns.getHeight() * 0.015f * (float) Math.cos(t * 2 * Math.PI / 140));
            }
            handler.postDelayed(this, 1000 / prefs.animFps());
        }
    };

    private void addBaked(FrameLayout root, boolean async) {
        final ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        bakedView = bg;
        loadBaked(bg, async);
    }

    private void loadBaked(final ImageView bg, boolean async) {
        if (!async) {
            Bitmap b = Backdrop.get(this, prefs);
            if (b != null) bg.setImageDrawable(dithered(b));
            return;
        }
        final Context app = getApplicationContext();
        final long started = SystemClock.uptimeMillis();
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap b = Backdrop.get(app, prefs);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (b == null || isDestroyed()) return;
                        bg.setImageDrawable(dithered(b));
                        // Cached: appears at once. Freshly baked: a short fade instead of a pop.
                        if (SystemClock.uptimeMillis() - started > 120) {
                            bg.setAlpha(0f);
                            bg.animate().alpha(1f).setDuration(300).start();
                        }
                    }
                });
            }
        }, "backdrop").start();
    }

    /**
     * The system is short of memory while the home screen is hidden (a film is playing): drop the
     * background pictures. They come back when the home screen is shown again.
     * The bound key service keeps this process "running", so it gets the RUNNING_* levels rather
     * than BACKGROUND; both are handled.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        boolean pressure = level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL
                || level >= TRIM_MEMORY_BACKGROUND;
        if (pressure && hidden && dock != null && !bgReleased) {
            bgReleased = true;
            animate(false);
            stopSlides();
            // Detach first, then recycle: the native pixels are freed now, not at the next GC.
            Bitmap baked = detach(bakedView);
            Bitmap pic = detach(kenBurns);
            detach(bannerBg);
            softCache.clear();
            Backdrop.release();
            if (baked != null) baked.recycle();
            if (pic != null) pic.recycle();
        }
    }

    private static Bitmap detach(ImageView v) {
        if (v == null) return null;
        Bitmap b = null;
        if (v.getDrawable() instanceof BitmapDrawable) b = ((BitmapDrawable) v.getDrawable()).getBitmap();
        v.setImageDrawable(null);
        return b;
    }

    private void restoreBackground() {
        if (!bgReleased) return;
        bgReleased = false;
        if (bakedView != null) loadBaked(bakedView, true);
        if (kenBurns != null) {
            kenBurns.setAlpha(0f);
            loadKenBurns(true);
        }
    }

    private BitmapDrawable dithered(Bitmap b) {
        BitmapDrawable d = new BitmapDrawable(getResources(), b);
        d.setDither(true);
        d.setFilterBitmap(true);
        return d;
    }

    /**
     * Soft version of a banner, made once per app: drawn into a 64x36 bitmap, box-blurred there,
     * then stretched with bilinear filtering. Cheap, and free of the GPU blur's stair-steps.
     */
    private Drawable softBanner(Apps.App app) {
        Drawable cached = softCache.get(app.component);
        if (cached != null) return cached;
        if (app.banner == null || app.banner.getConstantState() == null) return null;
        Bitmap small = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888);
        Drawable d = app.banner.getConstantState().newDrawable(getResources()).mutate();
        d.setBounds(0, 0, 64, 36);
        d.draw(new Canvas(small));
        Blur.box(small, 4, 3);
        BitmapDrawable soft = dithered(small);
        softCache.put(app.component, soft);
        return soft;
    }

    private void showBannerBackground(Apps.App app) {
        if (bannerBg == null) return;
        Drawable d = app == null ? null : softBanner(app);
        bannerBg.animate().cancel();
        if (d == null) {
            bannerBg.animate().alpha(0f).setDuration(250).start();
            return;
        }
        bannerBg.setImageDrawable(d);
        bannerBg.animate().alpha(0.45f).setDuration(350).start();
    }

    // ============================================================= boot screen

    private void showBootScreen(FrameLayout root) {
        try {
            addBackground(root, false);
        } catch (Throwable ignored) {
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.addView(bigClock());
        TextView t = Ui.text(this, 16, Ui.TEXT_DIM, false);
        t.setText("Запуск системы…");
        box.addView(t);
        root.addView(box, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    }

    private TextClock bigClock() {
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextSize(96);
        clock.setTypeface(Ui.face(this, 200));
        clock.setTextColor(Ui.TEXT);
        return clock;
    }

    // ================================================================ idle dim

    private static final long SLIDE_MS = 12_000;
    private static final long SLIDE_FADE_MS = 1_500;

    private FrameLayout slideBox;
    private TextClock idleClock;
    private List<File> slides;
    private int slideIndex;
    private ImageView slideFront;
    private long slideStart;
    private boolean slidesOn;

    /**
     * Shown after a while without key presses: a dim clock on black, or a slideshow of the
     * user's photos with the clock in the corner.
     */
    private View idleOverlay() {
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor(Ui.alpha(Color.BLACK, 0.97f));
        slideBox = new FrameLayout(this);
        o.addView(slideBox, new FrameLayout.LayoutParams(-1, -1));
        idleClock = bigClock();
        o.addView(idleClock, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        o.setVisibility(View.GONE);
        o.setAlpha(0f);
        return o;
    }

    private void restartIdleTimer() {
        handler.removeCallbacks(idle);
        int min = prefs.idleMinutes();
        if (min > 0 && hasWindowFocus()) handler.postDelayed(idle, min * 60_000L);
    }

    private void dim() {
        if (dimmed || idleOverlay == null) return;
        dimmed = true;
        animate(false);
        clockLayout(false);
        idleOverlay.setVisibility(View.VISIBLE);
        idleOverlay.animate().alpha(1f).setDuration(1200).start();
        if (prefs.idleMode() == Prefs.IDLE_SLIDES) startSlides();
    }

    private void wake() {
        if (idleOverlay == null) return;
        if (dimmed) {
            dimmed = false;
            stopSlides();
            idleOverlay.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                @Override public void run() {
                    if (!dimmed) idleOverlay.setVisibility(View.GONE);
                }
            }).start();
            if (hasWindowFocus()) animate(true);
        }
        restartIdleTimer();
    }

    /** Big dim clock in the middle, or a smaller one in the corner over the photos. */
    private void clockLayout(boolean overPhotos) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) idleClock.getLayoutParams();
        if (overPhotos) {
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.setMargins(0, 0, Ui.dp(this, 56), Ui.dp(this, 40));
            idleClock.setTextSize(56);
            idleClock.setTextColor(Ui.alpha(Color.WHITE, 0.85f));
            idleClock.setShadowLayer(Ui.dp(this, 12), 0, 0, Ui.alpha(Color.BLACK, 0.8f));
        } else {
            lp.gravity = Gravity.CENTER;
            lp.setMargins(0, 0, 0, 0);
            idleClock.setTextSize(96);
            idleClock.setTextColor(Ui.alpha(Ui.TEXT, 0.35f));
            idleClock.setShadowLayer(0, 0, 0, 0);
        }
        idleClock.setLayoutParams(lp);
    }

    // ----------------------------------------------------------- slideshow

    private void startSlides() {
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;   // Without access to the photos the clock alone is shown.
        }
        slidesOn = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<File> list = Wallpaper.candidates();
                Collections.shuffle(list);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (!slidesOn || list.isEmpty()) return;
                        slides = list;
                        slideIndex = 0;
                        nextSlide.run();
                        handler.post(slideTick);
                    }
                });
            }
        }, "slides").start();
    }

    private void stopSlides() {
        slidesOn = false;
        handler.removeCallbacks(nextSlide);
        handler.removeCallbacks(slideTick);
        if (slideBox != null) slideBox.removeAllViews();
        slideFront = null;
    }

    /** Loads the next photo in the background and cross-fades it in over the current one. */
    private final Runnable nextSlide = new Runnable() {
        @Override public void run() {
            if (!slidesOn || slides == null || slides.isEmpty()) return;
            final File f = slides.get(slideIndex++ % slides.size());
            final Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() {
                    final Bitmap b = Wallpaper.decodeForScreen(app, f);
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (!slidesOn) return;
                            if (b != null) showSlide(b);
                            handler.postDelayed(nextSlide, b != null ? SLIDE_MS : 200);
                        }
                    });
                }
            }, "slide").start();
        }
    };

    private void showSlide(Bitmap b) {
        if (slideFront == null) clockLayout(true);
        final ImageView old = slideFront;
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImageDrawable(dithered(b));
        v.setAlpha(0f);
        slideBox.addView(v, new FrameLayout.LayoutParams(-1, -1));
        slideFront = v;
        slideStart = SystemClock.uptimeMillis();
        v.animate().alpha(1f).setDuration(SLIDE_FADE_MS).withEndAction(new Runnable() {
            @Override public void run() {
                // Free the previous photo as soon as it is covered.
                if (old != null) slideBox.removeView(old);
            }
        }).start();
    }

    /** Slow zoom into the current photo, at the animation frame rate. */
    private final Runnable slideTick = new Runnable() {
        @Override public void run() {
            if (!slidesOn) return;
            if (slideFront != null) {
                float p = Math.min(1f, (SystemClock.uptimeMillis() - slideStart) / (float) (SLIDE_MS + SLIDE_FADE_MS));
                float s = 1f + 0.08f * p;
                slideFront.setScaleX(s);
                slideFront.setScaleY(s);
            }
            handler.postDelayed(this, 1000 / prefs.animFps());
        }
    };

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) restartIdleTimer(); else handler.removeCallbacks(idle);
    }

    // ================================================================= top bar

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, Ui.dp(this, 40), 0);

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextSize(30);
        time.setTypeface(Ui.face(this, 300));
        time.setTextColor(Ui.TEXT);
        time.setIncludeFontPadding(false);
        clock.addView(time);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(Ui.dp(this, 1), Ui.dp(this, 3), 0, 0);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextSize(13);
        date.setTypeface(Ui.face(this, 400));
        date.setTextColor(Ui.TEXT_DIM);
        line.addView(date);
        network = Ui.text(this, 13, Ui.TEXT_DIM, false);
        network.setPadding(Ui.dp(this, 12), 0, 0, 0);
        line.addView(network);
        clock.addView(line);
        bar.addView(clock, new LinearLayout.LayoutParams(0, -2, 1));

        pillHome = Ui.pill(this, "Главная");
        pillApps = Ui.pill(this, "Приложения");
        pillSettings = Ui.pill(this, "⚙");
        pillHome.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab(TAB_HOME, true); }
        });
        pillApps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setTab(TAB_APPS, true); }
        });
        pillSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
        for (TextView p : new TextView[]{pillHome, pillApps, pillSettings}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(Ui.dp(this, 6), 0, 0, 0);
            bar.addView(p, lp);
        }
        return bar;
    }

    /** Network type next to the date; a red dot when there is no connection. */
    private void watchNetwork(boolean on) {
        final ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        if (!on) {
            if (netCallback != null) {
                try {
                    cm.unregisterNetworkCallback(netCallback);
                } catch (Exception ignored) {
                }
                netCallback = null;
            }
            return;
        }
        showNetwork(cm.getNetworkCapabilities(cm.getActiveNetwork()));
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, final NetworkCapabilities caps) {
                handler.post(new Runnable() { public void run() { showNetwork(caps); } });
            }

            @Override public void onLost(Network n) {
                handler.post(new Runnable() { public void run() { showNetwork(null); } });
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {
            netCallback = null;
        }
    }

    private void showNetwork(NetworkCapabilities caps) {
        if (caps == null) {
            network.setText("●  Нет сети");
            network.setTextColor(Color.rgb(255, 110, 110));
            return;
        }
        String type = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : "Сеть";
        boolean internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        network.setText("●  " + type + (internet ? "" : " · без интернета"));
        network.setTextColor(internet ? Ui.TEXT_DIM : Color.rgb(255, 190, 90));
    }

    private void setTab(int t, boolean focus) {
        tab = t;
        homePane.setVisibility(t == TAB_HOME ? View.VISIBLE : View.GONE);
        appsPane.setVisibility(t == TAB_APPS ? View.VISIBLE : View.GONE);
        pillHome.setSelected(t == TAB_HOME);
        pillApps.setSelected(t == TAB_APPS);
        Ui.pillColor(pillHome);
        Ui.pillColor(pillApps);
        if (t == TAB_APPS) {
            if (gridDirty && !apps.isEmpty()) buildGrid();
            appsPane.scrollTo(0, 0);
        }
        if (focus) focusFirst();
    }

    // ================================================================ home tab

    private View homePane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setGravity(Gravity.BOTTOM);
        pane.setClipChildren(false);
        pane.setClipToPadding(false);

        heroTitle = Ui.light(this, Math.min(52, Ui.heightDp(this) * 0.072f), Ui.TEXT);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setPadding(0, Ui.dp(this, 4), Ui.dp(this, 48), 0);
        pane.addView(heroTitle);
        heroChips = new LinearLayout(this);
        heroChips.setOrientation(LinearLayout.HORIZONTAL);
        heroChips.setPadding(0, Ui.dp(this, 10), 0, 0);
        pane.addView(heroChips);

        dockLabel = Ui.light(this, 20, Ui.TEXT);
        dockLabel.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 10));
        pane.addView(dockLabel);

        // A plain row of tiles, aligned with the title; with many tiles it scrolls to the screen edge.
        dockScroll = new HorizontalScrollView(this);
        dockScroll.setHorizontalScrollBarEnabled(false);
        dockScroll.setClipChildren(false);
        dockScroll.setClipToPadding(false);
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        // Room above and below for the focused tile, which grows by 10%.
        int pad = Ui.dp(this, 10);
        dock.setPadding(0, pad, Ui.dp(this, 32), pad);
        dockScroll.addView(dock);
        pane.addView(dockScroll, new LinearLayout.LayoutParams(-1, -2));
        return pane;
    }

    private void showHero(Apps.App app) {
        heroTitle.setText(app.label);
        heroChips.removeAllViews();
        List<String> chips = new ArrayList<>();
        if (Apps.HDMI.equals(app.component)) chips.add("Вход");
        else chips.add(app.leanback ? "ТВ-приложение" : "Приложение");
        if (app.component.equals(prefs.autostart())) chips.add("Запуск при включении");
        for (String c : chips) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, Ui.dp(this, 8), 0);
            heroChips.addView(Ui.chip(this, c), lp);
        }
    }

    // ================================================================ apps tab

    private ScrollView appsPane() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(0, Ui.dp(this, 24), Ui.dp(this, 48), Ui.dp(this, 24));
        appsTitle = Ui.light(this, 30, Ui.TEXT);
        appsTitle.setSingleLine(true);
        appsTitle.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(appsTitle);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        box.addView(grid);
        scroll.addView(box);
        return scroll;
    }

    // ================================================================== tiles

    /**
     * Loads the app list in the background. On a cold start with favorites, the favorites are
     * loaded first so the dock appears before all the other banners are read.
     */
    private void reload() {
        if (loading) return;
        loading = true;
        final Context app = getApplicationContext();
        final boolean cold = apps.isEmpty();
        final List<String> favs = prefs.favorites();
        new Thread(new Runnable() {
            @Override public void run() {
                if (cold && !favs.isEmpty()) {
                    final List<Apps.App> some = Apps.loadSome(app, favs);
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (apps.isEmpty() && !isDestroyed()) {
                                apps = some;
                                rebuild();
                            }
                        }
                    });
                }
                final List<Apps.App> list = Apps.load(app);
                handler.post(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        dirty = false;
                        boolean changed = !Apps.same(apps, list);
                        apps = list;
                        if (changed && !isDestroyed()) rebuild();
                    }
                });
            }
        }, "apps").start();
    }

    /** Refills the dock (and the grid if it is on screen), keeping the focus in place. */
    private void rebuild() {
        builtVersion = Prefs.version();
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) focusTag = (String) focused.getTag();

        Map<String, Apps.App> byComponent = new LinkedHashMap<>();
        for (Apps.App a : apps) byComponent.put(a.component, a);

        // Dock: favorites, or the most used apps until there are favorites.
        List<Apps.App> favs = new ArrayList<>();
        for (String c : prefs.favorites()) {
            Apps.App a = byComponent.get(c);
            if (a != null) favs.add(a);
        }
        boolean noFavs = favs.isEmpty();
        List<Apps.App> dockApps = noFavs ? byUsage(visible()) : favs;
        if (prefs.hdmiTile()) {
            Apps.App hdmi = Apps.hdmi(this);
            if (hdmi != null) dockApps.add(hdmi);
        }
        dockLabel.setText(noFavs ? "Приложения" : "Избранное");
        dock.removeAllViews();
        fill(dock, dockApps, noFavs ? ROW_DOCK : ROW_FAV, tileH(), 16);

        // The grid is built only when its tab is open: less work on every return home.
        gridDirty = true;
        if (tab == TAB_APPS) buildGrid();

        if (!restoreFocus()) focusFirst();
    }

    /** Height of the tiles, the same in the dock and in the grid. */
    private int tileH() {
        return (int) Math.min(100, Ui.heightDp(this) * 0.14f);
    }

    private List<Apps.App> visible() {
        Set<String> hidden = prefs.hidden();
        List<Apps.App> out = new ArrayList<>();
        for (Apps.App a : apps) if (!hidden.contains(a.component)) out.add(a);
        return out;
    }

    /** Most launched first; ties keep the alphabetical order. */
    private List<Apps.App> byUsage(List<Apps.App> list) {
        final Map<String, Integer> n = new HashMap<>();
        for (Apps.App a : list) n.put(a.component, prefs.launches(a.component));
        List<Apps.App> out = new ArrayList<>(list);
        Collections.sort(out, new Comparator<Apps.App>() {
            @Override public int compare(Apps.App a, Apps.App b) {
                return n.get(b.component) - n.get(a.component);
            }
        });
        return out;
    }

    private void buildGrid() {
        gridDirty = false;
        grid.removeAllViews();
        List<Apps.App> all = visible();
        int gridH = tileH();
        float tileW = gridH * 16 / 9f;
        int gap = 22;
        int cols = Math.max(1, (int) ((Ui.widthDp(this) - 96 + gap) / (tileW + gap)));

        List<Apps.App> often = new ArrayList<>();
        for (Apps.App a : byUsage(all)) {
            if (often.size() >= cols || prefs.launches(a.component) < OFTEN_MIN) break;
            often.add(a);
        }
        if (!often.isEmpty()) {
            grid.addView(gridCaption("Часто открываете"));
            grid.addView(gridLine(often, ROW_OFTEN, gridH, gap));
            grid.addView(gridCaption("Все приложения · " + all.size()));
        }
        appsTitle.setText("Все приложения · " + all.size());
        for (int i = 0; i < all.size(); i += cols) {
            grid.addView(gridLine(all.subList(i, Math.min(all.size(), i + cols)), ROW_ALL, gridH, gap));
        }
    }

    private TextView gridCaption(String s) {
        TextView t = Ui.caption(this, s);
        t.setTextColor(Ui.TEXT_DIM);
        t.setPadding(0, Ui.dp(this, 16), 0, 0);
        return t;
    }

    private LinearLayout gridLine(List<Apps.App> list, String row, int tileH, int gap) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
        fill(line, list, row, tileH, gap);
        return line;
    }

    private void fill(LinearLayout line, List<Apps.App> list, final String row, int tileH, int gapDp) {
        final boolean inGrid = ROW_ALL.equals(row) || ROW_OFTEN.equals(row);
        for (final Apps.App a : list) {
            View card = Ui.tile(this, a, tileH, new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    if (!has) return;
                    if (inGrid) appsTitle.setText(a.label);
                    else showHero(a);
                    showBannerBackground(a);
                }
            });
            card.setTag(row + "|" + a.component);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Apps.launch(HomeActivity.this, a);
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    showOptions(a, row);
                    return true;
                }
            });
            card.setOnKeyListener(new View.OnKeyListener() {
                @Override public boolean onKey(View v, int keyCode, KeyEvent e) {
                    if (keyCode == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                        showOptions(a, row);
                        return true;
                    }
                    return false;
                }
            });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.setMargins(0, 0, Ui.dp(this, gapDp), 0);
            line.addView(card, lp);
        }
    }

    private boolean restoreFocus() {
        if (focusTag == null) return false;
        boolean inGrid = focusTag.startsWith(ROW_ALL + "|") || focusTag.startsWith(ROW_OFTEN + "|");
        View pane = inGrid ? appsPane : homePane;
        if (pane.getVisibility() != View.VISIBLE) return false;
        View v = pane.findViewWithTag(focusTag);
        if (v == null) {
            // The app moved between the favorites and the plain list: follow it.
            String comp = focusTag.substring(focusTag.indexOf('|') + 1);
            for (String r : inGrid ? new String[]{ROW_ALL, ROW_OFTEN} : new String[]{ROW_FAV, ROW_DOCK}) {
                v = pane.findViewWithTag(r + "|" + comp);
                if (v != null) break;
            }
        }
        return v != null && v.requestFocus();
    }

    private void focusFirst() {
        View first = findFirstCard(tab == TAB_APPS ? grid : dock);
        if (first != null) {
            first.requestFocus();
            if (tab == TAB_HOME) dockScroll.scrollTo(0, 0);
        } else {
            (tab == TAB_APPS ? pillApps : pillHome).requestFocus();
        }
    }

    private static View findFirstCard(View v) {
        if (v.getTag() instanceof String && v.isFocusable()) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findFirstCard(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    // ================================================================ options

    private void showOptions(final Apps.App a, final String row) {
        final boolean hdmi = Apps.HDMI.equals(a.component);
        final boolean fav = prefs.isFavorite(a.component);
        final boolean auto = a.component.equals(prefs.autostart());
        final boolean inGrid = ROW_ALL.equals(row) || ROW_OFTEN.equals(row);

        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add("Открыть");
        actions.add(new Runnable() { public void run() { Apps.launch(HomeActivity.this, a); } });

        if (!hdmi) {
            labels.add(fav ? "Убрать из избранного" : "Добавить в избранное");
            actions.add(new Runnable() { public void run() {
                if (fav) prefs.removeFavorite(a.component); else prefs.addFavorite(a.component);
                focusTag = (inGrid ? row : ROW_FAV) + "|" + a.component;
                rebuildKeepingTag();
            } });
        }
        if (fav && ROW_FAV.equals(row)) {
            labels.add("← Сдвинуть влево");
            actions.add(new Runnable() { public void run() {
                prefs.moveFavorite(a.component, -1);
                focusTag = ROW_FAV + "|" + a.component;
                rebuildKeepingTag();
            } });
            labels.add("Сдвинуть вправо →");
            actions.add(new Runnable() { public void run() {
                prefs.moveFavorite(a.component, +1);
                focusTag = ROW_FAV + "|" + a.component;
                rebuildKeepingTag();
            } });
        }
        if (hdmi) {
            labels.add("Убрать плитку HDMI");
            actions.add(new Runnable() { public void run() {
                prefs.setHdmiTile(false);
                focusTag = null;
                rebuildKeepingTag();
            } });
        }
        labels.add(auto ? "Не запускать при включении" : "Запускать при включении проектора");
        actions.add(new Runnable() { public void run() {
            prefs.setAutostart(auto ? "" : a.component);
            if (!inGrid) showHero(a);
        } });
        if (!hdmi) {
            labels.add("Скрыть из списка");
            actions.add(new Runnable() { public void run() {
                prefs.setHidden(a.component, true);
                prefs.removeFavorite(a.component);
                focusTag = null;
                rebuildKeepingTag();
            } });
            labels.add("О приложении");
            actions.add(new Runnable() { public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + a.pkg())));
                } catch (Exception ignored) {
                }
            } });
        }

        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    /** Rebuild without letting the current focus overwrite the tag chosen by an action. */
    private void rebuildKeepingTag() {
        String tag = focusTag;
        View focused = getCurrentFocus();
        if (focused != null) focused.clearFocus();
        focusTag = tag;
        rebuild();
    }

    // ============================================================== autostart

    private void maybeAutostart() {
        final String comp = prefs.autostart();
        if (comp.isEmpty()) return;
        if (SystemClock.elapsedRealtime() > AUTOSTART_WINDOW_MS) return;
        if (!prefs.claimAutostartForThisBoot()) return;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                Apps.launch(HomeActivity.this, comp);
            }
        }, 800);
    }
}
