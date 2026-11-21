// /mcp — the MCP server (Streamable HTTP, JSON replies). Read-only YouTube tools for the connected channel.
import { CORS, json, preflight, originOf } from "./_oauth.js";
import { get } from "./_store.js";
import { TOOLS, callTool } from "./_tools.js";
import { ToolError } from "./_google.js";

const SUPPORTED = ["2025-06-18", "2025-03-26", "2024-11-05"];

export const onRequestOptions = () => preflight();

function unauthorized(request) {
  return new Response(JSON.stringify({ error: "unauthorized" }), {
    status: 401,
    headers: {
      "Content-Type": "application/json",
      "WWW-Authenticate": `Bearer resource_metadata="${originOf(request)}/.well-known/oauth-protected-resource/mcp"`,
      ...CORS,
    },
  });
}

async function authenticate(request, env) {
  const m = /^Bearer\s+(.+)$/i.exec(request.headers.get("Authorization") || "");
  if (!m) return null;
  const at = await get(env, "at:" + m[1].trim());
  if (!at) return null;
  const grant = await get(env, "grant:" + at.grantId);
  return grant ? { grantId: at.grantId, grant } : null;
}

async function handle(msg, env, auth) {
  const id = msg.id;
  const method = msg.method;
  const ok = (result) => ({ jsonrpc: "2.0", id, result });
  const err = (code, message) => ({ jsonrpc: "2.0", id, error: { code, message } });

  if (typeof method !== "string") return err(-32600, "Invalid request");
  if (method.startsWith("notifications/")) return null;

  if (method === "initialize") {
    const asked = msg.params && msg.params.protocolVersion;
    return ok({
      protocolVersion: SUPPORTED.includes(asked) ? asked : SUPPORTED[0],
      capabilities: { tools: {} },
      serverInfo: { name: "ytiq", version: "1.0.0" },
      instructions:
        "Read-only analytics for one connected YouTube channel. Start with channel_overview, then recent_videos or weak_videos.",
    });
  }
  if (method === "ping") return ok({});
  if (method === "tools/list") return ok({ tools: TOOLS });

  if (method === "tools/call") {
    const p = msg.params || {};
    try {
      const out = await callTool(env, auth.grantId, auth.grant, p.name, p.arguments);
      return ok({ content: [{ type: "text", text: JSON.stringify(out) }] });
    } catch (e) {
      const text = e instanceof ToolError ? e.message : "Tool failed: " + String(e && e.message ? e.message : e).slice(0, 120);
      return ok({ content: [{ type: "text", text }], isError: true });
    }
  }
  return err(-32601, "Method not found");
}

export async function onRequestPost({ request, env }) {
  const auth = await authenticate(request, env);
  if (!auth) return unauthorized(request);

  let body;
  try {
    body = await request.json();
  } catch (e) {
    return json(400, { jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } });
  }

  if (Array.isArray(body)) {
    const out = (await Promise.all(body.map((m) => handle(m || {}, env, auth)))).filter(Boolean);
    return out.length ? json(200, out) : new Response(null, { status: 202, headers: CORS });
  }
  const res = await handle(body || {}, env, auth);
  return res ? json(200, res) : new Response(null, { status: 202, headers: CORS });
}

const notAllowed = () => new Response("Use POST", { status: 405, headers: { Allow: "POST, OPTIONS", ...CORS } });
export const onRequestGet = notAllowed;
export const onRequestDelete = notAllowed;
