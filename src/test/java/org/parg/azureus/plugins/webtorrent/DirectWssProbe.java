package org.parg.azureus.plugins.webtorrent;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;

/**
 * Run ONLY as a standalone Java 8 process, never within BiglyBT. This probe
 * overrides ProxySelector only in its own JVM, leaving BiglyBT untouched.
 * Reports address family and protocol status, never public IP addresses.
 */
public final class DirectWssProbe {
    private DirectWssProbe() {}

    private static final Endpoint ENDPOINT = new Endpoint() {
        @Override public void onOpen(Session session, EndpointConfig config) { }
    };

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static void reportFailure(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current != current.getCause() && depth++ < 8) {
            System.out.println("  cause: " + current.getClass().getName()
                + ": " + current.getMessage());
            current = current.getCause();
        }
    }

    private static void testTcpAndTls(String host, int port, InetAddress address) {
        final String family = address instanceof Inet6Address ? "IPv6" : "IPv4";
        long started = System.nanoTime();
        try (Socket direct = new Socket()) {
            direct.connect(new InetSocketAddress(address, port), 3_500);
            System.out.println("Direct TCP " + family + ": OK " + elapsed(started) + "ms");
            direct.setSoTimeout(5_000);
            // Retain the original hostname for SNI and certificate verification
            // even though the TCP destination was chosen by address family.
            try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(direct, host, port, true)) {
                SSLParameters params = tls.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(params);
                tls.setSoTimeout(5_000);
                long tlsStarted = System.nanoTime();
                tls.startHandshake();
                System.out.println("Direct TLS " + family + ": OK " + elapsed(tlsStarted)
                    + "ms protocol=" + tls.getSession().getProtocol());
            }
        } catch (Exception failure) {
            System.out.println("Direct TCP/TLS " + family + ": FAILED "
                + elapsed(started) + "ms " + failure.getClass().getSimpleName());
            reportFailure(failure);
        }
    }

    private static boolean testTyrus(URI uri, boolean async) {
        final ClientManager manager = ClientManager.createClient();
        manager.getProperties().put(ClientProperties.HANDSHAKE_TIMEOUT, 9_000);
        final ClientEndpointConfig config = ClientEndpointConfig.Builder.create().build();
        final long started = System.nanoTime();
        Session session = null;
        ExecutorService worker = null;
        Future<Session> pending = null;
        boolean success = false;
        try {
            if (async) {
                pending = manager.asyncConnectToServer(ENDPOINT, config, uri);
            } else {
                // Tyrus's synchronous API can hang during a failed TLS handshake.
                // Run it on a daemon and apply an independent deadline.
                worker = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "direct-wss-sync-probe");
                    thread.setDaemon(true);
                    return thread;
                });
                pending = worker.submit(() -> manager.connectToServer(ENDPOINT, config, uri));
            }
            session = pending.get(11_000, TimeUnit.MILLISECONDS);
            System.out.println("Tyrus 2.0.1 " + (async ? "async" : "sync")
                + " WSS: OPEN " + elapsed(started) + "ms");
            success = true;
        } catch (Throwable failure) {
            System.out.println("Tyrus 2.0.1 " + (async ? "async" : "sync")
                + " WSS: FAILED " + elapsed(started) + "ms");
            reportFailure(failure);
        } finally {
            if (pending != null && !success) pending.cancel(true);
            if (session != null) {
                try { session.close(); } catch (Exception ignored) { }
            }
            if (worker != null) worker.shutdownNow();
        }
        return success;
    }

    public static void main(String[] args) throws Exception {
        URI uri = new URI(args.length > 0 ? args[0] :
            "wss://tracker.openwebtorrent.com");
        if (!"wss".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Expected a wss:// hostname URI");
        }
        System.out.println("Java version: " + System.getProperty("java.version"));
        System.out.println("Endpoint: " + uri.getScheme() + "://" + uri.getHost());
        System.out.println("java.net.preferIPv4Stack="
            + System.getProperty("java.net.preferIPv4Stack"));
        System.out.println("java.net.preferIPv6Addresses="
            + System.getProperty("java.net.preferIPv6Addresses"));

        ProxySelector original = ProxySelector.getDefault();
        if (original != null) {
            for (String scheme : new String[] {"socket", "https", "http"}) {
                URI target = new URI(scheme, null, uri.getHost(), uri.getPort(),
                    null, null, null);
                try {
                    List<Proxy> selected = original.select(target);
                    StringBuilder types = new StringBuilder();
                    if (selected != null) {
                        for (Proxy proxy : selected) {
                            if (types.length() > 0) types.append(',');
                            types.append(proxy.type().name());
                        }
                    }
                    System.out.println("Default Java proxy route for " + scheme
                        + ": " + types);
                } catch (Exception e) {
                    System.out.println("Default ProxySelector failed for " + scheme
                        + ": " + e.getClass().getSimpleName());
                }
            }
        }

        // DIRECT ONLY, for this standalone process. No global BiglyBT change.
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI ignored) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            @Override public void connectFailed(
                URI ignored, SocketAddress address, IOException failure) {
                System.out.println("Direct route failed: "
                    + failure.getClass().getSimpleName());
            }
        });
        System.out.println("Probe route: DIRECT ONLY");
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        System.out.println("Java DNS first-address family: "
            + (addresses.length == 0 ? "none" :
                addresses[0] instanceof Inet6Address ? "IPv6" : "IPv4"));
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        boolean tested4 = false, tested6 = false;
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address && !tested4) {
                tested4 = true;
                testTcpAndTls(uri.getHost(), port, address);
            } else if (address instanceof Inet6Address && !tested6) {
                tested6 = true;
                testTcpAndTls(uri.getHost(), port, address);
            }
        }

        boolean sync = testTyrus(uri, false);
        boolean async = testTyrus(uri, true);
        System.out.println("RESULT: sync=" + (sync ? "OPEN" : "FAILED")
            + ", async=" + (async ? "OPEN" : "FAILED"));
        // Tyrus/Grizzly may leave worker threads active. End this process
        // explicitly; return failure when either mode cannot connect.
        System.exit(sync && async ? 0 : 1);
    }
}
