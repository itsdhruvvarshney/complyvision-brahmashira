-- ComplyVision: Metrology Inspector module (package scans, compliance rule catalog, inspector
-- dashboard / inspections / history / reports) + realistic demo data.
--
-- Run in the Supabase SQL editor AFTER schema.sql and authority_module.sql, then run rule_engine.sql.
-- Safe to re-run.
--
-- What this adds
--   * public.compliance_rules  - the Legal Metrology (Packaged Commodities) rule catalog the analyzer
--                                 evaluates. This file seeds the 16 core rules the demo history uses;
--                                 rule_engine.sql (generated from analyzer/app/rules/lm_rules.csv)
--                                 extends it to the full category-aware catalog.
--   * public.package_scans     - one row per package analysed by the ComplyVision analyzer (FastAPI +
--                                 OpenCV + PaddleOCR + rule engine). The canonical JSON report is kept in
--                                 `report`; verdict and rule counts are re-computed here from the rule
--                                 results so the client can never inflate them.
--   * Inspector RPCs (token protected, SECURITY DEFINER):
--       inspector_overview, inspector_cases, inspector_case, inspector_scan, inspector_history,
--       inspector_case_report, inspector_save_scan, inspector_submit_findings
--   * Demo data: inspector INS-003 "Harshit Chaubey" (password Welcome@123, created only if the ID does
--     not exist yet) with ~3 months of package-level history and pending assignments from the Supreme
--     Authority; a lighter package history for every other demo inspector.
--
-- Package verdict: any FAIL -> Failed, otherwise any REVIEW -> Under Review, otherwise Passed.
-- Compliance rate = passed packages / packages analysed.

-- ===========================================================================
-- 1. Rule catalog
-- ===========================================================================
create table if not exists public.compliance_rules (
  id            text primary key,
  sort_order    integer not null,
  title         text not null,
  reference     text not null,
  category      text not null,
  severity      text not null check (severity in ('Critical', 'Major', 'Minor')),
  description   text not null,
  pass_reason   text,
  fail_reason   text,
  review_reason text,
  na_reason     text
);

insert into public.compliance_rules
  (id, sort_order, title, reference, category, severity, description, pass_reason, fail_reason, review_reason, na_reason)
values
  ('LM-01', 1, 'Name & address of manufacturer / packer', 'LM (PC) Rules 2011, Rule 6(1)(a)', 'Mandatory declaration', 'Major',
   'The label must carry the name and complete address of the manufacturer, packer or importer.',
   'Manufacturer / packer name and address found on the label.',
   'No manufacturer, packer or importer name and address could be found.',
   'Manufacturer details were only partly legible; confirm the address on the physical pack.', null),
  ('LM-02', 2, 'Country of origin (imported goods)', 'LM (PC) Rules 2011, Rule 6(1)(b)', 'Mandatory declaration', 'Major',
   'Imported packages must state the country of origin or manufacture.',
   'Country of origin declared.', 'Imported package without a country of origin declaration.',
   'Import markers found but the country of origin is unclear.', 'Domestic product - no import markers on the label.'),
  ('LM-03', 3, 'Common or generic name of the commodity', 'LM (PC) Rules 2011, Rule 6(1)(c)', 'Mandatory declaration', 'Major',
   'The common or generic name of the commodity must be declared.',
   'Generic name of the commodity declared.', 'The generic name of the commodity is missing.',
   'Generic name could not be confirmed from the text read.', null),
  ('LM-04', 4, 'Net quantity in standard units', 'LM (PC) Rules 2011, Rule 6(1)(d)', 'Mandatory declaration', 'Critical',
   'Net quantity must be declared in standard units of weight, measure or number (g, kg, ml, L, N).',
   'Net quantity declared in standard units.', 'Net quantity declaration missing or not in standard units.',
   'Net quantity text was read with low confidence.', null),
  ('LM-05', 5, 'Month & year of manufacture / packing', 'LM (PC) Rules 2011, Rule 6(1)(e)', 'Mandatory declaration', 'Major',
   'The month and year in which the commodity is manufactured, packed or imported must be declared.',
   'Month and year of manufacture / packing declared.', 'Month and year of manufacture / packing not declared.',
   'A date was found but it is unclear whether it is the manufacture date.', null),
  ('LM-06', 6, 'Retail sale price (MRP) declared', 'LM (PC) Rules 2011, Rule 6(1)(f)', 'Price declaration', 'Critical',
   'The maximum retail price must be declared on the package.',
   'Retail sale price declared.', 'Retail sale price (MRP) not declared.',
   'Price text was read with low confidence.', null),
  ('LM-07', 7, 'MRP inclusive of all taxes, in Rs. / ₹', 'LM (PC) Rules 2011, Rule 6(1)(f), Rule 2(m)', 'Price declaration', 'Major',
   'MRP must be stated in Indian currency and as inclusive of all taxes.',
   'MRP stated in rupees and inclusive of all taxes.', 'MRP not stated as inclusive of all taxes.',
   'Tax-inclusive wording could not be confirmed.', null),
  ('LM-08', 8, 'Unit sale price declared', 'LM (PC) Amendment Rules 2021, Rule 6(1)(f)', 'Price declaration', 'Minor',
   'Unit sale price (price per kg / L / unit) must accompany the MRP.',
   'Unit sale price declared.', 'Unit sale price not declared alongside the MRP.',
   'A unit price was found but its unit could not be confirmed.', null),
  ('LM-09', 9, 'Consumer care details', 'LM (PC) Rules 2011, Rule 6(1)(g)', 'Mandatory declaration', 'Major',
   'Name, address, telephone number and e-mail for consumer complaints must be declared.',
   'Consumer care contact declared.', 'No consumer care telephone number or e-mail declared.',
   'Contact details partly legible.', null),
  ('LM-10', 10, 'Best before / use-by date', 'LM (PC) Rules 2011, Rule 6(1)(h)', 'Mandatory declaration', 'Major',
   'Commodities that may become unfit for consumption must state a best before or use-by date.',
   'Best before / use-by date declared.', 'Food commodity without a best before or use-by date.',
   'Shelf-life text found but not clearly a best before date.', 'Not a food or perishable commodity.'),
  ('LM-11', 11, 'Height of net-quantity numerals', 'LM (PC) Rules 2011, Rule 7', 'Legibility', 'Major',
   'Numerals of the net quantity declaration must meet the minimum height for the declared quantity.',
   'Numeral height meets the minimum for the declared quantity.', 'Net-quantity numerals are smaller than the minimum height.',
   'Numeral height could not be measured reliably.', 'No ArUco calibration marker in frame.'),
  ('LM-12', 12, 'Legibility & contrast of declarations', 'LM (PC) Rules 2011, Rule 8', 'Legibility', 'Minor',
   'Declarations must be legible and prominent, contrasting with the background.',
   'Declarations contrast clearly with the background.', 'Declarations have insufficient contrast against the background.',
   'Contrast is borderline in parts of the label.', null),
  ('LM-13', 13, 'MRP matches the case record', 'Case cross-check', 'Case cross-check', 'Critical',
   'The MRP on the pack must match the MRP recorded for the case / product.',
   'Pack MRP matches the case record.', 'Pack MRP differs from the case record.',
   'Pack MRP could not be compared with the case record.', 'No MRP recorded for this case.'),
  ('LM-14', 14, 'Date consistency', 'LM (PC) Rules 2011, Rule 6(1)(e), 6(1)(h)', 'Consistency', 'Major',
   'Manufacture date must not be in the future and best before must fall after manufacture.',
   'Declared dates are consistent.', 'Declared dates are inconsistent (future or reversed).',
   'Dates could not be fully interpreted.', 'Not enough dates declared to compare.'),
  ('LM-15', 15, 'Evidence image quality', 'ComplyVision evidence standard', 'Evidence quality', 'Minor',
   'The captured image must be sharp, evenly lit and readable for an automated decision.',
   'Image quality sufficient for automated verification.', 'Image unusable for verification.',
   'Image quality is marginal (blur, glare or low light) - verify manually.', null),
  ('LM-16', 16, 'Net quantity matches the declared product', 'Case cross-check', 'Case cross-check', 'Critical',
   'The net quantity on the pack must match the quantity recorded for the product under inspection.',
   'Pack quantity matches the product record.', 'Pack quantity differs from the product record.',
   'Pack quantity could not be compared with the product record.', 'No quantity recorded for this product.')
-- Titles, references and sections are owned by rule_engine.sql; re-running this file only refreshes
-- the demo-seeding reasons so it never rolls the catalog back
on conflict (id) do update set
  pass_reason = excluded.pass_reason, fail_reason = excluded.fail_reason,
  review_reason = excluded.review_reason, na_reason = excluded.na_reason;

-- ===========================================================================
-- 2. Package scans
-- ===========================================================================
create sequence if not exists public.package_scan_seq start 100000;

