package org.parg.azureus.plugins.webtorrent;

import com.biglybt.util.JSONUtils;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Remembers active torrents independently of their outstanding WebRTC offers.
final class TrackerAnnounceRegistry {
    private final Map<String, Map<String, String>> active = new HashMap<>();
    private static String key(byte[] hash) { return Base64.getEncoder().encodeToString(hash); }

    synchronized void register(String url, byte[] hash, String payload) {
        active.computeIfAbsent(url, unused -> new LinkedHashMap<>()).put(key(hash), payload);
    }

    synchronized void remove(String url, byte[] hash) {
        Map<String, String> torrents = active.get(url);
        if (torrents == null) return;
        torrents.remove(key(hash));
        if (torrents.isEmpty()) active.remove(url);
    }

    synchronized void clear() { active.clear(); }

    synchronized List<String> refreshExcept(String url, byte[] skipHash) {
        List<String> refreshed = new ArrayList<>();
        Map<String, String> torrents = active.get(url);
        if (torrents == null) return refreshed;
        String skip = skipHash == null ? null : key(skipHash);
        for (Map.Entry<String, String> entry : torrents.entrySet()) {
            if (entry.getKey().equals(skip)) continue;
            Map<String, Object> request = JSONUtils.decodeJSON(entry.getValue());
            if (request == null) continue;
            request.remove("event");
            request.put("numwant", 0);
            request.put("offers", new ArrayList<>());
            refreshed.add(JSONUtils.encodeToJSON(request));
        }
        return refreshed;
    }
}
