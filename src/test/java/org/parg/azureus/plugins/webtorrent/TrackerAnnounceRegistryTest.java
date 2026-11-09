package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import com.biglybt.util.JSONUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrackerAnnounceRegistryTest {
    private String payload() {
        return "{\"action\":\"announce\",\"event\":\"started\",\"info_hash\":\"example\",\"peer_id\":\"peer\",\"numwant\":4,\"offers\":[{\"offer_id\":\"one\"}]}";
    }

    @Test void reconnectRefreshesEveryOtherTorrentWithNoOffers() {
        TrackerAnnounceRegistry registry = new TrackerAnnounceRegistry();
        byte[] a = new byte[20], b = new byte[20]; b[0] = 1;
        registry.register("wss://tracker", a, payload());
        registry.register("wss://tracker", b, payload());
        List<String> replay = registry.refreshExcept("wss://tracker", a);
        assertEquals(1, replay.size());
        Map<String, Object> msg = JSONUtils.decodeJSON(replay.get(0));
        assertFalse(msg.containsKey("event"));
        assertEquals(0, ((Number) msg.get("numwant")).intValue());
        assertEquals(0, ((List<?>) msg.get("offers")).size());
    }

    @Test void stoppedTorrentIsNeverReplayed() {
        TrackerAnnounceRegistry registry = new TrackerAnnounceRegistry();
        byte[] hash = new byte[20];
        registry.register("wss://tracker", hash, payload());
        registry.remove("wss://tracker", hash);
        assertTrue(registry.refreshExcept("wss://tracker", null).isEmpty());
    }

    @Test void trackersHaveIndependentRegistrations() {
        TrackerAnnounceRegistry registry = new TrackerAnnounceRegistry();
        byte[] hash = new byte[20];
        registry.register("wss://one", hash, payload());
        assertTrue(registry.refreshExcept("wss://two", null).isEmpty());
        assertEquals(1, registry.refreshExcept("wss://one", null).size());
    }
}
