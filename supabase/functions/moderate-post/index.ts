// POST /functions/v1/moderate-post
//
// Body: { title, body, source_url?, image_url? }
// Auth: the caller's Supabase JWT (Authorization: Bearer <access_token>)
//
// Classifies the draft, writes the post with the service role (so the
// posts_force_pending trigger is bypassed and the verdict actually sticks),
// and returns the verdict to the client.

import { createClient } from "jsr:@supabase/supabase-js@2";
import { corsHeaders, json } from "../_shared/cors.ts";
import {
  buildClassifier,
  ModerationError,
  prefilter,
  type Verdict,
} from "../_shared/moderation.ts";

interface RequestBody {
  title?: string;
  body?: string;
  source_url?: string | null;
  image_url?: string | null;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (req.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) {
    return json({ error: "Missing Authorization header" }, 401);
  }

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;

  // Identify the caller using their own JWT.
  const userClient = createClient(
    supabaseUrl,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );
  const { data: { user }, error: userErr } = await userClient.auth.getUser();
  if (userErr || !user) {
    return json({ error: "Invalid or expired session" }, 401);
  }

  let payload: RequestBody;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "Body must be JSON" }, 400);
  }

  const title = (payload.title ?? "").trim();
  const body = (payload.body ?? "").trim();
  if (title.length < 3 || title.length > 200) {
    return json({ error: "Title must be between 3 and 200 characters" }, 400);
  }
  if (body.length < 1 || body.length > 8000) {
    return json({ error: "Body must be between 1 and 8000 characters" }, 400);
  }

  const draft = { title, body, sourceUrl: payload.source_url ?? null };

  let verdict: Verdict;
  try {
    verdict = prefilter(draft) ?? await buildClassifier().classify(draft);
  } catch (err) {
    if (err instanceof ModerationError) {
      console.error("moderation failed:", err.detail ?? err.message);
      return json({ error: err.message }, err.status);
    }
    console.error("unexpected moderation error:", err);
    return json({ error: "Moderation failed unexpectedly." }, 500);
  }

  // Service role: the force-pending trigger defers to it, so the verdict sticks.
  const admin = createClient(
    supabaseUrl,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } },
  );

  const { data: post, error: insertErr } = await admin
    .from("posts")
    .insert({
      author_id: user.id,
      title,
      body,
      source_url: payload.source_url ?? null,
      image_url: payload.image_url ?? null,
      topic: verdict.topic,
      tags: verdict.tags,
      status: verdict.approve ? "approved" : "rejected",
      moderation_score: verdict.score,
      moderation_reason: verdict.reason,
      moderation_model: verdict.model,
      moderated_at: new Date().toISOString(),
    })
    .select("id, status")
    .single();

  if (insertErr) {
    console.error("insert failed:", insertErr);
    return json({ error: "Could not save the post." }, 500);
  }

  return json({
    post_id: post.id,
    status: post.status,
    approved: verdict.approve,
    score: verdict.score,
    reason: verdict.reason,
    topic: verdict.topic,
    tags: verdict.tags,
    model: verdict.model,
  });
});
