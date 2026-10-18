# Context collection reference oracle

This thin tool calls unchanged released `collectPlutusScriptsWithContext` and `EncCBOR` APIs.
It does not run UTXOW, CEK, consensus or a whole ledger transition. Actual signed transaction
submissions are retained separately in the [fixture corpus](../../../../fixtures/reference/context-errors/README.md).
Synthetic API probes explicitly make no ledger-admission claim.

Build the pinned native dependency base using [the Byron recipe](../../byron/full/README.md), then:

```sh
docker build -f scripts/oracle/plutus/context/Dockerfile -t scarab-plutus176-context-reference:local .
python3 scripts/oracle/plutus/context/prepare.py --out /tmp/context-api-inputs.json
python3 scripts/oracle/plutus/context/run.py --verify-only
python3 scripts/oracle/plutus/context/run.py --out /tmp/context-api-results.json
```

The image pins the CHaP/Hackage index states, ledger packages and complete Cabal freeze. It uses the
existing pinned native-crypto/Plutus base, GHC9.10.3 and Cabal3.14.1.1. Runtime replay uses a read-only
container with `--network none`; a nonzero reference exit or any changed result/byte/order fails the
comparison. The fresh-image replay reproduces all 145 results. The original official node uses
GHC9.6: source/package agreement is recorded explicitly, without claiming an identical executable.

To recapture the separate 24 signed cases, use a uniquely named isolated private devnet already
running Conway/protocol10, then run:

```sh
python3 scripts/oracle/plutus/context_failures.py --name context-reference --out /tmp/context-submissions.json
```

That driver reuses the existing `v1context` case generator and writes each partial result before
shared-chain confirmation, retaining the original first-node verdict and per-producer broadcasts.
It does not start a network or alter operational nodes. Finish with the normal named devnet `down`.
