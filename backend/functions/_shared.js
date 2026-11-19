// Shared helpers for the VELLORA backend (Cloudflare Pages Functions).
// Files starting with "_" are NOT routes; they are only imported by the real routes.

export function json(status, message, extra) {
  const body = { success: false, errors: [{ code: (extra && extra.code) || status, message }] };
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

/** The app must send the shared secret (X-App-Key) that matches the APP_KEY secret on this site. */
export function checkAppKey(request, env) {
  const sent = (request.headers.get("X-App-Key") || "").trim();
  const want = String(env.APP_KEY || "").trim();
  if (!want) return json(500, "Server par APP_KEY secret set nahi hai");
  // constant-time-ish compare
  if (sent.length !== want.length) return json(401, "App key galat hai");
  let diff = 0;
  for (let i = 0; i < want.length; i++) diff |= sent.charCodeAt(i) ^ want.charCodeAt(i);
  return diff === 0 ? null : json(401, "App key galat hai");
}

/**
 * Cloudflare accounts, read from simple variables (paste each value exactly as it is, no quotes needed):
 *   CF_ID_1 + CF_TOKEN_1, CF_ID_2 + CF_TOKEN_2, ... up to 10 pairs.
 * (The older single JSON secret CF_ACCOUNTS still works too.) Spaces around a value are removed automatically.
 */
export function readAccounts(env) {
  const out = [];
  const seen = new Set();
  const add = (id, token) => {
    id = String(id || "").trim();
    token = String(token || "").trim();
    if (id && token && !seen.has(id)) {
      seen.add(id);
      out.push({ id, token });
    }
  };
  for (let i = 1; i <= 10; i++) add(env["CF_ID_" + i], env["CF_TOKEN_" + i]);
  try {
    const list = JSON.parse(env.CF_ACCOUNTS || "[]");
    if (Array.isArray(list)) for (const a of list) if (a) add(a.id, a.token);
  } catch (e) {
    /* ignore a broken JSON secret */
  }
  return out;
}

/** How many CF_ID_n / CF_TOKEN_n are filled but have no partner (a typo in a variable name). */
export function countIncomplete(env) {
  let n = 0;
  for (let i = 1; i <= 10; i++) {
    const hasId = String(env["CF_ID_" + i] || "").trim() !== "";
    const hasToken = String(env["CF_TOKEN_" + i] || "").trim() !== "";
    if (hasId !== hasToken) n++;
  }
  return n;
}

export function shuffle(list) {
  const a = list.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}
