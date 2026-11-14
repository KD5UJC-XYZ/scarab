# Native diagnostic presentation observer (#796)

This observer calls the unchanged Plutus 1.70 APIs and Pretty/Show instances at the
[reference pin](../../../../docs/data/reference-pins.json). It adds input decoding and lossless
observation only; it does not patch the evaluator, printers or prettyprinter. The base is the
[source-verified context matrix](../context-matrix/README.md), including its frozen Cabal plan.

```sh
python3 scripts/oracle/plutus/error-presentation/run.py --verify-only
python3 scripts/oracle/plutus/error-presentation/run.py --build
python3 scripts/oracle/plutus/error-presentation/unicode.py --record
python3 -m unittest discover -s scripts/tests -p test_error_presentation_oracle.py
```

`--build` refuses another base identity and executes the freshly resolved immutable image. Every
replay checks embedded observer/Cabal source, the accepted executable digest, and complete input
and output identities. `--record` is an explicit fixture update: two independent native processes
must emit identical complete output bytes before anything is accepted. There are no provider
message parsers, output masks, tolerances or expected-failure lists.

Inputs exercise every native EvaluationError family, its nested error constructors, native
universe constructors, constants, all native builtin names, UPLC term constructors, name/index
bounds, complete error causes and document layout choices. `Evaluate` actually constructs native
costing contexts and executes the compiled program at every historical legal language/protocol
pair represented in the costing fixtures. The two model scales are API controls; they are not
claims about on-chain protocol parameters. `HelperError` calls exported native helpers and records
both their native Text result and typed operands as `diagnosticInput`; this tests typed rendering
without deriving subjects by parsing provider prose. `Compile` binds the original serialized
scripts supplied to the separate signed recording work.

The API compiler is GHC9.10.3; the official node uses GHC9.6.7. `UnicodeProbe.hs` compares every
Unicode code point against the official compiler's released category table and unchanged lookup
logic. The sole compatibility adaptation removes a SOURCE boot-import pragma in a disposable
copy of the Bits helper. Original sources are hash-checked in a shared download cache. The complete
classifications agree; no JDK Unicode classification substitutes for this proof.

The [fixture documentation](../../../../fixtures/reference/plutus-error-presentation/README.md)
distinguishes native API evidence from official-node transaction admission and records the Flat
pointer-error repeatability boundary. The existing context observer also includes complementing
optional fields and empty collections; its original inputs remain represented.
