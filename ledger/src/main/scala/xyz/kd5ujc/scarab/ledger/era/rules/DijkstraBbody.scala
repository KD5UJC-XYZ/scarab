// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Id, Monad}
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.annotation.unused
import xyz.kd5ujc.scarab.ledger.{
  LedgerDefect,
  LedgerFailure,
  Nonce,
  TranslationError,
  TxIn,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts, Test, focus}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, EpochNo}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

// BBODY of Dijkstra as the reference writes it (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Bbody.hs`), with the predicates of
// Shelley's, Alonzo's and Conway's BBODY it runs (`Bbody.scala`): the body's size and hash against
// its header's, the header's highest supported major version, LEDGERS, the block's execution units
// and reference scripts, its Peras certificate, and the block counted for its issuer.

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
      govDefect: Raise[F, GovDefect],
      deferral: Phase2Deferral[F, Dijkstra]
  ): F[Unit] =
    val embed = EmbedLedgersInBbody.dijkstra
    val pp = env.bbodyPp
    val protVer = p.ppProtocolVersionL.get(pp)
    val blockBody = block.blockBody
    val txs = bb.txSeqBlockBody(blockBody)
    def test[A](check: Test[A])(using inject: InjectBbodyFailure[A, Dijkstra]): F[Unit] =
      Sts.runTest[F, BbodyPredFailure[Dijkstra]](check.leftMap(_.map(inject.injectFailure)))
    val curProtVerMajor = protVer.pvMajor.getVersion32
    val bhviHighestSupportedMajorVersion =
      l.versionInfoBlockHeader(block).bhviHighestSupportedMajorVersion
    val bhSlot = h.slotNoBlockHeader(block)
    for
      _ <- test(ShelleyBbody.validateBlockBodySize(block, protVer))
      _ <- test(ShelleyBbody.validateBlockBodyHash(block))
      _ <- test(
        Test.failureUnless(
          bhviHighestSupportedMajorVersion.value >= curProtVerMajor.value,
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
          govDefect,
          deferral
        )
      )
      _ <- test(
        AlonzoBbody
          .validateExUnits[Dijkstra](txs, AlonzoEraPParams.ppMaxBlockExUnitsL.get(pp).units)
      )
      utxo <- ShelleyUtxo.readStored[F, Dijkstra](
        ShelleyUtxo
          .restrictKeys(
            ls.utxo.utxo.utxo,
            txs.foldLeft(Set.empty[TxIn]) { (keys, tx) =>
              val txBody = t.body[TxLevel.Top](tx)
              keys ++ t.bodyOf.inputs[TxLevel.Top](txBody) ++
                t.bodyOf.referenceInputs[TxLevel.Top](txBody)
            }
          )
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
