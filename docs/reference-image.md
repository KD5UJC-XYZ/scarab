# Reference image

**Status: Current recipe for the pinned reference release.** Updating that release requires
new checksum pins and evidence; the commands below do not select the latest release.

The devnet scenarios in [running.md](running.md) compare the Scala node against the official Haskell
`cardano-node`. `scripts/prepare_reference_image.py` builds the local image
`cardano-reference-11.1.3:local` from the official release archive. It needs local Linux
Docker and Python 3 and installs nothing. By default it downloads nothing; `--download` (used by the
nightly end-to-end workflow) fetches the pinned URL and verifies it before use.

## Prepare once

Download the exact
[official archive](https://github.com/IntersectMBO/cardano-node/releases/download/11.1.3/cardano-node-11.1.3-linux-amd64.tar.gz)
outside the checkout.

- Size: 233694227 bytes
- SHA-256: `530fb99986fbdccc46f9ee0dd86cabb1393b015feac9173cb731d89fa748365f`, also listed in the
  [official checksum manifest](https://github.com/IntersectMBO/cardano-node/releases/download/11.1.3/cardano-node-11.1.3-sha256sums.txt)
- Release source revision: `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`
- Component versions: node 11.1.3, CLI 11.2.3.0, testnet 11.1.1 (its version output calls itself
  `cardano-node` and has a zero git stamp). Testnet links API 11.6.0.0 and CLI 11.2.3.0.

The digest-pinned official base image must already be in the local Docker cache:

```sh
docker pull ghcr.io/intersectmbo/cardano-node@sha256:b863d5751bf691588995a8e12509057076e207270db65e88bb16bd543aaa9347
python3 scripts/prepare_reference_image.py /absolute/path/cardano-node-11.1.3-linux-amd64.tar.gz
```

Or let the script fetch and verify the archive (HTTPS only; the file is renamed into place only after
the size and SHA-256 match, and a present verified file is reused):

```sh
python3 scripts/prepare_reference_image.py --download --pull-base
```

`--download-dir` sets the location (default `${SCARAB_DATA_DIR:-/tmp/scarab}/release`) and
`--pull-base` pulls the digest-pinned base image first.

An optional `--tag` changes the image name (default `cardano-reference-<release>:local`, so
`cardano-reference-11.1.3:local`).

## cardano-node 11.2.1

`--release 11.2.1` builds `cardano-reference-11.2.1:local` from the release of the `11.2` reference
build, the reference target the [ADR 0001 amendment of 2026-11-14](adr/0001-haskell-node-as-reference.md#amendment-2026-11-14-the-newest-release-re-pinned-in-stages)
names. The scenarios still run `cardano-reference-11.1.3:local`, and the nightly workflows build
only that one.

- [Official archive](https://github.com/IntersectMBO/cardano-node/releases/download/11.2.1/cardano-node-11.2.1-linux-amd64.tar.gz)
- Size: 249149875 bytes
- SHA-256: `88276ed753f460ee876c03c305ad68de9e4a16945f7c741e08130bf296791174`, also listed in the
  [official checksum manifest](https://github.com/IntersectMBO/cardano-node/releases/download/11.2.1/cardano-node-11.2.1-sha256sums.txt)
- Release source revision: `90bc1cd9380a78a63091ff673f8c5693152c8f60`
- Component versions: node 11.2.1, CLI 11.7.0.0, testnet 11.2.1 (its version output calls itself
  `cardano-node` and has a zero git stamp; it links API 11.12.0.0).
- The pinned base image is the same as 11.1.3's, and the same checks apply.

```sh
python3 scripts/prepare_reference_image.py --release 11.2.1 /absolute/path/cardano-node-11.2.1-linux-amd64.tar.gz
python3 scripts/prepare_reference_image.py --release 11.2.1 --download --pull-base
```

`cardano-testnet create-env` of 11.2.1 writes its `configuration.yaml` in the cardano-config
envelope (`$schema`, `Configuration`), which cardano-node 11.1.3 and Scarab do not read yet; the flat
form that 11.1.3's `create-env` writes is still read by cardano-node 11.2.1, as its release notes
say. So the devnet runs 11.2.1 pools with the 11.1.3 image's tools:
`python3 scripts/devnet.py --name <yours> up --node-image cardano-reference-11.2.1:local` runs every
pool on 11.2.1's `cardano-node`, and `--node-image 2=cardano-reference-11.2.1:local` only pool 2,
beside two 11.1.3 pools. Genesis, configuration, `cardano-cli` and `cardano-testnet` stay 11.1.3's;
`up` prints and keeps (`nodes.json`) the version of each pool's node.

## What the script verifies

1. The Docker context is the plain local socket (no `DOCKER_HOST`/`DOCKER_CONTEXT` override) and
   the engine runs Linux containers.
2. The pinned base image is present locally.
3. The archive size and SHA-256 match (also directly after a `--download`) the values above.
4. No archive member has an absolute path, a `..` component or a backslash.
5. Exactly one regular file each for `cardano-node`, `cardano-cli` and `cardano-testnet` exists,
   and each matches its pinned SHA-256.

Only those three executables are copied out (no keys or network configuration). The image is
built `FROM` the pinned base with `--pull=false --network=none`, installs the binaries in
`/opt/reference/bin` on `PATH` and clears the entrypoint.
