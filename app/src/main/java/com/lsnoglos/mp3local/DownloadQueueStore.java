package com.lsnoglos.mp3local;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Small persistent FIFO queue. SharedPreferences commits guarantee queued links survive
 * leaving the app or process termination. Only one active item is consumed at a time.
 * All read-modify-write operations share the same lock.
 */
public final class DownloadQueueStore {
    public static final String WAITING = "WAITING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private static final String PREF_NAME = "mp3_download_queue_v1";
    private static final String KEY = "jobs";
    private static final int MAX_HISTORY = 100;

    private DownloadQueueStore() { }

    public static class Job {
        public String id;
        public String url;
        public String label;
        public String status;
        public String message;
        public int percent;
        public long createdAt;
        public int qualityKbps;
        public String destinationTree;

        Job(String id, String url, String label, String status,
            String message, int percent, long createdAt) {
            this.id = id;
            this.url = url;
            this.label = label;
            this.status = status;
            this.message = message;
            this.percent = percent;
            this.createdAt = createdAt;
            this.qualityKbps = OutputSettings.DEFAULT_QUALITY;
            this.destinationTree = "";
        }

        JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("url", url);
            obj.put("label", label);
            obj.put("status", status);
            obj.put("message", message);
            obj.put("percent", percent);
            obj.put("createdAt", createdAt);
            obj.put("qualityKbps", qualityKbps);
            obj.put("destinationTree", destinationTree);
            return obj;
        }

        static Job fromJson(JSONObject obj) {
            Job job = new Job(
                    obj.optString("id", ""),
                    obj.optString("url", ""),
                    obj.optString("label", "Video"),
                    obj.optString("status", WAITING),
                    obj.optString("message", ""),
                    obj.optInt("percent", 0),
                    obj.optLong("createdAt", 0)
            );
            int bitrate = obj.optInt("qualityKbps", OutputSettings.DEFAULT_QUALITY);
            job.qualityKbps = OutputSettings.isValidQuality(bitrate) ?
                    bitrate : OutputSettings.DEFAULT_QUALITY;
            job.destinationTree = obj.optString("destinationTree", "");
            return job;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static List<Job> read(Context context) {
        List<Job> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) {
                    Job item = Job.fromJson(obj);
                    if (!item.id.isEmpty() && UrlTools.isVideoUrl(item.url)) out.add(item);
                }
            }
        } catch (JSONException ex) {
            Log.e("MP3LocalQueue", "Cannot read saved queue", ex);
        }
        return out;
    }

    private static void write(Context context, List<Job> jobs) {
        JSONArray array = new JSONArray();
        try {
            for (Job job : jobs) array.put(job.toJson());
            if (!prefs(context).edit().putString(KEY, array.toString()).commit()) {
                Log.e("MP3LocalQueue", "Failed to persist queue");
            }
        } catch (JSONException ex) {
            Log.e("MP3LocalQueue", "Cannot encode queue", ex);
        }
    }

    public static synchronized List<Job> list(Context context) {
        return read(context);
    }

    public static synchronized Job enqueue(Context context, String url) {
        if (!UrlTools.isVideoUrl(url)) return null;
        List<Job> jobs = read(context);
        // Avoid accidental multiple taps while still permitting a later re-download.
        for (Job job : jobs) {
            if (job.url.equals(url) && (WAITING.equals(job.status) ||
                    RUNNING.equals(job.status))) return job;
        }
        Job job = new Job(UUID.randomUUID().toString(), url, labelFor(url),
                WAITING, "En espera", 0, System.currentTimeMillis());
        job.qualityKbps = OutputSettings.quality(context);
        job.destinationTree = OutputSettings.destination(context);
        jobs.add(job);
        prune(jobs);
        write(context, jobs);
        return job;
    }

    public static synchronized Job takeNext(Context context) {
        List<Job> jobs = read(context);
        for (Job job : jobs) {
            if (WAITING.equals(job.status)) {
                job.status = RUNNING;
                job.percent = 0;
                job.message = "Preparando descarga";
                write(context, jobs);
                return job;
            }
        }
        return null;
    }

    public static synchronized void update(Context context, String id,
                                            String status, int percent, String message) {
        List<Job> jobs = read(context);
        for (Job job : jobs) {
            if (job.id.equals(id)) {
                // A cancellation is terminal even if a worker reports progress afterwards.
                if (CANCELLED.equals(job.status) && !CANCELLED.equals(status)) return;
                job.status = status;
                job.percent = Math.max(0, Math.min(100, percent));
                job.message = message == null ? "" : message;
                write(context, jobs);
                return;
            }
        }
    }

    public static synchronized boolean cancel(Context context, String id) {
        List<Job> jobs = read(context);
        for (Job job : jobs) {
            if (job.id.equals(id) && (RUNNING.equals(job.status) || WAITING.equals(job.status))) {
                boolean active = RUNNING.equals(job.status);
                job.status = CANCELLED;
                job.message = "Cancelada";
                write(context, jobs);
                return active;
            }
        }
        return false;
    }

    public static synchronized boolean retry(Context context, String id) {
        List<Job> jobs = read(context);
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            if (job.id.equals(id) && (FAILED.equals(job.status) ||
                    CANCELLED.equals(job.status))) {
                job.status = WAITING;
                job.percent = 0;
                job.message = "En espera (reintentando)";
                jobs.remove(i);
                jobs.add(job); // Retry joins the END of the FIFO queue.
                write(context, jobs);
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean isCancelled(Context context, String id) {
        for (Job job : read(context)) {
            if (job.id.equals(id)) return CANCELLED.equals(job.status);
        }
        return false;
    }

    public static synchronized int pending(Context context) {
        int count = 0;
        for (Job job : read(context)) {
            if (WAITING.equals(job.status) || RUNNING.equals(job.status)) count++;
        }
        return count;
    }

    public static synchronized void clearFinished(Context context) {
        List<Job> jobs = read(context);
        jobs.removeIf(job -> COMPLETED.equals(job.status) || FAILED.equals(job.status) ||
                CANCELLED.equals(job.status));
        write(context, jobs);
    }

    /** Called only when the service is freshly started and no worker is running. */
    public static synchronized void recoverInterrupted(Context context) {
        List<Job> jobs = read(context);
        boolean changed = false;
        for (Job job : jobs) {
            if (RUNNING.equals(job.status)) {
                job.status = WAITING;
                job.percent = 0;
                job.message = "Reanudada tras reiniciar el servicio";
                changed = true;
            }
        }
        if (changed) write(context, jobs);
    }

    private static void prune(List<Job> jobs) {
        int countCompleted = 0;
        for (Job job : jobs) {
            if (COMPLETED.equals(job.status) || FAILED.equals(job.status) ||
                    CANCELLED.equals(job.status)) countCompleted++;
        }
        Iterator<Job> it = jobs.iterator();
        while (countCompleted > MAX_HISTORY && it.hasNext()) {
            Job job = it.next();
            if (COMPLETED.equals(job.status) || FAILED.equals(job.status) ||
                    CANCELLED.equals(job.status)) {
                it.remove();
                countCompleted--;
            }
        }
    }

    private static String labelFor(String url) {
        try {
            Uri uri = Uri.parse(url);
            String id = uri.getQueryParameter("v");
            if (id == null || id.isEmpty()) id = uri.getLastPathSegment();
            if (id != null && !id.isEmpty()) {
                return "Video " + (id.length() > 24 ? id.substring(0, 24) : id);
            }
        } catch (Exception ignored) { }
        return "Video de YouTube";
    }
}
