# Connect the website to the shared ComplyVision Supabase project

The website and Android app must use the same Supabase project. Do not create replacement tables or apply the example schema from an unrelated project. Inspect the Android app's existing migrations, tables, columns, RLS policies, and Storage buckets first. Propose additive, versioned migrations for review before applying anything to the shared project.

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

## 2. Authentication and current website integration

When the project URL and publishable key are configured, the website's existing login form uses Supabase Auth `signInWithPassword`. Legal Metrology officer IDs are converted to the synthetic email `<officer-id>@lm.complyvision.local`; organisation users enter their email. The website checks the account's non-user-editable `app_metadata.role`, then routes officer IDs beginning with `S` to the Supreme Authority dashboard and other officer IDs to the Inspector dashboard. Make sure corresponding Auth users and login IDs are provisioned by a trusted process before using this flow. Without the key, the site uses a local preview sign-in and in-memory portal API; entered passwords are not verified and preview changes are discarded when the API restarts.

The Supabase JavaScript client is initialized in `src/lib/supabaseClient.js`. If the URL or publishable key is missing, the website uses the explicitly marked local preview mode. Inspection history is still browser-local. Do not connect it to a guessed table; map it to the existing Android schema after that schema and its RLS policies have been inspected.

## 3. Run locally and deploy

Start Vite with `npm run dev` from `frontend`. For deployment, add the same two environment variables to the hosting provider's environment settings. Review the table's RLS policies and the Storage bucket policies before using real customer data.

Official references: [Supabase React quickstart](https://supabase.com/docs/guides/getting-started/quickstarts/reactjs) · [Supabase API keys](https://supabase.com/docs/guides/getting-started/api-keys)
