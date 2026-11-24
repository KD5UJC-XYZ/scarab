// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Applicative, Id, Monad}
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, LedgerFailure, TranslationError, TxIn, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.RelLTEQ
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayUTxO
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts, Test, focus}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  ChainAccountState,
  Conway,
  EpochNo,
  EraTag,
  LedgerState,
  Network,
  StakePool,
  Word64
}
import xyz.kd5ujc.scarab.plutus.{ExUnits, ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

// The functions of Shelley's, Alonzo's and Conway's BBODY that the BBODY of each era from Conway
// runs, as the reference writes them (the `Rules/Bbody.hs` of Shelley, Alonzo and Conway, and
// `Shelley/BlockBody/Internal.hs`, at cardano-ledger f649f975 for Conway and 9df9f98c for
// Dijkstra, where they are the same but for the signal's type and the units' `OrdExUnits`), each
// generic in its era over the block, header and parameter classes, with BBODY's environment and
// state, and Conway's BBODY on them.

/** `data BbodyEnv era` (`Shelley/Rules/Bbody.hs:94-97`, cardano-ledger 9df9f98c; `:90-93` at
  * f649f975): the parameters in force and the chain's account.
  */
final case class BbodyEnv[E <: EraTag](bbodyPp: PParams[E], bbodyAccount: ChainAccountState)

/** `data ShelleyBbodyState era = BbodyState (State (EraRule "LEDGERS" era)) BlocksMade`
  * (`Shelley/Rules/Bbody.hs:86-88`, cardano-ledger 9df9f98c; `:82-84` at f649f975): the ledger
  * state and the blocks the pools made in the epoch.
  */
final case class ShelleyBbodyState[E <: EraTag](ls: LedgerState[E], blocksMade: BlocksMade)

object ShelleyBbodyState:
  def ls[E <: EraTag]: Lens[ShelleyBbodyState[E], LedgerState[E]] =
    Lens[ShelleyBbodyState[E], LedgerState[E]](_.ls)(l => s => s.copy(ls = l))

/** Shelley's functions of BBODY (`Shelley/Rules/Bbody.hs`, `Shelley/BlockBody/Internal.hs`,
  * cardano-ledger 9df9f98c).
  */
object ShelleyBbody:
  /** `validateBlockBodySize` (`Shelley/Rules/Bbody.hs:251-272`, cardano-ledger 9df9f98c; `:240-262`
    * at f649f975): the body's size at the protocol version against the size its header declares,
    * widened from `Word32`.
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

  /** `validateBlockBodyHash` (`Shelley/Rules/Bbody.hs:274-295`, cardano-ledger 9df9f98c; `:264-285`
    * at f649f975).
    */
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

  /** `incrBlocks` (`Shelley/BlockBody/Internal.hs:296-314`, cardano-ledger 9df9f98c; `:295-313` at
    * f649f975, `Slot.hs` the same at both): the block counted for its issuer, taken as a pool
    * (`coerceKeyRole`), unless its slot is of the overlay schedule (`isOverlaySlot`,
    * `Slot.hs:117-130`), which a decentralisation of zero never has.
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

/** A test of BBODY's predicates, its failures recorded, each injected into era `E` 's BBODY failure
  * (`?! injectFailure`, `failOnJust`).
  */
private def bbodyTest[F[_]: Applicative, A, E <: EraTag](check: Test[A])(using
    inject: InjectBbodyFailure[A, E],
    judge: Judge[F, BbodyPredFailure[E]]
): F[Unit] =
  Sts.runTest[F, BbodyPredFailure[E]](check.leftMap(_.map(inject.injectFailure)))

/** Alonzo's functions of BBODY (`Alonzo/Rules/Bbody.hs`, cardano-ledger 9df9f98c and f649f975). */
object AlonzoBbody:
  /** `validateExUnits` (`Alonzo/Rules/Bbody.hs:157-175`, cardano-ledger 9df9f98c; `:147-167` at
    * f649f975, where the units are `totExUnits`, the class member's default): the units of the
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

  /** `alonzoBbodyTransition` (`Alonzo/Rules/Bbody.hs:169-214`, cardano-ledger f649f975), every
    * failure recorded and injected into BBODY's ([[InjectBbodyFailure]]), in the reference's order:
    *   - the body's size and hash against its header's (`:194-196`);
    *   - LEDGERS (`:198-210`), at the header's slot and its epoch (`slotToEpochBoundary`,
    *     `Slot.hs:93-97`), with the parameters and the chain's account, nested: `ledgers`, the
    *     era's `EraRule "LEDGERS"` on the BBODY state's ledger state, its failures and events
    *     embedded by the caller ([[EmbedLedgersInBbody]]);
    *   - the block's execution units within `maxBlockExUnits` (`:212`);
    *
    * then the block counted for its issuer (`incrBlocks`, `:214`). The epoch of the slot is
    * `Globals` ' geometry's; one outside `EpochNo` is raised ([[LedgerFailure.Vocab]]).
    */
  def alonzoBbodyTransition[F[_]: Monad, H, E <: EraTag](env: BbodyEnv[E], block: Block[H, E])(
      ledgers: (LedgersEnv[E], Vector[Tx[TxLevel.Top, E]]) => F[Unit]
  )(using
      h: EraBlockHeader[H, E],
      bb: EraBlockBody[E],
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[TxLevel.Top],
      p: AlonzoEraPParams[E],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyBbodyState[E]],
      judge: Judge[F, BbodyPredFailure[E]],
      shelley: InjectBbodyFailure[ShelleyBbodyPredFailure[E], E],
      alonzo: InjectBbodyFailure[AlonzoBbodyPredFailure[E], E],
      defect: Raise[F, LedgerDefect]
  ): F[Unit] =
    val pp = env.bbodyPp
    val txs = bb.txSeqBlockBody(block.blockBody)
    val bhSlot = h.slotNoBlockHeader(block).toBigInt
    for
      _ <- bbodyTest(ShelleyBbody.validateBlockBodySize(block, p.ppProtocolVersionL.get(pp)))
      _ <- bbodyTest(ShelleyBbody.validateBlockBodyHash(block))
      globals <- ask.ask[Globals]
      epoch = globals.epochOf(bhSlot)
      firstSlot = globals.firstSlot(epoch)
      curEpoch <- Sts.lift[F, LedgerDefect, EpochNo](
        EpochNo.fromBigInt(epoch).leftMap(LedgerFailure.Vocab(_))
      )
      _ <- judge.nest(ledgers(LedgersEnv[E](bhSlot, curEpoch, pp, env.bbodyAccount), txs))
      _ <- bbodyTest(
        validateExUnits[E](txs, AlonzoEraPParams.ppMaxBlockExUnitsL[E].get(pp).units)
      )
      _ <- state.modify(s =>
        s.copy(blocksMade = ShelleyBbody.incrBlocks(block, firstSlot, p.ppDG.get(pp), s.blocksMade))
      )
    yield ()

