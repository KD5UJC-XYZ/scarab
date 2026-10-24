# Confirm a rule with the conway-decoding oracle

The oracle runs the released `cardano-ledger-binary` decoders on hand-written encodings and records the verdict and the complete failure, with no ledger rule. Scarab's suite then checks that it agrees. Details: `scripts/oracle/conway-decoding/README.md`.

1. Write the case in `scripts/oracle/conway-decoding/cases.py` (Conway, decoder version 9; Babbage twins at 7) or `scripts/oracle/conway-decoding/pre_conway_cases.py` (Shelley to Babbage). Each case breaks one rule and has a twin that keeps it. The encodings are written by hand, not produced by Scarab.
2. Build the context-reference image once (`scripts/oracle/plutus/context/README.md`), then regenerate the cases and run the oracle offline:

```sh
out="${SCARAB_DATA_DIR:-/tmp/scarab}/oracle"
mkdir -p "$out"
python3 scripts/oracle/conway-decoding/cases.py --out "$out/conway-decoding-cases.json"
docker build -f scripts/oracle/conway-decoding/Dockerfile -t scarab-conway-decoding:local .
docker run --rm --network none --read-only -v "$out:/fixtures:ro" \
  scarab-conway-decoding:local /fixtures/conway-decoding-cases.json > "$out/conway-decoding-reference.json"
```

3. Compare with the committed corpus. A new case changes both files; an existing case must not change.

```sh
diff "$out/conway-decoding-reference.json" fixtures/reference/conway-decoding/reference.json
diff "$out/conway-decoding-cases.json" fixtures/reference/conway-decoding/cases.json
```

4. Accept the new corpus by copying both files into `fixtures/reference/conway-decoding/`, and record the pins in `fixtures/reference/conway-decoding/provenance.json` and the case count in `fixtures/reference/conway-decoding/README.md`.
5. Run the Scala side, which reads the corpus through `ReferenceCorpus`:

```sh
bash scripts/sbtw "ledger/testOnly xyz.kd5ujc.scarab.ledger.ConwayDecodingReferenceSuite"
```

6. Fix the decoder until the suite agrees with the reference verdict and failure for every case, with no exclusions. Then run `bash scripts/sbtw "fuzz 100000 1 4"` (decoder change) and the level gates ([run the gates](run-the-gates.md)).
7. Remove `$out` when you are done.
