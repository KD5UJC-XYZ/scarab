# Native Byron parameter and minimum-fee boundaries

All 29 inputs in [inputs.jsonl](../../../scripts/oracle/byron-params/inputs.jsonl) are
executed unchanged by the [native observer](../../../scripts/oracle/byron-params/Main.hs).
They are constructed boundary inputs, not an imported upstream generator. The reference
is cardano-ledger pin `f649f9751074d2ab3de033fc3912f29c9862c1f5`; the exporter builds the
actual local Byron and binary packages from an untouched archive of that revision.

`native.jsonl` retains the original CBOR, label and every requested transaction size,
complete native decoded values or decoder errors, and every calculated fee. Inputs
cover all five Natural parameter fields (small, tagged bignum and negative), signed
Nano coefficients, ties-to-even rounding, Word64 conversion, maximum Lovelace and
modular multiplication. The native fee API accepts a `Natural` transaction size;
negative sizes are outside that reference type rather than discarded test cases.

The 29 rows include 21 successful decodes and all 66 requested fee results for decoded
policies. Rejected policies retain their native decoder errors. Lovelace failures retain
both the closed constructor/operands and diagnostic rendering. No native failure or
input is filtered, changed to success or replaced by a hand-translated algorithm.

Reproduce on an Euler/Rutherford compute host:

```sh
python3 scripts/oracle/byron-params/export.py \
  --source /path/to/pinned/cardano-ledger \
  --probe-dir /tmp/scarab/byron-params-native \
  --output-dir /tmp/scarab/byron-params-output \
  --container scarab-byron-params-native \
  --allow-download
```

The retained dependency image is `scarab-33-genesis-reference-cache:local`. The manifest
records the exact source, input, observer, exporter, executable, Cabal plan and output
hashes. This checks CBOR parameter/fee decoding and `calculateTxSizeLinear`; genesis JSON
has a distinct decoder and conversion rule and is not claimed by this corpus.
