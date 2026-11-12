// Topic moderation for AltScroll.
//
// A post is admitted to the feed only if it is genuinely informational or
// scientific. The LLM provider sits behind `Classifier` so switching vendors
// (Groq -> Gemini -> Anthropic -> self-hosted) is a change to this file alone.

export interface PostDraft {
  title: string;
  body: string;
  sourceUrl?: string | null;
}

export interface Verdict {
  approve: boolean;
  /** 0..1 confidence that the post is informational/scientific. */
  score: number;
  /** Short, user-facing explanation. */
  reason: string;
  /** Suggested topic bucket, e.g. "physics". */
  topic: string | null;
  tags: string[];
  model: string;
}

export interface Classifier {
  readonly model: string;
  classify(draft: PostDraft): Promise<Verdict>;
}

export const RUBRIC = `You are the submission gate for AltScroll, a feed that only
carries informational and scientific content. You decide whether a user's post
may be published.

APPROVE posts that are:
- Science: physics, chemistry, biology, astronomy, earth/climate science, medicine.
- Mathematics, engineering, computing, or technology explained substantively.
- History, economics, or social science presented factually and informatively.
- Explanations, summaries of research, data analysis, or "how/why X works".
- Genuine, well-posed technical or scientific questions.

REJECT posts that are:
- Personal updates, diary entries, selfies, mood posts, or social chatter.
- Marketing, promotion, spam, affiliate or engagement bait.
- Politics-as-opinion, hot takes, rants, or culture-war content.
- Hate, harassment, sexual content, graphic violence, or self-harm material.
- Pseudoscience or health misinformation stated as established fact
  (astrology, anti-vaccine claims, miracle cures, flat earth).
- Content too short or empty to carry information (a bare link, "check this out").

Judge the SUBSTANCE, not the writing quality. A clumsily written but real
explanation of photosynthesis is APPROVE. A polished post about someone's
weekend is REJECT.

Respond with ONLY a JSON object, no prose and no code fences:
{
  "approve": boolean,
  "score": number,          // 0..1 confidence the post is informational/scientific
  "reason": string,         // one sentence, addressed to the author, max 160 chars
  "topic": string | null,   // lowercase single word bucket, e.g. "biology"
  "tags": string[]          // up to 4 lowercase keywords
}`;

/** Strip ```json fences and surrounding prose before JSON.parse. */
function extractJson(raw: string): unknown {
  let s = raw.trim();
  const fence = s.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fence) s = fence[1].trim();
  const start = s.indexOf("{");
  const end = s.lastIndexOf("}");
  if (start !== -1 && end > start) s = s.slice(start, end + 1);
  return JSON.parse(s);
}

function coerce(parsed: Record<string, unknown>, model: string): Verdict {
  const score = Number(parsed.score);
  return {
    approve: parsed.approve === true,
    score: Number.isFinite(score) ? Math.min(Math.max(score, 0), 1) : 0,
    reason: typeof parsed.reason === "string" && parsed.reason.trim()
      ? parsed.reason.trim().slice(0, 300)
      : "No reason supplied by the moderation model.",
    topic: typeof parsed.topic === "string" && parsed.topic.trim()
      ? parsed.topic.trim().toLowerCase().slice(0, 40)
      : null,
    tags: Array.isArray(parsed.tags)
      ? parsed.tags.filter((t): t is string => typeof t === "string")
          .slice(0, 4).map((t) => t.toLowerCase().slice(0, 30))
      : [],
    model,
  };
}

/**
 * Groq free tier: 30 req/min, 1K req/day, 200K tokens/day on gpt-oss-20b.
 * Measured usage is ~580 tokens per post including reasoning tokens, so the
 * daily token budget is the binding limit at roughly 340 posts/day.
 */
export class GroqClassifier implements Classifier {
  readonly model: string;

  constructor(
    private readonly apiKey: string,
    model = Deno.env.get("MODERATION_MODEL") ?? "openai/gpt-oss-20b",
  ) {
    this.model = model;
  }

  async classify(draft: PostDraft): Promise<Verdict> {
    const user = [
      `TITLE: ${draft.title}`,
      `BODY: ${draft.body}`,
      draft.sourceUrl ? `SOURCE URL: ${draft.sourceUrl}` : null,
    ].filter(Boolean).join("\n\n");

    const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: this.model,
        temperature: 0,
        // gpt-oss is a reasoning model: it spends tokens thinking before it
        // answers, and those count against max_tokens. A tight budget returns
        // an empty `content` with finish_reason "length". 900 with low effort
        // leaves ample room - measured usage is ~580 tokens per post.
        max_tokens: 900,
        reasoning_effort: "low",
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: RUBRIC },
          { role: "user", content: user },
        ],
      }),
    });

    if (!res.ok) {
      const detail = await res.text();
      throw new ModerationError(
        res.status === 429
          ? "The moderation service is rate limited right now. Please try again shortly."
          : "The moderation service is unavailable. Please try again later.",
        res.status === 429 ? 429 : 502,
        `groq ${res.status}: ${detail.slice(0, 500)}`,
      );
    }

    const payload = await res.json();
    const choice = payload?.choices?.[0];
    const content: string = choice?.message?.content ?? "";

    // An empty body with finish_reason "length" means reasoning consumed the
    // whole budget. Say so plainly rather than blaming the parser.
    if (!content.trim()) {
      throw new ModerationError(
        "The moderation service timed out on that post. Try again, or shorten it.",
        502,
        `empty content, finish_reason=${choice?.finish_reason}`,
      );
    }

    try {
      return coerce(extractJson(content) as Record<string, unknown>, this.model);
    } catch {
      throw new ModerationError(
        "The moderation service returned an unreadable response. Please try again.",
        502,
        `unparseable content: ${content.slice(0, 500)}`,
      );
    }
  }
}

export class ModerationError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly detail?: string,
  ) {
    super(message);
  }
}

export function buildClassifier(): Classifier {
  const groqKey = Deno.env.get("GROQ_API_KEY");
  if (groqKey) return new GroqClassifier(groqKey);
  throw new ModerationError("No moderation provider configured.", 500,
    "set GROQ_API_KEY via `supabase secrets set`");
}

/**
 * Cheap deterministic pre-filter. Catches obvious non-posts before spending a
 * request against the free-tier quota.
 */
export function prefilter(draft: PostDraft): Verdict | null {
  const words = draft.body.trim().split(/\s+/).filter(Boolean).length;
  if (words < 12) {
    return {
      approve: false,
      score: 0,
      reason:
        "Too short to be informative — add at least a couple of sentences explaining the idea.",
      topic: null,
      tags: [],
      model: "prefilter",
    };
  }
  return null;
}
