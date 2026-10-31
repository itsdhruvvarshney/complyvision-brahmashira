-- ComplyVision: Supreme Authority module (case assignment, scheduling, track record,
-- inspector management, live reports) + realistic demo data.
--
-- Run in the Supabase SQL editor AFTER schema.sql. Safe to re-run (idempotent).
--
-- What this adds
--   * Cases submitted from the Legal Metrology WEBSITE portal land in public.inspections with
--     status 'Pending' and an authority_id (the Supreme Authority they are routed to).
--   * The authority assigns a case to one of the inspectors that report to them (reports_to),
--     schedules it, reviews the inspector's findings and closes it as Passed / Failed.
--   * Every step is written to public.inspection_events - the complete track record shown to the
--     authority and to the assigned inspector.
--   * Inspectors can be placed 'Under Detention' (no new cases) or 'Suspended' (login blocked,
--     open cases return to the pending queue); every action is kept in public.officer_actions.
--   * Case status flow: Pending -> Assigned -> Scheduled -> In Progress -> Under Review -> Passed | Failed
--
-- The website (server side, service_role key) can create cases with:
--   select public.portal_submit_case('SA-1001', 'Sri Lakshmi Stores', 'Hyderabad', 'Telangana', ...);
-- The mobile app only ever calls the SECURITY DEFINER functions below with a session token.
--
-- Demo logins created by the seed (only if the officer IDs do not exist yet)
--   Supreme Authority  SA-1001  "Dr. Rajesh Khanna"  (created only if you have no authority yet)
--   Supreme Authority  SA-1002  "Vikram Sethi"
--   Inspectors         MI-2001 ... MI-2024  (names are listed in the seed block below)
--   Password for every seeded officer: Welcome@123
-- If you already had a Supreme Authority account, ALL demo data is attached to the oldest one, so
-- your existing login simply shows the populated dashboard.

-- ===========================================================================
-- 1. Officers: hierarchy + disciplinary status
-- ===========================================================================
alter table public.officers
  add column if not exists reports_to        uuid references public.officers (id) on delete set null,
  add column if not exists account_status    text not null default 'Active',
  add column if not exists status_reason     text,
  add column if not exists status_changed_at timestamptz,
  add column if not exists status_changed_by uuid references public.officers (id) on delete set null,
  add column if not exists employee_since    date,
  add column if not exists specialization    text;

update public.officers set account_status = 'Suspended' where not is_active and account_status = 'Active';

alter table public.officers drop constraint if exists officers_account_status_check;
alter table public.officers add constraint officers_account_status_check
  check (account_status in ('Active', 'Under Detention', 'Suspended'));
create index if not exists officers_reports_to_idx on public.officers (reports_to);

create table if not exists public.officer_actions (
  id           bigint generated always as identity primary key,
  officer_uuid uuid not null references public.officers (id) on delete cascade,
  action       text not null check (action in ('Detained', 'Suspended', 'Reinstated')),
  reason       text,
  performed_by uuid references public.officers (id) on delete set null,
  created_at   timestamptz not null default now()
);
create index if not exists officer_actions_officer_idx on public.officer_actions (officer_uuid, created_at desc);

-- ===========================================================================
-- 2. Inspections: product details, routing, schedule, timestamps
-- ===========================================================================
alter table public.inspections
  alter column inspector_id drop not null,
  alter column scheduled_date drop not null,
  alter column status set default 'Pending';

alter table public.inspections drop constraint if exists inspections_status_check;
update public.inspections set status = 'Passed' where status = 'Completed';
update public.inspections set status = 'Failed' where status = 'Violation Found';
alter table public.inspections add constraint inspections_status_check
  check (status in ('Pending', 'Assigned', 'Scheduled', 'In Progress', 'Under Review', 'Passed', 'Failed'));

alter table public.inspections
  add column if not exists authority_id         uuid references public.officers (id) on delete set null,
  add column if not exists product_name         text,
  add column if not exists brand                text,
  add column if not exists category             text,
  add column if not exists manufacturer         text,
  add column if not exists batch_no             text,
  add column if not exists mrp                  numeric(10, 2),
  add column if not exists priority             text not null default 'Medium',
  add column if not exists source               text not null default 'Website Portal',
  add column if not exists complaint            text,
  add column if not exists submitted_at         timestamptz not null default now(),
  add column if not exists assigned_at          timestamptz,
  add column if not exists scheduled_time       time,
  add column if not exists due_date             date,
  add column if not exists started_at           timestamptz,
  add column if not exists review_submitted_at  timestamptz,
  add column if not exists violation_types      text[] not null default '{}';

alter table public.inspections add column if not exists result_at timestamptz
  generated always as (coalesce(completed_at, review_submitted_at, submitted_at)) stored;

alter table public.inspections drop constraint if exists inspections_priority_check;
alter table public.inspections add constraint inspections_priority_check
  check (priority in ('Critical', 'High', 'Medium', 'Low'));

create index if not exists inspections_authority_status_idx on public.inspections (authority_id, status);
create index if not exists inspections_result_idx on public.inspections (authority_id, result_at);

create table if not exists public.inspection_events (
  id            bigint generated always as identity primary key,
  inspection_id uuid not null references public.inspections (id) on delete cascade,
  event_type    text not null,
  title         text not null,
  detail        text,
  actor_name    text,
  actor_role    text,
  created_at    timestamptz not null default now()
);
create index if not exists inspection_events_inspection_idx on public.inspection_events (inspection_id, created_at);

create sequence if not exists public.inspection_code_seq start 10000;

alter table public.officer_actions    enable row level security;
alter table public.inspection_events  enable row level security;
revoke all on public.officer_actions, public.inspection_events from anon, authenticated;
revoke all on sequence public.inspection_code_seq from anon, authenticated;

-- ===========================================================================
-- 3. Internal helpers
-- ===========================================================================
create or replace function public._log_event(
  p_inspection uuid, p_type text, p_title text, p_detail text, p_actor uuid, p_role text
) returns void
language sql
security definer
set search_path = public, extensions
as $$
  insert into public.inspection_events (inspection_id, event_type, title, detail, actor_name, actor_role)
  values (p_inspection, p_type, p_title, p_detail, (select full_name from public.officers where id = p_actor), p_role);
$$;
revoke all on function public._log_event(uuid, text, text, text, uuid, text) from public, anon, authenticated;

-- ===========================================================================
-- 4. Supreme Authority: dashboard (period aware)
-- ===========================================================================
drop function if exists public.authority_dashboard(uuid);

create or replace function public.authority_dashboard(p_token uuid, p_days integer default 30)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me        uuid;
  v_dash      text;
  v_days      integer := greatest(coalesce(p_days, 30), 0);
  v_span      integer;
  v_from      timestamptz;
  v_prev_from timestamptz;
  v_cur       record;
  v_prev      record;
  v_rate      numeric := 0;
  v_prev_rate numeric;
  v_series    jsonb;
  v_perf      jsonb;
  v_queue     record;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'AUTHORITY' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  v_span := case when v_days = 0 then 180 else v_days end;
  v_from := case when v_days = 0 then '-infinity'::timestamptz else now() - make_interval(days => v_days) end;
  v_prev_from := case when v_days = 0 then null else now() - make_interval(days => v_days * 2) end;

  select count(*) as total,
         count(*) filter (where status = 'Passed')       as passed,
         count(*) filter (where status = 'Failed')       as failed,
         count(*) filter (where status = 'Under Review') as review
    into v_cur
    from public.inspections
   where authority_id = v_me and status in ('Passed', 'Failed', 'Under Review') and result_at >= v_from;

  if v_cur.passed + v_cur.failed > 0 then
    v_rate := round(100.0 * v_cur.passed / (v_cur.passed + v_cur.failed), 1);
  end if;

  if v_prev_from is not null then
    select count(*) as total, count(*) filter (where status = 'Passed') as passed
      into v_prev
      from public.inspections
     where authority_id = v_me and status in ('Passed', 'Failed')
       and result_at >= v_prev_from and result_at < v_from;
    if v_prev.total > 0 then v_prev_rate := round(100.0 * v_prev.passed / v_prev.total, 1); end if;
  end if;

  -- 8-point pass-rate series across the selected window (last 180 days for "all time")
  select coalesce(jsonb_agg(jsonb_build_object('rate', coalesce(x.rate, 0), 'total', coalesce(x.total, 0)) order by g.k), '[]'::jsonb)
    into v_series
    from generate_series(0, 7) as g(k)
    left join (
      select t.b,
             count(*) as total,
             round(100.0 * count(*) filter (where t.status = 'Passed') / nullif(count(*), 0), 1) as rate
        from (
          select status,
                 least(7, floor(extract(epoch from (result_at - (now() - make_interval(days => v_span))))
                                / (v_span * 86400.0 / 8)))::int as b
            from public.inspections
           where authority_id = v_me and status in ('Passed', 'Failed')
             and result_at >= now() - make_interval(days => v_span)
        ) t
       group by t.b
    ) x on x.b = g.k;

  select coalesce(jsonb_agg(jsonb_build_object(
           'officer_id', o.officer_id,
           'name', o.full_name,
           'office', o.office,
           'status', o.account_status,
           'total', c.total, 'passed', c.passed, 'failed', c.failed, 'review', c.review,
           'pass_rate', case when c.total = 0 then 0 else round(100.0 * c.passed / c.total, 1) end
         ) order by c.total desc, o.full_name), '[]'::jsonb)
    into v_perf
    from public.officers o
    cross join lateral (
      select count(*) as total,
             count(*) filter (where i.status = 'Passed')       as passed,
             count(*) filter (where i.status = 'Failed')       as failed,
             count(*) filter (where i.status = 'Under Review') as review
        from public.inspections i
       where i.inspector_id = o.id and i.status in ('Passed', 'Failed', 'Under Review') and i.result_at >= v_from
    ) c
   where o.reports_to = v_me;

  select count(*) filter (where status = 'Pending')       as pending,
         count(*) filter (where status = 'Under Review')  as review,
         count(*) filter (where status in ('Pending', 'Assigned', 'Scheduled', 'In Progress') and due_date < current_date) as overdue
    into v_queue
    from public.inspections where authority_id = v_me;

  return jsonb_build_object(
    'ok', true,
    'days', v_days,
    'generated_at', now(),
    'stats', jsonb_build_object(
      'inspectors_total',  (select count(*) from public.officers where reports_to = v_me),
      'inspectors_active', (select count(*) from public.officers where reports_to = v_me and account_status = 'Active'),
      'total',  v_cur.total,
      'passed', v_cur.passed,
      'failed', v_cur.failed,
      'review', v_cur.review
    ),
    'trend', jsonb_build_object(
      'rate', v_rate,
      'previous_rate', v_prev_rate,
      'delta', case when v_prev_rate is null or v_cur.passed + v_cur.failed = 0 then null else round(v_rate - v_prev_rate, 1) end,
      'series', v_series
    ),
    'queue', jsonb_build_object('pending', v_queue.pending, 'review', v_queue.review, 'overdue', v_queue.overdue),
    'inspectors', v_perf
  );
