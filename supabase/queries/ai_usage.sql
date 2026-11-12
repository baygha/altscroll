-- What AltScroll's AI actually did, and the token cost that implies.
-- Paste into the Supabase SQL editor. Groq does not expose a daily token
-- counter through the API, so this estimates from measured per-call costs:
--   bot post      ~900 tokens (generation, temperature 0.9)
--   user post     ~580 tokens (moderation check)
--   chat reply    ~700 tokens (history + reply)

with posts_today as (
    select
        count(*) filter (where moderation_model like 'bot:%')          as bot_posts,
        count(*) filter (where moderation_model not like 'bot:%'
                           and moderation_model <> 'import:csv'
                           and moderation_model <> 'seed')             as user_posts,
        count(*) filter (where moderation_model not like 'bot:%'
                           and moderation_model not in ('import:csv', 'seed')
                           and status = 'rejected')                    as rejected
      from public.posts
     where created_at >= date_trunc('day', now())
),
replies_today as (
    select count(*) as bot_replies
      from public.messages m
      join public.profiles p on p.id = m.sender_id
     where p.is_bot
       and m.created_at >= date_trunc('day', now())
)
select
    bot_posts,
    user_posts,
    rejected            as user_posts_rejected,
    bot_replies,
    bot_posts * 900 + user_posts * 580 + bot_replies * 700 as est_tokens_today,
    200000                                                  as daily_free_tokens,
    round(
        (bot_posts * 900 + user_posts * 580 + bot_replies * 700) * 100.0 / 200000,
        1
    )                                                       as pct_of_budget
  from posts_today, replies_today;

-- Everything Peter has ever posted, newest first.
select created_at, topic, title
  from public.posts
 where moderation_model like 'bot:%'
 order by created_at desc
 limit 20;

-- Is the schedule still running, and did the last run succeed?
select jobname, schedule, active from cron.job where jobname = 'altscroll-bot-post';

select start_time, status, return_message
  from cron.job_run_details
 where jobid = (select jobid from cron.job where jobname = 'altscroll-bot-post')
 order by start_time desc
 limit 10;
