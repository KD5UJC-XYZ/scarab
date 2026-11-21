// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Id, Monad}
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import scala.annotation.unused
import xyz.kd5ujc.scarab.ledger.{
  LedgerDefect,
  LedgerFailure,
  Mismatch,
  Nonce,
  TranslationError,
  TxIn,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraBbodyFailure, DijkstraPurpose}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayUTxO
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraPlutusPurpose
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts, Test, focus}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  ChainAccountState,
  Dijkstra,
  EpochNo,
  EraTag,
  LedgerState,
  StakePool,
  Word64
}
import xyz.kd5ujc.scarab.plutus.{ExUnits, ScriptDecoder, ScriptEvaluator}

// BBODY of Dijkstra as the reference writes it (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Bbody.hs`), with the predicates of
// Shelley's, Alonzo's and Conway's BBODY it runs, each generic in its era over the block, header and
// parameter classes: the body's size and hash against its header's, the header's highest supported
// major version, LEDGERS, the block's execution units and reference scripts, its Peras certificate,
// and the block counted for its issuer.

/** `data BbodyEnv era` (`Shelley/Rules/Bbody.hs:94-97`, cardano-ledger 9df9f98c): the parameters in
  * force and the chain's account.
  */
final case class BbodyEnv[E <: EraTag](bbodyPp: PParams[E], bbodyAccount: ChainAccountState)

/** `data ShelleyBbodyState era = BbodyState (State (EraRule "LEDGERS" era)) BlocksMade`
  * (`Shelley/Rules/Bbody.hs:86-88`, cardano-ledger 9df9f98c): the ledger state and the blocks the
  * pools made in the epoch.
  */
final case class ShelleyBbodyState[E <: EraTag](ls: LedgerState[E], blocksMade: BlocksMade)

object ShelleyBbodyState:
  def ls[E <: EraTag]: Lens[ShelleyBbodyState[E], LedgerState[E]] =
    Lens[ShelleyBbodyState[E], LedgerState[E]](_.ls)(l => s => s.copy(ls = l))

/** Shelley's functions of BBODY (`Shelley/Rules/Bbody.hs`, `Shelley/BlockBody/Internal.hs`,
  * cardano-ledger 9df9f98c).
  */
object ShelleyBbody:
  /** `validateBlockBodySize` (`Shelley/Rules/Bbody.hs:251-272`, cardano-ledger 9df9f98c): the
    * body's size at the protocol version against the size its header declares, widened from
    * `Word32`.
    */
  def validateBlockBodySize[H, E <: EraTag](block: Block[H, E], protVer: ProtVer)(using
      h: EraBlockHeader[H, E],
      b: EraBlockBody[E]
  ): Test[ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[E]] =
    val actualSize = b.blockBodySize(protVer, block.blockBody)
    val sizeInBlockHeader = h.blockBodySizeBlockHeader(block)
    Test.failureUnless(
      actualSize == sizeInBlockHeader,
      new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[E](
        Mismatch(actualSize, sizeInBlockHeader)
      )
    )

  /** `validateBlockBodyHash` (`Shelley/Rules/Bbody.hs:274-295`, cardano-ledger 9df9f98c). */
  def validateBlockBodyHash[H, E <: EraTag](block: Block[H, E])(using
      h: EraBlockHeader[H, E],
      b: EraBlockBody[E]
  ): Test[ShelleyBbodyPredFailure.InvalidBodyHashBBODY[E]] =
    val actualHash = b.hashBlockBody(block.blockBody)
    val hashInBlockHeader = h.blockBodyHashBlockHeader(block)
    Test.failureUnless(
      actualHash.bytes == hashInBlockHeader.bytes,
      new ShelleyBbodyPredFailure.InvalidBodyHashBBODY[E](Mismatch(actualHash, hashInBlockHeader))
    )

  /** `incrBlocks` (`Shelley/BlockBody/Internal.hs:296-314`, cardano-ledger 9df9f98c): the block
    * counted for its issuer, taken as a pool (`coerceKeyRole`), unless its slot is of the overlay
    * schedule (`isOverlaySlot`, `Slot.hs:117-130`), which a decentralisation of zero never has.
    */
  def incrBlocks[H, E <: EraTag](
      block: Block[H, E],
      firstSlot: BigInt,
      d: UnitInterval,
      blocksMade: BlocksMade
  )(using h: EraBlockHeader[H, E]): BlocksMade =
    val bhSlot = h.slotNoBlockHeader(block).toBigInt
    val isOverlay =
      xyz.kd5ujc.scarab.ledger.rules.ShelleyBbody.isOverlaySlot(firstSlot, d.rational, bhSlot)
    if isOverlay then blocksMade
    else
      val hkAsStakePool = h.blockIssuerBlockHeader(block).coerceKeyRole[StakePool]
      BlocksMade(
        blocksMade.blocks.updatedWith(hkAsStakePool)(n =>
          Some(Word64.fromUnsigned(n.fold(1L)(_.bits + 1)))
        )
      )

