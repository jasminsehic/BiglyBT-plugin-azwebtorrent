package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import com.biglybt.pif.logging.LoggerChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Ensure diagnostic endpoints never expose credentials or tracker query parameters. */
class TrackerDiagnosticsTest {
    @Test
    void stripsCredentialsAndQueryFromTrackerUrl() {
        assertEquals("wss://tracker.example:443", TrackerDiagnostics.endpoint(
            "wss://username:secret@tracker.example:443/ws?token=secret&peer_id=test"));
        assertEquals("unknown", TrackerDiagnostics.endpoint(null));
        assertEquals("invalid", TrackerDiagnostics.endpoint("not a URI"));
    }

    @Test
    void usesActualPluginLoggingChannelWithoutExposingTrackerSecrets() throws Exception {
        WebTorrentPlugin plugin = new WebTorrentPlugin();
        Field logField = WebTorrentPlugin.class.getDeclaredField("log");
        logField.setAccessible(true);
        List<String> captured = new ArrayList<>();
        LoggerChannel channel = (LoggerChannel) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{LoggerChannel.class},
            (proxy, method, args) -> {
                if (method.getName().equals("log") && args != null
                    && args.length >= 1 && args[0] instanceof String) {
                    captured.add((String) args[0]);
                }
                return null;
            });
        logField.set(plugin, channel);
        TrackerDiagnostics.report(plugin, "proxy", "heartbeat-pong-timeout",
            "wss://user:password@tracker.example/ws?token=secret",
            "timeoutMs=30000");
        assertEquals(1, captured.size());
        assertTrue(captured.get(0).contains("[soak][proxy] heartbeat-pong-timeout"));
        assertTrue(captured.get(0).contains("timeoutMs=30000"));
        assertFalse(captured.get(0).contains("password"));
        assertFalse(captured.get(0).contains("token"));
    }

    @Test
    void acceptsHostFreeTestsWithoutLogging() {
        assertDoesNotThrow(() ->
            TrackerDiagnostics.report(null, "proxy", "connect", "wss://tracker.example", "attempt=1"));
    }
}
