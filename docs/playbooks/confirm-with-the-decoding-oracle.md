<!-- SPDX-License-Identifier: Apache-2.0 -->
# Confirm a decoding rule with the reference decoders

**Status: Current.** The harness runs the released `cardano-ledger-binary` decoders on hand-written
encodings and records each verdict and complete failure, with no ledger rule. Its README,
[scripts/oracle/conway-decoding/README.md](../../scripts/oracle/conway-decoding/README.md), is the
authoritative recipe; this page adds the order of work and the check before a corpus is replaced.

| Corpus | Cases from | Committed in | Scala suite |
|---|---|---|---|
| Conway bodies and witness sets (version 9; Babbage bodies at 7) | `cases.py` | `fixtures/reference/conway-decoding` | `ConwayDecodingReferenceSuite` |
| Pre-Conway sets (Shelley to Babbage) | `pre_conway_cases.py` | `fixtures/reference/pre-conway-sets` | `PreConwaySetsReferenceSuite` |
| Definite and indefinite byte strings | `definite_bytes_cases.py` | `fixtures/reference/definite-bytes` | `DefiniteBytesReferenceSuite` |

1. Add the case to the corpus's generator in `scripts/oracle/conway-decoding/`: one encoding that
   breaks the rule and a twin that keeps it, written by hand, not produced by Scarab.
2. Build the images as the README says. Make a scratch directory of your own; every later command
   reads it as `${out:?}`, so it refuses to run if this step failed:

   ```sh
   data="${SCARAB_DATA_DIR:-/tmp/scarab}"
   out="$(mkdir -p "$data" && mktemp -d "$data/oracle-XXXXXX")" || out=
   test -n "$out" && echo "scratch: $out" || echo "stop: no scratch directory" >&2
   ```

   Run the harness into it. Conway (pre-Conway: `pre_conway_cases.py`, compared against
   `fixtures/reference/pre-conway-sets`):

   ```sh
   python3 scripts/oracle/conway-decoding/cases.py --out "${out:?}/cases.json"
   docker run --rm --network none --read-only -v "${out:?}:/fixtures:ro" \
     scarab-conway-decoding:local /fixtures/cases.json > "${out:?}/reference.json"
   ```

   Definite bytes: the corpus is one merged `cases.jsonl`, made by the three commands of the README
   with `${out:?}` in place of `/tmp` (`definite-bytes-inputs.json`, then the harness, then
   `--out "${out:?}/cases.jsonl"`).
3. Check that every committed case keeps its encoding and its verdict before you replace anything.
   This exits non-zero on any change and replaces nothing; pass the corpus's committed directory:

   ```sh
   python3 - "${out:?}" fixtures/reference/conway-decoding <<'PY'
   import json, sys
   from pathlib import Path
   new, old = Path(sys.argv[1]), Path(sys.argv[2])
   def entries(path):
       text = path.read_text() if path.exists() else ""
       rows = [json.loads(l) for l in text.splitlines() if l] if path.suffix == ".jsonl" else json.loads(text or "[]")
       return {v.get("id") or (v["era"], v["kind"], v["name"]): v for v in rows}
   files = [f for f in ("cases.json", "reference.json", "cases.jsonl") if (old / f).exists()]
   changed = [(f, k) for f in files for k, v in entries(old / f).items() if entries(new / f).get(k) != v]
   print("committed cases changed or missing:", changed)
   sys.exit(1 if changed else 0)
   PY
   ```

4. Only then copy the corpus's files from `$out` over the committed ones (`cases.json` and
   `reference.json`, or `cases.jsonl` for definite bytes), and update its `provenance.json` and
   README as they describe.
5. Run the corpus's suite, for example
   `scripts/sbtw "ledger/testOnly xyz.kd5ujc.scarab.ledger.ConwayDecodingReferenceSuite"`, and fix
   the decoder until it agrees with every reference verdict and failure. A decoder change also runs
   the decoder gate of [CONTRIBUTING](../../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change).
6. Remove `$out` when you are done.
