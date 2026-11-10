package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

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
    void acceptsHostFreeTestsWithoutLogging() {
        assertDoesNotThrow(() ->
            TrackerDiagnostics.report(null, "proxy", "connect", "wss://tracker.example", "attempt=1"));
    }
}
