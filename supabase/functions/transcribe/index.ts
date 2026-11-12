// POST /functions/v1/transcribe
//
// Body: { conversation_id, audio_base64, mime_type? }
// Auth: the caller's Supabase JWT
//
// Turns a held-to-talk recording into a normal chat message. The transcript is
// inserted as an ordinary row from the speaker, which means the existing
// messages_bot_reply_trg trigger fires and Peter answers exactly as he would
// to typing. Nothing downstream needs to know the message began as audio.

import { createClient } from "jsr:@supabase/supabase-js@2";
import { corsHeaders, json } from "../_shared/cors.ts";

const MODEL = Deno.env.get("STT_MODEL") ?? "whisper-large-v3-turbo";

// A minute of AAC is ~250KB, ~340KB once base64'd. 8MB is generous headroom
// while still refusing anything that is obviously not a voice note.
const MAX_BASE64_CHARS = 8 * 1024 * 1024;

function decodeBase64(b64: string): Uint8Array {
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);

  const authHeader = req.headers.get("Authorization");
  if (!authHeader) return json({ error: "Missing Authorization header" }, 401);

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;

  const userClient = createClient(supabaseUrl, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: authHeader } },
  });
  const { data: { user }, error: userErr } = await userClient.auth.getUser();
  if (userErr || !user) return json({ error: "Invalid or expired session" }, 401);

  let body: {
    conversation_id?: string;
    audio_base64?: string;
    mime_type?: string;
    language?: string;
  };
  try {
    body = await req.json();
  } catch {
    return json({ error: "Body must be JSON" }, 400);
  }

  const conversationId = body.conversation_id;
  const audioB64 = body.audio_base64;
  if (!conversationId) return json({ error: "conversation_id required" }, 400);
  if (!audioB64) return json({ error: "audio_base64 required" }, 400);
  if (audioB64.length > MAX_BASE64_CHARS) {
    return json({ error: "Recording too long. Keep voice notes under a minute." }, 413);
  }

  // Membership is checked here rather than trusted from the client, because
  // the insert below runs with the service role and bypasses RLS.
  const admin = createClient(supabaseUrl, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });
  const { data: member } = await admin
    .from("conversation_participants")
    .select("user_id")
    .eq("conversation_id", conversationId)
    .eq("user_id", user.id)
    .maybeSingle();
  if (!member) return json({ error: "Not part of this conversation" }, 403);

  const groqKey = Deno.env.get("GROQ_API_KEY");
  if (!groqKey) return json({ error: "Speech recognition is not configured." }, 500);

  let bytes: Uint8Array;
  try {
    bytes = decodeBase64(audioB64);
  } catch {
    return json({ error: "audio_base64 is not valid base64" }, 400);
  }

  const mime = body.mime_type ?? "audio/mp4";
  const ext = mime.includes("ogg") ? "ogg" : mime.includes("wav") ? "wav" : "m4a";

  const form = new FormData();
  form.append("file", new Blob([bytes], { type: mime }), `note.${ext}`);
  form.append("model", MODEL);
  form.append("response_format", "json");

  // Whisper auto-detects language when not told, and on a short or noisy clip
  // it guesses badly - an English sentence can come back as Arabic script.
  // The client sends the phone's language, which is almost always right.
  // "auto" explicitly opts back into detection.
  const lang = (body.language ?? "").trim().toLowerCase().slice(0, 5);
  if (lang && lang !== "auto") {
    form.append("language", lang.split(/[-_]/)[0]);
  }

  // Transcribe, never translate: this endpoint must return what was actually
  // said, in the language it was said in.
  form.append("temperature", "0");

  const res = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
    method: "POST",
    headers: { Authorization: `Bearer ${groqKey}` },
    body: form,
  });

  if (!res.ok) {
    const detail = await res.text();
    console.error("whisper failed:", res.status, detail.slice(0, 500));
    return json({
      error: res.status === 429
        ? "Speech recognition is rate limited. Try again shortly."
        : "Could not transcribe that recording.",
    }, res.status === 429 ? 429 : 502);
  }

  const transcript = String((await res.json())?.text ?? "").trim();

  // Whisper returns an empty string for silence, and sometimes a lone "you"
  // or "Thank you." for background noise - its known hallucination on near
  // silence. Dropping those beats sending Peter a message nobody spoke.
  const noise = /^(you|thank you\.?|thanks\.?|bye\.?|\.)$/i;
  if (!transcript || noise.test(transcript)) {
    return json({ transcript: "", skipped: "nothing audible in that recording" });
  }

  const { data: message, error: insertErr } = await admin
    .from("messages")
    .insert({
      conversation_id: conversationId,
      sender_id: user.id,
      content: transcript.slice(0, 4000),
    })
    .select("id, conversation_id, sender_id, content, created_at")
    .single();

  if (insertErr) {
    console.error("voice message insert failed:", insertErr);
    return json({ error: "Could not save that message." }, 500);
  }

  return json({ transcript, message });
});
