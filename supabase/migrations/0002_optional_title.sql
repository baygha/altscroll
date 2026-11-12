-- Imported library posts are short standalone facts with no headline, so the
-- feed card renders the body as its hero text. User-written posts still require
-- a title - that rule is enforced by the composer and the moderate-post
-- function, not by the column.
alter table public.posts alter column title drop not null;

-- The existing length check stays: char_length(null) is null, which a CHECK
-- constraint treats as satisfied, so NULL titles pass and short ones still fail.
