# Optional offline Haskell reference checks

On 2026-10-08 UTC, the official native Haskell `cardano-cli 11.2.3.0` decoded all three existing Conway golden transactions and returned transaction IDs exactly matching their precomputed upstream expectations. Three additional JSON views report Conway. [Captured commands/results](../fixtures/reference-cli/results.json), [provenance](../fixtures/reference-cli/provenance.json), and [evidence checksum manifest](../fixtures/reference-cli/SHA256SUMS) are retained.

This is **full-transaction decode and body-hash evidence only**. It does not establish signatures, spendability, script validation, ledger transitions, consensus, network interoperability, or protocol-major-10-specific acceptance. The CLI uses its supported Conway era-high decoder; protocol major 10 is a separate corpus provenance fact. Synthetic golden transactions need not be valid ledger transactions.

## Reproduce explicitly

The regular Scala build/application/tests do not require or execute the native CLI. Maven dependency fetching remains the only external network prerequisite of the normal build; existing networking tests use localhost simulation. Python 3.9+ standard-library tests for this optional harness require no native CLI:

```sh
python3 scripts/reference-cli-tests.py
```

On Linux x86-64, provide an independently trusted executable from the pinned official distribution and a **new** output directory:

```sh
python3 scripts/reference-cli-checks.py \
  --cli /absolute/path/to/trusted/cardano-cli \
  --output /tmp/cardano-reference-new-run
```

There is no automatic download, installation, PATH lookup for the CLI, platform substitution or checksum bypass. The script rejects an existing output directory to avoid stale evidence. It verifies platform, executable SHA-256, exact version text, pinned fixture manifest and each original transaction/body size and SHA-256 before checking the transactions. The manifest is pinned to this three-case corpus; corpus changes need an explicit reviewed pin update. Errors exit 2 and are reported as harness failures, never ledger rejection. Successful runs exit 0.

Each native invocation has a 20-second timeout, closed inherited descriptors, no stdin, a fixed output working directory and a minimal environment without node socket settings. Only `--version`, `conway transaction txid --tx-file ... --output-text`, and `debug transaction view --tx-file ... --output-json` are used. No node startup, socket configuration, socket permission retry, network query, submission, or key operation is performed. This is a file-only command allowlist, not an OS network sandbox or syscall-level proof of no network activity.

## Binary custody and reproducibility limits

The [official node 11.1.3 release](https://github.com/IntersectMBO/cardano-node/releases/tag/11.1.3) contains CLI 11.2.3.0 in `./bin/cardano-cli`:

- Archive: `cardano-node-11.1.3-linux-amd64.tar.gz`
- Archive SHA-256: `530fb99986fbdccc46f9ee0dd86cabb1393b015feac9173cb731d89fa748365f`
- Executable SHA-256: `0ac45e874599fac4ee6ca4fd9602c0ddb9854a62be5f365dfa425eff9f4bd0ed`
- Exact version: `cardano-cli 11.2.3.0 - linux-x86_64 - ghc-9.6`
- Embedded build revision: `938cba990357ae7c4b7f95c8f75dd9d31174bbeb`
- Release-linked CLI source: `eac27b8b0437a80cea2152917850aadea5749d90`
- Release-linked API source: `7a4de3f5e2510af7d2a819183333f2655e3b002d`

The embedded build revision is not asserted to be the CLI repository revision. The release checksum manifest is retained verbatim. Matching a publisher's checksum provides artifact consistency; no independently verified signature or reproducible-build proof is claimed. No executable/archive or upstream implementation source is bundled. Existing Cardano Ledger licenses/NOTICE cover the fixture-derived envelopes and output data; original harness code uses this project's Apache-2.0 license.

## Exact bytes and implementation route

Each text envelope has `type: "Tx ConwayEra"`, a description and `cborHex` containing the **untouched complete transaction bytes**. Decode-back equality is checked. No CBOR ordering, width, indefinite container, body, witness, hash, or endianness is changed. Raw body slices are not mislabeled as API TxBody envelopes, because that API representation itself serializes a full transaction.

Pinned official sources establish the route:

- [API TextEnvelope implementation](https://api.github.com/repos/IntersectMBO/cardano-api/git/blobs/06e7082fa9830ed389b159d4e5bbba1a2684084a): JSON fields and raw CBOR.
- [API transaction implementation](https://api.github.com/repos/IntersectMBO/cardano-api/git/blobs/2ac9770d75219a954f226fdcd5d01be2c9d41b07): Conway type label and annotated ledger decoder, plus TxBody serialization.
- [CLI reader](https://github.com/IntersectMBO/cardano-cli/blob/eac27b8b0437a80cea2152917850aadea5749d90/cardano-cli/src/Cardano/CLI/Read.hs#L299-L330): typed transaction reader.
- [CLI transaction ID path](https://github.com/IntersectMBO/cardano-cli/blob/eac27b8b0437a80cea2152917850aadea5749d90/cardano-cli/src/Cardano/CLI/EraBased/Transaction/Run.hs#L1668-L1700): body selection and `getTxIdShelley`.
- [CLI friendly transaction view](https://github.com/IntersectMBO/cardano-cli/blob/eac27b8b0437a80cea2152917850aadea5749d90/cardano-cli/src/Cardano/CLI/EraIndependent/Debug/TransactionView/Run.hs): decode and render.

API source blobs were verified by Git blob identity against the pinned repository tree during research. Provenance retains immutable IDs and SHA-256 values; the research source snapshots are not part of this distribution.

## Results

| Case | Exact transaction ID | JSON view fee |
|---|---|---|
| 044 | `ffa3e217dec2cec2f5d4f9ec60dce862332454c65dd1cab08d796ca8f2951355` | 694668 Lovelace |
| 057 | `c2b1815ec5ffbcafb810a857dc18a035884949ac34fc14d04df545f469d6cd17` | 4 Lovelace |
| 042 | `d39e160bd2cc34dbdf270f5a956fb1f663385eb9c5e900534c4ed937b331e080` | 19 Lovelace |

Every command exited 0 with empty stderr. IDs agree with the existing extraction manifest; expectations were not derived from this run or replaced afterward. The optional harness checks JSON validity and the Conway era, while fees are recorded observations. The Scala application continues comparing against the precomputed upstream TxInfo expectations. Network reference-runtime and Plutus evaluation oracle flags remain false: neither gate is satisfied by these decode/hash results.
