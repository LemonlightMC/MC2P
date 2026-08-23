package dev.mc2p.common.http;

import dev.mc2p.common.StateHolder;
import dev.mc2p.common.config.BaseConfig.HttpEndpointConfig;
import dev.mc2p.common.config.RestrictionsConfig;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.DispatcherType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the single TLS HTTP port for the standalone topology: the MCP endpoint
 * (auth-gated) and the unauthenticated health endpoint.
 * TLS modes: {@code selfsigned} (default), {@code keystore},
 * {@code none-behind-proxy}, {@code none} (loud warning).
 */
public final class McpHttpServer {

  private static final Logger log = LoggerFactory.getLogger(McpHttpServer.class);

  private Server server;
  private ServletContextHandler context;
  private McpSyncServer mcpServer;

  public McpHttpServer(
      final StateHolder<?> holder,
      final RestrictionsConfig serverRestrictions,
      final Function<HttpServletStreamableServerTransportProvider, McpSyncServer> handlerFactory,
      final Supplier<HealthEndpoint> healthEndpointFactory) {

    this.context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    this.context.setContextPath("/");

    final AuthFilter authFilter = new AuthFilter(holder, serverRestrictions);
    context.addFilter(new FilterHolder(authFilter), holder.config().mcp().endpoint(),
        EnumSet.of(DispatcherType.REQUEST));

    HttpServletStreamableServerTransportProvider transport = transport(holder.config().mcp().endpoint());
    mcpServer = handlerFactory.apply(transport);

    context.addServlet(new ServletHolder(transport), holder.config().mcp().endpoint());
    context.addServlet(new ServletHolder(healthEndpointFactory.get()), "/healthz");

    this.server = new Server();
    this.server.addConnector(
        buildConnector(holder.config().mcp(), holder.dataDirectory(), holder.config().serverId()));
    this.server.setHandler(context);

  }

  private ServerConnector buildConnector(final HttpEndpointConfig http, final Path dataDir, final String serverId) {
    final String tlsMode = http.tlsMode();
    final String keystorePath = http.keystore();
    final String keystorePasswordEnv = http.passwordEnv();
    final boolean ssl = switch (tlsMode) {
      case "selfsigned", "keystore" -> true;
      case "none-behind-proxy" -> {
        log.warn(
            "TLS mode 'none-behind-proxy': assuming the host panel terminates TLS in front of this port");
        yield false;
      }
      default -> {
        log.warn("!!!!!!!!!! TLS DISABLED (tls.mode=none). The MCP endpoint is served in plaintext. "
            + "Use selfsigned or a host proxy in production. !!!!!!!!!!");
        yield false;
      }
    };

    if (!ssl) {
      final ServerConnector plain = new ServerConnector(server);
      plain.setHost(http.bind());
      plain.setPort(http.port());
      return plain;
    }

    final SslContextFactory.Server sslFactory = new SslContextFactory.Server();
    String password;
    if ("keystore".equals(tlsMode)) {
      Path keystore = Path.of(keystorePath);
      if (!keystore.isAbsolute()) {
        keystore = dataDir.resolve(keystore);
      }
      sslFactory.setKeyStorePath(keystore.toString());
      password = keystorePasswordEnv == null ? null : System.getenv(keystorePasswordEnv);
      if (password == null || password.isBlank()) {
        throw new IllegalStateException(
            "tls.mode=keystore requires the password env var " + keystorePasswordEnv);
      }
      sslFactory.setKeyStorePassword(password);
      sslFactory.setKeyStoreType("PKCS12");
    } else {
      Path keystore = Path.of(keystorePath);
      if (!keystore.isAbsolute()) {
        keystore = dataDir.resolve(keystore);
      }
      password = SelfSignedCert.ensureKeystore(keystore, keystorePasswordEnv, serverId);
      sslFactory.setKeyStorePath(keystore.toString());
      sslFactory.setKeyStorePassword(password);
      sslFactory.setKeyStoreType("PKCS12");
    }
    sslFactory.setIncludeProtocols("TLSv1.2", "TLSv1.3");

    final HttpConfiguration httpConfig = new HttpConfiguration();
    httpConfig.setSecureScheme("https");
    httpConfig.setSecurePort(http.port());
    final HttpConnectionFactory httpConnection = new HttpConnectionFactory(httpConfig);
    final SslConnectionFactory sslConnection = new SslConnectionFactory(sslFactory, "http/1.1");
    final ServerConnector connector = new ServerConnector(server, sslConnection, httpConnection);
    connector.setHost(http.bind());
    connector.setPort(http.port());
    return connector;
  }

  public void registerServlet(final jakarta.servlet.Servlet servlet, final String pathSpec) {
    context.addServlet(new ServletHolder(servlet), pathSpec);
  }

  public void start() {
    try {
      server.start();
      log.info(
          "MC2P MCP endpoint listening on {}:{} (TLS enabled per config)",
          mcpBind(),
          server.getURI().getPort());
    } catch (final Exception e) {
      throw new IllegalStateException("failed to start HTTP server", e);
    }
  }

  private String mcpBind() {
    final var connectors = server.getConnectors();
    return connectors.length > 0 && connectors[0] instanceof final ServerConnector sc ? sc.getHost() : "0.0.0.0";
  }

  public void stop() {
    try {
      server.stop();
      mcpServer.closeGracefully();
      server = null;
      context = null;
      mcpServer = null;
    } catch (final Exception e) {
      log.warn("error stopping HTTP server", e);
    }
  }

  /** Convenience: builds the transport provider with MC2P's security wiring. */
  public static HttpServletStreamableServerTransportProvider transport(final String endpoint) {
    return HttpServletStreamableServerTransportProvider.builder()
        .jsonMapper(McpJsonDefaults.getMapper())
        .mcpEndpoint(endpoint)
        .contextExtractor(new McpRequestContextExtractor())
        .securityValidator(new DnsRebindingValidator())
        .keepAliveInterval(Duration.ofSeconds(30))
        .build();
  }

  public McpSyncServer mcpSyncServer() {
    return mcpServer;
  }
}
