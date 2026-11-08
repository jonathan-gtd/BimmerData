#!/usr/bin/env python3
# BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
"""
Pre-translate a BimmerData translation file (translations/<lang>/<sgbd>.csv) with any
OpenAI-compatible chat API (a local llama.cpp / Ollama / vLLM server, or a hosted one).

- Only rows with an empty translation are filled (use --overwrite-auto to redo `auto` rows);
  rows marked `relu` (reviewed) are never touched. Filled rows get the status `auto`.
- Each distinct German text is translated once per type, so the same text always gets the same
  translation (consistent labels).
- A glossary (tools/glossary_de_fr_en.csv) is injected into the prompt.
- Answers are cached on disk (JSON lines) so an interrupted run resumes where it stopped.
- Keys, the German column and the file layout are never changed.

Standard library only. Example (local llama.cpp server):

  python tools/translate_ecu.py --repo . --sgbd msv70 --lang fr \
      --api-url http://127.0.0.1:8080/v1 --model qwen3.8-27b --key-file ~/llm-api-key.txt
"""
import argparse
import csv
import io
import json
import os
import re
import sys
import time
import urllib.request

HEADER = ["type", "cle", "de", "traduction", "statut"]

LANG_NAMES = {"fr": "French", "en": "English", "es": "Spanish", "it": "Italian", "nl": "Dutch",
              "pl": "Polish", "pt": "Portuguese", "cs": "Czech", "sv": "Swedish"}

TYPE_HINTS = {
    "mesure": "live measurement label shown on a gauge (short, like an instrument label)",
    "resultat": "result name of a diagnostic job (short label)",
    "job": "description of a diagnostic job / function of the ECU (short sentence or label)",
    "defaut": "fault code text (DTC description) shown in a fault list",
    "symptome": "fault symptom / fault type text (e.g. short to ground, signal too high)",
    "table": "state or value text from a small status table",
    "ecu": "description of the ECU (control unit)",
}


def read_csv(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.reader(f, delimiter=";"))
    if not rows or [c.strip().lower() for c in rows[0][:5]] != HEADER:
        sys.exit(f"{path}: unexpected header {rows[:1]}")
    return [r + [""] * (5 - len(r)) for r in rows[1:]]


def write_csv(path, rows):
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";", lineterminator="\n", quoting=csv.QUOTE_MINIMAL)
    w.writerow(HEADER)
    w.writerows(rows)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(buf.getvalue())
    os.replace(tmp, path)


def load_glossary(path, lang):
    if not path or not os.path.exists(path):
        return []
    with open(path, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f, delimiter=";"))
    return [(r["de"], r[lang]) for r in rows if r.get(lang)]


