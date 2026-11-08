#!/usr/bin/env python3
"""Fill a BimmerData translation template from existing community material.

Input: a template made from YOUR OWN SGBD (keys and German texts are taken from it, so every key
is checked against the real file):

    ./gradlew :live:translationTemplate -Psgbd=msv70 -Plang=xx

Sources (all optional):
  --po FILE        TestO "jobs_all_en_EN.po" (job / result name -> English text), for English
  --jobs-txt FILE  TestO "MSV70_jobs.txt" (tab separated: ARG, ID, RESULTNAME, English label,
                   unit, LABEL, type, NAME, German label), for English
  --inpa-fr FILE   `pdftotext -layout` output of a French INPA fault memory printout
                   ("Erreur : 1(2) N° : 11313   texte", "type d'erreur : 2   (10) texte"), for French

Everything this script fills gets status "auto" (to be reviewed), and the output keeps every key
of the template, translated or not, so that the files can be completed by hand.

    python3 tools/seed_translations.py --template build/translations/xx/msv70.csv \
        --po jobs_all_en_EN.po --jobs-txt MSV70_jobs.txt --inpa-fr inpa_fr.txt --out translations
"""
import argparse
import csv
import io
import os
import re
import sys
import unicodedata

HEADER = ["type", "cle", "de", "traduction", "statut"]


def norm(text):
    """Same comparison as GermanText.normalize in the app."""
    t = text.lower().replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
    t = unicodedata.normalize("NFD", t)
    return "".join(c for c in t if ("a" <= c <= "z") or ("0" <= c <= "9"))


def read_template(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.reader(f, delimiter=";"))
    assert rows and rows[0][:2] == ["type", "cle"], f"{path}: not a translation template"
    return [r + [""] * (5 - len(r)) for r in rows[1:] if r]


def write(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";", lineterminator="\n", quoting=csv.QUOTE_MINIMAL)
    w.writerow(HEADER)
    for r in rows:
        w.writerow([r[0], r[1], r[2], r[3], r[4] if r[3] else ""])
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(buf.getvalue())


# ---------------------------------------------------------------- English

GERMAN = re.compile(
    r"[äöüßÄÖÜ]|\b(der|die|das|und|für|fuer|mit|von|bei|nicht|des|dem|den|zum|zur|ein|eine|einer|aus|auf|ist|wird|"
    r"werden|wert|werte|ergebnis|wenn|sonst|ja|nein|zustand|lesen|auslesen|ansteuern|ansteuerung|steuern|ueber|über|nach|vor|im|am|oder|bzw|"
    r"spannung|temperatur|drehzahl|status|abgleich|fehler|motor|klappe|sonde|zylinder|ventil|lesen)\b",
    re.IGNORECASE,
)
UNIT_SUFFIX = re.compile(
    r"\s*[\(\[](degC|deg C|°C|%|V|mV|rpm|1/min|km/h|hPa|kPa|mbar|bar|kg/h|mg/stk|mg/str|ms|s|deg|°CRK|°KW|Nm|A|mA|Ohm|l|K|Hz)[\)\]]\s*$",
    re.IGNORECASE,
)


NOT_A_LABEL = re.compile(r"_|\bunit\b|\beinheit\b|\bbyte|\bbis\b|=|^0x|\b(min|max)$", re.IGNORECASE)
WORDS = set()


def load_words(path):
    if path and os.path.isfile(path):
        with open(path, encoding="utf-8", errors="ignore") as f:
            WORDS.update(w.strip().lower() for w in f if w.strip())


def mostly_english(t):
    """At least 70 % of the words of 3+ letters are English words (or upper-case abbreviations)."""
    if not WORDS:
        return True
    words = re.findall(r"[A-Za-z]{3,}", t)
    if not words:
        return True
    ok = sum(1 for w in words if w.isupper() or w.lower() in WORDS or w.lower().rstrip("s") in WORDS)
    return ok >= 0.7 * len(words)


def english(text, german):
    """Cleaned English text, or None if it is German, a unit / range note or identical to the German one."""
    t = UNIT_SUFFIX.sub("", (text or "").strip()).strip()
    if not t or not any(c.isalpha() for c in t):
        return None
    if german and norm(t) == norm(german):
        return None
    if GERMAN.search(t) or NOT_A_LABEL.search(t):
        return None
    if re.fullmatch(r"[A-Z0-9_ ]+", t):  # an identifier, not a text
        return None
    if not mostly_english(t):
        return None
    return t


def read_po(path):
    """{(context, msgid): msgstr} with context JOB_L / JOB_S / RES_L / RES_S."""
    out = {}
    ctx = mid = None
    cur = None
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            m = re.match(r'^(msgctxt|msgid|msgstr)\s+"(.*)"$', line)
            if m:
                cur = m.group(1)
                val = bytes(m.group(2), "utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8")
                if cur == "msgctxt":
                    ctx, mid = val.rstrip("|"), None
                elif cur == "msgid":
                    mid = val
                else:
                    if ctx and mid:
                        out[(ctx, mid.upper())] = val
                continue
            m = re.match(r'^"(.*)"$', line)
            if m and cur == "msgstr" and ctx and mid:
                out[(ctx, mid.upper())] = out.get((ctx, mid.upper()), "") + m.group(1)
    return out


