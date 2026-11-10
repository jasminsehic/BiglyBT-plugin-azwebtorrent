package org.parg.azureus.plugins.webtorrent;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Fail closed rather than silently use an outbound JVM proxy for WSS trackers.
 *
 * Tyrus 1.21 consults Java's default ProxySelector for socket, https and http
 * before initiating a connection, even without an explicit proxy property.
 * This check does not modify the global ProxySelector or other BiglyBT traffic.
 */
final class TrackerDirectRouting {
    private TrackerDirectRouting() { }

    static void requireDirect(URI websocketUri) throws IOException {
        requireDirect(websocketUri, ProxySelector.getDefault());
    }

    static void requireDirect(URI websocketUri, ProxySelector selector) throws IOException {
        if (selector == null) return;
        for (String scheme : new String[] {"socket", "https", "http"}) {
            URI target;
            try {
                target = new URI(scheme, websocketUri.getUserInfo(),
                    websocketUri.getHost(), websocketUri.getPort(),
                    websocketUri.getPath(), websocketUri.getQuery(),
                    websocketUri.getFragment());
            } catch (URISyntaxException error) {
                throw new IOException("Invalid WebSocket tracker URI", error);
            }
            List<Proxy> selected = selector.select(target);
            if (selected == null) continue;
            for (Proxy proxy : selected) {
                if (proxy != null && proxy.type() != Proxy.Type.DIRECT) {
                    throw new IOException("Direct-only WSS connection refused: Java "
                        + "ProxySelector selected an outbound " + proxy.type()
                        + " proxy for " + scheme);
                }
            }
        }
    }
}
