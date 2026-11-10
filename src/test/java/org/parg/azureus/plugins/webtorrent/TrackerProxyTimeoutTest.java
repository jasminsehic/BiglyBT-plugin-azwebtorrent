package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.biglybt.core.util.ByteArrayHashMap;

class TrackerProxyTimeoutTest {
    private static Object session() throws Exception {
        TrackerProxy proxy = new TrackerProxy(null, null);
        Class<?> cls = Class.forName(TrackerProxy.class.getName() + "$ClientSession");
        Constructor<?> ctor = cls.getDeclaredConstructor(TrackerProxy.class, jakarta.websocket.Session.class);
        ctor.setAccessible(true);
        return ctor.newInstance(proxy, null);
    }

    private static Map<?, ?> waitFor(Object session, byte[] hash, long timeout) throws Exception {
        Method method = session.getClass().getDeclaredMethod("waitForAnnounce", byte[].class, long.class);
        method.setAccessible(true);
        return (Map<?, ?>) method.invoke(session, hash, timeout);
    }

    @Test
    void neverReturnsAnUnacknowledgedAnnounce() throws Exception {
        assertNull(waitFor(session(), new byte[20], 20));
    }

    @Test
    void neverReturnsStaleCachedResponseAfterTimeout() throws Exception {
        Object session = session();
        byte[] hash = new byte[20];
        Map<String, Object> old = Collections.singletonMap("interval", 120);
        Method received = session.getClass().getDeclaredMethod("announceReceived", byte[].class, Map.class);
        received.setAccessible(true);
        received.invoke(session, hash, old);

        Field mapField = session.getClass().getDeclaredField("announce_map");
        mapField.setAccessible(true);
        ByteArrayHashMap<?> map = (ByteArrayHashMap<?>) mapField.get(session);
        Object state = map.get(hash);
        Field timestamp = state.getClass().getDeclaredField("last_announce_time");
        timestamp.setAccessible(true);
        timestamp.setLong(state, -100_000); // The JVM may have been running for less than 10 seconds.
        assertNull(waitFor(session, hash, 20));
    }
}