/** Where Conway's BBODY checks the header's protocol version (`HeaderProtVerTooHigh`), by the
  * reference build: on mainnet, or on any network once the parameters' major version is 12 (`netId ==
  * Mainnet || curProtVerMajor >= natVersion @12`, `Conway/Rules/Bbody.hs:308-312`, cardano-ledger
  * f649f975) at 11.1; on mainnet only at 29a7d656 (the 11.2 build) and 9df9f98c (the Leios build),
  * `Conway/Rules/Bbody.hs:326-328` at both. The rule derives it from the build the judgment's
  * `Globals` name ([[of]]), as [[WithdrawalsRule]] is. The second clause of 11.1 is met only by a
  * Conway state whose parameters' major is 12 or more (a hard fork to 12 enacted in Conway, or a
  * cluster whose eras all start at version 12, as the Leios build's devnet of
  * `fixtures/leios/state` does), and then fails no header: the version after 12 is 13, the last
  * (`MaxVersion`, `Binary/Version.hs:63`, cardano-ledger-binary 048b78d2 as at f649f975), past
  * which no header's major decodes, and 13 has none after it.
  */
enum HeaderProtVerGate derives CanEqual:
  /** 11.1: on mainnet, and on any network from major version 12. */
  case MainnetOrFromVersion12

  /** 11.2 and Leios: on mainnet only. */
  case MainnetOnly

  /** Whether the header's protocol version is checked on `netId` with the parameters' major
    * `curProtVerMajor`.
    */
  def checks(netId: Network, curProtVerMajor: Version): Boolean = this match
    case MainnetOrFromVersion12 => netId == Network.Mainnet || curProtVerMajor.value >= 12
    case MainnetOnly            => netId == Network.Mainnet

