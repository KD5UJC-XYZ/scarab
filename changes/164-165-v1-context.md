### Fixed (Plutus V1 script context, #164, #165)
- **The Plutus V1 context follows the reference per era.** From Conway the reference translates every reference input as it
  translates an input, although the V1 context does not show them, and leaves the reference-script field of an output
  out of the V1 form instead of refusing it; Babbage keeps its refusals. The node now does the same (`ScriptContexts`),
  at every Conway protocol version, so a transaction is accepted or refused at script-context construction as by the
  reference.
- **Every script's context is built before any script runs** (`Phase2.evaluate`), as the reference collects all of
  them first: a transaction whose context cannot be built is refused whatever the validity flag and the outcome of an
  earlier script say. The order in which the translation looks at the fields (validity range, inputs, reference inputs,
  outputs) is the reference's.

### Added (reference-backed evidence for the V1 context, #164, #165)
- **`devnet.py v1-context`** submits to the reference cluster, per era and protocol version, transactions that run a
  Plutus V1 script (and V2 and V3 as controls) over the cases that differ by era, with the validity flag both ways, and
  records each verdict. The records (`fixtures/reference/v1-context`) are replayed by `V1ContextSuite` through phase 2, the
  transaction rule and a block; `ScriptContextV1EraSuite` states the per-era rules at every protocol version.
