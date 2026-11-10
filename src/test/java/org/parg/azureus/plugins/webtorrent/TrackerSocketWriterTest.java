package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import org.junit.jupiter.api.Test;

class TrackerSocketWriterTest {
    private Session fakeSession(CompletableFuture<Void> result, AtomicBoolean closed, AtomicInteger sends) {
        RemoteEndpoint.Async remote = (RemoteEndpoint.Async) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ RemoteEndpoint.Async.class },
            (proxy, method, args) -> {
                if (method.getName().equals("sendText")) {
                    sends.incrementAndGet();
                    return result;
                }
                return null;
            });
        return (Session) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ Session.class },
            (proxy, method, args) -> {
                if (method.getName().equals("getAsyncRemote")) return remote;
                if (method.getName().equals("close")) { closed.set(true); return null; }
                if (method.getName().equals("isOpen")) return !closed.get();
                return null;
            });
    }

    @Test void timedOutWriteClosesSocket() {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger sends = new AtomicInteger();
        TrackerSocketWriter writer = new TrackerSocketWriter(
            fakeSession(new CompletableFuture<>(), closed, sends), 20);
        assertThrows(IOException.class, () -> writer.send("announce"));
        assertTrue(closed.get());
        assertEquals(1, sends.get());
    }

    @Test void completedWriteKeepsSocketOpen() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger sends = new AtomicInteger();
        TrackerSocketWriter writer = new TrackerSocketWriter(
            fakeSession(CompletableFuture.completedFuture(null), closed, sends), 1000);
        writer.send("announce");
        assertFalse(closed.get());
        assertEquals(1, sends.get());
    }

    @Test void sameUrlUsesSameConnectionLock() {
        TrackerConnectionGate gate = new TrackerConnectionGate();
        assertSame(gate.lockFor("wss://example"), gate.lockFor("wss://example"));
        assertNotSame(gate.lockFor("wss://example"), gate.lockFor("wss://another"));
    }
}
