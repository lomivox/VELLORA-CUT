// POST /token — Claude swaps the one-time code (or a refresh token) for an access token.
import { json, preflight, randomToken, sha256b64url } from "./_oauth.js";
import { get, put, take, del } from "./_store.js";

export const onRequestOptions = () => preflight();

const ACCESS_TTL = 3600;
const REFRESH_TTL = 30 * 24 * 3600;

async function issue(env, grantId, clientId) {
  const access = "at_" + randomToken(32);
  const refresh = "rt_" + randomToken(32);
  await put(env, "at:" + access, { grantId, clientId }, ACCESS_TTL);
  await put(env, "rt:" + refresh, { grantId, clientId }, REFRESH_TTL);
  return { access_token: access, token_type: "Bearer", expires_in: ACCESS_TTL, refresh_token: refresh, scope: "mcp" };
}

export async function onRequestPost({ request, env }) {
  let f;
  try {
    f = new URLSearchParams(await request.text());
  } catch (e) {
    return json(400, { error: "invalid_request" });
  }
  const grantType = f.get("grant_type");

  if (grantType === "authorization_code") {
    const rec = await take(env, "code:" + (f.get("code") || ""));
    if (!rec) return json(400, { error: "invalid_grant", error_description: "Code expired or already used" });
    if (rec.clientId !== f.get("client_id") || rec.redirectUri !== f.get("redirect_uri")) {
      return json(400, { error: "invalid_grant", error_description: "Client or redirect mismatch" });
    }
    const verifier = f.get("code_verifier") || "";
    if (!verifier || (await sha256b64url(verifier)) !== rec.challenge) {
      return json(400, { error: "invalid_grant", error_description: "PKCE check failed" });
    }
    return json(200, await issue(env, rec.grantId, rec.clientId));
  }

  if (grantType === "refresh_token") {
    const old = await take(env, "rt:" + (f.get("refresh_token") || ""));
    if (!old) return json(400, { error: "invalid_grant", error_description: "Refresh token invalid or expired" });
    if (f.get("client_id") && f.get("client_id") !== old.clientId) {
      return json(400, { error: "invalid_grant", error_description: "Client mismatch" });
    }
    if (!(await get(env, "grant:" + old.grantId))) {
      return json(400, { error: "invalid_grant", error_description: "Connection no longer exists" });
    }
    return json(200, await issue(env, old.grantId, old.clientId));
  }

  return json(400, { error: "unsupported_grant_type" });
}
