// Sends Calm Read's sign-in emails for the relay Worker (../src/account.js). It exists only because the
// sending domain and the relay are in different Cloudflare accounts. Accepts one request shape:
//   POST /  Authorization: Bearer <MAILER_SECRET>  {to, subject, text, html}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return json({ error: "method not allowed" }, 405);
    if (!env.MAILER_SECRET || request.headers.get("Authorization") !== `Bearer ${env.MAILER_SECRET}`) {
      return json({ error: "unauthorized" }, 401);
    }
    let message;
    try {
      message = await request.json();
    } catch {
      return json({ error: "expected JSON" }, 400);
    }
    const { to, subject, text, html } = message;
    if (typeof to !== "string" || typeof subject !== "string" || typeof text !== "string") {
      return json({ error: "expected {to, subject, text, html}" }, 400);
    }
    try {
      const result = await env.EMAIL.send({ from: env.SENDER, to, subject, text, html });
      return json({ ok: true, messageId: result.messageId }, 200);
    } catch (error) {
      console.error("Email sending failed:", error.code, error.message);
      return json({ error: error.message, code: error.code }, 502);
    }
  },
};

function json(body, status) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
