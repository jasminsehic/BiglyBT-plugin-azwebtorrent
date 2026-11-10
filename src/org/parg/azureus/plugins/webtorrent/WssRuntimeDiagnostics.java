package org.parg.azureus.plugins.webtorrent;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;
import javax.websocket.Session;
import org.glassfish.tyrus.client.ClientManager;

/**
 * Diagnostic-only, privacy-safe visibility into the BiglyBT WebSocket runtime.
 * Never print source paths, IP addresses, SDP, credentials or full thread dumps.
 */
final class WssRuntimeDiagnostics {
    private WssRuntimeDiagnostics() { }

    static String classpath() {
        return "java=" + System.getProperty("java.version", "unknown")
            + " tyrusSource=" + sourceName(ClientManager.class)
            + " wsApiSource=" + sourceName(Session.class)
            + " pluginSource=" + sourceName(TrackerProxy.class)
            + " tyrusSameLoader=" + (ClientManager.class.getClassLoader()
                == TrackerProxy.class.getClassLoader())
            + " wsApiSameLoader=" + (Session.class.getClassLoader()
                == TrackerProxy.class.getClassLoader());
    }

    private static String sourceName(Class<?> type) {
        try {
            CodeSource code = type.getProtectionDomain().getCodeSource();
            if (code == null || code.getLocation() == null) return "unknown";
            String path = code.getLocation().getPath();
            if (path == null) return "unknown";
            path = path.replace('\\', '/');
            int index = path.lastIndexOf('/');
            String filename = index < 0 ? path : path.substring(index + 1);
            return filename.isEmpty() ? "directory" : filename;
        } catch (SecurityException e) {
            return "restricted";
        }
    }

    static String proxyTypes(URI trackerUri) {
        try {
            ProxySelector selector = ProxySelector.getDefault();
            if (selector == null) return "noSelector";
            StringBuilder summary = new StringBuilder();
            for (String scheme : new String[] {"socket", "https", "http"}) {
                URI proxyUri = new URI(scheme, null, trackerUri.getHost(),
                    trackerUri.getPort(), trackerUri.getPath(),
                    trackerUri.getQuery(), trackerUri.getFragment());
                List<Proxy> proxies = selector.select(proxyUri);
                boolean direct = true;
                if (proxies != null) {
                    for (Proxy proxy : proxies) {
                        if (proxy != null && proxy.type() != Proxy.Type.DIRECT) {
                            direct = false;
                            break;
                        }
                    }
                }
                if (summary.length() > 0) summary.append(',');
                summary.append(scheme).append('=')
                    .append(direct ? "DIRECT" : "PROXY_AVAILABLE");
            }
            return summary.toString();
        } catch (Exception e) {
            return "unavailable:" + e.getClass().getSimpleName();
        }
    }

    static void logHandshakeThreads(WebTorrentPlugin plugin, String tracker) {
        if (plugin == null) return;
        try {
            int count = 0;
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                String name = thread.getName();
                String lowered = name.toLowerCase(java.util.Locale.ROOT);
                if (!(lowered.contains("tyrus") || lowered.contains("grizzly")
                        || lowered.contains("websocket") || lowered.contains("wss")
                        || lowered.contains("nio"))) continue;
                StackTraceElement[] frames = entry.getValue();
                StringBuilder summary = new StringBuilder();
                for (int i = 0; i < Math.min(3, frames.length); i++) {
                    if (i > 0) summary.append(">");
                    summary.append(frames[i].getClassName())
                        .append('.').append(frames[i].getMethodName());
                }
                // Thread names are local implementation labels; do not log values
                // from stack-frame arguments or endpoint configuration.
                TrackerDiagnostics.report(plugin, "runtime", "handshake-thread",
                    tracker, "state=" + thread.getState()
                        + " stack=" + summary);
                if (++count >= 12) break;
            }
            if (count == 0) {
                TrackerDiagnostics.report(plugin, "runtime", "handshake-thread",
                    tracker, "noneMatched=true");
            }
        } catch (SecurityException e) {
            TrackerDiagnostics.report(plugin, "runtime", "handshake-thread",
                tracker, "inspection=restricted");
        }
    }
}
