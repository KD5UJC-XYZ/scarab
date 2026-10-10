# Finite eight-case likelihood oracle

This self-generated synthetic packet evaluates native Shelley PoolRank arithmetic for
sigma {1/3,1/6} × blocks {0,1,4,50}, f=1/20, d=0, and epoch size 1000. The fractions are
useful supplied test targets; no pool identifiers, original ledger snapshots or private
provider corpus are included. They do not authenticate a future go snapshot or freeze.

`NonMyopicDiagnostic.hs` accepts only byte-identical `cases.json` and calls the pinned
native `leaderProbability` and `likelihood` functions directly. It emits the binary64
probability and all 100 stored binary32 log weights. `Main.hs` is the unchanged existing
synthetic helper supplying fixed Globals; the cabal entry point is NonMyopicDiagnostic.
Native GHC9.6.7/-O0 build used the frozen 235-unit closure with no dependency rebuilds,
network disabled, 2 CPUs/2 GiB and a fresh private cache. Source, toolchain, package plan,
binary, exact commands, outputs and owned-container cleanup remain pinned in retained
private evidence. Dependencies and binaries are not bundled here.

Native result SHA-256:
`2774bb0bcb85ad4ac6187067e8eb7fc3d57cfd65ae1ebedbb74415541d76401a`.
Binary SHA-256:
`fe805eec4525b3ee8afd39025842f2f0b2d4dcc7355fcc666ac46ebf5dcd948a`.
The portable golden and provenance are in `ledger/src/test/resources/non-myopic-grid`.

`ConwayLikelihoodGridSuite` executes Java Math and StrictMath separately. Stage A injects
the native probability and compares all 100 final Float words. Stage B computes probability,
compares its raw Double word, and compares all 100 final Float words. All 32 cases passed:
3,200 exact binary32 comparisons and 16 binary64 comparisons, zero mismatches. Alongside
the prior replay and finite update regressions, 51 tests passed. Neither tolerance nor
normalization is used. Actual vectors and mismatch arrays are retained in a private
arithmetic result whose digest is in the portable provenance.

No numeric correction is supported by these passing cases. This evidence covers only
the stated finite inputs on the pinned execution environments. Other rationals, pow/log
rounding boundaries, Word64 subtraction/conversion and authenticated freeze provenance
remain unproved. No production likelihood generator or epoch guard changed.