create table if not exists public.package_scans (
  id               uuid primary key default gen_random_uuid(),
  scan_code        text not null unique,
  inspection_id    uuid not null references public.inspections (id) on delete cascade,
  inspector_id     uuid references public.officers (id) on delete set null,
  verdict          text not null check (verdict in ('Passed', 'Failed', 'Under Review')),
  rules_passed     integer not null default 0,
  rules_failed     integer not null default 0,
  rules_review     integer not null default 0,
  rules_na         integer not null default 0,
  score            numeric(5, 1) not null default 0,
  quality_grade    text,
  source           text not null default 'Camera' check (source in ('Camera', 'Gallery', 'Seed')),
  analyzer_version text,
  duration_ms      integer,
  report           jsonb not null default '{}'::jsonb,
  created_at       timestamptz not null default now()
);
create index if not exists package_scans_inspector_idx  on public.package_scans (inspector_id, created_at desc);
create index if not exists package_scans_inspection_idx on public.package_scans (inspection_id, created_at);

-- Category the rule engine classified the package into (report.classification), and inspector decisions
alter table public.package_scans add column if not exists category_id   text;
alter table public.package_scans add column if not exists category_name text;
alter table public.package_scans add column if not exists package_type  text;
alter table public.package_scans add column if not exists resolved_at   timestamptz;
create index if not exists package_scans_category_idx on public.package_scans (category_id);

alter table public.compliance_rules enable row level security;
alter table public.package_scans    enable row level security;
revoke all on public.compliance_rules, public.package_scans from anon, authenticated;
revoke all on sequence public.package_scan_seq from anon, authenticated;

-- ===========================================================================
-- 3. Internal helpers
-- ===========================================================================

-- Maps a failed rule onto the violation vocabulary the authority dashboards already use
create or replace function public._violation_label(p_rule text)
returns text
language sql
immutable
as $$
  select case
    when p_rule = 'LM-13' then 'MRP overcharging'
    when p_rule = 'LM-16' then 'Short net quantity'
    when p_rule in ('LM-05', 'LM-14', 'DR-05') then 'Incorrect manufacture date'
    when p_rule in ('LM-11', 'LM-12', 'LM-30') then 'Illegible declaration'
    when p_rule in ('LM-24', 'LM-25', 'LM-26', 'LM-27', 'LM-28', 'LM-29') then 'Misleading quantity claim'
    when p_rule in ('LM-08', 'LM-22') then 'Incorrect unit sale price'
    when p_rule = 'LM-23' then 'Dual or altered MRP'
    when p_rule = 'LM-19' then 'Export pack on domestic sale'
    when p_rule = 'LM-15' then 'Unclear evidence'
    when p_rule in ('FS-01', 'CS-01', 'DR-02', 'AY-01', 'MD-01', 'IN-01', 'BI-01') then 'Missing licence / certification'
    when p_rule ~ '^(FS-0[6-9]|FS-1[0-2]|TB-0[1-3]|DR-08|IN-02)$' then 'Missing statutory warning'
    else 'Missing mandatory declaration'
  end;
$$;

create or replace function public._rule_catalog()
returns jsonb
language sql
stable
security definer
set search_path = public, extensions
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
           'id', id, 'title', title, 'reference', reference, 'category', category,
           'severity', severity, 'description', description
         ) order by sort_order), '[]'::jsonb)
  from public.compliance_rules;
$$;

-- Light per-scan summary used by every list (no heavy report payload)
create or replace function public._scan_summary(s public.package_scans)
returns jsonb
language sql
stable
security definer
set search_path = public, extensions
as $$
  select jsonb_build_object(
    'scan_code', s.scan_code,
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
    'resolved_at', s.resolved_at,
    'flags', coalesce((
      select jsonb_agg(jsonb_build_object('id', r->>'id', 'status', r->>'status',
                                          'title', coalesce(r->>'title', cr.title)))
        from jsonb_array_elements(coalesce(s.report->'rules', '[]'::jsonb)) r
        left join public.compliance_rules cr on cr.id = r->>'id'
       where r->>'status' in ('FAIL', 'REVIEW')
    ), '[]'::jsonb)
  );
$$;

revoke all on function public._violation_label(text) from public, anon, authenticated;
revoke all on function public._rule_catalog() from public, anon, authenticated;
revoke all on function public._scan_summary(public.package_scans) from public, anon, authenticated;

-- ===========================================================================
-- 4. inspector_overview: dashboard numbers for the signed-in inspector only
-- ===========================================================================
create or replace function public.inspector_overview(p_token uuid, p_days integer default 30)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me         uuid;
  v_dash       text;
  v_days       integer := greatest(coalesce(p_days, 30), 0);
  v_from       timestamptz;
  v_prev_from  timestamptz;
  v_span_start timestamptz;
  v_span_secs  numeric;
  v_life       record;
  v_cur        record;
  v_prev       record;
  v_rate       numeric := 0;
  v_prev_rate  numeric;
  v_series     jsonb;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  v_from      := case when v_days = 0 then '-infinity'::timestamptz else now() - make_interval(days => v_days) end;
  v_prev_from := case when v_days = 0 then null else now() - make_interval(days => v_days * 2) end;
  v_span_start := case
    when v_days = 0 then coalesce((select min(created_at) from public.package_scans where inspector_id = v_me), now() - interval '30 days')
    else now() - make_interval(days => v_days) end;
  v_span_secs := greatest(extract(epoch from (now() - v_span_start)), 3600);

  select count(*) as total,
         count(*) filter (where verdict = 'Passed')       as passed,
         count(*) filter (where verdict = 'Failed')       as failed,
         count(*) filter (where verdict = 'Under Review') as review,
         count(distinct inspection_id)                    as cases,
         coalesce(sum(rules_passed + rules_failed + rules_review), 0) as checks
    into v_life
    from public.package_scans where inspector_id = v_me;

  select count(*) as total,
         count(*) filter (where verdict = 'Passed')       as passed,
         count(*) filter (where verdict = 'Failed')       as failed,
         count(*) filter (where verdict = 'Under Review') as review
    into v_cur
    from public.package_scans where inspector_id = v_me and created_at >= v_from;
  if v_cur.total > 0 then v_rate := round(100.0 * v_cur.passed / v_cur.total, 1); end if;

  if v_prev_from is not null then
    select count(*) as total, count(*) filter (where verdict = 'Passed') as passed
      into v_prev
      from public.package_scans
     where inspector_id = v_me and created_at >= v_prev_from and created_at < v_from;
    if v_prev.total > 0 then v_prev_rate := round(100.0 * v_prev.passed / v_prev.total, 1); end if;
  end if;

  -- 8-point compliance series across the selected window
  select coalesce(jsonb_agg(jsonb_build_object('rate', coalesce(x.rate, 0), 'total', coalesce(x.total, 0)) order by g.k), '[]'::jsonb)
    into v_series
    from generate_series(0, 7) as g(k)
    left join (
      select t.b, count(*) as total,
             round(100.0 * count(*) filter (where t.verdict = 'Passed') / nullif(count(*), 0), 1) as rate
        from (
          select verdict,
                 least(7, greatest(0, floor(extract(epoch from (created_at - v_span_start)) / (v_span_secs / 8.0))))::int as b
            from public.package_scans
           where inspector_id = v_me and created_at >= v_span_start
        ) t
       group by t.b
    ) x on x.b = g.k;

  return jsonb_build_object(
    'ok', true,
    'days', v_days,
    'generated_at', now(),
    'profile', (
      select jsonb_build_object(
        'officer_id', o.officer_id, 'name', o.full_name, 'designation', d.name, 'office', o.office,
        'email', o.email, 'phone', o.phone, 'specialization', o.specialization,
        'employee_since', o.employee_since, 'last_login_at', o.last_login_at,
        'status', o.account_status, 'status_reason', o.status_reason,
        'reports_to', (select jsonb_build_object('name', a.full_name, 'officer_id', a.officer_id, 'office', a.office, 'designation', ad.name)
                         from public.officers a join public.designations ad on ad.id = a.designation_id where a.id = o.reports_to)
      )
      from public.officers o join public.designations d on d.id = o.designation_id where o.id = v_me
    ),
    'lifetime', jsonb_build_object(
      'total', v_life.total, 'passed', v_life.passed, 'failed', v_life.failed, 'review', v_life.review,
      'cases', v_life.cases, 'checks', v_life.checks,
      'rate', case when v_life.total = 0 then 0 else round(100.0 * v_life.passed / v_life.total, 1) end
    ),
    'period', jsonb_build_object('total', v_cur.total, 'passed', v_cur.passed, 'failed', v_cur.failed, 'review', v_cur.review),
    'trend', jsonb_build_object(
      'rate', v_rate,
      'previous_rate', v_prev_rate,
      'delta', case when v_prev_rate is null or v_cur.total = 0 then null else round(v_rate - v_prev_rate, 1) end,
      'series', v_series
    ),
    'pipeline', (
      select jsonb_build_object(
        'assigned',    count(*) filter (where status = 'Assigned'),
        'scheduled',   count(*) filter (where status = 'Scheduled'),
        'in_progress', count(*) filter (where status = 'In Progress'),
        'review',      count(*) filter (where status = 'Under Review'),
        'passed',      count(*) filter (where status = 'Passed'),
        'failed',      count(*) filter (where status = 'Failed'),
        'today',       count(*) filter (where status in ('Assigned', 'Scheduled', 'In Progress') and scheduled_date = current_date),
        'overdue',     count(*) filter (where status in ('Assigned', 'Scheduled', 'In Progress') and due_date < current_date)
      )
      from public.inspections where inspector_id = v_me
    ),
    'history', coalesce((
      select jsonb_agg(r.j order by r.last_at desc)
      from (
        select c.last_at,
               jsonb_build_object(
                 'code', i.code, 'status', i.status,
                 'product', coalesce(i.product_name, i.commodity), 'brand', i.brand,
                 'category', coalesce(i.category, i.commodity),
                 'establishment', e.name, 'city', e.city,
                 'total', c.total, 'passed', c.passed, 'failed', c.failed, 'review', c.review,
                 'first_scan_at', c.first_at, 'last_scan_at', c.last_at
               ) as j
          from public.inspections i
          join public.establishments e on e.id = i.establishment_id
          join lateral (
            select count(*) as total,
                   count(*) filter (where ps.verdict = 'Passed')       as passed,
                   count(*) filter (where ps.verdict = 'Failed')       as failed,
                   count(*) filter (where ps.verdict = 'Under Review') as review,
                   min(ps.created_at) as first_at, max(ps.created_at) as last_at
              from public.package_scans ps where ps.inspection_id = i.id
          ) c on c.total > 0
         where i.inspector_id = v_me
         order by c.last_at desc
         limit 12
      ) r
    ), '[]'::jsonb),
    'next', (
      select jsonb_build_object(
        'code', i.code, 'status', i.status, 'priority', i.priority,
        'product', coalesce(i.product_name, i.commodity), 'establishment', e.name, 'city', e.city,
        'scheduled_date', i.scheduled_date, 'scheduled_time', to_char(i.scheduled_time, 'HH24:MI'), 'due_date', i.due_date)
        from public.inspections i join public.establishments e on e.id = i.establishment_id
       where i.inspector_id = v_me and i.status in ('Assigned', 'Scheduled', 'In Progress')
       order by (i.status = 'In Progress') desc, i.scheduled_date nulls last, i.due_date nulls last
       limit 1
    )
  );
