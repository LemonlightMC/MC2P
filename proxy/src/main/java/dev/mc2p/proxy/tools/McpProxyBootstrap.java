package dev.mc2p.proxy.tools;

import com.velocitypowered.api.proxy.ProxyServer;

import dev.mc2p.common.activity.ActivityLogger;
import dev.mc2p.common.json.Json;
import dev.mc2p.proxy.rpc.BackendClient;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import java.util.List;
import java.util.Map;

/**
 * Builds the proxy MCP synchronous server (SDK 2.0) over Streamable HTTP: the
 * relayed
 * backend tools, proxy-level tools, and resources {@code mc2p://status} and
 * {@code mc2p://servers}, with resource-list-change so SSE notifications from
 * backend
 * RPC pushes are forwarded to connected agents.
 */
public final class McpProxyBootstrap {

        private McpProxyBootstrap() {
        }

        public static McpSyncServer build(
                        final BackendClient client,
                        final ProxyServer proxy,
                        final ActivityLogger audit,
                        final String proxyServerId,
                        final String version,
                        final long startedAtMillis,
                        final HttpServletStreamableServerTransportProvider transport) {

                final List<SyncToolSpecification> tools = RelayTools.build(client, audit, proxyServerId, proxy);

                final SyncResourceSpecification status = new SyncResourceSpecification(
                                McpSchema.Resource.builder("mc2p://status", "Proxy status")
                                                .description("Proxy identity, health and fleet summary")
                                                .mimeType("application/json")
                                                .build(),
                                (exchange, request) -> ReadResourceResult
                                                .builder(List.of(McpSchema.TextResourceContents.builder(
                                                                "mc2p://status",
                                                                Json.toJson(Map.of(
                                                                                "serverId",
                                                                                proxyServerId,
                                                                                "plugin",
                                                                                version,
                                                                                "backends",
                                                                                client.knownServerIds().size(),
                                                                                "uptimeSeconds",
                                                                                (System.currentTimeMillis()
                                                                                                - startedAtMillis)
                                                                                                / 1000)))
                                                                .build()))
                                                .build());

                final SyncResourceSpecification servers = new SyncResourceSpecification(
                                McpSchema.Resource.builder("mc2p://servers", "Connected backends")
                                                .description("Backend serverIds reachable over mc2p:rpc")
                                                .mimeType("application/json")
                                                .build(),
                                (exchange, request) -> ReadResourceResult
                                                .builder(List.of(McpSchema.TextResourceContents.builder(
                                                                "mc2p://servers",
                                                                Json.toJson(Map.of("servers", client.knownServerIds())))
                                                                .build()))
                                                .build());

                return McpServer.sync(transport)
                                .serverInfo("mc2p-" + proxyServerId, version)
                                .capabilities(McpSchema.ServerCapabilities.builder()
                                                .tools(true)
                                                .resources(false, true)
                                                .logging()
                                                .build())
                                .tools(tools)
                                .resources(List.of(status, servers))
                                .build();
        }

}
