package org.parg.azureus.plugins.webtorrent;

import java.net.URI;

/**
 * Low-volume, privacy-safe diagnostic messages for real BiglyBT soak tests.
 * Never include SDP, torrent hashes, peer IDs, tracker query strings or credentials.
 */
final class TrackerDiagnostics {
    private TrackerDiagnostics() {}

    static String endpoint(String url) {
        if (url == null) return "unknown";
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) return "invalid";
            return uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
    }

    static void report(WebTorrentPlugin plugin, String component, String event, String trackerUrl, String detail) {
        if (plugin == null) return; // Lightweight mock-host tests
        try {
            plugin.log("[soak][" + component + "] " + event
                + " tracker=" + endpoint(trackerUrl)
                + (detail == null || detail.isEmpty() ? "" : " " + detail));
        } catch (RuntimeException ignored) {
            // Logging cannot interfere with tracker traffic or shutdown.
        }
    }
}
