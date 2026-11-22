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
| `node11_1-conway.cbor` | `11.1` | `haskell-state/conway-4593` (`a18120e5…4bc9`) | a pool with two owners, pledge ≠ cost and a relay of each kind (IPv4 and IPv6, a name with a port, an SRV name ending in a dot) and a metadata URL of 100 bytes (more than the 64 a URL holds below decoder version 9); single-precision likelihoods; a proposal of `Prices` and two `ExUnits` with two pool votes; the pool's future parameters, with that URL |
| `leios-conway.cbor` | `leios-b004fe0c` | `leios/state/conway-195` (`736c7ab5…371c`) | as above, without future parameters (their voting key's layout is not settled) |
| `node11_1-conway-indefinite-prices.cbor` | `11.1` | `node11_1-conway.cbor` | its proposal's `Prices` and `ExUnits` as indefinite lists, as an earlier writer stored them: it reads as `node11_1-conway.cbor` and writes it |
| `leios-dijkstra-param-proposal.cbor` | `leios-b004fe0c` | `leios/state/dijkstra-203` (`c2d0d6ff…09d7`) | a parameter-change proposal whose update sets parameters Dijkstra adds beside one of Conway's (`{0: 44, 34: 1048576, 36: 3, 37: 3/2, 38: 5/1, 47: [2160000000, 600000000000], 53: null}`: a number, a `NonZero`, a positive ratio, a `MaxPledgeLeverage`, an `ExUnits` and a null Peras round), pending in the proposals and the DRep pulser's snapshot and, under another id, among the actions its ratification enacted |

The `dijkstra-203` capture itself is not a case: the codecs write it back byte for byte, so its body would repeat
that file. The Dijkstra case was added by the owner's decision of 2026-11-21 on #826 (asked 14:33:55Z, answered
14:35:51Z), which approved V10's reading and writing of such proposals in place and this fixture with it, the state
held on the era's types by the answers of 15:53:11Z and 16:39:18Z; its bytes are those the flat writer of the first
build of the change wrote, which the era's codec writes again.

Recipe (in the JDK image, from the checkout, into a directory that does not exist yet); two runs write identical
bytes:

```sh
bash scripts/sbtw 'tools/runMain xyz.kd5ujc.scarab.storage.V10BodyFixtureMain /work/fixtures/snapshot-v10-bodies'
```
