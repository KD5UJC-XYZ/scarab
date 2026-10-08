> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# v0.19.1 bounded corpus test architecture

## Fresh v0.19 archive failure

The fresh v0.19 archive aggregate did not pass despite the earlier main-checkout
543-test pass. Two monolithic MUnit cases exceeded their existing 30-second test
budget: the 2,048-row original VRF corpus took 34.081 seconds, and the 17,336-row
Sum6 observation corpus took 50.888 seconds. The complete failed run is preserved
in `sum6-v019-fresh-archive-failure.log`, SHA-256
`d62cf1cddff6a7ab09f3fa1ce2e8bee193ffa382c3eca4982e8994bb26866ce6`.
Both runs used explicit APC4 sizing. The broader reason for timing variation
between checkouts is unproven; the earlier main-checkout pass does not substitute
for successful fresh-archive acceptance.

## Test-only correction

The patch partitions exhaustive cryptographic corpus work into deterministic,
contiguous finite batches. It does not alter production crypto, fixture bytes,
expected outcomes, MUnit timeouts, protocol deadlines or application behavior.
The version/documentation changes identify this test-only patch.

- Sum6: 68 tests, each at most 256 rows, covering all 17,336 observations.
  One independent coverage test checks exact flattened index equality, uniqueness,
  4 originals, 17,332 rejections, 4,334 rows per archive and 3,752 expected leaf calls.
  Every actual outcome, pair count and leaf count is still checked per row.
  Each batch also checks actual hash, leaf and verified totals against its pinned
  slice. Exhaustive disjoint coverage plus those equalities establishes the
  combined actual leaf count of 3,752 without order-dependent mutable counters.
  The four immutable original input byte projections are decoded once, then each
  synthetic mutation is independently reconstructed as before.
- VRF originals: 16 tests of at most 128 rows, preserving all 2,048 exact outputs
  and classifications: 3 valid, 2,018 invalid and 27 malformed.
- VRF additional observations: 19 tests of at most 128 rows, preserving all 2,338
  rows, including the 2,021 intentional repeated rechecks: 3 valid and 2,335 invalid.
  Both VRF corpora have independent coverage tests checking equal input/expectation
  counts, complete contiguous index partitions, unique row identifiers and exact
  classification counts. Indexed access replaces zip-based iteration, so a short
  expected file cannot silently truncate verification. Each batch compares every
  full expected output and actual class count. Existing helper corpora and all
  non-corpus controls remain unchanged.

The expected aggregate count increases from 543 to 646 because three monolithic
tests become 103 additional batch/coverage test registrations. This is a change
in test granularity, not 103 new independent cryptographic vectors.

## Acceptance profile

Build and serial CLI/projector/runtime gates use
`JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4` plus the existing sbt launcher flag.
No timeout guard is enlarged. Fresh release-archive verification remains a
separate required gate; deterministic batching bounds individual test work but
does not promise a universal wall-clock runtime on every host.

## Executed main-checkout gates

The formatted patch passed **129/129 focused tests** and the full **646/646 Scala
aggregate**, including unchanged non-corpus cases. `scalafmtCheckAll` and runtime
classpath generation passed. Logs are `corpus-batch-focused-verification.log`
and `corpus-batch-build-verification.log`. The largest Sum6 batch in this aggregate
was 1.913 seconds; the largest original VRF batch was 4.487 seconds. These are
observations from this run, not performance guarantees or a cure claim for host
resource variation. The previous failed fresh-archive run remains failed evidence.

All **23 serial CLI/projector/runtime scripts** also passed under the same APC4
profile, with their existing per-case guards unchanged. The complete ordered
record is `corpus-batch-regression-verification.log`. This retains all prior
controlled local transport checks, native-free runtime inventory and five
negative dependency checks, as well as Sum6 immutable projection, 21 negative
admission cases and seven direct-JVM CLI cases. Prior per-command evidence files
were restored unchanged; this patch retains its own aggregate rerun record.
Independent coverage/source/evidence review found no blocking issue.

## Fresh v0.19.1 archive acceptance

The independently extracted frozen v0.19.1 archive subsequently passed all
**646/646 aggregate tests**, formatting, runtime classpath generation and all
**23 serial CLI/projector/runtime scripts**, exit0, under the same APC4 profile.
The complete log is `corpus-batch-fresh-archive-verification.log`, SHA-256
`ec1228936e2bab061d24af1701564508621106fd041f3f2fd9e07fe30c6c599a`.
No deadline or expected output was changed. This closes that version's fresh
packaging gate and does not erase the original v0.19 timeout evidence. It is
separate from, and cannot substitute for, v0.20 archive acceptance.
