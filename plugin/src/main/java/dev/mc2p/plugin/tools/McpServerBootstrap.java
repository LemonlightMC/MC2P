package dev.mc2p.plugin.tools;

import dev.mc2p.common.config.RestrictionsConfig;
import dev.mc2p.common.facade.ServerFacade;
import dev.mc2p.common.http.McpRequestContextExtractor;
import dev.mc2p.common.json.Json;
import dev.mc2p.common.rpc.AuthContext;
import dev.mc2p.plugin.thread.MainThread;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the MCP synchronous server (SDK 2.0) over the Streamable HTTP
 * transport: tools registered as {@link SyncToolSpecification}s with SDK-side
 * input validation, resources {@code mc2p://server} and {@code mc2p://status},
 * and resource-list-change capability so SSE notifications for player
 * join/leave are supported.
 */
public final class McpServerBootstrap {

        private McpServerBootstrap() {
        }

        public static McpSyncServer build(
                        final ToolRegistry registry,
                        final ServerFacade facade,
                        final ToolInvoker invoker,
                        final HttpServletStreamableServerTransportProvider transport,
                        final String version,
                        final MainThread mainThread) {

                final List<SyncToolSpecification> tools = new ArrayList<>();
                for (final ToolSpec spec : registry.all()) {
                        final McpSchema.Tool tool = McpSchema.Tool.builder(spec.name(), spec.inputSchema())
                                        .description(spec.description())
                                        .build();
                        tools.add(SyncToolSpecification.builder()
                                        .tool(tool)
                                        .callHandler((exchange, request) -> invoker.invoke(spec.name(),
                                                        request.arguments(), authFrom(exchange.transportContext())))
                                        .build());
                }

                final SyncResourceSpecification serverResource = new SyncResourceSpecification(
                                McpSchema.Resource.builder("mc2p://server", "MC2P server")
                                                .description("Server identity and health")
                                                .mimeType("application/json")
                                                .build(),
                                (exchange, request) -> ReadResourceResult
                                                .builder(List.of(McpSchema.TextResourceContents.builder(
                                                                "mc2p://server",
                                                                Json.toJson(
                                                                                facade.status().toMap()))
                                                                .build()))
                                                .build());

                final SyncResourceSpecification statusResource = new SyncResourceSpecification(
                                McpSchema.Resource.builder("mc2p://status", "MC2P status")
                                                .description("Live server status")
                                                .mimeType("application/json")
                                                .build(),
                                (exchange, request) -> ReadResourceResult
                                                .builder(List.of(McpSchema.TextResourceContents.builder(
                                                                "mc2p://status",
                                                                Json.toJson(
                                                                                facade.status().toMap()))
                                                                .build()))
                                                .build());

                return McpServer.sync(transport)
                                .serverInfo("mc2p-" + facade.serverId(), version)
                                .capabilities(McpSchema.ServerCapabilities.builder()
                                                .tools(true)
                                                .resources(false, true)
                                                .logging()
                                                .build())
                                .tools(tools)
                                .resources(List.of(serverResource, statusResource))
                                .build();
        }

        private static AuthContext authFrom(final io.modelcontextprotocol.common.McpTransportContext context) {
                if (context == null) {
                        return AuthContext.unauthenticated();
                }
                final Object restrictions = context.get(McpRequestContextExtractor.KEY_RESTRICTIONS);
                final Object tokenId = context.get(McpRequestContextExtractor.KEY_TOKEN_ID);
                final Object remoteIp = context.get(McpRequestContextExtractor.KEY_REMOTE_IP);
                final Object clientName = context.get(McpRequestContextExtractor.KEY_CLIENT_NAME);
                final RestrictionsConfig parsed = restrictions instanceof final RestrictionsConfig rc
                                ? rc
                                : restrictions instanceof final java.util.Map<?, ?> m
                                                ? RestrictionsConfig.load((java.util.Map<String, Object>) m)
                                                : null;
                return new AuthContext(
                                parsed,
                                clientName == null ? "" : String.valueOf(clientName),
                                tokenId == null ? "" : String.valueOf(tokenId),
                                remoteIp == null ? "" : String.valueOf(remoteIp),
                                "http");
        }

        /** Convenience: builds the transport provider with MC2P's security wiring. */
        public static HttpServletStreamableServerTransportProvider transport(final String endpoint) {
                final HttpServletStreamableServerTransportProvider.Builder builder = HttpServletStreamableServerTransportProvider
                                .builder()
                                .jsonMapper(McpJsonDefaults.getMapper())
                                .mcpEndpoint(endpoint)
                                .contextExtractor(new McpRequestContextExtractor())
                                .securityValidator(new dev.mc2p.common.http.DnsRebindingValidator())
                                .keepAliveInterval(java.time.Duration.ofSeconds(30));
                return builder.build();
        }
}
