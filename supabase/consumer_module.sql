-- ComplyVision: General Consumer portal (register, login, personal package checks)
-- Run once in the Supabase SQL editor AFTER schema.sql, inspector_module.sql and rule_engine.sql.
-- Safe to re-run (idempotent).
--
-- Same security model as the officer modules
--   * RLS is enabled with NO policies and anon/authenticated have no table grants; the app only calls the
--     SECURITY DEFINER functions at the bottom.
--   * Passwords are bcrypt hashes (pgcrypto). 5 wrong passwords lock the account for 15 minutes.
--   * Login / registration return a random session token (30 days) that every other call must send.
--   * Every package check belongs to exactly one consumer; consumers can only ever read their own.

create extension if not exists pgcrypto with schema extensions;

-- ===========================================================================
-- 1. Tables
-- ===========================================================================
create table if not exists public.consumers (
  id              uuid primary key default gen_random_uuid(),
  full_name       text not null check (char_length(full_name) between 2 and 60),
  email           text not null check (char_length(email) <= 120),
  password_hash   text not null,
  is_active       boolean not null default true,
  failed_attempts integer not null default 0,
  locked_until    timestamptz,
  last_login_at   timestamptz,
  created_at      timestamptz not null default now()
);
create unique index if not exists consumers_email_uidx on public.consumers (lower(email));

create table if not exists public.consumer_sessions (
  token       uuid primary key default gen_random_uuid(),
  consumer_id uuid not null references public.consumers (id) on delete cascade,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null default (now() + interval '30 days'),
  revoked     boolean not null default false
);
create index if not exists consumer_sessions_consumer_idx on public.consumer_sessions (consumer_id);

create sequence if not exists public.consumer_scan_seq start 100000;

