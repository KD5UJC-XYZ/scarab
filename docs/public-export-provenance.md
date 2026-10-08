# Public export provenance

The public tree is a fresh root derived from v0.23.0 research commit `e79c20c6559da3753c633bd83eca1fda86063e19`. It does not publish that commit's history, provider-origin historical chain corpus, setup archives, private keys, or historical logs/dumps. The full research history is preserved only locally.

Original code is Apache-2.0 with an explicit MIT exception for the attributed Java VRF adaptations `Ed25519Point.java` and `Draft03Math.java`; their original source notices and `fixtures/vrf/licenses/CCL-LICENSE` are retained. Other fixture families retain their full upstream licenses and notices. Amaru-sourced four-header vectors are retained as licensed fixtures, not provider-origin chain captures.

The retained ChainSync Conway wrapper and BlockFetch source-derived hex contain the pinned Ouroboros Consensus Block_Conway toy golden, SHA-256 `0b7c8bdb99cf28f5e73769733abb4d4630013c5cf9368ed3176717841f95d8f3`. This is a disk-serialization toy, not authentic chain data; its declared body size 2345 differs from actual 6950. Its Apache-2.0 license, NOTICE and upstream provenance remain with the retained fixtures.

Six hash-pinned oracle provenance files intentionally retain generic historical cloud-workspace paths. These are archival tool invocations/results, not current commands, secret credentials, or paths the public build requires. They are retained byte-for-byte to preserve pins:

- `fixtures/coverage/oracle/cli-keyhash-results.json`: SHA-256 `7cdd2dea8bc5d3889cd92311ccb8f37c4b181477587a13476ef9305aff6ed63b`
- `fixtures/coverage/oracle/public-oracle-results.json`: SHA-256 `2e651ed3e8122d6b16c3f2bb9475b81b6dd25ae162b17a8cc5f5de1fc0dd52cf`
- `fixtures/fee-size/oracle/cli-results.json`: SHA-256 `23d5b07329e83c76baddbf6c0a8afb43cad64df0bd880ac4fe3e1e28454d2342`
- `fixtures/fee-size/oracle/variant-results.json`: SHA-256 `20e6f1c712b1c60a91baef7d3676f8413273d831589f5c85e4311b370bf5b967`
- `fixtures/witness/archive/pinned-source-oracle/provenance.json`: SHA-256 `59e5963c74173151eec575b4faa0fda71ad6e7caa59e768dfbcb3e3da0c0a57e`
- `fixtures/witness/archive/pinned-source-oracle/verify-compile-command.txt`: SHA-256 `5851fdcd23af3f6b09c2b378e54c2f4d6d30cd20d0ed994eb7554436b7674c8f`

Use [the public profile](public-profile.md) for current reproducible verification commands and actual reduced results. Historical research summaries do not claim included raw evidence or full public coverage. Optional private test sources require independently authorized corpus files and fail when those files are unavailable.