def read_jobs_txt(path):
    """{id: (result_name, english, german)} from MSV70_jobs.txt."""
    out = {}
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            p = line.rstrip("\r\n").split("\t")
            if len(p) < 9:
                continue
            try:
                ident = int(p[1], 16)
            except ValueError:
                continue
            out[ident] = (p[2].strip().upper(), p[3].strip(), p[8].strip())
    return out


def seed_english(rows, po, jobs_txt, stats):
    for r in rows:
        typ, key, de = r[0], r[1], r[2]
        en = None
        src = None
        if typ == "mesure":
            ident = int(key, 16)
            if ident in jobs_txt:
                result, label, german = jobs_txt[ident]
                if german and norm(german) != norm(de):
                    stats["jobs-txt German differs from the SGBD"] = stats.get("jobs-txt German differs from the SGBD", 0) + 1
                en = english(label, de)
                src = "jobs-txt"
                if en is None and po and not result.startswith("STAT_0X"):
                    en = english(po.get(("RES_L", result)), de)
                    src = "po"
        # Result texts of the .po (RES_L) are mostly unit / range notes ("2BYTE in 0 bis 4095", "Unit"):
        # not used. Results get a translation below when their German text equals a translated one.
        elif typ == "job" and po:
            en = english(po.get(("JOB_L", key)) or po.get(("JOB_S", key)), de)
            src = "po"
        if en:
            r[3], r[4] = en, "auto"
            stats[src] = stats.get(src, 0) + 1
    propagate(rows, stats)


def propagate(rows, stats):
    """Untranslated rows whose German text equals the German text of a translated row get its
    translation (e.g. FUMWELTTEXTE environment texts = MESSWERTETAB labels)."""
    known = {}
    for r in rows:
        if r[3] and r[2]:
            known.setdefault(norm(r[2]), r[3])
    for r in rows:
        if not r[3] and r[2] and norm(r[2]) in known:
            r[3], r[4] = known[norm(r[2])], "auto"
            stats["same German text"] = stats.get("same German text", 0) + 1


# ---------------------------------------------------------------- French (INPA printout)

def seed_french(rows, inpa_text, stats):
    faults = {}
    symptoms = {}
    pending_fault = None
    for line in inpa_text.splitlines():
        m = re.search(r"N°\s*:\s*(\d+)\s{2,}(\S.*)$", line)
        if m:
            pending_fault = int(m.group(1))
            faults[pending_fault] = m.group(2).strip()
            continue
        m = re.search(r"type d'erreur\s*:\s*\d+\s+\((\d+)\)\s*(\S.*)$", line)
        if m:
            symptoms[int(m.group(1))] = m.group(2).strip()
    by_key = {(r[0], r[1]): r for r in rows}
    for code, text in faults.items():
        r = by_key.get(("defaut", "0x%04X" % code))
        if r is not None and not r[3]:
            r[3], r[4] = text[0].upper() + text[1:], "auto"
            stats["inpa-fr defaut"] = stats.get("inpa-fr defaut", 0) + 1
        elif r is None:
            stats["inpa-fr defaut not in SGBD"] = stats.get("inpa-fr defaut not in SGBD", 0) + 1
    for code, text in symptoms.items():
        r = by_key.get(("symptome", "0x%02X" % code))
        if r is not None and not r[3]:
            r[3], r[4] = text[0].upper() + text[1:], "auto"
            stats["inpa-fr symptome"] = stats.get("inpa-fr symptome", 0) + 1
    propagate(rows, stats)


def coverage(rows):
    total, done = {}, {}
    for r in rows:
        total[r[0]] = total.get(r[0], 0) + 1
        if r[3]:
            done[r[0]] = done.get(r[0], 0) + 1
    return ", ".join(f"{t} {done.get(t, 0)}/{n}" for t, n in total.items())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--template", required=True)
    ap.add_argument("--sgbd", help="file name (default: template file name)")
    ap.add_argument("--po")
    ap.add_argument("--jobs-txt")
    ap.add_argument("--inpa-fr")
    ap.add_argument("--out", default="translations")
    ap.add_argument("--dict", default="/usr/share/dict/words", help="English word list used to reject German texts")
    a = ap.parse_args()
    sgbd = (a.sgbd or os.path.splitext(os.path.basename(a.template))[0]).lower()
    template = read_template(a.template)
    load_words(a.dict)

    if a.po or a.jobs_txt:
        rows = [list(r) for r in template]
        stats = {}
        seed_english(rows, read_po(a.po) if a.po else {}, read_jobs_txt(a.jobs_txt) if a.jobs_txt else {}, stats)
        path = os.path.join(a.out, "en", sgbd + ".csv")
        write(path, rows)
        print(f"{path}: {coverage(rows)}  sources {stats}")
    if a.inpa_fr:
        rows = [list(r) for r in template]
        stats = {}
        with open(a.inpa_fr, encoding="utf-8") as f:
            seed_french(rows, f.read(), stats)
        path = os.path.join(a.out, "fr", sgbd + ".csv")
        write(path, rows)
        print(f"{path}: {coverage(rows)}  sources {stats}")


if __name__ == "__main__":
    sys.exit(main())
