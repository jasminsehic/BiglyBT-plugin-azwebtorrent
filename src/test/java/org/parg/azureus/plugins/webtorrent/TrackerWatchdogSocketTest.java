package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Runs the actual heartbeat scheduler against a socket that never sends a pong. */
class TrackerWatchdogSocketTest {
    @Test @Timeout(8)
    void unansweredPingClosesConnectionAndEvictsTrackedSession() throws Exception {
        TrackerProxy proxy = new TrackerProxy(null, null, 40, 80);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger pings = new AtomicInteger();
        RemoteEndpoint.Basic remote = (RemoteEndpoint.Basic) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{RemoteEndpoint.Basic.class},
            (p, m, a) -> {
                if (m.getName().equals("sendPing")) { pings.incrementAndGet(); return null; }
                return null;
            });
        Session socket = (Session) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{Session.class},
            (p, m, a) -> {
                if (m.getName().equals("getBasicRemote")) return remote;
                if (m.getName().equals("isOpen")) return !closed.get();
                if (m.getName().equals("close")) { closed.set(true); return null; }
                return null;
            });
        try {
            Class<?> type = Class.forName(TrackerProxy.class.getName() + "$ClientSession");
            Constructor<?> ctor = type.getDeclaredConstructor(TrackerProxy.class,
                Session.class, String.class);
            ctor.setAccessible(true);
            Object client = ctor.newInstance(proxy, socket, "ws://tracker.test");
            Field registry = TrackerProxy.class.getDeclaredField("client_sessions");
            registry.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String,Object> sessions = (Map<String,Object>) registry.get(proxy);
            synchronized (sessions) { sessions.put("ws://tracker.test", client); }
            Method start = type.getDeclaredMethod("startHeartbeat");
            start.setAccessible(true);
            start.invoke(client);
            long firstPingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pings.get() == 0 && System.nanoTime() < firstPingDeadline) Thread.sleep(10);
            assertTrue(pings.get() >= 1, "Watchdog must send ping frames");
            // BiglyBT SystemTime's cached clock may not advance in a mock-only host.
            // Age the unanswered ping to exercise the watchdog independently of the host ticker.
            Field pingStateField = type.getDeclaredField("ping_state");
            pingStateField.setAccessible(true);
            Object pingState = pingStateField.get(client);
            Field sentAt = TrackerPingState.class.getDeclaredField("sentAt");
            sentAt.setAccessible(true);
            synchronized (pingState) {
                sentAt.setLong(pingState, com.biglybt.core.util.SystemTime.getMonotonousTime() - 1000);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!closed.get() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(pings.get() >= 1, "Watchdog must send WebSocket ping frames");
            assertTrue(closed.get(), "Missing pong must cause socket closure");
            synchronized (sessions) {
                assertTrue(sessions.isEmpty(), "Timed-out socket must not be reused");
            }
        } finally {
            proxy.destroy();
        }
    }
}
