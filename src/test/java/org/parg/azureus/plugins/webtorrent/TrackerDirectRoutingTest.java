package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackerDirectRoutingTest {
    private static ProxySelector selector(final Proxy selected) {
        return new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                return Collections.singletonList(selected);
            }
            @Override public void connectFailed(
                    URI uri, SocketAddress address, IOException error) { }
        };
    }

    @Test void acceptsDirectOnlySelector() throws Exception {
        assertDoesNotThrow(() -> TrackerDirectRouting.requireDirect(
            new URI("wss://tracker.example/tracker"),
            selector(Proxy.NO_PROXY)));
    }

    @Test void rejectsImplicitJvmHttpProxyWithoutAttemptingConnection() throws Exception {
        Proxy outbound = new Proxy(Proxy.Type.HTTP,
            new InetSocketAddress("127.0.0.1", 3128));
        IOException error = assertThrows(IOException.class,
            () -> TrackerDirectRouting.requireDirect(
                new URI("wss://tracker.example/tracker"), selector(outbound)));
        assertTrue(error.getMessage().contains("Direct-only"));
        assertTrue(error.getMessage().contains("HTTP"));
    }

    @Test void doesNotRequireGlobalProxySelector() throws Exception {
        TrackerDirectRouting.requireDirect(
            new URI("wss://tracker.example/tracker"), null);
    }
}
