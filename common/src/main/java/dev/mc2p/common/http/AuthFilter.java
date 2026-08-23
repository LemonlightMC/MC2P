package dev.mc2p.common.http;

import dev.mc2p.common.StateHolder;
import dev.mc2p.common.config.RestrictionsConfig;
import dev.mc2p.common.ratelimit.TokenBucketRateLimiter;
import dev.mc2p.common.tokens.TokenManager;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transport enforcement point for the MCP endpoint: IP allowlist, rate
 * limiting, body-size cap, and Bearer-token authentication (token →
 * restrictions). Runs off the main thread.
 *
 * <p>
 * On success it stamps the request with the resolved identity attributes, which
 * the {@link McpRequestContextExtractor} turns into the MCP transport context
 * that tool handlers read.
 * This is only the first layer — {@code ToolInvoker} re-checks
 * the restrictions for every call regardless of transport.
 */
public final class AuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final RestrictionsConfig serverRestrictions;
    private final List<Cidr> ipAllowlist;
    private final TokenBucketRateLimiter rateLimiter;
    private final int bodyLimitBytes;
    private final StateHolder<?> holder;

    public AuthFilter(
            final StateHolder<?> holder,
            final RestrictionsConfig serverRestrictions) {
        this.holder = holder;
        this.serverRestrictions = serverRestrictions;
        this.ipAllowlist = Cidr.parseAll(holder.config().auth().ipAllowlist());
        this.rateLimiter = new TokenBucketRateLimiter(holder.config().auth().rateLimit());
        this.bodyLimitBytes = holder.config().mcp().bodyLimitBytes();
    }

    @Override
    public void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse,
            final FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        final HttpServletResponse response = (HttpServletResponse) servletResponse;

        final String remoteIp = request.getRemoteAddr();
        InetAddress address;
        try {
            address = InetAddress.getByName(remoteIp);
        } catch (final IOException e) {
            address = null;
        }
        if (!ipAllowlist.isEmpty() && (address == null || !Cidr.anyMatch(ipAllowlist, address))) {
            log.info("mcp request from {} rejected by IP allowlist", remoteIp);
            reject(response, 403, "{\"error\":\"forbidden\"}");
            return;
        }
        if (!rateLimiter.tryAcquire(remoteIp)) {
            log.warn("mcp request from {} rate-limited", remoteIp);
            reject(response, 429, "{\"error\":\"too many requests\"}");
            return;
        }

        final long contentLength = request.getContentLengthLong();
        if (contentLength > bodyLimitBytes) {
            reject(response, 413, "{\"error\":\"payload too large\"}");
            return;
        }
        if (contentLength < 0) {
            request = new LimitedHttpServletRequest(request, bodyLimitBytes);
        }

        final String auth = request.getHeader("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            reject(response, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        final String presented = auth.substring(7).trim();
        final TokenManager.Token token = holder.tokens().authenticate(presented);
        if (token == null) {
            reject(response, 401, "{\"error\":\"unauthorized\"}");
            return;
        }

        final RestrictionsConfig effective = serverRestrictions.merge(token.restrictions());
        request.setAttribute(McpRequestContextExtractor.KEY_RESTRICTIONS, effective);
        request.setAttribute(McpRequestContextExtractor.KEY_TOKEN_ID, token.tokenId());
        request.setAttribute(McpRequestContextExtractor.KEY_REMOTE_IP, remoteIp);
        request.setAttribute(McpRequestContextExtractor.KEY_CLIENT_NAME, token.name());
        holder.activity().record(token.tokenId(), token.name(), remoteIp);

        chain.doFilter(request, response);
    }

    private static void reject(final HttpServletResponse response, final int status, final String body)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
