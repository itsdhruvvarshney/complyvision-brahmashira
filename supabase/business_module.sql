-- ComplyVision: Business portal data model on Supabase Auth (profiles, organizations, label inspections, rules, reports).
-- Run once in the Supabase SQL editor. Safe to re-run (idempotent).
--
-- Naming: `inspections` already exists (officer field inspections, schema.sql), so label-scan
-- records here use the `label_` prefix. Identity is Supabase Auth (auth.users); no passwords here.
-- Open decision: inspector scope. Implemented as "inspectors/admins in organization_members with
-- role 'inspector' / 'admin' can read every organization's label inspections". Tighten in is_staff().

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------
create table if not exists public.profiles (
  user_id      uuid primary key references auth.users (id) on delete cascade,
  display_name text,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);

create table if not exists public.organizations (
  id            uuid primary key default gen_random_uuid(),
  name          text not null,
  business_type text not null,
  owner_name    text not null,
  status        text not null default 'pending' check (status in ('pending', 'active', 'suspended')),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

create table if not exists public.organization_members (
  organization_id uuid not null references public.organizations (id) on delete cascade,
  user_id         uuid not null references auth.users (id) on delete cascade,
  role            text not null check (role in ('business_owner', 'business_member', 'inspector', 'admin')),
  status          text not null default 'active' check (status in ('active', 'invited', 'disabled')),
  created_at      timestamptz not null default now(),
  primary key (organization_id, user_id)
);
create index if not exists organization_members_user_idx on public.organization_members (user_id);

create table if not exists public.label_inspections (
  id              uuid primary key default gen_random_uuid(),
  organization_id uuid references public.organizations (id),
  created_by      uuid not null references auth.users (id),
  product_name    text not null,
  brand           text,
  status          text not null check (status in ('PASS', 'FAIL', 'REVIEW', 'NOT_APPLICABLE')),
  confidence      numeric check (confidence between 0 and 1),
  summary         text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
create index if not exists label_inspections_org_idx on public.label_inspections (organization_id, created_at desc);
create index if not exists label_inspections_creator_idx on public.label_inspections (created_by);

create table if not exists public.label_inspection_images (
  id            uuid primary key default gen_random_uuid(),
  inspection_id uuid not null references public.label_inspections (id) on delete cascade,
  bucket_id     text not null default 'inspection-images',
  storage_path  text not null unique,
  side          text check (side in ('front', 'back', 'left', 'right', 'top', 'bottom', 'other')),
  mime_type     text not null,
  file_size     bigint check (file_size >= 0),
  uploaded_by   uuid not null references auth.users (id),
  created_at    timestamptz not null default now()
);
create index if not exists label_inspection_images_insp_idx on public.label_inspection_images (inspection_id);

create table if not exists public.extracted_fields (
  id              uuid primary key default gen_random_uuid(),
  inspection_id   uuid not null references public.label_inspections (id) on delete cascade,
  field_key       text not null,   -- quantity, mrp, packing-date, care ...
  label           text not null,
  extracted_value text,
  confidence      numeric check (confidence between 0 and 1),
  status          text not null,
  evidence_region jsonb,           -- normalized bounding box {x,y,w,h}
  created_at      timestamptz not null default now()
);
create index if not exists extracted_fields_insp_idx on public.extracted_fields (inspection_id);

create table if not exists public.rule_sets (
  id             uuid primary key default gen_random_uuid(),
  name           text not null,
  jurisdiction   text not null,
  version        text not null,
  effective_from date not null,
  effective_to   date,
  is_active      boolean not null default true,
  unique (name, jurisdiction, version)
);

create table if not exists public.rules (
  id          uuid primary key default gen_random_uuid(),
  rule_set_id uuid not null references public.rule_sets (id) on delete cascade,
  code        text not null,
  reference   text,
  title       text not null,
  description text,
  severity    text not null default 'major' check (severity in ('minor', 'major', 'critical')),
  is_active   boolean not null default true,
  unique (rule_set_id, code)
);

create table if not exists public.label_rule_results (
  id            uuid primary key default gen_random_uuid(),
  inspection_id uuid not null references public.label_inspections (id) on delete cascade,
  rule_id       uuid references public.rules (id),
  field_id      uuid references public.extracted_fields (id) on delete set null,
  rule_version  text not null,
  status        text not null,
  explanation   text,
  checked_at    timestamptz not null default now()
);
create index if not exists label_rule_results_insp_idx on public.label_rule_results (inspection_id);

create table if not exists public.reports (
  id            uuid primary key default gen_random_uuid(),
  inspection_id uuid not null references public.label_inspections (id) on delete cascade,
  format        text not null check (format in ('pdf', 'json')),
  bucket_id     text not null default 'inspection-reports',
  storage_path  text not null unique,
  generated_by  uuid not null references auth.users (id),
  generated_at  timestamptz not null default now()
);
create index if not exists reports_insp_idx on public.reports (inspection_id);

create table if not exists public.user_preferences (
  user_id           uuid primary key references auth.users (id) on delete cascade,
  summary_language  text not null default 'en',
  concise_summaries boolean not null default false,
  display           jsonb not null default '{}'::jsonb,
  updated_at        timestamptz not null default now()
);

create table if not exists public.audit_events (
  id              bigint generated always as identity primary key,
  actor_id        uuid references auth.users (id) on delete set null,
  organization_id uuid references public.organizations (id) on delete set null,
  action          text not null,
  resource_type   text,
  resource_id     uuid,
  created_at      timestamptz not null default now()
);
create index if not exists audit_events_org_idx on public.audit_events (organization_id, created_at desc);

-- Optional tables (uncomment when the feature ships)
-- create table public.support_requests (id uuid primary key default gen_random_uuid(), user_id uuid references auth.users(id), subject text not null, message text not null, status text not null default 'open', created_at timestamptz not null default now());
-- create table public.notifications (id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade, title text not null, body text, read_at timestamptz, created_at timestamptz not null default now());

-- ---------------------------------------------------------------------------
-- Helpers (SECURITY DEFINER so policies on organization_members do not recurse)
-- ---------------------------------------------------------------------------
create or replace function public.is_org_member(p_org uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.organization_members m
    where m.organization_id = p_org and m.user_id = auth.uid() and m.status = 'active'
  );
$$;

create or replace function public.is_staff()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.organization_members m
    where m.user_id = auth.uid() and m.status = 'active' and m.role in ('inspector', 'admin')
  );
$$;

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.organization_members m
    where m.user_id = auth.uid() and m.status = 'active' and m.role = 'admin'
  );
