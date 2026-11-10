package org.parg.azureus.plugins.webtorrent;

import javax.websocket.Session;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Enforces a bounded, serialized write without holding the tracker-map mutex.
final class TrackerSocketWriter {
    private final Session session;
    private final long timeoutMillis;

    TrackerSocketWriter(Session session, long timeoutMillis) {
        this.session = session;
        this.timeoutMillis = timeoutMillis;
    }

    synchronized void send(String message) throws IOException {
        Future<Void> send = session.getAsyncRemote().sendText(message);
        try {
            send.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            send.cancel(true);
            try { session.close(); } catch (Exception ignored) { }
            throw new IOException("WebSocket tracker send timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { session.close(); } catch (Exception ignored) { }
            throw new IOException("WebSocket tracker send interrupted", e);
        } catch (ExecutionException e) {
            try { session.close(); } catch (Exception ignored) { }
            throw new IOException("WebSocket tracker send failed", e.getCause());
        }
    }
}
