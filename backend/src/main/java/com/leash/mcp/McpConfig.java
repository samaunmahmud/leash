package com.leash.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Replaces Spring AI's default Streamable HTTP transport (same settings) only to add a context extractor: it passes
 * the agent that {@link AgentAuthFilter} resolved from the key into each tool call. By default the transport context
 * is empty by design (https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html,
 * "transport context"), so without this a tool couldn't know who is calling.
 */
@Configuration
public class McpConfig {

    public static final String AGENT_KEY = "agentId";

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties props) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(props.getMcpEndpoint())
                .keepAliveInterval(props.getKeepAliveInterval())
                .disallowDelete(props.isDisallowDelete())
                .contextExtractor(request -> request.servletRequest().getAttribute(AgentAuthFilter.AGENT_ATTR) instanceof String id
                        ? McpTransportContext.create(Map.of(AGENT_KEY, id))
                        : McpTransportContext.EMPTY)
                .build();
    }
}
