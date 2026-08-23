package dev.mc2p.common.http;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns the identity attributes stamped by {@link AuthFilter} into the MCP
 * transport
 * context that tool handlers read from {@code exchange.transportContext()}.
 */
public final class McpRequestContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

    public static final String KEY_RESTRICTIONS = "mc2p.restrictions";
    public static final String KEY_TOKEN_ID = "mc2p.tokenId";
    public static final String KEY_REMOTE_IP = "mc2p.remoteIp";
    public static final String KEY_CLIENT_NAME = "mc2p.clientName";

    @Override
    public McpTransportContext extract(final HttpServletRequest request) {
        return new SimpleMcpTransportContext(request.getAttribute(KEY_RESTRICTIONS),
                request.getAttribute(KEY_TOKEN_ID), request.getAttribute(KEY_REMOTE_IP),
                request.getAttribute(KEY_CLIENT_NAME));
    }

    class SimpleMcpTransportContext implements McpTransportContext {
        private final Object restrictions;
        private final Object tokenId;
        private final Object remoteIp;
        private final Object clientName;

        SimpleMcpTransportContext(final Object restrictions, final Object tokenId, final Object remoteIp,
                final Object clientName) {
            this.restrictions = restrictions;
            this.tokenId = tokenId;
            this.remoteIp = remoteIp;
            this.clientName = clientName;
        }

        @Override
        public Object get(String key) {
            if (key == null || key.isEmpty()) {
                return null;
            }
            if (KEY_RESTRICTIONS.equals(key)) {
                return restrictions;
            } else if (KEY_TOKEN_ID.equals(key)) {
                return tokenId;
            } else if (KEY_REMOTE_IP.equals(key)) {
                return remoteIp;
            } else if (KEY_CLIENT_NAME.equals(key)) {
                return clientName;
            } else {
                return null;
            }
        }

        @Override
        public int hashCode() {
            int result = 31 + getEnclosingInstance().hashCode();
            result = 31 * result + ((restrictions == null) ? 0 : restrictions.hashCode());
            result = 31 * result + ((tokenId == null) ? 0 : tokenId.hashCode());
            result = 31 * result + ((remoteIp == null) ? 0 : remoteIp.hashCode());
            result = 31 * result + ((clientName == null) ? 0 : clientName.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            SimpleMcpTransportContext other = (SimpleMcpTransportContext) obj;
            if (!getEnclosingInstance().equals(other.getEnclosingInstance())) {
                return false;
            }
            if (restrictions == null) {
                if (other.restrictions != null) {
                    return false;
                }
            } else if (!restrictions.equals(other.restrictions)) {
                return false;
            }
            if (tokenId == null) {
                if (other.tokenId != null) {
                    return false;
                }
            } else if (!tokenId.equals(other.tokenId)) {
                return false;
            }
            if (remoteIp == null) {
                if (other.remoteIp != null) {
                    return false;
                }
            } else if (!remoteIp.equals(other.remoteIp)) {
                return false;
            }
            if (clientName == null) {
                if (other.clientName != null) {
                    return false;
                }
            } else if (!clientName.equals(other.clientName)) {
                return false;
            }
            return true;
        }

        private McpRequestContextExtractor getEnclosingInstance() {
            return McpRequestContextExtractor.this;
        }

    }
}
