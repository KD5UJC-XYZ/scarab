# Independent epoch-zero restart/rejoin check

`scripts/private_cluster_plutus_early_restart_check.py` is a separate opt-in driver requiring `--restart-profile early-restart-only-v1`. Without `--execute` it performs preflight only. It uses the existing support manifest, pinned Scala image/build/classpaths, projection and private owned/evidence roots supplied to the other local controllers.

The driver preserves the existing pre-funding and epoch-zero bootstrap gates. It permits only the existing disposable reference fixture funding transaction before Scala bootstrap. It does not build or submit tested spending transactions, start an HTTP client or invoke an endpoint comparator.

The first default-mode service exports a checkpoint after one checked publication. The reviewed `perform_early_restart` adapter requires a clean stop, independently acquires the exact checkpoint full point, checks the original binary/request pair against controller-selected source/store/session/generation, and publishes a separate acceptance record. Only then does it remove the first owned process and create the replacement with read-only checkpoint/authority inputs. Scala performs semantic replay recovery. The replacement readiness must show a fresh coherent owner and checkpoint identity and an actual empty volatile pool.

The replacement uses the unchanged default epoch-zero mode, with a maximum of one new checked publication. `wait_checked_successor` observes the next full point and fresh-owner pin. The driver then requires the final result and clean process exit, matching transport open/close counts, no evaluation receipts or included transaction IDs, exact raw publication and terminal-observation hashes, and a final slot below 1000. A fast successful exit is handled by inspecting receipt files before requiring a running process. A timeout, epoch crossing, missing original or resource failure cannot become success.

Original checkpoint, request, controller authority, acquisition result, restored readiness, successor publication and finalized result remain in private evidence. `plutus-early-restart-controller-result-v1` reports only early restart/rejoin, fresh owner, observed empty pool and checked successor. It explicitly denies whole-state oracle comparison, transaction-inclusion, multi-epoch, full-ledger, late-restore and crash-durability claims. The observed exact acquisition point supports the controller decision; it is not an endpoint parity certificate.

This driver is independent of the blocked repeated-state comparator and does not implement or substitute for it. Existing same-epoch spend comparators require submitted transaction originals and therefore are not applicable to this no-spend check. The disabled 600-second supervisor remains unchanged.

The existing immutable container ownership checks, disk watchdog, consumer-before-reference cleanup and four-CPU/seven-GiB upper budget are retained. The operation has a 240-second outer bound and 30-second cleanup allowance; only one of the two service slots is live at a time. Worker tests are offline mocks. The main-owned execution below supplies separately retained live evidence.


## Retained local acceptance - 2026-10-10

Exact source `5db2ca87dd3224d805dfcf3d87c4495208f49015` completed the isolated
restart check in 70.628 seconds overall, including preparation and cleanup.
The service intervals were 1.070 and 0.583 seconds; this was a bounded restart
check, not the planned 600-second active soak. It bootstrapped at slot 188/block 1, exported
one checked original at slot 360/block 2, and restored under a fresh owner with
an observed empty volatile pool. The replacement checked the next block at
slot 362/block 3. Both services finalized their resources and balanced all
transport opens/closes; owned-container absence was verified.

The [curated receipt](../reference/plutus-admission/early-restart-live-receipt.json)
records full points, counts, source revision and hashes of private originals.
Raw checkpoint bytes, keys, cluster state and logs remain outside Git. The
checkpoint-point acquisition supports the restart decision; this run performs
no whole-state oracle comparison or tested-spend submission. It establishes
neither multi-epoch operation, late restore nor crash durability.

The first attempt was preserved as a failure before restart authorization.
Its service completed successfully, but a receipt could appear while Docker
inspection was in flight; the controller then incorrectly rejected the stopped
process. The tested fix rechecks final evidence after observing exit. A later
matching successor-wait hardening is covered by regression tests; the live
source above deliberately remains pinned to the revision actually executed.
