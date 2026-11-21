// Helpers shared by the OAuth endpoints and the MCP endpoint.

const encoder = new TextEncoder();

export const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "Authorization, Content-Type, MCP-Protocol-Version, Mcp-Session-Id",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Access-Control-Expose-Headers": "WWW-Authenticate",
};

export function json(status, body, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...CORS, ...extra },
  });
}

export function preflight() {
  return new Response(null, { status: 204, headers: CORS });
}

export const originOf = (request) => new URL(request.url).origin;

export function b64url(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function b64urlToBytes(str) {
  const b64 = str.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (str.length % 4)) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function randomToken(bytes = 32) {
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return b64url(a);
}

export async function sha256b64url(text) {
  return b64url(await crypto.subtle.digest("SHA-256", encoder.encode(text)));
}

// ---- encryption of the Google refresh tokens kept in the database (key = SHA-256 of the TOKEN_ENC_KEY secret)

async function aesKey(env) {
  const secret = String(env.TOKEN_ENC_KEY || "").trim();
  if (secret.length < 16) throw new Error("TOKEN_ENC_KEY secret is missing or too short");
  const raw = await crypto.subtle.digest("SHA-256", encoder.encode(secret));
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

export async function encrypt(env, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await aesKey(env), encoder.encode(text));
  return b64url(iv) + "." + b64url(ct);
}

export async function decrypt(env, packed) {
  const [iv, ct] = packed.split(".");
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: b64urlToBytes(iv) },
    await aesKey(env),
    b64urlToBytes(ct)
  );
  return new TextDecoder().decode(plain);
}

// ---- which apps may receive our login codes (Claude, ChatGPT, local testing)

const ALLOWED_HOSTS = ["claude.ai", "claude.com", "chatgpt.com", "platform.openai.com"];

export function redirectAllowed(uri) {
  try {
    const u = new URL(uri);
    if (u.protocol === "https:") {
      return ALLOWED_HOSTS.some((h) => u.hostname === h || u.hostname.endsWith("." + h));
    }
    return u.protocol === "http:" && (u.hostname === "localhost" || u.hostname === "127.0.0.1");
  } catch (e) {
    return false;
  }
}

export function protectedResourceMetadata(origin) {
  return {
    resource: origin + "/mcp",
    authorization_servers: [origin],
    bearer_methods_supported: ["header"],
    scopes_supported: ["mcp"],
    resource_name: "YTIQ",
  };
}

export function authServerMetadata(origin) {
  return {
    issuer: origin,
    authorization_endpoint: origin + "/authorize",
    token_endpoint: origin + "/token",
    registration_endpoint: origin + "/register",
    response_types_supported: ["code"],
    grant_types_supported: ["authorization_code", "refresh_token"],
    code_challenge_methods_supported: ["S256"],
    token_endpoint_auth_methods_supported: ["none"],
    scopes_supported: ["mcp"],
  };
}
