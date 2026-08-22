package dev.mc2p.common.tokens;

import java.nio.file.Path;

import dev.mc2p.common.config.ConfigSupport;
import dev.mc2p.common.validate.Utils;

public record ProxySecret(String value) {
  public static volatile ProxySecret instance = null;

  public static ProxySecret retrieve() {
    if (instance == null) {
      throw new IllegalStateException("Proxy Secret not yet initialized!");
    }
    return instance;
  }

  public static ProxySecret create(final String value) {
    instance = new ProxySecret(value != null && value.isBlank() ? null : value);
    return instance;
  }

  public static boolean isPresent() {
    return instance != null;
  }

  public static ProxySecret ensure(String str, Path baseDir) {
    if (ProxySecret.isPresent()) {
      return ProxySecret.retrieve();
    }
    final String generated = Utils.generateToken();
    try {
      ConfigSupport.writeFile(baseDir, ConfigSupport.PROXY_SECRET_FILE, generated);
    } catch (final Exception ex) {
      throw new IllegalStateException("Failed to persist the proxy secret", ex);
    }
    return ProxySecret.create(generated);
  }

  public static ProxySecret resolve(String str, Path baseDir) {
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
      return ProxySecret.create(value);
    }
    if (str.startsWith("file:")) {
      final String value = ConfigSupport.readFile(baseDir, str.substring(5).trim());
      if (value == null) {
        return null;
      }
      return ProxySecret.create(value);
    }
    final int idx = str.indexOf(':');
    if (idx > 0) {
      return ProxySecret.create(str.substring(0, idx));
    }
    // Plaintext: allowed but warned by the caller.
    return ProxySecret.create(str);
  }
}
