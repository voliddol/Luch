package com.local.tvlauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.IOException;

/** A silent looping local video behind the tiles. Only the foreground home screen owns a decoder. */
final class VideoWallpaper extends FrameLayout implements TextureView.SurfaceTextureListener {
    private final Uri uri;
    private final TextureView picture;
    private final View fallback;
    private HandlerThread thread;
    private Handler worker;
    private Playback playback;
    private boolean running, reportedFailure;
    private int videoWidth, videoHeight;
    // Accessed only on the worker. A loop can continue after a visit to an app or to the idle screen.
    private int positionMs;

    VideoWallpaper(Context context, Uri uri) {
        super(context);
        this.uri = uri;
        picture = new TextureView(context);
        picture.setSurfaceTextureListener(this);
        addView(picture, new LayoutParams(-1, -1));
        fallback = new View(context);
        fallback.setBackgroundColor(Ui.BG);
        addView(fallback, new LayoutParams(-1, -1));
    }

    void setRunning(boolean on) {
        running = on;
        if (on) startIfReady();
        else stopPlayback();
    }

    private void startIfReady() {
        if (!running || playback != null || !picture.isAvailable()) return;
        if (uri == null || !(ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || ContentResolver.SCHEME_FILE.equals(uri.getScheme()))) {
            showFailure();
            return;
        }
        if (worker == null) {
            thread = new HandlerThread("Luch video wallpaper");
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        playback = new Playback(new Surface(picture.getSurfaceTexture()));
        worker.post(playback::open);
    }

    private void stopPlayback() {
        fallback.setVisibility(VISIBLE);
        Playback old = playback;
        if (old != null) {
            playback = null;
            old.cancelled = true;
            old.opening.cancel();
            worker.post(old::release);
        }
    }

    private void showFailure() {
        fallback.setVisibility(VISIBLE);
        if (running && isShown() && !reportedFailure) {
            reportedFailure = true;
            Toast.makeText(getContext(), "Видеообои недоступны. Проверьте файл или выберите другое видео.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
        startIfReady();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
        centerCrop();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        stopPlayback();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPlayback();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            worker = null;
        }
        super.onDetachedFromWindow();
    }

    private void centerCrop() {
        int w = picture.getWidth(), h = picture.getHeight();
        if (w == 0 || h == 0 || videoWidth == 0 || videoHeight == 0) return;
        float aspect = (videoWidth / (float) videoHeight) / (w / (float) h);
        Matrix crop = new Matrix();
        crop.setScale(Math.max(1f, aspect), Math.max(1f, 1f / aspect), w / 2f, h / 2f);
        picture.setTransform(crop);
    }

    /** All MediaPlayer calls and callbacks run on the same looper, including teardown. */
    private final class Playback {
        final Surface surface;
        final CancellationSignal opening = new CancellationSignal();
        volatile boolean cancelled;
        MediaPlayer player;
        boolean prepared, released;

        Playback(Surface surface) {
            this.surface = surface;
        }

        void open() {
            if (cancelled) return;
            try (AssetFileDescriptor file = getContext().getContentResolver()
                    .openAssetFileDescriptor(uri, "r", opening)) {
                if (cancelled) return;
                if (file == null) throw new IOException("No file descriptor");
                player = new MediaPlayer();
                player.setSurface(surface);
                player.setVolume(0f, 0f);
                player.setLooping(true);
                player.setOnErrorListener((mp, what, extra) -> {
                    fail();
                    return true;
                });
                player.setOnVideoSizeChangedListener((mp, w, h) -> post(() -> {
                    if (playback == this && w > 0 && h > 0) {
                        videoWidth = w;
                        videoHeight = h;
                        centerCrop();
                    }
                }));
                player.setOnInfoListener((mp, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        post(() -> {
                            if (playback == this) fallback.setVisibility(GONE);
                        });
                    }
                    return false;
                });
                player.setOnPreparedListener(this::start);
                long length = file.getDeclaredLength();
                if (length == AssetFileDescriptor.UNKNOWN_LENGTH) player.setDataSource(file.getFileDescriptor());
                else player.setDataSource(file.getFileDescriptor(), file.getStartOffset(), length);
                if (!cancelled) player.prepareAsync();
            } catch (IOException | RuntimeException e) {
                fail();
            }
        }

        private void start(MediaPlayer mp) {
            if (cancelled || released) return;
            try {
                if (!hasVideoTrack(mp)) {
                    fail();
                    return;
                }
                prepared = true;
                if (positionMs > 0) mp.seekTo(positionMs);
                mp.start();
            } catch (RuntimeException e) {
                fail();
            }
        }

        private boolean hasVideoTrack(MediaPlayer mp) {
            if (mp.getVideoWidth() > 0 && mp.getVideoHeight() > 0) return true;
            for (MediaPlayer.TrackInfo track : mp.getTrackInfo()) {
                if (track.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) return true;
            }
            return false;
        }

        private void fail() {
            prepared = false;
            release();
            if (!cancelled) post(() -> { if (playback == this) showFailure(); });
        }

        void release() {
            if (released) return;
            released = true;
            if (player != null) {
                if (prepared) {
                    try {
                        positionMs = player.getCurrentPosition();
                    } catch (IllegalStateException e) {
                        positionMs = 0;
                    }
                }
                try {
                    player.release();
                } catch (RuntimeException ignored) {
                    // A vendor media service can already be gone after a decoder failure.
                }
                player = null;
            }
            surface.release();
        }
    }
}
