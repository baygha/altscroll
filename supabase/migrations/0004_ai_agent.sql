-- The AltScroll AI agent: scheduling and chat triggering.
--
-- The agent itself lives in the `bot-agent` edge function. This file wires up
-- the two things that call it:
--   1. pg_cron, every 10 minutes, to publish a post.
--   2. a trigger on `messages`, so it answers chats.
--
-- Both authenticate with a shared secret header rather than a user JWT, since
-- neither caller is a signed-in person.

create extension if not exists pg_cron with schema extensions;
create extension if not exists pg_net  with schema extensions;

-- Mark the bot so the app can badge it and the agent can find itself. ---------
alter table public.profiles
    add column if not exists is_bot boolean not null default false;

create index if not exists profiles_bot_idx on public.profiles (is_bot)
    where is_bot = true;

-- Call details, kept in one place instead of repeated in every caller. --------
-- Not exposed through the Data API: no RLS policy is added, and the table is
-- revoked from the anon/authenticated roles below.
create table if not exists public.bot_runtime (
    id      integer primary key default 1 check (id = 1),
    fn_url  text not null,
    secret  text not null
);

alter table public.bot_runtime enable row level security;
revoke all on public.bot_runtime from anon, authenticated;

-- Fill these in before running. They are deliberately NOT committed:
--   <PROJECT-REF>  your Supabase project ref, e.g. abcdefghijklmnop
--   <BOT-SECRET>   the same value you set with
--                  `supabase secrets set BOT_SECRET=...`
--                  Generate one with: python -c "import secrets;
--                  print(secrets.token_urlsafe(32))"
--
-- The real values for this project live in supabase/bot_runtime.local.sql,
-- which is git-ignored. Run that file instead of editing this one.
insert into public.bot_runtime (id, fn_url, secret)
values (
    1,
    'https://<PROJECT-REF>.supabase.co/functions/v1/bot-agent',
    '<BOT-SECRET>'
)
on conflict (id) do update
    set fn_url = excluded.fn_url, secret = excluded.secret;

-- Helper: fire an action at the agent, without waiting for the response. -----
create or replace function public.call_bot_agent(payload jsonb)
returns bigint
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    cfg public.bot_runtime%rowtype;
begin
    select * into cfg from public.bot_runtime where id = 1;
    if cfg.fn_url is null then
        raise warning 'bot_runtime not configured';
        return null;
    end if;

    return net.http_post(
        url     := cfg.fn_url,
        headers := jsonb_build_object(
                       'Content-Type', 'application/json',
                       'x-bot-secret', cfg.secret
                   ),
        body    := payload,
        timeout_milliseconds := 55000
    );
end;
$$;

-- 1. Publish a post every 30 minutes. ---------------------------------------
-- 48 posts/day at roughly 900 tokens each is ~43k of the 200k/day free Groq
-- budget, leaving room for user posts and chat replies. Going to '*/10' means
-- 144 posts/day (~130k tokens) and leaves very little headroom.
select cron.unschedule('altscroll-bot-post')
 where exists (select 1 from cron.job where jobname = 'altscroll-bot-post');

select cron.schedule(
    'altscroll-bot-post',
    '*/30 * * * *',
    $cron$ select public.call_bot_agent('{"action":"post"}'::jsonb) $cron$
);

-- 2. Answer chat messages. ---------------------------------------------------
create or replace function public.trigger_bot_reply()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    bot uuid;
begin
    select id into bot from public.profiles where is_bot = true limit 1;

    -- No bot yet, or the bot is the one talking: nothing to do. Without this
    -- second check the agent's own reply would trigger another reply.
    if bot is null or new.sender_id = bot then
        return null;
    end if;

    if not exists (
        select 1 from public.conversation_participants
         where conversation_id = new.conversation_id and user_id = bot
    ) then
        return null;
    end if;

    perform public.call_bot_agent(
        jsonb_build_object('action', 'reply', 'conversation_id', new.conversation_id)
    );
    return null;
end;
$$;

drop trigger if exists messages_bot_reply_trg on public.messages;
create trigger messages_bot_reply_trg
    after insert on public.messages
    for each row execute function public.trigger_bot_reply();

-- Check
select jobname, schedule, active from cron.job where jobname = 'altscroll-bot-post';