end;
$$;

-- ===========================================================================
-- 5. inspector_cases: every case assigned to me, with who assigned it and scan progress
-- ===========================================================================
create or replace function public.inspector_cases(p_token uuid)
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
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  return jsonb_build_object(
    'ok', true,
    'generated_at', now(),
    'counts', (
      select jsonb_build_object(
        'total',       count(*),
        'assigned',    count(*) filter (where status = 'Assigned'),
        'scheduled',   count(*) filter (where status = 'Scheduled'),
        'in_progress', count(*) filter (where status = 'In Progress'),
        'review',      count(*) filter (where status = 'Under Review'),
        'passed',      count(*) filter (where status = 'Passed'),
        'failed',      count(*) filter (where status = 'Failed'),
        'today',       count(*) filter (where status in ('Assigned', 'Scheduled', 'In Progress') and scheduled_date = current_date),
        'overdue',     count(*) filter (where status in ('Assigned', 'Scheduled', 'In Progress') and due_date < current_date)
      )
      from public.inspections where inspector_id = v_me
    ),
    'items', coalesce((
      select jsonb_agg(r.j order by r.ord, r.sched nulls last, r.pr, r.code)
      from (
        select case i.status when 'In Progress' then 0 when 'Scheduled' then 1 when 'Assigned' then 2
                             when 'Under Review' then 3 else 4 end as ord,
               case when i.status in ('Passed', 'Failed') then null else i.scheduled_date end as sched,
               case i.priority when 'Critical' then 0 when 'High' then 1 when 'Medium' then 2 else 3 end as pr,
               i.code,
               jsonb_build_object(
                 'code', i.code, 'status', i.status, 'priority', i.priority,
                 'product', coalesce(i.product_name, i.commodity), 'brand', i.brand,
                 'category', coalesce(i.category, i.commodity), 'manufacturer', i.manufacturer,
                 'batch_no', i.batch_no, 'mrp', i.mrp,
                 'establishment', e.name, 'address', e.address, 'city', e.city, 'state', e.state,
                 'establishment_type', e.category,
                 'type', i.inspection_type, 'source', i.source, 'complaint', i.complaint,
                 'submitted_at', i.submitted_at, 'assigned_at', i.assigned_at,
                 'scheduled_date', i.scheduled_date, 'scheduled_time', to_char(i.scheduled_time, 'HH24:MI'),
                 'due_date', i.due_date, 'started_at', i.started_at,
                 'review_submitted_at', i.review_submitted_at, 'completed_at', i.completed_at,
                 'violations', i.violations_count,
                 'assigned_by', jsonb_build_object(
                   'name', coalesce(ev.actor_name, a.full_name), 'officer_id', a.officer_id,
                   'office', a.office, 'designation', ad.name, 'at', coalesce(ev.created_at, i.assigned_at),
                   'detail', ev.detail),
                 'scans', c.total, 'scans_passed', c.passed, 'scans_failed', c.failed, 'scans_review', c.review,
                 'last_scan_at', c.last_at
               ) as j
          from public.inspections i
          join public.establishments e on e.id = i.establishment_id
          left join public.officers a on a.id = i.authority_id
          left join public.designations ad on ad.id = a.designation_id
          left join lateral (
            select x.detail, x.created_at, x.actor_name from public.inspection_events x
             where x.inspection_id = i.id and x.event_type in ('Assigned', 'Reassigned')
             order by x.created_at desc, x.id desc limit 1
          ) ev on true
          left join lateral (
            select count(*) as total,
                   count(*) filter (where ps.verdict = 'Passed')       as passed,
                   count(*) filter (where ps.verdict = 'Failed')       as failed,
                   count(*) filter (where ps.verdict = 'Under Review') as review,
                   max(ps.created_at) as last_at
              from public.package_scans ps where ps.inspection_id = i.id
          ) c on true
         where i.inspector_id = v_me
         order by 1, 2 nulls last, 3, 4
         limit 1000
      ) r
    ), '[]'::jsonb)
  );
end;
$$;

-- ===========================================================================
-- 6. inspector_case: one of my cases with track record, scans and per-rule statistics
-- ===========================================================================
create or replace function public.inspector_case(p_token uuid, p_code text)
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
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  select * into i from public.inspections where code = btrim(coalesce(p_code, '')) and inspector_id = v_me;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;

  return jsonb_build_object(
    'ok', true,
    'generated_at', now(),
    'case', (
      select jsonb_build_object(
        'code', i.code, 'status', i.status, 'priority', i.priority,
        'product', coalesce(i.product_name, i.commodity), 'brand', i.brand,
        'category', coalesce(i.category, i.commodity), 'manufacturer', i.manufacturer,
        'batch_no', i.batch_no, 'mrp', i.mrp,
        'establishment', e.name, 'address', e.address, 'city', e.city, 'state', e.state,
        'establishment_type', e.category,
        'type', i.inspection_type, 'source', i.source, 'complaint', i.complaint, 'remarks', i.remarks,
        'submitted_at', i.submitted_at, 'assigned_at', i.assigned_at,
        'scheduled_date', i.scheduled_date, 'scheduled_time', to_char(i.scheduled_time, 'HH24:MI'),
        'due_date', i.due_date, 'started_at', i.started_at,
        'review_submitted_at', i.review_submitted_at, 'completed_at', i.completed_at,
        'violations', i.violations_count, 'violation_types', to_jsonb(i.violation_types),
        'assigned_by', (
          select jsonb_build_object('name', coalesce(ev.actor_name, a.full_name), 'officer_id', a.officer_id,
                                    'office', a.office, 'designation', ad.name,
                                    'at', coalesce(ev.created_at, i.assigned_at), 'detail', ev.detail)
            from public.officers a
            left join public.designations ad on ad.id = a.designation_id
            left join lateral (
              select x.detail, x.created_at, x.actor_name from public.inspection_events x
               where x.inspection_id = i.id and x.event_type in ('Assigned', 'Reassigned')
               order by x.created_at desc, x.id desc limit 1
            ) ev on true
           where a.id = i.authority_id
        )
      )
      from public.establishments e where e.id = i.establishment_id
    ),
    'events', coalesce((
      select jsonb_agg(jsonb_build_object(
               'type', ev.event_type, 'title', ev.title, 'detail', ev.detail,
               'actor', ev.actor_name, 'role', ev.actor_role, 'at', ev.created_at
             ) order by ev.created_at, ev.id)
      from public.inspection_events ev where ev.inspection_id = i.id
    ), '[]'::jsonb),
    'scans', coalesce((
      select jsonb_agg(public._scan_summary(ps) order by ps.created_at desc)
      from public.package_scans ps where ps.inspection_id = i.id
    ), '[]'::jsonb),
    -- Only the rules the category-aware engine actually evaluated for this case's packages
    'rule_stats', coalesce((
      select jsonb_agg(jsonb_build_object(
               'id', cr.id, 'title', cr.title, 'reference', cr.reference, 'severity', cr.severity,
               'section', cr.category,
               'passed', coalesce(x.passed, 0), 'failed', coalesce(x.failed, 0),
               'review', coalesce(x.review, 0), 'not_applicable', coalesce(x.na, 0)
             ) order by cr.sort_order)
      from public.compliance_rules cr
      join (
        select r->>'id' as rid,
               count(*) filter (where r->>'status' = 'PASS')           as passed,
               count(*) filter (where r->>'status' = 'FAIL')           as failed,
               count(*) filter (where r->>'status' = 'REVIEW')         as review,
               count(*) filter (where r->>'status' = 'NOT_APPLICABLE') as na
          from public.package_scans ps, jsonb_array_elements(coalesce(ps.report->'rules', '[]'::jsonb)) r
         where ps.inspection_id = i.id
         group by 1
      ) x on x.rid = cr.id
    ), '[]'::jsonb),
    'catalog', public._rule_catalog()
  );