end;
$$;

-- ===========================================================================
-- 5. Supreme Authority: all cases routed to me
-- ===========================================================================
create or replace function public.authority_inspections(p_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me   uuid;
  v_dash text;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'AUTHORITY' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  return jsonb_build_object(
    'ok', true,
    'counts', (
      select jsonb_build_object(
        'total',       count(*),
        'pending',     count(*) filter (where status = 'Pending'),
        'assigned',    count(*) filter (where status = 'Assigned'),
        'scheduled',   count(*) filter (where status = 'Scheduled'),
        'in_progress', count(*) filter (where status = 'In Progress'),
        'review',      count(*) filter (where status = 'Under Review'),
        'passed',      count(*) filter (where status = 'Passed'),
        'failed',      count(*) filter (where status = 'Failed'),
        'overdue',     count(*) filter (where status in ('Pending', 'Assigned', 'Scheduled', 'In Progress') and due_date < current_date)
      )
      from public.inspections where authority_id = v_me
    ),
    'items', coalesce((
      select jsonb_agg(r.j order by r.ord, r.pr, r.submitted desc)
      from (
        select i.submitted_at as submitted,
               case i.status when 'Pending' then 0 when 'Under Review' then 1 when 'Assigned' then 2
                             when 'Scheduled' then 3 when 'In Progress' then 4 else 5 end as ord,
               case i.priority when 'Critical' then 0 when 'High' then 1 when 'Medium' then 2 else 3 end as pr,
               jsonb_build_object(
                 'code', i.code,
                 'status', i.status,
                 'priority', i.priority,
                 'product', coalesce(i.product_name, i.commodity),
                 'brand', i.brand,
                 'category', coalesce(i.category, i.commodity),
                 'batch_no', i.batch_no,
                 'mrp', i.mrp,
                 'establishment', e.name,
                 'city', e.city,
                 'state', e.state,
                 'inspector', o.full_name,
                 'inspector_id', o.officer_id,
                 'type', i.inspection_type,
                 'source', i.source,
                 'submitted_at', i.submitted_at,
                 'assigned_at', i.assigned_at,
                 'scheduled_date', i.scheduled_date,
                 'scheduled_time', to_char(i.scheduled_time, 'HH24:MI'),
                 'due_date', i.due_date,
                 'violations', i.violations_count
               ) as j
          from public.inspections i
          join public.establishments e on e.id = i.establishment_id
          left join public.officers o on o.id = i.inspector_id
         where i.authority_id = v_me
         order by 2, 3, i.submitted_at desc
         limit 800
      ) r
    ), '[]'::jsonb)
  );
end;
$$;

-- ===========================================================================
-- 6. One case with its full track record (authority: own cases; inspector: assigned cases)
-- ===========================================================================
create or replace function public.inspection_detail(p_token uuid, p_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me   uuid;
  v_dash text;
  i      public.inspections%rowtype;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;

  select * into i from public.inspections where code = btrim(coalesce(p_code, ''));
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;

  if v_dash = 'AUTHORITY' and i.authority_id is distinct from v_me then
    return jsonb_build_object('ok', false, 'code', 'forbidden');
  end if;
  if v_dash = 'INSPECTOR' and i.inspector_id is distinct from v_me then
    return jsonb_build_object('ok', false, 'code', 'forbidden');
  end if;

  return jsonb_build_object(
    'ok', true,
    'inspection', (
      select jsonb_build_object(
        'code', i.code,
        'status', i.status,
        'priority', i.priority,
        'product', coalesce(i.product_name, i.commodity),
        'brand', i.brand,
        'category', coalesce(i.category, i.commodity),
        'manufacturer', i.manufacturer,
        'batch_no', i.batch_no,
        'mrp', i.mrp,
        'establishment', e.name,
        'address', e.address,
        'city', e.city,
        'state', e.state,
        'establishment_type', e.category,
        'type', i.inspection_type,
        'source', i.source,
        'complaint', i.complaint,
        'remarks', i.remarks,
        'submitted_at', i.submitted_at,
        'assigned_at', i.assigned_at,
        'scheduled_date', i.scheduled_date,
        'scheduled_time', to_char(i.scheduled_time, 'HH24:MI'),
        'due_date', i.due_date,
        'started_at', i.started_at,
        'review_submitted_at', i.review_submitted_at,
        'completed_at', i.completed_at,
        'violations', i.violations_count,
        'violation_types', to_jsonb(i.violation_types),
        'authority', (select full_name from public.officers where id = i.authority_id)
      )
      from public.establishments e where e.id = i.establishment_id
    ),
    'inspector', (
      select jsonb_build_object(
        'officer_id', o.officer_id, 'name', o.full_name, 'office', o.office,
        'phone', o.phone, 'email', o.email, 'status', o.account_status)
      from public.officers o where o.id = i.inspector_id
    ),
    'events', coalesce((
      select jsonb_agg(jsonb_build_object(
               'type', ev.event_type, 'title', ev.title, 'detail', ev.detail,
               'actor', ev.actor_name, 'role', ev.actor_role, 'at', ev.created_at
             ) order by ev.created_at, ev.id)
      from public.inspection_events ev where ev.inspection_id = i.id
    ), '[]'::jsonb)
  );
end;
$$;

-- ===========================================================================
-- 7. Authority actions on a case: assign / schedule / review
-- ===========================================================================
create or replace function public.authority_assign_inspection(
  p_token uuid, p_code text, p_inspector text,
  p_date date default null, p_time text default null, p_note text default null
) returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me     uuid;
  v_dash   text;
  i        public.inspections%rowtype;
  o        public.officers%rowtype;
  v_time   time;
  v_date   date;
  v_new    text;
  v_note   text := nullif(btrim(coalesce(p_note, '')), '');
  v_re     boolean;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'AUTHORITY' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  select * into i from public.inspections where code = btrim(coalesce(p_code, '')) and authority_id = v_me for update;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  if i.status not in ('Pending', 'Assigned', 'Scheduled') then
    return jsonb_build_object('ok', false, 'code', 'bad_state');
  end if;

  select * into o from public.officers
   where officer_id = upper(btrim(coalesce(p_inspector, ''))) and reports_to = v_me;
  if not found then return jsonb_build_object('ok', false, 'code', 'invalid_inspector'); end if;
  if o.account_status <> 'Active' or not o.is_active then
    return jsonb_build_object('ok', false, 'code', 'inspector_unavailable');
  end if;

  if p_date is not null and p_date < current_date then
    return jsonb_build_object('ok', false, 'code', 'bad_date');
  end if;
  begin
    v_time := nullif(btrim(coalesce(p_time, '')), '')::time;
  exception when others then
    return jsonb_build_object('ok', false, 'code', 'bad_time');
  end;

  -- Keep an existing future schedule when the authority only changes the inspector
  v_date := coalesce(p_date, case when i.scheduled_date >= current_date then i.scheduled_date end);
  if p_date is null then
    v_time := case when v_date is not null then coalesce(v_time, i.scheduled_time) else null end;
  end if;
  v_new := case when v_date is not null then 'Scheduled' else 'Assigned' end;
  v_re  := i.inspector_id is not null and i.inspector_id <> o.id;

  update public.inspections
     set inspector_id = o.id, status = v_new, assigned_at = now(),
         scheduled_date = v_date, scheduled_time = v_time
   where id = i.id;

  perform public._log_event(
    i.id, case when v_re then 'Reassigned' else 'Assigned' end,
    case when v_re then 'Reassigned to inspector' else 'Assigned to inspector' end,
    o.full_name || ' (' || o.officer_id || ')' || coalesce(' - ' || v_note, ''),
    v_me, 'Supreme Authority');

  if v_date is not null then
    perform public._log_event(
      i.id, 'Scheduled', 'Inspection scheduled',
      to_char(v_date, 'DD Mon YYYY') || coalesce(' at ' || to_char(v_time, 'HH24:MI'), ''),
      v_me, 'Supreme Authority');
  end if;

  return jsonb_build_object('ok', true, 'code', 'ok', 'status', v_new);
end;
$$;

create or replace function public.authority_schedule_inspection(
  p_token uuid, p_code text, p_date date, p_time text default null, p_note text default null
) returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me   uuid;
  v_dash text;
  i      public.inspections%rowtype;
  v_time time;
  v_note text := nullif(btrim(coalesce(p_note, '')), '');
  v_re   boolean;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'AUTHORITY' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  select * into i from public.inspections where code = btrim(coalesce(p_code, '')) and authority_id = v_me for update;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  if i.inspector_id is null or i.status not in ('Assigned', 'Scheduled') then
    return jsonb_build_object('ok', false, 'code', 'bad_state');
  end if;
  if p_date is null or p_date < current_date then
    return jsonb_build_object('ok', false, 'code', 'bad_date');
  end if;
  begin
    v_time := nullif(btrim(coalesce(p_time, '')), '')::time;
  exception when others then
    return jsonb_build_object('ok', false, 'code', 'bad_time');
  end;

  v_re := i.scheduled_date is not null;
  update public.inspections set status = 'Scheduled', scheduled_date = p_date, scheduled_time = v_time where id = i.id;

  perform public._log_event(
    i.id, case when v_re then 'Rescheduled' else 'Scheduled' end,
    case when v_re then 'Inspection rescheduled' else 'Inspection scheduled' end,
    to_char(p_date, 'DD Mon YYYY') || coalesce(' at ' || to_char(v_time, 'HH24:MI'), '') || coalesce(' - ' || v_note, ''),
    v_me, 'Supreme Authority');

  return jsonb_build_object('ok', true, 'code', 'ok');
end;
$$;

-- verdict: 'Passed' | 'Failed' | 'Reinspect'
create or replace function public.authority_review_inspection(
  p_token uuid, p_code text, p_verdict text, p_remarks text default null
) returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me   uuid;
  v_dash text;
  i      public.inspections%rowtype;
  v_note text := nullif(btrim(coalesce(p_remarks, '')), '');
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'AUTHORITY' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;
  if p_verdict not in ('Passed', 'Failed', 'Reinspect') then
    return jsonb_build_object('ok', false, 'code', 'bad_verdict');
  end if;

  select * into i from public.inspections where code = btrim(coalesce(p_code, '')) and authority_id = v_me for update;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  if i.status <> 'Under Review' then return jsonb_build_object('ok', false, 'code', 'bad_state'); end if;

  if p_verdict = 'Reinspect' then
    update public.inspections
       set status = 'Assigned', scheduled_date = null, scheduled_time = null, started_at = null,
           review_submitted_at = null, assigned_at = now()
     where id = i.id;
    perform public._log_event(i.id, 'Returned', 'Sent back for re-inspection', v_note, v_me, 'Supreme Authority');
  else
    update public.inspections
       set status = p_verdict, completed_at = now(), remarks = coalesce(v_note, remarks)
     where id = i.id;
    perform public._log_event(
      i.id, 'Result',
      case when p_verdict = 'Passed' then 'Inspection passed'
           else 'Inspection failed - ' || i.violations_count || ' violation(s)' end,
      coalesce(v_note, i.remarks), v_me, 'Supreme Authority');
  end if;

  return jsonb_build_object('ok', true, 'code', 'ok');
end;
$$;

-- ===========================================================================
-- 8. Inspector actions on their own case: start / submit for review
-- ===========================================================================
create or replace function public.inspector_update_inspection(
  p_token uuid, p_code text, p_action text,
  p_violations integer default 0, p_types text[] default '{}', p_remarks text default null
) returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me   uuid;
  v_dash text;
  i      public.inspections%rowtype;
  v_note text := nullif(btrim(coalesce(p_remarks, '')), '');
  v_viol integer := greatest(0, least(coalesce(p_violations, 0), 50));
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  select * into i from public.inspections where code = btrim(coalesce(p_code, '')) and inspector_id = v_me for update;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;

  if p_action = 'start' then
    if i.status not in ('Assigned', 'Scheduled') then return jsonb_build_object('ok', false, 'code', 'bad_state'); end if;
    if i.scheduled_date is not null and i.scheduled_date > current_date then
      return jsonb_build_object('ok', false, 'code', 'too_early');
    end if;
    update public.inspections set status = 'In Progress', started_at = now() where id = i.id;
    perform public._log_event(i.id, 'Started', 'Inspection started', null, v_me, 'Inspector');

  elsif p_action = 'submit' then
    if i.status <> 'In Progress' then return jsonb_build_object('ok', false, 'code', 'bad_state'); end if;
    update public.inspections
       set status = 'Under Review', review_submitted_at = now(), violations_count = v_viol,
           violation_types = coalesce(p_types, '{}'), remarks = coalesce(v_note, remarks)
     where id = i.id;
    perform public._log_event(
      i.id, 'Review', 'Submitted for review',
      v_viol || ' violation(s) recorded' || coalesce(' - ' || v_note, ''), v_me, 'Inspector');

  else
    return jsonb_build_object('ok', false, 'code', 'bad_action');
  end if;

  return jsonb_build_object('ok', true, 'code', 'ok');
end;
$$;

-- ===========================================================================
-- 9. Inspector dashboard (own cases, new statuses)
-- ===========================================================================
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
        'assigned',    count(*) filter (where status in ('Assigned', 'Scheduled')),
        'in_progress', count(*) filter (where status = 'In Progress'),
        'in_review',   count(*) filter (where status = 'Under Review'),
        'completed',   count(*) filter (where status in ('Passed', 'Failed')),
        'violations',  coalesce(sum(violations_count), 0)
      )
      from public.inspections where inspector_id = v_officer
    ),
    'inspections', coalesce((
      select jsonb_agg(r.j order by r.done, r.sched nulls last, r.code)
      from (
        select (i.status in ('Passed', 'Failed')) as done, i.scheduled_date as sched, i.code,
               jsonb_build_object(
                 'code', i.code,
                 'establishment', e.name,
                 'city', e.city,
                 'commodity', i.commodity,
                 'product', coalesce(i.product_name, i.commodity),
                 'priority', i.priority,
                 'type', i.inspection_type,
                 'status', i.status,
                 'scheduled_date', i.scheduled_date,
                 'scheduled_time', to_char(i.scheduled_time, 'HH24:MI'),
                 'violations', i.violations_count
               ) as j
          from public.inspections i
          join public.establishments e on e.id = i.establishment_id
         where i.inspector_id = v_officer
         order by 1, 2 nulls last, 3
         limit 300
      ) r
    ), '[]'::jsonb)
  );
