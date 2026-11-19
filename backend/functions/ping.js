// GET /ping — quick health check (opens in a browser). Shows only counts, never any secret.
import { readAccounts, countIncomplete } from "./_shared.js";

export async function onRequestGet({ env }) {
  return new Response(
    JSON.stringify({
      ok: true,
      cloudflareAccounts: readAccounts(env).length,
      incompletePairs: countIncomplete(env), // must be 0: an ID without its token (or the other way round)
      hasAppKey: !!String(env.APP_KEY || "").trim(),
      hasYouTubeKey: !!String(env.YT_API_KEY || "").trim(),
    }),
    { headers: { "Content-Type": "application/json" } }
  );
}
