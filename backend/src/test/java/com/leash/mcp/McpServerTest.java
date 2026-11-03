package com.leash.mcp;

import com.leash.MutableClock;
import com.leash.agent.AgentKeys;
import com.leash.api.Http;
import com.leash.demo.DemoScenario;
import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T12 end to end with a real MCP client (the official MCP Java SDK, Streamable HTTP) against the booted app: agents
 * authenticate with their own key and can only ever spend under their own leash.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}") int port;
    @Autowired AgentKeys keys;
    @Autowired LedgerRepository ledger;
    @Autowired MandateService leashes;
    @Autowired MutableClock clock;

    final List<McpSyncClient> clients = new ArrayList<>();

    @BeforeEach
    void reset() {
        ledger.deleteAll();
        DemoLeashes.all().forEach(leashes::seed);
        clock.reset();
    }

    @AfterEach
    void close() {
        clients.forEach(McpSyncClient::closeGracefully);
    }

    McpSyncClient client(String key) {
        var req = HttpRequest.newBuilder();
        if (key != null) req.header("Authorization", "Bearer " + key);
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp").requestBuilder(req).build();
        var c = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10))
                .clientInfo(new McpSchema.Implementation("leash-test", "1.0")).build();
        clients.add(c);
        c.initialize();
        return c;
    }

    static JsonNode call(McpSyncClient c, String tool, Map<String, Object> args) {
        var res = c.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(res.isError()).as("tool error: %s", res.content()).isNotEqualTo(Boolean.TRUE);
        String text = ((McpSchema.TextContent) res.content().getFirst()).text();
        return JSON.readTree(text);
    }

    static Map<String, Object> milk(String amount) {
        return Map.of("merchant", "Larder", "category", "groceries", "description", "Milk, eggs, bread",
                "amount", amount, "task", "Buy this week's groceries");
    }

    @Test
    void listsTheThreeToolsWithPlainInstructions() {
        var c = client(keys.issue(DemoLeashes.GROCERY_AGENT).key());
        var tools = c.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("request_purchase", "check_budget", "get_mandate");
        var request = tools.stream().filter(t -> t.name().equals("request_purchase")).findFirst().orElseThrow();
        assertThat(request.description()).contains("APPROVE", "TUG", "BLOCK", "Do NOT retry a blocked purchase");
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) request.inputSchema().get("properties");
        assertThat(props).containsKeys("merchant", "amount", "pageText", "requestId").doesNotContainKey("agentId");
    }

    @Test
    void approveTugAndBlockOverMcp() {
        var c = client(keys.issue(DemoLeashes.GROCERY_AGENT).key());

        var ok = call(c, "request_purchase", milk("18.40"));
        assertThat(ok.get("outcome").asString()).isEqualTo("APPROVE");
        assertThat(ok.get("next").asString()).contains("Don't buy it again");

        var tug = call(c, "request_purchase", Map.of("merchant", "Larder", "category", "groceries",
                "description", "Big shop", "amount", "32.00", "requestId", "mcp-tug-1"));
        assertThat(tug.get("outcome").asString()).isEqualTo("TUG");
        assertThat(tug.get("next").asString()).contains("mcp-tug-1");

        var block = call(c, "request_purchase", Map.of("merchant", "Larder", "category", "gift cards",
                "description", "Larder Gift Card Bundle, 5 x £100", "amount", "500.00",
                "task", "Buy this week's groceries", "pageText", DemoScenario.POISONED_PAGE));
        assertThat(block.get("outcome").asString()).isEqualTo("BLOCK");
        assertThat(block.get("next").asString()).contains("Do not retry");
        assertThat(block.get("reasons").toString()).contains("prompt-injection");

        // Checking on the tug: same request_id, no second purchase.
        var again = call(c, "request_purchase", Map.of("merchant", "Larder", "category", "groceries",
                "description", "Big shop", "amount", "32.00", "requestId", "mcp-tug-1"));
        assertThat(again.get("tugId").asString()).isEqualTo(tug.get("tugId").asString());
        assertThat(ledger.count()).isEqualTo(3);
    }

    @Test
    void budgetAndMandateAreTheCallersOwn() {
        var c = client(keys.issue(DemoLeashes.GROCERY_AGENT).key());
        call(c, "request_purchase", milk("18.40"));
        var budget = call(c, "check_budget", Map.of());
        assertThat(budget.get("length").asString()).isEqualTo("£60.00");
        assertThat(budget.get("spent").asString()).isEqualTo("£18.40");
        assertThat(budget.get("left").asString()).isEqualTo("£41.60");

        var office = client(keys.issue(DemoLeashes.OFFICE_AGENT).key());
        var rules = call(office, "get_mandate", Map.of());
        assertThat(rules.get("leash").asString()).isEqualTo("Office supplies");
        assertThat(rules.get("neverCategories").toString()).contains("electronics");
    }

    @Test
    void anAgentCannotSpendUnderAnotherLeash() {
        var office = client(keys.issue(DemoLeashes.OFFICE_AGENT).key());
        // Even with arguments sneaking in another agent's ID, the key decides.
        var args = new java.util.HashMap<String, Object>(milk("18.40"));
        args.put("agentId", DemoLeashes.GROCERY_AGENT);
        var res = call(office, "request_purchase", args);
        assertThat(res.get("outcome").asString()).isEqualTo("BLOCK"); // groceries aren't office supplies
        assertThat(ledger.find(res.get("requestId").asString()).orElseThrow().agentId()).isEqualTo(DemoLeashes.OFFICE_AGENT);
    }

    @Test
    void noKeyOrAWrongOrRotatedKeyIsRefusedBeforeMcp() {
        var http = new Http(port);
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"x\",\"version\":\"1\"}}}";
        var none = http.post("/mcp", init);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.body()).contains("agent key");

        assertThatThrownBy(() -> client("lk_not-a-real-key")).isInstanceOf(RuntimeException.class);

        String old = keys.issue(DemoLeashes.GROCERY_AGENT).key();
        keys.issue(DemoLeashes.GROCERY_AGENT); // rotate
        assertThatThrownBy(() -> client(old)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aSnappedBackAgentIsBlockedOverMcpToo() {
        var c = client(keys.issue(DemoLeashes.GROCERY_AGENT).key());
        leashes.snap(DemoLeashes.GROCERY_AGENT);
        assertThat(call(c, "request_purchase", milk("5.00")).get("outcome").asString()).isEqualTo("BLOCK");
        assertThat(call(c, "check_budget", Map.of()).get("revoked").asBoolean()).isTrue();
    }

    @Test
    void badAmountsAreRefusedWithoutFloatingPoint() {
        assertThat(LeashTools.minor("18.40", "GBP")).isEqualTo(1_840);
        assertThat(LeashTools.minor("£0.29", "GBP")).isEqualTo(29);
        assertThat(LeashTools.minor("1,250.50", "GBP")).isEqualTo(125_050);
        assertThat(LeashTools.minor("500", "JPY")).isEqualTo(500);
        assertThatThrownBy(() -> LeashTools.minor("18.405", "GBP")).hasMessageContaining("at most 2 decimals");
        assertThatThrownBy(() -> LeashTools.minor("-5", "GBP")).hasMessageContaining("more than zero");
        assertThatThrownBy(() -> LeashTools.minor("lots", "GBP")).isInstanceOf(IllegalArgumentException.class);
    }
}
