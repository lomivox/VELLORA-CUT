# Claude connector (MCP) — setup

Endpoint: `https://ytiq.pages.dev/mcp` (add it in Claude: Settings -> Connectors -> Add custom connector).

One-time setup on Cloudflare / Google:
1. Cloudflare -> Storage & databases -> D1 -> create database `ytiq-db`.
   Pages project `ytiq` -> Settings -> Bindings -> Add -> D1 database -> name `DB` -> `ytiq-db`.
2. Google Cloud (same project as the YouTube API) -> Credentials -> Create OAuth client -> **Web application**.
   Authorized redirect URI: `https://ytiq.pages.dev/oauth/google/callback`
3. Pages secrets: `GOOGLE_WEB_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `TOKEN_ENC_KEY` (any long random text).
4. Redeploy, then open `https://ytiq.pages.dev/mcp` — it must answer 401 (that is correct: it wants a login).

Tools (read-only): channel_overview, recent_videos, weak_videos, video_details.
