// POST /register — dynamic client registration (RFC 7591). Claude / ChatGPT register themselves here.
import { json, preflight, randomToken, redirectAllowed } from "./_oauth.js";
import { put } from "./_store.js";

export const onRequestOptions = () => preflight();

export async function onRequestPost({ request, env }) {
  let body;
  try {
    body = await request.json();
  } catch (e) {
    return json(400, { error: "invalid_client_metadata", error_description: "Body must be JSON" });
  }
  const redirectUris = Array.isArray(body.redirect_uris) ? body.redirect_uris : [];
  if (redirectUris.length === 0 || redirectUris.length > 10 || !redirectUris.every(redirectAllowed)) {
    return json(400, { error: "invalid_redirect_uri", error_description: "Redirect URI not allowed" });
  }
  const clientId = "c_" + randomToken(18);
  const client = {
    client_id: clientId,
    client_name: String(body.client_name || "MCP client").slice(0, 80),
    redirect_uris: redirectUris,
    grant_types: ["authorization_code", "refresh_token"],
    response_types: ["code"],
    token_endpoint_auth_method: "none",
  };
  await put(env, "client:" + clientId, client, 365 * 24 * 3600);
  return json(201, { ...client, client_id_issued_at: Math.floor(Date.now() / 1000) });
}
