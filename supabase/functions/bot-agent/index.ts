// POST /functions/v1/bot-agent
//
// The AltScroll AI agent. One function, three actions:
//
//   { "action": "setup" }                        create the bot account (idempotent)
//   { "action": "post" }                         write and publish one post
//   { "action": "reply", "conversation_id": … }  answer the latest chat message
//
// Not called by the app. "post" is driven by pg_cron every 30 minutes and
// "reply" by a trigger on `messages`, so JWT verification is off and the
// function authenticates callers with a shared secret header instead.

import { createClient } from "jsr:@supabase/supabase-js@2";
import { corsHeaders, json } from "../_shared/cors.ts";
import { extractJson, GroqError, groqChat, GROQ_MODEL } from "../_shared/groq.ts";

const BOT_USERNAME = "peter";
const BOT_DISPLAY_NAME = "Peter";
const BOT_BIO =
  "AI agent. I post science and answer questions - check anything that matters.";

// The auth account is keyed on this address and never changes, so renaming the
// bot stays a rename rather than becoming a second account.
const BOT_EMAIL = "ai-agent@altscroll.bot";

// Usernames the bot has previously answered to. Kept so setup can find and
// rename the existing account instead of creating a duplicate alongside it.
const LEGACY_USERNAMES = ["altscroll_ai"];

const admin = () =>
  createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } },
  );

// --------------------------------------------------------------------------
// setup
// --------------------------------------------------------------------------

async function ensureBot(): Promise<string> {
  const db = admin();

  // is_bot is the real identity, but it only exists once migration 0004 has
  // run, and an older account predates it. Fall back to known usernames.
  const { data: flagged } = await db
    .from("profiles").select("id").eq("is_bot", true).limit(1).maybeSingle();

  let id: string | undefined = flagged?.id;

  if (!id) {
    const { data: named } = await db
      .from("profiles")
      .select("id")
      .in("username", [BOT_USERNAME, ...LEGACY_USERNAMES])
      .limit(1)
      .maybeSingle();
    id = named?.id;
  }

  if (!id) {
    // A real auth.users row, because profiles.id references it and messages
    // reference profiles. The bot is a user like any other, not a special case.
    const { data: created, error } = await db.auth.admin.createUser({
      email: BOT_EMAIL,
      password: crypto.randomUUID() + crypto.randomUUID(),
      email_confirm: true,
      user_metadata: { username: BOT_USERNAME, display_name: BOT_DISPLAY_NAME },
    });
    if (error || !created.user) {
      throw new Error(`could not create bot user: ${error?.message}`);
    }
    id = created.user.id;
  }

  // Applied every run, not only on creation: setup must be able to repair a
  // half-finished account, such as one made before is_bot existed.
  const { error: profileError } = await db.from("profiles").update({
    username: BOT_USERNAME,
    display_name: BOT_DISPLAY_NAME,
    is_bot: true,
    bio: BOT_BIO,
  }).eq("id", id);

  if (profileError) {
    throw new Error(`could not flag bot profile: ${profileError.message}`);
  }

  return id;
}

async function botId(): Promise<string> {
  const { data } = await admin()
    .from("profiles").select("id").eq("is_bot", true).limit(1).maybeSingle();
  if (!data?.id) throw new Error("bot account missing - run action 'setup' first");
  return data.id;
}

// --------------------------------------------------------------------------
// post
// --------------------------------------------------------------------------

const TOPICS = [
  "physics", "biology", "chemistry", "astronomy", "mathematics",
  "computing", "medicine", "earth science", "history of science",
  "engineering", "psychology", "economics",
];

interface GeneratedPost {
  title?: string;
  body?: string;
  topic?: string;
  tags?: string[];
  source?: string;
}

async function writePost(): Promise<Response> {
  const db = admin();
  const author = await botId();

  // Show the model what it has already written so it does not loop on the
  // same half-dozen crowd-pleasers.
  const { data: recent } = await db
    .from("posts")
    .select("title, body")
    .eq("author_id", author)
    .order("created_at", { ascending: false })
    .limit(30);

  const avoid = (recent ?? [])
    .map((r) => r.title ?? r.body?.slice(0, 60))
    .filter(Boolean)
    .join("\n- ");

  const topic = TOPICS[Math.floor(Math.random() * TOPICS.length)];

  const system = `You write short, factual posts for AltScroll, a feed that
carries only informational and scientific content.

Rules:
- One specific, verifiable idea per post. Not a survey of a whole field.
- Explain the mechanism or the reason, never just state the fact.
- SHORT: 35 to 55 words in the body. Two or three sentences. This is a feed
  people scroll, not an article. If it does not fit, pick a smaller idea.
- Plain language, no hype, no emoji.
- No "did you know", no rhetorical questions, no second person address.
- Prefer the genuinely surprising over the famous. Avoid the usual
  pop-science rotation unless you have a specific angle on it.
- Be accurate. If you are not confident a detail is correct, leave it out.
- "source" names where this is established knowledge (a field, a study, an
  instrument, a researcher). It is a short attribution, not a URL.

Respond with ONLY a JSON object:
{
  "title": string,   // 4-9 words, states the finding, not a teaser
  "body": string,    // 35-55 words, two or three sentences
  "topic": string,   // lowercase, one word where possible
  "tags": string[],  // up to 4 lowercase keywords
  "source": string   // short attribution
}`;

  const user = avoid
    ? `Write one post about ${topic}.\n\nYou have already written these - pick something different:\n- ${avoid}`
    : `Write one post about ${topic}.`;

  const raw = await groqChat(
    [{ role: "system", content: system }, { role: "user", content: user }],
    // Higher temperature than moderation: this is generation, and determinism
    // here would mean the same post every run.
    { maxTokens: 1200, temperature: 0.9, json: true },
  );

  const gen = extractJson<GeneratedPost>(raw);
  const title = (gen.title ?? "").trim();
  const body = (gen.body ?? "").trim();

  const words = body.split(/\s+/).filter(Boolean);
  if (title.length < 3 || words.length < 20) {
    return json({ error: "generated post was too thin", title, body }, 422);
  }
  // Models drift long. Rather than discard a good post, trim it at the last
  // sentence that fits so the feed card never overflows.
  let trimmed = body;
  if (words.length > 70) {
    const sentences = body.match(/[^.!?]+[.!?]+/g) ?? [body];
    trimmed = "";
    for (const sentence of sentences) {
      const next = trimmed + sentence;
      if (next.split(/\s+/).filter(Boolean).length > 70 && trimmed) break;
      trimmed = next;
    }
    trimmed = trimmed.trim() || body;
  }

  // Bot output is produced under our own rubric, so it is marked rather than
  // re-classified - that would double the token cost of every scheduled post.
  const { data: post, error } = await db.from("posts").insert({
    author_id: author,
    title: title.slice(0, 200),
    body: trimmed.slice(0, 8000),
    source_url: (gen.source ?? "").trim() || null,
    topic: (gen.topic ?? topic).toLowerCase().slice(0, 40),
    tags: (gen.tags ?? []).slice(0, 4).map((t) => String(t).toLowerCase()),
    status: "approved",
    moderation_score: 1,
    moderation_reason: "Written by the AltScroll AI agent.",
    moderation_model: `bot:${GROQ_MODEL}`,
    moderated_at: new Date().toISOString(),
  }).select("id, title, topic").single();

  if (error) {
    console.error("bot post insert failed:", error);
    return json({ error: "could not save post" }, 500);
  }
  return json({ posted: post });
}

