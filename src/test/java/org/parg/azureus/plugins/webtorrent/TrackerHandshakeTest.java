package org.parg.azureus.plugins.webtorrent;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.websocket.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TrackerHandshakeTest {
    private Session session(AtomicBoolean closed) {
        return (Session) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] { Session.class }, (p, m, a) -> {
                if (m.getName().equals("close")) { closed.set(true); return null; }
                if (m.getName().equals("isOpen")) return !closed.get();
                return null;
            });
    }

    @Test @Timeout(5)
    void timeoutCancelsConnectionAndClosesLateSocket() {
        TrackerHandshake handshake = new TrackerHandshake();
        CompletableFuture<Session> connecting = new CompletableFuture<>();
        IOException error = assertThrows(IOException.class, () -> handshake.await(connecting, 30));
        assertTrue(error.getMessage().contains("timed out"));
        assertTrue(connecting.isCancelled());
        AtomicBoolean closed = new AtomicBoolean();
        assertFalse(handshake.onOpen(session(closed)));
        assertTrue(closed.get());
    }

    @Test
    void failedHandshakeClosesAlreadyOpenedSocket() {
        TrackerHandshake handshake = new TrackerHandshake();
        AtomicBoolean closed = new AtomicBoolean();
        assertTrue(handshake.onOpen(session(closed)));
        CompletableFuture<Session> connecting = new CompletableFuture<>();
        connecting.completeExceptionally(new IOException("TLS failure"));
        assertThrows(IOException.class, () -> handshake.await(connecting, 100));
        assertTrue(closed.get());
    }

    @Test
    void handshakeUsesReadBudgetInsteadOfTenSecondCap() {
        assertEquals(25_000, TrackerHandshake.timeoutForRead(30_000));
        assertEquals(15_000, TrackerHandshake.timeoutForRead(20_000));
        assertEquals(1_000, TrackerHandshake.timeoutForRead(6_000));
        assertEquals(25_000, TrackerHandshake.timeoutForRead(Integer.MAX_VALUE));
    }

    @Test
    void failedHandshakePreservesOriginalTlsCause() {
        TrackerHandshake handshake = new TrackerHandshake();
        CompletableFuture<Session> connecting = new CompletableFuture<>();
        javax.net.ssl.SSLHandshakeException tlsFailure =
            new javax.net.ssl.SSLHandshakeException("simulated certificate failure");
        connecting.completeExceptionally(tlsFailure);
        IOException failure = assertThrows(IOException.class,
            () -> handshake.await(connecting, 1_000));
        assertSame(tlsFailure, failure.getCause());
    }

    @Test
    void successfulHandshakeKeepsSocketOpen() throws Exception {
        TrackerHandshake handshake = new TrackerHandshake();
        AtomicBoolean closed = new AtomicBoolean();
        Session s = session(closed);
        assertTrue(handshake.onOpen(s));
        assertSame(s, handshake.await(CompletableFuture.completedFuture(s), 100));
        assertFalse(closed.get());
    }
}
