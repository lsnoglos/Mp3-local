package com.lsnoglos.mp3local;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(12, 18, 32);
    private static final int PANEL = Color.rgb(25, 35, 54);
    private static final int ACCENT = Color.rgb(101, 228, 198);
    private static final int WHITE = Color.rgb(240, 245, 252);
    private static final int MUTED = Color.rgb(167, 178, 192);

    private EditText search;
    private WebView web;
    private FrameLayout body;
    private ScrollView library;
    private LinearLayout libraryItems;
    private TextView status;
    private ProgressBar progress;
    private Button browserTab;
    private Button filesTab;
    private boolean showingFiles = false;
    private boolean receiverRegistered = false;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadService.ACTION_STATUS.equals(intent.getAction())) return;
            String message = intent.getStringExtra(DownloadService.EXTRA_MESSAGE);
            int percent = intent.getIntExtra(DownloadService.EXTRA_PERCENT, -1);
            status.setText(message == null ? "Listo" : message);
            progress.setIndeterminate(percent < 0);
            if (percent >= 0) progress.setProgress(percent);
            if (intent.getBooleanExtra(DownloadService.EXTRA_FINISHED, false)) {
                progress.setVisibility(View.GONE);
                if (showingFiles) refreshLibrary();
            } else {
                progress.setVisibility(View.VISIBLE);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        createScreen();
        registerStatusReceiver();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
        handleSharedLink(getIntent());
    }

    private void createScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        setContentView(root);

        TextView heading = new TextView(this);
        heading.setText("♫  MP3 LOCAL");
        heading.setTextColor(ACCENT);
        heading.setTextSize(23);
        heading.setTypeface(null, Typeface.BOLD);
        root.addView(heading);

        TextView hint = new TextView(this);
        hint.setText("Explora videos y guarda audio autorizado en tu teléfono");
        hint.setTextSize(12);
        hint.setTextColor(MUTED);
        hint.setPadding(0, dp(3), 0, dp(12));
        root.addView(hint);

        LinearLayout searchRow = horizontal();
        root.addView(searchRow);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(WHITE);
        search.setHintTextColor(MUTED);
        search.setHint("Buscar o pegar enlace de YouTube");
        search.setPadding(dp(10), 0, dp(10), 0);
        search.setBackground(shape(PANEL, 12));
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button go = button("Ir", ACCENT, BG);
        searchRow.addView(go, buttonParams(8));
        go.setOnClickListener(v -> navigate());

        LinearLayout actions = horizontal();
        actions.setPadding(0, dp(9), 0, dp(9));
        root.addView(actions);
        Button back = button("‹ Atrás", PANEL, WHITE);
        Button paste = button("Pegar", PANEL, WHITE);
        Button download = button("↓ MP3", ACCENT, BG);
        Button cancel = button("✕", PANEL, WHITE);
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(paste, weightedParams());
        actions.addView(download, weightedParams());
        actions.addView(cancel, new LinearLayout.LayoutParams(dp(46), dp(42)));
        back.setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        paste.setOnClickListener(v -> paste());
        download.setOnClickListener(v -> download());
        cancel.setOnClickListener(v -> startService(new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_CANCEL)));

        LinearLayout tabs = horizontal();
        root.addView(tabs);
        browserTab = button("Navegar", ACCENT, BG);
        filesTab = button("Mis MP3", PANEL, WHITE);
        Button updateMotor = button("↻ Motor", PANEL, WHITE);
        tabs.addView(browserTab, new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(filesTab, weightedParams());
        tabs.addView(updateMotor, weightedParams());
        browserTab.setOnClickListener(v -> showLibrary(false));
        filesTab.setOnClickListener(v -> showLibrary(true));
        updateMotor.setOnClickListener(v -> updateYtDlp());

        body = new FrameLayout(this);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        bodyParams.topMargin = dp(10);
        root.addView(body, bodyParams);

        web = new WebView(this);
        web.setBackgroundColor(WHITE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                String scheme = target.getScheme();
                String host = target.getHost();
                if (host != null && "https".equalsIgnoreCase(scheme) &&
                        (host.equals("youtube.com") || host.endsWith(".youtube.com") ||
                         host.equals("youtu.be"))) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, target));
                } catch (ActivityNotFoundException ignored) {
                    message("No se pudo abrir el enlace");
                }
                return true;
            }
        });
        body.addView(web, new FrameLayout.LayoutParams(-1, -1));

        library = new ScrollView(this);
        library.setFillViewport(true);
        library.setVisibility(View.GONE);
        libraryItems = new LinearLayout(this);
        libraryItems.setOrientation(LinearLayout.VERTICAL);
        libraryItems.setPadding(dp(4), dp(4), dp(4), dp(4));
        library.addView(libraryItems);
        body.addView(library, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setText("Listo. Abre un video o pega su enlace.");
        status.setTextColor(MUTED);
        status.setTextSize(12);
        status.setMaxLines(3);
        status.setPadding(0, dp(10), 0, dp(3));
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        web.loadUrl("https://m.youtube.com/");
    }

    private void navigate() {
        String text = search.getText().toString().trim();
        if (text.isEmpty()) {
            web.loadUrl("https://m.youtube.com/");
        } else if (text.startsWith("https://") && Uri.parse(text).getHost() != null) {
            Uri uri = Uri.parse(text);
            String host = uri.getHost().toLowerCase();
            if (host.equals("youtube.com") || host.endsWith(".youtube.com") ||
                    host.equals("youtu.be")) {
                web.loadUrl(text);
            } else {
                message("Por seguridad, solo se navega por YouTube");
                return;
            }
        } else {
            web.loadUrl("https://m.youtube.com/results?search_query=" +
                    URLEncoder.encode(text, StandardCharsets.UTF_8));
        }
        showLibrary(false);
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence item = clip.getItemAt(0).coerceToText(this);
            search.setText(item);
            if (item != null && UrlTools.isVideoUrl(item.toString())) navigate();
        }
    }

    private void download() {
        String typed = search.getText().toString().trim();
        String current = web.getUrl();
        String url = UrlTools.isVideoUrl(typed) ? typed :
                UrlTools.isVideoUrl(current) ? current : null;
        if (url == null) {
            message("Abre un video individual o pega un enlace válido");
            return;
        }
        Intent service = new Intent(this, DownloadService.class);
        service.setAction(DownloadService.ACTION_DOWNLOAD);
        service.putExtra(DownloadService.EXTRA_URL, url);
        try {
            startForegroundService(service);
            status.setText("Preparando descarga MP3…");
            progress.setIndeterminate(true);
            progress.setVisibility(View.VISIBLE);
        } catch (Exception error) {
            message("No se pudo iniciar la descarga: " + error.getMessage());
        }
    }

    private void updateYtDlp() {
        Intent service = new Intent(this, DownloadService.class);
        service.setAction(DownloadService.ACTION_UPDATE);
        try {
            startForegroundService(service);
            status.setText("Buscando actualizaciones del motor yt-dlp…");
            progress.setIndeterminate(true);
            progress.setVisibility(View.VISIBLE);
        } catch (Exception error) {
            message("No se pudo iniciar la actualización: " + error.getMessage());
        }
    }

    private void showLibrary(boolean show) {
        showingFiles = show;
        web.setVisibility(show ? View.GONE : View.VISIBLE);
        library.setVisibility(show ? View.VISIBLE : View.GONE);
        browserTab.setBackground(shape(show ? PANEL : ACCENT, 12));
        browserTab.setTextColor(show ? WHITE : BG);
        filesTab.setBackground(shape(show ? ACCENT : PANEL, 12));
        filesTab.setTextColor(show ? BG : WHITE);
        if (show) refreshLibrary();
    }

    private void refreshLibrary() {
        libraryItems.removeAllViews();
        addLibraryText("Archivos guardados en Música / MP3 Local", ACCENT, 17);
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME};
        String where = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
        int count = 0;
        try (Cursor cursor = getContentResolver().query(collection, cols, where,
                new String[]{"Music/Mp3 Local/%"}, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) while (cursor.moveToNext()) {
                count++;
                long id = cursor.getLong(0);
                String name = cursor.getString(1);
                Uri uri = ContentUris.withAppendedId(collection, id);
                LinearLayout item = horizontal();
                item.setPadding(dp(7), dp(10), dp(7), dp(10));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(8);
                libraryItems.addView(item, lp);
                item.setBackground(shape(PANEL, 12));
                TextView title = new TextView(this);
                title.setText(name);
                title.setTextColor(WHITE);
                title.setTextSize(13);
                title.setMaxLines(2);
                item.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                Button play = button("▶", ACCENT, BG);
                item.addView(play, new LinearLayout.LayoutParams(dp(45), dp(42)));
                play.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "audio/mpeg");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(intent); } catch (Exception e) {
                        message("Instala un reproductor de audio");
                    }
                });
                Button share = button("↗", PANEL, WHITE);
                item.addView(share, new LinearLayout.LayoutParams(dp(38), dp(42)));
                share.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("audio/mpeg");
                    intent.putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, "Compartir MP3"));
                });
            }
        } catch (Exception e) {
            addLibraryText("No se pudo leer la biblioteca: " + e.getMessage(), MUTED, 13);
        }
        if (count == 0) addLibraryText("Todavía no hay MP3 guardados.", MUTED, 13);
    }

    private void addLibraryText(String value, int color, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setPadding(dp(6), dp(14), dp(6), dp(6));
        libraryItems.addView(view);
    }

    private void handleSharedLink(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction()) &&
                "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) {
                // Shared texts may contain a description plus a video URL.
                for (String part : shared.split("\\s+")) {
                    String cleaned = part.replaceAll("[),.;]+$", "");
                    if (UrlTools.isVideoUrl(cleaned)) {
                        search.setText(cleaned);
                        web.loadUrl(cleaned);
                        showLibrary(false);
                        return;
                    }
                }
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedLink(intent);
    }

    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter(DownloadService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else registerReceiver(downloadReceiver, filter);
        receiverRegistered = true;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private LinearLayout.LayoutParams weightedParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
        p.leftMargin = dp(6);
        return p;
    }
    private LinearLayout.LayoutParams buttonParams(int gap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(55), dp(48));
        p.leftMargin = dp(gap);
        return p;
    }
    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setBackground(shape(background, 12));
        return button;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private int dp(int px) {
        return Math.round(getResources().getDisplayMetrics().density * px);
    }
    private void message(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    @Override public void onBackPressed() {
        if (showingFiles) showLibrary(false);
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(downloadReceiver);
        web.destroy();
        super.onDestroy();
    }
}
