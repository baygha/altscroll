-- AltScroll initial schema
-- Run with: supabase db push   (or paste into the Supabase SQL editor)

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------------------
-- profiles : public-facing user record, 1:1 with auth.users
-- ---------------------------------------------------------------------------
create table if not exists public.profiles (
    id          uuid primary key references auth.users (id) on delete cascade,
    username    text unique not null check (char_length(username) between 3 and 30),
    display_name text,
    avatar_url  text,
    bio         text,
    created_at  timestamptz not null default now()
);

-- Auto-create a profile whenever a user signs up.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, username, display_name)
    values (
        new.id,
        coalesce(
            new.raw_user_meta_data ->> 'username',
            split_part(new.email, '@', 1) || '_' || substr(new.id::text, 1, 4)
        ),
        coalesce(new.raw_user_meta_data ->> 'display_name', split_part(new.email, '@', 1))
    )
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------------
-- posts : the scroll feed. Seeded/curated rows have author_id = null.
-- ---------------------------------------------------------------------------
do $$ begin
    create type public.post_status as enum ('pending', 'approved', 'rejected');
exception when duplicate_object then null; end $$;

create table if not exists public.posts (
    id              uuid primary key default gen_random_uuid(),
    author_id       uuid references public.profiles (id) on delete set null,
    title           text not null check (char_length(title) between 3 and 200),
    body            text not null check (char_length(body) between 1 and 8000),
    source_url      text,
    image_url       text,
    topic           text,                       -- e.g. 'physics', 'biology', 'computing'
    tags            text[] not null default '{}',
    status          public.post_status not null default 'pending',
    -- moderation audit trail
    moderation_score     real,                  -- 0..1 confidence it is info/science
    moderation_reason    text,
    moderation_model     text,
    moderated_at         timestamptz,
    like_count      integer not null default 0,
    created_at      timestamptz not null default now()
);

create index if not exists posts_feed_idx on public.posts (created_at desc, id desc)
    where status = 'approved';
create index if not exists posts_author_idx on public.posts (author_id, created_at desc);
create index if not exists posts_topic_idx on public.posts (topic) where status = 'approved';

-- A client must never be able to self-approve. Force status + audit fields to
-- defaults on insert; only the service role (edge function) may change them.
create or replace function public.posts_force_pending()
returns trigger
language plpgsql
as $$
begin
    if auth.role() <> 'service_role' then
        new.status := 'pending';
        new.moderation_score := null;
        new.moderation_reason := null;
        new.moderation_model := null;
        new.moderated_at := null;
        new.like_count := 0;
    end if;
    return new;
end;
$$;

drop trigger if exists posts_force_pending_trg on public.posts;
create trigger posts_force_pending_trg
    before insert on public.posts
    for each row execute function public.posts_force_pending();

-- ---------------------------------------------------------------------------
-- post_likes
-- ---------------------------------------------------------------------------
create table if not exists public.post_likes (
    post_id uuid not null references public.posts (id) on delete cascade,
    user_id uuid not null references public.profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (post_id, user_id)
);

create or replace function public.sync_like_count()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if tg_op = 'INSERT' then
        update public.posts set like_count = like_count + 1 where id = new.post_id;
    elsif tg_op = 'DELETE' then
        update public.posts set like_count = greatest(like_count - 1, 0) where id = old.post_id;
    end if;
    return null;
end;
$$;

drop trigger if exists post_likes_count_trg on public.post_likes;
create trigger post_likes_count_trg
    after insert or delete on public.post_likes
    for each row execute function public.sync_like_count();

-- ---------------------------------------------------------------------------
-- chat : conversations / participants / messages
-- ---------------------------------------------------------------------------
create table if not exists public.conversations (
    id          uuid primary key default gen_random_uuid(),
    is_group    boolean not null default false,
    title       text,
    created_by  uuid references public.profiles (id) on delete set null,
    created_at  timestamptz not null default now(),
    last_message_at timestamptz not null default now()
);

create table if not exists public.conversation_participants (
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    user_id         uuid not null references public.profiles (id) on delete cascade,
    joined_at       timestamptz not null default now(),
    last_read_at    timestamptz not null default 'epoch',
    primary key (conversation_id, user_id)
);

create index if not exists participants_user_idx on public.conversation_participants (user_id);

create table if not exists public.messages (
    id              uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    sender_id       uuid not null references public.profiles (id) on delete cascade,
    content         text not null check (char_length(content) between 1 and 4000),
    created_at      timestamptz not null default now()
);

create index if not exists messages_conversation_idx
    on public.messages (conversation_id, created_at desc);

-- Bump the conversation's ordering timestamp on each new message.
create or replace function public.touch_conversation()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.conversations
       set last_message_at = new.created_at
     where id = new.conversation_id;
    return null;
end;
$$;

drop trigger if exists messages_touch_conversation_trg on public.messages;
create trigger messages_touch_conversation_trg
    after insert on public.messages
    for each row execute function public.touch_conversation();

