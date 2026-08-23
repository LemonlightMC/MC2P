package dev.mc2p.plugin.config;

import dev.mc2p.common.config.BaseConfig;
import dev.mc2p.common.config.RestrictionsConfig;
import dev.mc2p.common.ratelimit.TokenBucketRateLimiter;
import dev.mc2p.common.validate.Args;

import java.util.Map;

/**
 * Typed backend plugin configuration. Two file layouts share this parser:
 *
 * <ul>
 * <li>{@code config.yml} (standalone) — no {@code mode}; restrictions under
 * {@code global-restrictions}.</li>
 * <li>{@code backend.yml} (backend-only) — {@code mode} and {@code serverId};
 * restrictions under {@code server-restrictions}.</li>
 * </ul>
 *
 * Only one of the two files exists on disk at a time; switching modes swaps
 * them and
 * migrates the restrictions block into the other file.
 */
public record BackendConfig(
                String mode,
                String serverId,
                HttpEndpointConfig mcp,
                RpcSection rpc,
                AuthSection auth,
                LimitsSection limits,
                String restartStrategy,
                AuditSection audit,
                RestrictionsConfig globalRestrictions,
                RestrictionsConfig serverRestrictions) implements BaseConfig {

        public record LimitsSection(
                        int maxConcurrentRequests,
                        int maxCoordinate,
                        int maxRegionBlocks,
                        int maxEntityLimit,
                        int maxCommandLength) {
        }

        public static final String DEFAULT_MODE = "auto";

        public static BackendConfig defaults() {
                return load(Map.of());
        }

        public static BackendConfig load(final Map<String, Object> yaml) {
                final String mode = Args.string(yaml, "mode", DEFAULT_MODE);
                final String serverId = Args.string(yaml, "serverId", "main");

                final Map<String, Object> mcp = Args.map(yaml.get("mcp"));
                final Map<String, Object> tls = Args.map(mcp.get("tls"));
                final HttpEndpointConfig mcpSection = new HttpEndpointConfig(
                                Args.string(mcp, "bind", "0.0.0.0"),
                                Args.integer(mcp, "port", 8443),
                                Args.string(mcp, "endpoint", "/mcp"),
                                Args.integer(mcp, "body-limit-bytes", 65536),
                                Args.string(tls, "mode", "selfsigned"),
                                Args.string(tls, "keystore", "keystore.p12"),
                                Args.string(tls, "password-env", "MC2P_KEYSTORE_PW"));

                final Map<String, Object> proxy = Args.map(yaml.get("proxy"));
                final RpcSection proxySection = new RpcSection(
                                Args.string(proxy, "secret", "MC2P_PROXY_SECRET"),
                                Args.string(proxy, "rpc-channel", "mc2p:rpc"),
                                Args.integer(proxy, "timeout-ms", 5000), 0);

                final Map<String, Object> auth = Args.map(yaml.get("auth"));
                final Map<String, Object> rate = Args.map(auth.get("rate-limit"));
                final AuthSection authSection = new AuthSection(
                                Args.strings(auth, "ip-allowlist"),
                                new TokenBucketRateLimiter.Config(
                                                (double) Args.integer(rate, "tokens-per-second", 5),
                                                Args.integer(rate, "burst", 20)),
                                Args.integer(auth, "activity-window-minutes", 5));

                final Map<String, Object> limits = Args.map(yaml.get("limits"));
                final LimitsSection limitsSection = new LimitsSection(
                                Args.integer(limits, "max-concurrent-requests", 12),
                                Args.integer(limits, "max-coordinate", 30000000),
                                Args.integer(limits, "max-region-blocks", 32768),
                                Args.integer(limits, "max-entity-limit", 128),
                                Args.integer(limits, "max-command-length", -1));

                final Map<String, Object> restart = Args.map(yaml.get("restart"));
                final String restartStrategy = Args.string(restart, "strategy", "auto");

                final Map<String, Object> audit = Args.map(yaml.get("audit"));
                final AuditSection auditSection = new AuditSection(
                                Args.string(audit, "file", "logs/mcp-audit.log"),
                                Args.integer(audit, "max-mb", 50),
                                Args.integer(audit, "max-files", 5));

                final RestrictionsConfig global = RestrictionsConfig
                                .load(Args.map(yaml.get("global-restrictions")));
                final RestrictionsConfig server = RestrictionsConfig
                                .load(Args.map(yaml.get("server-restrictions")));

                return new BackendConfig(
                                mode,
                                serverId,
                                mcpSection,
                                proxySection,
                                authSection,
                                limitsSection,
                                restartStrategy,
                                auditSection,
                                global,
                                server);
        }

        /** The restrictions that apply to requests handled directly by this server. */
        public RestrictionsConfig effectiveRestrictions() {
                return globalRestrictions.merge(serverRestrictions);
        }
}
