# Bounded service checkpoint and clean restart

The `plutus-service` command remains volatile by default. An explicit checkpoint mode can stop after 1–8 new checked publications and export the confirmed coherent state for the restricted linear replay format. This does not guarantee that a particular transaction was included before the checkpoint. Pending envelopes, reservations, leases, evaluation receipt history, and historical owner identities are not restored.

Keep the usual eight service options. For export, add:

```
--checkpoint-after 1 --store-id <64 lowercase hex> --session-id <64 lowercase hex> --generation 0
```

The service writes `checkpoint.bin` and then `checkpoint-request.json`. Each file is published independently with CREATE_NEW semantics. They are not an atomic pair, and no fsync/crash durability guarantee is made. A failure between files can leave the binary alone; an existing file is never overwritten. The request explicitly says `restoreAuthorized: false`. A supervisor must verify both complete files and their exact publication/source/full-point identity before making any acceptance decision.

For restore, add:

```
--restore-checkpoint /absolute/prior/checkpoint.bin \
--restore-authority /absolute/controller/accepted.json \
--restore-authority-sha256 <raw file SHA256> \
--store-id <64 lowercase hex> --session-id <64 lowercase hex> --generation 0
```

The two input paths must be separate, absolute, normalized files outside the new output directory. The independently pinned authority file must have exactly these fields: `schema` = `plutus-service-restore-authority-v1`, `decision` = `accept`, and `claim` equal to the complete checkpoint request claim. The configured store, session, and incoming generation must match. The service never generates an acceptance file. The caller is responsible for current anti-rollback/revocation policy and must supply a fresh authorization decision when those policies can change; reading a historically accepted file does not requery an external controller.

Startup rereads the original checked native anchor, verifies its manifest and source join, authenticates the entire checkpoint, privately replays original blocks, and checks the terminal coherent tuple through `RestrictedRuntimeCheckpoint.recover`. Only then does it construct a new submission owner, an empty admission pool, a new evaluation evidence store, and HTTP resources. The normal follower intersects at the restored full point and validates subsequent original blocks. Old ledger undo, runtime fences, and candidates remain foreign under the recovery implementation's new lineages.

Export and restore may be combined. `--generation` identifies the incoming accepted publication, while the next export uses generation + 1 and rejects overflow. The requested new publication count plus restored depth must fit both the restored runtime's actual retained capacity and the eight-block format bound. Existing duration, epoch-zero, profile, relay, and deadline limits remain in force. Checkpoint completion uses the normal publication-budget stop; opt-in output adds `boundedRestart` metadata without changing default result wire semantics.

This wiring supports only the reviewed uncompacted linear epoch-zero replay domain. It does not enable arbitrary historical rollback histories, compacted checkpoints, multi-epoch restart, long-running persistence, or full ledger/consensus validation. No live restart demonstration is claimed by these offline tests. A later supervisor scenario must independently bind any transaction-before/restart/transaction-after claim to observed inclusion and checkpoint points.

`PlutusServiceCheckpointSuite` runs option, authority, and CREATE_NEW tests in default public CI. With the independently pinned `PLUTUS_LIVE_BUNDLE`, `PLUTUS_LIVE_MANIFEST`, and `PLUTUS_LIVE_MANIFEST_SHA256`, it additionally tests incomplete publication pairs, checked source restore, empty volatile pool, actual narrower-capacity rejection, and fake-peer rejoin through the second retained original block. Those retained bytes are private inputs and are not committed.