$$;

create or replace function public.can_access_inspection(p_inspection uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.label_inspections i
    where i.id = p_inspection
      and (i.created_by = auth.uid()
           or (i.organization_id is not null and public.is_org_member(i.organization_id))
           or public.is_staff())
  );
$$;

revoke all on function public.is_org_member(uuid), public.is_staff(), public.is_admin(),
                       public.can_access_inspection(uuid) from public, anon;
grant execute on function public.is_org_member(uuid), public.is_staff(), public.is_admin(),
                          public.can_access_inspection(uuid) to authenticated;

-- ---------------------------------------------------------------------------
-- Triggers
-- ---------------------------------------------------------------------------
create or replace function public._touch_updated_at()
returns trigger language plpgsql as $$
begin new.updated_at := now(); return new; end;
$$;

do $$
declare t text;
begin
  foreach t in array array['profiles', 'organizations', 'label_inspections', 'user_preferences'] loop
    execute format('drop trigger if exists touch_%1$s on public.%1$s', t);
    execute format('create trigger touch_%1$s before update on public.%1$s for each row execute function public._touch_updated_at()', t);
  end loop;
end $$;

-- Auto-create profile + preferences when a Supabase Auth user is created
create or replace function public._handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (user_id, display_name)
  values (new.id, coalesce(new.raw_user_meta_data ->> 'display_name', split_part(new.email, '@', 1)))
  on conflict do nothing;
  insert into public.user_preferences (user_id) values (new.id) on conflict do nothing;
  return new;
end;
$$;
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public._handle_new_user();

-- Registration: verified user creates an organization and becomes its owner
create or replace function public.register_organization(p_name text, p_business_type text, p_owner_name text)
returns uuid language plpgsql security definer set search_path = public as $$
declare v_org uuid;
begin
  if auth.uid() is null then raise exception 'not authenticated'; end if;
  insert into public.organizations (name, business_type, owner_name)
  values (p_name, p_business_type, p_owner_name) returning id into v_org;
  insert into public.organization_members (organization_id, user_id, role)
  values (v_org, auth.uid(), 'business_owner');
  insert into public.audit_events (actor_id, organization_id, action, resource_type, resource_id)
  values (auth.uid(), v_org, 'organization.registered', 'organization', v_org);
  return v_org;
