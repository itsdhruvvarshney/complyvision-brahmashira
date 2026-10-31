-- ComplyVision: Legal Metrology officer authentication + dashboards
-- Run once in the Supabase SQL editor. Safe to re-run (idempotent).
--
-- Security model
--   * Every table has RLS enabled with NO policies, and anon/authenticated have no table grants,
--     so the public API key can never read or write a table directly.
--   * The app talks only to the SECURITY DEFINER functions at the bottom (officer_login,
--     officer_logout, inspector_dashboard, authority_dashboard).
--   * Passwords are stored as bcrypt hashes (pgcrypto), never in plain text.
--   * Login is rate-limited per officer: 5 wrong attempts lock the account for 15 minutes.
--   * A successful login returns a random session token (8 h) that every dashboard call must send.

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
create table if not exists public.designations (
  id        smallint primary key,
  name      text not null unique,
  dashboard text not null check (dashboard in ('AUTHORITY', 'INSPECTOR'))
);

create table if not exists public.officers (
  id              uuid primary key default gen_random_uuid(),
  officer_id      text not null unique check (officer_id ~ '^[A-Z0-9][A-Z0-9-]{3,19}$'),
  full_name       text not null check (char_length(full_name) between 3 and 60),
  designation_id  smallint not null references public.designations (id),
  password_hash   text not null,
  office          text,
  email           text,
  phone           text,
  is_active       boolean not null default true,
  failed_attempts integer not null default 0,
  locked_until    timestamptz,
  last_login_at   timestamptz,
  created_at      timestamptz not null default now()
);
create index if not exists officers_designation_idx on public.officers (designation_id);

create table if not exists public.officer_sessions (
  token        uuid primary key default gen_random_uuid(),
  officer_uuid uuid not null references public.officers (id) on delete cascade,
  created_at   timestamptz not null default now(),
  expires_at   timestamptz not null default (now() + interval '8 hours'),
  revoked      boolean not null default false
);
create index if not exists officer_sessions_officer_idx on public.officer_sessions (officer_uuid);

create table if not exists public.login_audit (
  id               bigint generated always as identity primary key,
  officer_id_tried text,
  success          boolean not null,
  reason           text,
  created_at       timestamptz not null default now()
);

create table if not exists public.establishments (
  id       uuid primary key default gen_random_uuid(),
  name     text not null unique,
  address  text,
  city     text not null,
  state    text not null,
  category text
);

create table if not exists public.inspections (
  id               uuid primary key default gen_random_uuid(),
  code             text not null unique,
  establishment_id uuid not null references public.establishments (id),
  inspector_id     uuid not null references public.officers (id),
  commodity        text not null,
  inspection_type  text not null,
  status           text not null default 'Scheduled'
                   check (status in ('Scheduled', 'In Progress', 'Completed', 'Violation Found')),
  scheduled_date   date not null,
  completed_at     timestamptz,
  violations_count integer not null default 0 check (violations_count >= 0),
  remarks          text,
  created_at       timestamptz not null default now()
);
create index if not exists inspections_inspector_idx on public.inspections (inspector_id);
create index if not exists inspections_status_idx on public.inspections (status);

-- ---------------------------------------------------------------------------
-- Lock the tables down
-- ---------------------------------------------------------------------------
alter table public.designations     enable row level security;
alter table public.officers         enable row level security;
alter table public.officer_sessions enable row level security;
alter table public.login_audit      enable row level security;
alter table public.establishments   enable row level security;
alter table public.inspections      enable row level security;

revoke all on public.designations, public.officers, public.officer_sessions,
              public.login_audit, public.establishments, public.inspections
  from anon, authenticated;

-- ---------------------------------------------------------------------------
-- Internal helper: resolve a session token to an officer + dashboard type
-- ---------------------------------------------------------------------------
create or replace function public._session_officer(p_token uuid)
returns table (officer_uuid uuid, dashboard text)
language sql
security definer
set search_path = public, extensions
as $$
  select o.id, d.dashboard
  from public.officer_sessions s
  join public.officers o     on o.id = s.officer_uuid
  join public.designations d on d.id = o.designation_id
  where s.token = p_token
    and not s.revoked
    and s.expires_at > now()
    and o.is_active;
