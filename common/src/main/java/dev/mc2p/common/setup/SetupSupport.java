package dev.mc2p.common.setup;

import dev.mc2p.common.json.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared in-plugin setup helpers: the static agent-side {@code mcpServers.json}
 * template
 * and 0600 secret files for the proxy secret fallback.
 */
public final class SetupSupport {

    private SetupSupport() {
    }

    /**
     * The static MCP client config; only {@code <HOST>}, the port, and
     * {@code <TOKEN>}
     * vary. The admin replaces the placeholders with their public host and the
     * token of
     * the permissions they grant the agent.
     */
    public static String clientConfigTemplate(final int port) {
        final Map<String, Object> mcpServers = new LinkedHashMap<>();
        mcpServers.put(
                "mcpServers",
                Map.of(
                        "mc2p",
                        Map.of(
                                "type",
                                "streamable-http",
                                "url",
                                "https://<HOST>:" + port + "/mcp",
                                "headers",
                                Map.of("Authorization", "Bearer <TOKEN>"))));
        return Json.toJson(mcpServers);
    }
}
