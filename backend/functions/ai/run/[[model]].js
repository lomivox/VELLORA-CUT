// POST /ai/run/<model>  — forwards the app's request to Cloudflare Workers AI.
// Tries the saved accounts one after another: when an account's daily quota is used up (or its token is bad)
// the next one is used automatically. The real tokens stay in the CF_ACCOUNTS secret and never reach the phone.
import { json, checkAppKey, readAccounts, shuffle } from "../../_shared.js";

// Only these models can be used through this server (edit the list to allow more).
const ALLOWED_MODELS = new Set([
  "@cf/black-forest-labs/flux-1-schnell",
  "@cf/meta/llama-3.1-8b-instruct",
  "@cf/openai/whisper-large-v3-turbo",
  "@cf/stabilityai/stable-diffusion-xl-base-1.0",
  "@cf/bytedance/stable-diffusion-xl-lightning",
  "@cf/lykon/dreamshaper-8-lcm",
]);

const MAX_BODY_CHARS = 40_000_000; // whisper audio comes as base64 text

export async function onRequestPost({ request, env, params }) {
  const bad = checkAppKey(request, env);
  if (bad) return bad;

  const model = Array.isArray(params.model) ? params.model.join("/") : String(params.model || "");
  if (!ALLOWED_MODELS.has(model)) return json(400, "Model not allowed on this server: " + model);

  const accounts = readAccounts(env);
  if (accounts.length === 0) return json(500, "No Cloudflare accounts are configured on the server");

  const payload = await request.text();
  if (payload.length > MAX_BODY_CHARS) return json(413, "Request too large");

  let sawQuota = false;
  let sawBadToken = false;
  let lastNetworkError = "";

  for (const a of shuffle(accounts)) {
    let res;
    try {
      res = await fetch(`https://api.cloudflare.com/client/v4/accounts/${a.id}/ai/run/${model}`, {
        method: "POST",
        headers: { Authorization: `Bearer ${a.token}`, "Content-Type": "application/json" },
        body: payload,
      });
    } catch (e) {
      lastNetworkError = String(e && e.message ? e.message : e);
      continue;
    }
    const text = await res.text();
    if (res.status === 429 || text.includes('"code":4006')) {
      sawQuota = true;
      continue;
    }
    if (res.status === 401 || res.status === 403) {
      sawBadToken = true;
      continue;
    }
    return new Response(text, {
      status: res.status,
      headers: { "Content-Type": res.headers.get("Content-Type") || "application/json" },
    });
  }

  // Every account failed. 429 + code 4006 is exactly what the app already treats as "quota finished".
  if (sawQuota) return json(429, "All accounts' daily quota is used up", { code: 4006 });
  if (sawBadToken) return json(502, "The server's Cloudflare tokens are invalid");
  return json(502, "Could not reach Cloudflare: " + lastNetworkError);
}
