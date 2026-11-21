// Serves the OAuth discovery documents under /.well-known/ (a folder name starting with a dot is not
// reliable as a Pages Functions route), and lets every other request pass through untouched.
import { json, preflight, originOf, protectedResourceMetadata, authServerMetadata } from "./_oauth.js";

export async function onRequest(context) {
  const { request } = context;
  const path = new URL(request.url).pathname;
  if (!path.startsWith("/.well-known/")) return context.next();
  if (request.method === "OPTIONS") return preflight();
  const origin = originOf(request);
  if (path.startsWith("/.well-known/oauth-protected-resource")) return json(200, protectedResourceMetadata(origin));
  if (path.startsWith("/.well-known/oauth-authorization-server") || path.startsWith("/.well-known/openid-configuration")) {
    return json(200, authServerMetadata(origin));
  }
  return context.next();
}
