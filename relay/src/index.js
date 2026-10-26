// Relay between the Calm Read (formerly Reddit Swipe) app and Reddit's official API.
// Holds the Reddit app secret so it never ships inside the public APK.
// Only subreddit listings can be requested: GET /r/<sub>/<hot|new|top|rising>?limit=&after=&t=
// It also serves the optional accounts (sign-in codes and sync), in account.js.

import { handleAccount, json } from "./account.js";

const USER_AGENT = "cloudflare-worker:reddit-swipe-relay:v1 (by /u/titsonaplane)";
const LISTING_PATH = /^\/r\/([A-Za-z0-9_]{2,21})\/(hot|new|top|rising)$/;
const ALLOWED_PARAMS = ["limit", "after", "t"];
const CACHE_SECONDS = 120;

// Reused across requests while this Worker instance stays warm.
let token = null;
let tokenExpiresAt = 0;

async function getToken(env) {
  if (token && Date.now() < tokenExpiresAt) return token;
  const response = await fetch("https://www.reddit.com/api/v1/access_token", {
    method: "POST",
    headers: {
      Authorization: "Basic " + btoa(`${env.REDDIT_CLIENT_ID}:${env.REDDIT_CLIENT_SECRET}`),
      "Content-Type": "application/x-www-form-urlencoded",
      "User-Agent": USER_AGENT,
    },
    body: "grant_type=client_credentials",
  });
  if (!response.ok) throw new Error(`Reddit token request failed: HTTP ${response.status}`);
  const data = await response.json();
  token = data.access_token;
  // Refresh a minute early.
  tokenExpiresAt = Date.now() + (data.expires_in - 60) * 1000;
  return token;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const account = await handleAccount(request, env, url);
    if (account) return account;

    if (request.method !== "GET") return json({ error: "method not allowed" }, 405);
    if (url.pathname === "/") return json({ ok: true }, 200);

    const match = url.pathname.match(LISTING_PATH);
    if (!match) return json({ error: "not found" }, 404);

    const upstream = new URL(`https://oauth.reddit.com/r/${match[1]}/${match[2]}`);
    upstream.searchParams.set("raw_json", "1");
    for (const name of ALLOWED_PARAMS) {
      const value = url.searchParams.get(name);
      if (value) upstream.searchParams.set(name, value.slice(0, 32));
    }

    // Identical requests within a couple of minutes are served from Cloudflare's cache.
    const cache = caches.default;
    const cacheKey = new Request(upstream.toString());
    const cached = await cache.match(cacheKey);
    if (cached) return cached;

    try {
      const accessToken = await getToken(env);
      const reddit = await fetch(upstream, {
        headers: { Authorization: `bearer ${accessToken}`, "User-Agent": USER_AGENT },
      });
      if (reddit.status === 401) token = null;
      const response = new Response(reddit.body, {
        status: reddit.status,
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": `public, max-age=${CACHE_SECONDS}`,
        },
      });
      if (reddit.ok) ctx.waitUntil(cache.put(cacheKey, response.clone()));
      return response;
    } catch (error) {
      return json({ error: error.message }, 502);
    }
  },
};
