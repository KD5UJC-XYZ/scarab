# Live reference header and block capture

The optional `--capture` scenario extends the bounded private-cluster launcher. It uses the existing Scala NtN14 ChainSync and BlockFetch implementations against the official Haskell development cluster. Run after compiling the checkout at `/work` in JDK21:

```sh
python3 scripts/private_cluster.py --reference-image cardano-reference-11.1.3:local \
  --output /absolute/task/new-capture-evidence --seconds 240 \
  --scala-repo /absolute/checkout --capture
```

The launcher queries a Conway anchor point and passes only its public slot/hash to Scala. ChainSync negotiates wire14, finds that intersection, permits one rollback to that exact anchor as alignment, and requests its successor. A different rollback or repeated alignment fails closed. A separate bounded BlockFetch connection requests only the derived header point and requires exactly one block followed by BatchDone. The original header envelope and raw block bytes are recorded in the task-local Markdown receipt, never copied into the repository.

Comparison requires Conway structural block indexing, identical original header bytes and hashes, matching slot/block number, parent equal to the supplied anchor hash, advancing slot, and both body size and body hash commitments. It decodes the advertised header protocol pair from the original header. It does not equate that pair to the queried ledger version, check issuer authorization or signatures, or apply the ledger. The existing partial signature-evidence profile is unchanged; its historical header-version admission must not be silently widened to fit a newer reference node.

Observed on dev-host 2026-10-08: the actual header advertised **11.2** while the reference query returned ledger **9.0**. The captured block at slot 53/block number 2 had header hash `040cf5e7ed9233f57a285c1a4b45c010003ab3a1482140bd22a7be6bd003fc8a` and raw SHA-256 `127ed61769d46f54466876362b7e2c3d4fcf2120034f44a530ea9fc630c1a7a0`. Original header bytes, parent, body hash and four-byte empty body size matched. The three-node run advanced from block 2 to 58 and epoch 0 to 2 in 129.8 seconds including cleanup. No live transaction was submitted.

Raw evidence remains outside Git in `/home/<user>/cardano-reference-capture2-20261008`. An initial run stopped on the previously unhandled alignment rollback; its failure and successful cleanup remain in `/home/<user>/cardano-reference-capture-20261008`.

The explicit offline command `reference-capture-check HEADER_HEX_FILE BLOCK_HEX_FILE ANCHOR_SLOT ANCHOR_HASH` opens no socket. `scripts/verify-reference-capture.py LOCAL_CAPTURE_DIRECTORY` runs five offline cases over the actual locally captured bytes: original accepted; truncated block, different original header, wrong parent anchor and altered body commitment rejected. Run it in the local JDK/Python test container with the compiled checkout at `/work` and evidence mounted read-only. The mutation driver currently requires the captured block's empty invalid-index list; it rejects other profiles rather than silently changing its mutation semantics.

Validation for this increment: 112 application tests, 12 launcher failure/isolation tests and five real-byte offline regressions passed in Docker. The network implementations were reused unchanged. All task containers and networks were removed; keys stayed in bounded tmpfs. Header/block consistency is narrower than reference decoder parity, signature validity, consensus or ledger conformance.

## Receipt provenance

The preserved live receipt in `cardano-reference-capture2-20261008/scala-capture.md` emits scope `live-reference-header-block-comparison`. It was produced before the subsequent label-only change to `header-block-byte-comparison`, which also labels the pure offline verifier accurately. Original live output is unchanged; it is not attributed to the post-label source bytes. The five offline regressions and final application tests used the later source. Independent review checked the seven feature source hashes and independently decoded/hashed the saved original bytes; approval covers structural capture and consistency only.
