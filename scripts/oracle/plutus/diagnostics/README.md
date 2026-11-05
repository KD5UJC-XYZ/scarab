# Native diagnostic printer observer

Build the accepted [context-matrix base](../context-matrix/README.md) first. This observer adds no
patch to the reference libraries. Its Docker recipe reuses that frozen Plutus1.70.0.0 dependency
build; `run.py` refuses a changed base before building, checks embedded source/pins before executing,
and binds replay to the immutable image and accepted executable. Runs have no network and a read-only
container filesystem. Temporary image receipts are removed automatically.

```sh
python3 scripts/oracle/plutus/diagnostics/run.py --build
# For an intentional, reviewed replacement of the finite native observations:
python3 scripts/oracle/plutus/diagnostics/run.py --build --record
# In the Docker JDK environment:
bash scripts/sbtw 'node/testOnly *PlutusContextTextSuite *ContextRefusalReasonSuite *ReferenceRefusalSuite'
```

The [fixture README](../../../../fixtures/reference/plutus-diagnostics/README.md) describes coverage,
limits and pinned reference definitions. `inputs.py` defines synthetic typed values; only the native
observer supplies expected text. Original node-captured refusal fixtures are never rewritten here.
