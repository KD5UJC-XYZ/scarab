<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron Ed25519 verification (#392)

Part of the [compatibility matrix](../compatibility.md); the register entry is `byron-ed25519` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the matrix row for this component.

## Status as recorded

implemented: Byron block headers, delegation certificates (genesis and on-chain), update proposals and votes, and ordinary and redeem transaction witnesses verify with `ByronEd25519`, the semantics of the reference's Byron path (cardano-crypto-wrapper 1.7.0.0 `verifySignatureRaw`/`verifyRedeemSigRaw`, cardano-crypto 1.3.0 `Wallet.verify`, crypton 1.0.6 ed25519-donna `ed25519_sign_open`): S below 2^253 and reduced modulo L, the key read with y modulo p and of any order, R compared as bytes with the canonical encoding of [S]B - [h]A, no cofactor. Shelley and later, including bootstrap witnesses (`Ed25519DSIGN`, `Cardano/Ledger/Keys/Bootstrap.hs:161-169` of cardano-ledger-core 1.21.0.0), stay strict. Prior art: gouroboros `055fb39a` `internal/ed25519byron/verify.go`

## Evidence

`ByronEd25519ReferenceSuite`: 569 generated rows (non-canonical S, small-order A and R, non-canonical A, controls, a seeded mixture) and the 2,219 rows of the strict witness corpus, each with the verdict of the unchanged released libraries (`fixtures/witness-byron`, oracle `scripts/oracle/byron-donna`); the strict profile accepts nothing the Byron one refuses (all rows and 2,000 mutations); `ByronSignatureProfileSuite` (Byron accepts, `Verdicts` refuses); recorded Byron data under both profiles: the whole preprod Byron era (`ByronPreprodSuite`: 68 signatures, 7 genesis certificates, 45 headers, 2 proposals, 14 votes) and the mainnet fixtures (`ByronMainnetSignatureSuite`: 24 signatures, 7 genesis certificates, 8 headers, 8 transaction and 1 redeem witnesses), all accepted by both

## Explicitly unchecked or unconfirmed

recorded mainnet Byron data in the repository is a sample of eight blocks, not the era; the bound for the rest of mainnet Byron rests on an earlier sync from genesis, built from `be476555` (to slot 1,769,881) and `62141bc0` (from there into Shelley), both verifying Byron signatures with `StrictEd25519`, which adopted every mainnet Byron block without a signature failure, and on strict acceptance implying Byron acceptance; a full replay of mainnet Byron from recorded data is not run ([#415](https://github.com/KD5UJC-XYZ/scarab/issues/415)); the harness is built with GHC 9.10.3, not the release's GHC 9.6
