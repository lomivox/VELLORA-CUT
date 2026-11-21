// Tiny key/value store on Cloudflare D1 (binding name: DB). D1 is used (not KV) because an OAuth code written in
// one place must be readable immediately from another place; D1 is strongly consistent.

let ready = false;

async function init(env) {
  if (ready) return;
  if (!env.DB) throw new Error("D1 database is not bound (binding name must be DB)");
  await env.DB.prepare("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL, exp INTEGER)").run();
  ready = true;
}

const nowSec = () => Math.floor(Date.now() / 1000);

export async function put(env, key, value, ttlSec) {
  await init(env);
  const exp = ttlSec ? nowSec() + ttlSec : null;
  await env.DB.prepare("INSERT OR REPLACE INTO kv (k, v, exp) VALUES (?1, ?2, ?3)")
    .bind(key, JSON.stringify(value), exp)
    .run();
  if (Math.random() < 0.05) {
    await env.DB.prepare("DELETE FROM kv WHERE exp IS NOT NULL AND exp < ?1").bind(nowSec()).run();
  }
}

export async function get(env, key) {
  await init(env);
  const row = await env.DB.prepare("SELECT v, exp FROM kv WHERE k = ?1").bind(key).first();
  if (!row) return null;
  if (row.exp && row.exp < nowSec()) {
    await del(env, key);
    return null;
  }
  return JSON.parse(row.v);
}

export async function del(env, key) {
  await init(env);
  await env.DB.prepare("DELETE FROM kv WHERE k = ?1").bind(key).run();
}

/** Read and delete in one step (codes and refresh tokens work only once). */
export async function take(env, key) {
  await init(env);
  const row = await env.DB.prepare("DELETE FROM kv WHERE k = ?1 RETURNING v, exp").bind(key).first();
  if (!row) return null;
  if (row.exp && row.exp < nowSec()) return null;
  return JSON.parse(row.v);
}