def chat(api_url, model, key, system, user, timeout, thinking):
    body = {
        "model": model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
    }
    if not thinking:
        body["chat_template_kwargs"] = {"enable_thinking": False}
    req = urllib.request.Request(api_url.rstrip("/") + "/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + key} if key else {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = json.load(r)
    return data["choices"][0]["message"]["content"], data.get("usage", {})


def extract_json(text):
    text = re.sub(r"^```(json)?|```$", "", text.strip(), flags=re.M).strip()
    start, end = text.find("{"), text.rfind("}")
    return json.loads(text[start:end + 1])


DIGITS = re.compile(r"\d+")


def check(de, tr):
    """Problems of one translation (empty list = fine)."""
    issues = []
    if not tr.strip():
        issues.append("empty")
    if sorted(DIGITS.findall(de)) != sorted(DIGITS.findall(tr)):
        issues.append("digits differ")
    if len(tr) > max(40, 3 * len(de)):
        issues.append("too long")
    return issues


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--repo", default=".", help="BimmerData checkout (holds translations/ and tools/)")
    p.add_argument("--sgbd", required=True)
    p.add_argument("--lang", required=True)
    p.add_argument("--csv", help="explicit file instead of <repo>/translations/<lang>/<sgbd>.csv")
    p.add_argument("--api-url", default="http://127.0.0.1:8080/v1")
    p.add_argument("--model", required=True)
    p.add_argument("--key-file", help="file holding the API key (optional)")
    p.add_argument("--glossary", help="default: <repo>/tools/glossary_de_fr_en.csv")
    p.add_argument("--batch", type=int, default=40)
    p.add_argument("--types", help="comma-separated types to translate (default: all)")
    p.add_argument("--overwrite-auto", action="store_true", help="also redo rows with status auto")
    p.add_argument("--cache", help="answer cache (JSON lines), default <csv>.cache.jsonl")
    p.add_argument("--timeout", type=int, default=600)
    p.add_argument("--thinking", action="store_true", help="let the model think (slower)")
    p.add_argument("--limit", type=int, default=0, help="stop after N batches (testing)")
    a = p.parse_args()

    path = a.csv or os.path.join(a.repo, "translations", a.lang, a.sgbd + ".csv")
    rows = read_csv(path)
    key = open(os.path.expanduser(a.key_file), encoding="utf-8").read().strip() if a.key_file else ""
    glossary = load_glossary(a.glossary or os.path.join(a.repo, "tools", "glossary_de_fr_en.csv"), a.lang)
    types = set(a.types.split(",")) if a.types else None
    target = LANG_NAMES.get(a.lang, a.lang)
    cache_path = a.cache or path + ".cache.jsonl"

    cache = {}
    if os.path.exists(cache_path):
        for line in open(cache_path, encoding="utf-8"):
            e = json.loads(line)
            cache[(e["type"], e["de"])] = e["tr"]

    def wanted(r):
        t, _, de, tr, st = r[:5]
        if not de.strip() or (types and t not in types) or st == "relu":
            return False
        return not tr.strip() or (a.overwrite_auto and st == "auto")

    todo = {}
    for r in rows:
        if wanted(r) and (r[0], r[2]) not in cache:
            todo.setdefault(r[0], [])
            if r[2] not in todo[r[0]]:
                todo[r[0]].append(r[2])

    gloss = "\n".join(f"- {de} = {tr}" for de, tr in glossary)
    system = (
        f"You translate German texts from BMW engine/vehicle diagnostic files (EDIABAS SGBD '{a.sgbd}') "
        f"into {target}. Rules: keep the meaning exact and technical; be short, like a dashboard or "
        "diagnostic tester label; keep every number, unit, code, abbreviation and proper name as is "
        "(DME, VANOS, Valvetronic, DISA, DMTL, HFM, CAN, PWM, Bank 1, Bank 2, cylinder numbers); do not "
        "add explanations; German umlaut spellings like ue/oe/ae mean ü/ö/ä; texts that are already "
        "English or pure identifiers may be returned in the target language or unchanged. "
        "Use this glossary when a term appears:\n" + gloss + "\n"
        'Answer ONLY with JSON: {"t": ["translation 1", "translation 2", ...]} in the same order and count.'
    )

    batches = [(t, texts[i:i + a.batch]) for t, texts in todo.items() for i in range(0, len(texts), a.batch)]
    total = sum(len(b) for _, b in batches)
    print(f"{path}: {len(rows)} rows, {total} distinct texts to translate in {len(batches)} batches", flush=True)
    t0, done, out_tokens = time.time(), 0, 0
    with open(cache_path, "a", encoding="utf-8") as cf:
        for n, (t, texts) in enumerate(batches, 1):
            if a.limit and n > a.limit:
                break
            user = f"Type of texts: {TYPE_HINTS.get(t, t)}.\n" + json.dumps({"de": texts}, ensure_ascii=False)
            for attempt in range(3):
                try:
                    content, usage = chat(a.api_url, a.model, key, system, user, a.timeout, a.thinking)
                    out = extract_json(content)["t"]
                    if len(out) != len(texts):
                        raise ValueError(f"{len(out)} answers for {len(texts)} texts")
                    break
                except Exception as ex:  # retry on bad JSON / count mismatch / network
                    print(f"  batch {n}: attempt {attempt + 1} failed: {ex}", flush=True)
                    out = None
            if out is None:
                continue
            out_tokens += usage.get("completion_tokens", 0)
            for de, tr in zip(texts, out):
                tr = str(tr).strip()
                cache[(t, de)] = tr
                cf.write(json.dumps({"type": t, "de": de, "tr": tr}, ensure_ascii=False) + "\n")
            cf.flush()
            done += len(texts)
            el = time.time() - t0
            print(f"  batch {n}/{len(batches)} {t}: {done}/{total} texts, {el:.0f}s, "
                  f"{out_tokens / max(el, 1):.1f} tok/s", flush=True)

    filled, problems = 0, []
    for r in rows:
        if wanted(r) and (r[0], r[2]) in cache:
            r[3], r[4] = cache[(r[0], r[2])], "auto"
            filled += 1
            for issue in check(r[2], r[3]):
                problems.append(f"{r[0]};{r[1]};{issue}: {r[2]} -> {r[3]}")
    write_csv(path, rows)
    print(f"filled {filled} rows; {len(problems)} rows to check")
    for line in problems[:200]:
        print("  CHECK " + line)


if __name__ == "__main__":
    main()
