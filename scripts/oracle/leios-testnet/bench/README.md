# Leios closure benchmark: the reference side

**Status: Current.** An optional, hand-run harness for #1380. It times the reference ledger's
part of the Leios voting thread's closure check on the same input `ClosureBench` (in `tools`) checks,
so the two implementations are compared on one host and one input.

`ClosureBench --export <dir> ...` writes the input it checks:

| File | Contents |
|---|---|
| `nes.cbor` | the new-epoch state, as the Haskell node writes it (`DebugNewEpochState`'s codec, UTxO `map(0)`) |
| `utxo.cbor` | the outputs the closure spends from before it: a map of `[txid, index]` to each output's ledger bytes |
| `closure.cbor` | an array of the closure's transactions in block form |
| `cached.json` | the positions the transaction cache holds |
| `slot.txt` | the slot the closure is checked at |

The harness ticks the state to the slot (`applyTickNoEvents`), then takes each transaction in order:
- at a cached position, it reapplies it from the token `assumeValidatedClosureTx` makes
  (`unsafeMakeValidatedTx` on the state the transaction meets, then `reapplyValidatedTx`);
- otherwise it applies it in full (`applyTx`).

Those are the ledger calls of `validateEbClosure` (ouroboros-consensus d91b3557, `LeiosVoting.hs:467-560`).
What consensus adds around them is not run: its ledger tables (each transaction meets the
closure's outputs restricted to its own inputs, and its diff is folded back in), the measure taken of
each transaction applied in full (`txMeasurePhase1`, `txMeasurePhase2`), and the cache's
bookkeeping. The comparison is of the validation calls alone, on a state whose UTxO holds only the
outputs the closure spends from, so the plumbing over a small table costs little. `cached.json`
lists every position whose transaction hash the cache holds, as both sides find cache hits by hash. The final state is forced inside the timed span, and each run
prints one JSON line (`closureBenchReference`).

`Main.hs` builds with `-fno-full-laziness`, so that every run computes its check: without it, GHC
shares the first run's result with the later ones.

The image starts from the Leios oracle image (`scarab-leios-testnet:local`, [the oracle](../README.md))
for its GHC (9.10.3; the release builds with 9.6) and its cabal store. `cabal.project` carries the
oracle's pins. Build and run from the checkout:

```sh
docker build -f scripts/oracle/leios-testnet/bench/Dockerfile -t scarab-leios-closure-bench:local .
docker run --rm -v <bench data>:/bench:ro scarab-leios-closure-bench:local \
  /bench/config/shelley-genesis.json /bench/<export dir> 4
```

The run data, the snapshot and the transactions, are not published.
