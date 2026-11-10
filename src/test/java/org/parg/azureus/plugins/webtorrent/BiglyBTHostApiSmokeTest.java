package org.parg.azureus.plugins.webtorrent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.biglybt.pif.Plugin;

/**
 * Smoke-check class loading against the actual BiglyBT host JAR in CI.
 * This is not a substitute for loading the packaged plugin inside the running app.
 */
class BiglyBTHostApiSmokeTest {
    @Test
    void pluginCanLoadAgainstSupportedHostApi() throws Exception {
        Class<?> plugin = Class.forName("org.parg.azureus.plugins.webtorrent.WebTorrentPlugin");
        assertTrue(Plugin.class.isAssignableFrom(plugin));
        assertNotNull(plugin.getDeclaredConstructor());
        assertNotNull(plugin.getMethod("initialize", com.biglybt.pif.PluginInterface.class));
        assertNotNull(Class.forName("org.parg.azureus.plugins.webtorrent.TrackerProxy"));
    }
}
