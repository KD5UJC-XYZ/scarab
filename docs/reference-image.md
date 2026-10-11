# Reference image

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

`--download-dir` sets the location (default `${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/release`) and
`--pull-base` pulls the digest-pinned base image first.

An optional `--tag` changes the image name (default `cardano-reference-11.1.3:local`).

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