end;
$$;

-- ===========================================================================
-- 10. Supreme Authority: inspectors (list, detail, detain / suspend / reinstate)
-- ===========================================================================
create or replace function public.authority_inspectors(p_token uuid)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me   uuid;
  v_dash text;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'AUTHORITY' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  return jsonb_build_object(
    'ok', true,
    'inspectors', coalesce((
      select jsonb_agg(jsonb_build_object(
               'officer_id', o.officer_id,
               'name', o.full_name,
               'office', o.office,
               'email', o.email,
               'phone', o.phone,
               'status', o.account_status,
               'status_reason', o.status_reason,
               'status_changed_at', o.status_changed_at,
               'last_login_at', o.last_login_at,
               'employee_since', o.employee_since,
               'specialization', o.specialization,
               'total',  c.total,
               'passed', c.passed,
               'failed', c.failed,
               'review', c.review,
               'open',   c.open,
               'violations', c.violations,
               'avg_days', c.avg_days,
               'pass_rate', case when c.total = 0 then 0 else round(100.0 * c.passed / c.total, 1) end
             ) order by o.full_name)
      from public.officers o
      cross join lateral (
        select count(*) filter (where i.status in ('Passed', 'Failed', 'Under Review')) as total,
               count(*) filter (where i.status = 'Passed')       as passed,
               count(*) filter (where i.status = 'Failed')       as failed,
               count(*) filter (where i.status = 'Under Review') as review,
               count(*) filter (where i.status in ('Assigned', 'Scheduled', 'In Progress')) as open,
               coalesce(sum(i.violations_count), 0)              as violations,
               round((avg(extract(epoch from (i.completed_at - i.assigned_at)) / 86400.0)
                      filter (where i.status in ('Passed', 'Failed') and i.completed_at is not null and i.assigned_at is not null))::numeric, 1) as avg_days
          from public.inspections i where i.inspector_id = o.id
      ) c
      where o.reports_to = v_me
    ), '[]'::jsonb)
  );