-- SECURITY DEFINER membership check. Policies call this instead of querying
-- conversation_participants directly, which would recurse through its own RLS.
create or replace function public.is_participant(conv uuid)
returns boolean
language sql
security definer
stable
set search_path = public
as $$
    select exists (
        select 1 from public.conversation_participants
         where conversation_id = conv and user_id = auth.uid()
    );
$$;

-- Find an existing 1:1 conversation with `other_user`, or create one.
create or replace function public.start_direct_conversation(other_user uuid)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
    me   uuid := auth.uid();
    conv uuid;
begin
    if me is null then
        raise exception 'not authenticated';
    end if;
    if me = other_user then
        raise exception 'cannot start a conversation with yourself';
    end if;

    select c.id into conv
      from public.conversations c
      join public.conversation_participants p1 on p1.conversation_id = c.id and p1.user_id = me
      join public.conversation_participants p2 on p2.conversation_id = c.id and p2.user_id = other_user
     where c.is_group = false
     limit 1;

    if conv is not null then
        return conv;
    end if;

    insert into public.conversations (is_group, created_by)
    values (false, me)
    returning id into conv;

    insert into public.conversation_participants (conversation_id, user_id)
    values (conv, me), (conv, other_user);

    return conv;
end;
$$;

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------
alter table public.profiles                  enable row level security;
alter table public.posts                     enable row level security;
alter table public.post_likes                enable row level security;
alter table public.conversations             enable row level security;
alter table public.conversation_participants enable row level security;
alter table public.messages                  enable row level security;

-- profiles: everyone signed in can discover users; you may only edit your own.
drop policy if exists profiles_select on public.profiles;
create policy profiles_select on public.profiles
    for select to authenticated using (true);

drop policy if exists profiles_update_own on public.profiles;
create policy profiles_update_own on public.profiles
    for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

-- posts: the feed shows approved posts only; authors additionally see their own
-- pending/rejected posts so they can tell what happened.
drop policy if exists posts_select on public.posts;
create policy posts_select on public.posts
    for select to authenticated
    using (status = 'approved' or author_id = auth.uid());

drop policy if exists posts_insert_own on public.posts;
create policy posts_insert_own on public.posts
    for insert to authenticated with check (author_id = auth.uid());

drop policy if exists posts_delete_own on public.posts;
create policy posts_delete_own on public.posts
    for delete to authenticated using (author_id = auth.uid());

-- post_likes
drop policy if exists likes_select on public.post_likes;
create policy likes_select on public.post_likes
    for select to authenticated using (true);

drop policy if exists likes_write_own on public.post_likes;
create policy likes_write_own on public.post_likes
    for insert to authenticated with check (user_id = auth.uid());

drop policy if exists likes_delete_own on public.post_likes;
create policy likes_delete_own on public.post_likes
    for delete to authenticated using (user_id = auth.uid());

-- conversations
drop policy if exists conversations_select on public.conversations;
create policy conversations_select on public.conversations
    for select to authenticated using (public.is_participant(id));

-- participants
drop policy if exists participants_select on public.conversation_participants;
create policy participants_select on public.conversation_participants
    for select to authenticated using (public.is_participant(conversation_id));

drop policy if exists participants_update_own on public.conversation_participants;
create policy participants_update_own on public.conversation_participants
    for update to authenticated
    using (user_id = auth.uid()) with check (user_id = auth.uid());

-- messages
drop policy if exists messages_select on public.messages;
create policy messages_select on public.messages
    for select to authenticated using (public.is_participant(conversation_id));

drop policy if exists messages_insert on public.messages;
create policy messages_insert on public.messages
    for insert to authenticated
    with check (sender_id = auth.uid() and public.is_participant(conversation_id));

-- ---------------------------------------------------------------------------
-- Realtime : stream new messages to connected clients
-- ---------------------------------------------------------------------------
do $$ begin
    alter publication supabase_realtime add table public.messages;
exception when duplicate_object then null; end $$;

do $$ begin
    alter publication supabase_realtime add table public.posts;
exception when duplicate_object then null; end $$;

-- ---------------------------------------------------------------------------
-- Feed helper : keyset pagination over the approved feed, with like state.
-- ---------------------------------------------------------------------------
create or replace function public.feed_page(
    before_ts timestamptz default null,
    page_size integer default 10
)
returns table (
    id uuid,
    title text,
    body text,
    source_url text,
    image_url text,
    topic text,
    tags text[],
    like_count integer,
    created_at timestamptz,
    author_id uuid,
    author_username text,
    author_avatar_url text,
    liked_by_me boolean
)
language sql
stable
set search_path = public
as $$
    select p.id, p.title, p.body, p.source_url, p.image_url, p.topic, p.tags,
           p.like_count, p.created_at,
           p.author_id, pr.username, pr.avatar_url,
           exists (select 1 from public.post_likes l
                    where l.post_id = p.id and l.user_id = auth.uid())
      from public.posts p
      left join public.profiles pr on pr.id = p.author_id
     where p.status = 'approved'
       and (before_ts is null or p.created_at < before_ts)
     order by p.created_at desc, p.id desc
     limit least(greatest(page_size, 1), 50);
$$;
