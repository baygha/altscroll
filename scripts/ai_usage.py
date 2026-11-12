"""Print the live Groq quota for AltScroll.

    python scripts/ai_usage.py

Reads the key from the GROQ_API_KEY environment variable, or from a
git-ignored .groq_key file in the project root.

Groq reports request and per-minute token limits in response headers. The
200k/day token ceiling is not in the headers - for that, see the Metrics page
at https://console.groq.com, or run supabase/queries/ai_usage.sql for an
estimate based on what AltScroll actually generated.
"""
import os
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent


def api_key() -> str:
    key = os.environ.get("GROQ_API_KEY")
    if key:
        return key.strip()
    f = ROOT / ".groq_key"
    if f.exists():
        return f.read_text(encoding="utf-8").strip()
    sys.exit("No key. Set GROQ_API_KEY or create a .groq_key file.")


def main() -> None:
    req = urllib.request.Request(
        "https://api.groq.com/openai/v1/chat/completions",
        data=b'{"model":"openai/gpt-oss-20b","max_tokens":1,'
             b'"messages":[{"role":"user","content":"hi"}]}',
        headers={
            "Authorization": f"Bearer {api_key()}",
            "Content-Type": "application/json",
            # urllib's default agent is rejected with a 403.
            "User-Agent": "altscroll-usage/1.0",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        h = {k.lower(): v for k, v in r.headers.items()}

    def pair(limit_key: str, remaining_key: str):
        limit = h.get(limit_key)
        remaining = h.get(remaining_key)
        if limit is None or remaining is None:
            return None
        return int(limit), int(remaining)

    print("Groq quota for AltScroll")
    print("-" * 46)

    reqs = pair("x-ratelimit-limit-requests", "x-ratelimit-remaining-requests")
    if reqs:
        limit, remaining = reqs
        used = limit - remaining
        pct = used * 100 // limit if limit else 0
        bar = "#" * (pct // 5) + "." * (20 - pct // 5)
        print(f"  requests today   {used:>6} / {limit:<6} [{bar}] {pct}%")
        print(f"                   resets in {h.get('x-ratelimit-reset-requests', '?')}")

    toks = pair("x-ratelimit-limit-tokens", "x-ratelimit-remaining-tokens")
    if toks:
        limit, remaining = toks
        used = limit - remaining
        print(f"  tokens this min  {used:>6} / {limit:<6} "
              f"(resets in {h.get('x-ratelimit-reset-tokens', '?')})")

    print()
    print("  Peter posts 48/day (~43k tokens) of the 200k/day token budget.")
    print("  Daily token total is not in these headers - see console.groq.com")


if __name__ == "__main__":
    main()
