# Local reference-cluster checkpoint

Verified on dev-host on 2026-10-08 using Docker Desktop Linux containers. Official node 11.1.3, CLI 11.2.3.0 and testnet 11.1.1 were taken from the checksum-pinned release archive documented in private-cluster.md. No public peers, published ports, real funds or real-network keys were used.

Three bounded runs established Conway ledger protocol version 9.0 and multi-producer block growth across epoch boundaries. The latest run observed blocks 2–54 and epochs 0–2 with converged final tips in 130.8 seconds including cleanup. The existing Scala implementation negotiated node-to-node wire version 14 with the actual reference node. Header-advertised protocol version was not measured by this checkpoint. No block or ledger conformance is claimed.

Validation: formatting checks, 100 network tests, 52 network-runtime tests and 108 application tests passed in the official JDK21 container. Twelve Python launcher tests passed in Docker, including timeout/cancellation cleanup continuation, named preflight cleanup, both verification attempts, interrupted capture and failed error-receipt writes. Independent review closed all three launcher findings before this commit.

Raw logs, exact generated public configuration, source hashes and cleanup receipts remain outside Git under `/home/<user>/cardano-reference-smoke-20261008`, `/home/<user>/cardano-reference-integration-20261008` and `/home/<user>/cardano-reference-reviewfix-20261008`. Disposable signing keys and DBs existed only in task-owned tmpfs and were removed with task containers. Unrelated resources were left intact. This is local test evidence, not configured CI.
