package com.lsnoglos.mp3local;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

/**
 * Central, persistent settings and destination writer.
 * Default: Music/MP3 Local in MediaStore. Optional: user-granted SAF folder.
 * A job always retains the settings selected when it was enqueued.
 */
public final class OutputSettings {
    public static final int DEFAULT_QUALITY = 192;
    public static final String DEFAULT_FOLDER = "Music/MP3 Local/";
    private static final String PREFS = "noel_mp3_preferences";
    private static final String KEY_QUALITY = "quality_kbps";
    private static final String KEY_FOLDER = "destination_tree";
    private static final int[] QUALITIES = {128, 192, 256, 320};

    private OutputSettings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isValidQuality(int quality) {
        for (int option : QUALITIES) if (quality == option) return true;
        return false;
    }

    public static int quality(Context context) {
        int candidate = prefs(context).getInt(KEY_QUALITY, DEFAULT_QUALITY);
        return isValidQuality(candidate) ? candidate : DEFAULT_QUALITY;
    }

    public static void setQuality(Context context, int quality) {
        if (!isValidQuality(quality)) throw new IllegalArgumentException("Calidad inválida");
        prefs(context).edit().putInt(KEY_QUALITY, quality).apply();
    }

    public static String destination(Context context) {
        return prefs(context).getString(KEY_FOLDER, "");
    }

    public static void setDestination(Context context, Uri treeUri) {
        if (treeUri == null || !DocumentsContract.isTreeUri(treeUri))
            throw new IllegalArgumentException("Carpeta no válida");
        prefs(context).edit().putString(KEY_FOLDER, treeUri.toString()).apply();
    }

    public static void restoreDefault(Context context) {
        prefs(context).edit().remove(KEY_FOLDER).apply();
    }

    public static String describe(String treeUri) {
        if (treeUri == null || treeUri.isEmpty()) return "Música / MP3 Local (predeterminada)";
        try {
            String documentId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri));
            int colon = documentId.indexOf(':');
            String path = colon >= 0 ? documentId.substring(colon + 1) : documentId;
            return path.isEmpty() ? "Carpeta seleccionada" : path;
        } catch (Exception error) {
            return "Carpeta personalizada";
        }
    }

    public static boolean hasWriteGrant(Context context, Uri tree) {
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(tree) && permission.isWritePermission()) return true;
        }
        return false;
    }

    public static Uri save(Context context, File source, String treeUri,
                           BooleanSupplier canceled) throws Exception {
        if (treeUri == null || treeUri.isEmpty()) return saveDefault(context, source, canceled);
        Uri tree = Uri.parse(treeUri);
        if (!hasWriteGrant(context, tree)) {
            throw new IllegalStateException(
                    "Permiso de la carpeta caducado. Selecciona nuevamente la carpeta en ⚙ Ajustes.");
        }
        ContentResolver resolver = context.getContentResolver();
        Uri document = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        Uri newFile = DocumentsContract.createDocument(resolver, document, "audio/mpeg",
                source.getName());
        if (newFile == null) {
            throw new IllegalStateException("No se pudo crear MP3 en la carpeta seleccionada.");
        }
        try {
            copy(context, source, newFile, canceled);
            return newFile;
        } catch (Exception exception) {
            try { DocumentsContract.deleteDocument(resolver, newFile); }
            catch (Exception ignored) { }
            throw exception;
        }
    }

    private static Uri saveDefault(Context context, File source, BooleanSupplier canceled)
            throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/MP3 Local/");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("No se pudo crear el MP3.");
        try {
            copy(context, source, uri, canceled);
            values.clear();
            values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception error) {
            context.getContentResolver().delete(uri, null, null);
            throw error;
        }
    }

    private static void copy(Context context, File source, Uri destination,
                             BooleanSupplier canceled) throws Exception {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) throw new IllegalStateException("No se puede escribir el MP3.");
            byte[] bytes = new byte[65536];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (canceled.getAsBoolean()) throw new InterruptedException("Descarga cancelada");
                output.write(bytes, 0, count);
            }
            output.flush();
        }
    }
}