end;
$$;
revoke all on function public.register_organization(text, text, text) from public, anon;
grant execute on function public.register_organization(text, text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- Row Level Security + grants
-- ---------------------------------------------------------------------------
alter table public.profiles                enable row level security;
alter table public.organizations           enable row level security;
alter table public.organization_members    enable row level security;
alter table public.label_inspections       enable row level security;
alter table public.label_inspection_images enable row level security;
alter table public.extracted_fields        enable row level security;
alter table public.rule_sets               enable row level security;
alter table public.rules                   enable row level security;
alter table public.label_rule_results      enable row level security;
alter table public.reports                 enable row level security;
alter table public.user_preferences        enable row level security;
alter table public.audit_events            enable row level security;

revoke all on public.profiles, public.organizations, public.organization_members,
              public.label_inspections, public.label_inspection_images, public.extracted_fields,
              public.rule_sets, public.rules, public.label_rule_results, public.reports,
              public.user_preferences, public.audit_events
  from anon, authenticated;

grant select, insert, update on public.profiles, public.user_preferences to authenticated;
grant select, update on public.organizations to authenticated;
grant select on public.organization_members, public.audit_events to authenticated;
grant select, insert, update, delete on public.label_inspections to authenticated;
grant select, insert, delete on public.label_inspection_images, public.extracted_fields,
                                public.label_rule_results, public.reports to authenticated;
grant select, insert, update, delete on public.rule_sets, public.rules to authenticated;

-- profiles / preferences: own row only
drop policy if exists profiles_own on public.profiles;
create policy profiles_own on public.profiles for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
drop policy if exists preferences_own on public.user_preferences;
create policy preferences_own on public.user_preferences for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- organizations: members read; owners/admins update
drop policy if exists organizations_read on public.organizations;
create policy organizations_read on public.organizations for select to authenticated
  using (public.is_org_member(id) or public.is_staff());
drop policy if exists organizations_update on public.organizations;
create policy organizations_update on public.organizations for update to authenticated
  using (public.is_admin() or exists (
    select 1 from public.organization_members m
    where m.organization_id = id and m.user_id = auth.uid() and m.role = 'business_owner' and m.status = 'active'))
  with check (true);

-- memberships: see own row and teammates
drop policy if exists members_read on public.organization_members;
create policy members_read on public.organization_members for select to authenticated
  using (user_id = auth.uid() or public.is_org_member(organization_id) or public.is_admin());

-- label inspections
drop policy if exists label_inspections_read on public.label_inspections;
create policy label_inspections_read on public.label_inspections for select to authenticated
  using (created_by = auth.uid()
         or (organization_id is not null and public.is_org_member(organization_id))
         or public.is_staff());
drop policy if exists label_inspections_insert on public.label_inspections;
create policy label_inspections_insert on public.label_inspections for insert to authenticated
  with check (created_by = auth.uid()
              and (organization_id is null or public.is_org_member(organization_id)));
drop policy if exists label_inspections_update on public.label_inspections;
create policy label_inspections_update on public.label_inspections for update to authenticated
  using (created_by = auth.uid() or public.is_admin()) with check (created_by = auth.uid() or public.is_admin());
drop policy if exists label_inspections_delete on public.label_inspections;
create policy label_inspections_delete on public.label_inspections for delete to authenticated
  using (created_by = auth.uid() or public.is_admin());

-- children of an inspection follow the parent's access
do $$
declare t text;
begin
  foreach t in array array['label_inspection_images', 'extracted_fields', 'label_rule_results', 'reports'] loop
    execute format('drop policy if exists %1$s_read on public.%1$s', t);
    execute format('create policy %1$s_read on public.%1$s for select to authenticated using (public.can_access_inspection(inspection_id))', t);
    execute format('drop policy if exists %1$s_insert on public.%1$s', t);
    execute format('create policy %1$s_insert on public.%1$s for insert to authenticated with check (public.can_access_inspection(inspection_id))', t);
    execute format('drop policy if exists %1$s_delete on public.%1$s', t);
    execute format('create policy %1$s_delete on public.%1$s for delete to authenticated using (public.can_access_inspection(inspection_id) and (public.is_admin() or exists (select 1 from public.label_inspections i where i.id = inspection_id and i.created_by = auth.uid())))', t);
  end loop;
end $$;

-- rules: signed-in users read active; admins edit
drop policy if exists rule_sets_read on public.rule_sets;
create policy rule_sets_read on public.rule_sets for select to authenticated using (is_active or public.is_admin());
drop policy if exists rule_sets_admin on public.rule_sets;
create policy rule_sets_admin on public.rule_sets for all to authenticated
  using (public.is_admin()) with check (public.is_admin());
drop policy if exists rules_read on public.rules;
create policy rules_read on public.rules for select to authenticated using (is_active or public.is_admin());
drop policy if exists rules_admin on public.rules;
create policy rules_admin on public.rules for all to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- audit: members read their organization's events; writes only via SECURITY DEFINER functions / backend
drop policy if exists audit_read on public.audit_events;
create policy audit_read on public.audit_events for select to authenticated
  using (actor_id = auth.uid() or (organization_id is not null and public.is_org_member(organization_id)) or public.is_admin());

-- ---------------------------------------------------------------------------
-- Storage: private buckets; path = {user_id}/{inspection_id}/{side}/{filename}
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public)
values ('inspection-images', 'inspection-images', false),
       ('inspection-reports', 'inspection-reports', false)
on conflict (id) do update set public = false;

drop policy if exists "cv images read" on storage.objects;
create policy "cv images read" on storage.objects for select to authenticated
  using (bucket_id in ('inspection-images', 'inspection-reports')
         and (
           (storage.foldername(name))[1] = auth.uid()::text
           or public.can_access_inspection(nullif((storage.foldername(name))[2], '')::uuid)
         ));
drop policy if exists "cv images insert" on storage.objects;
create policy "cv images insert" on storage.objects for insert to authenticated
  with check (bucket_id in ('inspection-images', 'inspection-reports')
              and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists "cv images delete" on storage.objects;
create policy "cv images delete" on storage.objects for delete to authenticated
  using (bucket_id in ('inspection-images', 'inspection-reports')
         and (storage.foldername(name))[1] = auth.uid()::text);
