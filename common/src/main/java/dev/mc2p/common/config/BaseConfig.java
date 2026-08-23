package dev.mc2p.common.config;

import java.util.List;

import dev.mc2p.common.ratelimit.TokenBucketRateLimiter;

public interface BaseConfig {

  public String serverId();

  public HttpEndpointConfig mcp();

  public AuthSection auth();

  public RpcSection rpc();

  public AuditSection audit();

  public RestrictionsConfig globalRestrictions();

  /**
   * The transport-shape part of the MCP HTTP endpoint, shared by the standalone
   * backend
   * plugin and the proxy plugin so the (duplicated per spec) Jetty hosting layer
   * stays
   * identical between modules.
   */
  public record HttpEndpointConfig(
      String bind,
      int port,
      String endpoint,
      int bodyLimitBytes,
      String tlsMode,
      String keystore,
      String passwordEnv) {
  }

  public record AuthSection(
      List<String> ipAllowlist, TokenBucketRateLimiter.Config rateLimit, int activityWindowMinutes) {
  }

  public record AuditSection(String file, int maxMb, int maxFiles) {
  }

  public record RpcSection(String secretEnv, String channel, long timeoutMs, int maxChunks) {
  }
}
