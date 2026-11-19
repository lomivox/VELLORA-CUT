# VELLORA backend (Cloudflare Pages + Functions)

Holds the secret keys (Cloudflare AI accounts, YouTube API key) so the app needs no keys in Settings.

## Cloudflare Pages setup (once)
1. Cloudflare dashboard -> Workers & Pages -> Create -> Pages -> Connect to Git -> repo `lomivox/VELLORA-CUT`.
2. Build settings: Framework preset **None**, Build command **(empty)**, Build output directory **public**,
   Root directory (advanced) **backend**.
3. After the first deploy: Settings -> Variables and Secrets -> add these as **Secret**:
   - `APP_KEY`  = same text as `WORKER_APP_KEY` in app/build.gradle.kts
   - `CF_ACCOUNTS` = `[{"id":"ACCOUNT_ID_1","token":"API_TOKEN_1"},{"id":"ACCOUNT_ID_2","token":"API_TOKEN_2"}]`
   - `YT_API_KEY` = YouTube Data API key (optional)
4. Redeploy (Deployments -> Retry), then open `https://<project>.pages.dev/ping` — it must show `"ok":true`.
5. Put `https://<project>.pages.dev` into `WORKER_URL` in app/build.gradle.kts and build the app.
