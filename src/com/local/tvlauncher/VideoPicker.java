package com.local.tvlauncher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Picks one readable local video, using the system document picker or a TV-friendly folder list. */
final class VideoPicker {
    static final int READ_FILES = 11, OPEN_DOCUMENT = 12;
    private static final int READ_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private final SettingsActivity activity;
    private final Prefs prefs;
    private final Runnable refresh;

    VideoPicker(SettingsActivity activity, Prefs prefs, Runnable refresh) {
        this.activity = activity;
        this.prefs = prefs;
        this.refresh = refresh;
    }

    void open() {
        Intent files = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(READ_GRANT | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        ComponentName picker = files.resolveActivity(activity.getPackageManager());
        if (picker == null || "com.android.tv.frameworkpackagestubs".equals(picker.getPackageName())) {
            requestFolders();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Видео для фона")
                .setItems(new String[]{"Файлы Android", "Папки устройства и USB"}, (d, which) -> {
                    if (which == 0) {
                        try {
                            activity.startActivityForResult(files, OPEN_DOCUMENT);
                            return;
                        } catch (android.content.ActivityNotFoundException | SecurityException ignored) {
                            // Some TV firmwares advertise a document picker that they cannot start.
                        }
                    }
                    requestFolders();
                }).setNegativeButton("Отмена", null).show();
    }

    void documentSelected(Intent data) {
        if (data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            message("Выберите файл в папках устройства или на USB.");
            return;
        }
        validate(uri, true);
    }

    private void requestFolders() {
        if (activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            folders();
        } else {
            activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, READ_FILES);
        }
    }

    void storageResult(boolean granted) {
        if (granted) folders();
        else message("Для выбора видео из папок нужен доступ к файлам.");
    }

    private static final class Entry {
        final File file;
        final String label;
        final boolean directory;

        Entry(File file, String label, boolean directory) {
            this.file = file;
            this.label = label;
            this.directory = directory;
        }
    }

    private void folders() {
        read("Открываю папки…", "Не удалось открыть папки устройства.", signal -> {
            List<Entry> roots = new ArrayList<>();
            addRoot(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Загрузки");
            addRoot(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Видео");
            addRoot(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Камера");
            StorageManager storage = (StorageManager) activity.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume volume : storage.getStorageVolumes()) {
                if (!volume.isRemovable()) continue;
                String state = volume.getState();
                if (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) continue;
                File root = Build.VERSION.SDK_INT >= 30 ? volume.getDirectory() : legacyRoot(storage, volume);
                addRoot(roots, root, volume.getDescription(activity));
            }
            return roots;
        }, roots -> {
            AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("Папки устройства и USB")
                    .setNegativeButton("Отмена", null);
            if (roots.isEmpty()) {
                dialog.setMessage("Сохраните видео в папку Download, Movies или DCIM либо подключите USB-накопитель.");
            } else {
                dialog.setItems(labels(roots), (d, index) -> {
                    Entry root = roots.get(index);
                    browse(root, root.file);
                });
            }
            dialog.show();
        });
    }

    private static void addRoot(List<Entry> roots, File dir, String label) {
        if (dir != null && dir.isDirectory() && dir.canRead()) roots.add(new Entry(dir, label, true));
    }

    /** StorageVolume.getDirectory is only public from Android 11 onward. */
    private File legacyRoot(StorageManager storage, StorageVolume volume) {
        String suffix = "/Android/data/" + activity.getPackageName() + "/files";
        for (File appDir : activity.getExternalFilesDirs(null)) {
            if (appDir == null || !volume.equals(storage.getStorageVolume(appDir))) continue;
            String path = appDir.getAbsolutePath();
            if (path.endsWith(suffix)) return new File(path.substring(0, path.length() - suffix.length()));
        }
        return volume.getUuid() == null ? null : new File("/storage", volume.getUuid());
    }

    private void browse(Entry root, File dir) {
        read("Открываю папку…", "Папка недоступна. Проверьте подключение накопителя.", signal -> {
            File[] files = dir.listFiles();
            if (files == null) throw new IOException("Unreadable directory");
            List<Entry> entries = new ArrayList<>();
            for (File file : files) {
                signal.throwIfCanceled();
                if (file.isHidden()) continue;
                boolean folder = file.isDirectory();
                if (folder || (file.isFile() && isVideoName(file.getName()))) {
                    entries.add(new Entry(file, file.getName() + (folder ? "/" : ""), folder));
                }
            }
            entries.sort((a, b) -> a.directory == b.directory
                    ? a.label.compareToIgnoreCase(b.label) : (a.directory ? -1 : 1));
            return entries;
        }, entries -> {
            Runnable back = () -> {
                if (dir.equals(root.file)) folders();
                else browse(root, dir.getParentFile());
            };
            AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                    .setTitle(dir.equals(root.file) ? root.label : dir.getName())
                    .setNeutralButton("Назад", (d, which) -> back.run())
                    .setOnCancelListener(d -> back.run()).setNegativeButton("Отмена", null);
            if (entries.isEmpty()) {
                dialog.setMessage("В этой папке нет видео.");
            } else {
                dialog.setItems(labels(entries), (d, index) -> {
                    Entry entry = entries.get(index);
                    if (entry.directory) browse(root, entry.file);
                    else validate(Uri.fromFile(entry.file), false);
                });
            }
            dialog.show();
        });
    }

    private static String[] labels(List<Entry> entries) {
        String[] result = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) result[i] = entries.get(i).label;
        return result;
    }

    private static boolean isVideoName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "mp4": case "m4v": case "mkv": case "mov": case "webm": case "avi":
            case "3gp": case "3gpp": case "3g2": case "ts": case "mts": case "m2ts":
            case "mpg": case "mpeg": case "vob": case "ogv": return true;
            default: return false;
        }
    }