/** Alonzo's functions of BBODY (`Alonzo/Rules/Bbody.hs`, cardano-ledger 9df9f98c). */
object AlonzoBbody:
  /** `validateExUnits` (`Alonzo/Rules/Bbody.hs:157-175`, cardano-ledger 9df9f98c): the units of the
    * block's transactions (`foldMap getTotalExUnits`) within the parameters' limit, point-wise.
    */
  def validateExUnits[E <: EraTag](txs: Vector[Tx[TxLevel.Top, E]], ppMax: ExUnits)(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[TxLevel.Top]
  ): Test[AlonzoBbodyPredFailure.TooManyExUnits[E]] =
    val txTotal = txs.foldLeft(ExUnits.zero)((acc, tx) => acc + t.getTotalExUnits[TxLevel.Top](tx))
    Test.failureUnless(
      txTotal.fits(ppMax),
      new AlonzoBbodyPredFailure.TooManyExUnits[E](Mismatch(txTotal, ppMax))
    )

/** Conway's functions of BBODY (`Conway/Rules/Bbody.hs`, cardano-ledger 9df9f98c). */
object ConwayBbody:
  /** `totalRefScriptSizeInBlock` (`Conway/Rules/Bbody.hs:371-384`, cardano-ledger 9df9f98c): up to
    * version 10 each transaction's reference scripts against the UTxO set; from 11 against it with
    * the outputs of the transactions before it, a valid one's (`txouts`) or an invalid one's
    * collateral return (`collOuts`), the set's own entry kept where both have one (`Map.union`).
    */
  def totalRefScriptSizeInBlock[E <: EraTag](
      protVer: ProtVer,
      txs: Vector[Tx[TxLevel.Top, E]],
      utxo: UTxO[E]
  )(using
      t: AlonzoEraTx[E],
      b: BabbageEraTxBody[E],
      lt: t.bodyOf.Level[TxLevel.Top],
      lb: b.Level[TxLevel.Top]
  ): Long =
    if protVer.pvMajor.value <= 10 then
      txs.foldLeft(0L)((sum, tx) => sum + ConwayUTxO.txNonDistinctRefScriptsSize(utxo, tx))
    else
      txs
        .foldLeft((utxo.unUTxO, 0L)) { case ((accUtxo, accSum), tx) =>
          val txBody = t.body[TxLevel.Top](tx)
          val toAdd = t.isValid(tx) match
            case IsPhase2Valid.Phase2Valid   => UTxOs.txouts[E, TxLevel.Top](txBody)
            case IsPhase2Valid.Phase2Invalid => BabbageUtxo.collOuts[E](txBody)
          val updatedUtxo = toAdd.unUTxO ++ accUtxo
          (updatedUtxo, accSum + ConwayUTxO.txNonDistinctRefScriptsSize(UTxO(accUtxo), tx))
        }
        ._2

  /** `validateBodyRefScriptsSizeTooBig` (`Conway/Rules/Bbody.hs:344-369`, cardano-ledger 9df9f98c):
    * the block's reference scripts within `maxRefScriptSizePerBlock`, widened from `Word32`.
    */
  def validateBodyRefScriptsSizeTooBig[E <: EraTag](
      pp: PParams[E],
      txs: Vector[Tx[TxLevel.Top, E]],
      utxo: UTxO[E]
  )(using
      t: AlonzoEraTx[E],
      b: BabbageEraTxBody[E],
      p: ConwayEraPParams[E],
      lt: t.bodyOf.Level[TxLevel.Top],
      lb: b.Level[TxLevel.Top]
  ): Test[ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[E]] =
    val totalSize = totalRefScriptSizeInBlock(p.ppProtocolVersionL.get(pp), txs, utxo)
    val maxSize = p.ppMaxRefScriptSizePerBlockG.get(pp).value.toLong
    Test.failureUnless(
      totalSize <= maxSize,
      new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[E](Mismatch(totalSize, maxSize))
    )

