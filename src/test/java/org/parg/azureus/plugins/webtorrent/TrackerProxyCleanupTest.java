package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;

class TrackerProxyCleanupTest {
    private Object newSession(AtomicBoolean closed) throws Exception {
        Session socket = (Session) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Session.class}, (p, m, a) -> {
                if (m.getName().equals("close")) { closed.set(true); return null; }
                return null;
            });
        Class<?> cls = Class.forName(TrackerProxy.class.getName() + "$ClientSession");
        Constructor<?> ctor = cls.getDeclaredConstructor(TrackerProxy.class, Session.class);
        ctor.setAccessible(true);
        return ctor.newInstance(new TrackerProxy(null,null), socket);
    }

    @Test void closingSocketReleasesWaitingAnnounce() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        Object session = newSession(closed);
        Method wait = session.getClass().getDeclaredMethod("waitForAnnounce", byte[].class, long.class);
        wait.setAccessible(true);
        Method close = session.getClass().getDeclaredMethod("close");
        close.setAccessible(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> pending = pool.submit(() -> wait.invoke(session, new byte[20], 4000L));
            Thread.sleep(100);
            close.invoke(session);
            assertNull(pending.get(1, TimeUnit.SECONDS));
            assertTrue(closed.get());
        } finally { pool.shutdownNow(); }
    }

    @Test void rejectionUnblocksWaitingAnnounce() throws Exception {
        Object session = newSession(new AtomicBoolean());
        Method wait = session.getClass().getDeclaredMethod("waitForAnnounce", byte[].class, long.class);
        wait.setAccessible(true);
        Method failed = session.getClass().getDeclaredMethod("announceFailed", Map.class);
        failed.setAccessible(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> pending = pool.submit(() -> wait.invoke(session, new byte[20], 4000L));
            Thread.sleep(100);
            failed.invoke(session, Collections.singletonMap("failure reason", "offers invalid"));
            Map<?, ?> response = (Map<?, ?>) pending.get(1, TimeUnit.SECONDS);
            assertEquals("offers invalid", response.get("failure reason"));
        } finally { pool.shutdownNow(); }
    }
}
