// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoScriptsNeeded,
  AlonzoUTxO,
  AsItem,
  AsIxItem,
  BabbageUTxO,
  BothLevels,
  CollectError,
  Data,
  DijkstraEraUTxO,
  EraScript,
  EraTx,
  EraTxBody,
  MaryUTxO,
  MultiAssetValue,
  PParams,
  PlutusWithContext,
  ScriptsProvided,
  SubOnly,
  Tx,
  TxBody,
  TxLevel,
  UTxO,
  UTxOs,
  Val
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayUTxO
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  ConwayCertState,
  Credential,
  Dijkstra,
  Guard,
  KeyHash,
  StakePool,
  Staking
}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.plutus.PlutusLanguage as Lang

/** Dijkstra's functions of the UTxO set and its instances of the UTxO classes
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/UTxO.hs`, cardano-ledger 9df9f98c). The
  * reference writes them for any era with Dijkstra's classes whose levels are both (`STxLevel l era
  * ~ STxBothLevels l era`); here they are written for Dijkstra, as Conway's `conwayProducedValue`
  * is for Conway, and call the shared functions of the earlier eras.
  */
object DijkstraUTxO:
  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private val ops = summon[Val[MultiAssetValue]]

  private def subBodies(top: TxBody[TxLevel.Top, Dijkstra]): Vector[TxBody[TxLevel.Sub, Dijkstra]] =
    b.subTransactions(top).toVector.map(_.body)

  /** `dijkstraConsumed` (`UTxO.hs:68-77`, cardano-ledger 9df9f98c): what the body consumes with no
    * account's deposit looked up, as Dijkstra's refunds are the ones its certificates declare.
    */
  def dijkstraConsumed[L <: TxLevel](
      pp: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
      body: TxBody[L, Dijkstra]
  )(using l: BothLevels[L]): MultiAssetValue =
    instance.getConsumedValue[L](pp, _ => None, utxo, body)

  /** `getConsumedDijkstraValue` (`UTxO.hs:79-104`, cardano-ledger 9df9f98c): Mary's consumed value
    * of the body, and at the top level each sub-transaction's consumed value added.
    */
  def getConsumedDijkstraValue[L <: TxLevel](
      pp: PParams[Dijkstra],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      utxo: UTxO[Dijkstra],
      body: TxBody[L, Dijkstra]
  )(using l: BothLevels[L]): MultiAssetValue =
    def txBodyConsumedValue[M <: TxLevel](txBody: TxBody[M, Dijkstra])(using
        BothLevels[M]
    ): MultiAssetValue =
      MaryUTxO.getConsumedMaryValue[Dijkstra, M](pp, lookupStakingDeposit, utxo, txBody)(using b)
    l match
      case BothLevels.Top =>
        subBodies(body).foldLeft(txBodyConsumedValue[TxLevel.Top](body))((total, sub) =>
          ops.plus(
            total,
            instance.getConsumedValue[TxLevel.Sub](pp, lookupStakingDeposit, utxo, sub)(using
              BothLevels.Sub
            )
          )
        )
      case BothLevels.Sub => txBodyConsumedValue[TxLevel.Sub](body)

  /** `localProducedValue` (`UTxO.hs:130-146`, cardano-ledger 9df9f98c): what one body of a batch
    * produces that is its own, the fee and the certificates' deposits aside: its outputs, its
    * treasury donation, its proposals' deposits, the assets it burns and its direct deposits.
    */
  def localProducedValue[L <: TxLevel](pp: PParams[Dijkstra], txBody: TxBody[L, Dijkstra])(using
      l: BothLevels[L]
  ): MultiAssetValue =
    given xyz.kd5ujc.scarab.ledger.era.EraTxOut[Dijkstra] = b.outputsOf
    Vector(
      UTxOs.sumAllValue(b.outputs[L](txBody)),
      ops.inject(b.treasuryDonation[L](txBody)),
      ops.inject(b.conwayProposalsDeposits[L](pp, txBody)),
      MaryUTxO.burnedMultiAssets[Dijkstra, L](txBody)(using b),
      ops.inject(Coin(b.directDeposits[L](txBody).valuesIterator.map(_.value).sum))
    ).foldLeft(ops.zero)(ops.plus)

  /** `dijkstraProducedValue` (`UTxO.hs:106-128`, cardano-ledger 9df9f98c): each body's own produced
    * value, the fee, and the deposits of the batch's certificates, the sub-transactions' first and
    * then the top level's, counted together so that a pool registered earlier in the batch pays no
    * deposit again.
    */
  def dijkstraProducedValue(
      pp: PParams[Dijkstra],
      isRegPoolId: KeyHash[StakePool] => Boolean,
      topTxBody: TxBody[TxLevel.Top, Dijkstra]
  ): MultiAssetValue =
    val subs = subBodies(topTxBody)
    val batchTxCerts =
      subs.flatMap(b.certs[TxLevel.Sub](_)(using BothLevels.Sub)) ++ b.certs[TxLevel.Top](topTxBody)
    Vector(
      localProducedValue[TxLevel.Top](pp, topTxBody),
      subs
        .map(localProducedValue[TxLevel.Sub](pp, _)(using BothLevels.Sub))
        .foldLeft(ops.zero)(ops.plus),
      ops.inject(b.fee(topTxBody)),
      ops.inject(b.certsOf.getTotalDepositsTxCerts(pp, isRegPoolId, batchTxCerts))
    ).foldLeft(ops.zero)(ops.plus)

  /** `getDijkstraScriptsProvided` (`UTxO.hs:165-186`, cardano-ledger 9df9f98c): Babbage's scripts
    * provided, and at the top level each sub-transaction's too, the top level's kept where both
    * have a hash (`Map.unions`).
    */
  def getDijkstraScriptsProvided[L <: TxLevel](utxo: UTxO[Dijkstra], tx: Tx[L, Dijkstra])(using
      l: BothLevels[L]
  ): ScriptsProvided[Dijkstra] =
    def babbage[M <: TxLevel](x: Tx[M, Dijkstra])(using m: BothLevels[M]) =
      BabbageUTxO.getBabbageScriptsProvided[Dijkstra, M](utxo, x)(using t, b, m, m, b.outputsOf)
    l match
      case BothLevels.Top =>
        ScriptsProvided(
          b.subTransactions(tx.body)
            .toVector
            .map(sub => babbage[TxLevel.Sub](sub)(using BothLevels.Sub).unScriptsProvided)
            .foldLeft(babbage[TxLevel.Top](tx).unScriptsProvided)((all, sub) => sub ++ all)
        )
      case BothLevels.Sub => babbage[TxLevel.Sub](tx)

  /** `getDijkstraScriptsNeeded` (`UTxO.hs:188-198`, cardano-ledger 9df9f98c): Conway's scripts
    * needed, then each guard that is a script, by its position among the guards.
    */
  def getDijkstraScriptsNeeded[L <: TxLevel](utxo: UTxO[Dijkstra], txb: TxBody[L, Dijkstra])(using
      l: BothLevels[L]
  ): AlonzoScriptsNeeded[Dijkstra] =
    val s = EraScript.dijkstra
    val guardingScriptsNeeded = AlonzoScriptsNeeded[Dijkstra](
      AlonzoUTxO
        .zipAsIxItem(b.guards[L](txb).toVector) { case AsIxItem(idx, cred) =>
          credScriptHash(cred).map(sh => (s.mkGuardingPurpose(AsIxItem(idx, sh)), sh.bytes))
        }
        .flatten
    )
    ConwayUTxO.getConwayScriptsNeeded[Dijkstra, L](utxo, txb)(using
      b,
      l,
      s
    ) ++ guardingScriptsNeeded

  private def credScriptHash(c: Credential[Guard]) = c match
    case Credential.ScriptHashObj(h) => Some(h)
    case Credential.KeyHashObj(_)    => None

  /** `getDijkstraSupplementalDataHashes` (`UTxO.hs:213-230`, cardano-ledger 9df9f98c): Babbage's
    * supplemental datums, and at the top level each sub-transaction's too.
    */
  def getDijkstraSupplementalDataHashes[L <: TxLevel](
      utxo: UTxO[Dijkstra],
      txBody: TxBody[L, Dijkstra]
  )(using l: BothLevels[L]): Set[Bytes] =
    l match
      case BothLevels.Top =>
        subBodies(txBody).foldLeft(
          BabbageUTxO.getBabbageSupplementalDataHashes[Dijkstra, TxLevel.Top](utxo, txBody)(using b)
        )((all, sub) =>
          all ++ BabbageUTxO.getBabbageSupplementalDataHashes[Dijkstra, TxLevel.Sub](utxo, sub)(
            using
            b,
            BothLevels.Sub
          )
        )
      case BothLevels.Sub =>
        BabbageUTxO.getBabbageSupplementalDataHashes[Dijkstra, TxLevel.Sub](utxo, txBody)(using b)

  /** `batchNonDistinctRefScriptsSize` (`UTxO.hs:300-314`, cardano-ledger 9df9f98c): the size of the
    * reference scripts of a top-level transaction and of each of its sub-transactions, added up.
    */
  def batchNonDistinctRefScriptsSize(utxo: UTxO[Dijkstra], tx: Tx[TxLevel.Top, Dijkstra]): Long =
    b.subTransactions(tx.body)
      .toVector
      .map(sub =>
        ConwayUTxO.txNonDistinctRefScriptsSize[Dijkstra, TxLevel.Sub](utxo, sub)(using
          t,
          b,
          BothLevels.Sub,
          BothLevels.Sub
        )
      )
      .foldLeft(
        ConwayUTxO.txNonDistinctRefScriptsSize[Dijkstra, TxLevel.Top](utxo, tx)(using t, b)
      )(
        _ + _
      )

  /** `instance EraUTxO DijkstraEra`, `instance AlonzoEraUTxO DijkstraEra` and `instance
    * DijkstraEraUTxO DijkstraEra` (`UTxO.hs:148-163, 200-211, 291-298`, cardano-ledger 9df9f98c).
    */
  lazy val instance: DijkstraEraUTxO[Dijkstra] { val txOf: EraTx.dijkstra.type } =
    new DijkstraEraUTxO[Dijkstra]:
      override val txOf: EraTx.dijkstra.type = EraTx.dijkstra
      // `getConsumedValue = getConsumedDijkstraValue`, `UTxO.hs:151`, cardano-ledger 9df9f98c
      override def getConsumedValue[L <: TxLevel](
          pp: PParams[Dijkstra],
          lookupStakingDeposit: Credential[Staking] => Option[Coin],
          utxo: UTxO[Dijkstra],
          body: TxBody[L, Dijkstra]
      )(using l: BothLevels[L]): MultiAssetValue =
        getConsumedDijkstraValue[L](pp, lookupStakingDeposit, utxo, body)
      // `getProducedValue = dijkstraProducedValue`, `UTxO.hs:153`, cardano-ledger 9df9f98c
      override def getProducedValue(
          pp: PParams[Dijkstra],
          isPoolRegistered: KeyHash[StakePool] => Boolean,
          body: TxBody[TxLevel.Top, Dijkstra]
      ): MultiAssetValue = dijkstraProducedValue(pp, isPoolRegistered, body)
      // `getScriptsProvided = getDijkstraScriptsProvided`, `UTxO.hs:155`, cardano-ledger 9df9f98c
      override def getScriptsProvided[L <: TxLevel](utxo: UTxO[Dijkstra], tx: Tx[L, Dijkstra])(using
          l: BothLevels[L]
      ): ScriptsProvided[Dijkstra] = getDijkstraScriptsProvided[L](utxo, tx)
      // `getScriptsNeeded = getDijkstraScriptsNeeded`, `UTxO.hs:157`, cardano-ledger 9df9f98c
      override def getScriptsNeeded[L <: TxLevel](utxo: UTxO[Dijkstra], body: TxBody[L, Dijkstra])(
          using l: BothLevels[L]
      ): AlonzoScriptsNeeded[Dijkstra] = getDijkstraScriptsNeeded[L](utxo, body)
      // `getScriptsHashesNeeded = getAlonzoScriptsHashesNeeded`, `UTxO.hs:159`, cardano-ledger 9df9f98c
      override def getScriptsHashesNeeded(
          scriptsNeeded: AlonzoScriptsNeeded[Dijkstra]
      ): Set[Bytes] =
        AlonzoUTxO.getAlonzoScriptsHashesNeeded(scriptsNeeded)
      // `getWitsVKeyNeeded _ = getConwayWitsVKeyNeeded`, `UTxO.hs:161`, cardano-ledger 9df9f98c
      override def getWitsVKeyNeeded[L <: TxLevel](
          certState: ConwayCertState,
          utxo: UTxO[Dijkstra],
          body: TxBody[L, Dijkstra]
      )(using l: BothLevels[L]): Set[Bytes] =
        ConwayUTxO.getConwayWitsVKeyNeeded[Dijkstra, L](utxo, body)(using b, l)
      // `getMinFeeTxUtxo = getConwayMinFeeTxUtxo`, `UTxO.hs:163`, cardano-ledger 9df9f98c
      override def getMinFeeTxUtxo[L <: TxLevel](
          pp: PParams[Dijkstra],
          tx: Tx[L, Dijkstra],
          utxo: UTxO[Dijkstra]
      )(using l: BothLevels[L]): Coin =
        ConwayUTxO.getConwayMinFeeTxUtxo[Dijkstra, L](pp, tx, utxo)(using t, b, l, l)
      // `getSupplementalDataHashes = getDijkstraSupplementalDataHashes`, `UTxO.hs:201`, cardano-ledger 9df9f98c
      override def getSupplementalDataHashes[L <: TxLevel](
          utxo: UTxO[Dijkstra],
          txBody: TxBody[L, Dijkstra]
      )(using l: BothLevels[L]): Set[Bytes] = getDijkstraSupplementalDataHashes[L](utxo, txBody)
      // `getSpendingDatum = getBabbageSpendingDatum`, `UTxO.hs:203`, cardano-ledger 9df9f98c
      override def getSpendingDatum[L <: TxLevel](
          utxo: UTxO[Dijkstra],
          tx: Tx[L, Dijkstra],
          sp: DijkstraPlutusPurpose[AsItem]
      )(using l: BothLevels[L]): Either[PlutusData.DecodeError, Option[Data]] =
        BabbageUTxO.getBabbageSpendingDatum[Dijkstra, L](utxo, tx, sp)(using
          t,
          l,
          b.outputsOf,
          EraScript.dijkstra
        )
      // the annotated transaction's four, `UTxO.hs:205-211, 232-289`, cardano-ledger 9df9f98c
      override def scriptsProvidedStAnnTx[L <: TxLevel](stAnnTx: DijkstraStAnnTx[L])(using
          BothLevels[L]
      ): ScriptsProvided[Dijkstra] = stAnnTx match
        case s: DijkstraStAnnTx.TopTx => s.dsattScriptsProvided
        case s: DijkstraStAnnTx.SubTx => s.dsastScriptsProvided
      override def scriptsNeededStAnnTx[L <: TxLevel](stAnnTx: DijkstraStAnnTx[L])(using
          BothLevels[L]
      ): AlonzoScriptsNeeded[Dijkstra] = stAnnTx match
        case s: DijkstraStAnnTx.TopTx => s.dsattScriptsNeeded
        case s: DijkstraStAnnTx.SubTx => s.dsastScriptsNeeded
      // the top level's with each sub-transaction's, the batch's together (`UTxO.hs:258-276`, cardano-ledger 9df9f98c)
      override def plutusScriptsWithContextStAnnTx[L <: TxLevel](stAnnTx: DijkstraStAnnTx[L])(using
          BothLevels[L]
      ): Either[NonEmptyList[CollectError[Dijkstra]], List[PlutusWithContext]] = stAnnTx match
        case s: DijkstraStAnnTx.TopTx =>
          // `concat <$> sequence`: the top level's first, a sub-transaction's read only while every
          // one before it was collected, as the reference's lazy list is forced
          s.dsattSubTransactions.foldLeft(s.dsattPlutusScriptsWithContext.value)((collected, sub) =>
            collected.flatMap(pwcs =>
              plutusScriptsWithContextStAnnTx[TxLevel.Sub](sub)(using BothLevels.Sub).map(pwcs ++ _)
            )
          )
        case s: DijkstraStAnnTx.SubTx => s.dsastPlutusScriptsWithContext.value
      override def plutusLanguagesUsedStAnnTx[L <: TxLevel](stAnnTx: DijkstraStAnnTx[L])(using
          BothLevels[L]
      ): Set[Lang] = stAnnTx match
        case s: DijkstraStAnnTx.TopTx => s.dsattPlutusLanguagesUsed
        case s: DijkstraStAnnTx.SubTx => s.dsastPlutusLanguagesUsed
      // `instance DijkstraEraUTxO DijkstraEra`, `UTxO.hs:291-298`, cardano-ledger 9df9f98c
      override def subTransactionsStAnnTx(
          stAnnTx: DijkstraStAnnTx[TxLevel.Top]
      ): List[DijkstraStAnnTx[TxLevel.Sub]] = stAnnTx match
        case s: DijkstraStAnnTx.TopTx => s.dsattSubTransactions
      override def plutusLegacyModeStAnnTx(
          stAnnTx: DijkstraStAnnTx[TxLevel.Top]
      ): PlutusLegacyMode =
        stAnnTx match
          case s: DijkstraStAnnTx.TopTx => s.dsattPlutusLegacyMode
      override def scriptsHashesNeededStAnnTx[L <: TxLevel](stAnnTx: DijkstraStAnnTx[L])(using
          l: SubOnly[L]
      ): Set[Bytes] =
        l match
          case SubOnly.Sub =>
            stAnnTx match
              case s: DijkstraStAnnTx.SubTx => s.dsastScriptsHashesNeeded
