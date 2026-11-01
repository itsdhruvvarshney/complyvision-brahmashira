-- ComplyVision: Organisation portal (Organisation Pre-Verification: BIS and Pre-FSSAI checks)
-- Run once in the Supabase SQL editor AFTER schema.sql, inspector_module.sql and rule_engine.sql.
-- Safe to re-run (idempotent).
--
-- Who writes here
--   * The ComplyVision WEBSITE registers organisations (org_register, anon key). The mobile app has no
--     registration: organisations can only log in. A registered organisation can log in at once.
--   * The app calls org_login / org_overview / org_scan / org_save_scan with the session token.
--   * Forgot password: the org-forgot-password Edge Function (supabase/functions) calls org_create_reset with
--     the service role, emails a one-time link from the ComplyVision Support Team, and the website's
--     reset-password page calls org_reset_password with the token.
--
-- Security model (same as the officer and consumer modules)
--   * RLS on with NO policies and no table grants: only the SECURITY DEFINER functions below are reachable.
--   * Passwords are bcrypt hashes. 5 wrong logins lock the organisation for 15 minutes.
--   * Reset tokens are random, stored only as SHA-256 hashes, valid for 30 minutes and usable once.
--   * Demo organisations (section 9) share a demo password; remove them before going live.

create extension if not exists pgcrypto with schema extensions;

-- ===========================================================================
-- 1. Tables
-- ===========================================================================
create sequence if not exists public.org_code_seq start 10011;

