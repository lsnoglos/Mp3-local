package com.lsnoglos.mp3local;

import java.net.URI;
import java.util.Locale;

/** Rejects non-video addresses before passing a URL to the local downloader. */
public final class UrlTools {
    private UrlTools() {}

    public static boolean isVideoUrl(String input) {
        if (input == null || input.trim().isEmpty()) return false;
        try {
            URI uri = URI.create(input.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            if (path == null) return false;
            if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
                return path.length() > 1;
            }
            if (!(host.equals("youtube.com") || host.endsWith(".youtube.com"))) {
                return false;
            }
            if (path.equals("/watch")) {
                String query = uri.getRawQuery();
                return query != null && query.matches("(?s)(^|.*&)v=[^&]+(&.*|$)");
            }
            return path.matches("/(shorts|live|embed)/[^/]+/?");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
