<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Byron

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-byron` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: UTxO with witnesses (Byron Ed25519 profile, #392 row below; Byron signing tags, address roots, network magic), value conservation, linear fee, AVVM and non-AVVM genesis UTxO, delegation certificates, update proposals, votes and endorsements, adoption at the epoch boundary, the transition epoch to Shelley derived from the update state (or `TestShelleyHardForkAtEpoch`), the Byron UTxO carried into the Shelley ledger

## Evidence

real mainnet blocks (AVVM redemption, verification-key transactions with one and two inputs: Merkle proofs, signatures, roots, fees), the preprod Byron era (proposals, votes, derived transition at epoch 4 equal to the network's), synthetic signed cases; devnet e2e Byron to Shelley UTxO comparison; software update registration (#748): the system tags of the installer metadata and the application name are checked as `registerSoftwareUpdate` checks them, in its order, and `ByronSoftwareRegistrationReferenceSuite` requires the verdict of the released `registerProposal` for the 42 cases of `fixtures/reference/byron-software-registration` (oracle `scripts/oracle/byron-update-state`, `register`): bounds in code points, ASCII and control characters, several failures at once, the protocol update refused first, and two block update payloads with a vote

Dated evidence (2026-11-13): only the first 65,536 outputs of a transaction enter the UTxO and the
output balance, as `txOutputUTxO` indexes them with `[0 ..] :: [Word16]` (cardano-ledger f649f975,
`Cardano/Chain/UTxO/UTxO.hs:176-185`, `Validation.hs:202-204`); every output's network magic and
attributes are still checked (`Validation.hs:255`). The address root a witness key is checked
against covers the address's attributes encoded again with minimal heads and lengths
(`checkAddrSpendingData`, `Cardano/Chain/Common/Address.hs:306-313`; `encCBORAttributes`,
`Attributes.hs:192-196`), not the attributes as written. `ByronLedgerSuite` pins both with cases
built from those definitions: 65,537 outputs, and addresses whose attribute maps are written as
the accepted wide-head cases of the [decoding corpus](../../fixtures/reference/conway-decoding/README.md).

Dated evidence (2026-11-14): a transaction's checks run in the order of `updateUTxOTxWitness`
(cardano-ledger f649f975, `Cardano/Chain/UTxO/Validation.hs:373-397`), so that of two faults the one
the reference meets first is reported (#969): the inputs and witnesses, then `validateTxAux`
(`Validation.hs:189-217`: size, minimum fee, output balance, input balance, fee, minimum fee
reached), then `validateTx` (`Validation.hs:247-258`: transaction attributes, each output's
attributes and network magic) and the UTxO union. An output address that does not decode is
refused before any of them, as the reference decodes it with the transaction (`TxOut`'s `decCBOR`,
`Cardano/Chain/UTxO/Tx.hs:216-219`). `ByronLedgerSuite` has one case for each pair of faults whose first
error the earlier order reported differently (26 pairs); the order is read from the pinned source,
not replayed through the reference.

## Explicitly unchecked or unconfirmed

no preprod Byron transaction exists, so the UTxO rules are pinned by mainnet blocks (not contiguous, state not replayed); mainnet-scale UTxO memory and snapshot size not measured; the mempool reads current epoch geometry; the forger still takes the epoch history at start-up (restart after the transition on a derived chain); ledger-lite bootstrap mode needs a configured Shelley epoch; a block fetched from a peer has every output address decoded in full by the transport check (`ByronTransport.check` through `FetchedBlockIntegrity`), before its body proof and ledger admission, as the reference decodes it with the block body; only a path that reaches `ByronLedger.applyTx` without that check would refuse an undecodable output address when the transaction is checked, after the block's own checks and the transactions before it (which production paths do so is not established), where the reference fails to decode the block body (cardano-ledger f649f975, `Cardano/Chain/Block/Body.hs:81-88`, `UTxO/TxPayload.hs:70-71`, `UTxO/TxAux.hs:108-115`, `UTxO/Tx.hs:110-113,216-219`, `Common/Address.hs:172-176`)
