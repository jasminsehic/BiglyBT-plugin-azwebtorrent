package org.parg.azureus.plugins.webtorrent;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;

/**
 * Java 8 command-line WSS diagnostic, run in a SEPARATE JVM, never in BiglyBT.
 *
 * In this isolated process the ProxySelector is set to DIRECT deliberately.
 * Do not copy that process-wide override into the BiglyBT plugin.
 */
public final class DirectWssProbe {
    private DirectWssProbe() {}

    private static final Endpoint ENDPOINT = new Endpoint() {
        @Override
        public void onOpen(Session session, EndpointConfig config) { }
    };

    public static void main(String[] args) throws Exception {
        URI uri = new URI(args.length > 0 ? args[0] :
            "wss://tracker.openwebtorrent.com");
        if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Expected wss://hostname URI");
        }
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("Host: " + uri.getHost());
        System.out.println("java.net.preferIPv4Stack=" +
            System.getProperty("java.net.preferIPv4Stack"));
        System.out.println("java.net.preferIPv6Addresses=" +
            System.getProperty("java.net.preferIPv6Addresses"));

        ProxySelector previous = ProxySelector.getDefault();
        if (previous != null) {
            for (String scheme : new String[] {"socket", "https", "http"}) {
                URI proxyUri = new URI(scheme, null, uri.getHost(), 443, null, null, null);
                try {
                    List<Proxy> options = previous.select(proxyUri);
                    StringBuilder types = new StringBuilder();
                    for (Proxy proxy : options) {
                        if (types.length() > 0) types.append(',');
                        types.append(proxy.type());
                    }
                    System.out.println("Original ProxySelector for " + scheme +
                        " (types only): " + types);
                } catch (Exception failure) {
                    System.out.println("Original ProxySelector threw " +
                        failure.getClass().getSimpleName());
                }
            }
        }

        // Force *only this standalone diagnostic process* to use direct sockets.
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI ignored) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            @Override
            public void connectFailed(URI u, SocketAddress address, IOException error) {
                System.err.println("Direct connection failed: " +
                    error.getClass().getSimpleName());
            }
        });
        System.out.println("Test routing: DIRECT ONLY (no outbound proxy)");
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        System.out.println("DNS addresses: " + addresses.length);
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        for (InetAddress address : addresses) {
            String family = address instanceof Inet6Address ? "IPv6" :
                address instanceof Inet4Address ? "IPv4" : "unknown";
            long started = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), 3500);
                System.out.println("Direct TCP " + family + ": OK " +
                    elapsed(started) + "ms");
            } catch (Exception failure) {
                System.out.println("Direct TCP " + family + ": FAILED " +
                    elapsed(started) + "ms " + failure.getClass().getSimpleName());
            }
        }

        for (boolean async : new boolean[] {false, true}) {
            ClientManager manager = ClientManager.createClient();
            manager.getProperties().put(ClientProperties.HANDSHAKE_TIMEOUT, 12000);
            ClientEndpointConfig config = ClientEndpointConfig.Builder.create().build();
            long started = System.nanoTime();
            Session session = null;
            Future<Session> pending = null;
            try {
                if (async) {
                    pending = manager.asyncConnectToServer(ENDPOINT, config, uri);
                    session = pending.get(14000, TimeUnit.MILLISECONDS);
                } else {
                    session = manager.connectToServer(ENDPOINT, config, uri);
                }
                System.out.println("Tyrus 1.21 " + (async ? "async" : "sync") +
                    " WSS: OPEN " + elapsed(started) + "ms");
            } catch (Throwable failure) {
                System.out.println("Tyrus 1.21 " + (async ? "async" : "sync") +
                    " WSS: FAILED " + elapsed(started) + "ms");
                for (Throwable cause = failure; cause != null && cause != cause.getCause();
                     cause = cause.getCause()) {
                    System.out.println("  cause: " + cause.getClass().getName() +
                        ": " + cause.getMessage());
                }
            } finally {
                if (pending != null) pending.cancel(true);
                if (session != null) {
                    try { session.close(); } catch (IOException ignored) { }
                }
            }
        }
        // Tyrus/Grizzly uses worker threads; end only this diagnostic JVM.
        System.exit(0);
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
