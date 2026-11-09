package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WebTorrentTrackerTeardownTest {
    @Test void detachedSessionsSurviveClearingMap() {
        Map<Integer, String> sessions = new HashMap<>();
        sessions.put(1, "one");
        sessions.put(2, "two");
        List<String> snapshot = WebTorrentTracker.detachSessions(sessions);
        assertTrue(sessions.isEmpty());
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.contains("one"));
        assertTrue(snapshot.contains("two"));
    }
}
