// Talking to Google / YouTube on behalf of a connected channel.
import { decrypt } from "./_oauth.js";
import { get, put, del } from "./_store.js";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
export const QUOTA_MESSAGE = "YouTube daily quota used up — resets about 12–1 PM Pakistan time";

export class ToolError extends Error {}

export function describeYouTubeError(code, bodyText) {
  let reason = "";
  let message = "";
  try {
    const e = JSON.parse(bodyText).error;
    message = (e && e.message) || "";
    reason = (e && e.errors && e.errors[0] && e.errors[0].reason) || "";
  } catch (x) {
    /* not JSON */
  }
  const clean = message.replace(/<[^>]*>/g, "").replace(/\s+/g, " ").trim();
  if (/quotaExceeded|dailyLimitExceeded/i.test(reason) || /exceeded your quota/i.test(clean)) return QUOTA_MESSAGE;
  if (/rateLimitExceeded|userRateLimitExceeded/i.test(reason)) return "Too many requests — wait a minute and try again";
  if (/accessNotConfigured/i.test(reason) || /has not been used/i.test(clean)) return "YouTube Data API is not enabled for this Google Cloud project";
  if (code === 401) return "Google login expired — reconnect the connector";
  if (/insufficientPermissions/i.test(reason)) return "Permission missing — reconnect the connector";
  if (code === 404) return "Not found on YouTube";
  return clean ? `YouTube error ${code}: ${clean.slice(0, 120)}` : `YouTube error ${code}`;
}

/** A valid Google access token for this grant (cached for its lifetime). */
export async function googleAccessToken(env, grantId, grant) {
  const cached = await get(env, "gat:" + grantId);
  if (cached) return cached.token;
  const refresh = await decrypt(env, grant.refreshEnc);
  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: String(env.GOOGLE_WEB_CLIENT_ID).trim(),
      client_secret: String(env.GOOGLE_CLIENT_SECRET).trim(),
      refresh_token: refresh,
      grant_type: "refresh_token",
    }),
  });
  const text = await res.text();
  if (!res.ok) {
    if (text.includes("invalid_grant")) {
      await del(env, "gat:" + grantId);
      throw new ToolError("Google access expired — remove the connector and add it again");
    }
    throw new ToolError("Could not get a Google token (" + res.status + ")");
  }
  const j = JSON.parse(text);
  await put(env, "gat:" + grantId, { token: j.access_token }, Math.max(120, (j.expires_in || 3600) - 120));
  return j.access_token;
}

/** GET a YouTube Data API v3 endpoint for the connected channel. */
export async function yt(env, grantId, grant, path, params) {
  const token = await googleAccessToken(env, grantId, grant);
  const q = new URLSearchParams(params);
  const res = await fetch(`https://www.googleapis.com/youtube/v3/${path}?${q.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const text = await res.text();
  if (!res.ok) throw new ToolError(describeYouTubeError(res.status, text));
  return JSON.parse(text);
}
