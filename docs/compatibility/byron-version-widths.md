<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron protocol-version widths (REF-07, #171)

Part of the [compatibility matrix](../compatibility.md); the register entry is `byron-version-widths` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

bounded major/minor/alt (`Word16`/`Word16`/`Word8`) at smart construction and the shared header/proposal decoder; proposal succession uses bounded exact arithmetic

## Evidence

`ByronVersionSuite`: signed complete blocks with a signed transaction and proposal, matching proofs, 65535/65536 and 255/256 boundaries, large-major truncation rejection, legal and illegal registration transitions; `ByronProposalFixtureSuite` and [retained raw proposals](../../fixtures/byron-version/README.md): actual release CLI 11.2.3.0 decodes the signed maximum-width proposal and rejects each malformed width before socket connection; `ByronHeaderFixtureSuite` checks nine complete public-block variants against actual pinned CLI decoder verdicts; eleven signed `ByronVersionSuite` cases consume executed unchanged Haskell predicate results ([scope and provenance](../../scripts/oracle/byron/README.md)); source cross-check against ledger f649f97 `ProtocolVersion.hs:27-32,65-68`, `Validation/Registration.hs:489-499` and pinned `byron.cddl:126`; `CodecSuite` preserves historical three-BigInt bytes and snapshot fingerprints

## Explicitly unchecked or unconfirmed

`ByronRegistrationFixtureSuite` now replays [22 unchanged-reference registration/admission/crypto/block-ledger cases](../../fixtures/byron-version/full-registration/README.md), including six fully signed accepted boundary transitions and independent bad-signature controls; raw overflow mutations prove decoding only. Controlled synthetic state does not establish PBFT/adoption history; ordinary e2e checks valid Byron history
