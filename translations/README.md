# Translations of the ECU texts

The texts that BimmerData shows from the ECU description files (SGBDs, `.PRG`) are in German:
measurement labels, job descriptions, fault texts, symptoms, environment values, state texts.
This folder holds their translations, **one independent file per ECU and per language**:

```
translations/<language>/<sgbd>.csv      e.g. translations/fr/msv70.csv
```

The app downloads the file of an ECU when it first shows one of its texts (default source: this
folder on GitHub, `https://raw.githubusercontent.com/jonathan-gtd/BimmerData/main/translations`),
keeps it on the phone and works offline afterwards. In *Settings › Translations* you choose the
language, the ECUs to translate, and another source if you want (your own server or private git
repository with a token, or a folder on the phone).

## Format

UTF-8, `;` separated, one header line:

```
type;cle;de;traduction;statut
mesure;0x4402;Öltemperatur;Température d'huile;relu
defaut;0x2F08;Ansauglufttemperatursensor, Signal;Sonde de température d'air d'admission, signal;relu
job;STATUS_OEL;Ölzustand auslesen;Lire l'état de l'huile;auto
ecu;;MSV70 fuer N52;Électronique moteur MSV70 (N52);auto
```

| column | meaning |
|---|---|
| `type` | what is translated, see below |
| `cle` | key: always an identifier of the `.PRG`, never the text |
| `de` | the German text of the `.PRG` the file was made from (reference and guard) |
| `traduction` | the translation; empty = not translated yet (the German text is shown) |
| `statut` | `auto` (machine or unchecked translation) or `relu` (reviewed by a human) |

| type | key | example | from the `.PRG` |
|---|---|---|---|
| `mesure` | measurement id | `0x4402` | `MESSWERTETAB.ID` → `INFO` |
| `resultat` | `JOB/RESULT`, or `*/RESULT` for every job | `STATUS_OEL/STAT_PHY_NIVOEL_WERT` | results of read-only jobs (label shown in the app) |
| `defaut` | fault code | `0x2F08` | `FORTTEXTE.ORT` → `ORTTEXT` (without the code) |
| `symptome` | symptom code | `0x0A` | `FARTTEXTEINDIVIDUELL` or `FARTTEXTE.ARTNR` |
| `job` | job name | `STATUS_OEL` | first sentence of the job comment |
| `table` | `TABLE/value` | `LAMBDASTATUS/0x02` | small text tables: environment texts (`FUMWELTTEXTE`), states, fault status texts |
| `ecu` | (empty) | | ECU description of the SGBD |

Keys are case-insensitive. Numbers may be written `0x2F08`, `0x2f08` or `2F08` (measurement ids
and fault codes of up to 4 characters are hex), or in decimal with 5 digits or more.

**The `de` guard.** A translation is only shown when the German text of *your* `.PRG` under that
key is still the one in the `de` column (case, spaces, punctuation and `ue`/`ü` spelling ignored).
If another version of the ECU file has a different text under the same code, the app keeps the
German text and counts the line as "to check" in *Settings › Translations*: a wrong fault text is
worse than a German one. Lines with an empty `de` are not checked.

Texts the ECU returns at run time without a key (fault symptoms and status texts, environment
value names, state texts of results) are matched by their German text against the `de` column of
any line of the file.

## Making a file for an ECU

The file is generated from **your own ECU file**, with every key and the German text filled in
and the translation empty:

- in the app: *Faults* › the ECU › **Export for translation** (if you already have translations
  for it, they are kept in the export);
- on a PC with the source code and your EDIABAS `Ecu` folder:

  ```
  ./gradlew :live:translationTemplate -Psgbd=msv70 -Plang=fr -PecuDir=/path/to/EDIABAS/Ecu
  # keep the translations of an existing file:
  ./gradlew :live:translationTemplate -Psgbd=msv70 -Plang=fr -Pmerge=translations/fr/msv70.csv -Pout=translations/fr/msv70.csv
  ```

Open it in LibreOffice Calc (character set UTF-8, separator `;`, column types "Text"), fill the
`traduction` column, set `statut` to `relu` for what you checked, save as CSV with the same
options, and send it as a pull request (or put it in your own source).

Only text goes into these files: no ECU file, no binary, nothing that would let anyone rebuild a
`.PRG`.

## Where the current files come from

`en/msv70.csv` and `fr/msv70.csv` were seeded with [`tools/seed_translations.py`](../tools/seed_translations.py)
from a template of `MSV70.PRG` and community material: the TestO datalogger translation file
(`jobs_all_en_EN.po`, job descriptions), its MSV70 measurement list (English labels), and a French
INPA fault memory printout. Everything seeded is marked `auto` and needs a review; job descriptions
taken from the TestO file come from jobs of the same name, sometimes of other ECUs, and can be
wrong. Rows with the same German text share the same translation.
