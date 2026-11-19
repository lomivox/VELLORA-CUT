// GET /yt/search?..., /yt/videos?... and /yt/channels?...  — YouTube Data API (read-only public data), with the real API key added here (YT_API_KEY secret).
import { json, checkAppKey } from "../_shared.js";

const ALLOWED_PATHS = new Set(["search", "videos", "channels"]);

export async function onRequestGet({ request, env, params }) {
  const bad = checkAppKey(request, env);
  if (bad) return bad;

  const path = Array.isArray(params.path) ? params.path.join("/") : String(params.path || "");
  if (!ALLOWED_PATHS.has(path)) return json(400, "Ye YouTube path allowed nahi");
  const ytKey = String(env.YT_API_KEY || "").trim();
  if (!ytKey) return json(500, "Server par YT_API_KEY secret set nahi hai");

  const incoming = new URL(request.url);
  const q = new URLSearchParams(incoming.search);
  q.delete("key");
  q.set("key", ytKey);

  const res = await fetch(`https://www.googleapis.com/youtube/v3/${path}?${q.toString()}`);
  return new Response(await res.text(), {
    status: res.status,
    headers: { "Content-Type": res.headers.get("Content-Type") || "application/json" },
  });
}
