# Pinned Plutus boundary oracle

This optional tool runs `deserialiseScript` followed by `evaluateScriptRestricting` in the exact
plutus-core/plutus-ledger-api1.70.0.0 source used by reference node11.1.3. It accepts JSON lines from
`PlutusBoundaryCorpus` and returns decoder admission plus the typed evaluation failure stage or
success. `--hash-vectors` independently exports compressed G1/G2 points for binary DST vectors.
It links the complete crypto backend. A `-with-crypto` build is not valid oracle evidence.

Pins:

- Plutus `507cab8cf6192e5143f12be5b2d3005a2928dc6d` (tag1.70.0.0).
- `plutus-core.cabal` SHA-256 `83f272966c2352f6e6a5aec76ffc3383cff49b99454b8b0934961e833843455f`.
- GHC9.10.3 image `haskell@sha256:4f1f62fec9cea4cbb03e885c9ad45642609cadd3897b1e64a7f61d0862b575b1`.
- blst0.3.15 `6d960cd05d6fe2b5bc9ba161edf0c1a131b87c4c`, `-D__BLST_PORTABLE__`.
- Upstream Hackage index2026-06-22T23:30:49Z and CHaP2026-06-18T17:45:00Z, including its repository keys.
- Resolved `cardano-crypto-class2.5.0.0`; Debian bookworm libsodium1.0.18 and libsecp256k1 0.2.0.
- Tool compiled with `-Wall -Werror -O0`; API/core O0, tests and benchmarks disabled.

Build and run from the repository root (Docker only):

```sh
docker build -f scripts/oracle/plutus/Dockerfile -t scarab-plutus-oracle:local .
```

In the repository's own JDK container, export requests to a mounted directory:

```sh
./scripts/sbtw 'plutus/Test/runMain xyz.kd5ujc.scarab.plutus.PlutusBoundaryCorpus /oracle/requests.jsonl'
```

Then pipe the exported requests into the optional Haskell image:

```sh
docker run --rm -i scarab-plutus-oracle:local < /tmp/scarab-plutus/requests.jsonl > /tmp/scarab-plutus/verdicts.jsonl
docker run --rm scarab-plutus-oracle:local --hash-vectors > /tmp/scarab-plutus/hash-vectors.jsonl
```

The recorded 2026-10-18 run built these pinned sources in an isolated container with the same
packages and project configuration, then ran the retained source. All477 rows ran with real crypto;
the earlier no-crypto exploratory output is excluded. No public-network follower is needed.
The fresh Docker image also built successfully and independently reproduced all477 rows. Its image manifest is e061568cd4971962854e85f26910eb5094ff1c416567a547feb3ba6eff59c5da; ordinary `check` replays the retained golden verdicts
and original script hashes without Haskell or network access.

The API oracle measures script admission/evaluation, not complete transaction rules. Its budgets
are14,000,000 memory units and10,000,000,000 CPU steps. Cost models are the pinned A–E JSON models,
selected and ordered by language and protocol in `PlutusBoundaryCorpus`. Failures are classified
by constructors; diagnostic text is not control flow. Golden rows omit unstable decoder addresses
and bind the actual script bytes and canonical JSON parameter lists by SHA-256.
