// GET /ping — quick health check (opens in a browser). Shows only counts, never any secret.
export async function onRequestGet({ env }) {
  let accounts = 0;
  try {
    const list = JSON.parse(env.CF_ACCOUNTS || "[]");
    accounts = Array.isArray(list) ? list.filter((a) => a && a.id && a.token).length : 0;
  } catch (e) {
    accounts = -1; // CF_ACCOUNTS is not valid JSON
  }
  return new Response(
    JSON.stringify({ ok: true, cloudflareAccounts: accounts, hasAppKey: !!env.APP_KEY, hasYouTubeKey: !!env.YT_API_KEY }),
    { headers: { "Content-Type": "application/json" } }
  );
}
