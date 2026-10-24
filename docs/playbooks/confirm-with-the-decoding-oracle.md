<!-- SPDX-License-Identifier: Apache-2.0 -->
# Confirm a rule with the conway-decoding oracle

The oracle runs the released `cardano-ledger-binary` decoders on hand-written encodings and records the verdict and the complete failure, with no ledger rule. Scarab's suite then checks that it agrees. Details: `scripts/oracle/conway-decoding/README.md`.

1. Write the case in `scripts/oracle/conway-decoding/cases.py` (Conway, decoder version 9; Babbage twins at 7) or `scripts/oracle/conway-decoding/pre_conway_cases.py` (Shelley to Babbage). Each case breaks one rule and has a twin that keeps it. The encodings are written by hand, not produced by Scarab.
2. Build the context-reference image once (`scripts/oracle/plutus/context/README.md`). Write the cases straight into the fixtures (unchanged cases stay byte-identical; `git diff` shows only yours) and run the oracle offline on them:

```sh
out="${SCARAB_DATA_DIR:-/tmp/scarab}/oracle"
mkdir -p "$out"
python3 scripts/oracle/conway-decoding/cases.py --out fixtures/reference/conway-decoding/cases.json
docker build -f scripts/oracle/conway-decoding/Dockerfile -t scarab-conway-decoding:local .
docker run --rm --network none --read-only -v "$PWD/fixtures/reference/conway-decoding:/fixtures:ro" \
  scarab-conway-decoding:local /fixtures/cases.json > "$out/reference.json"
```

3. Compare by JSON value, as the oracle README does, not by bytes: every committed case keeps its verdict; only new cases may be added.

```sh
python3 - "$out/reference.json" <<'PY'
import json, sys
from pathlib import Path
def verdicts(text):
    return {(v["era"], v["kind"], v["name"]): v for v in json.loads(text)}
new = verdicts(Path(sys.argv[1]).read_text())
old = verdicts(Path("fixtures/reference/conway-decoding/reference.json").read_text())
print("changed or missing committed verdicts:", [k for k, v in old.items() if new.get(k) != v])
print("new cases:", len(new) - len(old))
PY
```

4. Accept: copy `$out/reference.json` over `fixtures/reference/conway-decoding/reference.json`, and record the pins in `fixtures/reference/conway-decoding/provenance.json` and the case count in `fixtures/reference/conway-decoding/README.md`.
5. Run the Scala side, which reads the corpus through `ReferenceCorpus`:

```sh
sbtw "ledger/testOnly xyz.kd5ujc.scarab.ledger.ConwayDecodingReferenceSuite"
```

6. Fix the decoder until the suite agrees with the reference verdict and failure for every case, with no exclusions. Then run `sbtw "fuzz 100000 1 4"` (the Docker wrapper of [build in Docker](build-in-docker-from-a-worktree.md)) (decoder change) and the level gates ([run the gates](run-the-gates.md)).
7. Remove `$out` when you are done.
