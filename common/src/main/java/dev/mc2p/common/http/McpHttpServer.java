package dev.mc2p.common.http;

import dev.mc2p.common.activity.ClientActivityTracker;
import dev.mc2p.common.config.RestrictionsConfig;
import dev.mc2p.common.ratelimit.TokenBucketRateLimiter;
import dev.mc2p.common.tokens.TokenManager;
import jakarta.servlet.DispatcherType;
import java.nio.file.Path;
import java.util.EnumSet;
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

    private final Server server;
    private final ServletContextHandler context;

    public McpHttpServer(
            final HttpEndpointConfig http,
            final TokenManager tokens,
            final RestrictionsConfig serverRestrictions,
            final java.util.List<String> ipAllowlist,
            final TokenBucketRateLimiter.Config rateLimit,
            final Path dataDir,
            final String serverId,
            final ClientActivityTracker activity) {

        this.context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        this.context.setContextPath("/");

        final TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(rateLimit);
        final AuthFilter authFilter = new AuthFilter(tokens, serverRestrictions, ipAllowlist, rateLimiter,
                http.bodyLimitBytes(), activity);
        context.addFilter(new FilterHolder(authFilter), http.endpoint(), EnumSet.of(DispatcherType.REQUEST));

        this.server = new Server();
        this.server.addConnector(buildConnector(http, dataDir, serverId));
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
        } catch (final Exception e) {
            log.warn("error stopping HTTP server", e);
        }
    }
}
