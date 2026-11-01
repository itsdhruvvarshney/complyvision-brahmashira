// Supabase Edge Function: org-forgot-password
//
// The app (or the website) POSTs { org_code, email }. If they match an active organisation, a one-time
// reset link is emailed from the ComplyVision Support Team. The response is ALWAYS the same generic
// { ok: true } so nobody can probe which Organisation IDs / emails exist.
//
// Secrets (supabase secrets set ...):
//   RESEND_API_KEY   API key of the email provider (Resend, https://resend.com)
//   MAIL_FROM        e.g. "ComplyVision Support Team <support@your-verified-domain>"
//   RESET_BASE_URL   the website's reset page, e.g. https://your-site/reset-password.html
// SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are provided automatically by Supabase.
//
// Deploy:  supabase functions deploy org-forgot-password

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};
const generic = { ok: true, message: "If the details match, a reset link has been sent to the registered email." };
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]!));

function emailHtml(person: string, orgName: string, orgCode: string, link: string, minutes: number) {
  return `<!doctype html><html><body style="margin:0;background:#eef5fd;font-family:Segoe UI,Arial,sans-serif;color:#071d36">
  <table width="100%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:28px 12px">
   <table width="520" cellpadding="0" cellspacing="0" style="max-width:520px;background:#ffffff;border-radius:18px;overflow:hidden;box-shadow:0 6px 24px rgba(30,95,176,.15)">
    <tr><td style="background:linear-gradient(90deg,#2f86ff,#1648d8);padding:22px 28px;color:#fff;font-size:22px;font-weight:800">Comply<span style="color:#bfe3ff">Vision</span></td></tr>
    <tr><td style="padding:28px">
      <p style="margin:0 0 12px;font-size:16px">Hello ${escapeHtml(person)},</p>
      <p style="margin:0 0 16px;font-size:15px;line-height:1.55">We received a request to reset the password of <b>${escapeHtml(orgName)}</b>
      (Organisation ID <b>${escapeHtml(orgCode)}</b>) on the ComplyVision Organisation Portal.</p>
      <p style="margin:24px 0"><a href="${link}" style="background:#1463f3;color:#fff;text-decoration:none;font-weight:700;padding:14px 26px;border-radius:30px;display:inline-block">Reset my password</a></p>
      <p style="margin:0 0 8px;font-size:13px;color:#566a85;line-height:1.5">This one-time link is valid for ${minutes} minutes and can be used only once.
      If the button does not work, copy this address into your browser:<br><span style="word-break:break-all">${link}</span></p>
      <p style="margin:18px 0 0;font-size:13px;color:#566a85;line-height:1.5">Did not ask for this? You can ignore this email; your password stays unchanged.</p>
    </td></tr>
    <tr><td style="background:#f3f8fe;padding:16px 28px;font-size:12px;color:#566a85">ComplyVision Support Team<br>Please do not share this link with anyone.</td></tr>
   </table></td></tr></table></body></html>`;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ ok: false, message: "Method not allowed" }, 405);

  let body: { org_code?: string; email?: string };
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, message: "Invalid request" }, 400);
  }
  const orgCode = String(body.org_code ?? "").trim();
  const email = String(body.email ?? "").trim();
  if (!orgCode || !email || orgCode.length > 30 || email.length > 120) return json({ ok: false, message: "Enter your Organisation ID and email." }, 400);

  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data, error } = await supabase.rpc("org_create_reset", { p_org_code: orgCode, p_email: email });
  if (error) {
    console.error("org_create_reset failed", error.message);
    return json({ ok: false, message: "Something went wrong. Please try again." }, 500);
  }
  if (!data?.ok) return json(generic); // no match or rate limited: same answer

  const apiKey = Deno.env.get("RESEND_API_KEY");
  const from = Deno.env.get("MAIL_FROM") ?? "ComplyVision Support Team <support@complyvision.in>";
  const base = Deno.env.get("RESET_BASE_URL");
  if (!apiKey || !base) {
    console.error("RESEND_API_KEY / RESET_BASE_URL are not configured");
    return json({ ok: false, message: "Email service is not configured yet. Please contact support." }, 503);
  }

  const link = `${base}${base.includes("?") ? "&" : "?"}token=${data.token}`;
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      from,
      to: [data.email],
      subject: "Reset your ComplyVision Organisation Portal password",
      html: emailHtml(data.authorised_person, data.name, data.org_code, link, data.expires_minutes),
      text: `Hello ${data.authorised_person},\n\nReset the password for ${data.name} (${data.org_code}) using this one-time link, valid for ${data.expires_minutes} minutes:\n${link}\n\nIf you did not ask for this, ignore this email.\n\nComplyVision Support Team`,
    }),
  });
  if (!res.ok) {
    console.error("Email provider rejected the message", res.status, await res.text());
    return json({ ok: false, message: "The email could not be sent. Please try again later." }, 502);
  }
  return json(generic);
});
