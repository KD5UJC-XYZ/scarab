// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.ledger.state.{Allegra, Babbage, Conway, Dijkstra, EraTag, Mary}

// The reference's type families, one match type each with one case per era (cardano-ledger
// f649f975 `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs`). Each era's cases come with that
// era's types; a family does not reduce at an era that has no case, so a rule that names one there
// does not compile.

/** The value of era `E` (`type family Value era`, `Core.hs:464`, cardano-ledger f649f975): lovelace
  * up to Allegra, lovelace and native assets from Mary.
  */
type Value[E <: EraTag] = E match
  case Allegra  => AdaValue
  case Mary     => MultiAssetValue
  case Babbage  => MultiAssetValue
  case Conway   => MultiAssetValue
  case Dijkstra => MultiAssetValue

/** The transaction output of era `E` (`type TxOut era`, `Core.hs:297-318`, cardano-ledger
  * f649f975): one nominal type per era, an opaque type over the representation the reference
  * shares.
  */
type TxOut[E <: EraTag] = E match
  case Allegra  => allegra.AllegraTxOut
  case Mary     => mary.MaryTxOut
  case Babbage  => babbage.BabbageTxOut
  case Conway   => conway.ConwayTxOut
  case Dijkstra => dijkstra.DijkstraTxOut

/** The transaction body of era `E` at level `L` (`data TxBody l era`, `Core.hs:223-241`,
  * cardano-ledger f649f975).
  */
type TxBody[L <: TxLevel, E <: EraTag] = E match
  case Babbage  => AtTop[L, babbage.BabbageTxBody]
  case Conway   => AtTop[L, conway.ConwayTxBody]
  case Dijkstra => dijkstra.DijkstraTxBody[L]

/** The certificate of era `E` (`type TxCert era`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/TxCert.hs:58`, cardano-ledger f649f975).
  */
type TxCert[E <: EraTag] = E match
  case Babbage  => babbage.BabbageTxCert
  case Conway   => conway.ConwayTxCert
  case Dijkstra => dijkstra.DijkstraTxCert

/** The native script of era `E` (`type NativeScript era`, `Core.hs:585`, cardano-ledger f649f975):
  * `Timelock era` from Allegra (`eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:332`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Scripts.hs:74`).
  */
type NativeScript[E <: EraTag] = E match
  case Allegra  => allegra.AllegraTimelock
  case Mary     => mary.MaryTimelock
  case Babbage  => babbage.BabbageTimelock
  case Conway   => conway.ConwayTimelock
  case Dijkstra => dijkstra.DijkstraNativeScript

/** The script of era `E` (`type Script era`, `Core.hs:583`, cardano-ledger f649f975): the native
  * script up to Mary (`Allegra/Scripts.hs:331`,
  * `eras/mary/impl/src/Cardano/Ledger/Mary/Scripts.hs:21`), a native or a Plutus script from Alonzo
  * (`AlonzoScript era`, `eras/babbage/impl/src/Cardano/Ledger/Babbage/Scripts.hs:41`,
  * `Conway/Scripts.hs:73`).
  */
type Script[E <: EraTag] = E match
  case Allegra  => allegra.AllegraTimelock
  case Mary     => mary.MaryTimelock
  case Babbage  => babbage.BabbageScript
  case Conway   => conway.ConwayScript
  case Dijkstra => dijkstra.DijkstraScript

/** The Plutus script of era `E` (`data PlutusScript era`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:170`, cardano-ledger f649f975): the
  * languages the era has, and no other (`Babbage/Scripts.hs:57-59`, `Conway/Scripts.hs:90-93`).
  */
type PlutusScript[E <: EraTag] = E match
  case Babbage  => babbage.BabbagePlutusScript
  case Conway   => conway.ConwayPlutusScript
  case Dijkstra => dijkstra.DijkstraPlutusScript

/** The witness set of era `E` (`type TxWits era`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:516`, cardano-ledger f649f975).
  */
type TxWits[E <: EraTag] = E match
  case Babbage  => babbage.BabbageTxWits
  case Conway   => conway.ConwayTxWits
  case Dijkstra => dijkstra.DijkstraTxWits

/** The Plutus purpose of era `E` in the form `F` (`type PlutusPurpose f era`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:172`, cardano-ledger f649f975).
  */
