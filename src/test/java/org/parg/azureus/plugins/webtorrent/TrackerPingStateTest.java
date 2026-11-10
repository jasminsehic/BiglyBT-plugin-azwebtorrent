package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TrackerPingStateTest {
    @Test void noPongExpiresOutstandingPing() {
        TrackerPingState state = new TrackerPingState();
        assertTrue(state.startPing(100));
        assertFalse(state.startPing(200));
        assertFalse(state.isExpired(299, 200));
        assertTrue(state.isExpired(300, 200));
    }
    @Test void pongClearsTimeout() {
        TrackerPingState state = new TrackerPingState();
        assertTrue(state.startPing(100));
        state.pong();
        assertFalse(state.isExpired(1000, 200));
        assertTrue(state.startPing(1000));
    }
}
