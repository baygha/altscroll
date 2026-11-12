-- Fix: the feed stopped after 10 posts.
--
-- The CSV import inserted all 485 rows in one statement, so every row shares an
-- identical created_at. Paging with "created_at < before_ts" then matches
-- nothing on page 2, the client sees a short page and concludes the feed ended.
--
-- Two changes:
--   1. Page on the (created_at, id) pair so identical timestamps still have a
--      strict, stable order. This is correct regardless of how rows arrive.
--   2. Spread the imported timestamps over a shuffled order, so the library
--      reads as a mixed feed rather than 97 biology facts in a row.

-- 1. Give the imported rows distinct timestamps in a shuffled order. -----------
with shuffled as (
    select id, row_number() over (order by random()) as rn
      from public.posts
     where moderation_model = 'import:csv'
)
update public.posts p
   set created_at = now() - (s.rn * interval '1 minute')
  from shuffled s
 where p.id = s.id;

-- 2. Replace feed_page with a composite keyset. -------------------------------
-- The old signature must be dropped: adding a parameter creates an overload
-- rather than replacing, and a named call could then match both.
drop function if exists public.feed_page(timestamptz, integer);
drop function if exists public.feed_page(timestamptz, uuid, integer);

create or replace function public.feed_page(
    before_ts timestamptz default null,
    before_id uuid default null,
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
       and (
             before_ts is null
             or before_id is null
             or (p.created_at, p.id) < (before_ts, before_id)
           )
     order by p.created_at desc, p.id desc
     limit least(greatest(page_size, 1), 50);
$$;

-- Supporting index for the composite ordering.
drop index if exists public.posts_feed_idx;
create index posts_feed_idx on public.posts (created_at desc, id desc)
    where status = 'approved';

-- Check: should report 485
select count(*) as approved_posts from public.posts where status = 'approved';
