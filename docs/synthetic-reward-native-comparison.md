# Actual native synthetic reward comparison

Result: the unchanged Scala harness at commit
`54f310d72434328708408953a15c61873172d176` exactly matched the supplied native
result from corrected API-only source commit
`3c8a83dd7ad92e91135adef91cb7bfe25d0f81d0` for this finite synthetic matrix.
The comparison command exited 0; there were no mismatch paths or values.
No implementation or expectation was changed for this comparison.

## Evidence identity

| Artifact | SHA256 |
|---|---|
| Exact canonical case input | `14d229647bcf67312d5979b4632d137c23177ac3dcf20356963b6cf193e97fb9` |
| Native result, 38,557 bytes | `a1eb9842d864eb318723ae29792ba9aaf4d31410ae9f420ac51529c5507ddb74` |
| Scala result | `a69664a16302f2113eac02eb305ac9294401fd3b3f85f1a17b436f5a81c0acce` |
| Native executable (adjacent attempt report) | `f5955e17c89e95a8f41a451f4d771b1dd4a767ee7eff35c1d8e86b91e6425abf` |

The exact self-generated native result is retained publicly as
[`native-result.json`](../app/src/test/resources/synthetic-reward/native-result.json),
with a [portable provenance manifest](../app/src/test/resources/synthetic-reward/native-provenance.json).
The original private `attempt-report.json` identifies the source commit, executable,
image, input and output hashes. This comparison independently verified the input
and native-output hashes and native file size before execution, and rechecked
both hashes afterward. It did not rebuild or rerun the native executable.

The native result was mounted as one read-only file at
`/evidence/native-result.json`. Docker used the existing local Scala image,
network none, at most 2 CPUs/2 GiB with no additional swap, and the owned worktree's
private build/cache directory. No new dependency, native compilation, live node,
captured/blocked fixture, key, public fetch or runtime publication was involved.

Command run on the Test classpath:

```text
app/Test/runMain lab.SyntheticRewardDifferentialMain app/src/test/resources/synthetic-reward/cases-proposed.json 14d229647bcf67312d5979b4632d137c23177ac3dcf20356963b6cf193e97fb9 .cache/scala-native-comparison.json native /evidence/native-result.json
```

Private comparison artifacts under the Scala worktree:

- `.cache/native-reward-comparison.log`: exact command outcome.
- `.cache/native-reward-comparison-report.json`: command, hashes, per-case counts,
  exit code and empty first-mismatch list.
- `.cache/scala-native-comparison.json`: actual Scala value projection.

## Compared scope

There are 11 cases, 11 independent initial probes, 33 recorded action steps and one
registration application. Initial probes include 20 producing-pool projections
(two for each nonempty case; none for the empty case). Initial probes are separate
from the phase counts in the following table.

| Case | Steps | Absent | Pulsing | Complete | Application |
|---|---:|---:|---:|---:|---|
| base | 6 | 0 | 4 | 2 | no |
| empty | 2 | 0 | 1 | 1 | no |
| partial-force | 3 | 0 | 2 | 1 | no |
| initial-force | 2 | 0 | 1 | 1 | no |
| late-start | 1 | 0 | 0 | 1 | no |
| timing-synthetic100 | 5 | 2 | 2 | 1 | no |
| timing-derived80 | 5 | 2 | 2 | 1 | no |
| tiny100 | 2 | 0 | 1 | 1 | no |
| tiny10 | 2 | 0 | 1 | 1 | no |
| uncapped | 2 | 0 | 1 | 1 | no |
| registration | 3 | 0 | 2 | 1 | yes |
| Total | 33 | 4 | 17 | 12 | 1 |

Exact compared values: chunk size; frozen fees, reserve contribution, treasury
allocation, available reward pot and circulation; pool relative stake, pot,
blocks, leader reward and reward-relevant snapshot; ordered remaining credential/
pool/stake records; accumulated member records; final typed reward sets and
signed deltas; application registered/unregistered sets, credits, unregistered
total, account balances and pots. The comparator also recomputed monetary and
application conservation. JSON object order was ignored; declared array order,
fields, decimal-string precision and input identity were exact. Internal ledger
identity hashes were not compared.

Native timing cases invoke exported RUPD via applySTS from SNothing independently
at each signal. Window 100 remains explicitly synthetic, distinct from derived
4k/f=80. Low-level direct pulse/force cases do not independently establish timing.

## Limits

This is actual native/Scala value agreement for the specified finite profile:
five credentials, two pools, rho=tau=a0=0 and nOpt=k=1, with the declared fee,
block-count, empty-state, timing and registration variations. It does not prove
general reward arithmetic parity, event/non-myopic equality, native go provenance,
valid chain history, full RUPD/NEWEPOCH validation or runtime safety. No runtime
guard or authority changed. The native producer label is supported here by the
separately retained attempt evidence, not treated as authentication by itself.


## Reusable public regression

The golden preserves all 38,557 original bytes and the native hash above. It is
self-generated from the declared repeated-hash synthetic profile; it contains no
private keys, captured-chain data or provider corpus. The default public Scala
suite pins its exact input/result hashes and compares all declared fields using
the existing comparator. Hand-derived `synthetic-expectation` data remains separate.
The [curated native source packet](../reference/synthetic-reward-diff/README.md)
contains the unchanged executed Haskell source and licensed package metadata.
Machine-specific build plans, caches, launchers, logs and binaries remain private.
This regression replays recorded output; it does not rerun native code in CI.
