// GET /oauth/google/callback — Google sends the person back here after sign-in and channel choice.
import { originOf, randomToken, encrypt, b64urlToBytes } from "../../_oauth.js";
import { put, take } from "../../_store.js";
import { describeYouTubeError } from "../../_google.js";

function page(status, message) {
  return new Response(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>YTIQ</title><body style="font-family:sans-serif;padding:24px"><h3>YTIQ</h3><p>${message}</p>`, {
    status,
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
}

function backToClient(redirectUri, params) {
  const u = new URL(redirectUri);
  for (const [k, v] of Object.entries(params)) if (v) u.searchParams.set(k, v);
  return Response.redirect(u.toString(), 302);
}

export async function onRequestGet({ request, env }) {
  const q = new URL(request.url).searchParams;
  const pending = await take(env, "authreq:" + (q.get("state") || ""));
  if (!pending) return page(400, "This sign-in link expired. Go back to Claude and try connecting again.");

  if (q.get("error")) {
    return backToClient(pending.redirectUri, {
      error: "access_denied",
      error_description: q.get("error"),
      state: pending.state,
    });
  }
  const fail = (msg) =>
    backToClient(pending.redirectUri, { error: "server_error", error_description: msg, state: pending.state });

  try {
    // 1. Google code -> Google tokens
    const tokenRes = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        code: q.get("code") || "",
        client_id: String(env.GOOGLE_WEB_CLIENT_ID).trim(),
        client_secret: String(env.GOOGLE_CLIENT_SECRET).trim(),
        redirect_uri: originOf(request) + "/oauth/google/callback",
        grant_type: "authorization_code",
      }),
    });
    const tokenText = await tokenRes.text();
    if (!tokenRes.ok) return fail("Google sign-in failed (" + tokenRes.status + ")");
    const tokens = JSON.parse(tokenText);
    if (!tokens.refresh_token) return fail("Google gave no long-lived access — try again");

    // 2. which channel did the person pick?
    const chRes = await fetch("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true", {
      headers: { Authorization: "Bearer " + tokens.access_token },
    });
    const chText = await chRes.text();
    if (!chRes.ok) return fail(describeYouTubeError(chRes.status, chText));
    const item = (JSON.parse(chText).items || [])[0];
    if (!item) return fail("No YouTube channel found for this login");

    let email = "";
    try {
      const payload = tokens.id_token.split(".")[1];
      email = JSON.parse(new TextDecoder().decode(b64urlToBytes(payload))).email || "";
    } catch (e) {
      /* email is only informational */
    }

    // 3. remember this connection (Google refresh token stored encrypted)
    const grantId = "g_" + randomToken(18);
    await put(env, "grant:" + grantId, {
      refreshEnc: await encrypt(env, tokens.refresh_token),
      email,
      channelId: item.id,
      channelTitle: (item.snippet && item.snippet.title) || "",
      createdAt: Date.now(),
    });

    // 4. our own one-time code for Claude
    const code = randomToken(24);
    await put(
      env,
      "code:" + code,
      { grantId, clientId: pending.clientId, redirectUri: pending.redirectUri, challenge: pending.challenge },
      300
    );
    return backToClient(pending.redirectUri, { code, state: pending.state });
  } catch (e) {
    return fail("Sign-in failed: " + String(e && e.message ? e.message : e).slice(0, 100));
  }
}
