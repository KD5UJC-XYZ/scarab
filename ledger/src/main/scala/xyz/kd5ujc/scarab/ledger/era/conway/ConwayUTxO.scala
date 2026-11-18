// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TxIn, Voter}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraTx,
  AlonzoScriptsNeeded,
  AlonzoUTxO,
  BabbageEraTxOut,
  BabbageUTxO,
  EraTx,
  EraTxBody,
  EraTxError,
  EraTxOut,
  EraUTxO,
  GovAction,
  MaryUTxO,
  MultiAssetValue,
  PParams,
  ShelleyUTxO,
  ScriptsProvided,
  Tx,
  TxBody,
  TxLevel,
  UTxO,
  Val
}
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Conway,
  ConwayCertState,
  Credential,
  KeyHash,
  StakePool,
  Staking
}

/** Conway's unspent outputs read from the outputs the node keeps. */
object ConwayUTxO:
  /** Each stored output read as a Conway output ([[ConwayTxOut.fromStored]]), the first one it
    * refuses refusing the whole: only a damaged store holds such an output.
    */
  def fromStored(
      stored: Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut]
  ): Either[EraTxError, UTxO[Conway]] =
    stored.toVector
      .traverse((in, out) => ConwayTxOut.fromStored(out).map(in -> _))
      .map(outs => UTxO[Conway](outs.toMap))

  /** `conwayProducedValue` (`Conway/UTxO.hs:107-117`, cardano-ledger f649f975): Mary's produced
    * value and the treasury donation.
    */
  def conwayProducedValue(
      pp: PParams[Conway],
      isStakePool: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, Conway]
  ): MultiAssetValue =
    val b = EraTxBody.conway
    val ops = summon[Val[MultiAssetValue]]
    ops.plus(
      MaryUTxO.getProducedMaryValue[Conway](pp, isStakePool, body),
      ops.inject(b.treasuryDonation[TxLevel.Top](body)(using b.top))
    )

  /** `txNonDistinctRefScriptsSize` (`Conway/UTxO.hs:161-170`, cardano-ledger f649f975): the bytes
    * of the reference scripts of the spent and reference inputs, an input in both counted once and
    * a script as many times as outputs carry it, added up as the reference's 64-bit `Int`.
    */
  def txNonDistinctRefScriptsSize[L <: TxLevel](utxo: UTxO[Conway], tx: Tx[L, Conway])(using
      l: EraTxBody.conway.Level[L]
  ): Long =
    val b = EraTxBody.conway
    val body = EraTx.conway.body[L](tx)(using l)
    val inputs: Set[TxIn] = b.referenceInputs[L](body)(using l) ++ b.inputs[L](body)(using l)
    val o = summon[BabbageEraTxOut[Conway]]
    BabbageUTxO
      .getReferenceScriptsNonDistinct(utxo, inputs)
      .map((_, script) => o.scripts.originalBytes(script).size.toLong)
      .sum

  /** `getConwayMinFeeTxUtxo` (`Conway/UTxO.hs:146-158`, cardano-ledger f649f975): the minimum fee
    * with the size of the reference scripts the UTxO set gives the transaction.
    */
  def getConwayMinFeeTxUtxo[L <: TxLevel](
      pp: PParams[Conway],
      tx: Tx[L, Conway],
      utxo: UTxO[Conway]
  )(using
      l: EraTxBody.conway.Level[L]
  ): Coin =
    EraTx.conway.getMinFeeTx[L](pp, tx, txNonDistinctRefScriptsSize[L](utxo, tx))(using l)

  /** `getConwayScriptsNeeded` (`Conway/UTxO.hs:62-105`, cardano-ledger f649f975): Alonzo's
    * spending, withdrawing and minting needs, the certificates' by their own position (no
    * duplicate's first index, as before Conway), each voter by its position among the voters
    * (`Map.keys`) with a script credential, and each proposal by its position whose parameter
    * change or treasury withdrawal names a guardrails script.
    */
  def getConwayScriptsNeeded[L <: TxLevel](utxo: UTxO[Conway], body: TxBody[L, Conway])(using
      l: EraTxBody.conway.Level[L]
  ): AlonzoScriptsNeeded[Conway] =
    val b = EraTxBody.conway
    def credScriptHash(credential: xyz.kd5ujc.scarab.ledger.Credential): Option[Bytes] =
      credential match
        case xyz.kd5ujc.scarab.ledger.Credential.Script(h) => Some(h)
        case xyz.kd5ujc.scarab.ledger.Credential.Key(_)    => None
    val certifyingScriptsNeeded = AlonzoScriptsNeeded[Conway](
      AlonzoUTxO
        .zipAsIxItem(b.certs[L](body)) { asIxItem =>
          b.certsOf
            .getScriptWitnessTxCert(asIxItem.item)
            .map(hash => (ConwayPlutusPurpose.ConwayCertifying(asIxItem), hash))
        }
        .flatten
    )
    val votingScriptsNeeded = AlonzoScriptsNeeded[Conway](
      AlonzoUTxO
        .zipAsIxItem(b.votingProcedures[L](body).unVotingProcedures.keys) { asIxItem =>
          val voterScript = asIxItem.item match
            case Voter.CommitteeHot(credential) => credScriptHash(credential)
            case Voter.DRepVoter(credential)    => credScriptHash(credential)
            case Voter.Pool(_)                  => None
          voterScript.map(hash => (ConwayPlutusPurpose.ConwayVoting(asIxItem), hash))
        }
        .flatten
    )
    val proposingScriptsNeeded = AlonzoScriptsNeeded[Conway](
      AlonzoUTxO
        .zipAsIxItem(b.proposalProcedures[L](body).toVector) { asIxItem =>
          val guardrails = asIxItem.item.pProcGovAction match
            case GovAction.ParameterChange(_, _, Some(guardrailsScriptHash)) =>
              Some(guardrailsScriptHash.bytes)
            case GovAction.TreasuryWithdrawals(_, Some(guardrailsScriptHash)) =>
              Some(guardrailsScriptHash.bytes)
            case _ => None
          guardrails.map(hash => (ConwayPlutusPurpose.ConwayProposing(asIxItem), hash))
        }
        .flatten
    )
    AlonzoUTxO.getSpendingScriptsNeeded[Conway, L](utxo, body) ++
      AlonzoUTxO.getWithdrawingScriptsNeeded[Conway, L](body) ++
      certifyingScriptsNeeded ++
      AlonzoUTxO.getMintingScriptsNeeded[Conway, L](body) ++
      votingScriptsNeeded ++
      proposingScriptsNeeded

  /** `getConwayWitsVKeyNeeded` (`Conway/UTxO.hs:172-180`, cardano-ledger f649f975): Shelley's keys
    * without the governance ones, the required signers, and the voters' keys.
    */
  def getConwayWitsVKeyNeeded[L <: TxLevel](utxo: UTxO[Conway], body: TxBody[L, Conway])(using
      l: EraTxBody.conway.Level[L]
  ): Set[Bytes] =
    val b = EraTxBody.conway
    ShelleyUTxO.getShelleyWitsVKeyNeededNoGov[Conway, L](utxo, body) ++
      b.reqSignerHashes[L](body) ++ voterWitnesses[L](body)

  /** `voterWitnesses` (`Conway/UTxO.hs:182-194`, cardano-ledger f649f975): the key of each voter a
    * key witnesses: a committee member's or a DRep's key credential, and a pool's id.
    */
  def voterWitnesses[L <: TxLevel](body: TxBody[L, Conway])(using
      l: EraTxBody.conway.Level[L]
  ): Set[Bytes] =
    EraTxBody.conway
      .votingProcedures[L](body)
      .unVotingProcedures
      .keySet
      .flatMap {
        case Voter.CommitteeHot(xyz.kd5ujc.scarab.ledger.Credential.Key(h)) => Some(h)
        case Voter.DRepVoter(xyz.kd5ujc.scarab.ledger.Credential.Key(h))    => Some(h)
        case Voter.Pool(poolId)                                             => Some(poolId)
        case Voter.CommitteeHot(_) | Voter.DRepVoter(_)                     => None
      }

  val instance: EraUTxO[Conway] = new EraUTxO[Conway]:
    override val txOf: EraTx.conway.type = EraTx.conway
    // `getConsumedValue = getConsumedMaryValue`, `Conway/UTxO.hs:122`, cardano-ledger f649f975
    override def getConsumedValue[L <: TxLevel](
        pp: PParams[Conway],
        lookupStakingDeposit: Credential[Staking] => Option[Coin],
        utxo: UTxO[Conway],
        body: TxBody[L, Conway]
    )(using l: txOf.bodyOf.Level[L]): MultiAssetValue =
      MaryUTxO.getConsumedMaryValue[Conway, L](pp, lookupStakingDeposit, utxo, body)(using
        txOf.bodyOf,
        l
      )
    // `getProducedValue = conwayProducedValue`, `Conway/UTxO.hs:124`, cardano-ledger f649f975
    override def getProducedValue(
        pp: PParams[Conway],
        isPoolRegistered: KeyHash[StakePool] => Boolean,
        body: TxBody[TxLevel.Top, Conway]
    ): MultiAssetValue = conwayProducedValue(pp, isPoolRegistered, body)
    // `getScriptsProvided = getBabbageScriptsProvided`, `Conway/UTxO.hs:126`, cardano-ledger
    // f649f975
    override def getScriptsProvided[L <: TxLevel](utxo: UTxO[Conway], tx: Tx[L, Conway])(using
        l: txOf.bodyOf.Level[L]
    ): ScriptsProvided[Conway] =
      BabbageUTxO.getBabbageScriptsProvided[Conway, L](utxo, tx)(using txOf, txOf.bodyOf, l, l)

    // `getScriptsNeeded = getConwayScriptsNeeded`, `Conway/UTxO.hs:128`, cardano-ledger f649f975
    override def getScriptsNeeded[L <: TxLevel](utxo: UTxO[Conway], body: TxBody[L, Conway])(using
        l: txOf.bodyOf.Level[L]
    ): AlonzoScriptsNeeded[Conway] = getConwayScriptsNeeded[L](utxo, body)(using l)

    // `getScriptsHashesNeeded = getAlonzoScriptsHashesNeeded`, `Conway/UTxO.hs:130`,
    // cardano-ledger f649f975
    override def getScriptsHashesNeeded(scriptsNeeded: AlonzoScriptsNeeded[Conway]): Set[Bytes] =
      AlonzoUTxO.getAlonzoScriptsHashesNeeded(scriptsNeeded)

    // `getWitsVKeyNeeded _ = getConwayWitsVKeyNeeded`, `Conway/UTxO.hs:132`, cardano-ledger
    // f649f975: Conway's keys need no certificate state
    override def getWitsVKeyNeeded[L <: TxLevel](
        certState: ConwayCertState,
        utxo: UTxO[Conway],
        body: TxBody[L, Conway]
    )(using l: txOf.bodyOf.Level[L]): Set[Bytes] = getConwayWitsVKeyNeeded[L](utxo, body)(using l)

    // `getMinFeeTxUtxo = getConwayMinFeeTxUtxo`, `Conway/UTxO.hs:134`, cardano-ledger f649f975
    override def getMinFeeTxUtxo[L <: TxLevel](
        pp: PParams[Conway],
        tx: Tx[L, Conway],
        utxo: UTxO[Conway]
    )(using l: txOf.bodyOf.Level[L]): Coin = getConwayMinFeeTxUtxo[L](pp, tx, utxo)(using l)
