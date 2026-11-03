import { describe, expect, it } from "vitest";
import { claudeDesktopConfig } from "./mcpConfig";

describe("claudeDesktopConfig", () => {
  it("passes the key through an env var and allows plain http only for http URLs", () => {
    const local = JSON.parse(claudeDesktopConfig("http://localhost:8080/mcp", "lk_abc"));
    expect(local.mcpServers.leash.args).toContain("--allow-http");
    expect(local.mcpServers.leash.args).toContain("Authorization:${LEASH_AGENT_KEY}");
    expect(local.mcpServers.leash.env.LEASH_AGENT_KEY).toBe("Bearer lk_abc");
    const hosted = JSON.parse(claudeDesktopConfig("https://leash.example/mcp", "lk_abc"));
    expect(hosted.mcpServers.leash.args).not.toContain("--allow-http");
  });
});
