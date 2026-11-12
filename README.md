# AltScroll

An Android app with the scrolling habit of social media and none of the content.
The feed carries only informational and scientific posts, and every submission is
screened by an LLM before it can appear.

Setup instructions: [docs/SETUP.md](docs/SETUP.md)

## What works now

- **Email auth** — sign up / sign in via Supabase Auth; a `profiles` row is
  created automatically by a trigger on `auth.users`.
- **Scroll feed** — full-screen snapping `VerticalPager`, one post per viewport,
  keyset-paginated 10 at a time with prefetch three pages ahead. Likes are
  optimistic and reconcile on failure.
- **Post composer** — drafts go to an edge function that classifies them as
  informational/scientific or not, and the verdict is shown with its reason.
- **Chat** — Messenger-style 1:1 threads over Supabase Realtime, with user
  search, unread markers, and read receipts.
- **Peter, the AI agent** — a real user account (`@peter`, flagged `is_bot`)
  that publishes a post every 30 minutes via `pg_cron` and answers chat
  messages via a trigger on `messages`. Badged **AI** everywhere his name
  appears.

## Layout

```
app/src/main/java/com/altscroll/app/
  data/
    SupabaseClient.kt        single configured client
    model/Models.kt          serializable DTOs
    repo/                    AuthRepository, FeedRepository, ChatRepository
  ui/
    auth/   feed/   post/   chat/   nav/   theme/   common/
  MainActivity.kt            session gate: splash / auth / app

supabase/
  migrations/0001_init.sql   tables, triggers, RLS, realtime, feed_page()
  seed.sql                   six sample posts
  functions/
    _shared/groq.ts          shared Groq chat client
    _shared/moderation.ts    Classifier interface + Groq implementation
    moderate-post/index.ts   verify JWT -> classify -> insert as service role
    bot-agent/index.ts       Peter: setup / post / reply
```

## The AI agent

`bot-agent` is not called by the app. `pg_cron` fires `{"action":"post"}` every
30 minutes and a trigger on `messages` fires `{"action":"reply"}`, so there is
no user JWT to verify — it authenticates callers with an `x-bot-secret` header
instead, and `verify_jwt` is off for that function alone.

Peter's own replies re-fire the insert trigger, so `trigger_bot_reply` returns
early when the sender is the bot. Without that check he would answer himself
forever.

Budget: ~900 tokens per generated post. At 48 posts/day that is ~43k of Groq's
200k/day free allowance, leaving room for user submissions (~580 each) and chat
replies. Change the schedule in `cron.job` — `'*/10 * * * *'` would be 144
posts/day (~130k tokens) and leaves very little headroom.

## Design notes

**The client cannot publish.** The app never inserts into `posts`. It calls the
`moderate-post` function, which classifies the draft and writes it with the
service role. A `posts_force_pending` trigger resets `status` to `pending` for
any non-service-role insert, and the feed only reads `approved` rows — so a
tampered APK gets a post nobody can see, not a bypass.

**Keyset pagination, not OFFSET.** The feed is append-heavy. With `OFFSET`, a
post arriving mid-scroll shifts every subsequent page and duplicates a row.
`feed_page(before_ts, page_size)` pages on `created_at` instead.

**RLS without recursion.** A policy on `conversation_participants` that queries
`conversation_participants` recurses through its own RLS. The
`is_participant(conv)` helper is `SECURITY DEFINER`, so policies call it instead.

## Not built yet

- Image upload for posts (the `image_url` column and Storage are ready; no
  picker UI).
- Group chat (`conversations.is_group` exists; only 1:1 is wired up).
- Push notifications for new messages.
- Profile editing and a "my posts" screen showing pending/rejected drafts
  (`FeedRepository.myPosts()` is written but not yet on screen).
- Moderation appeals / human review queue.