    private void validate(Uri uri, boolean persist) {
        read("Проверяю видео…", "Не удалось прочитать видео. Проверьте файл или выберите другой.", signal -> {
            try (AssetFileDescriptor file = activity.getContentResolver().openAssetFileDescriptor(uri, "r", signal)) {
                if (file == null) throw new IOException("Missing file");
                MediaMetadataRetriever media = new MediaMetadataRetriever();
                try {
                    long length = file.getDeclaredLength();
                    if (length == AssetFileDescriptor.UNKNOWN_LENGTH) media.setDataSource(file.getFileDescriptor());
                    else media.setDataSource(file.getFileDescriptor(), file.getStartOffset(), length);
                    String video = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
                    String duration = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    return "yes".equalsIgnoreCase(video) && duration != null && Long.parseLong(duration) > 0;
                } finally {
                    media.release();
                }
            }
        }, valid -> {
            if (!valid) message("В файле не найдена видеодорожка с длительностью.");
            else apply(uri, persist);
        });
    }

    private void apply(Uri uri, boolean persist) {
        ContentResolver resolver = activity.getContentResolver();
        Uri previous = Uri.parse(prefs.videoUri());
        boolean addedGrant = false;
        try {
            if (persist && !hasReadGrant(resolver, uri)) {
                resolver.takePersistableUriPermission(uri, READ_GRANT);
                addedGrant = true;
            }
            prefs.setVideo(uri.toString());
        } catch (RuntimeException e) {
            if (addedGrant) releaseGrant(resolver, uri);
            message("Не удалось сохранить доступ к видео. Выберите его через «Папки устройства и USB».");
            return;
        }
        if (!uri.equals(previous) && ContentResolver.SCHEME_CONTENT.equals(previous.getScheme())) {
            releaseGrant(resolver, previous);
        }
        refresh.run();
    }

    private static boolean hasReadGrant(ContentResolver resolver, Uri uri) {
        for (UriPermission grant : resolver.getPersistedUriPermissions()) {
            if (grant.isReadPermission() && uri.equals(grant.getUri())) return true;
        }
        return false;
    }

    private static void releaseGrant(ContentResolver resolver, Uri uri) {
        try {
            resolver.releasePersistableUriPermission(uri, READ_GRANT);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // A removed document or provider may already have revoked the grant.
        }
    }

    private interface Read<T> { T run(CancellationSignal signal) throws Exception; }
    private interface Ready<T> { void show(T result); }

    private <T> void read(String title, String failure, Read<T> read, Ready<T> ready) {
        CancellationSignal signal = new CancellationSignal();
        AlertDialog wait = new AlertDialog.Builder(activity).setMessage(title)
                .setNegativeButton("Отмена", (d, which) -> signal.cancel())
                .setOnCancelListener(d -> signal.cancel()).show();
        new Thread(() -> {
            T value = null;
            try {
                value = read.run(signal);
            } catch (Exception ignored) {
                // The device may have been unplugged or the file permission revoked.
            }
            final T result = value;
            activity.runOnUiThread(() -> {
                if (signal.isCanceled() || activity.isFinishing() || activity.isDestroyed() || !wait.isShowing()) return;
                wait.dismiss();
                if (result == null) message(failure);
                else ready.show(result);
            });
        }, "Luch video picker").start();
    }

    private void message(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}
