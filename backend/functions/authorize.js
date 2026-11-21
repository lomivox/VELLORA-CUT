// GET /authorize — Claude sends the person here; we send them on to Google to sign in and pick the channel.
import { originOf, randomToken, redirectAllowed } from "./_oauth.js";
import { get, put } from "./_store.js";

function page(status, message) {
  return new Response(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>YTIQ</title><body style="font-family:sans-serif;padding:24px"><h3>YTIQ</h3><p>${message}</p>`, {
    status,
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
}

export async function onRequestGet({ request, env }) {
  const url = new URL(request.url);
  const q = url.searchParams;
  const clientId = q.get("client_id") || "";
  const redirectUri = q.get("redirect_uri") || "";
  const state = q.get("state") || "";
  const challenge = q.get("code_challenge") || "";

  const client = clientId ? await get(env, "client:" + clientId) : null;
  if (!client) return page(400, "Unknown app. Remove the connector and add it again.");
  if (!client.redirect_uris.includes(redirectUri) || !redirectAllowed(redirectUri)) return page(400, "Redirect address is not allowed.");
  if (q.get("response_type") !== "code" || !challenge || (q.get("code_challenge_method") || "S256") !== "S256") {
    return page(400, "Invalid request (PKCE S256 is required).");
  }
  if (!String(env.GOOGLE_WEB_CLIENT_ID || "").trim() || !String(env.GOOGLE_CLIENT_SECRET || "").trim()) {
    return page(500, "The server is missing its Google login settings.");
  }

  const reqId = randomToken(24);
  await put(env, "authreq:" + reqId, { clientId, redirectUri, state, challenge }, 600);

  const google = new URL("https://accounts.google.com/o/oauth2/v2/auth");
  google.searchParams.set("client_id", String(env.GOOGLE_WEB_CLIENT_ID).trim());
  google.searchParams.set("redirect_uri", originOf(request) + "/oauth/google/callback");
  google.searchParams.set("response_type", "code");
  google.searchParams.set("scope", "openid email https://www.googleapis.com/auth/youtube.readonly");
  google.searchParams.set("access_type", "offline");
  google.searchParams.set("prompt", "consent select_account");
  google.searchParams.set("state", reqId);
  return Response.redirect(google.toString(), 302);
}
