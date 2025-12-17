package io.conduktor.connect.otel;

import java.io.InputStream;
import java.util.Properties;

/**
 * Utility class for retrieving version information.
 */
public class VersionUtil {
    private static final String VERSION;

    static {
        String version = "unknown";
        try (InputStream stream = VersionUtil.class.getResourceAsStream("/version.properties")) {
            if (stream != null) {
                Properties props = new Properties();
                props.load(stream);
                version = props.getProperty("version", version);
            }
        } catch (Exception e) {
            // Ignore and use default
        }

        // Fallback to manifest version if properties not found
        if ("unknown".equals(version)) {
            Package pkg = VersionUtil.class.getPackage();
            if (pkg != null && pkg.getImplementationVersion() != null) {
                version = pkg.getImplementationVersion();
            }
        }

        VERSION = version;
    }

    public static String getVersion() {
        return VERSION;
    }

    private VersionUtil() {
        // Utility class
    }
}