create table if not exists public.consumer_scans (
  id               uuid primary key default gen_random_uuid(),
  scan_code        text not null unique,
  consumer_id      uuid not null references public.consumers (id) on delete cascade,
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
create index if not exists consumer_scans_consumer_idx on public.consumer_scans (consumer_id, created_at desc);

alter table public.consumers         enable row level security;
alter table public.consumer_sessions enable row level security;
alter table public.consumer_scans    enable row level security;
revoke all on public.consumers, public.consumer_sessions, public.consumer_scans from anon, authenticated;
revoke all on sequence public.consumer_scan_seq from anon, authenticated;

-- ===========================================================================
-- 2. Helpers
-- ===========================================================================
create or replace function public._session_consumer(p_token uuid)
returns uuid
language sql
stable
security definer
set search_path = public, extensions
as $$
  select s.consumer_id
    from public.consumer_sessions s
    join public.consumers c on c.id = s.consumer_id
   where s.token = p_token and not s.revoked and s.expires_at > now() and c.is_active;
$$;

create or replace function public._consumer_scan_summary(s public.consumer_scans)
returns jsonb
language sql
stable
security definer
set search_path = public, extensions
as $$
  select jsonb_build_object(
    'scan_code', s.scan_code,
    'product', coalesce(s.product_name, s.category_name, 'Scanned product'),
    'brand', s.brand,
    'verdict', s.verdict,
    'passed', s.rules_passed, 'failed', s.rules_failed, 'review', s.rules_review, 'not_applicable', s.rules_na,
    'score', s.score,
    'quality', s.quality_grade,
    'source', s.source,
    'analyzer_version', s.analyzer_version,
    'duration_ms', s.duration_ms,
    'created_at', s.created_at,
    'category_id', s.category_id,
    'category', s.category_name,
    'package_type', s.package_type,
    'flags', coalesce((
      select jsonb_agg(jsonb_build_object('id', r->>'id', 'status', r->>'status',
                                          'title', coalesce(r->>'title', cr.title)))
        from jsonb_array_elements(coalesce(s.report->'rules', '[]'::jsonb)) r
        left join public.compliance_rules cr on cr.id = r->>'id'
       where r->>'status' in ('FAIL', 'REVIEW')
    ), '[]'::jsonb)
  );
$$;

revoke all on function public._session_consumer(uuid) from public, anon, authenticated;
revoke all on function public._consumer_scan_summary(public.consumer_scans) from public, anon, authenticated;

-- ===========================================================================
-- 3. consumer_register: creates the account and signs the consumer in
-- ===========================================================================
create or replace function public.consumer_register(p_name text, p_email text, p_password text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_name  text := regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g');
  v_email text := lower(btrim(coalesce(p_email, '')));
  v_id    uuid;
  v_token uuid;
begin
  delete from public.consumer_sessions where expires_at < now() - interval '1 day';

  if char_length(v_name) < 2 or char_length(v_name) > 60 then
    return jsonb_build_object('ok', false, 'code', 'bad_name');
  end if;
  if v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]{2,}$' or char_length(v_email) > 120 then
    return jsonb_build_object('ok', false, 'code', 'bad_email');
  end if;
  if char_length(coalesce(p_password, '')) < 6 or char_length(p_password) > 64 then
    return jsonb_build_object('ok', false, 'code', 'bad_password');
  end if;
  if exists (select 1 from public.consumers where lower(email) = v_email) then
    return jsonb_build_object('ok', false, 'code', 'email_taken');
  end if;

  begin
    insert into public.consumers (full_name, email, password_hash, last_login_at)
    values (v_name, v_email, extensions.crypt(p_password, extensions.gen_salt('bf')), now())
    returning id into v_id;
  exception when unique_violation then
    return jsonb_build_object('ok', false, 'code', 'email_taken');
  end;

  insert into public.consumer_sessions (consumer_id) values (v_id) returning token into v_token;
  return jsonb_build_object('ok', true, 'code', 'ok', 'token', v_token, 'full_name', v_name, 'email', v_email,
                            'member_since', now());
end;
$$;

-- ===========================================================================
-- 4. consumer_login
-- ===========================================================================
create or replace function public.consumer_login(p_email text, p_password text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  c_max_attempts constant integer := 5;
  c_lock_minutes constant integer := 15;
  v_email text := lower(btrim(coalesce(p_email, '')));
  c       public.consumers%rowtype;
  v_token uuid;
  v_fails integer;
begin
  delete from public.consumer_sessions where expires_at < now() - interval '1 day';

  if v_email = '' or coalesce(p_password, '') = '' or length(p_password) > 128 then
    return jsonb_build_object('ok', false, 'code', 'invalid');
  end if;

  select * into c from public.consumers where lower(email) = v_email;
  if not found then
    -- Same answer as a wrong password so emails cannot be probed
    return jsonb_build_object('ok', false, 'code', 'invalid');
  end if;

  if c.locked_until is not null and c.locked_until > now() then
    return jsonb_build_object('ok', false, 'code', 'locked',
                              'locked_seconds', ceil(extract(epoch from (c.locked_until - now())))::integer);
  end if;

  if c.password_hash = extensions.crypt(p_password, c.password_hash) then
    if not c.is_active then return jsonb_build_object('ok', false, 'code', 'inactive'); end if;

    update public.consumers set failed_attempts = 0, locked_until = null, last_login_at = now() where id = c.id;
    insert into public.consumer_sessions (consumer_id) values (c.id) returning token into v_token;
    return jsonb_build_object('ok', true, 'code', 'ok', 'token', v_token, 'full_name', c.full_name,
                              'email', c.email, 'member_since', c.created_at);
  end if;

  v_fails := c.failed_attempts + 1;
  if v_fails >= c_max_attempts then
    update public.consumers set failed_attempts = 0, locked_until = now() + make_interval(mins => c_lock_minutes)
     where id = c.id;
    return jsonb_build_object('ok', false, 'code', 'locked', 'locked_seconds', c_lock_minutes * 60);
  end if;
  update public.consumers set failed_attempts = v_fails where id = c.id;
  return jsonb_build_object('ok', false, 'code', 'invalid', 'attempts_left', c_max_attempts - v_fails);
end;
$$;

create or replace function public.consumer_logout(p_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
  update public.consumer_sessions set revoked = true where token = p_token;
  return jsonb_build_object('ok', true);
end;
$$;

-- ===========================================================================
-- 5. consumer_overview: profile, totals and every check this consumer has made (newest first)
-- ===========================================================================
create or replace function public.consumer_overview(p_token uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
declare
  v_me uuid := public._session_consumer(p_token);
  c    public.consumers%rowtype;
begin
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  select * into c from public.consumers where id = v_me;

  return jsonb_build_object(
    'ok', true,
    'generated_at', now(),
    'profile', jsonb_build_object('name', c.full_name, 'email', c.email, 'member_since', c.created_at,
                                  'last_login_at', c.last_login_at),
    'totals', (
      select jsonb_build_object(
               'total', count(*),
               'passed', count(*) filter (where verdict = 'Passed'),
               'failed', count(*) filter (where verdict = 'Failed'),
               'review', count(*) filter (where verdict = 'Under Review'),
               'average_score', coalesce(round(avg(score), 1), 0),
               'this_month', count(*) filter (where created_at >= date_trunc('month', now())))
        from public.consumer_scans where consumer_id = v_me),
    'scans', coalesce((
      select jsonb_agg(public._consumer_scan_summary(x) order by x.created_at desc)
      from (select * from public.consumer_scans where consumer_id = v_me order by created_at desc limit 300) x
    ), '[]'::jsonb)
  );
end;
$$;

-- ===========================================================================
-- 6. consumer_scan: the full report of one of my checks
-- ===========================================================================
create or replace function public.consumer_scan(p_token uuid, p_scan_code text)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
declare
  v_me uuid := public._session_consumer(p_token);
  s    public.consumer_scans%rowtype;
begin
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  select * into s from public.consumer_scans where scan_code = btrim(coalesce(p_scan_code, '')) and consumer_id = v_me;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  return jsonb_build_object('ok', true, 'scan', public._consumer_scan_summary(s), 'report', s.report,
                            'catalog', public._rule_catalog());
end;
$$;

-- ===========================================================================
-- 7. consumer_save_scan: store one analysed package under this consumer
--    Verdict and counts are recomputed here from the rules so a client can never inflate them.
-- ===========================================================================
create or replace function public.consumer_save_scan(p_token uuid, p_report jsonb, p_source text default 'Camera')
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me      uuid := public._session_consumer(p_token);
  v_rules   jsonb := p_report->'rules';
  v_pass    integer;
  v_fail    integer;
  v_review  integer;
  v_na      integer;
  v_unknown integer;
  v_verdict text;
  v_score   numeric;
  v_scan    text;
  v_source  text := case when p_source in ('Camera', 'Gallery') then p_source else 'Camera' end;
  v_product text;
begin
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;

  if p_report is null or jsonb_typeof(p_report) <> 'object'
     or v_rules is null or jsonb_typeof(v_rules) <> 'array' or jsonb_array_length(v_rules) = 0 then
    return jsonb_build_object('ok', false, 'code', 'bad_report');
  end if;
  if pg_column_size(p_report) > 4000000 then
    return jsonb_build_object('ok', false, 'code', 'too_large');
  end if;

  select count(*) filter (where r->>'status' = 'PASS'),
         count(*) filter (where r->>'status' = 'FAIL'),
         count(*) filter (where r->>'status' = 'REVIEW'),
         count(*) filter (where r->>'status' = 'NOT_APPLICABLE'),
         count(*) filter (where coalesce(r->>'status', '') not in ('PASS', 'FAIL', 'REVIEW', 'NOT_APPLICABLE'))
    into v_pass, v_fail, v_review, v_na, v_unknown
    from jsonb_array_elements(v_rules) r;
  if v_unknown > 0 then return jsonb_build_object('ok', false, 'code', 'bad_report'); end if;

  v_verdict := case when v_fail > 0 then 'Failed' when v_review > 0 then 'Under Review' else 'Passed' end;
  v_score := case when v_pass + v_fail + v_review = 0 then 0
                  else round(100.0 * v_pass / (v_pass + v_fail + v_review), 1) end;
  v_scan := 'CNS-' || to_char(now(), 'YY') || '-' || lpad(nextval('public.consumer_scan_seq')::text, 6, '0');
  v_product := nullif(left(btrim(coalesce(p_report #>> '{ai_insights,product_name}', '')), 120), '');

  insert into public.consumer_scans (
    scan_code, consumer_id, product_name, brand, verdict, rules_passed, rules_failed, rules_review, rules_na,
    score, quality_grade, source, analyzer_version, duration_ms, report, category_id, category_name, package_type)
  values (
    v_scan, v_me, v_product, nullif(left(btrim(coalesce(p_report #>> '{ai_insights,brand}', '')), 80), ''),
    v_verdict, v_pass, v_fail, v_review, v_na, v_score,
    left(p_report #>> '{quality,grade}', 20), v_source, left(p_report->>'analyzer_version', 40),
    case when (p_report->>'duration_ms') ~ '^\d{1,9}$' then (p_report->>'duration_ms')::integer end,
    p_report,
    left(p_report #>> '{classification,category,id}', 40), left(p_report #>> '{classification,category,name}', 120),
    left(p_report #>> '{classification,package_type,id}', 20));

  return jsonb_build_object(
    'ok', true, 'code', 'ok', 'scan_code', v_scan, 'verdict', v_verdict,
    'passed', v_pass, 'failed', v_fail, 'review', v_review, 'not_applicable', v_na, 'score', v_score);
end;
$$;

-- ===========================================================================
-- 8. consumer_delete_scan: remove one of my own checks
-- ===========================================================================
create or replace function public.consumer_delete_scan(p_token uuid, p_scan_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me uuid := public._session_consumer(p_token);
begin
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  delete from public.consumer_scans where scan_code = btrim(coalesce(p_scan_code, '')) and consumer_id = v_me;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  return jsonb_build_object('ok', true);
end;
$$;

-- ===========================================================================
-- 9. consumer_categories: the product taxonomy for the category picker (same data the inspector sees)
-- ===========================================================================
create or replace function public.consumer_categories(p_token uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
begin
  if public._session_consumer(p_token) is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  return jsonb_build_object(
    'ok', true,
    'version', (select max(catalog_version) from public.product_categories),
    'categories', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', c.id, 'name', c.name, 'group', c.group_id, 'group_name', c.group_name,
               'laws', to_jsonb(c.laws), 'tags', to_jsonb(c.tags),
               'quantity', jsonb_build_object('dims', to_jsonb(c.quantity_dims), 'ref', c.quantity_ref),
               'standards', c.standards,
               'rules', (select count(*) from public.compliance_rules r
                          where r.active and r.scope <> 'wholesale' and public._rule_applies(r.applies_to, c))
             ) order by c.sort_order)
      from public.product_categories c
    ), '[]'::jsonb),
    'package_types', coalesce((
      select jsonb_agg(jsonb_build_object('id', p.id, 'name', p.name, 'ref', p.reference) order by p.id)
      from public.package_types p
    ), '[]'::jsonb)
  );
end;
$$;

-- ===========================================================================
-- 10. Grants: the app (anon key) may call only these functions
-- ===========================================================================
revoke all on function public.consumer_register(text, text, text)  from public;
revoke all on function public.consumer_login(text, text)           from public;
revoke all on function public.consumer_logout(uuid)                from public;
revoke all on function public.consumer_overview(uuid)              from public;
revoke all on function public.consumer_scan(uuid, text)            from public;
revoke all on function public.consumer_save_scan(uuid, jsonb, text) from public;
revoke all on function public.consumer_delete_scan(uuid, text)     from public;
revoke all on function public.consumer_categories(uuid)            from public;

grant execute on function public.consumer_register(text, text, text)   to anon, authenticated;
grant execute on function public.consumer_login(text, text)            to anon, authenticated;
grant execute on function public.consumer_logout(uuid)                 to anon, authenticated;
grant execute on function public.consumer_overview(uuid)               to anon, authenticated;
grant execute on function public.consumer_scan(uuid, text)             to anon, authenticated;
grant execute on function public.consumer_save_scan(uuid, jsonb, text) to anon, authenticated;
grant execute on function public.consumer_delete_scan(uuid, text)      to anon, authenticated;
grant execute on function public.consumer_categories(uuid)             to anon, authenticated;
