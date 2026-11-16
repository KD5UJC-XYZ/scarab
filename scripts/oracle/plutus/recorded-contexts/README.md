<!-- SPDX-License-Identifier: Apache-2.0 -->
# Script contexts of recorded transactions (#43)

**Current: inputs for an unchanged observer; no Haskell source of its own.**

The recorded Preview and preprod Plutus transactions, and API-only Conway transactions with several
voters, collected by the [context-matrix observer](../context-matrix/README.md): unchanged released
`collectPlutusScriptsWithContext` at the case's own era type (`AlonzoEra`, `BabbageEra`,
`ConwayEra`), protocol version, system start and slot length, answering each collected request's
complete `PlutusWithContext` encoding. That observer fits because it takes a whole transaction with
the outputs it reads at a given era, protocol and clock, and answers the requests themselves; the
[context oracle](../context/README.md) reads every case as Conway on a fixed clock of 0.1 s slots
from the POSIX epoch and answers only script hashes. The observer's sources, Cabal pins and image are
used as they are, so the [context matrix's](../../../../fixtures/reference/context-matrix/README.md)
provenance stays valid; `run.py` requires the image's embedded sources and executable to be the ones
that provenance accepts before it runs.

`prepare.py` writes the inputs: each recorded file of `fixtures/preview/plutus` and
`fixtures/preprod/plutus` through the context oracle's `original_case` (the recorded body,
witnesses, validity flag and auxiliary data, and every output the file records), at the network's
clock line, which every recorded slot sample must lie on; and four variants of a recorded vote with
five voters. One preprod transaction is left out for the size of its answer
([fixture README](../../../../fixtures/reference/recorded-contexts/README.md)).

```sh
python3 scripts/oracle/plutus/recorded-contexts/run.py --verify-only
python3 scripts/oracle/plutus/recorded-contexts/run.py --out <raw answers>.json   # replay, needs the image
python3 scripts/oracle/plutus/recorded-contexts/run.py --record --out <raw answers>.json
# In the private-socket Docker JDK container:
bash scripts/sbtw 'ledger/testOnly *RecordedScriptContextsSuite'
```

The replay runs the observer offline and read-only and requires every retained answer, byte for
byte and in order. `--record` writes a new fixture (`contexts.json`, the measured fields of
`provenance.json`, `manifest.json`); a recorded file that changes, or a new one, needs it. Build the
image with `context-matrix/run.py --build` when it is missing.
