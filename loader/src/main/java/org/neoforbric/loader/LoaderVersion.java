package org.neoforbric.loader;

import java.io.IOException;
import java.util.Properties;

/** Build version shared by the UI and both dependency resolvers. */
public final class LoaderVersion {
    public static final String VERSION = read();
    private LoaderVersion() {}

    private static String read() {
        try (var input = LoaderVersion.class.getResourceAsStream("version.properties")) {
            if (input == null) throw new IllegalStateException("Missing NeoForbric build version");
            var properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank()) throw new IllegalStateException("Empty NeoForbric build version");
            Version.parse(version);
            return version;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read NeoForbric build version", error);
        }
    }
}
