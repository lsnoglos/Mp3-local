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
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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
import java.util.List;

/** Navigation, capture/paste and a FIFO download queue. All interface controls are local. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 20, 30);
    private static final int PANEL = Color.rgb(28, 35, 50);
    private static final int STONE = Color.rgb(58, 69, 87);
    private static final int BLUE = Color.rgb(51, 93, 190);
    private static final int PURPLE = Color.rgb(111, 74, 170);
    private static final int AMBER = Color.rgb(184, 110, 31);
    private static final int GREEN = Color.rgb(22, 130, 88);
    private static final int RED = Color.rgb(165, 52, 67);
    private static final int GOLD = Color.rgb(255, 180, 88);
    private static final int WHITE = Color.rgb(246, 248, 252);
    private static final int MUTED = Color.rgb(173, 184, 200);

    private EditText search;
    private WebView web;
    private ScrollView queueScroll;
    private LinearLayout queueItems;
    private ScrollView library;
    private LinearLayout libraryItems;
    private Button browserTab;
    private Button queueTab;
    private Button libraryTab;
    private TextView status;
    private ProgressBar downloadProgress;
    private ProgressBar pageProgress;
    private int selectedTab = 0; // 0=browser, 1=queue, 2=library
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DownloadService.ACTION_STATUS.equals(action)) {
                String message = intent.getStringExtra(DownloadService.EXTRA_MESSAGE);
                int percent = intent.getIntExtra(DownloadService.EXTRA_PERCENT, -1);
                status.setText(message == null ? "Listo" : message);
                if (intent.getBooleanExtra(DownloadService.EXTRA_FINISHED, false)) {
                    downloadProgress.setVisibility(View.GONE);
                } else if (DownloadQueueStore.pending(MainActivity.this) > 0) {
                    downloadProgress.setVisibility(View.VISIBLE);
                    downloadProgress.setIndeterminate(percent < 0);
                    if (percent >= 0) downloadProgress.setProgress(percent);
                }
            }
            if (DownloadService.ACTION_QUEUE_CHANGED.equals(action) ||
                    DownloadService.ACTION_STATUS.equals(action)) {
                updateQueueBadge();
                if (selectedTab == 1) refreshQueue();
                if (selectedTab == 2 && DownloadQueueStore.pending(MainActivity.this) == 0)
                    refreshLibrary();
            }
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        createScreen();
        registerStatusReceiver();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
        handleSharedLink(getIntent());
        updateQueueBadge();
        if (DownloadQueueStore.pending(this) > 0) {
            Intent resume = new Intent(this, DownloadService.class)
                    .setAction(DownloadService.ACTION_RESUME);
            try { startForegroundService(resume); }
            catch (Exception ex) { status.setText("Abre Cola para reintentar: " + ex.getMessage()); }
        }
    }

    private void createScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(9), dp(12), dp(10));
        setContentView(root);

        TextView heading = new TextView(this);
        heading.setText("♫  MP3 LOCAL");
        heading.setTextColor(GOLD);
        heading.setTextSize(23);
        heading.setTypeface(null, Typeface.BOLD);
        root.addView(heading);

        TextView intro = new TextView(this);
        intro.setText("Explora, captura enlaces y organiza tus descargas");
        intro.setTextSize(12);
        intro.setTextColor(MUTED);
        intro.setPadding(0, dp(2), 0, dp(12));
        root.addView(intro);

        LinearLayout searchRow = horizontal();
        root.addView(searchRow);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setTextColor(WHITE);
        search.setHintTextColor(MUTED);
        search.setHint("Buscar o pegar URL");
        search.setPadding(dp(12), 0, dp(10), 0);
        search.setBackground(shape(PANEL, 12));
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button go = button("Ir", BLUE, 14);
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(dp(57), dp(48));
        goParams.leftMargin = dp(7);
        searchRow.addView(go, goParams);
        go.setOnClickListener(v -> navigate());

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setProgressTintList(ColorStateList.valueOf(BLUE));
        pageProgress.setProgressBackgroundTintList(ColorStateList.valueOf(PANEL));
        pageProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pg = new LinearLayout.LayoutParams(-1, dp(3));
        pg.topMargin = dp(3);
        root.addView(pageProgress, pg);

        LinearLayout controls = horizontal();
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(-1, dp(44));
        controlParams.topMargin = dp(8);
        root.addView(controls, controlParams);

        Button back = button("←", STONE, 24);
        Button reload = button("↻", BLUE, 22);
        Button paste = button("Pegar", PURPLE, 12);
        Button capture = button("Capturar actual", AMBER, 12);
        controls.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        controls.addView(reload, fixedWithGap(44, 5));
        controls.addView(paste, flexibleWithGap(1, 6));
        controls.addView(capture, flexibleWithGap(1.4f, 6));
        back.setContentDescription("Volver a la página anterior");
        reload.setContentDescription("Recargar página actual");
        back.setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        reload.setOnClickListener(v -> { if (selectedTab != 0) showTab(0); web.reload(); });
        paste.setOnClickListener(v -> pasteOnly());
        capture.setOnClickListener(v -> captureCurrent());

        Button download = button("Descargar MP3", GREEN, 16);
        download.setTypeface(null, Typeface.BOLD);
        Drawable icon = getDrawable(R.drawable.ic_download);
        if (icon != null) {
            icon.setBounds(0, 0, dp(23), dp(23));
            download.setCompoundDrawables(icon, null, null, null);
            download.setCompoundDrawablePadding(dp(10));
        }
        download.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(50));
        dl.topMargin = dp(9);
        root.addView(download, dl);
        download.setOnClickListener(v -> enqueueDownload());

        LinearLayout tabs = horizontal();
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(-1, dp(42));
        tabParams.topMargin = dp(12);
        root.addView(tabs, tabParams);
        browserTab = button("Navegar", BLUE, 12);
        queueTab = button("Cola (0)", PANEL, 12);
        libraryTab = button("Mis MP3", PANEL, 12);
        tabs.addView(browserTab, new LinearLayout.LayoutParams(0, dp(42), 1));
        tabs.addView(queueTab, flexibleWithGap(1, 6));
        tabs.addView(libraryTab, flexibleWithGap(1, 6));
        browserTab.setOnClickListener(v -> showTab(0));
        queueTab.setOnClickListener(v -> showTab(1));
        libraryTab.setOnClickListener(v -> showTab(2));

        FrameLayout body = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1);
        contentParams.topMargin = dp(8);
        root.addView(body, contentParams);

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                pageProgress.setProgress(newProgress);
                pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                String host = target.getHost();
                if ("https".equalsIgnoreCase(target.getScheme()) && host != null) {
                    host = host.toLowerCase();
                    if (host.equals("youtube.com") || host.endsWith(".youtube.com") ||
                            host.equals("youtu.be")) return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, target));
                } catch (ActivityNotFoundException error) {
                    message("No se pudo abrir el enlace externo");
                }
                return true;
            }
        });
        body.addView(web, new FrameLayout.LayoutParams(-1, -1));

        queueScroll = new ScrollView(this);
        queueScroll.setVisibility(View.GONE);
        queueItems = new LinearLayout(this);
        queueItems.setPadding(dp(4), dp(8), dp(4), dp(8));
        queueItems.setOrientation(LinearLayout.VERTICAL);
        queueScroll.addView(queueItems);
        body.addView(queueScroll, new FrameLayout.LayoutParams(-1, -1));

        library = new ScrollView(this);
        library.setVisibility(View.GONE);
        libraryItems = new LinearLayout(this);
        libraryItems.setPadding(dp(4), dp(8), dp(4), dp(8));
        libraryItems.setOrientation(LinearLayout.VERTICAL);
        library.addView(libraryItems);
        body.addView(library, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setText("Listo. «Pegar» no navega; usa «Ir» para abrir el enlace.");
        status.setTextColor(MUTED);
        status.setTextSize(12);
        status.setMaxLines(3);
        status.setPadding(0, dp(9), 0, dp(5));
        root.addView(status);
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        downloadProgress.setProgressTintList(ColorStateList.valueOf(GREEN));
        downloadProgress.setProgressBackgroundTintList(ColorStateList.valueOf(PANEL));
        downloadProgress.setVisibility(View.GONE);
        root.addView(downloadProgress, new LinearLayout.LayoutParams(-1, dp(4)));
        web.loadUrl("https://m.youtube.com/");
    }

    private void navigate() {
        String input = search.getText().toString().trim();
        if (input.isEmpty()) {
            web.loadUrl("https://m.youtube.com/");
        } else if (input.startsWith("http://") || input.startsWith("https://")) {
            Uri uri = Uri.parse(input);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null ||
                    !(host.equalsIgnoreCase("youtu.be") ||
                            host.equalsIgnoreCase("youtube.com") ||
                            host.toLowerCase().endsWith(".youtube.com"))) {
                message("Solo se navega por enlaces HTTPS de YouTube");
                return;
            }
            web.loadUrl(input);
        } else {
            web.loadUrl("https://m.youtube.com/results?search_query=" +
                    URLEncoder.encode(input, StandardCharsets.UTF_8));
        }
        showTab(0);
    }

    /** Paste only updates the input. The user must press Ir to navigate. */
    private void pasteOnly() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            message("El portapapeles está vacío");
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text != null) {
            search.setText(text.toString().trim());
            search.setSelection(search.length());
            status.setText("Enlace pegado. Pulsa «Ir» para navegar o «Descargar MP3» para agregarlo a la cola.");
        }
    }

    /** Captures the displayed page without navigating or reloading. */
    private void captureCurrent() {
        String url = web.getUrl();
        if (url == null || !url.startsWith("https://")) {
            message("Todavía no hay una página para capturar");
            return;
        }
        search.setText(url);
        search.setSelection(search.length());
        status.setText("URL actual capturada. No se ha recargado la página.");
    }

    private void enqueueDownload() {
        String input = search.getText().toString().trim();
        String current = web.getUrl();
        String url;
        if (UrlTools.isVideoUrl(input)) {
            url = input;
        } else if (input.startsWith("http://") || input.startsWith("https://")) {
            message("La URL escrita no corresponde a un video individual de YouTube");
            return;
        } else if (UrlTools.isVideoUrl(current)) {
            url = current;
        } else {
            message("Abre un video o pega su enlace válido antes de descargar");
            return;
        }
        Intent intent = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_DOWNLOAD)
                .putExtra(DownloadService.EXTRA_URL, url);
        try {
            startForegroundService(intent);
            status.setText("Añadiendo video a la cola. Puedes seguir navegando.");
            downloadProgress.setVisibility(View.VISIBLE);
            downloadProgress.setIndeterminate(true);
        } catch (Exception error) {
            message("No fue posible iniciar la cola: " + error.getMessage());
        }
    }

    private void showTab(int tab) {
        selectedTab = tab;
        web.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        queueScroll.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        library.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        styleTab(browserTab, tab == 0);
        styleTab(queueTab, tab == 1);
        styleTab(libraryTab, tab == 2);
        if (tab == 1) refreshQueue();
        if (tab == 2) refreshLibrary();
    }

    private void styleTab(Button button, boolean selected) {
        button.setBackground(shape(selected ? BLUE : PANEL, 10));
        button.setTextColor(selected ? WHITE : MUTED);
    }

    private void updateQueueBadge() {
        if (queueTab != null) {
            queueTab.setText("Cola (" + DownloadQueueStore.pending(this) + ")");
        }
    }

    private void refreshQueue() {
        int previousY = queueScroll.getScrollY();
        queueItems.removeAllViews();
        LinearLayout titleBar = horizontal();
        queueItems.addView(titleBar, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView title = text("Cola de descargas", WHITE, 17, true);
        titleBar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = button("Limpiar historial", STONE, 11);
        titleBar.addView(clear, new LinearLayout.LayoutParams(dp(126), dp(38)));
        clear.setOnClickListener(v -> {
            DownloadQueueStore.clearFinished(this);
            refreshQueue();
            updateQueueBadge();
        });
        List<DownloadQueueStore.Job> jobs = DownloadQueueStore.list(this);
        if (jobs.isEmpty()) {
            addText(queueItems, "Sin descargas. Navega por YouTube y pulsa «Descargar MP3».",
                    MUTED, 14);
        } else {
            addText(queueItems, "Las descargas se procesan una por una. Puedes seguir navegando.",
                    MUTED, 12);
            for (DownloadQueueStore.Job job : jobs) drawQueueJob(job);
        }
        updateQueueBadge();
        queueScroll.post(() -> queueScroll.scrollTo(0, previousY));
    }

    private void drawQueueJob(DownloadQueueStore.Job job) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(10), dp(13), dp(12));
        card.setBackground(shape(PANEL, 12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(9);
        queueItems.addView(card, cardParams);

        LinearLayout firstLine = horizontal();
        card.addView(firstLine, new LinearLayout.LayoutParams(-1, -2));
        TextView label = text(job.label, WHITE, 14, true);
        label.setMaxLines(1);
        firstLine.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

        int color;
        String stateLabel;
        switch (job.status) {
            case DownloadQueueStore.RUNNING: color = GOLD; stateLabel = "Descargando"; break;
            case DownloadQueueStore.WAITING: color = MUTED; stateLabel = "En espera"; break;
            case DownloadQueueStore.COMPLETED: color = Color.rgb(81, 207, 140);
                stateLabel = "Completado"; break;
            case DownloadQueueStore.FAILED: color = Color.rgb(255, 112, 112);
                stateLabel = "Error"; break;
            default: color = MUTED; stateLabel = "Cancelado"; break;
        }
        TextView state = text(stateLabel, color, 11, true);
        firstLine.addView(state);

        TextView shortUrl = text(job.url, MUTED, 11, false);
        shortUrl.setSingleLine(true);
        shortUrl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(-1, -2);
        urlParams.topMargin = dp(5);
        card.addView(shortUrl, urlParams);

        TextView detail = text(job.message, MUTED, 12, false);
        LinearLayout.LayoutParams dt = new LinearLayout.LayoutParams(-1, -2);
        dt.topMargin = dp(6);
        card.addView(detail, dt);

        if (DownloadQueueStore.RUNNING.equals(job.status)) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(job.percent);
            bar.setProgressTintList(ColorStateList.valueOf(GREEN));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(STONE));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
            progressParams.topMargin = dp(9);
            card.addView(bar, progressParams);
        }

        if (DownloadQueueStore.WAITING.equals(job.status) ||
                DownloadQueueStore.RUNNING.equals(job.status) ||
                DownloadQueueStore.FAILED.equals(job.status) ||
                DownloadQueueStore.CANCELLED.equals(job.status)) {
            Button action;
            boolean cancel = DownloadQueueStore.WAITING.equals(job.status) ||
                    DownloadQueueStore.RUNNING.equals(job.status);
            action = button(cancel ? "✕ Cancelar" : "↻ Reintentar",
                    cancel ? RED : BLUE, 12);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(38));
            actionParams.topMargin = dp(8);
            card.addView(action, actionParams);
            action.setOnClickListener(v -> {
                Intent service = new Intent(this, DownloadService.class);
                service.setAction(cancel ? DownloadService.ACTION_CANCEL_ITEM :
                        DownloadService.ACTION_RETRY_ITEM);
                service.putExtra(DownloadService.EXTRA_JOB_ID, job.id);
                if (cancel) startService(service);
                else startForegroundService(service);
            });
        }
    }

    private void refreshLibrary() {
        libraryItems.removeAllViews();
        addText(libraryItems, "Archivos guardados en Música / MP3 Local", GOLD, 17);
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] columns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME};
        String filter = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
        int count = 0;
        try (Cursor cursor = getContentResolver().query(collection, columns, filter,
                new String[]{"Music/Mp3 Local/%"}, MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) while (cursor.moveToNext()) {
                count++;
                long id = cursor.getLong(0);
                String name = cursor.getString(1);
                Uri fileUri = ContentUris.withAppendedId(collection, id);
                LinearLayout item = horizontal();
                item.setPadding(dp(10), dp(8), dp(8), dp(8));
                item.setBackground(shape(PANEL, 12));
                LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(-1, -2);
                itemParams.topMargin = dp(9);
                libraryItems.addView(item, itemParams);
                TextView itemName = text(name, WHITE, 13, false);
                itemName.setMaxLines(2);
                item.addView(itemName, new LinearLayout.LayoutParams(0, -2, 1));
                Button play = button("▶", GREEN, 17);
                item.addView(play, new LinearLayout.LayoutParams(dp(42), dp(42)));
                play.setOnClickListener(v -> {
                    Intent playIntent = new Intent(Intent.ACTION_VIEW);
                    playIntent.setDataAndType(fileUri, "audio/mpeg");
                    playIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(playIntent); }
                    catch (Exception ex) { message("No se encontró un reproductor de MP3"); }
                });
                Button share = button("↗", PURPLE, 17);
                item.addView(share, fixedWithGap(42, 6));
                share.setOnClickListener(v -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("audio/mpeg");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "Compartir MP3"));
                });
            }
        } catch (Exception error) {
            addText(libraryItems, "Error al leer MP3: " + error.getMessage(), MUTED, 12);
        }
        if (count == 0) addText(libraryItems, "Todavía no tienes MP3 guardados.", MUTED, 14);
    }

    private void handleSharedLink(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction()) ||
                !"text/plain".equals(intent.getType())) return;
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared == null) return;
        for (String token : shared.split("\\s+")) {
            String candidate = token.replaceAll("[),.;]+$", "");
            if (UrlTools.isVideoUrl(candidate)) {
                search.setText(candidate);
                search.setSelection(search.length());
                status.setText("Enlace recibido. Usa «Ir» para navegar o «Descargar MP3» para ponerlo en cola.");
                return;
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedLink(intent);
    }

    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(DownloadService.ACTION_STATUS);
        filter.addAction(DownloadService.ACTION_QUEUE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (queueTab != null) updateQueueBadge();
        if (selectedTab == 1 && queueItems != null) refreshQueue();
        if (selectedTab == 2 && libraryItems != null) refreshLibrary();
    }

    private void addText(LinearLayout parent, String value, int color, int size) {
        TextView v = text(value, color, size, false);
        v.setPadding(dp(4), dp(12), dp(4), dp(5));
        parent.addView(v);
    }
    private TextView text(String value, int color, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }
    private Button button(String label, int background, int fontSize) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(WHITE);
        button.setTextSize(fontSize);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(shape(background, 10));
        return button;
    }
    private LinearLayout.LayoutParams fixedWithGap(int width, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(width), dp(44));
        params.leftMargin = dp(gap);
        return params;
    }
    private LinearLayout.LayoutParams flexibleWithGap(float weight, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), weight);
        params.leftMargin = dp(gap);
        return params;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
    private void message(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    @Override public void onBackPressed() {
        if (selectedTab != 0) showTab(0);
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(receiver);
        web.destroy();
        super.onDestroy();
    }
}
