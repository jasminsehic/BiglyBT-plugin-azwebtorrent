package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TrackerConcurrentCleanupTest {
    @Test @Timeout(10)
    void shutdownAndDisconnectReleaseBothPendingAnnounces() throws Exception {
        TrackerProxy proxy = new TrackerProxy(null, null);
        AtomicBoolean firstClosed = new AtomicBoolean(), secondClosed = new AtomicBoolean();
        Class<?> type = Class.forName(TrackerProxy.class.getName() + "$ClientSession");
        Constructor<?> create = type.getDeclaredConstructor(TrackerProxy.class, Session.class);
        create.setAccessible(true);
        Object first = create.newInstance(proxy, fakeSocket(firstClosed));
        Object second = create.newInstance(proxy, fakeSocket(secondClosed));

        Field registryField = TrackerProxy.class.getDeclaredField("client_sessions");
        registryField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String,Object> registry = (Map<String,Object>) registryField.get(proxy);
        synchronized (registry) {
            registry.put("ws://first", first);
            registry.put("ws://second", second);
        }
        Method wait = type.getDeclaredMethod("waitForAnnounce", byte[].class, long.class);
        wait.setAccessible(true);
        Method close = type.getDeclaredMethod("close");
        close.setAccessible(true);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch waiting = new CountDownLatch(2);
        try {
            Future<Object> a = pool.submit(() -> {
                waiting.countDown();
                return wait.invoke(first, new byte[20], 3000L);
            });
            Future<Object> b = pool.submit(() -> {
                waiting.countDown();
                return wait.invoke(second, new byte[20], 3000L);
            });
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            Future<?> shutdown = pool.submit(() -> { proxy.destroy(); return null; });
            Future<?> remoteClose = pool.submit(() -> { close.invoke(first); return null; });
            shutdown.get(2, TimeUnit.SECONDS);
            remoteClose.get(2, TimeUnit.SECONDS);
            assertNull(a.get(2, TimeUnit.SECONDS));
            assertNull(b.get(2, TimeUnit.SECONDS));
            assertTrue(firstClosed.get());
            assertTrue(secondClosed.get());
            synchronized (registry) { assertTrue(registry.isEmpty()); }
        } finally {
            pool.shutdownNow();
            proxy.destroy();
        }
    }

    private static Session fakeSocket(AtomicBoolean closed) {
        return (Session) Proxy.newProxyInstance(TrackerConcurrentCleanupTest.class.getClassLoader(),
            new Class<?>[]{Session.class}, (p,m,a) -> {
                if (m.getName().equals("close")) { closed.set(true); return null; }
                if (m.getName().equals("isOpen")) return !closed.get();
                return null;
            });
    }
}
