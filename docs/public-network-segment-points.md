# Verified candidate: four historical preprod Babbage blocks

Checked 2026-10-10. This resolves the missing-point prerequisite in [the feasibility assessment](public-network-download-feasibility.md) for **public preprod**, not mainnet. No public Cardano relay was contacted; no devnet workload was changed. Only retained files and two bounded HTTPS archive/metadata reads were used. Execution remains conditional on successful soak.

## Exact selection

Network: preprod; network magic **1**, independently confirmed in [official Shelley genesis](https://book.world.dev.cardano.org/environments/preprod/shelley-genesis.json). Era: Babbage, recorded protocol 8.0, epoch 104. These are historical blocks, not the current tip.

| Role | Slot | Block height | Header hash |
| --- | ---: | ---: | --- |
| Exclusive predecessor | 43610414 | 1563645 | `80d02d3c9a576af65e4f36b95a57ae528b62c14836282fbd8d6a93aa1fef557f` |
| First inclusive | 43610428 | 1563646 | `40a3d87d796ade6686c21c35ef0cedc7e2a792452f418c24b34403d606ef9a0e` |
| Second | 43610443 | 1563647 | `1fc52cb9c93e7ea7d5985a581c43a91f9b51c011173717c2d60d9f37d55fc129` |
| Third | 43610470 | 1563648 | `c576d670f8c011b1ddb507bc3b70bab50738d0e6290e29804c99e969cb74a156` |
| Last inclusive | 43610483 | 1563649 | `d51f1cd7d29585e4faeb97202b09124eb7d4789d1a32a0309516d00d66551e42` |

Request exactly four blocks from first through last, with the listed exclusive predecessor and complete ordered expected points. The predecessor itself is not downloaded by that request. A one-block fallback can be planned in advance using first=last=43610428 and the same predecessor; it is not an automatic retry policy.

## Original-byte pins

| Slot | Original disk-envelope bytes | SHA256 |
| --- | ---: | --- |
| 43610414 (anchor) | 864 | `fe21bac089cfe08d392b152f24f0ad423f7e4662905d7ac94b7f0b7ef99b5582` |
| 43610428 | 11867 | `b5c6c1f7608cfe228bb20a25fa4a8ad59c57b169639be11973d7d3910e0cfca1` |
| 43610443 | 3809 | `d744033ffc37658d5705272b3cf8c0fafc222ef6e7ea8153dc1d00eb04a04155` |
| 43610470 | 2503 | `dfd9d1501e6c03dda49bc811d794155251ed042e4af71da2921f0782f50b4b4d` |
| 43610483 | 2858 | `7721a9cdb29471c69f9a7b570ee2c8cb693af55c446ad1ac035777ce679550d2` |

Selected payload totals **21,037 bytes**, excluding mux/BlockFetch/KeepAlive framing. Preserve the original conservative caps from the feasibility assessment. If the relay returns a different serialization, optional byte-pin failure is a mismatch to investigate, not permission to discard pins or claim exact equality.

## Independent evidence chain

1. Recovered existing evidence from the preserved private-history `fixtures/post-byron/` backup: `PROVENANCE.md`, `expectations.tsv`, `preprod-lookup.json`, `upstream-tree-entries.json`, `02019.chunk`, and five original slices. The retained lookup SHA256 is `24336d5777e53c20ed6390197e272df441e435d3ed8f5fe48a6f2ec1a56ffeee`. The existing `fixtures/chain-fetch/babbage.tsv` selects these same four blocks; its source-manifest SHA256 is `fd066cf8fc8791b6cc002902f6353981639cec476d2b130410911d21435a459b`.
2. Recomputed original header BLAKE2b-256, slot, height and parent from the raw slices using the retained independent span reader. Each child links to the previous header and slots strictly increase. All five slices concatenate exactly to the 21,901-byte archive; chunk SHA256 `10a6220cde4f1fd299e5c6bcf455efb0b5c9665ac282d112d7a3b19df56029ed`.
3. Fresh read of [Pallas archive at immutable commit 0ea294c](https://github.com/txpipe/pallas/blob/0ea294c1273b9c4a5a0a9f767897800589f5e02b/test_data/02019.chunk), via its [raw URL](https://raw.githubusercontent.com/txpipe/pallas/0ea294c1273b9c4a5a0a9f767897800589f5e02b/test_data/02019.chunk), was byte-for-byte identical. Retained Git blob identity: `0c173c25667232e65e93f6e8d189d4ee4465b09e`.
4. Fresh read-only POST to [Koios preprod block_info](https://preprod.koios.rest/api/v1/block_info), with JSON `_block_hashes` containing the five table hashes, returned all five. Every slot, parent and height matched the independently parsed original bytes. Response SHA256 `2876d22391c81f2864276b32ad25183d3b648d33cdc072504b1d3b197adde7aa`; confirmation counts can change, so future response digests need not match. Requests used 15-second timeouts and a 64 KiB response ceiling. The browser GET variant was unavailable; the normal read-only POST succeeded.

No tuple or raw-archive mismatch was found. Koios `block_size` is **not** the same quantity as complete serialized disk-envelope length (e.g. first selected block: 11,005 versus 11,867 bytes); it must not replace the raw length pin. The private provenance's named mainnet singles include Shelley, Allegra and Mary; the inspected current summary singled out Mary. No mainnet predecessor slot has been inferred from those singles. The five chosen Babbage blocks are consistently preprod; the separate `babbage1.block` fixture has an unestablished network and is not used.

## Remaining launch prerequisites and honest claim

After the soak succeeds, resolve a numeric peer from the fresh [official preprod topology](https://book.world.dev.cardano.org/environments/preprod/topology.json), pin configuration/provenance and instantiate the existing KeepAlive descriptor with magic 1 and these exact points. No DNS/peer availability or NtN14 compatibility test has been done. Old-history availability, wire interoperability and exact returned serialization remain unproved until that bounded request completes. Do not substitute mainnet topology or magic.

Success would demonstrate **public preprod block fetching, exact original-byte comparisons and header/parent-link checks** for four historical blocks. Agreement between an archive and explorer is source corroboration, not authenticated consensus, historical ledger-state validation, a whole-era sync, or proof of public-mainnet compatibility. Preserve only the small sanitized report and remove the owned downloaded data after checking it.
