<p align="center">
  <img src="docs/assets/scarab-logo.png" alt="Scarab logo" width="220">
</p>

<h1 align="center">Scarab</h1>

<p align="center"><em>A Cardano™-compatible node in functional Scala</em></p>

An independent, Cardano™-compatible node written in Scala 3 as tagless-final cats-effect code: effects are
abstract (`F[_]`), domain errors are closed ADTs raised through cats-mtl (`Raise`, `Handle`), state
goes through `Stateful`, and the algebras carry cats-laws and ScalaCheck properties. The goal is a
node that is compatible with the reference Haskell implementation and readable enough to study, and
this repository is how far that has got.

> **Unofficial research software. Not audited. Do not use it with mainnet funds or to run
> production stake pools.** It is not affiliated with or endorsed by Input Output Global, Intersect,
> the Cardano Foundation or EMURGO, and is not the Cardano node software they distribute. The name
> "Cardano" is used only to describe compatibility with the Cardano protocol. "Cardano" is a trademark of the Cardano Foundation, CHE-184.477.354, Dammstrasse 16, 6300 Zug, Switzerland, in Switzerland and other countries. See
> [SECURITY.md](SECURITY.md), [NOTICE](NOTICE) and [docs/licenses.md](docs/licenses.md#name-and-trademark-wording).

## Status: v0.1.0 research preview

The node follows a chain from genesis, validates TPraos and Praos headers (VRF, KES, operational
certificates, nonces, leader schedule) and applies the ledger rules of every Shelley-based era
through Conway: native and Plutus V1 to V3 scripts, all certificates, governance (proposals, votes,
ratification, enactment, DRep pulser) and treasury donations. It persists its chain (immutable blocks,
snapshots, a RocksDB UTxO set), acts as a relay (node-to-node servers, mempool, local transaction
submission) and can forge Praos blocks. Byron is supported from genesis (PBFT, ledger, update system) through to Shelley. What is checked against
what, and what is not, is in [docs/compatibility.md](docs/compatibility.md); the next steps are in
[docs/roadmap.md](docs/roadmap.md).

## Evidence

| Check | Result |
|---|---|
| **Preview, genesis to tip, uninterrupted, one build** | Commit d66b216 (frozen classes), fresh db, no restarts, `-Xmx3g`, 6 CPUs, 2026-10-11: 4,748,226 blocks, epochs 0 to 1447 in 3h36m42s (about 365 blocks/s), then 32.8 min at the live tip; 0 error events; 3,229,757 successful and 242 failing Plutus evaluations, each equal to `is_valid`; final block 4748301 (slot 125092775, hash `c182c2d1...f733e`) identical to Koios `/tip`. Container RSS peaked at 5.66 GB (glibc malloc arenas; bounded since, see docs/performance.md section 7), disk 14.8 GB. Details in [docs/performance.md](docs/performance.md). The tag `v0.1.0` will sit on a later commit (hygiene, docs, CI; no intended behaviour change, gates re-run). |
| Preview, genesis to tip (earlier run, 12 restarts across builds) | 4,747,639 blocks, epoch 1447, no rule failure; every header leader-checked, 3.23M Plutus transactions evaluated with verdicts equal to `is_valid`; about 551 blocks/s average on 8 CPUs, 272 MB live heap at the tip. This run is not a single-build run, see [docs/performance.md](docs/performance.md). |
| Devnet pool (`devnet.py pool`) | The Scala node as pool 1 among two Haskell pools: latest run (on d66b216): 80 forged, 79 adopted, 78 on the final chain; the Haskell ledger counts 78 blocks made by the pool, equal to the 78 Scala blocks on chain; 16/16 ledger comparisons matched. An earlier run (a3c6929): 86 forged, 85 adopted, Haskell blocks made 85 against 83.2 expected. |
| Devnet e2e (`devnet.py e2e`) | Byron to Conway followed from Origin; every per-era ledger comparison against the Haskell node matched: Shelley 4, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30. |
| Devnet soak (`devnet.py soak`) | 24/24 comparisons matched over epochs 0 to 9 (41/41 over 18 epochs) with governance and Plutus V3 load, through the hard fork to protocol 11. |
| Devnet relay (`devnet.py relay`) | A Haskell relay synced solely from the Scala node and stayed at the tip; 203/203 load transactions went through the Scala mempool into blocks. |
| Real-data bugs | Six ledger bugs found only by syncing Preview and now pinned by tests, see [docs/findings.md](docs/findings.md). |

The devnet runs are against `cardano-node` 11.1.3 and are one developer's recorded runs with the
commits in [docs/compatibility.md](docs/compatibility.md); they do not run in CI yet. Preview
agreement shows the node accepts the real chain with full validation (no false rejections), not that its ledger state is identical to
the Haskell node's (roadmap K1, db-analyser comparison); the devnet comparator covers identity on generated load. Things explicitly **not** checked include mainnet, a preprod sync to the tip, Byron
transactions on a contiguous chain (mainnet Byron blocks with transactions are validated individually), `ledger-state` and the other node-to-client queries listed in the compatibility matrix, Mithril import, the P2P governor and security
review.

## Quick start

Needs Docker and Python 3 on the host. No host JDK or sbt is required.

```sh
# 1. The JDK image used for building and running
docker build -f docker/jdk.Dockerfile -t cardano-scala-jdk:local .

# 2. Build and test (scalafmt check, compile, all tests)
docker run --rm -v "$PWD":/work -w /work --user "$(id -u):$(id -g)" -e HOME=/work/.cache/home \
  cardano-scala-jdk:local bash scripts/sbtw check node/runtimeClasspathFile

# 3. Follow a network (flags, defaults and Preview in docs/running.md)
java -cp "$(cat node/target/runtime-classpath.txt)" xyz.kd5ujc.scarab.node.Main follow \
  --genesis-dir <config dir> --peer <host>:<port> --db "${SCARAB_DATA_DIR:-/tmp/cardano-scala-lab}/db"
```

Step 3 needs a JDK 21 (or run it inside the image). The scenarios against the Haskell node also need
the reference image `cardano-reference-11.1.3:local`, built from the checksum-pinned official release
archive ([docs/reference-image.md](docs/reference-image.md)):

```sh
python3 scripts/devnet.py soak --seconds 480    # also: e2e, relay, pool; then `down`
```

What each scenario asserts is in [docs/running.md](docs/running.md). Run data goes to
`${SCARAB_DATA_DIR:-/tmp/cardano-scala-lab}`, never into the checkout or your home directory (`CSL_DATA_DIR`, the former name, is still read when
`SCARAB_DATA_DIR` is unset).

## Modules

| Path | Contents |
|---|---|
| `codec/` | CBOR with original-bytes preservation, `Bytes`, `Rational` |
| `crypto/` | Blake2b, strict Ed25519, VRF (verify and prove), Sum6 KES, BLS12-381, operational certificates |
| `protocol/` | Pure Ouroboros mini-protocol codecs and state machines |
| `network/` | fs2 tagless-final bearer, multiplexer, handshake, client and server roles of ChainSync, BlockFetch, KeepAlive, TxSubmission2, LocalTxSubmission, LocalStateQuery |
| `plutus/` | The Plutus script evaluator algebra and its Scalus adapter |
| `ledger/` | Blocks, headers, genesis, transactions and the Byron to Conway ledger rules |
| `consensus/` | Praos, TPraos and PBFT, chain selection, the hard-fork combinator, header rules |
| `storage/` | Immutable and volatile stores, ledger snapshots, the UTxO store, the Haskell ImmutableDB reader |
| `node/` | Peer, follower, relay, mempool, forging, node-to-client, query server, `Main` |
| `models-gen/`, `models/` | CDDL parser and generator; the generated models and their equivalence tests |
| `fuzz/` | Structured fuzzing of the decoders |
| `fixtures/` | Licensed test vectors and captured chain data, each directory with its provenance |
| `scripts/` | `devnet.py` (scenarios), `sbtw` (pinned sbt launcher), `prepare_reference_image.py` |
| `docs/` | The documents below |

## Documentation

- [docs/architecture.md](docs/architecture.md): layers, algebras, error model, data flow.
- [docs/running.md](docs/running.md): every `follow` flag, the capture tool, devnet scenarios, data directories, Preview.
- [docs/compatibility.md](docs/compatibility.md): the compatibility matrix with evidence and unchecked items.
- [docs/performance.md](docs/performance.md): sync numbers, profiles, memory and disk.
- [docs/findings.md](docs/findings.md): facts learned against real data, the six Preview bugs.
- [docs/roadmap.md](docs/roadmap.md): tracked work items with stable IDs.
- [docs/leios.md](docs/leios.md): Ouroboros Leios (CIP-164) design note: sources, protocol changes, mapping onto this codebase, staged plan (research only, not implemented).
- [docs/reference-image.md](docs/reference-image.md): building the Haskell reference image.
- [CHANGELOG.md](CHANGELOG.md).

## Contributing, security, license

Read [CONTRIBUTING.md](CONTRIBUTING.md) (Docker-only build, style rules, the gates a change must
pass) and the [Code of Conduct](CODE_OF_CONDUCT.md). Report vulnerabilities privately as described
in [SECURITY.md](SECURITY.md). Never commit signing keys.

Apache-2.0, see [LICENSE](LICENSE). Third-party dependencies and fixture attributions are in
[NOTICE](NOTICE); the resolved dependency licences are in [docs/licenses.md](docs/licenses.md).
