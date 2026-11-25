package com.lsnoglos.mp3local;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

/**
 * Foreground download/conversion. All download and conversion work runs locally.
 * Only one task at a time; finished audio is published through MediaStore.
 */
public class DownloadService extends Service {
    public static final String ACTION_DOWNLOAD = "com.lsnoglos.mp3local.DOWNLOAD";
    public static final String ACTION_CANCEL = "com.lsnoglos.mp3local.CANCEL";
    public static final String ACTION_UPDATE = "com.lsnoglos.mp3local.UPDATE";
    public static final String ACTION_STATUS = "com.lsnoglos.mp3local.STATUS";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_FINISHED = "finished";
    private static final String CHANNEL = "downloads";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS = "motor_updates";
    private static final long UPDATE_EVERY = 7L * 24 * 60 * 60 * 1000;
    private static final long RETRY_AFTER = 24L * 60 * 60 * 1000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean canceled;
    private volatile String processId;
    private volatile int lastPercent = -2;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Descargas MP3", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progreso de descarga y conversión");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelDownload();
            return START_NOT_STICKY;
        }
        boolean manualUpdate = ACTION_UPDATE.equals(intent.getAction());
        if (!manualUpdate && !ACTION_DOWNLOAD.equals(intent.getAction())) return START_NOT_STICKY;
        String url = intent.getStringExtra(EXTRA_URL);
        if (!manualUpdate && !UrlTools.isVideoUrl(url)) {
            sendStatus("Enlace de video inválido", -1, true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running) {
            sendStatus("Ya hay una descarga en proceso", -1, false);
            return START_NOT_STICKY;
        }
        running = true;
        canceled = false;
        lastPercent = -2;
        processId = UUID.randomUUID().toString();
        try {
            Notification notification = notification(
                    manualUpdate ? "Preparando actualización de yt-dlp…" : "Iniciando descarga…",
                    -1, true);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            running = false;
            sendStatus("No se pudo crear la notificación: " + e.getMessage(), -1, true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final String jobId = processId;
        executor.execute(() -> {
            if (manualUpdate) updateMotor(startId);
            else downloadAudio(url, jobId, startId);
        });
        return START_NOT_STICKY;
    }

    private void downloadAudio(String url, String jobId, int startId) {
        File folder = new File(getCacheDir(), "jobs/" + jobId);
        try {
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IllegalStateException("No hay espacio para preparar la descarga");
            }
            sendStatus("Preparando motor de audio…", -1, false);
            YoutubeDL.getInstance().init(getApplicationContext());
            FFmpeg.getInstance().init(getApplicationContext());
            // Update the extractor before running it; if offline, retain the bundled version.
            updateEngine(false);
            if (canceled) throw new InterruptedException("Cancelado");

            YoutubeDLRequest request = new YoutubeDLRequest(url);
            request.addOption("--no-playlist");
            request.addOption("--no-mtime");
            request.addOption("--extract-audio");
            request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", "192K");
            request.addOption("-f", "bestaudio/best");
            request.addOption("-o", new File(folder, "%(title).120B [%(id)s].%(ext)s").getAbsolutePath());

            sendStatus("Descargando y convirtiendo a MP3…", 0, false);
            YoutubeDL.getInstance().execute(request, jobId, (percent, eta, line) -> {
                if (canceled) return Unit.INSTANCE;
                int rounded = Math.min(99, Math.max(0, Math.round(percent)));
                if (rounded != lastPercent) {
                    lastPercent = rounded;
                    sendStatus("Procesando audio · " + rounded + "%", rounded, false);
                }
                return Unit.INSTANCE;
            });

            if (canceled) throw new InterruptedException("Cancelado");
            File mp3 = findMp3(folder);
            if (mp3 == null || mp3.length() == 0) {
                throw new IllegalStateException("No se generó un MP3. El sitio puede haber cambiado.");
            }
            sendStatus("Guardando en Música / MP3 Local…", 99, false);
            Uri saved = saveToMusic(mp3);
            if (saved == null) throw new IllegalStateException("No se pudo guardar el archivo");
            sendStatus("¡MP3 guardado en Música / MP3 Local!", 100, true);
            notifyDone("MP3 guardado correctamente");
        } catch (Exception exception) {
            String detail = exception.getMessage() == null ?
                    exception.getClass().getSimpleName() : exception.getMessage();
            if (canceled) detail = "Descarga cancelada";
            else if (detail.contains("older than 90 days"))
                detail = "El motor yt-dlp está desactualizado. Pulsa '↻ Motor' y vuelve a probar.";
            sendStatus(detail, -1, true);
            notifyDone(detail);
        } finally {
            deleteRecursively(folder);
            running = false;
            processId = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    /**
     * Manual update gives clear feedback; automatic updates are attempted at most once
     * per day and successful checks are cached for seven days.
     */
    private void updateMotor(int startId) {
        try {
            sendStatus("Inicializando motor de descarga…", -1, false);
            YoutubeDL.getInstance().init(getApplicationContext());
            if (canceled) throw new InterruptedException("Actualización cancelada");
            updateEngine(true);
            if (canceled) throw new InterruptedException("Actualización cancelada");
            sendStatus("Motor yt-dlp actualizado. Puedes descargar MP3.", 100, true);
            notifyDone("Motor yt-dlp actualizado");
        } catch (Exception error) {
            String message = canceled ? "Actualización cancelada" :
                    "No se pudo actualizar yt-dlp. Revisa la conexión e inténtalo otra vez.";
            Log.w("MP3Local", "Error al actualizar yt-dlp", error);
            sendStatus(message, -1, true);
            notifyDone(message);
        } finally {
            running = false;
            processId = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    private void updateEngine(boolean force) throws Exception {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastSuccess = prefs.getLong("success", 0);
        long lastAttempt = prefs.getLong("attempt", 0);
        if (!force) {
            if (lastSuccess > 0 && now >= lastSuccess &&
                    now - lastSuccess < UPDATE_EVERY) return;
            if (lastAttempt > 0 && now >= lastAttempt &&
                    now - lastAttempt < RETRY_AFTER) return;
        }
        if (canceled) return;
        sendStatus("Comprobando actualización de yt-dlp…", -1, false);
        prefs.edit().putLong("attempt", now).apply();
        try {
            YoutubeDL.getInstance().updateYoutubeDL(
                    getApplicationContext(), YoutubeDL.UpdateChannel.STABLE);
            prefs.edit().putLong("success", System.currentTimeMillis()).apply();
            sendStatus("Motor yt-dlp preparado", -1, false);
        } catch (Exception error) {
            if (force) throw error;
            Log.w("MP3Local", "No se pudo actualizar; se usará el motor local", error);
            sendStatus("Sin actualización; intentando descarga con motor actual…", -1, false);
        }
    }

    private Uri saveToMusic(File file) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, file.getName());
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/MP3 Local/");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Uri uri = getContentResolver().insert(collection, values);
        if (uri == null) throw new IllegalStateException("No se pudo crear el archivo MP3");
        try {
            try (InputStream source = new FileInputStream(file);
                 OutputStream target = getContentResolver().openOutputStream(uri)) {
                if (target == null) throw new IllegalStateException("No se pudo escribir el archivo");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    if (canceled) throw new InterruptedException("Cancelado");
                    target.write(buffer, 0, read);
                }
                target.flush();
            }
            values.clear();
            values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            throw error;
        }
    }

    private File findMp3(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isDirectory()) {
                File found = findMp3(file);
                if (found != null) return found;
            } else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                return file;
            }
        }
        return null;
    }

    private void cancelDownload() {
        if (!running) {
            stopSelf();
            return;
        }
        canceled = true;
        String id = processId;
        if (id != null) {
            try { YoutubeDL.getInstance().destroyProcessById(id); }
            catch (Exception ignored) { }
        }
        sendStatus("Cancelando descarga…", -1, false);
    }

    private void sendStatus(String message, int percent, boolean finished) {
        Intent status = new Intent(ACTION_STATUS);
        status.setPackage(getPackageName());
        status.putExtra(EXTRA_MESSAGE, message);
        status.putExtra(EXTRA_PERCENT, percent);
        status.putExtra(EXTRA_FINISHED, finished);
        sendBroadcast(status);
        if (!finished && percent != 100) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                    notification(message, percent, true));
        }
    }

    private Notification notification(String description, int percent, boolean cancellable) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setContentTitle("MP3 Local")
                .setContentText(description)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOnlyAlertOnce(true)
                .setOngoing(cancellable)
                .setContentIntent(openPending);
        if (percent < 0) builder.setProgress(0, 0, true);
        else builder.setProgress(100, percent, false);
        if (cancellable) {
            Intent cancel = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
            PendingIntent stop = PendingIntent.getService(this, 11, cancel,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", stop);
        }
        return builder.build();
    }

    private void notifyDone(String text) {
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("MP3 Local")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(1002, notification);
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) for (File child : files) deleteRecursively(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