$$;
revoke all on function public._session_officer(uuid) from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- officer_login: verifies ID + password + name, returns the designation mapping and a session token
-- ---------------------------------------------------------------------------
create or replace function public.officer_login(p_officer_id text, p_password text, p_name text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  c_max_attempts constant integer := 5;
  c_lock_minutes constant integer := 15;
  v_id    text := upper(btrim(coalesce(p_officer_id, '')));
  v_name  text := lower(regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g'));
  o       public.officers%rowtype;
  d       public.designations%rowtype;
  v_token uuid;
  v_fails integer;
begin
  -- Housekeeping: drop sessions that expired more than a day ago
  delete from public.officer_sessions where expires_at < now() - interval '1 day';

  if v_id = '' or v_name = '' or coalesce(p_password, '') = '' or length(p_password) > 128 then
    insert into public.login_audit (officer_id_tried, success, reason) values (left(v_id, 20), false, 'malformed');
    return jsonb_build_object('ok', false, 'code', 'invalid');
  end if;

  select * into o from public.officers where officer_id = v_id;

  if not found then
    insert into public.login_audit (officer_id_tried, success, reason) values (left(v_id, 20), false, 'unknown_id');
    return jsonb_build_object('ok', false, 'code', 'invalid');
  end if;

  if o.locked_until is not null and o.locked_until > now() then
    insert into public.login_audit (officer_id_tried, success, reason) values (v_id, false, 'locked');
    return jsonb_build_object(
      'ok', false, 'code', 'locked',
      'locked_seconds', ceil(extract(epoch from (o.locked_until - now())))::integer
    );
  end if;

  if o.password_hash = extensions.crypt(p_password, o.password_hash)
     and lower(regexp_replace(btrim(o.full_name), '\s+', ' ', 'g')) = v_name then

    if not o.is_active then
      insert into public.login_audit (officer_id_tried, success, reason) values (v_id, false, 'inactive');
      return jsonb_build_object('ok', false, 'code', 'inactive');
    end if;

    update public.officers
       set failed_attempts = 0, locked_until = null, last_login_at = now()
     where id = o.id;

    insert into public.officer_sessions (officer_uuid) values (o.id) returning token into v_token;
    select * into d from public.designations where id = o.designation_id;

    insert into public.login_audit (officer_id_tried, success, reason) values (v_id, true, 'ok');

    return jsonb_build_object(
      'ok', true, 'code', 'ok',
      'token', v_token,
      'officer_id', o.officer_id,
      'full_name', o.full_name,
      'designation', d.name,
      'dashboard', d.dashboard,
      'office', o.office
    );
  end if;

  -- Wrong password or name: count the failure and lock after too many
  v_fails := o.failed_attempts + 1;
  if v_fails >= c_max_attempts then
    update public.officers
       set failed_attempts = 0, locked_until = now() + make_interval(mins => c_lock_minutes)
     where id = o.id;
    insert into public.login_audit (officer_id_tried, success, reason) values (v_id, false, 'bad_credentials_locked');
    return jsonb_build_object('ok', false, 'code', 'locked', 'locked_seconds', c_lock_minutes * 60);
  end if;

  update public.officers set failed_attempts = v_fails where id = o.id;
  insert into public.login_audit (officer_id_tried, success, reason) values (v_id, false, 'bad_credentials');
  return jsonb_build_object('ok', false, 'code', 'invalid', 'attempts_left', c_max_attempts - v_fails);
end;
$$;

-- ---------------------------------------------------------------------------
-- officer_logout
-- ---------------------------------------------------------------------------
create or replace function public.officer_logout(p_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
  update public.officer_sessions set revoked = true where token = p_token;
  return jsonb_build_object('ok', true);
end;
$$;

-- ---------------------------------------------------------------------------
-- inspector_dashboard: the signed-in inspector's own assignments
-- ---------------------------------------------------------------------------
create or replace function public.inspector_dashboard(p_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_officer uuid;
  v_dash    text;
begin
  select s.officer_uuid, s.dashboard into v_officer, v_dash from public._session_officer(p_token) s;
  if v_officer is null then
    return jsonb_build_object('ok', false, 'code', 'session_expired');
  end if;
  if v_dash <> 'INSPECTOR' then
    return jsonb_build_object('ok', false, 'code', 'forbidden');
  end if;

  return jsonb_build_object(
    'ok', true,
    'stats', (
      select jsonb_build_object(
        'assigned',    count(*),
        'in_progress', count(*) filter (where status = 'In Progress'),
        'completed',   count(*) filter (where status in ('Completed', 'Violation Found')),
        'violations',  coalesce(sum(violations_count), 0)
      )
      from public.inspections where inspector_id = v_officer
    ),
    'inspections', coalesce((
      select jsonb_agg(
        jsonb_build_object(
          'code', i.code,
          'establishment', e.name,
          'city', e.city,
          'commodity', i.commodity,
          'type', i.inspection_type,
          'status', i.status,
          'scheduled_date', i.scheduled_date,
          'violations', i.violations_count
        ) order by i.scheduled_date desc, i.code
      )
      from public.inspections i
      join public.establishments e on e.id = i.establishment_id
      where i.inspector_id = v_officer
    ), '[]'::jsonb)
  );
end;
$$;

-- ---------------------------------------------------------------------------
-- authority_dashboard: organisation-wide view for Supreme Authority officers
-- ---------------------------------------------------------------------------
create or replace function public.authority_dashboard(p_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_officer uuid;
  v_dash    text;
begin
  select s.officer_uuid, s.dashboard into v_officer, v_dash from public._session_officer(p_token) s;
  if v_officer is null then
    return jsonb_build_object('ok', false, 'code', 'session_expired');
  end if;
  if v_dash <> 'AUTHORITY' then
    return jsonb_build_object('ok', false, 'code', 'forbidden');
  end if;

  return jsonb_build_object(
    'ok', true,
    'stats', (
      select jsonb_build_object(
        'inspectors', (
          select count(*) from public.officers o
          join public.designations d on d.id = o.designation_id
          where d.dashboard = 'INSPECTOR' and o.is_active
        ),
        'total_inspections', count(*),
        'completed',   count(*) filter (where status in ('Completed', 'Violation Found')),
        'in_progress', count(*) filter (where status = 'In Progress'),
        'scheduled',   count(*) filter (where status = 'Scheduled'),
        'violations',  coalesce(sum(violations_count), 0),
        'completion_rate', coalesce(
          round(100.0 * count(*) filter (where status in ('Completed', 'Violation Found')) / nullif(count(*), 0)), 0
        )
      )
      from public.inspections
    ),
    'inspectors', coalesce((
      select jsonb_agg(
        jsonb_build_object(
          'officer_id', o.officer_id,
          'name', o.full_name,
          'office', o.office,
          'assigned',    (select count(*) from public.inspections i where i.inspector_id = o.id),
          'completed',   (select count(*) from public.inspections i where i.inspector_id = o.id and i.status in ('Completed', 'Violation Found')),
          'in_progress', (select count(*) from public.inspections i where i.inspector_id = o.id and i.status = 'In Progress'),
          'violations',  (select coalesce(sum(i.violations_count), 0) from public.inspections i where i.inspector_id = o.id)
        ) order by o.full_name
      )
      from public.officers o
      join public.designations d on d.id = o.designation_id
      where d.dashboard = 'INSPECTOR' and o.is_active
    ), '[]'::jsonb),
    'recent', coalesce((
      select jsonb_agg(r.j order by r.sched desc, r.code)
      from (
        select i.scheduled_date as sched, i.code,
               jsonb_build_object(
                 'code', i.code,
                 'establishment', e.name,
                 'city', e.city,
                 'commodity', i.commodity,
                 'type', i.inspection_type,
                 'status', i.status,
                 'scheduled_date', i.scheduled_date,
                 'violations', i.violations_count,
                 'inspector', o.full_name
               ) as j
        from public.inspections i
        join public.establishments e on e.id = i.establishment_id
        join public.officers o on o.id = i.inspector_id
        order by i.scheduled_date desc, i.code
        limit 8
      ) r
    ), '[]'::jsonb)
  );
end;
$$;

-- ---------------------------------------------------------------------------
-- Only the four app-facing functions are callable with the public key
-- ---------------------------------------------------------------------------
revoke all on function public.officer_login(text, text, text)  from public;
revoke all on function public.officer_logout(uuid)             from public;
revoke all on function public.inspector_dashboard(uuid)        from public;
revoke all on function public.authority_dashboard(uuid)        from public;
grant execute on function public.officer_login(text, text, text) to anon, authenticated;
grant execute on function public.officer_logout(uuid)            to anon, authenticated;
grant execute on function public.inspector_dashboard(uuid)       to anon, authenticated;
grant execute on function public.authority_dashboard(uuid)       to anon, authenticated;
