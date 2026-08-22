package dev.mc2p.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

import dev.mc2p.common.validate.Utils;

/**
 * YAML config loading and secret-source resolution ({@code env:VAR},
 * {@code file:path},
 * plaintext).
 */
public final class ConfigSupport {
    private static final String HASH_PREFIX = "sha256:";

    /**
     * File name of the proxy secret fallback inside each plugin's data directory.
     */
    public static final String PROXY_SECRET_FILE = "proxy-secret";

    private ConfigSupport() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadYaml(final Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try (InputStream in = Files.newInputStream(file)) {
            final Object parsed = new Yaml().load(in);
            if (parsed == null) {
                return new LinkedHashMap<>();
            }
            if (!(parsed instanceof Map)) {
                throw new IOException("config root must be a mapping");
            }
            return (Map<String, Object>) parsed;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadYaml(final InputStream in) throws IOException {
        final Object parsed = new Yaml().load(in);
        if (parsed == null) {
            return new LinkedHashMap<>();
        }
        if (!(parsed instanceof Map)) {
            throw new IOException("config root must be a mapping");
        }
        return (Map<String, Object>) parsed;
    }

    public static <T> T loadYaml(final Path file, Class<T> cls, final T def) throws IOException {
        if (!Files.isRegularFile(file)) {
            return def;
        }
        try (InputStream in = Files.newInputStream(file)) {
            final Object parsed = new Yaml().load(in);
            if (parsed == null) {
                return def;
            }
            // check if parsed is an instance of cls
            if (!cls.isInstance(parsed)) {
                throw new IOException("config root must be assignable to " + cls.getName());
            }
            return cls.cast(parsed);
        }
    }

    /** Serializes a config map back to YAML text. */
    public static String dumpYaml(final Map<String, Object> config) {
        return new Yaml().dump(config);
    }

    public record Secret(String tokenId, byte[] hash, String raw) {

    }

    /**
     * Resolves a token/secret source spec.
     *
     * @param spec    {@code env:VAR}, {@code file:path} (0600), or plaintext
     * @param baseDir directory relative {@code file:} paths are resolved against
     * @return the resolved secret, or null if the source is missing
     */
    public static Secret resolveSecret(String str, final Path baseDir) {
        if (str == null || str.isBlank()) {
            return null;
        }
        str = str.trim();
        if (str.startsWith("env:")) {
            final String var = str.substring(4).trim();
            final String value = System.getenv(var);
            if (value == null || value.isBlank()) {
                return null;
            }
            return new Secret(value, Utils.sha256(value), str);
        }
        if (str.startsWith(HASH_PREFIX)) {
            try {
                final byte[] hex = HexFormat.of().parseHex(str.substring(HASH_PREFIX.length()));
                return new Secret(HexFormat.of().formatHex(hex, 0, 4), hex, str);
            } catch (final IllegalArgumentException e) {
                return null;
            }
        }
        if (str.startsWith("file:")) {
            final String value = readFile(baseDir, str.substring(5).trim());
            if (value == null) {
                return null;
            }
            return new Secret(value, Utils.sha256(value), str);
        }
        // Plaintext: allowed but warned by the caller.
        return new Secret(str, Utils.sha256(str), str);
    }

    public static String readFile(final Path dataDir, final String name) {
        return readFile(dataDir.resolve(name));
    }

    public static String readFile(final Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            final String value = Files.readString(path).trim();
            return value.isEmpty() ? null : value;
        } catch (final IOException e) {
            return null;
        }
    }

    public static boolean writeFile(final Path dataDir, final String name, final String data) throws Exception {
        try {
            Files.createDirectories(dataDir);
            final Path file = dataDir.resolve(name);
            Files.writeString(file, data);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return true;
        } catch (final UnsupportedOperationException ignored) {
            return true; // Windows: ignore permissions
        } catch (final Exception ex) {
            throw ex;
        }
    }
}
