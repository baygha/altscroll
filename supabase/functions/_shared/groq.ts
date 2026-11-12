// Minimal Groq chat client shared by the moderation gate and the AI agent.
//
// gpt-oss is a reasoning model: it spends tokens thinking before it answers,
// and that spend counts against max_tokens. Every caller here therefore sets a
// generous budget and low reasoning effort, and treats empty content as a
// failure rather than letting it fall through as a parse error.

export interface ChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface ChatOptions {
  maxTokens?: number;
  temperature?: number;
  json?: boolean;
}

export class GroqError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly detail?: string,
  ) {
    super(message);
  }
}

export const GROQ_MODEL = Deno.env.get("MODERATION_MODEL") ?? "openai/gpt-oss-20b";

export async function groqChat(
  messages: ChatMessage[],
  opts: ChatOptions = {},
): Promise<string> {
  const apiKey = Deno.env.get("GROQ_API_KEY");
  if (!apiKey) {
    throw new GroqError("No AI provider configured.", 500, "GROQ_API_KEY unset");
  }

  const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: GROQ_MODEL,
      temperature: opts.temperature ?? 0,
      max_tokens: opts.maxTokens ?? 900,
      reasoning_effort: "low",
      ...(opts.json ? { response_format: { type: "json_object" } } : {}),
      messages,
    }),
  });

  if (!res.ok) {
    const detail = await res.text();
    throw new GroqError(
      res.status === 429
        ? "The AI service is rate limited right now. Try again shortly."
        : "The AI service is unavailable.",
      res.status === 429 ? 429 : 502,
      `groq ${res.status}: ${detail.slice(0, 500)}`,
    );
  }

  const payload = await res.json();
  const choice = payload?.choices?.[0];
  const content: string = choice?.message?.content ?? "";

  if (!content.trim()) {
    throw new GroqError(
      "The AI service returned nothing.",
      502,
      `empty content, finish_reason=${choice?.finish_reason}`,
    );
  }
  return content;
}

/** Strip ```json fences and surrounding prose before JSON.parse. */
export function extractJson<T>(raw: string): T {
  let s = raw.trim();
  const fence = s.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fence) s = fence[1].trim();
  const start = s.indexOf("{");
  const end = s.lastIndexOf("}");
  if (start !== -1 && end > start) s = s.slice(start, end + 1);
  return JSON.parse(s) as T;
}
