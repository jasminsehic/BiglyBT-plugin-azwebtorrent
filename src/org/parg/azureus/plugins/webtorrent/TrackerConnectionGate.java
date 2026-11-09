package org.parg.azureus.plugins.webtorrent;

import java.util.HashMap;
import java.util.Map;

// Keeps unrelated tracker URLs independent during connection establishment.
final class TrackerConnectionGate {
    private final Map<String, Object> locks = new HashMap<>();

    synchronized Object lockFor(String url) {
        return locks.computeIfAbsent(url, key -> new Object());
    }
}