/** BBODY of Dijkstra (`Dijkstra/Rules/Bbody.hs`, cardano-ledger 9df9f98c). */
object DijkstraBbody:
  /** `validatePerasCert` (`Dijkstra/BlockBody/Internal.hs:240-246`, cardano-ledger 9df9f98c): the
    * build's placeholder, which refuses every certificate, so that no block carries one.
    */
  def validatePerasCert(@unused nonce: Nonce, @unused cert: PerasCert): Boolean = false

  private val t = EraTx.dijkstra
  private val bb = EraBlockBody.dijkstra
  private val p = ConwayEraPParams.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  /** `dijkstraBbodyTransition` (`Dijkstra/Rules/Bbody.hs:333-402`, cardano-ledger 9df9f98c), every
    * failure recorded and injected into BBODY's ([[InjectBbodyFailure]]), in the reference's order:
    *   - the body's size and hash against its header's;
    *   - the header's highest supported major version at least the parameters' major
    *     (`HeaderProtVerTooLow`, `:366-376`);
    *   - LEDGERS (`:384-390`), with the header's slot and its epoch (`slotToEpochBoundary`,
    *     `Slot.hs:93-97`), the parameters and the chain's account, on the ledger state and the
    *     proposals, its failures and events embedded ([[EmbedLedgersInBbody]]);
    *   - the block's execution units within `maxBlockExUnits` (`:392`);
    *   - the block's reference scripts against the UTxO set before LEDGERS (`ls ^. utxoL`, `:394`):
    *     the stored outputs of the transactions' inputs and reference inputs, the only ones it
    *     looks up, read into the era (one the era does not read is raised, [[UtxoStateDefect]]);
    *   - a Peras certificate, against the header's previous nonce (`:396-400`);
    *
    * then the block counted for its issuer (`incrBlocks`, `:402`).
    *
    * The signal is the reference's `LeiosBbodySignal`: a block whose header `H` has the two header
    * classes. The epoch of the slot is `Globals` ' geometry's; one outside `EpochNo` is raised
    * ([[LedgerFailure.Vocab]]), and LEDGERS raises its own defects.
    */
  def dijkstraBbodyTransition[F[_]: Monad, H](
      env: BbodyEnv[Dijkstra],
      block: Block[H, Dijkstra],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(using
      h: EraBlockHeader[H, Dijkstra],
      l: LeiosEraBlockHeader[H, Dijkstra],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyBbodyState[Dijkstra]],
      proposals: Stateful[F, Proposals[Dijkstra]],
      judge: Judge[F, BbodyPredFailure[Dijkstra]],
      events: Tell[F, Chain[BbodyEvent[Dijkstra]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      govDefect: Raise[F, GovDefect]
  ): F[Unit] =
    val embed = EmbedLedgersInBbody.dijkstra
    val pp = env.bbodyPp
    val protVer = p.ppProtocolVersionL.get(pp)
    val blockBody = block.blockBody
    val txs = bb.txSeqBlockBody(blockBody)
    def test[A](check: Test[A])(using inject: InjectBbodyFailure[A, Dijkstra]): F[Unit] =
      Sts.runTest[F, BbodyPredFailure[Dijkstra]](check.leftMap(_.map(inject.injectFailure)))
    val curProtVerMajor = protVer.pvMajor.value.toLong
    val bhviHighestSupportedMajorVersion =
      l.versionInfoBlockHeader(block).bhviHighestSupportedMajorVersion
    val bhSlot = h.slotNoBlockHeader(block)
    for
      _ <- test(ShelleyBbody.validateBlockBodySize(block, protVer))
      _ <- test(ShelleyBbody.validateBlockBodyHash(block))
      _ <- test(
        Test.failureUnless(
          bhviHighestSupportedMajorVersion >= curProtVerMajor,
          new DijkstraBbodyPredFailure.HeaderProtVerTooLow[Dijkstra](
            Mismatch(bhviHighestSupportedMajorVersion, curProtVerMajor)
          )
        )
      )
      globals <- ask.ask[Globals]
      epoch = globals.epochOf(bhSlot.toBigInt)
      firstSlot = globals.firstSlot(epoch)
      curEpoch <- Sts.lift[F, LedgerDefect, EpochNo](
        EpochNo.fromBigInt(epoch).leftMap(LedgerFailure.Vocab(_))
      )
      before <- state.get
      ls = before.ls
      _ <- judge.nest(
        DijkstraLedgers.ledgersTransition[F](
          LedgersEnv[Dijkstra](bhSlot.toBigInt, curEpoch, pp, env.bbodyAccount),
          txs,
          context,
          verdicts,
          decoder,
          slotToPOSIXTime
        )(using
          summon[Monad[F]],
          ask,
          state.focus(ShelleyBbodyState.ls[Dijkstra]),
          proposals,
          judge.contramap(embed.wrapFailed),
          told(events)(embed.wrapEvent),
          evaluator,
          unavailable,
          stateDefect,
          defect,
          poolDefect,
          govDefect
        )
      )
      _ <- test(
        AlonzoBbody
          .validateExUnits[Dijkstra](txs, AlonzoEraPParams.ppMaxBlockExUnitsL.get(pp).units)
      )
      utxo <- ShelleyUtxo.readStored[F, Dijkstra](
        ShelleyUtxo
          .extractKeys(
            ls.utxo.utxo.utxo,
            txs.foldLeft(Set.empty[TxIn]) { (keys, tx) =>
              val txBody = t.body[TxLevel.Top](tx)
              keys ++ t.bodyOf.inputs[TxLevel.Top](txBody) ++
                t.bodyOf.referenceInputs[TxLevel.Top](txBody)
            }
          )
          ._2
      )
      _ <- test(ConwayBbody.validateBodyRefScriptsSizeTooBig[Dijkstra](pp, txs, utxo))
      _ <- bb
        .perasCertBlockBody(blockBody)
        .fold(Monad[F].unit) { cert =>
          val nonce = l.prevNonceBlockHeader(block)
          test(
            Test.failureUnless(
              validatePerasCert(nonce, cert),
              new DijkstraBbodyPredFailure.PerasCertValidationFailed[Dijkstra](cert, nonce)
            )
          )
        }
      _ <- state.modify(s =>
        s.copy(blocksMade = ShelleyBbody.incrBlocks(block, firstSlot, p.ppDG.get(pp), s.blocksMade))
      )
    yield ()

  /** The failure in the node's flat form (`DijkstraBbodyFailure`), which the flat Dijkstra BBODY
    * reports: glue the comparisons read until the era failures have their own encoders (#796),
    * one-way. LEDGERS's through [[DijkstraLedgerPredFailure.toStored]] with `purpose` and `read`;
    * what that form cannot hold is LEDGER's ([[DijkstraLedgerUnprojected]]).
    */
  def toStored(
      failure: DijkstraBbodyPredFailure[Dijkstra],
      purpose: DijkstraPlutusPurpose[AsItem] => Option[DijkstraPurpose],
      read: GovAction[Dijkstra] => Option[xyz.kd5ujc.scarab.ledger.GovAction]
  ): Either[DijkstraLedgerUnprojected, DijkstraBbodyFailure] = failure match
    case DijkstraBbodyPredFailure.WrongBlockBodySizeBBODY(m) =>
      Right(
        DijkstraBbodyFailure.WrongBlockBodySize(Mismatch(BigInt(m.supplied), BigInt(m.expected)))
      )
    case DijkstraBbodyPredFailure.InvalidBodyHashBBODY(m) =>
      Right(DijkstraBbodyFailure.InvalidBodyHash(Mismatch(m.supplied.bytes, m.expected.bytes)))
    case DijkstraBbodyPredFailure.LedgersFailure(ShelleyLedgersPredFailure.LedgerFailure(f)) =>
      DijkstraLedgerPredFailure
        .toStored(f, purpose, read)
        .map(DijkstraBbodyFailure.LedgersFailure(_))
    case DijkstraBbodyPredFailure.TooManyExUnits(m) => Right(DijkstraBbodyFailure.TooManyExUnits(m))
    case DijkstraBbodyPredFailure.BodyRefScriptsSizeTooBig(m) =>
      Right(DijkstraBbodyFailure.BodyRefScriptsSizeTooBig(m))
    case DijkstraBbodyPredFailure.PerasCertValidationFailed(cert, nonce) =>
      Right(DijkstraBbodyFailure.PerasCertValidationFailed(cert.bytes, nonce))
    case DijkstraBbodyPredFailure.HeaderProtVerTooLow(m) =>
      Right(
        DijkstraBbodyFailure.HeaderProtVerTooLow(Mismatch(BigInt(m.supplied), BigInt(m.expected)))
      )
