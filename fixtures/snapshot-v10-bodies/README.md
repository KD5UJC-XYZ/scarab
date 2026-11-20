# Golden V10 snapshot bodies

Scarab fixture encodings are Apache-2.0. They are derived from the throwaway-devnet states of
[`fixtures/haskell-state`](../haskell-state/README.md) and [`fixtures/leios/state`](../leios/state/README.md);
every value put in is made up, and no file holds a key, a signature or operational node data (see
[NOTICE](../../NOTICE)).

Each file is the Haskell state section of a V10 snapshot (`SnapshotV10`'s body after its sidecar) as the
current codecs write it (`ReferenceLayout.encode`), in the layout of its build. `scripts/check_storage_format.py`
pins their hashes, and `V10BodyGoldenSuite` derives each file again from its case (`V10BodyCases`), reads it with
the snapshot's reader (`ReferenceLayout.decodeNative`), writes it back to the same bytes, and checks the values its
case put in. A change to the bytes a V10 body holds therefore fails that suite, and its regeneration fails the
guard until the owner approves it (FMT-1). Added by the owner's decision of 2026-11-20 on #1241, which approved
these fixtures under FMT-2.

| File | Build | Source state (sha256 of `state`) | Put in |
|---|---|---|---|
| `node11_1-conway.cbor` | `11.1` | `haskell-state/conway-4593` (`a18120e5…4bc9`) | a pool with two owners, pledge ≠ cost and a relay of each kind (IPv4 and IPv6, a name with a port, an SRV name ending in a dot); single-precision likelihoods; a proposal of `Prices` and two `ExUnits` with two pool votes; the pool's future parameters |
| `leios-conway.cbor` | `leios-b004fe0c` | `leios/state/conway-195` (`736c7ab5…371c`) | as above, without future parameters (their voting key's layout is not settled) |
| `node11_1-conway-indefinite-prices.cbor` | `11.1` | `node11_1-conway.cbor` | its proposal's `Prices` and `ExUnits` as indefinite lists, as an earlier writer stored them: it reads as `node11_1-conway.cbor` and writes it |

A Dijkstra state is not a case: the codecs write the Leios build's `dijkstra-203` capture back byte for byte, so
its body would repeat that file.

Recipe (in the JDK image, from the checkout, into a directory that does not exist yet); two runs write identical
bytes:

```sh
bash scripts/sbtw 'tools/runMain xyz.kd5ujc.scarab.storage.V10BodyFixtureMain /work/fixtures/snapshot-v10-bodies'
```
