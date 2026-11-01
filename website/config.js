// Fill these in (same values as supabase.url / supabase.anonKey in the Android app's local.properties).
// The anon key is public by design; every table is locked behind RLS and only the org_* functions are callable.
window.COMPLYVISION = {
  SUPABASE_URL: "https://YOUR-PROJECT-REF.supabase.co",
  SUPABASE_ANON_KEY: "YOUR-ANON-OR-PUBLISHABLE-KEY",
};

async function cvRpc(fn, args) {
  const { SUPABASE_URL, SUPABASE_ANON_KEY } = window.COMPLYVISION;
  const headers = { "Content-Type": "application/json", apikey: SUPABASE_ANON_KEY };
  if (SUPABASE_ANON_KEY.startsWith("eyJ")) headers.Authorization = "Bearer " + SUPABASE_ANON_KEY;
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${fn}`, { method: "POST", headers, body: JSON.stringify(args) });
  if (!res.ok) throw new Error("Server error " + res.status);
  return res.json();
}
