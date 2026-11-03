package com.leash.mcp;

import com.leash.agent.AgentKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Agents authenticate with their own key ({@code Authorization: Bearer lk_...}). Runs before any MCP or controller
 * code, so an unauthenticated caller can't even list the tools. The resolved agent ID is put on the request; MCP tools
 * and the purchase endpoint use it instead of trusting an agent ID the caller sends, so one agent can never spend
 * under another's leash.
 *
 * <p>Guards: everything under {@code /mcp}, and {@code POST /api/purchases} unless {@code leash.agent-auth.rest} is
 * false (only the older REST tests turn it off; they predate keys).
 */
@Component
public class AgentAuthFilter extends OncePerRequestFilter {

    public static final String AGENT_ATTR = "leash.agentId";

    private final AgentKeys keys;
    private final boolean guardRest;

    public AgentAuthFilter(AgentKeys keys, @Value("${leash.agent-auth.rest:true}") boolean guardRest) {
        this.keys = keys;
        this.guardRest = guardRest;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (path.equals("/mcp") || path.startsWith("/mcp/")) return false;
        return !(guardRest && "POST".equals(req.getMethod()) && path.equals("/api/purchases"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> agent = bearer(req).flatMap(keys::agentFor);
        if (agent.isEmpty()) {
            res.setStatus(401);
            res.setHeader("WWW-Authenticate", "Bearer realm=\"leash\"");
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Send your agent key as "
                    + "Authorization: Bearer lk_... (get one from the leash card in the Leash dashboard).\"}");
            return;
        }
        req.setAttribute(AGENT_ATTR, agent.get());
        chain.doFilter(req, res);
    }

    static Optional<String> bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
        String key = h.substring(7).strip();
        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }
}
