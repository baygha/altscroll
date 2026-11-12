# AltScroll — setup

Three things to wire up: the Supabase project, the moderation API key, and the
Android app's local credentials.

---

## 1. Supabase project

1. Create a project at [supabase.com](https://supabase.com) (free tier is fine).
2. From **Project Settings → API**, copy:
   - **Project URL** → `SUPABASE_URL`
   - **anon public** key → `SUPABASE_ANON_KEY`
   - **service_role** key → used only by the edge function, never by the app
3. Apply the schema. Either paste `supabase/migrations/0001_init.sql` into the
   SQL editor, or with the CLI:

   ```bash
   npm install -g supabase
   supabase login
   supabase link --project-ref <your-project-ref>
   supabase db push
   ```

4. Optional, so the feed isn't empty on first run — load the sample posts:

   ```bash
   supabase db execute --file supabase/seed.sql
   ```

5. Under **Authentication → Providers**, email/password is on by default. While
   developing, turning **Confirm email** off under **Authentication → Sign In /
   Providers** lets you sign in immediately after sign-up instead of waiting for
   a confirmation mail.

---

## 2. Moderation API key (Groq, free tier)

The post classifier runs server-side in an edge function, so the key never ships
inside the APK.

1. Get a free key at [console.groq.com/keys](https://console.groq.com/keys) — no
   credit card required.
2. Store it as a Supabase secret and deploy the function:

   ```bash
   supabase secrets set GROQ_API_KEY=gsk_your_key_here
   supabase functions deploy moderate-post
   ```

`SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `SUPABASE_SERVICE_ROLE_KEY` are injected
into edge functions automatically — you do not set those yourself.

### Free-tier budget

`openai/gpt-oss-20b` on Groq's free plan allows 30 requests/minute, 1,000
requests/day, and 200,000 tokens/day. A moderation call costs roughly 350 tokens,
so the token cap binds first at about **550 posts per day**. The `prefilter()`
in `supabase/functions/_shared/moderation.ts` rejects posts under 12 words
without spending a request.

### Swapping providers

`_shared/moderation.ts` defines a `Classifier` interface. To move to Gemini,
Anthropic, or a self-hosted model, add a class implementing `classify()` and
return it from `buildClassifier()`. Nothing else changes — not the function, not
the app, not the schema.

---

## 3. Android app

Put the two client-side values in `local.properties` (git-ignored):

```properties
sdk.dir=C\:/Android
SUPABASE_URL=https://<your-project-ref>.supabase.co
SUPABASE_ANON_KEY=<your anon key>
```

The anon key is safe on the client — every table is behind row level security,
and the `posts_force_pending` trigger means a tampered client still cannot
publish an unapproved post.

Build and install:

```bash
gradle :app:assembleDebug
C:/Android/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

If the app opens on a "Supabase is not configured" screen, the two values are
missing from `local.properties` — add them and rebuild (BuildConfig is generated
at compile time, so a reinstall alone won't pick them up).

---

## How moderation is enforced

The security property is that **the client never decides a post's status**:

1. The app calls the `moderate-post` edge function with the draft and the user's
   JWT. It does not insert into `posts` at all.
2. The function verifies the JWT, classifies the draft, then inserts using the
   **service role**.
3. If a modified client inserted into `posts` directly, the
   `posts_force_pending` trigger would reset `status` to `pending`, and the feed
   query only returns `approved` rows.

So the worst a tampered client achieves is a post nobody can see.
