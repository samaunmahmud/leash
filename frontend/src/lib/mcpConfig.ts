/** The Claude Desktop config for one agent key, via the mcp-remote bridge (see README). */
export function claudeDesktopConfig(mcpUrl: string, key: string): string {
  const http = mcpUrl.startsWith("http://");
  const args = ["-y", "mcp-remote", mcpUrl, "--header", "Authorization:${LEASH_AGENT_KEY}", ...(http ? ["--allow-http"] : []), "--transport", "http-only"];
  return JSON.stringify({ mcpServers: { leash: { command: "npx", args, env: { LEASH_AGENT_KEY: `Bearer ${key}` } } } }, null, 2);
}