end;
$$;

create or replace function public.authority_inspector_detail(p_token uuid, p_officer_id text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me   uuid;
  v_dash text;
  o      public.officers%rowtype;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'AUTHORITY' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  select * into o from public.officers where officer_id = upper(btrim(coalesce(p_officer_id, ''))) and reports_to = v_me;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;

  return jsonb_build_object(
    'ok', true,
    'monthly', coalesce((
      select jsonb_agg(jsonb_build_object(
               'label', to_char(m.m, 'Mon'),
               'total', coalesce(x.total, 0),
               'rate',  coalesce(x.rate, 0)
             ) order by m.m)
      from generate_series(date_trunc('month', now()) - interval '5 months', date_trunc('month', now()), interval '1 month') as m(m)
      left join (
        select date_trunc('month', result_at) as mm,
               count(*) as total,
               round(100.0 * count(*) filter (where status = 'Passed') / nullif(count(*), 0), 1) as rate
          from public.inspections
         where inspector_id = o.id and status in ('Passed', 'Failed', 'Under Review')
         group by 1
      ) x on x.mm = m.m
    ), '[]'::jsonb),
    'open_cases', coalesce((
      select jsonb_agg(jsonb_build_object(
               'code', r.code, 'status', r.status, 'priority', r.priority, 'product', r.product,
               'establishment', r.establishment, 'city', r.city,
               'scheduled_date', r.scheduled_date, 'scheduled_time', r.stime
             ) order by r.scheduled_date nulls last, r.code)
      from (
        select i.code, i.status, i.priority, coalesce(i.product_name, i.commodity) as product,
               e.name as establishment, e.city, i.scheduled_date, to_char(i.scheduled_time, 'HH24:MI') as stime
          from public.inspections i join public.establishments e on e.id = i.establishment_id
         where i.inspector_id = o.id and i.status in ('Assigned', 'Scheduled', 'In Progress', 'Under Review')
         order by i.scheduled_date nulls last, i.code
         limit 25
      ) r
    ), '[]'::jsonb),
    'recent', coalesce((
      select jsonb_agg(jsonb_build_object(
               'code', r.code, 'status', r.status, 'priority', r.priority, 'product', r.product,
               'establishment', r.establishment, 'city', r.city, 'violations', r.violations,
               'completed_at', r.completed_at
             ) order by r.completed_at desc)
      from (
        select i.code, i.status, i.priority, coalesce(i.product_name, i.commodity) as product,
               e.name as establishment, e.city, i.violations_count as violations, i.completed_at
          from public.inspections i join public.establishments e on e.id = i.establishment_id
         where i.inspector_id = o.id and i.status in ('Passed', 'Failed')
         order by i.completed_at desc nulls last
         limit 8
      ) r
    ), '[]'::jsonb),
    'actions', coalesce((
      select jsonb_agg(jsonb_build_object(
               'action', a.action, 'reason', a.reason, 'by', p.full_name, 'at', a.created_at
             ) order by a.created_at desc)
      from public.officer_actions a
      left join public.officers p on p.id = a.performed_by
      where a.officer_uuid = o.id
    ), '[]'::jsonb)
  );
end;
$$;

-- p_status: 'Active' (reinstate) | 'Under Detention' | 'Suspended'
create or replace function public.authority_set_inspector_status(
  p_token uuid, p_officer_id text, p_status text, p_reason text default null
) returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me       uuid;
  v_dash     text;
  o          public.officers%rowtype;
  v_reason   text := nullif(btrim(coalesce(p_reason, '')), '');
  v_released integer := 0;
  c          record;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'AUTHORITY' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;
  if p_status not in ('Active', 'Under Detention', 'Suspended') then
    return jsonb_build_object('ok', false, 'code', 'bad_status');
  end if;

  select * into o from public.officers
   where officer_id = upper(btrim(coalesce(p_officer_id, ''))) and reports_to = v_me for update;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  if o.account_status = p_status then return jsonb_build_object('ok', false, 'code', 'no_change'); end if;
  if p_status <> 'Active' and (v_reason is null or char_length(v_reason) < 5) then
    return jsonb_build_object('ok', false, 'code', 'reason_required');
  end if;

  update public.officers
     set account_status = p_status,
         is_active = (p_status <> 'Suspended'),
         status_reason = case when p_status = 'Active' then null else v_reason end,
         status_changed_at = now(),
         status_changed_by = v_me
   where id = o.id;

  insert into public.officer_actions (officer_uuid, action, reason, performed_by)
  values (o.id,
          case p_status when 'Active' then 'Reinstated' when 'Under Detention' then 'Detained' else 'Suspended' end,
          v_reason, v_me);

  if p_status = 'Suspended' then
    update public.officer_sessions set revoked = true where officer_uuid = o.id and not revoked;

    -- Their unfinished cases go back to the pending queue so nothing is left stranded
    for c in
      select id from public.inspections
       where inspector_id = o.id and status in ('Assigned', 'Scheduled', 'In Progress') for update
    loop
      update public.inspections
         set inspector_id = null, status = 'Pending', assigned_at = null, scheduled_date = null,
             scheduled_time = null, started_at = null
       where id = c.id;
      perform public._log_event(
        c.id, 'Returned', 'Returned to pending queue',
        'Inspector ' || o.full_name || ' (' || o.officer_id || ') was suspended', v_me, 'Supreme Authority');
      v_released := v_released + 1;
    end loop;
  end if;

  return jsonb_build_object('ok', true, 'code', 'ok', 'released', v_released);
end;
$$;

-- ===========================================================================
-- 11. Supreme Authority: live report (period aware)
-- ===========================================================================
create or replace function public.authority_report(p_token uuid, p_days integer default 30)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me     uuid;
  v_dash   text;
  v_days   integer := greatest(coalesce(p_days, 30), 0);
  v_from   timestamptz;
  v_weeks  integer;
  v_cur    record;
  v_turn   numeric;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'AUTHORITY' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  v_from  := case when v_days = 0 then '-infinity'::timestamptz else now() - make_interval(days => v_days) end;
  v_weeks := case when v_days = 0 then 12 else greatest(4, least(12, ceil(v_days / 7.0)::int)) end;

  select count(*) as total,
         count(*) filter (where status = 'Passed')       as passed,
         count(*) filter (where status = 'Failed')       as failed,
         count(*) filter (where status = 'Under Review') as review,
         coalesce(sum(violations_count), 0)              as violations
    into v_cur
    from public.inspections
   where authority_id = v_me and status in ('Passed', 'Failed', 'Under Review') and result_at >= v_from;

  select round(avg(extract(epoch from (completed_at - submitted_at)) / 86400.0)::numeric, 1)
    into v_turn
    from public.inspections
   where authority_id = v_me and status in ('Passed', 'Failed') and completed_at is not null and completed_at >= v_from;

  return jsonb_build_object(
    'ok', true,
    'days', v_days,
    'generated_at', now(),
    'authority', (select jsonb_build_object('name', full_name, 'officer_id', officer_id, 'office', office, 'designation', d.name)
                    from public.officers o join public.designations d on d.id = o.designation_id where o.id = v_me),
    'summary', jsonb_build_object(
      'total', v_cur.total, 'passed', v_cur.passed, 'failed', v_cur.failed, 'review', v_cur.review,
      'violations', v_cur.violations,
      'pass_rate', case when v_cur.total = 0 then 0 else round(100.0 * v_cur.passed / v_cur.total, 1) end,
      'fail_rate', case when v_cur.total = 0 then 0 else round(100.0 * v_cur.failed / v_cur.total, 1) end,
      'avg_turnaround_days', v_turn
    ),
    'pipeline', (
      select jsonb_build_object(
        'pending',     count(*) filter (where status = 'Pending'),
        'assigned',    count(*) filter (where status = 'Assigned'),
        'scheduled',   count(*) filter (where status = 'Scheduled'),
        'in_progress', count(*) filter (where status = 'In Progress'),
        'review',      count(*) filter (where status = 'Under Review'),
        'overdue',     count(*) filter (where status in ('Pending', 'Assigned', 'Scheduled', 'In Progress') and due_date < current_date),
        'pending_critical', count(*) filter (where status = 'Pending' and priority = 'Critical'),
        'pending_high',     count(*) filter (where status = 'Pending' and priority = 'High'),
        'pending_medium',   count(*) filter (where status = 'Pending' and priority = 'Medium'),
        'pending_low',      count(*) filter (where status = 'Pending' and priority = 'Low')
      )
      from public.inspections where authority_id = v_me
    ),
    'workforce', (
      select jsonb_build_object(
        'total',     count(*),
        'active',    count(*) filter (where account_status = 'Active'),
        'detained',  count(*) filter (where account_status = 'Under Detention'),
        'suspended', count(*) filter (where account_status = 'Suspended')
      )
      from public.officers where reports_to = v_me
    ),
    'weekly', coalesce((
      select jsonb_agg(jsonb_build_object(
               'label', to_char(w.ws, 'DD Mon'),
               'passed', coalesce(x.passed, 0), 'failed', coalesce(x.failed, 0), 'review', coalesce(x.review, 0)
             ) order by w.ws)
      from (
        select date_trunc('week', now()) - make_interval(days => 7 * (v_weeks - 1 - k)) as ws
          from generate_series(0, v_weeks - 1) k
      ) w
      left join (
        select date_trunc('week', result_at) as ws,
               count(*) filter (where status = 'Passed')       as passed,
               count(*) filter (where status = 'Failed')       as failed,
               count(*) filter (where status = 'Under Review') as review
          from public.inspections
         where authority_id = v_me and status in ('Passed', 'Failed', 'Under Review')
         group by 1
      ) x on x.ws = w.ws
    ), '[]'::jsonb),
    'categories', coalesce((
      select jsonb_agg(jsonb_build_object(
               'name', r.name, 'total', r.total, 'passed', r.passed, 'failed', r.failed,
               'pass_rate', round(100.0 * r.passed / nullif(r.total, 0), 1)
             ) order by r.total desc, r.name)
      from (
        select coalesce(category, commodity) as name, count(*) as total,
               count(*) filter (where status = 'Passed') as passed,
               count(*) filter (where status = 'Failed') as failed
          from public.inspections
         where authority_id = v_me and status in ('Passed', 'Failed', 'Under Review') and result_at >= v_from
         group by 1 order by 2 desc, 1 limit 8
      ) r
    ), '[]'::jsonb),
    'states', coalesce((
      select jsonb_agg(jsonb_build_object(
               'name', r.name, 'total', r.total, 'passed', r.passed, 'failed', r.failed,
               'pass_rate', round(100.0 * r.passed / nullif(r.total, 0), 1)
             ) order by r.total desc, r.name)
      from (
        select e.state as name, count(*) as total,
               count(*) filter (where i.status = 'Passed') as passed,
               count(*) filter (where i.status = 'Failed') as failed
          from public.inspections i join public.establishments e on e.id = i.establishment_id
         where i.authority_id = v_me and i.status in ('Passed', 'Failed', 'Under Review') and i.result_at >= v_from
         group by 1 order by 2 desc, 1 limit 8
      ) r
    ), '[]'::jsonb),
    'violations', coalesce((
      select jsonb_agg(jsonb_build_object('name', r.t, 'count', r.n) order by r.n desc, r.t)
      from (
        select vt as t, count(*) as n
          from public.inspections i, unnest(i.violation_types) as vt
         where i.authority_id = v_me and i.status in ('Passed', 'Failed', 'Under Review') and i.result_at >= v_from
         group by vt order by 2 desc, 1 limit 8
      ) r
    ), '[]'::jsonb),
    'inspectors', coalesce((
      select jsonb_agg(jsonb_build_object(
               'officer_id', r.officer_id, 'name', r.name, 'status', r.status,
               'total', r.total, 'passed', r.passed, 'failed', r.failed, 'review', r.review,
               'pass_rate', round(100.0 * r.passed / nullif(r.total, 0), 1)
             ) order by r.total desc, r.name)
      from (
        select o.officer_id, o.full_name as name, o.account_status as status,
               count(*) as total,
               count(*) filter (where i.status = 'Passed')       as passed,
               count(*) filter (where i.status = 'Failed')       as failed,
               count(*) filter (where i.status = 'Under Review') as review
          from public.officers o
          join public.inspections i on i.inspector_id = o.id
         where o.reports_to = v_me and i.status in ('Passed', 'Failed', 'Under Review') and i.result_at >= v_from
         group by o.officer_id, o.full_name, o.account_status
         order by 4 desc, 2 limit 15
      ) r
    ), '[]'::jsonb)
  );
end;
$$;

-- ===========================================================================
-- 12. Website bridge: the Legal Metrology portal creates cases through this (service_role only)
-- ===========================================================================
create or replace function public.portal_submit_case(
  p_authority_officer_id text,
  p_establishment text, p_city text, p_state text,
  p_product_name text, p_brand text default null, p_category text default null,
  p_manufacturer text default null, p_batch_no text default null, p_mrp numeric default null,
  p_priority text default 'Medium', p_inspection_type text default 'Complaint-Based',
  p_complaint text default null, p_address text default null
) returns text
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_auth uuid;
  v_est  uuid;
  v_id   uuid;
  v_code text;
  v_prio text := case when p_priority in ('Critical', 'High', 'Medium', 'Low') then p_priority else 'Medium' end;
begin
  select o.id into v_auth from public.officers o
    join public.designations d on d.id = o.designation_id
   where o.officer_id = upper(btrim(p_authority_officer_id)) and d.dashboard = 'AUTHORITY';
  if v_auth is null then raise exception 'Unknown Supreme Authority %', p_authority_officer_id; end if;

  select id into v_est from public.establishments where name = btrim(p_establishment);
  if v_est is null then
    insert into public.establishments (name, address, city, state, category)
    values (btrim(p_establishment), p_address, btrim(p_city), btrim(p_state), 'Retail')
    returning id into v_est;
  end if;

  v_code := 'CV-' || to_char(now(), 'YY') || '-' || lpad(nextval('public.inspection_code_seq')::text, 5, '0');

  insert into public.inspections (
    code, establishment_id, authority_id, commodity, inspection_type, status, complaint,
    product_name, brand, category, manufacturer, batch_no, mrp, priority, source, submitted_at, due_date)
  values (
    v_code, v_est, v_auth, coalesce(p_category, p_product_name), p_inspection_type, 'Pending', p_complaint,
    p_product_name, p_brand, coalesce(p_category, p_product_name), p_manufacturer, p_batch_no, p_mrp, v_prio,
    'Website Portal', now(),
    current_date + case v_prio when 'Critical' then 3 when 'High' then 7 when 'Medium' then 14 else 21 end)
  returning id into v_id;

  insert into public.inspection_events (inspection_id, event_type, title, detail, actor_name, actor_role)
  values
    (v_id, 'Submitted', 'Case submitted',
     'Filed through the Legal Metrology website for "' || p_product_name || '"', 'Legal Metrology Portal', 'System'),
    (v_id, 'Routed', 'Assigned to Supreme Authority',
     'Routed to ' || (select full_name from public.officers where id = v_auth) || ' for allocation', 'Legal Metrology Portal', 'System');

  return v_code;
end;
$$;

-- ===========================================================================
-- 13. Permissions: app-facing functions are callable with the public key (token protected)
-- ===========================================================================
revoke all on function public.authority_dashboard(uuid, integer)                          from public;
revoke all on function public.authority_inspections(uuid)                                 from public;
revoke all on function public.inspection_detail(uuid, text)                               from public;
revoke all on function public.authority_assign_inspection(uuid, text, text, date, text, text) from public;
revoke all on function public.authority_schedule_inspection(uuid, text, date, text, text) from public;
revoke all on function public.authority_review_inspection(uuid, text, text, text)         from public;
revoke all on function public.inspector_update_inspection(uuid, text, text, integer, text[], text) from public;
revoke all on function public.inspector_dashboard(uuid)                                   from public;
revoke all on function public.authority_inspectors(uuid)                                  from public;
revoke all on function public.authority_inspector_detail(uuid, text)                      from public;
revoke all on function public.authority_set_inspector_status(uuid, text, text, text)      from public;
revoke all on function public.authority_report(uuid, integer)                             from public;
revoke all on function public.portal_submit_case(text, text, text, text, text, text, text, text, text, numeric, text, text, text, text)
  from public, anon, authenticated;

grant execute on function public.authority_dashboard(uuid, integer)                          to anon, authenticated;
grant execute on function public.authority_inspections(uuid)                                 to anon, authenticated;
grant execute on function public.inspection_detail(uuid, text)                               to anon, authenticated;
grant execute on function public.authority_assign_inspection(uuid, text, text, date, text, text) to anon, authenticated;
grant execute on function public.authority_schedule_inspection(uuid, text, date, text, text) to anon, authenticated;
grant execute on function public.authority_review_inspection(uuid, text, text, text)         to anon, authenticated;
grant execute on function public.inspector_update_inspection(uuid, text, text, integer, text[], text) to anon, authenticated;
grant execute on function public.inspector_dashboard(uuid)                                   to anon, authenticated;
grant execute on function public.authority_inspectors(uuid)                                  to anon, authenticated;
grant execute on function public.authority_inspector_detail(uuid, text)                      to anon, authenticated;
grant execute on function public.authority_set_inspector_status(uuid, text, text, text)      to anon, authenticated;
grant execute on function public.authority_report(uuid, integer)                             to anon, authenticated;
grant execute on function public.portal_submit_case(text, text, text, text, text, text, text, text, text, numeric, text, text, text, text)
  to service_role;

-- ===========================================================================
-- 14. DEMO DATA (runs once; skipped when demo cases already exist)
-- ===========================================================================
do $seed$
declare
  c_password constant text := 'Welcome@123';
  v_auth_dsg  smallint;
  v_insp_dsg  smallint;
  v_auth1     uuid;
  v_auth2     uuid;
  v_hash      text := extensions.crypt(c_password, extensions.gen_salt('bf'));
  v_est       uuid[];
  v_all1      uuid[];
  v_act1      uuid[];
  v_all2      uuid[];
  v_act2      uuid[];
  r           record;
begin
  if exists (select 1 from public.inspections where code like 'CV-%') then
    raise notice 'Demo cases already present - seed skipped';
    return;
  end if;

  -- Designations --------------------------------------------------------
  insert into public.designations (id, name, dashboard)
  select coalesce((select max(id) from public.designations), 0) + 1, 'Supreme Authority', 'AUTHORITY'
   where not exists (select 1 from public.designations where dashboard = 'AUTHORITY');
  insert into public.designations (id, name, dashboard)
  select coalesce((select max(id) from public.designations), 0) + 1, 'Metrology Inspector', 'INSPECTOR'
   where not exists (select 1 from public.designations where dashboard = 'INSPECTOR');
  select id into v_auth_dsg from public.designations where dashboard = 'AUTHORITY' order by id limit 1;
  select id into v_insp_dsg from public.designations where dashboard = 'INSPECTOR' order by id limit 1;

  -- Supreme authorities ---------------------------------------------------
  select o.id into v_auth1 from public.officers o
   where o.designation_id = v_auth_dsg order by o.created_at, o.officer_id limit 1;
  if v_auth1 is null then
    insert into public.officers (officer_id, full_name, designation_id, password_hash, office, email, phone, employee_since)
    values ('SA-1001', 'Dr. Rajesh Khanna', v_auth_dsg, v_hash, 'Department of Consumer Affairs, New Delhi',
            'rajesh.khanna@lm-india.example', '+91 11 2338 0001', date '2009-07-01')
    returning id into v_auth1;
  end if;

  insert into public.officers (officer_id, full_name, designation_id, password_hash, office, email, phone, employee_since)
  values ('SA-1002', 'Vikram Sethi', v_auth_dsg, v_hash, 'Legal Metrology Directorate, Mumbai',
          'vikram.sethi@lm-india.example', '+91 22 2262 0002', date '2012-02-15')
  on conflict (officer_id) do nothing;
  select id into v_auth2 from public.officers where officer_id = 'SA-1002';

  -- Inspectors ------------------------------------------------------------
  insert into public.officers
    (officer_id, full_name, designation_id, password_hash, office, email, phone, employee_since,
     specialization, last_login_at, reports_to)
  select v.oid, v.nm, v_insp_dsg, v_hash, 'Legal Metrology Office, ' || v.city,
         lower(replace(v.nm, ' ', '.')) || '@lm-india.example',
         '+91 9' || lpad((abs(hashtext(v.oid))::bigint % 900000000 + 100000000)::text, 9, '0'),
         date '2010-01-01' + (abs(hashtext(v.oid || 'j'))::bigint % 4800)::int,
         (array['Weighing Instruments', 'Packaged Commodities', 'Fuel Dispensers', 'Retail & E-commerce', 'Food & Beverages'])
           [1 + (abs(hashtext(v.oid || 's'))::bigint % 5)::int],
         now() - make_interval(hours => (abs(hashtext(v.oid || 'l'))::bigint % 300)::int),
         case when v.grp = 1 then v_auth1 else v_auth2 end
    from (values
      ('MI-2001', 'Anil Rao',           'Hyderabad',          1),
      ('MI-2002', 'Priya Sharma',       'New Delhi',          1),
      ('MI-2003', 'Vivek Kumar',        'Patna',              1),
      ('MI-2004', 'Neha Dubey',         'Lucknow',            1),
      ('MI-2005', 'Rohit Singh',        'Jaipur',             1),
      ('MI-2006', 'Kavita Nair',        'Kochi',              1),
      ('MI-2007', 'Arjun Mehta',        'Mumbai',             1),
      ('MI-2008', 'Sunita Patil',       'Pune',               1),
      ('MI-2009', 'Imran Qureshi',      'Bhopal',             1),
      ('MI-2010', 'Deepa Iyer',         'Chennai',            1),
      ('MI-2011', 'Sanjay Gupta',       'Kanpur',             1),
      ('MI-2012', 'Meera Joshi',        'Ahmedabad',          1),
      ('MI-2013', 'Rahul Bansal',       'Chandigarh',         1),
      ('MI-2014', 'Pooja Reddy',        'Bengaluru',          1),
      ('MI-2015', 'Karan Malhotra',     'Amritsar',           1),
      ('MI-2016', 'Anjali Das',         'Kolkata',            1),
      ('MI-2017', 'Manish Tiwari',      'Varanasi',           1),
      ('MI-2018', 'Lakshmi Menon',      'Thiruvananthapuram', 1),
      ('MI-2019', 'Faisal Khan',        'Indore',             1),
      ('MI-2020', 'Ritu Verma',         'Dehradun',           1),
      ('MI-2021', 'Suresh Naidu',       'Visakhapatnam',      1),
      ('MI-2022', 'Nikhil Joshi',       'Nagpur',             1),
      ('MI-2023', 'Tanvi Kulkarni',     'Nashik',             1),
      ('MI-2024', 'Gaurav Saxena',      'Agra',               1),
      ('MI-3001', 'Harsh Vardhan Rane', 'Thane',              2),
      ('MI-3002', 'Shweta Bhosale',     'Kolhapur',           2),
      ('MI-3003', 'Aditya Deshpande',   'Aurangabad',         2),
      ('MI-3004', 'Farah Sheikh',       'Surat',              2)
    ) as v(oid, nm, city, grp)
  on conflict (officer_id) do nothing;

  -- Any inspector the project already had joins the primary authority's team
  update public.officers o set reports_to = v_auth1
   where o.designation_id = v_insp_dsg and o.reports_to is null;

  -- One inspector under detention, one suspended (history kept in officer_actions)
  update public.officers set account_status = 'Under Detention', status_changed_at = now() - interval '6 days',
         status_changed_by = v_auth1,
         status_reason = 'Repeated delays in submitting inspection reports; inquiry in progress'
   where officer_id = 'MI-2023';
  update public.officers set account_status = 'Suspended', is_active = false, status_changed_at = now() - interval '12 days',
         status_changed_by = v_auth1,
         status_reason = 'Verification stamps found inconsistent with recorded measurements'
   where officer_id = 'MI-2024';
  insert into public.officer_actions (officer_uuid, action, reason, performed_by, created_at)
  select o.id, 'Detained', o.status_reason, v_auth1, o.status_changed_at from public.officers o where o.officer_id = 'MI-2023'
  union all
  select o.id, 'Suspended', o.status_reason, v_auth1, o.status_changed_at from public.officers o where o.officer_id = 'MI-2024';

  -- Establishments --------------------------------------------------------
  insert into public.establishments (name, address, city, state, category)
  values
    ('Sri Lakshmi Provision Stores',   '12 Abids Road',               'Hyderabad',          'Telangana',       'Retail'),
    ('Raj Kirana & General Store',     'Shop 4, Chandni Chowk',       'New Delhi',          'Delhi',           'Retail'),
    ('Ganga Super Bazaar',             'Boring Road',                 'Patna',              'Bihar',           'Supermarket'),
    ('Nawab Food Mart',                'Hazratganj',                  'Lucknow',            'Uttar Pradesh',   'Supermarket'),
    ('Pink City Wholesale Traders',    'Johari Bazaar',               'Jaipur',             'Rajasthan',       'Wholesale'),
    ('Malabar Fresh Hypermart',        'MG Road',                     'Kochi',              'Kerala',          'Hypermarket'),
    ('Marine Drive Mega Mart',         'Nariman Point',               'Mumbai',             'Maharashtra',     'Hypermarket'),
    ('Shivaji Nagar Grocers',          'FC Road',                     'Pune',               'Maharashtra',     'Retail'),
    ('Lake City Bazaar',               'New Market',                  'Bhopal',             'Madhya Pradesh',  'Supermarket'),
    ('Marina Daily Needs',             'T Nagar',                     'Chennai',            'Tamil Nadu',      'Retail'),
    ('Ganesh Packaged Foods Depot',    'Civil Lines',                 'Kanpur',             'Uttar Pradesh',   'Wholesale'),
    ('Sabarmati Retail Hub',           'CG Road',                     'Ahmedabad',          'Gujarat',         'Supermarket'),
    ('Sector 17 City Mart',            'Sector 17',                   'Chandigarh',         'Chandigarh',      'Supermarket'),
    ('Garden City Organics',           'Indiranagar',                 'Bengaluru',          'Karnataka',       'Retail'),
    ('Golden Temple Stores',           'Hall Bazaar',                 'Amritsar',           'Punjab',          'Retail'),
    ('Howrah Bridge Traders',          'Burrabazar',                  'Kolkata',            'West Bengal',     'Wholesale'),
    ('Kashi Vishwanath Provision',     'Godowlia',                    'Varanasi',           'Uttar Pradesh',   'Retail'),
    ('Backwater Spices & Staples',     'Statue Junction',             'Thiruvananthapuram', 'Kerala',          'Retail'),
    ('Rajwada Super Store',            'Rajwada',                     'Indore',             'Madhya Pradesh',  'Supermarket'),
    ('Doon Valley Mart',               'Rajpur Road',                 'Dehradun',           'Uttarakhand',     'Supermarket'),
    ('Vizag Port Wholesale Market',    'Dwaraka Nagar',               'Visakhapatnam',      'Andhra Pradesh',  'Wholesale'),
    ('Orange City Bazaar',             'Sitabuldi',                   'Nagpur',             'Maharashtra',     'Supermarket'),
    ('Panchavati Fuel & Mart',         'Nashik Road',                 'Nashik',             'Maharashtra',     'Fuel Station'),
    ('Taj Ganj Trading Co.',           'Taj Ganj',                    'Agra',               'Uttar Pradesh',   'Wholesale'),
    ('Charminar Bulk Traders',         'Laad Bazaar',                 'Hyderabad',          'Telangana',       'Wholesale'),
    ('Connaught Place Gourmet',        'Connaught Place',             'New Delhi',          'Delhi',           'Retail'),
    ('Bandra Fresh Express',           'Linking Road',                'Mumbai',             'Maharashtra',     'Retail'),
    ('Koramangala Quick Mart',         'Koramangala 5th Block',       'Bengaluru',          'Karnataka',       'Retail'),
    ('Salt Lake Daily Bazaar',         'Sector V',                    'Kolkata',            'West Bengal',     'Supermarket'),
    ('Thane Highway Petro Mart',       'Ghodbunder Road',             'Thane',              'Maharashtra',     'Fuel Station'),
    ('Surat Diamond City Mart',        'Ring Road',                   'Surat',              'Gujarat',         'Supermarket'),
    ('Kolhapur Gul & Jaggery Depot',   'Laxmipuri',                   'Kolhapur',           'Maharashtra',     'Wholesale'),
    ('Ajanta Provision Centre',        'Cidco',                       'Aurangabad',         'Maharashtra',     'Retail'),
    ('Patliputra Value Store',         'Kankarbagh',                  'Patna',              'Bihar',           'Retail'),
    ('Mysore Road Gold Weigh',         'Mysore Road',                 'Bengaluru',          'Karnataka',       'Jeweller'),
    ('Kapadia Bullion & Jewels',       'Zaveri Bazaar',               'Mumbai',             'Maharashtra',     'Jeweller'),
    ('Greenfield E-Mart Warehouse',    'Sector 62, Noida',            'Noida',              'Uttar Pradesh',   'E-commerce Warehouse'),
    ('Cyber Hub Dark Store',           'DLF Cyber City',              'Gurugram',           'Haryana',         'E-commerce Warehouse'),
    ('Hitech City Quick Commerce Hub', 'Madhapur',                    'Hyderabad',          'Telangana',       'E-commerce Warehouse')
  on conflict (name) do nothing;

  select array_agg(id order by name) into v_est from public.establishments;

  select array_agg(o.id order by o.officer_id), array_agg(o.id order by o.officer_id) filter (where o.account_status = 'Active')
    into v_all1, v_act1
    from public.officers o where o.reports_to = v_auth1 and o.designation_id = v_insp_dsg;
  select array_agg(o.id order by o.officer_id), array_agg(o.id order by o.officer_id) filter (where o.account_status = 'Active')
    into v_all2, v_act2
    from public.officers o where o.reports_to = v_auth2 and o.designation_id = v_insp_dsg;

  create temp table _seed_new (id uuid primary key) on commit drop;

  -- Cases -----------------------------------------------------------------
  for r in
    select * from (values
      (1, v_auth1, v_all1, v_act1, 1, 1250),
      (2, v_auth2, v_all2, v_act2, 2001, 2070)
    ) as x(grp, authority, everyone, active, n_from, n_to)
  loop
    with g as (
      select n,
             abs(hashtext('a' || n)::bigint) as h1, abs(hashtext('b' || n)::bigint) as h2,
             abs(hashtext('c' || n)::bigint) as h3, abs(hashtext('d' || n)::bigint) as h4,
             abs(hashtext('e' || n)::bigint) as h5, abs(hashtext('f' || n)::bigint) as h6
        from generate_series(r.n_from, r.n_to) n
    ), a as (
      select g.*, (h2 % 100)::int as roll, (h4 % 100)::int as roll2,
             (h1 % 121)::int as rage,   -- days since a closed case was decided
             (h1 % 10)::int as oage     -- days since an in-flight case was filed
        from g
    ), st0 as (
      select a.*,
             case
               when roll >= 90 then
                 case when roll2 < 40 then 'Pending' when roll2 < 52 then 'Assigned' when roll2 < 74 then 'Scheduled'
                      when roll2 < 86 then 'In Progress' else 'Under Review' end
               -- closed cases: compliance improves the more recently they were decided
               when roll2 < 74 + (120 - rage) / 12 then 'Passed'
               else 'Failed'
             end as status
        from a
    ), st as (
      select st0.*,
             case status
               when 'Pending'      then oage
               when 'Assigned'     then 1 + oage % 6
               when 'Scheduled'    then 1 + oage % 8
               when 'In Progress'  then 4 + oage % 6
               when 'Under Review' then 4 + oage % 6
               else rage + 6        -- filed ~6 days before it was decided
             end as age
        from st0
    ), t1 as (
      select st.*,
             now() - make_interval(days => age) - make_interval(hours => (h3 % 20)::int) as submitted_at
        from st
    ), t2 as (
      select t1.*,
             case when status = 'Pending' then null::timestamptz
                  else least(submitted_at + make_interval(hours => 4 + (h3 % 18)::int), now() - interval '30 minutes') end as assigned_at
        from t1
    ), t3 as (
      select t2.*,
             case
               when status in ('Pending', 'Assigned') then null::date
               when status = 'Scheduled' then current_date + (h4 % 7)::int
               else (assigned_at + make_interval(days => 1 + (h4 % 2)::int))::date
             end as sched_date,
             case when status = 'Failed' then 1 + (h6 % 4)::int
                  when status = 'Under Review' then (h6 % 3)::int
                  else 0 end as vcount
        from t2
    ), t4 as (
      select t3.*,
             case when sched_date is null then null::time
                  else ((array['09:30', '10:00', '11:00', '11:30', '14:00', '15:30'])[1 + (h5 % 6)::int])::time end as sched_time
        from t3
    ), t5 as (
      select t4.*,
             case when status in ('In Progress', 'Under Review', 'Passed', 'Failed')
                  then (sched_date + sched_time)::timestamptz + make_interval(mins => (h6 % 20)::int) end as started_at
        from t4
    ), t6 as (
      select t5.*,
             case when status in ('Under Review', 'Passed', 'Failed')
                  then started_at + make_interval(hours => 2 + (h3 % 4)::int) end as review_at
        from t5
    ), t7 as (
      select t6.*,
             case when status in ('Passed', 'Failed') then review_at + make_interval(hours => 6 + (h4 % 40)::int) end as completed_at,
             (array['Routine Inspection', 'Surprise Inspection', 'Complaint-Based', 'Pre-Market Verification', 'Re-Inspection'])[1 + (h4 % 5)::int] as itype,
             case when h5 % 100 < 8 then 'Critical' when h5 % 100 < 30 then 'High' when h5 % 100 < 75 then 'Medium' else 'Low' end as priority,
             case when h4 % 10 < 7 then 'Website Portal' when h4 % 10 < 9 then 'Consumer Complaint' else 'Field Report' end as source,
             (select coalesce(array_agg((array['Short net quantity', 'MRP overcharging', 'Missing mandatory declaration',
                                               'Unverified weighing scale', 'Tampered stamping seal', 'Unsealed or damaged packaging',
                                               'Incorrect manufacture date', 'Misleading quantity claim'])[1 + ((h6 + k * 3) % 8)::int] order by k),
                              '{}'::text[])
                from generate_series(0, vcount - 1) k) as vtypes
        from t6
    ), p as (
      select * from (values
        (0,  'Basmati Rice 5 kg',            'Royal Harvest',   'Food Grains',      'Royal Harvest Foods Pvt Ltd',  699.00),
        (1,  'Refined Sunflower Oil 1 L',    'Golden Drop',     'Edible Oils',      'Golden Drop Agro Ltd',         168.00),
        (2,  'Whole Wheat Atta 10 kg',       'Annapurna Fresh', 'Food Grains',      'Annapurna Mills',              455.00),
        (3,  'Toor Dal 1 kg',                'Desi Pantry',     'Pulses',           'Desi Pantry Foods',            172.00),
        (4,  'Mustard Oil 2 L',              'Kachi Ghani Gold','Edible Oils',      'Gold Seed Oils Pvt Ltd',       355.00),
        (5,  'Table Salt 1 kg',              'Sagar Crystal',   'Staples',          'Sagar Salt Works',             28.00),
        (6,  'Granulated Sugar 5 kg',        'Madhur',          'Staples',          'Madhur Sugars Ltd',            245.00),
        (7,  'Tea Powder 500 g',             'Chai Junction',   'Beverages',        'Chai Junction Tea Co.',        215.00),
        (8,  'Instant Coffee 200 g',         'Brew Master',     'Beverages',        'Brew Master Beverages',        420.00),
        (9,  'Packaged Drinking Water 20 L', 'AquaPure',        'Packaged Water',   'AquaPure Industries',          90.00),
        (10, 'Cow Ghee 1 L',                 'Gau Amrit',       'Dairy',            'Gau Amrit Dairy Coop.',        640.00),
        (11, 'Paneer 500 g',                 'Fresh Fields',    'Dairy',            'Fresh Fields Dairy',           190.00),
        (12, 'Detergent Powder 4 kg',        'Sparkle Wash',    'Household',        'Sparkle Home Care Ltd',        399.00),
        (13, 'Dishwash Liquid 1 L',          'Shine Plus',      'Household',        'Shine Plus Consumer',          199.00),
        (14, 'Biscuits Family Pack 800 g',   'Crunchy Days',    'Snacks',           'Crunchy Days Foods',           120.00),
        (15, 'Namkeen Mix 400 g',            'Bikaner Select',  'Snacks',           'Bikaner Select Snacks',        110.00),
        (16, 'Honey 500 g',                  'Vana Madhu',      'Health Foods',     'Vana Madhu Naturals',          260.00),
        (17, 'Cooking Gas Cylinder 14.2 kg', 'Bharat Flame',    'Fuel & Gas',       'Bharat Flame Energy',          903.00),
        (18, 'Petrol Dispenser Calibration', 'Highway Fuels',   'Fuel & Gas',       'Highway Fuels Pvt Ltd',        0.00),
        (19, 'Digital Weighing Scale 30 kg', 'Precisa Pro',     'Weighing Scales',  'Precisa Instruments',          3499.00),
        (20, 'Jewellery Weighing Balance',   'GoldTrue',        'Weighing Scales',  'GoldTrue Technologies',        8999.00),
        (21, 'Baby Food Cereal 300 g',       'Little Sprout',   'Baby Care',        'Little Sprout Nutrition',      299.00),
        (22, 'Mineral Water 1 L (Case of 12)','Himalayan Spring','Packaged Water',  'Himalayan Spring Waters',      240.00),
        (23, 'Almonds 500 g',                'Nutri Orchard',   'Dry Fruits',       'Nutri Orchard Exports',        560.00)
      ) as pr(i, product, brand, category, manufacturer, mrp)
    ), rws as (
      select t7.*, pr.product, pr.brand, pr.category, pr.manufacturer, pr.mrp,
             'CV-' || to_char(t7.submitted_at, 'YY') || '-' || lpad(t7.n::text, 5, '0') as code,
             case when t7.status = 'Pending' then null::uuid
                  when t7.status in ('Assigned', 'Scheduled', 'In Progress')
                    then r.active[1 + (t7.h1 % array_length(r.active, 1))::int]
                  else r.everyone[1 + (t7.h1 % array_length(r.everyone, 1))::int] end as inspector,
             v_est[1 + (t7.h3 % array_length(v_est, 1))::int] as est
        from t7 join p pr on pr.i = (t7.h2 % 24)::int
    )
    insert into public.inspections (
      code, establishment_id, inspector_id, authority_id, commodity, inspection_type, status,
      scheduled_date, scheduled_time, completed_at, violations_count, remarks, created_at,
      product_name, brand, category, manufacturer, batch_no, mrp, priority, source, complaint,
      submitted_at, assigned_at, due_date, started_at, review_submitted_at, violation_types)
    select
      code, est, inspector, r.authority, category, itype, status,
      sched_date, sched_time, completed_at, vcount,
      case status
        when 'Passed' then 'All instruments and packages verified as per Legal Metrology norms.'
        when 'Failed' then 'Non-compliance recorded: ' || array_to_string(vtypes, ', ') || '.'
        when 'Under Review' then 'Findings submitted by the inspector; awaiting authority review.'
      end,
      submitted_at,
      product, brand, category, manufacturer,
      'B' || to_char(submitted_at, 'YYMM') || '-' || lpad((1000 + h5 % 9000)::text, 4, '0'),
      nullif(mrp, 0), priority, source,
      case itype
        when 'Complaint-Based' then (array[
          'Consumer reported the net quantity is lower than the declared weight on the pack.',
          'Customer was charged above the printed MRP at the billing counter.',
          'Weighing scale appears to show inflated readings at the store.',
          'Pack seal found tampered and the manufacture date looks altered.',
          'Fuel dispensed was less than the quantity displayed on the nozzle.'])[1 + (h2 % 5)::int]
        when 'Surprise Inspection' then 'Unannounced verification ordered by the Supreme Authority.'
        when 'Pre-Market Verification' then 'Mandatory declarations check before the product reaches retail shelves.'
        when 'Re-Inspection' then 'Follow-up verification after earlier non-compliance was reported.'
        else 'Periodic statutory verification of weights, measures and packaged commodities.'
      end,
      submitted_at, assigned_at,
      submitted_at::date + case priority when 'Critical' then 3 when 'High' then 7 when 'Medium' then 14 else 21 end,
      started_at, review_at, vtypes
    from rws
    on conflict (code) do nothing;
  end loop;

  insert into _seed_new (id) select id from public.inspections i
   where i.code like 'CV-%'
     and not exists (select 1 from public.inspection_events e where e.inspection_id = i.id);

  -- Track record for every demo case --------------------------------------
  insert into public.inspection_events (inspection_id, event_type, title, detail, actor_name, actor_role, created_at)
  select i.id, 'Submitted', 'Case submitted',
         'Filed via ' || i.source || ' for "' || coalesce(i.product_name, i.commodity) || '" (batch ' || coalesce(i.batch_no, 'n/a') || ')',
         'Legal Metrology Portal', 'System', i.submitted_at
    from public.inspections i join _seed_new s on s.id = i.id
  union all
  select i.id, 'Routed', 'Assigned to Supreme Authority',
         'Routed to ' || a.full_name || ' for allocation', 'Legal Metrology Portal', 'System', i.submitted_at + interval '5 minutes'
    from public.inspections i join _seed_new s on s.id = i.id join public.officers a on a.id = i.authority_id
  union all
  select i.id, 'Assigned', 'Assigned to inspector',
         o.full_name || ' (' || o.officer_id || ')', a.full_name, 'Supreme Authority', i.assigned_at
    from public.inspections i join _seed_new s on s.id = i.id
    join public.officers a on a.id = i.authority_id join public.officers o on o.id = i.inspector_id
   where i.assigned_at is not null
  union all
  select i.id, 'Scheduled', 'Inspection scheduled',
         to_char(i.scheduled_date, 'DD Mon YYYY') || ' at ' || to_char(i.scheduled_time, 'HH24:MI'),
         a.full_name, 'Supreme Authority', i.assigned_at + interval '10 minutes'
    from public.inspections i join _seed_new s on s.id = i.id join public.officers a on a.id = i.authority_id
   where i.scheduled_date is not null and i.assigned_at is not null
  union all
  select i.id, 'Started', 'Inspection started', 'On-site verification began at ' || e.name,
         o.full_name, 'Inspector', i.started_at
    from public.inspections i join _seed_new s on s.id = i.id
    join public.officers o on o.id = i.inspector_id join public.establishments e on e.id = i.establishment_id
   where i.started_at is not null
  union all
  select i.id, 'Review', 'Submitted for review', i.violations_count || ' violation(s) recorded',
         o.full_name, 'Inspector', i.review_submitted_at
    from public.inspections i join _seed_new s on s.id = i.id join public.officers o on o.id = i.inspector_id
   where i.review_submitted_at is not null
  union all
  select i.id, 'Result',
         case i.status when 'Passed' then 'Inspection passed'
                       else 'Inspection failed - ' || i.violations_count || ' violation(s)' end,
         i.remarks, a.full_name, 'Supreme Authority', i.completed_at
    from public.inspections i join _seed_new s on s.id = i.id join public.officers a on a.id = i.authority_id
   where i.completed_at is not null;

  raise notice 'Demo data created. Seed password for all demo officers is %', c_password;
end
$seed$;

-- Existing rows from before this module (if any) belong to the primary authority
update public.inspections i
   set authority_id = (
     select o.id from public.officers o join public.designations d on d.id = o.designation_id
      where d.dashboard = 'AUTHORITY' order by o.created_at, o.officer_id limit 1)
 where i.authority_id is null;