create table if not exists public.org_accounts (
  id                uuid primary key default gen_random_uuid(),
  org_code          text not null unique check (org_code ~ '^ORG-[0-9]{2}-[0-9]{5,}$'),
  name              text not null check (char_length(name) between 3 and 120),
  business_type     text not null check (business_type in ('Manufacturer', 'Packer', 'Importer', 'Retailer', 'Distributor', 'Exporter')),
  industry          text,
  gstin             text check (gstin is null or gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
  pan               text check (pan is null or pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
  fssai_licence     text check (fssai_licence is null or fssai_licence ~ '^[0-9]{14}$'),
  bis_licence       text,
  address           text,
  city              text,
  state             text,
  pincode           text check (pincode is null or pincode ~ '^[0-9]{6}$'),
  authorised_person text not null check (char_length(authorised_person) between 3 and 80),
  designation       text,
  email             text not null check (char_length(email) <= 120),
  phone             text,
  password_hash     text not null,
  status            text not null default 'active' check (status in ('active', 'pending', 'suspended')),
  registered_via    text not null default 'website' check (registered_via in ('website', 'app', 'seed')),
  failed_attempts   integer not null default 0,
  locked_until      timestamptz,
  last_login_at     timestamptz,
  created_at        timestamptz not null default now()
);
create unique index if not exists org_accounts_email_uidx on public.org_accounts (lower(email));
create unique index if not exists org_accounts_gstin_uidx on public.org_accounts (gstin) where gstin is not null;

create table if not exists public.org_sessions (
  token      uuid primary key default gen_random_uuid(),
  org_id     uuid not null references public.org_accounts (id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '12 hours'),
  revoked    boolean not null default false
);
create index if not exists org_sessions_org_idx on public.org_sessions (org_id);

create table if not exists public.org_password_resets (
  id         bigint generated always as identity primary key,
  org_id     uuid not null references public.org_accounts (id) on delete cascade,
  token_hash text not null unique,
  expires_at timestamptz not null default (now() + interval '30 minutes'),
  used_at    timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists org_password_resets_org_idx on public.org_password_resets (org_id, created_at desc);

create sequence if not exists public.org_scan_seq start 100000;

create table if not exists public.org_verifications (
  id               uuid primary key default gen_random_uuid(),
  scan_code        text not null unique,
  org_id           uuid not null references public.org_accounts (id) on delete cascade,
  authority        text not null check (authority in ('BIS', 'FSSAI')),
  product_name     text,
  brand            text,
  verdict          text not null check (verdict in ('Passed', 'Failed', 'Under Review')),
  rules_passed     integer not null default 0,
  rules_failed     integer not null default 0,
  rules_review     integer not null default 0,
  rules_na         integer not null default 0,
  score            numeric(5, 1) not null default 0,
  quality_grade    text,
  source           text not null default 'Camera' check (source in ('Camera', 'Gallery')),
  analyzer_version text,
  duration_ms      integer,
  category_id      text,
  category_name    text,
  package_type     text,
  report           jsonb not null default '{}'::jsonb,
  created_at       timestamptz not null default now()
);
create index if not exists org_verifications_org_idx on public.org_verifications (org_id, created_at desc);

alter table public.org_accounts         enable row level security;
alter table public.org_sessions         enable row level security;
alter table public.org_password_resets  enable row level security;
alter table public.org_verifications    enable row level security;
revoke all on public.org_accounts, public.org_sessions, public.org_password_resets, public.org_verifications from anon, authenticated;
revoke all on sequence public.org_code_seq, public.org_scan_seq from anon, authenticated;

-- ===========================================================================
-- 2. Helpers
-- ===========================================================================
create or replace function public._session_org(p_token uuid)
returns uuid
language sql
stable
security definer
set search_path = public, extensions
as $$
  select s.org_id
    from public.org_sessions s
    join public.org_accounts o on o.id = s.org_id
   where s.token = p_token and not s.revoked and s.expires_at > now() and o.status = 'active';
$$;

create or replace function public._org_profile(o public.org_accounts)
returns jsonb
language sql
stable
security definer
set search_path = public, extensions
as $$
  select jsonb_build_object(
    'org_code', o.org_code, 'name', o.name, 'business_type', o.business_type, 'industry', o.industry,
    'gstin', o.gstin, 'pan', o.pan, 'fssai_licence', o.fssai_licence, 'bis_licence', o.bis_licence,
    'address', o.address, 'city', o.city, 'state', o.state, 'pincode', o.pincode,
    'authorised_person', o.authorised_person, 'designation', o.designation, 'email', o.email, 'phone', o.phone,
    'status', o.status, 'registered_via', o.registered_via, 'member_since', o.created_at, 'last_login_at', o.last_login_at);
$$;

create or replace function public._org_scan_summary(s public.org_verifications)
returns jsonb
language sql
stable
security definer
set search_path = public, extensions
as $$
  select jsonb_build_object(
    'scan_code', s.scan_code, 'authority', s.authority,
    'product', coalesce(s.product_name, s.category_name, 'Scanned product'), 'brand', s.brand,
    'verdict', s.verdict,
    'passed', s.rules_passed, 'failed', s.rules_failed, 'review', s.rules_review, 'not_applicable', s.rules_na,
    'score', s.score, 'quality', s.quality_grade, 'source', s.source,
    'analyzer_version', s.analyzer_version, 'duration_ms', s.duration_ms, 'created_at', s.created_at,
    'category_id', s.category_id, 'category', s.category_name, 'package_type', s.package_type,
    'flags', coalesce((
      select jsonb_agg(jsonb_build_object('id', r->>'id', 'status', r->>'status',
                                          'title', coalesce(r->>'title', cr.title)))
        from jsonb_array_elements(coalesce(s.report->'rules', '[]'::jsonb)) r
        left join public.compliance_rules cr on cr.id = r->>'id'
       where r->>'status' in ('FAIL', 'REVIEW')
    ), '[]'::jsonb));
$$;

revoke all on function public._session_org(uuid) from public, anon, authenticated;
revoke all on function public._org_profile(public.org_accounts) from public, anon, authenticated;
revoke all on function public._org_scan_summary(public.org_verifications) from public, anon, authenticated;

-- ===========================================================================
-- 3. org_register: called by the WEBSITE. Creates the account and returns its Organisation ID.
-- ===========================================================================
create or replace function public.org_register(
  p_name text, p_business_type text, p_industry text, p_authorised_person text, p_designation text,
  p_email text, p_phone text, p_gstin text, p_fssai_licence text, p_bis_licence text,
  p_address text, p_city text, p_state text, p_pincode text, p_password text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_name   text := regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g');
  v_person text := regexp_replace(btrim(coalesce(p_authorised_person, '')), '\s+', ' ', 'g');
  v_email  text := lower(btrim(coalesce(p_email, '')));
  v_gstin  text := nullif(upper(btrim(coalesce(p_gstin, ''))), '');
  v_fssai  text := nullif(btrim(coalesce(p_fssai_licence, '')), '');
  v_pin    text := nullif(btrim(coalesce(p_pincode, '')), '');
  v_code   text;
begin
  if char_length(v_name) < 3 or char_length(v_name) > 120 then return jsonb_build_object('ok', false, 'code', 'bad_name'); end if;
  if p_business_type not in ('Manufacturer', 'Packer', 'Importer', 'Retailer', 'Distributor', 'Exporter') then
    return jsonb_build_object('ok', false, 'code', 'bad_type'); end if;
  if char_length(v_person) < 3 or char_length(v_person) > 80 then return jsonb_build_object('ok', false, 'code', 'bad_person'); end if;
  if v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]{2,}$' or char_length(v_email) > 120 then
    return jsonb_build_object('ok', false, 'code', 'bad_email'); end if;
  if v_gstin is not null and v_gstin !~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$' then
    return jsonb_build_object('ok', false, 'code', 'bad_gstin'); end if;
  if v_fssai is not null and v_fssai !~ '^[0-9]{14}$' then return jsonb_build_object('ok', false, 'code', 'bad_fssai'); end if;
  if v_pin is not null and v_pin !~ '^[0-9]{6}$' then return jsonb_build_object('ok', false, 'code', 'bad_pincode'); end if;
  if char_length(coalesce(p_password, '')) < 8 or char_length(p_password) > 64
     or p_password !~ '[A-Za-z]' or p_password !~ '[0-9]' then
    return jsonb_build_object('ok', false, 'code', 'bad_password'); end if;
  if exists (select 1 from public.org_accounts where lower(email) = v_email) then
    return jsonb_build_object('ok', false, 'code', 'email_taken'); end if;
  if v_gstin is not null and exists (select 1 from public.org_accounts where gstin = v_gstin) then
    return jsonb_build_object('ok', false, 'code', 'gstin_taken'); end if;

  v_code := 'ORG-' || to_char(now(), 'YY') || '-' || lpad(nextval('public.org_code_seq')::text, 5, '0');
  begin
    insert into public.org_accounts (
      org_code, name, business_type, industry, gstin, pan, fssai_licence, bis_licence, address, city, state, pincode,
      authorised_person, designation, email, phone, password_hash, registered_via)
    values (
      v_code, v_name, p_business_type, nullif(left(btrim(coalesce(p_industry, '')), 120), ''), v_gstin,
      case when v_gstin is not null then substr(v_gstin, 3, 10) end, v_fssai,
      nullif(left(btrim(coalesce(p_bis_licence, '')), 40), ''),
      nullif(left(btrim(coalesce(p_address, '')), 200), ''), nullif(left(btrim(coalesce(p_city, '')), 60), ''),
      nullif(left(btrim(coalesce(p_state, '')), 60), ''), v_pin,
      v_person, nullif(left(btrim(coalesce(p_designation, '')), 80), ''), v_email,
      nullif(left(btrim(coalesce(p_phone, '')), 20), ''), extensions.crypt(p_password, extensions.gen_salt('bf')), 'website');
  exception when unique_violation then
    return jsonb_build_object('ok', false, 'code', 'email_taken');
  end;
  return jsonb_build_object('ok', true, 'code', 'ok', 'org_code', v_code, 'name', v_name);
end;
$$;

-- ===========================================================================
-- 4. org_login: Organisation ID + organisation name + authorised person email + password
-- ===========================================================================
create or replace function public.org_login(p_org_code text, p_name text, p_email text, p_password text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  c_max_attempts constant integer := 5;
  c_lock_minutes constant integer := 15;
  v_code  text := upper(btrim(coalesce(p_org_code, '')));
  v_name  text := lower(regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g'));
  v_email text := lower(btrim(coalesce(p_email, '')));
  o       public.org_accounts%rowtype;
  v_token uuid;
  v_fails integer;
begin
  delete from public.org_sessions where expires_at < now() - interval '1 day';

  if v_code = '' or v_name = '' or v_email = '' or coalesce(p_password, '') = '' or length(p_password) > 128 then
    return jsonb_build_object('ok', false, 'code', 'invalid');
  end if;

  select * into o from public.org_accounts where org_code = v_code;
  if not found then return jsonb_build_object('ok', false, 'code', 'invalid'); end if;

  if o.locked_until is not null and o.locked_until > now() then
    return jsonb_build_object('ok', false, 'code', 'locked',
                              'locked_seconds', ceil(extract(epoch from (o.locked_until - now())))::integer);
  end if;

  if o.password_hash = extensions.crypt(p_password, o.password_hash)
     and lower(regexp_replace(btrim(o.name), '\s+', ' ', 'g')) = v_name
     and lower(o.email) = v_email then
    if o.status <> 'active' then return jsonb_build_object('ok', false, 'code', 'inactive', 'status', o.status); end if;
    update public.org_accounts set failed_attempts = 0, locked_until = null, last_login_at = now() where id = o.id;
    insert into public.org_sessions (org_id) values (o.id) returning token into v_token;
    return jsonb_build_object('ok', true, 'code', 'ok', 'token', v_token, 'profile', public._org_profile(o));
  end if;

  v_fails := o.failed_attempts + 1;
  if v_fails >= c_max_attempts then
    update public.org_accounts set failed_attempts = 0, locked_until = now() + make_interval(mins => c_lock_minutes) where id = o.id;
    return jsonb_build_object('ok', false, 'code', 'locked', 'locked_seconds', c_lock_minutes * 60);
  end if;
  update public.org_accounts set failed_attempts = v_fails where id = o.id;
  return jsonb_build_object('ok', false, 'code', 'invalid', 'attempts_left', c_max_attempts - v_fails);
end;
$$;

create or replace function public.org_logout(p_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
  update public.org_sessions set revoked = true where token = p_token;
  return jsonb_build_object('ok', true);
end;
$$;

-- ===========================================================================
-- 5. Forgot password
--    org_create_reset: SERVICE ROLE ONLY (called by the Edge Function). Returns the plain token once so the
--    function can email it; nothing is ever returned to the app. Rate limited to 3 links per hour.
-- ===========================================================================
create or replace function public.org_create_reset(p_org_code text, p_email text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  o       public.org_accounts%rowtype;
  v_token text;
begin
  select * into o from public.org_accounts
   where org_code = upper(btrim(coalesce(p_org_code, ''))) and lower(email) = lower(btrim(coalesce(p_email, '')))
     and status = 'active';
  if not found then return jsonb_build_object('ok', false, 'code', 'no_match'); end if;

  if (select count(*) from public.org_password_resets where org_id = o.id and created_at > now() - interval '1 hour') >= 3 then
    return jsonb_build_object('ok', false, 'code', 'rate_limited');
  end if;

  v_token := encode(extensions.gen_random_bytes(32), 'hex');
  insert into public.org_password_resets (org_id, token_hash)
  values (o.id, encode(extensions.digest(v_token, 'sha256'), 'hex'));
  return jsonb_build_object('ok', true, 'token', v_token, 'email', o.email, 'name', o.name,
                            'authorised_person', o.authorised_person, 'org_code', o.org_code, 'expires_minutes', 30);
end;
$$;

--    org_check_reset / org_reset_password: called by the website's reset page with the emailed token
create or replace function public.org_check_reset(p_token text)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
declare r public.org_password_resets%rowtype; o public.org_accounts%rowtype;
begin
  select * into r from public.org_password_resets
   where token_hash = encode(extensions.digest(coalesce(p_token, ''), 'sha256'), 'hex') and used_at is null and expires_at > now();
  if not found then return jsonb_build_object('ok', false, 'code', 'invalid_or_expired'); end if;
  select * into o from public.org_accounts where id = r.org_id;
  return jsonb_build_object('ok', true, 'org_code', o.org_code, 'name', o.name);
end;
$$;

create or replace function public.org_reset_password(p_token text, p_new_password text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare r public.org_password_resets%rowtype;
begin
  if char_length(coalesce(p_new_password, '')) < 8 or char_length(p_new_password) > 64
     or p_new_password !~ '[A-Za-z]' or p_new_password !~ '[0-9]' then
    return jsonb_build_object('ok', false, 'code', 'bad_password');
  end if;
  select * into r from public.org_password_resets
   where token_hash = encode(extensions.digest(coalesce(p_token, ''), 'sha256'), 'hex') and used_at is null and expires_at > now()
   for update;
  if not found then return jsonb_build_object('ok', false, 'code', 'invalid_or_expired'); end if;

  update public.org_accounts
     set password_hash = extensions.crypt(p_new_password, extensions.gen_salt('bf')), failed_attempts = 0, locked_until = null
   where id = r.org_id;
  update public.org_password_resets set used_at = now() where org_id = r.org_id and used_at is null;
  update public.org_sessions set revoked = true where org_id = r.org_id;  -- sign out everywhere
  return jsonb_build_object('ok', true);
end;
$$;

-- ===========================================================================
-- 6. org_overview / org_scan: profile, totals per regulator and every verification (newest first)
-- ===========================================================================
create or replace function public.org_overview(p_token uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
declare
  v_me uuid := public._session_org(p_token);
  o    public.org_accounts%rowtype;
begin
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  select * into o from public.org_accounts where id = v_me;

  return jsonb_build_object(
    'ok', true, 'generated_at', now(),
    'profile', public._org_profile(o),
    'totals', (
      select jsonb_build_object(
        'total', count(*),
        'passed', count(*) filter (where verdict = 'Passed'),
        'failed', count(*) filter (where verdict = 'Failed'),
        'review', count(*) filter (where verdict = 'Under Review'),
        'average_score', coalesce(round(avg(score), 1), 0),
        'this_month', count(*) filter (where created_at >= date_trunc('month', now())),
        'bis', count(*) filter (where authority = 'BIS'),
        'fssai', count(*) filter (where authority = 'FSSAI'))
      from public.org_verifications where org_id = v_me),
    'scans', coalesce((
      select jsonb_agg(public._org_scan_summary(x) order by x.created_at desc)
      from (select * from public.org_verifications where org_id = v_me order by created_at desc limit 300) x
    ), '[]'::jsonb));
end;
$$;

create or replace function public.org_scan(p_token uuid, p_scan_code text)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
declare
  v_me uuid := public._session_org(p_token);
  s    public.org_verifications%rowtype;
begin
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  select * into s from public.org_verifications where scan_code = btrim(coalesce(p_scan_code, '')) and org_id = v_me;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  return jsonb_build_object('ok', true, 'scan', public._org_scan_summary(s), 'report', s.report, 'catalog', public._rule_catalog());
end;
$$;

-- ===========================================================================
-- 7. org_save_scan: store one BIS / Pre-FSSAI verification. Verdict and counts are recomputed here.
-- ===========================================================================
create or replace function public.org_save_scan(p_token uuid, p_authority text, p_report jsonb, p_source text default 'Camera')
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me      uuid := public._session_org(p_token);
  v_auth    text := upper(btrim(coalesce(p_authority, '')));
  v_rules   jsonb := p_report->'rules';
  v_pass integer; v_fail integer; v_review integer; v_na integer; v_unknown integer;
  v_verdict text; v_score numeric; v_scan text;
  v_source  text := case when p_source in ('Camera', 'Gallery') then p_source else 'Camera' end;
begin
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_auth not in ('BIS', 'FSSAI') then return jsonb_build_object('ok', false, 'code', 'bad_authority'); end if;
  if p_report is null or jsonb_typeof(p_report) <> 'object' or v_rules is null
     or jsonb_typeof(v_rules) <> 'array' or jsonb_array_length(v_rules) = 0 then
    return jsonb_build_object('ok', false, 'code', 'bad_report'); end if;
  if pg_column_size(p_report) > 4000000 then return jsonb_build_object('ok', false, 'code', 'too_large'); end if;

  select count(*) filter (where r->>'status' = 'PASS'), count(*) filter (where r->>'status' = 'FAIL'),
         count(*) filter (where r->>'status' = 'REVIEW'), count(*) filter (where r->>'status' = 'NOT_APPLICABLE'),
         count(*) filter (where coalesce(r->>'status', '') not in ('PASS', 'FAIL', 'REVIEW', 'NOT_APPLICABLE'))
    into v_pass, v_fail, v_review, v_na, v_unknown from jsonb_array_elements(v_rules) r;
  if v_unknown > 0 then return jsonb_build_object('ok', false, 'code', 'bad_report'); end if;

  v_verdict := case when v_fail > 0 then 'Failed' when v_review > 0 then 'Under Review' else 'Passed' end;
  v_score := case when v_pass + v_fail + v_review = 0 then 0 else round(100.0 * v_pass / (v_pass + v_fail + v_review), 1) end;
  -- Pass mark: a compliance score of 75% or more is a pass for inspector and organisation checks
  if v_pass + v_fail + v_review > 0 and v_score >= 75 then v_verdict := 'Passed'; end if;
  v_scan := case v_auth when 'BIS' then 'BIS-' else 'FSA-' end || to_char(now(), 'YY') || '-' || lpad(nextval('public.org_scan_seq')::text, 6, '0');

  insert into public.org_verifications (
    scan_code, org_id, authority, product_name, brand, verdict, rules_passed, rules_failed, rules_review, rules_na, score,
    quality_grade, source, analyzer_version, duration_ms, report, category_id, category_name, package_type)
  values (
    v_scan, v_me, v_auth, nullif(left(btrim(coalesce(p_report #>> '{ai_insights,product_name}', '')), 120), ''),
    nullif(left(btrim(coalesce(p_report #>> '{ai_insights,brand}', '')), 80), ''),
    v_verdict, v_pass, v_fail, v_review, v_na, v_score,
    left(p_report #>> '{quality,grade}', 20), v_source, left(p_report->>'analyzer_version', 40),
    case when (p_report->>'duration_ms') ~ '^\d{1,9}$' then (p_report->>'duration_ms')::integer end, p_report,
    left(p_report #>> '{classification,category,id}', 40), left(p_report #>> '{classification,category,name}', 120),
    left(p_report #>> '{classification,package_type,id}', 20));

  return jsonb_build_object('ok', true, 'code', 'ok', 'scan_code', v_scan, 'verdict', v_verdict,
                            'passed', v_pass, 'failed', v_fail, 'review', v_review, 'not_applicable', v_na, 'score', v_score);
end;
$$;

create or replace function public.org_delete_scan(p_token uuid, p_scan_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare v_me uuid := public._session_org(p_token);
begin
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  delete from public.org_verifications where scan_code = btrim(coalesce(p_scan_code, '')) and org_id = v_me;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  return jsonb_build_object('ok', true);
end;
$$;

create or replace function public.org_categories(p_token uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
begin
  if public._session_org(p_token) is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  return jsonb_build_object(
    'ok', true, 'version', (select max(catalog_version) from public.product_categories),
    'categories', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', c.id, 'name', c.name, 'group', c.group_id, 'group_name', c.group_name,
               'laws', to_jsonb(c.laws), 'tags', to_jsonb(c.tags),
               'quantity', jsonb_build_object('dims', to_jsonb(c.quantity_dims), 'ref', c.quantity_ref),
               'standards', c.standards,
               'rules', (select count(*) from public.compliance_rules r
                          where r.active and r.scope <> 'wholesale' and public._rule_applies(r.applies_to, c))
             ) order by c.sort_order)
      from public.product_categories c), '[]'::jsonb),
    'package_types', coalesce((select jsonb_agg(jsonb_build_object('id', p.id, 'name', p.name, 'ref', p.reference) order by p.id)
                                 from public.package_types p), '[]'::jsonb));
end;
$$;

-- ===========================================================================
-- 8. Grants
-- ===========================================================================
revoke all on function public.org_register(text, text, text, text, text, text, text, text, text, text, text, text, text, text, text) from public;
revoke all on function public.org_login(text, text, text, text)       from public;
revoke all on function public.org_logout(uuid)                        from public;
revoke all on function public.org_create_reset(text, text)            from public, anon, authenticated;
revoke all on function public.org_check_reset(text)                   from public;
revoke all on function public.org_reset_password(text, text)          from public;
revoke all on function public.org_overview(uuid)                      from public;
revoke all on function public.org_scan(uuid, text)                    from public;
revoke all on function public.org_save_scan(uuid, text, jsonb, text)  from public;
revoke all on function public.org_delete_scan(uuid, text)             from public;
revoke all on function public.org_categories(uuid)                    from public;

grant execute on function public.org_register(text, text, text, text, text, text, text, text, text, text, text, text, text, text, text) to anon, authenticated;
grant execute on function public.org_login(text, text, text, text)       to anon, authenticated;
grant execute on function public.org_logout(uuid)                        to anon, authenticated;
grant execute on function public.org_create_reset(text, text)            to service_role;
grant execute on function public.org_check_reset(text)                   to anon, authenticated;
grant execute on function public.org_reset_password(text, text)          to anon, authenticated;
grant execute on function public.org_overview(uuid)                      to anon, authenticated;
grant execute on function public.org_scan(uuid, text)                    to anon, authenticated;
grant execute on function public.org_save_scan(uuid, text, jsonb, text)  to anon, authenticated;
grant execute on function public.org_delete_scan(uuid, text)             to anon, authenticated;
grant execute on function public.org_categories(uuid)                    to anon, authenticated;

-- ===========================================================================
-- 9. DEMO organisations: fictional companies with correctly formatted GSTIN / FSSAI / BIS numbers.
--    All share the demo password set below. Remove before going live:
--      delete from public.org_accounts where registered_via = 'seed';
-- ===========================================================================
insert into public.org_accounts (org_code, name, business_type, industry, gstin, pan, fssai_licence, bis_licence, address, city, state,
                                 pincode, authorised_person, designation, email, phone, password_hash, registered_via)
select v.*, extensions.crypt('Comply@2026', extensions.gen_salt('bf')), 'seed'
from (values
  ('ORG-26-10001', 'Sunrise Agro Foods Pvt Ltd', 'Manufacturer', 'Packaged foods & flour', '27GYGCS6980E1ZI', 'GYGCS6980E', '22719982636303', null, 'Plot 14, MIDC Chakan Industrial Area, Phase II', 'Pune', 'Maharashtra', '410501', 'Rohan Deshpande', 'Director - Quality', 'rohan.deshpande@sunriseagrofoods.in', '+91 98220 41873'),
  ('ORG-26-10002', 'Gangotri Dairy Cooperative Ltd', 'Manufacturer', 'Milk & dairy products', '24EEGCG9765H1ZW', 'EEGCG9765H', '12419488180079', null, 'Survey 212, Anand-Sojitra Road', 'Anand', 'Gujarat', '388001', 'Meera Patel', 'Head of Compliance', 'meera.patel@gangotridairy.in', '+91 94270 11582'),
  ('ORG-26-10003', 'Kaveri Spices & Condiments LLP', 'Manufacturer', 'Spices & masalas', '29YULCK5663B1ZJ', 'YULCK5663B', '22920966982011', null, 'No. 7, 2nd Cross, Peenya 2nd Stage', 'Bengaluru', 'Karnataka', '560058', 'Suresh Naik', 'Plant Manager', 'suresh.naik@kaverispices.in', '+91 98450 66210'),
  ('ORG-26-10004', 'Himalayan Pure Beverages Pvt Ltd', 'Manufacturer', 'Packaged drinking water & beverages', '05EUUCH0063M1Z0', 'EUUCH0063M', '10523516067910', 'R-46910418', 'Khasra 88, Selaqui Industrial Estate', 'Dehradun', 'Uttarakhand', '248011', 'Ankita Rawat', 'Quality Assurance Lead', 'ankita.rawat@himalayanpure.in', '+91 99970 35124'),
  ('ORG-26-10005', 'Voltedge Electricals India Pvt Ltd', 'Manufacturer', 'Electrical appliances & batteries', '07DYHCV1245W1Z2', 'DYHCV1245W', null, 'R-48319125', 'B-42, Okhla Industrial Area Phase I', 'New Delhi', 'Delhi', '110020', 'Vikram Malhotra', 'Regulatory Affairs Manager', 'vikram.malhotra@voltedge.in', '+91 98110 72045'),
  ('ORG-26-10006', 'PlayNest Toys & Kids Pvt Ltd', 'Importer', 'Toys & children products', '33MTSCP9600E1ZX', 'MTSCP9600E', null, 'R-41136797', '12, Ambattur Industrial Estate North', 'Chennai', 'Tamil Nadu', '600098', 'Divya Raman', 'Compliance Officer', 'divya.raman@playnesttoys.in', '+91 98400 28831'),
  ('ORG-26-10007', 'Lotus Retail Mart Pvt Ltd', 'Retailer', 'Supermarket chain (packaged goods)', '19TDWCL7092W1Z2', 'TDWCL7092W', '11919604621421', null, '4th Floor, Camac Square, Camac Street', 'Kolkata', 'West Bengal', '700016', 'Sourav Banerjee', 'Category Head - Private Label', 'sourav.banerjee@lotusretailmart.in', '+91 98300 54419'),
  ('ORG-26-10008', 'SteelCraft Cement & Allied Products', 'Manufacturer', 'Cement & building materials', '08CWBCS0680J1ZZ', 'CWBCS0680J', null, 'R-49230842', 'Village Nimbahera Road, Tehsil Chittorgarh', 'Chittorgarh', 'Rajasthan', '312601', 'Mahesh Choudhary', 'Chief Quality Officer', 'mahesh.choudhary@steelcraftcement.in', '+91 94140 90377'),
  ('ORG-26-10009', 'Annapurna Sweets & Snacks', 'Packer', 'Traditional sweets & namkeen', '09LXXCA8601D1ZU', 'LXXCA8601D', '10922343298669', null, 'C-18, Sector 63', 'Noida', 'Uttar Pradesh', '201301', 'Priya Sharma', 'Proprietor', 'priya.sharma@annapurnasweets.in', '+91 98100 33762'),
  ('ORG-26-10010', 'FreshLeaf Tea Estates Pvt Ltd', 'Manufacturer', 'Tea & coffee', '18LCTCF1200E1ZI', 'LCTCF1200E', '21820513456737', null, 'Tea Estate Road, Mangaldai', 'Darrang', 'Assam', '784125', 'Dibyajyoti Bora', 'Estate Manager', 'dibyajyoti.bora@freshleaftea.in', '+91 98640 77821')
) as v(org_code, name, business_type, industry, gstin, pan, fssai_licence, bis_licence, address, city, state, pincode, authorised_person, designation, email, phone)
on conflict (org_code) do nothing;
