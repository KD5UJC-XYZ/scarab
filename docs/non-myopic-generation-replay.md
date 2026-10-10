# Exact finite JVM likelihood arithmetic replay

The test-only `ConwayLikelihoodReplaySuite` executes real JVM arithmetic against the
recorded native `generationProbes` in `ledger/src/test/resources/non-myopic/native-result.json`.
The complete golden SHA-256 is
`e336935edca7af26f79c4497074e16ad72d3bcf92075adcb372f92950efce214`.
No new native execution or runtime admission is involved.

For f=1/20, relative stake=1/13, decentralization=0, blocks=1 and epoch size=500,
stage A injects native probability binary64 `3f702126612e5f00`. It computes the native
ordered expression `n*log(x) + m*log(1-t*x)` at all 100 positions `(i+0.5)/100`, with
n=1 and m=499, and converts each final Double once to Float. Stage B calculates
`t=(1-pow(1-f,sigma))*(1-d)` independently, compares its raw binary64 word, and
repeats all 100 raw binary32 comparisons. Java Math and StrictMath are tested separately.
Zero-block/sigma1/13 and zero-block/zero-stake cases are separate controls from the same
hash-pinned native golden; signed zero is compared by raw bits.

Actual execution on the pinned Java21 offline image passed all six new tests: both
probability calculations matched, all eight 100-word comparisons matched (800 exact
binary32 comparisons), and zero-stake probabilities were positive zero. The one-block
expected vector starts `c0a9dc4e,c0875573` and ends `bffa29aa,bffb6514`; SHA-256 of all 100
concatenated lowercase hex words is
`770759d0737843bd745e884cb5295250431c4aa5d0a1628b9b3ef7b7e0f858d1`.
The complete invocation, source pins, actual raw words, mismatch rows and log are retained
privately. Existing finite non-myopic regression suites ran alongside the new comparisons.

This is finite arithmetic evidence, not a universal or authenticated frozen-state generator.
No mismatch arose in these probes, so no numeric correction is justified. Other rational
inputs, binary64 pow/log results and Float rounding boundaries remain unproved. A binary64
difference may disappear after Float rounding; matching raw Float vectors alone cannot
establish primitive equality. The chosen positive case exercises both logarithms and a
nontrivial power, while the zero cases cover only their narrower paths. Validity of Word64
subtraction/Natural conversion outside these small inputs is also unproved. Production
`ConwayNonMyopic.generateForFrozen` still refuses every nonempty go pool domain, including
zero-stake pools. Neither tolerances nor normalized likelihood equivalence are used.

Native equations and source hashes remain in
`reference/non-myopic-diagnostic/source-pins.json`: Shelley 1.19.0.1 PoolRank.hs
`62f66ab3449f8e4bc9b2b6d5e6b8ef0aeeef23fb0a74ceae451034ec82d0a323`, lines126–172.
The existing helper/provenance records establish which finite native observations were run;
they do not authenticate chain state or enable longer multi-epoch service operation.
