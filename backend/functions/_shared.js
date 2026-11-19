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
  const sent = request.headers.get("X-App-Key") || "";
  const want = env.APP_KEY || "";
  if (!want) return json(500, "Server par APP_KEY secret set nahi hai");
  // constant-time-ish compare
  if (sent.length !== want.length) return json(401, "App key galat hai");
  let diff = 0;
  for (let i = 0; i < want.length; i++) diff |= sent.charCodeAt(i) ^ want.charCodeAt(i);
  return diff === 0 ? null : json(401, "App key galat hai");
}

/** CF_ACCOUNTS secret: JSON like [{"id":"<account id>","token":"<api token>"}, ...] */
export function readAccounts(env) {
  try {
    const list = JSON.parse(env.CF_ACCOUNTS || "[]");
    return Array.isArray(list) ? list.filter((a) => a && a.id && a.token) : [];
  } catch (e) {
    return [];
  }
}

export function shuffle(list) {
  const a = list.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}