end;
$$;

-- ===========================================================================
-- 7. inspector_scan: the full canonical report of one package
-- ===========================================================================
create or replace function public.inspector_scan(p_token uuid, p_scan_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me   uuid;
  v_dash text;
  ps     public.package_scans%rowtype;
  v_case text;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  select * into ps from public.package_scans where scan_code = btrim(coalesce(p_scan_code, ''));
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  select code into v_case from public.inspections where id = ps.inspection_id and inspector_id = v_me;
  if v_case is null then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  return jsonb_build_object(
    'ok', true,
    'case_code', v_case,
    'scan', public._scan_summary(ps),
    'report', ps.report,
    'catalog', public._rule_catalog()
  );
end;
$$;

-- ===========================================================================
-- 8. inspector_history: everything I have worked on, newest first, plus monthly stats and activity
-- ===========================================================================
create or replace function public.inspector_history(p_token uuid)
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
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  return jsonb_build_object(
    'ok', true,
    'generated_at', now(),
    'cases', coalesce((
      select jsonb_agg(r.j order by r.at desc)
      from (
        select coalesce(c.last_at, i.completed_at, i.review_submitted_at, i.started_at, i.assigned_at) as at,
               jsonb_build_object(
                 'code', i.code, 'status', i.status, 'priority', i.priority,
                 'product', coalesce(i.product_name, i.commodity), 'brand', i.brand,
                 'category', coalesce(i.category, i.commodity), 'batch_no', i.batch_no, 'mrp', i.mrp,
                 'establishment', e.name, 'city', e.city, 'state', e.state,
                 'type', i.inspection_type,
                 'assigned_at', i.assigned_at, 'started_at', i.started_at,
                 'review_submitted_at', i.review_submitted_at, 'completed_at', i.completed_at,
                 'violations', i.violations_count, 'violation_types', to_jsonb(i.violation_types),
                 'remarks', i.remarks,
                 'assigned_by', a.full_name,
                 'total', coalesce(c.total, 0), 'passed', coalesce(c.passed, 0),
                 'failed', coalesce(c.failed, 0), 'review', coalesce(c.review, 0),
                 'first_scan_at', c.first_at, 'last_scan_at', c.last_at
               ) as j
          from public.inspections i
          join public.establishments e on e.id = i.establishment_id
          left join public.officers a on a.id = i.authority_id
          left join lateral (
            select count(*) as total,
                   count(*) filter (where ps.verdict = 'Passed')       as passed,
                   count(*) filter (where ps.verdict = 'Failed')       as failed,
                   count(*) filter (where ps.verdict = 'Under Review') as review,
                   min(ps.created_at) as first_at, max(ps.created_at) as last_at
              from public.package_scans ps where ps.inspection_id = i.id
          ) c on true
         where i.inspector_id = v_me
           and (i.status in ('In Progress', 'Under Review', 'Passed', 'Failed') or c.total > 0)
         order by 1 desc nulls last
         limit 1000
      ) r
    ), '[]'::jsonb),
    'monthly', coalesce((
      select jsonb_agg(jsonb_build_object(
               'label', to_char(m.m, 'Mon YYYY'),
               'total', coalesce(x.total, 0), 'passed', coalesce(x.passed, 0),
               'failed', coalesce(x.failed, 0), 'review', coalesce(x.review, 0)
             ) order by m.m)
      from generate_series(date_trunc('month', now()) - interval '5 months', date_trunc('month', now()), interval '1 month') as m(m)
      left join (
        select date_trunc('month', created_at) as mm, count(*) as total,
               count(*) filter (where verdict = 'Passed')       as passed,
               count(*) filter (where verdict = 'Failed')       as failed,
               count(*) filter (where verdict = 'Under Review') as review
          from public.package_scans where inspector_id = v_me
         group by 1
      ) x on x.mm = m.m
    ), '[]'::jsonb),
    'top_issues', coalesce((
      select jsonb_agg(jsonb_build_object('id', t.rid, 'title', cr.title, 'failed', t.failed, 'review', t.review)
                       order by t.failed desc, t.review desc)
      from (
        select r->>'id' as rid,
               count(*) filter (where r->>'status' = 'FAIL')   as failed,
               count(*) filter (where r->>'status' = 'REVIEW') as review
          from public.package_scans ps, jsonb_array_elements(coalesce(ps.report->'rules', '[]'::jsonb)) r
         where ps.inspector_id = v_me and r->>'status' in ('FAIL', 'REVIEW')
         group by 1
         order by 2 desc, 3 desc
         limit 6
      ) t
      join public.compliance_rules cr on cr.id = t.rid
    ), '[]'::jsonb),
    'activity', coalesce((
      select jsonb_agg(jsonb_build_object(
               'code', a.code, 'product', a.product, 'type', a.event_type, 'title', a.title,
               'detail', a.detail, 'actor', a.actor_name, 'role', a.actor_role, 'at', a.created_at
             ) order by a.created_at desc)
      from (
        select i.code, coalesce(i.product_name, i.commodity) as product,
               ev.event_type, ev.title, ev.detail, ev.actor_name, ev.actor_role, ev.created_at
          from public.inspection_events ev
          join public.inspections i on i.id = ev.inspection_id
         where i.inspector_id = v_me
         order by ev.created_at desc, ev.id desc
         limit 120
      ) a
    ), '[]'::jsonb)
  );
end;
$$;

-- ===========================================================================
-- 9. inspector_case_report: everything needed for the PDF / JSON case report
--    Full package reports are included for every non-compliant package (latest 40) and the two most
--    recent compliant packages as representative samples; the rest are summarised.
-- ===========================================================================
create or replace function public.inspector_case_report(p_token uuid, p_code text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me     uuid;
  v_dash   text;
  i        public.inspections%rowtype;
  v_detail jsonb;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  select * into i from public.inspections where code = btrim(coalesce(p_code, '')) and inspector_id = v_me;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;

  v_detail := public.inspector_case(p_token, p_code);

  return v_detail || jsonb_build_object(
    'schema', 'complyvision.case-report/1.0',
    'inspector', (
      select jsonb_build_object('officer_id', o.officer_id, 'name', o.full_name, 'designation', d.name,
                                'office', o.office, 'email', o.email, 'phone', o.phone)
        from public.officers o join public.designations d on d.id = o.designation_id where o.id = v_me
    ),
    'packages', coalesce((
      select jsonb_agg(
               public._scan_summary(x.ps)
               || case when x.keep then jsonb_build_object('report', (x.ps).report) else '{}'::jsonb end
               order by (x.ps).created_at)
      from (
        select ps,
               (ps.verdict <> 'Passed' and row_number() over (partition by (ps.verdict <> 'Passed') order by ps.created_at desc) <= 40)
               or (ps.verdict = 'Passed' and row_number() over (partition by (ps.verdict = 'Passed') order by ps.created_at desc) <= 2) as keep
          from public.package_scans ps where ps.inspection_id = i.id
      ) x
    ), '[]'::jsonb)
  );
end;
$$;

-- ===========================================================================
-- 10. inspector_save_scan: store one analysed package against my case
-- ===========================================================================
create or replace function public.inspector_save_scan(p_token uuid, p_code text, p_report jsonb, p_source text default 'Camera')
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me      uuid;
  v_dash    text;
  i         public.inspections%rowtype;
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
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  if p_report is null or jsonb_typeof(p_report) <> 'object'
     or v_rules is null or jsonb_typeof(v_rules) <> 'array' or jsonb_array_length(v_rules) = 0 then
    return jsonb_build_object('ok', false, 'code', 'bad_report');
  end if;
  if pg_column_size(p_report) > 4000000 then
    return jsonb_build_object('ok', false, 'code', 'too_large');
  end if;

  select * into i from public.inspections where code = btrim(coalesce(p_code, '')) and inspector_id = v_me for update;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;

  if i.status in ('Assigned', 'Scheduled') then
    if i.scheduled_date is not null and i.scheduled_date > current_date then
      return jsonb_build_object('ok', false, 'code', 'too_early');
    end if;
    -- The first scan starts the inspection automatically
    update public.inspections set status = 'In Progress', started_at = now() where id = i.id;
    perform public._log_event(i.id, 'Started', 'Inspection started', 'Started with the first package scan', v_me, 'Inspector');
  elsif i.status <> 'In Progress' then
    return jsonb_build_object('ok', false, 'code', 'bad_state');
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
  -- Pass mark: a compliance score of 75% or more is a pass for inspector and organisation checks
  if v_pass + v_fail + v_review > 0 and v_score >= 75 then v_verdict := 'Passed'; end if;
  v_scan := 'PKG-' || to_char(now(), 'YY') || '-' || lpad(nextval('public.package_scan_seq')::text, 6, '0');

  insert into public.package_scans (
    scan_code, inspection_id, inspector_id, verdict, rules_passed, rules_failed, rules_review, rules_na,
    score, quality_grade, source, analyzer_version, duration_ms, report, category_id, category_name, package_type)
  values (
    v_scan, i.id, v_me, v_verdict, v_pass, v_fail, v_review, v_na, v_score,
    left(p_report #>> '{quality,grade}', 20), v_source, left(p_report->>'analyzer_version', 40),
    case when (p_report->>'duration_ms') ~ '^\d{1,9}$' then (p_report->>'duration_ms')::integer end,
    p_report,
    left(p_report #>> '{classification,category,id}', 40), left(p_report #>> '{classification,category,name}', 120),
    left(p_report #>> '{classification,package_type,id}', 20));

  perform public._log_event(
    i.id, 'Scan', 'Package analysed',
    v_scan || ': ' || v_verdict || ' - ' || v_pass || '/' || (v_pass + v_fail + v_review) || ' applicable rules passed',
    v_me, 'Inspector');

  return jsonb_build_object(
    'ok', true, 'code', 'ok', 'scan_code', v_scan, 'verdict', v_verdict,
    'passed', v_pass, 'failed', v_fail, 'review', v_review, 'not_applicable', v_na, 'score', v_score,
    'case_status', 'In Progress');
end;
$$;

-- ===========================================================================
-- 10a. inspector_resolve_scan: the inspector settles the REVIEW rules of one package after checking the
--      physical pack. Only rules still in REVIEW can be resolved, only to PASS or FAIL, only while the case
--      is In Progress. The original automated outcome is kept on the rule; verdict and counts are
--      re-computed here so the client can never inflate them.
--      p_decisions: [{"id": "FS-02", "status": "PASS" | "FAIL", "note": "Green dot seen on the side panel"}]
-- ===========================================================================
create or replace function public.inspector_resolve_scan(p_token uuid, p_scan_code text, p_decisions jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me      uuid;
  v_dash    text;
  v_name    text;
  ps        public.package_scans%rowtype;
  i         public.inspections%rowtype;
  v_rules   jsonb;
  v_applied integer := 0;
  v_pass    integer;
  v_fail    integer;
  v_review  integer;
  v_na      integer;
  v_verdict text;
  v_score   numeric;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;
  if p_decisions is null or jsonb_typeof(p_decisions) <> 'array' or jsonb_array_length(p_decisions) = 0
     or jsonb_array_length(p_decisions) > 100 then
    return jsonb_build_object('ok', false, 'code', 'bad_report');
  end if;

  select * into ps from public.package_scans where scan_code = btrim(coalesce(p_scan_code, '')) for update;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  select * into i from public.inspections where id = ps.inspection_id and inspector_id = v_me;
  if not found then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;
  if i.status <> 'In Progress' then return jsonb_build_object('ok', false, 'code', 'bad_state'); end if;
  select full_name into v_name from public.officers where id = v_me;

  select coalesce(jsonb_agg(
           case
             when d.d is not null and r->>'status' = 'REVIEW' and d.d->>'status' in ('PASS', 'FAIL') then
               r || jsonb_build_object(
                 'status', d.d->>'status',
                 'reason', 'Inspector ' || case d.d->>'status' when 'PASS' then 'confirmed compliance' else 'confirmed a violation' end
                           || ' on the physical pack' || coalesce(': ' || nullif(left(btrim(d.d->>'note'), 300), ''), '.')
                           || ' (automated check: ' || coalesce(r->>'reason', 'needs review') || ')',
                 'resolution', jsonb_build_object('by', v_name, 'officer_uuid', v_me, 'at', now(), 'from', 'REVIEW',
                                                  'note', nullif(left(btrim(d.d->>'note'), 300), '')))
             else r
           end order by o.n), '[]'::jsonb),
         count(*) filter (where d.d is not null and r->>'status' = 'REVIEW' and d.d->>'status' in ('PASS', 'FAIL'))
    into v_rules, v_applied
    from jsonb_array_elements(coalesce(ps.report->'rules', '[]'::jsonb)) with ordinality o(r, n)
    left join lateral (
      select x as d from jsonb_array_elements(p_decisions) x where x->>'id' = o.r->>'id' limit 1
    ) d on true;
  if v_applied = 0 then return jsonb_build_object('ok', false, 'code', 'nothing_to_resolve'); end if;

  select count(*) filter (where r->>'status' = 'PASS'),
         count(*) filter (where r->>'status' = 'FAIL'),
         count(*) filter (where r->>'status' = 'REVIEW'),
         count(*) filter (where r->>'status' = 'NOT_APPLICABLE')
    into v_pass, v_fail, v_review, v_na
    from jsonb_array_elements(v_rules) r;
  v_verdict := case when v_fail > 0 then 'Failed' when v_review > 0 then 'Under Review' else 'Passed' end;
  v_score := case when v_pass + v_fail + v_review = 0 then 0
                  else round(100.0 * v_pass / (v_pass + v_fail + v_review), 1) end;
  -- Pass mark: a compliance score of 75% or more is a pass for inspector and organisation checks
  if v_pass + v_fail + v_review > 0 and v_score >= 75 then v_verdict := 'Passed'; end if;

  update public.package_scans
     set report = jsonb_set(jsonb_set(report, '{rules}', v_rules), '{summary}',
                            coalesce(report->'summary', '{}'::jsonb) || jsonb_build_object(
                              'verdict', case v_verdict when 'Failed' then 'FAIL' when 'Passed' then 'PASS' else 'REVIEW' end,
                              'passed', v_pass, 'failed', v_fail, 'review', v_review, 'not_applicable', v_na, 'score', v_score,
                              'resolved_by_inspector', true)),
         verdict = v_verdict, rules_passed = v_pass, rules_failed = v_fail, rules_review = v_review, rules_na = v_na,
         score = v_score, resolved_at = now()
   where id = ps.id;

  perform public._log_event(
    i.id, 'Scan', 'Review items resolved',
    ps.scan_code || ': ' || v_applied || ' rule(s) settled on the physical pack - now ' || v_verdict, v_me, 'Inspector');

  return jsonb_build_object('ok', true, 'code', 'ok', 'scan_code', ps.scan_code, 'resolved', v_applied, 'verdict', v_verdict,
                            'passed', v_pass, 'failed', v_fail, 'review', v_review, 'not_applicable', v_na, 'score', v_score);
end;
$$;

-- ===========================================================================
-- 11. inspector_submit_findings: close my field work and send the case to the authority
-- ===========================================================================
create or replace function public.inspector_submit_findings(p_token uuid, p_code text, p_remarks text default null)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_me    uuid;
  v_dash  text;
  i       public.inspections%rowtype;
  v_note  text := nullif(btrim(coalesce(p_remarks, '')), '');
  c       record;
  v_types text[];
  v_rate  numeric;
begin
  select s.officer_uuid, s.dashboard into v_me, v_dash from public._session_officer(p_token) s;
  if v_me is null then return jsonb_build_object('ok', false, 'code', 'session_expired'); end if;
  if v_dash <> 'INSPECTOR' then return jsonb_build_object('ok', false, 'code', 'forbidden'); end if;

  select * into i from public.inspections where code = btrim(coalesce(p_code, '')) and inspector_id = v_me for update;
  if not found then return jsonb_build_object('ok', false, 'code', 'not_found'); end if;
  if i.status <> 'In Progress' then return jsonb_build_object('ok', false, 'code', 'bad_state'); end if;

  select count(*) as total,
         count(*) filter (where verdict = 'Passed')       as passed,
         count(*) filter (where verdict = 'Failed')       as failed,
         count(*) filter (where verdict = 'Under Review') as review
    into c
    from public.package_scans where inspection_id = i.id;
  if c.total = 0 then return jsonb_build_object('ok', false, 'code', 'no_scans'); end if;

  select coalesce(array_agg(distinct public._violation_label(r->>'id')), '{}')
    into v_types
    from public.package_scans ps, jsonb_array_elements(coalesce(ps.report->'rules', '[]'::jsonb)) r
   where ps.inspection_id = i.id and r->>'status' = 'FAIL';

  v_rate := round(100.0 * c.passed / c.total, 1);

  update public.inspections
     set status = 'Under Review', review_submitted_at = now(),
         violations_count = c.failed, violation_types = v_types,
         remarks = coalesce(v_note, c.total || ' packages analysed: ' || c.passed || ' passed, ' || c.failed
                                    || ' failed, ' || c.review || ' under review (compliance ' || v_rate || '%).')
   where id = i.id;

  perform public._log_event(
    i.id, 'Review', 'Submitted for review',
    c.total || ' packages analysed - ' || c.passed || ' passed, ' || c.failed || ' failed, ' || c.review
      || ' under review (compliance ' || v_rate || '%)' || coalesce(' - ' || v_note, ''),
    v_me, 'Inspector');

  return jsonb_build_object('ok', true, 'code', 'ok', 'packages', c.total, 'compliance', v_rate);
end;
$$;

-- ===========================================================================
-- 12. Permissions
-- ===========================================================================
revoke all on function public.inspector_overview(uuid, integer)                 from public;
revoke all on function public.inspector_cases(uuid)                             from public;
revoke all on function public.inspector_case(uuid, text)                        from public;
revoke all on function public.inspector_scan(uuid, text)                        from public;
revoke all on function public.inspector_history(uuid)                          from public;
revoke all on function public.inspector_case_report(uuid, text)                 from public;
revoke all on function public.inspector_save_scan(uuid, text, jsonb, text)      from public;
revoke all on function public.inspector_resolve_scan(uuid, text, jsonb)         from public;
revoke all on function public.inspector_submit_findings(uuid, text, text)       from public;

grant execute on function public.inspector_overview(uuid, integer)              to anon, authenticated;
grant execute on function public.inspector_cases(uuid)                          to anon, authenticated;
grant execute on function public.inspector_case(uuid, text)                     to anon, authenticated;
grant execute on function public.inspector_scan(uuid, text)                     to anon, authenticated;
grant execute on function public.inspector_history(uuid)                        to anon, authenticated;
grant execute on function public.inspector_case_report(uuid, text)              to anon, authenticated;
grant execute on function public.inspector_save_scan(uuid, text, jsonb, text)   to anon, authenticated;
grant execute on function public.inspector_resolve_scan(uuid, text, jsonb)      to anon, authenticated;
grant execute on function public.inspector_submit_findings(uuid, text, text)    to anon, authenticated;

-- ===========================================================================
-- 13. DEMO DATA
-- ===========================================================================

-- Seed rule results for one package: compact {id, status, reason?} objects (titles come from the catalog)
create or replace function public._seed_rules(p_verdict text, p_h bigint, p_food boolean)
returns jsonb
language plpgsql
stable
set search_path = public, extensions
as $$
declare
  pool_f   text[] := array['LM-06', 'LM-07', 'LM-04', 'LM-05', 'LM-09', 'LM-08', 'LM-11', 'LM-13', 'LM-16', 'LM-01', 'LM-14', 'LM-12'];
  pool_r   text[] := array['LM-15', 'LM-12', 'LM-11', 'LM-01', 'LM-03', 'LM-09'];
  v_fail   text[] := '{}';
  v_review text[] := '{}';
  v_out    jsonb := '[]'::jsonb;
  v_status text;
  r        record;
begin
  if p_verdict = 'Failed' then
    v_fail := array[pool_f[1 + (p_h % 12)::int]];
    if p_h % 3 = 0 then v_fail := v_fail || pool_f[1 + ((p_h / 7) % 12)::int]; end if;
    if p_h % 5 = 0 then v_review := array[pool_r[1 + ((p_h / 11) % 6)::int]]; end if;
  elsif p_verdict = 'Under Review' then
    v_review := array[pool_r[1 + (p_h % 6)::int]];
    if p_h % 4 = 0 then v_review := v_review || pool_r[1 + ((p_h / 13) % 6)::int]; end if;
  end if;

  -- Demo history uses the 16 core rules every category shares
  for r in select * from public.compliance_rules where id ~ '^LM-(0[1-9]|1[0-6])$' order by sort_order loop
    v_status := case
      when r.id = any (v_fail) then 'FAIL'
      when r.id = any (v_review) then 'REVIEW'
      when r.id = 'LM-02' then 'NOT_APPLICABLE'
      when r.id = 'LM-10' and not p_food then 'NOT_APPLICABLE'
      else 'PASS'
    end;
    v_out := v_out || case
      when v_status = 'PASS' then jsonb_build_object('id', r.id, 'status', v_status)
      else jsonb_build_object('id', r.id, 'status', v_status, 'reason',
             case v_status when 'FAIL' then r.fail_reason when 'REVIEW' then r.review_reason else r.na_reason end)
    end;
  end loop;
  return v_out;
end;
$$;
revoke all on function public._seed_rules(text, bigint, boolean) from public, anon, authenticated;

do $seed$
declare
  c_password constant text := 'Welcome@123';
  v_insp_dsg  smallint;
  v_auth      uuid;
  v_auth_name text;
  v_me        uuid;
  v_my_name   text;
  v_hash      text := extensions.crypt(c_password, extensions.gen_salt('bf'));
  v_est       uuid[];
  v_case      uuid;
  v_code      text;
  v_status    text;
  v_day       date;
  v_sched     date;
  v_time      time;
  v_assigned  timestamptz;
  v_started   timestamptz;
  v_reviewed  timestamptz;
  v_done      timestamptz;
  v_n         integer;
  v_pass_p    numeric;
  v_prod      record;
  v_h         bigint;
  v_cnt       record;
  v_note      text;
  v_type      text;
  v_prio      text;
  k           integer;
begin
  select id into v_insp_dsg from public.designations where dashboard = 'INSPECTOR' order by id limit 1;
  select o.id, o.full_name into v_auth, v_auth_name
    from public.officers o join public.designations d on d.id = o.designation_id
   where d.dashboard = 'AUTHORITY' order by o.created_at, o.officer_id limit 1;
  if v_insp_dsg is null or v_auth is null then
    raise notice 'Run schema.sql and authority_module.sql first - inspector seed skipped';
    return;
  end if;

  -- -------------------------------------------------------------------------
  -- A. INS-003 Harshit Chaubey: full package-level history
  -- -------------------------------------------------------------------------
  insert into public.officers
    (officer_id, full_name, designation_id, password_hash, office, email, phone, employee_since,
     specialization, reports_to, last_login_at)
  values
    ('INS-003', 'Harshit Chaubey', v_insp_dsg, v_hash, 'Legal Metrology Office, Dehradun',
     'harshit.chaubey@lm-india.example', '+91 98370 41203', current_date - 90,
     'Packaged Commodities', v_auth, now() - interval '3 hours')
  on conflict (officer_id) do nothing;

  update public.officers
     set reports_to = coalesce(reports_to, v_auth),
         employee_since = coalesce(employee_since, current_date - 90),
         specialization = coalesce(specialization, 'Packaged Commodities'),
         office = coalesce(office, 'Legal Metrology Office, Dehradun')
   where officer_id = 'INS-003' and designation_id = v_insp_dsg;

  select id, full_name into v_me, v_my_name from public.officers where officer_id = 'INS-003' and designation_id = v_insp_dsg;

  if v_me is null then
    raise notice 'INS-003 exists but is not an inspector - personal seed skipped';
  elsif exists (select 1 from public.package_scans where inspector_id = v_me) then
    raise notice 'INS-003 already has package history - personal seed skipped';
  else
    insert into public.establishments (name, address, city, state, category)
    values
      ('Paltan Bazaar General Store',  'Paltan Bazaar',          'Dehradun',   'Uttarakhand', 'Retail'),
      ('Rajpur Road Fresh Mart',       '112 Rajpur Road',        'Dehradun',   'Uttarakhand', 'Supermarket'),
      ('Haridwar Road Wholesale Depot','Haridwar Bypass Road',   'Dehradun',   'Uttarakhand', 'Wholesale'),
      ('Clement Town Kirana',          'Subhash Nagar',          'Dehradun',   'Uttarakhand', 'Retail'),
      ('Prem Nagar Super Store',       'Chakrata Road',          'Dehradun',   'Uttarakhand', 'Supermarket'),
      ('Ganga Provisions Rishikesh',   'Ghat Road',              'Rishikesh',  'Uttarakhand', 'Retail'),
      ('Haldwani Mandi Traders',       'Nainital Road',          'Haldwani',   'Uttarakhand', 'Wholesale'),
      ('Roorkee Civil Lines Mart',     'Civil Lines',            'Roorkee',    'Uttarakhand', 'Supermarket'),
      ('Har Ki Pauri Daily Needs',     'Upper Road',             'Haridwar',   'Uttarakhand', 'Retail'),
      ('Mussoorie Mall Road Stores',   'Mall Road',              'Mussoorie',  'Uttarakhand', 'Retail')
    on conflict (name) do nothing;

    select array_agg(id order by name) into v_est
      from public.establishments where state = 'Uttarakhand';

    for k in 0..45 loop
      v_h := abs(hashtext('ins003-case-' || k))::bigint;

      select * into v_prod from (values
        (0,  'Premium Rice (1 kg)',            'Royal Harvest',    'Food Grains',   'Royal Harvest Foods Pvt Ltd, Karnal',      145.00, true),
        (1,  'Refined Oil (1 L)',              'Golden Drop',      'Edible Oils',   'Golden Drop Agro Ltd, Indore',             168.00, true),
        (2,  'Detergent Powder (500 g)',       'Sparkle Wash',     'Household',     'Sparkle Home Care Ltd, Baddi',              65.00, false),
        (3,  'Wheat Atta (5 kg)',              'Annapurna Fresh',  'Food Grains',   'Annapurna Mills, Haridwar',                285.00, true),
        (4,  'Toor Dal (1 kg)',                'Desi Pantry',      'Pulses',        'Desi Pantry Foods, Nagpur',                172.00, true),
        (5,  'Iodised Salt (1 kg)',            'Sagar Crystal',    'Staples',       'Sagar Salt Works, Gandhidham',              28.00, true),
        (6,  'Tea Powder (250 g)',             'Chai Junction',    'Beverages',     'Chai Junction Tea Co., Siliguri',          120.00, true),
        (7,  'Biscuits Family Pack (800 g)',   'Crunchy Days',     'Snacks',        'Crunchy Days Foods, Rudrapur',             120.00, true),
        (8,  'Mustard Oil (1 L)',              'Kachi Ghani Gold', 'Edible Oils',   'Gold Seed Oils Pvt Ltd, Alwar',            185.00, true),
        (9,  'Dishwash Liquid (500 ml)',       'Shine Plus',       'Household',     'Shine Plus Consumer, Haridwar',            110.00, false),
        (10, 'Sugar (1 kg)',                   'Madhur',           'Staples',       'Madhur Sugars Ltd, Muzaffarnagar',          52.00, true),
        (11, 'Instant Noodles (280 g)',        'Quick Bowl',       'Snacks',        'Quick Bowl Foods, Pantnagar',               56.00, true),
        (12, 'Toothpaste (150 g)',             'Dentafresh',       'Personal Care', 'Dentafresh Oral Care, Baddi',               99.00, false),
        (13, 'Shampoo (340 ml)',               'Silken Care',      'Personal Care', 'Silken Care Industries, Haridwar',         245.00, false),
        (14, 'Cow Ghee (500 ml)',              'Gau Amrit',        'Dairy',         'Gau Amrit Dairy Coop., Kashipur',          330.00, true),
        (15, 'Honey (500 g)',                  'Vana Madhu',       'Health Foods',  'Vana Madhu Naturals, Dehradun',            260.00, true)
      ) as p(i, product, brand, category, manufacturer, mrp, food)
      where p.i = (case when k < 4 then k else (v_h % 16)::int end);

      -- Case plan: 36 closed, 3 under review, 1 in progress, 2 assigned, 4 scheduled
      v_pass_p := least(0.95, 0.66 + 0.16 * k / 45.0 + ((v_h % 9) - 4) / 100.0);
      if k < 36 then
        v_day := current_date - 87 + (k * 85 / 35);
        v_status := 'Closed';
        v_n := 30 + (v_h % 46)::int;
      elsif k < 39 then
        v_day := current_date - case k when 36 then 3 when 37 then 2 else 1 end;
        v_status := 'Under Review';
        v_n := 38 + (v_h % 24)::int;
      elsif k = 39 then
        v_day := current_date;
        v_status := 'In Progress';
        v_n := 12;
      elsif k < 42 then
        v_day := null;
        v_status := 'Assigned';
        v_n := 0;
      else
        v_day := current_date + case k when 42 then 0 when 43 then 1 when 44 then 3 else 5 end;
        v_status := 'Scheduled';
        v_n := 0;
      end if;

      v_code := 'LM-' || to_char(current_date, 'YYYY') || '-' || lpad((k + 1)::text, 4, '0');
      v_time := ((array['09:30', '10:00', '10:30', '11:00', '14:00', '15:30'])[1 + (v_h % 6)::int])::time;
      v_type := (array['Routine Inspection', 'Surprise Inspection', 'Complaint-Based', 'Pre-Market Verification', 'Re-Inspection'])[1 + ((v_h / 3) % 5)::int];
      v_prio := case when v_h % 100 < 10 then 'Critical' when v_h % 100 < 35 then 'High' when v_h % 100 < 80 then 'Medium' else 'Low' end;
      v_assigned := case when v_day is null then now() - make_interval(hours => 18 + (v_h % 30)::int)
                         else least((v_day - 2)::timestamptz + make_interval(hours => 10 + (v_h % 6)::int), now() - interval '2 hours') end;
      v_started  := case when v_status in ('Closed', 'Under Review', 'In Progress')
                         then least((v_day + v_time)::timestamptz + make_interval(mins => (v_h % 15)::int), now() - interval '90 minutes') end;
      v_note := (array[
        'Consumer complaint about short weight - weigh 10 sample packs',
        'Verify MRP and unit sale price on shelf stock',
        'Festive season surprise drive - focus on mandatory declarations',
        'Re-check after last month''s non-compliance notice',
        'Carry the 20 mm ArUco calibration card for numeral height checks'])[1 + ((v_h / 5) % 5)::int];

      insert into public.inspections (
        code, establishment_id, inspector_id, authority_id, commodity, inspection_type, status,
        scheduled_date, scheduled_time, product_name, brand, category, manufacturer, batch_no, mrp,
        priority, source, complaint, submitted_at, assigned_at, due_date, started_at, created_at)
      values (
        v_code, v_est[1 + (v_h % array_length(v_est, 1))::int], v_me, v_auth, v_prod.category, v_type,
        case when v_status = 'Closed' then 'Passed' else v_status end,
        case when v_status = 'Assigned' then null else v_day end,
        case when v_status = 'Assigned' then null else v_time end,
        v_prod.product, v_prod.brand, v_prod.category, v_prod.manufacturer,
        'B' || to_char(coalesce(v_day, current_date), 'YYMM') || '-' || lpad((1000 + v_h % 9000)::text, 4, '0'),
        v_prod.mrp, v_prio,
        case when v_h % 10 < 7 then 'Website Portal' when v_h % 10 < 9 then 'Consumer Complaint' else 'Field Report' end,
        case v_type
          when 'Complaint-Based' then 'Consumer reported the net quantity is lower than the declared weight on the pack.'
          when 'Surprise Inspection' then 'Unannounced verification ordered by the Supreme Authority.'
          when 'Pre-Market Verification' then 'Mandatory declarations check before the product reaches retail shelves.'
          when 'Re-Inspection' then 'Follow-up verification after earlier non-compliance was reported.'
          else 'Periodic statutory verification of packaged commodities.'
        end,
        v_assigned - interval '6 hours', v_assigned,
        (v_assigned - interval '6 hours')::date + case v_prio when 'Critical' then 3 when 'High' then 7 when 'Medium' then 14 else 21 end,
        v_started, v_assigned - interval '6 hours')
      on conflict (code) do nothing
      returning id into v_case;

      continue when v_case is null;

      -- Packages analysed on site, a few minutes apart
      if v_n > 0 then
        insert into public.package_scans (
          scan_code, inspection_id, inspector_id, verdict, rules_passed, rules_failed, rules_review, rules_na,
          score, quality_grade, source, analyzer_version, duration_ms, report, created_at)
        select 'PKG-' || to_char(t.at, 'YY') || '-' || lpad(nextval('public.package_scan_seq')::text, 6, '0'),
               v_case, v_me, t.verdict,
               cnt.p, cnt.f, cnt.r, cnt.na,
               case when cnt.p + cnt.f + cnt.r = 0 then 0 else round(100.0 * cnt.p / (cnt.p + cnt.f + cnt.r), 1) end,
               t.grade, 'Seed', 'seed-1.0', 1800 + (t.h % 2600)::int,
               jsonb_build_object(
                 'schema', 'complyvision.package-report/1.0',
                 'seeded', true,
                 'analyzer_version', 'seed-1.0',
                 'generated_at', t.at,
                 'quality', jsonb_build_object('grade', t.grade, 'score', 62 + (t.h % 36)::int),
                 'declarations', jsonb_build_object(
                   'product', v_prod.product, 'brand', v_prod.brand, 'manufacturer', v_prod.manufacturer,
                   'mrp', v_prod.mrp, 'category', v_prod.category),
                 'summary', jsonb_build_object(
                   'verdict', case t.verdict when 'Passed' then 'PASS' when 'Failed' then 'FAIL' else 'REVIEW' end,
                   'passed', cnt.p, 'failed', cnt.f, 'review', cnt.r, 'not_applicable', cnt.na),
                 'rules', t.rules),
               t.at
          from (
            select j, h, at, verdict, grade, public._seed_rules(verdict, h, v_prod.food) as rules
              from (
                select j, h,
                       least(v_started + make_interval(mins => j * 3 + (h % 3)::int), now() - interval '5 minutes') as at,
                       case when (h % 1000) / 1000.0 < v_pass_p then 'Passed'
                            when (h % 1000) / 1000.0 < v_pass_p + (1 - v_pass_p) * 0.66 then 'Failed'
                            else 'Under Review' end as verdict,
                       case when h % 10 < 6 then 'Good' when h % 10 < 9 then 'Fair' else 'Poor' end as grade
                  from (select j, abs(hashtext(v_code || ':' || j))::bigint as h from generate_series(1, v_n) j) s0
              ) s1
          ) t
          cross join lateral (
            select count(*) filter (where r->>'status' = 'PASS')           as p,
                   count(*) filter (where r->>'status' = 'FAIL')           as f,
                   count(*) filter (where r->>'status' = 'REVIEW')         as r,
                   count(*) filter (where r->>'status' = 'NOT_APPLICABLE') as na
              from jsonb_array_elements(t.rules) r
          ) cnt;
      end if;

      select count(*) as total,
             count(*) filter (where verdict = 'Passed') as passed,
             count(*) filter (where verdict = 'Failed') as failed,
             count(*) filter (where verdict = 'Under Review') as review,
             max(created_at) as last_at
        into v_cnt
        from public.package_scans where inspection_id = v_case;

      v_reviewed := case when v_status in ('Closed', 'Under Review') then v_cnt.last_at + make_interval(mins => 25 + (v_h % 50)::int) end;
      v_done := case when v_status = 'Closed' then v_reviewed + make_interval(hours => 8 + (v_h % 30)::int) end;
      if v_done is not null and v_done > now() then v_done := now() - interval '30 minutes'; end if;

      if v_status in ('Closed', 'Under Review') then
        update public.inspections
           set status = case when v_status = 'Under Review' then 'Under Review'
                             when v_cnt.failed::numeric / greatest(v_cnt.total, 1) > 0.19 then 'Failed'
                             else 'Passed' end,
               review_submitted_at = v_reviewed,
               completed_at = v_done,
               violations_count = v_cnt.failed,
               violation_types = coalesce((
                 select array_agg(distinct public._violation_label(r->>'id'))
                   from public.package_scans ps, jsonb_array_elements(ps.report->'rules') r
                  where ps.inspection_id = v_case and r->>'status' = 'FAIL'), '{}'),
               remarks = v_cnt.total || ' packages analysed: ' || v_cnt.passed || ' passed, ' || v_cnt.failed
                         || ' failed, ' || v_cnt.review || ' under review (compliance '
                         || round(100.0 * v_cnt.passed / greatest(v_cnt.total, 1), 1) || '%).'
         where id = v_case;
      end if;

      -- Track record
      insert into public.inspection_events (inspection_id, event_type, title, detail, actor_name, actor_role, created_at)
      select v_case, x.t, x.title, x.detail, x.actor, x.role, x.at
        from (values
          ('Submitted', 'Case submitted', 'Filed for "' || v_prod.product || '"', 'Legal Metrology Portal', 'System', v_assigned - interval '6 hours'),
          ('Routed', 'Assigned to Supreme Authority', 'Routed to ' || v_auth_name || ' for allocation', 'Legal Metrology Portal', 'System', v_assigned - interval '5 hours 55 minutes'),
          ('Assigned', 'Assigned to inspector', v_my_name || ' (INS-003) - ' || v_note, v_auth_name, 'Supreme Authority', v_assigned)
        ) as x(t, title, detail, actor, role, at);

      if v_status <> 'Assigned' then
        insert into public.inspection_events (inspection_id, event_type, title, detail, actor_name, actor_role, created_at)
        values (v_case, 'Scheduled', 'Inspection scheduled', to_char(v_day, 'DD Mon YYYY') || ' at ' || to_char(v_time, 'HH24:MI'),
                v_auth_name, 'Supreme Authority', v_assigned + interval '10 minutes');
      end if;
      if v_started is not null then
        insert into public.inspection_events (inspection_id, event_type, title, detail, actor_name, actor_role, created_at)
        values (v_case, 'Started', 'Inspection started', 'On-site verification began', v_my_name, 'Inspector', v_started);
      end if;
      if v_cnt.total > 0 then
        insert into public.inspection_events (inspection_id, event_type, title, detail, actor_name, actor_role, created_at)
        values (v_case, 'Scan', 'Packages analysed',
                v_cnt.total || ' packages scanned with the ComplyVision analyzer: ' || v_cnt.passed || ' passed, '
                  || v_cnt.failed || ' failed, ' || v_cnt.review || ' under review',
                v_my_name, 'Inspector', v_cnt.last_at);
      end if;
      if v_reviewed is not null then
        insert into public.inspection_events (inspection_id, event_type, title, detail, actor_name, actor_role, created_at)
        values (v_case, 'Review', 'Submitted for review',
                v_cnt.total || ' packages analysed - ' || v_cnt.passed || ' passed, ' || v_cnt.failed || ' failed, '
                  || v_cnt.review || ' under review',
                v_my_name, 'Inspector', v_reviewed);
      end if;
      if v_done is not null then
        insert into public.inspection_events (inspection_id, event_type, title, detail, actor_name, actor_role, created_at)
        select v_case, 'Result',
               case i.status when 'Passed' then 'Inspection passed' else 'Inspection failed - ' || i.violations_count || ' violation(s)' end,
               i.remarks, v_auth_name, 'Supreme Authority', v_done
          from public.inspections i where i.id = v_case;
      end if;
    end loop;

    raise notice 'INS-003 Harshit Chaubey seeded (password % if the officer was created by this script)', c_password;
  end if;

  -- -------------------------------------------------------------------------
  -- B. Every other demo inspector: a lighter package history for recent closed / reviewed cases
  -- -------------------------------------------------------------------------
  if not exists (
    select 1 from public.package_scans ps join public.inspections i on i.id = ps.inspection_id where i.code like 'CV-%'
  ) then
    insert into public.package_scans (
      scan_code, inspection_id, inspector_id, verdict, rules_passed, rules_failed, rules_review, rules_na,
      score, quality_grade, source, analyzer_version, duration_ms, report, created_at)
    select 'PKG-' || to_char(t.at, 'YY') || '-' || lpad(nextval('public.package_scan_seq')::text, 6, '0'),
           t.iid, t.inspector_id, t.verdict, cnt.p, cnt.f, cnt.r, cnt.na,
           case when cnt.p + cnt.f + cnt.r = 0 then 0 else round(100.0 * cnt.p / (cnt.p + cnt.f + cnt.r), 1) end,
           t.grade, 'Seed', 'seed-1.0', 1800 + (t.h % 2600)::int,
           jsonb_build_object(
             'schema', 'complyvision.package-report/1.0', 'seeded', true, 'analyzer_version', 'seed-1.0',
             'generated_at', t.at,
             'quality', jsonb_build_object('grade', t.grade, 'score', 62 + (t.h % 36)::int),
             'declarations', jsonb_build_object('product', t.product, 'brand', t.brand, 'mrp', t.mrp, 'category', t.category),
             'summary', jsonb_build_object(
               'verdict', case t.verdict when 'Passed' then 'PASS' when 'Failed' then 'FAIL' else 'REVIEW' end,
               'passed', cnt.p, 'failed', cnt.f, 'review', cnt.r, 'not_applicable', cnt.na),
             'rules', t.rules),
           t.at
      from (
        select s1.*, public._seed_rules(s1.verdict, s1.h, s1.food) as rules
          from (
            select i.id as iid, i.inspector_id, coalesce(i.product_name, i.commodity) as product, i.brand, i.mrp,
                   coalesce(i.category, i.commodity) as category,
                   coalesce(i.category, i.commodity) in ('Food Grains', 'Edible Oils', 'Pulses', 'Staples', 'Beverages',
                     'Packaged Water', 'Dairy', 'Snacks', 'Health Foods', 'Baby Care', 'Dry Fruits') as food,
                   g.h,
                   least(coalesce(i.started_at, i.submitted_at) + make_interval(mins => g.j * 4), coalesce(i.review_submitted_at, now()) - interval '1 minute') as at,
                   case
                     when (g.h % 1000) / 1000.0 < case i.status when 'Passed' then 0.9 when 'Failed' then 0.52 else 0.74 end then 'Passed'
                     when (g.h % 1000) / 1000.0 < case i.status when 'Passed' then 0.95 when 'Failed' then 0.86 else 0.86 end then 'Failed'
                     else 'Under Review'
                   end as verdict,
                   case when g.h % 10 < 6 then 'Good' when g.h % 10 < 9 then 'Fair' else 'Poor' end as grade
              from public.inspections i
              cross join lateral (
                select j, abs(hashtext(i.code || ':' || j))::bigint as h
                  from generate_series(1, 2 + (abs(hashtext(i.code || 'n'))::bigint % 6)::int) j
              ) g
             where i.code like 'CV-%'
               and i.inspector_id is not null
               and i.status in ('Passed', 'Failed', 'Under Review')
               and i.result_at >= now() - interval '75 days'
          ) s1
      ) t
      cross join lateral (
        select count(*) filter (where r->>'status' = 'PASS')           as p,
               count(*) filter (where r->>'status' = 'FAIL')           as f,
               count(*) filter (where r->>'status' = 'REVIEW')         as r,
               count(*) filter (where r->>'status' = 'NOT_APPLICABLE') as na
          from jsonb_array_elements(t.rules) r
      ) cnt;

    raise notice 'Package history seeded for the other demo inspectors';
  end if;
end
$seed$;
