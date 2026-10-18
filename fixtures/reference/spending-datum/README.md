# Spending datums: reference admission and context arguments

Generated on 2026-10-18 by `scripts/oracle/plutus/context_transactions.py` against the official
reference node **11.1.3**, `cardano-cli` **11.2.3.0** (both git
`938cba990357ae7c4b7f95c8f75dd9d31174bbeb`, GHC9.6), on an isolated private Conway/protocol10
cluster. All keys were generated for this throwaway cluster. The fixture contains public
verification keys/signatures and original signed transaction bytes, never signing keys.

The 18 cases cover V1/V2/V3, a missing datum witness, a supplied datum witness, and an output with
no datum, each with both validity flags. The six missing-witness cases are all refused in phase1
with `MissingRequiredDatums`; neither flag consumes funding, the script input or collateral.
No-datum V1/V2 spends are refused with `UnspendableUTxONoDatumHash`. The three supplied-datum
controls and the V3 no-datum control enter blocks when flagged valid; the other flag is refused
with `ValidationTagMismatch`. Total: **4 included, 14 refused**.

Every refusal retains the actual raw `ApplyTxErr` from an independent LocalTxSubmission of the
identical signed bytes, alongside the original CLI result. Accepted controls are broadcast to all
three private producers with per-node results retained. The exact new output must remain present
across a shared tip and at least `k+1` further shared-chain blocks; setup has its own equivalent
confirmation. `spentAfter` records funding, script and collateral effects separately. Original
transactions are also split into their original body/witness byte spans for `PlutusCase` replay.

An earlier attempt stopped after eight recorded cases: an admitted V2 control was forged and then
orphaned by a same-slot VRF tie-break, leaving its inputs unspent. That attempt is retained privately
as failed evidence and is not this fixture. The fresh complete execution uses explicit identical-byte
broadcast and shared-chain confirmation; it changes no expected verdict or timeout.

Reference source at cardano-ledger `f649f975`:

- `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs:229-240`: absent datum witnesses produce
  `Nothing`, with required hashes checked separately by UTXOW.
- `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/TxInfo.hs:184-194`: `Nothing` selects two legacy
  arguments, `Just` selects three.
- `eras/conway/impl/src/Cardano/Ledger/Conway/TxInfo.hs:754-766`: V3 spending info carries the optional datum.

`SpendingDatumSuite` checks original-byte binding, argument arity/optional data, phase1, full LEDGER
and BBODY verdicts, and input/collateral effects. It additionally checks the language-independent
missing-hash predicate for every supported language/protocol combination5–11 using the measured
cases; the actual submissions in this fixture are protocol10. Direct phase2/context translation
is tested separately from whole-transaction admission.

Regenerate only on a uniquely named private cluster:

```sh
python3 scripts/devnet.py --name datum-reference up
python3 scripts/oracle/plutus/context_transactions.py --name datum-reference --out /tmp/spending-datum.json
python3 scripts/devnet.py --name datum-reference down
```

The JSON SHA-256 is recorded in `manifest.json`. The generator runs on the host using Python and
Docker; all Cardano executables run inside the reference container.
