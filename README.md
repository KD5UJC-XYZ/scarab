# Cardano Scala Lab

A Cardano node being rebuilt in Scala as idiomatic cats-effect tagless-final code. Effects are
abstract (`F[_]`), errors and state are typed with cats-mtl (`Raise`, `Handle`, `Stateful`), and
the algebras carry cats-laws and ScalaCheck properties. The goal is a complete node that is
compatible with the reference Haskell implementation.

## Status

The node follows a reference cluster from genesis, validates Praos headers (VRF, KES, opcert,
nonces) and applies Conway ledger rules for key-witnessed ADA transactions and staking
certificates. An 8-minute soak matched the Haskell `cardano-node` exactly at 23/23 comparison
points over epochs 0-9 (270 blocks, 432 transactions, 2 fork rollbacks, 4 reconnects). The
compared state covers the UTxO, fees, deposits, treasury, reserves, accounts, pools, DReps,
snapshots, reward update and the Praos nonces. Scripts, assets, governance actions and Byron
spending are rejected as unsupported. See [docs/node.md](docs/node.md) for details and limits.

## Modules

| Module | Contents |
|---|---|
| `core/` | Pure CBOR, Blake2b, Ed25519, VRF, KES and operational certificates |
| `network/` | Pure Ouroboros mini-protocol codecs |
| `network-runtime/` | fs2 tagless-final runtime: bearer, multiplexer, handshake, ChainSync / BlockFetch / KeepAlive clients over one connection |
| `node/` | Tagless-final node: peer, volatile chain, follower, Praos, Conway ledger, query server |
| `fixtures/` | Licensed test vectors and provenance (see `NOTICE`) |
| `scripts/` | `devnet.py` (local soak), `sbtw` (sbt wrapper), `prepare_reference_image.py` |

## Prerequisites

- Docker (Linux containers) and Python 3.11+ on the host.
- No host JDK or sbt is needed: they run in containers through `scripts/sbtw`.

## Build the images

```sh
# JDK image used by devnet.py and for building
docker build -f Dockerfile.public-check -t cardano-public-v023-check:local .
```

The Haskell reference image `cardano-reference-11.1.3:local` is built from the checksum-pinned
official release archive; see [docs/reference-image.md](docs/reference-image.md).

## Build and test

`scripts/sbtw` needs a JDK, so run it inside the JDK image:

```sh
docker run --rm -v "$PWD":/work -w /work --user "$(id -u):$(id -g)" \
  -e HOME=/work/.cache/home cardano-public-v023-check:local bash scripts/sbtw check
```

With a local JDK 21 the same command is `bash scripts/sbtw check`.

## Soak against the reference node

Needs both images above.

```sh
bash scripts/sbtw node/compile node/runtimeClasspathFile
python3 scripts/devnet.py soak --seconds 480     # up + follow + load + compare
python3 scripts/devnet.py down                   # remove containers and run data
```

`devnet.py` starts a 3-pool `cardano-testnet` cluster and the Scala node, drives a transaction
load, and diffs the full ledger and consensus state at matching tips. Details are in
[docs/node.md](docs/node.md).

## Era-walking end-to-end

`python3 scripts/devnet.py e2e` starts a reference cluster that walks Byron, Shelley, Allegra,
Mary, Alonzo, Babbage and Conway (`up-eras`), follows it from Origin with the Scala node and
asserts every era transition and zero validation errors. The same chain is committed under
`fixtures/eras` for the Docker-free `EraTransitionSuite`.

Run data lives in `${CSL_DATA_DIR:-/tmp/cardano-scala-lab}`, never in the checkout or home
directory.

## Roadmap

1. Cleanup and refactor to a tagless-final runtime (in progress).
2. Follow public Preview: hard-fork combinator and TPraos headers, Alonzo/Babbage ledger, and a
   headers-only bootstrap mode.
3. A reference cluster that walks Byron, Shelley, Allegra, Mary, Alonzo, Babbage and Conway
   (the node follows it from Byron, era transitions are tested); a comparator at every era is next.
4. Plutus V1-V3 evaluator, native scripts and multi-assets.
5. Persistence and Mithril snapshot import, multiple peers, mempool and transaction submission,
   and block production.

## License

Apache-2.0, see `LICENSE`. Third-party attributions for fixtures and adapted code are in `NOTICE`.
