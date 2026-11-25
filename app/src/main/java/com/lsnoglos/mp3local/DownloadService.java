package com.lsnoglos.mp3local;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

/**
 * Processes a persistent FIFO queue on a single background thread.
 * Browsing and queuing can continue while one audio file is being converted.
 */
public class DownloadService extends Service {
    public static final String ACTION_DOWNLOAD = "com.lsnoglos.mp3local.DOWNLOAD";
    public static final String ACTION_CANCEL = "com.lsnoglos.mp3local.CANCEL";
    public static final String ACTION_CANCEL_ITEM = "com.lsnoglos.mp3local.CANCEL_ITEM";
    public static final String ACTION_RETRY_ITEM = "com.lsnoglos.mp3local.RETRY_ITEM";
    public static final String ACTION_RESUME = "com.lsnoglos.mp3local.RESUME";
    public static final String ACTION_STATUS = "com.lsnoglos.mp3local.STATUS";
    public static final String ACTION_QUEUE_CHANGED = "com.lsnoglos.mp3local.QUEUE_CHANGED";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_JOB_ID = "job_id";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PERCENT = "percent";
    public static final String EXTRA_FINISHED = "finished";

    private static final String CHANNEL = "downloads";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS = "motor_updates";
    private static final long UPDATE_EVERY = 7L * 24 * 60 * 60 * 1000;
    private static final long RETRY_AFTER = 24L * 60 * 60 * 1000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean workerRunning;
    private volatile boolean canceled;
    private volatile String currentId;
    private volatile int lastPercent = -1;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Descargas MP3", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progreso de la cola de descargas");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        DownloadQueueStore.recoverInterrupted(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESUME : intent.getAction();
        if (ACTION_CANCEL_ITEM.equals(action) || ACTION_CANCEL.equals(action)) {
            String id = intent == null ? null : intent.getStringExtra(EXTRA_JOB_ID);
            if (id == null) id = currentId;
            if (id != null) cancelItem(id);
            if (!workerRunning) stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_DOWNLOAD.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            DownloadQueueStore.Job job = DownloadQueueStore.enqueue(this, url);
            if (job == null) {
                broadcastStatus("Enlace de video inválido", -1, false);
                if (!workerRunning) stopSelf(startId);
                return START_NOT_STICKY;
            }
            broadcastStatus("Añadido a cola: " + job.label, -1, false);
        } else if (ACTION_RETRY_ITEM.equals(action)) {
            String id = intent.getStringExtra(EXTRA_JOB_ID);
            if (id != null) DownloadQueueStore.retry(this, id);
        } else if (!ACTION_RESUME.equals(action)) {
            if (!workerRunning) stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (DownloadQueueStore.pending(this) == 0 && !workerRunning) {
            broadcastQueue();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID,
                    notification("Cola: " + DownloadQueueStore.pending(this) +
                            " archivo(s)", -1, currentId),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception ex) {
            broadcastStatus("No se pudo iniciar la descarga: " + ex.getMessage(), -1, false);
            Log.e("MP3Local", "Cannot start foreground service", ex);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        broadcastQueue();
        synchronized (this) {
            if (!workerRunning) {
                workerRunning = true;
                worker.execute(this::processQueue);
            }
        }
        return START_NOT_STICKY;
    }

    private void processQueue() {
        while (true) {
            DownloadQueueStore.Job job = DownloadQueueStore.takeNext(this);
            if (job == null) break;
            currentId = job.id;
            // Respect cancellation even if it arrives between takeNext and currentId assignment.
            canceled = DownloadQueueStore.isCancelled(this, job.id);
            lastPercent = -1;
            broadcastQueue();
            File temp = new File(getCacheDir(), "jobs/" + job.id);
            try {
                downloadOne(job, temp);
                if (canceled) throw new InterruptedException("Cancelada");
                DownloadQueueStore.update(this, job.id, DownloadQueueStore.COMPLETED,
                        100, "MP3 guardado en " + OutputSettings.describe(job.destinationTree));
                broadcastStatus("Descarga completada: " + job.label, 100, false);
                notifyFinished("Descarga completada: " + job.label);
            } catch (Exception ex) {
                if (canceled) {
                    DownloadQueueStore.update(this, job.id, DownloadQueueStore.CANCELLED,
                            0, "Cancelada por el usuario");
                    broadcastStatus("Descarga cancelada", -1, false);
                } else {
                    String detail = ex.getMessage();
                    if (detail == null || detail.trim().isEmpty())
                        detail = ex.getClass().getSimpleName();
                    if (detail.length() > 170) detail = detail.substring(0, 170) + "…";
                    DownloadQueueStore.update(this, job.id, DownloadQueueStore.FAILED,
                            0, detail);
                    broadcastStatus("No se pudo descargar: " + detail, -1, false);
                    Log.w("MP3Local", "Download failed for " + job.label, ex);
                }
            } finally {
                deleteRecursively(temp);
                currentId = null;
                broadcastQueue();
            }
        }
        synchronized (this) {
            // Another download may have been queued after takeNext() saw an empty list.
            if (DownloadQueueStore.pending(this) > 0) {
                worker.execute(this::processQueue);
                return;
            }
            workerRunning = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        broadcastStatus("Cola terminada", 100, true);
    }

    private void downloadOne(DownloadQueueStore.Job job, File temp) throws Exception {
        if (canceled || DownloadQueueStore.isCancelled(this, job.id))
            throw new InterruptedException("Cancelada");
        if (!temp.exists() && !temp.mkdirs())
            throw new IllegalStateException("No se pudo crear la carpeta temporal");
        setProgress(job, 0, "Preparando motor de audio");
        YoutubeDL.getInstance().init(getApplicationContext());
        FFmpeg.getInstance().init(getApplicationContext());
        updateEngine();
        if (canceled) throw new InterruptedException("Cancelada");

        YoutubeDLRequest request = new YoutubeDLRequest(job.url);
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--extract-audio");
        request.addOption("--audio-format", "mp3");
        request.addOption("--audio-quality", job.qualityKbps + "K");
        request.addOption("-f", "bestaudio/best");
        request.addOption("-o",
                new File(temp, "%(title).120B [%(id)s].%(ext)s").getAbsolutePath());

        setProgress(job, 0, "Descargando y convirtiendo MP3");
        YoutubeDL.getInstance().execute(request, job.id, (percent, eta, line) -> {
            if (canceled) return Unit.INSTANCE;
            int value = Math.min(99, Math.max(0, Math.round(percent)));
            // Minimize disk writes and UI redraws while maintaining useful progress.
            if (value == 99 || value >= lastPercent + 3) {
                if (value != lastPercent) {
                    lastPercent = value;
                    setProgress(job, value, "Procesando " + value + "%");
                }
            }
            return Unit.INSTANCE;
        });
        if (canceled) throw new InterruptedException("Cancelada");
        File mp3 = findMp3(temp);
        if (mp3 == null || mp3.length() == 0)
            throw new IllegalStateException("No se pudo generar MP3; comprueba el enlace o actualiza yt-dlp.");
        setProgress(job, 99, "Guardando en " + OutputSettings.describe(job.destinationTree));
        OutputSettings.save(this, mp3, job.destinationTree, () -> canceled);
    }

    private void setProgress(DownloadQueueStore.Job job, int percent, String message) {
        if (canceled) return;
        DownloadQueueStore.update(this, job.id, DownloadQueueStore.RUNNING, percent, message);
        broadcastStatus(job.label + " · " + message, percent, false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(
                message + " · Restantes: " + DownloadQueueStore.pending(this),
                percent, job.id));
        broadcastQueue();
    }

    private void cancelItem(String id) {
        boolean wasRunning = DownloadQueueStore.cancel(this, id);
        if (wasRunning && id.equals(currentId)) {
            canceled = true;
            try { YoutubeDL.getInstance().destroyProcessById(id); }
            catch (Exception ex) { Log.w("MP3Local", "Could not abort process", ex); }
        }
        broadcastQueue();
        broadcastStatus("Elemento retirado de la cola", -1, false);
    }

    private void updateEngine() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long success = prefs.getLong("success", 0);
        long attempt = prefs.getLong("attempt", 0);
        if (success > 0 && now >= success && now - success < UPDATE_EVERY) return;
        if (attempt > 0 && now >= attempt && now - attempt < RETRY_AFTER) return;
        if (canceled) return;
        prefs.edit().putLong("attempt", now).apply();
        try {
            String result = String.valueOf(YoutubeDL.getInstance().updateYoutubeDL(
                    getApplicationContext(), YoutubeDL.UpdateChannel._STABLE));
            if ("DONE".equals(result) || "ALREADY_UP_TO_DATE".equals(result))
                prefs.edit().putLong("success", System.currentTimeMillis()).apply();
        } catch (Exception error) {
            Log.w("MP3Local", "Using installed yt-dlp motor due to update error", error);
        }
    }

    private File findMp3(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File result = findMp3(f);
                if (result != null) return result;
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                return f;
            }
        }
        return null;
    }

    private void broadcastStatus(String message, int percent, boolean finished) {
        Intent broadcast = new Intent(ACTION_STATUS);
        broadcast.setPackage(getPackageName());
        broadcast.putExtra(EXTRA_MESSAGE, message);
        broadcast.putExtra(EXTRA_PERCENT, percent);
        broadcast.putExtra(EXTRA_FINISHED, finished);
        sendBroadcast(broadcast);
    }

    private void broadcastQueue() {
        Intent changed = new Intent(ACTION_QUEUE_CHANGED);
        changed.setPackage(getPackageName());
        sendBroadcast(changed);
    }

    private Notification notification(String message, int percent, String activeId) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPI = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Noel Mp3 Free · Descargas")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(openPI)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (percent < 0) builder.setProgress(0, 0, true);
        else builder.setProgress(100, percent, false);
        if (activeId != null) {
            Intent cancel = new Intent(this, DownloadService.class)
                    .setAction(ACTION_CANCEL_ITEM)
                    .putExtra(EXTRA_JOB_ID, activeId);
            PendingIntent pi = PendingIntent.getService(this, 10, cancel,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar actual", pi);
        }
        return builder.build();
    }

    private void notifyFinished(String message) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Noel Mp3 Free")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify(
                (int) (System.currentTimeMillis() % 30000) + 2000, n);
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
        worker.shutdownNow();
        super.onDestroy();
    }
}
