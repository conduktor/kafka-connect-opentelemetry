package io.conduktor.connect.otel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionUtilTest {

    @Test
    void testGetVersion() {
        String version = VersionUtil.getVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
        // Should be either the actual version or "unknown"
        assertTrue(version.equals("unknown") || version.matches("\\d+\\.\\d+\\.\\d+.*"));
    }

    @Test
    void testVersionConsistency() {
        String version1 = VersionUtil.getVersion();
        String version2 = VersionUtil.getVersion();
        assertEquals(version1, version2, "Version should be consistent across calls");
    }
}
