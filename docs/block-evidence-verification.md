> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# v0.21 block evidence verification

Verified in the main checkout on 2026-10-08 UTC. **Fresh archive acceptance is still a separate pending gate.** No timeout, expected cryptographic result, old runtime guard or acquisition identity was relaxed.

## Final source results

- Formatting and final aggregate: **1,099 tests passed**, exit 0. This includes the existing 1,042-test baseline and 57 new tests.
- Focused core/index/body and app checks: **480 passed**, recorded separately before the final envelope-diagnostic refinement.
- Final direct JVM block-evidence CLI: **52 cases passed**, including 15 real originals, 24 synthetic cases, ownership/input/symlink bounds, missing nonce, the conditional KES rejection and a wire-wrapper Unsupported result.
- Final original body-commitment CLI: **25 cases passed** with its unchanged 30-second per-process guard.
- Independent packet projection, source provenance, exact test-resource copies and optimized-Python/argument guards passed.
- Runtime inventory: **26 jars**, no excluded native artifacts or bundled native libraries. All **five negative runtime-admission checks** passed; no runtime dependency was added.
- Independent read-only source/evidence review passed, including the final diagnostic refinement. This review is distinct from JVM or reference-runtime execution.

Commands are retained in `block-evidence-final-command.sh`; final aggregate and suffix logs are `block-evidence-final-build-verification.log` and `block-evidence-final-suffix-verification.log`. Runs used JDK21 and explicit per-process `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4`. Sbt's displayed local times use UTC-05:00; orchestration/evidence dates here are UTC.

Final reviewed implementation digests:

- `CardanoBlockEvidence.scala`: `b34e35b37e3f983821efb9b5e94708c2a68eb905ef06adc311c853a8a59ba4c3`
- `BlockEvidenceCommand.scala`: `05f76d0c66e7f66dc7b91ead4ba4845cd1643e7de39ed6e6543bd5b7f58ce5e9`
- `CardanoBlockIndex.scala`: `f132fa06b999b59e2cefffc7261d93a1991f443ebef4f921abb66a7653f29339`

## Serial attempt, preserved failure and final-source coverage

The planned serial list contains 27 scripts, retained in `block-evidence-regression-command.sh`. The first attempt completed gates 1–24, then the unchanged `verify-body-commitment-cli.py` timed out at the oversized-file preflight case after its existing 30-second guard. Gates 26–27 had not run. The failure is preserved in `block-evidence-regression-attempt1-failure.log`; it is not relabelled an uninterrupted pass.

That attempt ran the prior compiled source snapshot. Its source digests and exact index source are retained in `block-evidence-initial-snapshot.json` and `block-evidence-initial-index-source.scala.txt`. An initial aggregate of 1,099 passed against that snapshot in `block-evidence-build-verification.log`.

The only subsequent production refinement is a conservative **evidence-profile-only diagnostic**: a non-array second envelope item such as `[4, tag24(bytes)]` is Unsupported rather than Malformed. Existing public index/body paths and all admitted inputs are unchanged. The final aggregate was rerun after this change. Final gates 25–27 passed, followed by repeated runtime inventory and all five negative runtime checks. The prior 24 script passes cover unchanged production paths; the final aggregate and relevant CLI gates cover the refined source. This is coverage across clearly distinguished runs, **not a claim that all 27 scripts passed in one uninterrupted final-source invocation**. The parent fresh-archive gate must establish its own complete result.

Historical per-command logs were restored unchanged; this milestone's rerun outputs are preserved under `block-evidence-rerun-artifacts/`. The initial focused test-harness correction is separately recorded in `block-evidence-focused-initial-note.md`; it did not change production fatal-error behavior or expected predicate outcomes.

## Bounded timeout diagnosis

One unchanged reproduction used the same 1,048,577-byte file, explicit APC4, the original 30-second limit and a SIGQUIT trigger at 20 seconds if still running. It rejected correctly with exit 2 in **3.9237 seconds**, before the dump trigger. Captured result/script are `block-evidence-oversize-diagnostic.json` and `.py`.

Source routing rejects this case in the regular-file size preflight, before any index, body, header or cryptographic predicate. The original failed child had no retained partial streams/thread state, so its actual location and cause remain **unproven**. A later successful retry does not prove the failed execution harmless or fixed.

Separate passive host samples from 10:40:06.909–10:40:18.918 UTC observed 4,672 steal ticks of 11,325 exposed CPU ticks, **41.25%** aggregate (intervals 37.41–45.94%). `block-evidence-host-samples.json` and `block-evidence-host-summary.json` preserve the measurement. This is credible current scheduling contention, **not causal evidence for the earlier Java timeout**. No new timing harness, CPU setting change or enlarged guard was introduced on that basis.

## Evidence scope

Fifteen original full blocks match body commitments and OpCert signatures. Thirteen source-labelled blocks plus one network-unestablished conditional case pass source-derived candidate-message Sum6 under supplied timing; the other network-unestablished case rejects under the unchanged 129600/62 assumption. No period was adjusted to manufacture a pass. All original positives omit nonce explicitly. There is **no genuine integrated full-block VRF positive**; a separately recorded native all-zero supplied-nonce negative rejects.

The source-candidate profile remains finite/source-derived research evidence. General Haskell encoder/decoder parity, a resolved cborg/Serialise build plan, an independent full Haskell KES oracle, authorized issuer/registration, counter state, derived nonce, leader eligibility, ledger transition and chain selection remain absent. No signing, keys, new public network request or production operation occurred in implementation/verification. Source retrieval by the separate source-profile research is recorded independently in its retained manifests.
