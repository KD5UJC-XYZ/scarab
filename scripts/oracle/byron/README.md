# Byron protocol-version reference boundaries

**Status: Historical** — executed offline on 2026-10-18 for REF-07 (#171).

This optional tool reproduces two distinct reference results. It requires the installed pinned
[reference image](../../../docs/reference-image.md) and `haskell:9.10.3-slim-bookworm`; every
execution uses a throwaway Docker container with `--network none`. No node or socket is used.

```sh
python3 scripts/oracle/byron/run.py --output /tmp/scarab-byron-reference.json
```

The first result is actual complete-block CBOR decoding by CLI 11.2.3.0, bundled in node 11.1.3.
The CLI's `--byron-block` argument uses the derived Haskell `Read` syntax
`EpochSlots {unEpochSlots = 21600}`; a bare `21600` fails option parsing. Nine retained public
blocks check the original `(0,0,0)`, each component's legal maximum, the combined maximum, and
four out-of-range values. Five decode; four fail with `expected word16` or `expected word8`.
The complete bytes, exact errors and binary/image provenance are in
[the header manifest](../../../fixtures/byron-version/headers/manifest.json).
This command decodes blocks; it does not verify their signatures or apply ledger rules.

The second result executes the **unchanged predicate excerpt** `pvCanFollow` from pinned ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`,
`eras/byron/ledger/impl/src/Cardano/Chain/Update/Validation/Registration.hs:489–497`.
`Main.hs` also retains `ProtocolVersion.hs:27–33` unchanged: the actual strict
`Word16`/`Word16`/`Word8` fields and derived `Eq`, `Generic`, `Ord`. Only unrelated `NFData` and
`NoThunks` derivation is omitted. `extraction.json` binds each excerpt to its original line range
and SHA-256; the driver verifies the retained excerpts before compiling with GHC 9.10.3,
`-Wall -Werror`. Input/output glue uses base only.

The eleven [recorded transitions](../../../fixtures/byron-version/registration.json) yield six
true and five false results: next major/minor at the bounds, ignored alt changes, invalid alt-only
increase, major jumps, a nonzero minor with next major, and Word16 wrap boundaries. These are
executed predicate results, **not execution of the full reference registration machinery**.
`ByronVersionSuite` consumes these verdicts through signed Scala block/transaction/proposal
validation. It does not turn them into a claim about reference signature verification or other
registration preconditions. The later [full registration oracle](full/README.md) executes unchanged released registration,
proposal/transaction admission and complete-block ledger rules composed with authentic header crypto.
It supplies the broader #171 evidence separately from these historical excerpt results.

The Haskell excerpts are Apache-2.0, from Intersect's cardano-ledger. The original notices and
licence are retained in [fixtures/licenses](../../../fixtures/licenses); see the repository
`NOTICE`. No implementation is adopted into production. The image IDs, GHC version, excerpt
hashes and source-file hash used for the recorded run are retained beside the verdicts.
