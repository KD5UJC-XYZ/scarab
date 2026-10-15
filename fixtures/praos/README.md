# Praos VRF certificate fixtures

Public evidence for `xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate` (the Praos VRF input `alpha` and the check of a header's
VRF certificate), read by `PraosVrfCertificateSuite`.

| File | Contents |
|---|---|
| `alpha.tsv` | 15 rows `slot  nonce  alpha`: the Praos VRF input for a slot and an epoch nonce, `Blake2b-256(slot as 8 big-endian bytes ++ nonce)`; `NEUTRAL` is the neutral nonce, which contributes no bytes. Slots at the edges of Word64 and nonces of zero, sequential and high bytes are included. |
| `certificates.tsv` | 18 rows `case  slot  nonce  public key  proof  claimed output  alpha  expected verdict`: the VRF certificates of four archived public preprod headers (slots 70070331, 70070379, 70070426, 70070464) as they are (`original`) and, for the first two, seven mutations each (`wrong-slot`, `little-endian-slot`, `neutral-nonce`, `wrong-nonce`, `proof-bit`, `key-bit`, `claim-bit`), each with the verdict the pinned native reference gave. The last two originals reject under the nonce used and are kept as rejections of unknown context, not as invalid-header fixtures. |
| `sha256.json` | SHA-256 of every file here, checked by the suite before it reads them. |
| `evidence/` | The sources the rows were derived from, kept verbatim: `Consensus-Praos.hs`, `Protocol-Praos-VRF.hs`, `VRF-Class.hs` (IntersectMBO ouroboros-consensus, cardano-ledger and cardano-base at the commits in `sources.json`), four Amaru header files `preprod_*.cbor` with the Amaru Rust sources that name the epoch nonce (`amaru-*.rs`), `research.md` (the derivation of the alpha and the results), `public-header-results.json`, `epoch166-header-results.json`, `alpha-controls.json`, `mutation-results.json` (what the native reference returned for each row), and the licences. |

## How produced

The two valid headers come from the public test data of the Amaru project (pinned in `evidence/sources.json`
with URLs and SHA-256); the epoch nonce is the one its test state records. The expected results of the
originals and of every mutation were obtained by calling only the VRF verification function of the Cardano
fork of libsodium, built from the pinned source on the research machine (`research.md`, "Genuine archived
public fixtures"); no prover, signer, key generation or private key was involved, and none of the files holds
one. The library is a source build, not the node's shipped binary: agreement with
the node is evidence, not proof (see `docs/compatibility.md`).

## Provenance and licence

`evidence/*.hs` are verbatim Apache-2.0 sources of IntersectMBO (`consensus-LICENSE`, `consensus-NOTICE`,
`ledger-LICENSE`, `ledger-NOTICE`); `evidence/amaru-*` and the header files are from pragma-org/amaru at commit
`34a453005bcaaf837ee73bd996b99eab8ef92961` under its Apache-2.0 licence (`amaru-LICENSE`). The `.tsv` rows and
`.json` results are our own derived data about those public headers; the repository's Apache-2.0 applies to
them. Chain data in the headers is public. No private data. See `../../NOTICE` and `../../docs/licenses.md`.
