<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native Praos2 header results

Executed 2026-11-04 with the [pinned native harness](../../../scripts/oracle/leios-praos2/README.md),
GHC 9.10.3 and ouroboros-consensus `2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b`, as required by
[#746](https://github.com/KD5UJC-XYZ/scarab/issues/746). The original inputs and their capture
provenance are [the restarted public testnet fixtures](../../leios/testnet/README.md).

| File | Executed result | SHA-256 |
|---|---|---|
| `headers.jsonl` | All 1,100 recorded headers accepted; every resulting protocol-state field retained | `9f5cde5f97a302e9f8a4284bd4cec87f421a91f53daac5e31530d47bbfd8752c` |
| `cases.jsonl` | 59 synthetic cases: 18 accepted transitions, 35 refusals and six certification-slot results | `90ea5fb06c42ba20c16dce5ec74db7c6f65c382257e307325633e569662278cf` |

`cases.jsonl` retains the original or modified header, recorded-prefix length, explicit forecast
and prior-state overrides, and native result. `praos2_cases.py` recreates these inputs. The ten
signed cases use seven deterministic native-signed headers; the signing seeds are public test
constants in the harness, not testnet credentials. Ordinary testnet headers have no announcements
or certificates, so the synthetic cases supply that coverage explicitly.

`Praos2ReferenceSuite` compares every accepted protocol-state field, each refusal constructor and
its domain arguments, and every timing result. Native diagnostic text is retained but its rendering
is not compared. See the harness README for the exact projection and untested scope. The genesis
pool forecast is supplied as input; these results do not validate a Dijkstra ledger forecast or
enable the node to follow or produce blocks on the testnet. Raw generated run/build artifacts are
not published.