type PlutusPurpose[F[_, _], E <: EraTag] = E match
  case Babbage  => AlonzoPlutusPurpose[F, Babbage]
  case Conway   => conway.ConwayPlutusPurpose[F]
  case Dijkstra => dijkstra.DijkstraPlutusPurpose[F]

/** `ScriptsNeeded era` (`State/UTxO.hs:243-245`, cardano-ledger f649f975): what UTXOW needs to find
  * the scripts a transaction must run, by era. Babbage's and Conway's are Alonzo's form (`type
  * ScriptsNeeded BabbageEra = AlonzoScriptsNeeded BabbageEra`, `Babbage/UTxO.hs:44`; `type
  * ScriptsNeeded ConwayEra = AlonzoScriptsNeeded ConwayEra`, `Conway/UTxO.hs:120`), and so is
  * Dijkstra's (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/UTxO.hs:149`, cardano-ledger
  * 9df9f98c).
  */
type ScriptsNeeded[E <: EraTag] = E match
  case Babbage  => AlonzoScriptsNeeded[Babbage]
  case Conway   => AlonzoScriptsNeeded[Conway]
  case Dijkstra => AlonzoScriptsNeeded[Dijkstra]

/** The auxiliary data of era `E` (`type TxAuxData era`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:483`, cardano-ledger f649f975).
  */
type TxAuxData[E <: EraTag] = E match
  case Babbage  => babbage.BabbageTxAuxData
  case Conway   => conway.ConwayTxAuxData
  case Dijkstra => dijkstra.DijkstraTxAuxData

/** The transaction of era `E` at level `L` (`type Tx l era`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:167`, cardano-ledger f649f975).
  */
type Tx[L <: TxLevel, E <: EraTag] = E match
  case Babbage  => AtTop[L, babbage.BabbageTx]
  case Conway   => AtTop[L, conway.ConwayTx]
  case Dijkstra => dijkstra.DijkstraTx[L]

/** The transaction of era `E` at level `L` annotated with what the ledger state gives it (`type
  * StAnnTx (l :: TxLevel) era`, `Core.hs:180`, cardano-ledger f649f975): Babbage's and Conway's are
  * Alonzo's, of a top-level transaction only (`type StAnnTx l BabbageEra = AlonzoStAnnTx l
  * BabbageEra`, `Babbage/Tx.hs:44`; `type StAnnTx l ConwayEra = AlonzoStAnnTx l ConwayEra`,
  * `Conway/Tx.hs:67`); Dijkstra's is its own, of both levels (`type StAnnTx l DijkstraEra =
  * DijkstraStAnnTx l DijkstraEra`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:221`,
  * cardano-ledger 9df9f98c).
  */
type StAnnTx[L <: TxLevel, E <: EraTag] = E match
  case Babbage  => AtTop[L, AlonzoStAnnTx[Babbage]]
  case Conway   => AtTop[L, AlonzoStAnnTx[Conway]]
  case Dijkstra => dijkstra.DijkstraStAnnTx[L]

/** The cache annotated transactions share (`type StAnnTxCache era`, `Core.hs:183`, cardano-ledger
  * f649f975): Babbage's and Conway's the runnable scripts by hash (`Babbage/Tx.hs:46`,
  * `Conway/Tx.hs:69`), and so is Dijkstra's
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:223`, cardano-ledger 9df9f98c).
  */
type StAnnTxCache[E <: EraTag] = E match
  case Babbage  => Map[xyz.kd5ujc.scarab.codec.Bytes, SupportedPlutusRunnable[Babbage]]
  case Conway   => Map[xyz.kd5ujc.scarab.codec.Bytes, SupportedPlutusRunnable[Conway]]
  case Dijkstra => Map[xyz.kd5ujc.scarab.codec.Bytes, SupportedPlutusRunnable[Dijkstra]]

/** The governance state of era `E` (`type GovState era`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/Governance.hs:66`, cardano-ledger f649f975):
  * `ConwayGovState era` from Conway (`Conway/Governance.hs:406`, cardano-ledger f649f975;
  * `Dijkstra/Governance.hs:29`, cardano-ledger 9df9f98c), the record [[EraGov]] has lenses onto.
  * The ledger state holds Dijkstra's (`xyz.kd5ujc.scarab.ledger.state.GovState`), and Conway's as
  * the node's flat record.
  */
type GovState[E <: EraTag] = E match
  case Conway   => ConwayGovState[Conway]
  case Dijkstra => ConwayGovState[Dijkstra]
