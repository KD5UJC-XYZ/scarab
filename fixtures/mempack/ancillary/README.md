# Signed preprod ancillary manifest

`preprod-manifest.json` is unchanged from the ancillary archive pinned in [../README.md](../README.md):
node 11.1.3, preprod slot 136,511,997, archive `preprod-e319-i6320.ancillary.tar.zst`.
It lists all three ledger files and the unfinished immutable chunk trio. Those large files are not
committed here; their SHA-256 pins are in the manifest and the parent README.

`preprod.vkey` is the public verification key from Mithril
[`769d862dc2d7941192dac9088d1d86df967bf5cc`](https://github.com/input-output-hk/mithril/tree/769d862dc2d7941192dac9088d1d86df967bf5cc),
`mithril-infra/configuration/release-preprod/ancillary.vkey`. It is public input, not a signing key.
The key's format is hex-encoded JSON containing 32 octets. The manifest signature is 64 raw bytes
encoded as hex; its message is the raw SHA-256 of sorted path/digest pairs, not the JSON encoding.

`AncillaryManifestSuite` verifies this original signature and rejects changed key/signature/digest
inputs and unsafe/duplicate paths. The optional `PreprodSnapshotImportSuite` uses all original archive
files plus recorded immutable chunks for a complete authenticated offline import. Operators must get
the appropriate key from a trusted source; this fixture is evidence for this captured snapshot.


This directory is the signed-manifest corpus; its parent README covers the selected MemPack outputs
and the uncommitted full native snapshot. Ordinary signature tests run without downloading payloads.
Regenerate neither signature nor expected digests with Scarab: obtain a new original native manifest
and public trust-root provenance, review them, and retain the previous evidence. A valid manifest
signature alone does not demonstrate that the optional full import/export or matched-checkpoint
comparison ran; those need the original payloads and their separate acceptance assertions.
