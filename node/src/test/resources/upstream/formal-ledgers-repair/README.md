# Formal LEDGERS foreign-input conversion repair

Status: executed bounded repair under [#270](https://github.com/KD5UJC-XYZ/scarab/issues/270).
This is a local patch to the pinned formal executable specification, not a patch
to the running native Haskell node or to Scala ledger behavior.

Native source is `cardano-ledger f649f9751074d2ab3de033fc3912f29c9862c1f5`;
formal artifact source is `formal-ledger-specifications b3a4fd47a1144ff1fb994f89b1045e78159e07ae`.
The original singleton payment observation exits 139 during formal evaluation,
after capturing native acceptance. The original files and failure remain in
[the refund corpus](../conway-native-refund/README.md). A debugger observation
locates the failure inside generated formal list processing; its log hash is
retained in this manifest, with raw evidence outside the checkout.

## Cause and executed repair

`hs/src/MAlonzo/Code/Ledger/Conway/Foreign/Ledger.hs:4724–4779` coerces the foreign
environment, state and transaction list directly into internal Agda representations.
The adjacent single-transaction wrapper at lines 4664–4707 instead uses the
existing `Convertible` instances. These records are not interchangeable memory
representations. The [repair patch](../../../../../../scripts/oracle/ledger-govcert/formal-ledgers-conversion.patch)
uses those same environment/state/transaction conversions before invoking the
unchanged computational LEDGERS rule. Its existing output conversion is unchanged.
No native transition substitutes for the formal computation.

The unchanged singleton now accepts natively and formally, and the complete
translated outputs compare equal. The separate
[batch observer](../../../../../../scripts/oracle/ledger-govcert/formal-ledgers-batches.patch)
also runs the formal rule on the original signed batches from
[the native batch corpus](../conway-native-ledgers/README.md). Ordered-three and
dependent-two both accept and compare equal. Reversed-dependency and double-spend
both reject; **their error text differs**. The upstream `checkConformance` accepts
two rejected outcomes without comparing their text, so its successful invocation
is not a predicate-failure-payload equality claim. The explicit `outputs-differ`
observations remain here. Scala's typed native-failure comparisons remain separate.

All original input/native-output CBOR files are byte-identical, checked by the
manifest and `UpstreamNativeLedgerConformanceSuite`. Equality with the formal
result means equality after the existing native-to-formal translation and
normalization, not byte equality between two different encodings.

## Reproduction and provenance

Use disposable checkouts at the pins above, the original pinned dependency plan
and GHC 9.10.3. Apply the conversion patch to the **formal checkout**. Apply
`exporter.patch` and `native-hooks.patch` to the **native checkout**, then either
`derived-payment.patch` for the singleton, or `multi-ledgers.patch` followed by
`formal-ledgers-batches.patch` for the four batches. Patches live in
`scripts/oracle/ledger-govcert/`. Commit the formal patch in its disposable clone,
then point the native project's formal `source-repository-package` location to
that clone and change its tag to the **new local patched commit SHA**. Merely
pointing its location to a dirty checkout while retaining tag `b3a4fd47` builds
the original code, not the repair. Alternatively remove that formal
`source-repository-package` stanza and register the patched checkout's `hs`
directory as a local Cabal package. Keep constrained-generators and the other
recorded dependencies unchanged.

Build `cardano-ledger-conformance:tests` in the pinned Haskell Docker environment.
Execute its binary with these arguments:

```text
--match Conformance/Conway/Imp/EPOCH/Proposals/Expired proposal deposit refunded
--seed=498301 --qc-max-success=1 --qc-max-size=30
```

Set `SCARAB_IMP_EXPORT_DIR` to an existing writable evidence directory. For the
batch observer, additionally set `SCARAB_BATCH_INPUT_DIR` to the original refund
corpus's `cases` directory and `SCARAB_BATCH_EXPORT_DIR` to a fresh writable
directory. Override the Docker image's oracle entrypoint with the test binary;
the base image's default entrypoint expects a different JSON API.

The observed optimization rebuilt only the changed formal wrapper with
`-O1 -dynamic-too`, using its original package unit ID and cached interfaces,
replaced that object in a **private** package archive, then relinked the native
conformance runner. The unit ID alone therefore does not identify its contents:
the manifest binds the original/patched wrapper, object, original/patched archive,
compiler flags, dependency plan and linked executables. A regular source build of
the patched formal package is the alternative to this cache-dependent optimization.
No original package cache was overwritten.

The singleton and batch invocations both exit zero. The root independently replays
the batch executable in a separate network-disabled Docker container with frozen
source/executable bindings. An initial invocation used the wrong image entrypoint
and exited before executing a test; it is not a semantic result. These are bounded
observations, not all-era or all-formal-rule completeness. Original sources and
patches are Apache-2.0 under the existing upstream notices.