object HeaderProtVerGate:
  /** The gate of the build `build`. */
  def of(build: ReferenceBuild): HeaderProtVerGate = build match
    case ReferenceBuild.Release11_1                        => MainnetOrFromVersion12
    case ReferenceBuild.Release11_2 | ReferenceBuild.Leios => MainnetOnly

/** Conway's functions of BBODY (`Conway/Rules/Bbody.hs`, cardano-ledger 9df9f98c and f649f975), and
  * Conway's BBODY.
  */
object ConwayBbody:
  /** `totalRefScriptSizeInBlock` (`Conway/Rules/Bbody.hs:371-384`, cardano-ledger 9df9f98c;
    * `:357-370` at f649f975): up to version 10 each transaction's reference scripts against the
    * UTxO set; from 11 against it with the outputs of the transactions before it, a valid one's
    * (`txouts`) or an invalid one's collateral return (`collOuts`), the set's own entry kept where
    * both have one (`Map.union`).
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
          // `Map.union` biased to the set: the outputs it has no entry for added to it, not the set
          // added to them, which would copy the block's set so far at each transaction (#85)
          val updatedUtxo = accUtxo ++ toAdd.unUTxO.filterNot((in, _) => accUtxo.contains(in))
          (updatedUtxo, accSum + ConwayUTxO.txNonDistinctRefScriptsSize(UTxO(accUtxo), tx))
        }
        ._2

  /** `validateBodyRefScriptsSizeTooBig` (`Conway/Rules/Bbody.hs:344-369`, cardano-ledger 9df9f98c;
    * `:329-355` at f649f975): the block's reference scripts within `maxRefScriptSizePerBlock`,
    * widened from `Word32`.
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

  /** `conwayBbodyTransition` (`Conway/Rules/Bbody.hs:270-316`, cardano-ledger f649f975), every
    * failure recorded and injected into BBODY's ([[InjectBbodyFailure]]), in the reference's order:
    *   - the header's major version at most the one after the parameters' (`succVersion`,
    *     `Binary/Version.hs:153-157`; `checkHeaderProtVerTooHigh`, `:293-305`), where the build's
    *     gate checks it ([[HeaderProtVerGate]], `:308-312`) on the network of the judgment's
    *     `Globals` (`liftSTS $ asks networkId`, `:306`, the Shelley genesis's); a major with no
    *     version after it (`maxBound`) is never too high;
    *   - the block's reference scripts against the UTxO set before LEDGERS (`ls ^. utxoL`, `:314`):
    *     the stored outputs of the transactions' inputs and reference inputs, the only ones
    *     `totalRefScriptSizeInBlock` looks up, read into the era (one the era does not read is
    *     raised, [[UtxoStateDefect]]).
    */
  def conwayBbodyTransition[F[_]: Monad, H, E <: EraTag](
      env: BbodyEnv[E],
      block: Block[H, E]
  )(using
      h: PraosEraBlockHeader[H, E],
      bb: EraBlockBody[E],
      t: AlonzoEraTx[E],
      b: BabbageEraTxBody[E],
      p: ConwayEraPParams[E],
      lt: t.bodyOf.Level[TxLevel.Top],
      lb: b.Level[TxLevel.Top],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyBbodyState[E]],
      judge: Judge[F, BbodyPredFailure[E]],
      conway: InjectBbodyFailure[ConwayBbodyPredFailure[E], E],
      stored: StoredOutputs[F, E]
  ): F[Unit] =
    val pp = env.bbodyPp
    val curProtVerMajor = p.ppProtocolVersionL.get(pp).pvMajor
    val checkHeaderProtVerTooHigh: Option[Mismatch[RelLTEQ, Version]] =
      val bhProtVerMajor = h.protVerBlockHeader(block).pvMajor
      Version
        .mkVersion(BigInt(curProtVerMajor.value) + 1)
        .filter(nextProtVerMajor => bhProtVerMajor.value > nextProtVerMajor.value)
        .map(nextProtVerMajor => Mismatch(bhProtVerMajor, nextProtVerMajor))
    val txs = bb.txSeqBlockBody(block.blockBody)
    for
      globals <- ask.ask[Globals]
      netId = globals.networkId
      _ <-
        if HeaderProtVerGate.of(globals.ledger.referenceBuild).checks(netId, curProtVerMajor) then
          bbodyTest(
            Test.failureOnJust(checkHeaderProtVerTooHigh)(
              new ConwayBbodyPredFailure.HeaderProtVerTooHigh[E](_)
            )
          )
        else Monad[F].unit
      before <- state.get
      utxo <- stored.readAt(
        before.ls.utxo.utxo.utxo,
        txs.foldLeft(Set.empty[TxIn]) { (keys, tx) =>
          val txBody = t.body[TxLevel.Top](tx)
          keys ++ t.bodyOf.inputs[TxLevel.Top](txBody) ++ b.referenceInputs[TxLevel.Top](txBody)
        }
      )
      _ <- bbodyTest(validateBodyRefScriptsSizeTooBig[E](pp, txs, utxo))
    yield ()

  private val t = EraTx.conway
  private val bb = EraBlockBody.conway

  /** BBODY of Conway (`STS (BBODY era)`, `Conway/Rules/Bbody.hs:237-268`, cardano-ledger f649f975):
    * `conwayBbodyTransition >> alonzoBbodyTransition` ([[conwayBbodyTransition]],
    * [[AlonzoBbody.alonzoBbodyTransition]]), LEDGERS Conway's (`ShelleyLedgers.ledgersTransition`,
    * which `EraRule "LEDGERS" ConwayEra` names), with `context`, `verdicts`, `decoder` and
    * `slotToPOSIXTime` for its transactions, its failures and events embedded
    * ([[EmbedLedgersInBbody]], `Embed ledgers (BBODY era)`, `:318-327`). The network the header's
    * version is checked on is the judgment's `Globals` ', as the reference reads it; the context's
    * is LEDGERS's alone. The signal is the reference's `BbodySignal`: a block whose header `H` has
    * the two header classes. LEDGERS raises its own defects. The proposals GOV judges are the
    * caller's, beside the BBODY state (`proposals`), as LEDGER takes them ([[ConwayLedger]]).
    */
  def conwayBbody[F[_]: Monad, H](
      env: BbodyEnv[Conway],
      block: Block[H, Conway],
      context: UtxoContext[Conway],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(using
      h: EraBlockHeader[H, Conway],
      pv: PraosEraBlockHeader[H, Conway],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyBbodyState[Conway]],
      judge: Judge[F, BbodyPredFailure[Conway]],
      events: Tell[F, Chain[BbodyEvent[Conway]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, Conway],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      govDefect: Raise[F, GovDefect],
      proposals: Stateful[F, Proposals[Conway]],
      stored: StoredOutputs[F, Conway]
  ): F[Unit] =
    val embed = EmbedLedgersInBbody.conway
    given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
    given EraBlockBody[Conway] = bb
    for
      _ <- conwayBbodyTransition[F, H, Conway](env, block)
      _ <- AlonzoBbody.alonzoBbodyTransition[F, H, Conway](env, block) { (ledgersEnv, txs) =>
        given Stateful[F, LedgerState[Conway]] = state.focus(ShelleyBbodyState.ls[Conway])
        given Judge[F, LedgersPredFailure[Conway]] = judge.contramap(embed.wrapFailed)
        given Tell[F, Chain[LedgersEvent[Conway]]] = told(events)(embed.wrapEvent)
        ShelleyLedgers.ledgersTransition[F, Conway](
          ledgersEnv,
          txs,
          context,
          verdicts,
          decoder,
          slotToPOSIXTime
        )
      }
    yield ()