// --------------------------------------------------------------------------
// reply
// --------------------------------------------------------------------------

async function reply(conversationId: string): Promise<Response> {
  const db = admin();
  const bot = await botId();

  const { data: participates } = await db
    .from("conversation_participants")
    .select("user_id")
    .eq("conversation_id", conversationId)
    .eq("user_id", bot)
    .maybeSingle();
  if (!participates) return json({ skipped: "bot is not in this conversation" });

  const { data: rows } = await db
    .from("messages")
    .select("sender_id, content, created_at")
    .eq("conversation_id", conversationId)
    .order("created_at", { ascending: false })
    .limit(16);

  const history = (rows ?? []).reverse();
  if (history.length === 0) return json({ skipped: "nothing to reply to" });

  // Already answered the newest message - the trigger fired twice, or our own
  // insert re-triggered it.
  if (history[history.length - 1].sender_id === bot) {
    return json({ skipped: "last message is already ours" });
  }

  const system = `Your name is ${BOT_DISPLAY_NAME}. You are an AI agent on
AltScroll, a feed and chat app for science and information, and you are a
friend to the person you are talking to.

Your two jobs:
1. You post useful, factual things to the feed on your own schedule.
2. You chat with your friend here - about anything at all, not only science.

How you talk:
- Like a friend texting. Warm, relaxed, genuinely interested in them.
- Short. Usually one to three sentences. This is a messaging app.
- Plain language. No lecturing, no bullet lists, no headings, no emoji.
- Answer what was actually asked. Do not pad, do not add a summary.
- Any topic is fair game - their day, films, football, a problem they have.
  You are not a help desk and you do not steer every subject back to science.
- When something does touch your interests, you are good company on it: you
  know a lot and you share it without turning it into a lecture.
- Ask a question back when you are actually curious, not as a habit.
- If you do not know, or the answer is contested, say so plainly.
- Never invent a statistic, a study, or a citation.
- You know you are an AI. You never pretend to be human if asked, and you are
  relaxed about it rather than apologetic.

Language: always reply in the same language the person just wrote in. If they
write English, reply in English. If they switch, you switch. Never translate
their message or answer in a language they did not use.`;

  const messages = [
    { role: "system" as const, content: system },
    ...history.map((m) => ({
      role: (m.sender_id === bot ? "assistant" : "user") as "assistant" | "user",
      content: m.content,
    })),
  ];

  const text = (await groqChat(messages, { maxTokens: 800, temperature: 0.6 }))
    .trim()
    .slice(0, 4000);

  const { error } = await db.from("messages").insert({
    conversation_id: conversationId,
    sender_id: bot,
    content: text,
  });
  if (error) {
    console.error("bot reply insert failed:", error);
    return json({ error: "could not send reply" }, 500);
  }
  return json({ replied: text });
}

// --------------------------------------------------------------------------

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const expected = Deno.env.get("BOT_SECRET");
  if (!expected || req.headers.get("x-bot-secret") !== expected) {
    return json({ error: "forbidden" }, 403);
  }

  let body: { action?: string; conversation_id?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "body must be JSON" }, 400);
  }

  try {
    switch (body.action) {
      case "setup":
        return json({ bot_id: await ensureBot() });
      case "post":
        return await writePost();
      case "reply":
        if (!body.conversation_id) return json({ error: "conversation_id required" }, 400);
        return await reply(body.conversation_id);
      default:
        return json({ error: `unknown action: ${body.action}` }, 400);
    }
  } catch (err) {
    if (err instanceof GroqError) {
      console.error("groq failed:", err.detail ?? err.message);
      return json({ error: err.message }, err.status);
    }
    console.error("bot-agent failed:", err);
    return json({ error: String(err) }, 500);
  }
});
