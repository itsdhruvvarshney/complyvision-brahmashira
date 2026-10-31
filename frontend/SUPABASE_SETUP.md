# Connect ComplyVision to Supabase

The app currently runs in demo mode. Inspection history and settings are stored in the browser, and image analysis uses sample results. These steps create the Supabase foundation; they do not switch the demo service over automatically.

## 1. Create a project and add the client

Create a project in the [Supabase Dashboard](https://database.new), then from the `frontend` directory install the JavaScript client:

```sh
npm install @supabase/supabase-js
```

In the project's **Connect** panel, copy the Project URL and publishable key. Add them to `frontend/.env.local`:

```dotenv
VITE_SUPABASE_URL=https://YOUR_PROJECT.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

`.env.local` is ignored by Git. Restart Vite after changing it. Only publishable keys belong in browser code; never put a Supabase secret key or legacy `service_role` key in a `VITE_` variable.

## 2. Create an inspections table with owner-only access

In the Supabase Dashboard's SQL Editor, run:

```sql
create table public.inspections (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  payload jsonb not null,
  created_at timestamptz not null default now()
);

alter table public.inspections enable row level security;
grant select, insert, update, delete on public.inspections to authenticated;

create policy "Users can read their inspections"
  on public.inspections for select to authenticated
  using ((select auth.uid()) = owner_id);

create policy "Users can create their inspections"
  on public.inspections for insert to authenticated
  with check ((select auth.uid()) = owner_id);

create policy "Users can update their inspections"
  on public.inspections for update to authenticated
  using ((select auth.uid()) = owner_id)
  with check ((select auth.uid()) = owner_id);

create policy "Users can delete their inspections"
  on public.inspections for delete to authenticated
  using ((select auth.uid()) = owner_id);
```

These policies require Supabase Auth sign-in. Keep the table unavailable to the `anon` role; add access only through policies that match your actual product roles.

## 3. Initialize the client

Create `frontend/src/lib/supabaseClient.js`:

```js
import { createClient } from '@supabase/supabase-js'

const url = import.meta.env.VITE_SUPABASE_URL
const publishableKey = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY

if (!url || !publishableKey) {
  throw new Error('Set VITE_SUPABASE_URL and VITE_SUPABASE_PUBLISHABLE_KEY in frontend/.env.local')
}

export const supabase = createClient(url, publishableKey)
```

Then a signed-in user's inspections can be queried with:

```js
const { data, error } = await supabase
  .from('inspections')
  .select('id, payload, created_at')
  .order('created_at', { ascending: false })

if (error) throw error
```

Supabase applies the row policies to this query. Once Auth is wired, replace the `localStorage` reads and writes in `src/services/inspectionService.js` with database calls. Keep image/OCR analysis behind a backend or Edge Function; the browser should upload files to a private Storage bucket and call the analysis endpoint, then save the returned result as the inspection `payload`.

## 4. Run locally and deploy

Start Vite with `npm run dev` from `frontend`. For deployment, add the same two environment variables to the hosting provider's environment settings. Review the table's RLS policies and the Storage bucket policies before using real customer data.

Official references: [Supabase React quickstart](https://supabase.com/docs/guides/getting-started/quickstarts/reactjs) · [Supabase API keys](https://supabase.com/docs/guides/getting-started/api-keys)
