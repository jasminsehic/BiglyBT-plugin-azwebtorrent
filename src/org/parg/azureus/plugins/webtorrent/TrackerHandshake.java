package org.parg.azureus.plugins.webtorrent;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.websocket.Session;

/** Bounds the connection attempt and closes sessions opened after cancellation. */
final class TrackerHandshake {
    private final AtomicBoolean abandoned = new AtomicBoolean();
    private final AtomicReference<Session> opened = new AtomicReference<>();

    // Keep the WebSocket handshake within the caller's read timeout, while
    // allowing real remote TLS handshakes longer than the old hard-coded 10 s.
    static int timeoutForRead(int readTimeoutMillis) {
        return (int) Math.min(25_000L,
            Math.max(1_000L, (long) readTimeoutMillis - 5_000L));
    }

    boolean onOpen(Session session) {
        opened.set(session);
        if (!abandoned.get()) return true;
        closeQuietly(session);
        return false;
    }

    Session await(Future<Session> connecting, long timeoutMs) throws IOException {
        try {
            return connecting.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abort(connecting);
            throw new IOException("WebSocket tracker handshake timed out", e);
        } catch (ExecutionException e) {
            abort(connecting);
            throw new IOException("WebSocket tracker handshake failed", e.getCause());
        } catch (InterruptedException e) {
            abort(connecting);
            Thread.currentThread().interrupt();
            throw new IOException("WebSocket tracker handshake interrupted", e);
        }
    }

    private void abort(Future<Session> connecting) {
        abandoned.set(true);
        connecting.cancel(true);
        Session late = opened.get();
        if (late != null) closeQuietly(late);
    }

    private static void closeQuietly(Session session) {
        try { session.close(); } catch (Exception ignored) { }
    }
}
