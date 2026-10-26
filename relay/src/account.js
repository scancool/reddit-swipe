// Optional Calm Read accounts: sign in with a 6-digit code sent by email, then keep one JSON snapshot
// (liked cards, sources, settings) per account so it syncs across phones.
//
//   POST /auth/code    {email}          emails a code (one per minute per address, valid 10 minutes)
//   POST /auth/verify  {email, code}    returns {token, email}; 5 wrong tries and the code is void
//   POST /auth/signout                  ends this session
//   GET  /sync                          returns {data, updatedAt} (data is null before the first upload)
//   PUT  /sync         {data, updatedAt} replaces the snapshot
//
// Storage (KV namespace ACCOUNTS): code:<email> and sent:<email> expire on their own; session:<sha256 of token>
// maps a session to its email; user:<email> holds the snapshot. Tokens and codes are only ever stored hashed.

const CODE_TTL_SECONDS = 600;
const RESEND_WAIT_SECONDS = 60;
const MAX_CODE_ATTEMPTS = 5;
const SESSION_TTL_SECONDS = 365 * 24 * 3600;
const MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
const EMAIL_PATTERN = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,}$/;

export async function handleAccount(request, env, url) {
  const route = `${request.method} ${url.pathname}`;
  try {
    switch (route) {
      case "POST /auth/code": return await sendCode(request, env);
      case "POST /auth/verify": return await verifyCode(request, env);
      case "POST /auth/signout": return await signOut(request, env);
      case "GET /sync": return await getSnapshot(request, env);
      case "PUT /sync": return await putSnapshot(request, env);
      default: return null;
    }
  } catch (error) {
    if (error instanceof HttpError) return json({ error: error.message }, error.status);
    console.error(route, error);
    return json({ error: "Something went wrong. Try again in a moment." }, 500);
  }
}

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

async function sendCode(request, env) {
  const email = normalizeEmail((await readJson(request)).email);
  if (await env.ACCOUNTS.get(`sent:${email}`)) {
    throw new HttpError(429, "A code was just sent. Wait a minute before asking for another.");
  }
  const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");
  await env.ACCOUNTS.put(
    `code:${email}`,
    JSON.stringify({ hash: await sha256(`${email}:${code}`), attempts: 0 }),
    { expirationTtl: CODE_TTL_SECONDS },
  );
  await env.ACCOUNTS.put(`sent:${email}`, "1", { expirationTtl: RESEND_WAIT_SECONDS });
  try {
    await env.EMAIL.send({
      from: env.SENDER,
      to: email,
      subject: `${code} is your Calm Read code`,
      text: `Your Calm Read sign-in code is ${code}\n\nIt works for 10 minutes. If you didn't ask for it, ignore this email.`,
      html: `<p>Your Calm Read sign-in code is</p><p style="font-size:28px;font-weight:bold;letter-spacing:4px">${code}</p>` +
        `<p>It works for 10 minutes. If you didn't ask for it, ignore this email.</p>`,
    });
  } catch (error) {
    console.error("Email sending failed:", error.code, error.message);
    await env.ACCOUNTS.delete(`sent:${email}`);
    throw new HttpError(502, "Couldn't send the email. Check the address and try again.");
  }
  return json({ ok: true }, 200);
}

async function verifyCode(request, env) {
  const body = await readJson(request);
  const email = normalizeEmail(body.email);
  const code = String(body.code ?? "").trim();
  const stored = await env.ACCOUNTS.get(`code:${email}`, "json");
  if (!stored) throw new HttpError(400, "That code has expired. Ask for a new one.");
  if (stored.hash !== (await sha256(`${email}:${code}`))) {
    stored.attempts += 1;
    if (stored.attempts >= MAX_CODE_ATTEMPTS) {
      await env.ACCOUNTS.delete(`code:${email}`);
      throw new HttpError(400, "Too many wrong tries. Ask for a new code.");
    }
    await env.ACCOUNTS.put(`code:${email}`, JSON.stringify(stored), { expirationTtl: CODE_TTL_SECONDS });
    throw new HttpError(400, "That code isn't right.");
  }
  await env.ACCOUNTS.delete(`code:${email}`);
  const token = hex(crypto.getRandomValues(new Uint8Array(32)));
  await env.ACCOUNTS.put(`session:${await sha256(token)}`, email, { expirationTtl: SESSION_TTL_SECONDS });
  return json({ token, email }, 200);
}

async function signOut(request, env) {
  const token = bearer(request);
  if (token) await env.ACCOUNTS.delete(`session:${await sha256(token)}`);
  return json({ ok: true }, 200);
}

async function getSnapshot(request, env) {
  const email = await requireSession(request, env);
  const stored = await env.ACCOUNTS.get(`user:${email}`, "json");
  return json(stored ?? { data: null, updatedAt: 0 }, 200);
}

async function putSnapshot(request, env) {
  const email = await requireSession(request, env);
  const text = await request.text();
  if (text.length > MAX_SNAPSHOT_BYTES) throw new HttpError(413, "Too much to sync.");
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    throw new HttpError(400, "Expected JSON.");
  }
  if (typeof body.data !== "object" || typeof body.updatedAt !== "number") throw new HttpError(400, "Expected {data, updatedAt}.");
  await env.ACCOUNTS.put(`user:${email}`, JSON.stringify({ data: body.data, updatedAt: body.updatedAt }));
  return json({ ok: true, updatedAt: body.updatedAt }, 200);
}

async function requireSession(request, env) {
  const token = bearer(request);
  const email = token && (await env.ACCOUNTS.get(`session:${await sha256(token)}`));
  if (!email) throw new HttpError(401, "Signed out. Sign in again.");
  return email;
}

function bearer(request) {
  const header = request.headers.get("Authorization") ?? "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : null;
}

function normalizeEmail(value) {
  const email = String(value ?? "").trim().toLowerCase();
  if (!EMAIL_PATTERN.test(email)) throw new HttpError(400, "That doesn't look like an email address.");
  return email;
}

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    throw new HttpError(400, "Expected JSON.");
  }
}

async function sha256(text) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))));
}

function hex(bytes) {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function json(body, status) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
