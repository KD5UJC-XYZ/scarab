// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  LeiosBlock,
  Mismatch,
  Nonce,
  ProtocolParams,
  TxIn,
  Utxo as NodeUtxo
}
import xyz.kd5ujc.scarab.ledger.dijkstra.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.plutus.ExUnits

/** The state the rules of a Dijkstra transaction run on: the ledger state of Dijkstra, with the
  * node's phase-2 counters beside it ([[Counted]]), as [[ConwayLedger]] is for Conway.
  */
type DijkstraLedger = Counted[LedgerState[Dijkstra]]

/** The sizes and sums the Dijkstra rules compute over a transaction or a block. */
object DijkstraTxMeasures:
  def txIn(in: DijkstraTxIn): TxIn = TxIn(in.id, BigInt(in.index))

  /** `originalBytesSize` of a reference script: a Plutus script's program bytes, or a native
    * script's encoding.
    */
  def scriptSize(script: DijkstraScript): Long = script match
    case DijkstraScript.Native(raw)        => raw.size.toLong
    case DijkstraScript.Plutus(_, program) => program.size.toLong

  /** `txNonDistinctRefScriptsSize` (`Conway/UTxO.hs:166-170`, cardano-ledger 9df9f98c): the
    * reference scripts of the outputs that the set of a body's inputs and reference inputs spends
    * or reads, each counted once per input. `size` gives the size of the reference script of the
    * output an input names, if the set holds it.
    */
  def refScriptsSize(size: TxIn => Option[Long], body: DijkstraTxBody): Long =
    (body.inputs.toSet ++ body.referenceInputs.toSet).iterator
      .flatMap(in => size(txIn(in)))
      .sum

  /** The size of the reference script of an output of the UTxO set, if the set holds it. */
  def inUtxo(utxo: NodeUtxo): TxIn => Option[Long] =
    in => utxo.get(in).map(_.referenceScriptSize.toLong)

  /** `batchNonDistinctRefScriptsSize` (`Dijkstra/UTxO.hs:301-314`): the transaction's and each of
    * its sub-transactions'.
    */
  def batchRefScriptsSize(utxo: NodeUtxo, tx: DijkstraTx): Long =
    refScriptsSize(inUtxo(utxo), tx.body) +
      tx.body.subTransactions.map(s => refScriptsSize(inUtxo(utxo), s.body)).sum

  private def redeemers(w: DijkstraWitnesses): ExUnits =
    w.redeemers.foldLeft(ExUnits.zero)((acc, r) => acc + ExUnits(r.memory, r.steps))

  /** `getTotalExUnits` of a Dijkstra transaction (`Dijkstra/Tx.hs:417-421`): its redeemers' units
    * and those of its sub-transactions.
    */
  def totalExUnits(tx: DijkstraTx): ExUnits =
    tx.body.subTransactions.foldLeft(redeemers(tx.witnesses))((acc, s) =>
      acc + redeemers(s.witnesses)
    )

/** LEDGER of Dijkstra (`Dijkstra/Rules/Ledger.hs:392-487`, `dijkstraLedgerTransition`,
  * cardano-ledger 9df9f98c), in the reference's order:
  *
  *   - SUBLEDGERS over the sub-transactions (`Ledger.hs:405-418`). Over none it is `foldM` over
  *     `[]` (`SubLedgers.hs:136-143`), the state unchanged; over any it halts
  *     ([[DijkstraPending.SubLedgers]]).
  *   - the treasury value (`Conway.validateTreasuryValue`, `Ledger.hs:423`) and the reference
  *     scripts of the batch against `maxRefScriptSizePerTx` of the parameters
  *     (`validateAllRefScriptSize`, `Ledger.hs:346-362, 424`), against the UTxO set before the
  *     transaction; both recorded (`runTest`).
  *   - for a phase-2-valid transaction ENTITIES, then GOV (`Ledger.hs:426-471`): ENTITIES halts
  *     ([[DijkstraPending.Entities]]), so GOV ([[DijkstraGov]]) is not reached from here yet.
  *   - UTXOW (`Ledger.hs:475-486`), [[DijkstraUtxow]], on the UTxO state.
  *
  * So a phase-2-valid transaction halts, and a phase-2-invalid one is judged in full when UTXOW has
  * its Conway form ([[DijkstraStAnnTx]]).
  */
object DijkstraLedgerRule
    extends JudgedSts[LedgerEnv, DijkstraLedger, DijkstraTx, DijkstraLedgerFailure, DijkstraHalt]:
  /** `validateTreasuryValue` (`Conway/Rules/Ledger.hs`): a declared treasury must be the chain's.
    */
  def treasuryValue(body: DijkstraTxBody, treasury: BigInt): Test[DijkstraLedgerFailure] =
    body.currentTreasuryValue.fold(Test.pass[DijkstraLedgerFailure])(submitted =>
      Test.failureUnless(
        submitted == treasury,
        DijkstraLedgerFailure.TreasuryValueMismatch(Mismatch(submitted, treasury))
      )
    )

  /** `validateAllRefScriptSize` (`Ledger.hs:346-362`). */
  def refScriptSize(
      pp: ProtocolParams,
      utxo: NodeUtxo,
      tx: DijkstraTx
  ): Test[DijkstraLedgerFailure] =
    val total = DijkstraTxMeasures.batchRefScriptsSize(utxo, tx)
    Test.failureUnless(
      BigInt(total) <= pp.maxRefScriptSizePerTx,
      DijkstraLedgerFailure.TxRefScriptsSizeTooBig(
        Mismatch(total.toInt, pp.maxRefScriptSizePerTx.toInt)
      )
    )

  def transition[F[_]: Monad](env: LedgerEnv, tx: DijkstraTx)(using
      ask: Ask[F, Globals],
      st: Stateful[F, DijkstraLedger],
      raise: Raise[F, DijkstraHalt],
      judge: Judge[F, DijkstraLedgerFailure]
  ): F[Unit] =
    for
      before <- st.get
      pp = before.state.utxo.gov.curPParams
      utxo = before.state.utxo.utxo.utxo
      _ <-
        if tx.body.subTransactions.isEmpty then Monad[F].unit
        else DijkstraSts.halt[F, Unit](DijkstraPending.SubLedgers)
      _ <- Sts.runTest[F, DijkstraLedgerFailure](treasuryValue(tx.body, env.treasury.value))
      _ <- Sts.runTest[F, DijkstraLedgerFailure](refScriptSize(pp, utxo, tx))
      _ <-
        if tx.isValid then DijkstraSts.halt[F, Unit](DijkstraPending.Entities)
        else Monad[F].unit
      // `UtxoEnv slot pp (certStateAfterSubLedgers ^. certPStateL) (lsCertState ledgerState)
      // originalUtxo` (`Ledger.hs:475-486`): with no sub-transaction, both are the state before
      _ <- DijkstraSts.trans[F, DijkstraUtxoEnv, Counted[
        UTxOState[Dijkstra]
      ], DijkstraLedger, DijkstraTx, DijkstraUtxowFailure, DijkstraLedgerFailure](
        DijkstraUtxow,
        DijkstraUtxoEnv(env.ctx, pp, before.state.cert.pState, before.state.cert, utxo),
        tx,
        Counted.utxoOfEra[Dijkstra],
        DijkstraLedgerFailure.UtxowFailure(_)
      )
    yield ()

/** LEDGERS of Dijkstra, Shelley's (`Babbage.LEDGERS`, `Dijkstra/Era.hs:296`;
  * `Shelley/Rules/Ledgers.hs`): the transactions of a block in order, each by LEDGER with its
  * position. The block's verdict is the first failure ([[Judge.raising]]), raised as
  * `LedgersFailure (LedgerFailure _)`.
  */
object DijkstraLedgers
    extends Sts[LedgersEnv, DijkstraLedger, Vector[DijkstraTx], DijkstraBlockFailure]:
  def transition[F[_]: Monad](env: LedgersEnv, txs: Vector[DijkstraTx])(using
      ask: Ask[F, Globals],
      st: Stateful[F, DijkstraLedger],
      raise: Raise[F, DijkstraBlockFailure]
  ): F[Unit] =
    TxIx.enumerate(txs).traverse_ { (tx, ix) =>
      DijkstraLedgerRule
        .transition[F](LedgerEnv(env.ctx.copy(txIndex = ix.index), env.treasury), tx)(using
          summon[Monad[F]],
          ask,
          st,
          raise.contramap(DijkstraBlockFailure.Halted(_)),
          Judge.raising[F, DijkstraLedgerFailure, DijkstraBlockFailure](f =>
            DijkstraBlockFailure.Rejected(DijkstraBbodyFailure.LedgersFailure(f))
          )
        )
    }

/** `BbodyState` of Dijkstra: the ledger state and the blocks the pools made in the epoch. */
final case class DijkstraBbodyState(ledger: DijkstraLedger, blocksMade: BlocksMade)

/** BBODY of Dijkstra (`Dijkstra/Rules/Bbody.hs:368-404`, `dijkstraBbodyTransition`, cardano-ledger
  * 9df9f98c), in the reference's order, each check a failure of the block:
  *
  *   - the body's size and hash against its header's (`validateBlockBodySize`,
  *     `validateBlockBodyHash`, `Shelley/Rules/Bbody.hs:251-295`); the size is that of the body's
  *     encoding, which is memoized, so its original bytes;
  *   - the header's highest supported major version, of its version info, at least the major of the
  *     parameters (`HeaderProtVerTooLow`, `Bbody.hs:378-386`);
  *   - LEDGERS over the transactions, with the epoch of the header's slot;
  *   - the execution units of all the transactions, sub-transactions included, within
  *     `maxBlockExUnits` (`Alonzo.validateExUnits`, `Alonzo/Rules/Bbody.hs:166-175`);
  *   - the reference scripts of the block within `maxRefScriptSizePerBlock` of the parameters
  *     (`Conway.validateBodyRefScriptsSizeTooBig`, `Conway/Rules/Bbody.hs:356-384`): from version
  *     11 each transaction against the UTxO set as the block meets it with the outputs of the
  *     transactions before (a valid one's outputs, an invalid one's collateral return);
  *   - a Peras certificate, which `validatePerasCert` refuses whatever it is: `validatePerasCert _
  *     _ _ = False` (`Dijkstra/BlockBody/Internal.hs:245-246`), so a body that carries one fails
  *     (`PerasCertValidationFailed`, with the header's previous nonce, which no header of the build
  *     overrides from the class default `NeutralNonce`, `Cardano/Ledger/Block.hs:140-141`);
  *
  * then the block counted for its issuer (`incrBlocks`; the decentralisation parameter is zero from
  * Babbage, so no slot is an overlay slot).
  *
  * The caller's `env.ctx` is the context of the block's transactions, as [[EraBlock]] builds it for
  * Conway: its slot the header's and its epoch that slot's (`LedgersEnv bhSlot curEpoch`,
  * `Bbody.hs:388-393`).
  */
object DijkstraBbody
    extends Sts[BbodyEnv, DijkstraBbodyState, DijkstraBbody.Signal, DijkstraBlockFailure]:
  /** The block and the pool that made it ([[issuer]]). */
  final case class Signal(block: LeiosBlock, issuer: KeyHash[StakePool])

  given Embed[DijkstraLedger, DijkstraBbodyState, DijkstraBlockFailure, DijkstraBlockFailure] =
    Embed(
      monocle.Lens[DijkstraBbodyState, DijkstraLedger](_.ledger)(l => s => s.copy(ledger = l)),
      identity
    )

  /** `totalRefScriptSizeInBlock` (`Conway/Rules/Bbody.hs:371-384`). */
  def refScriptsSize(pp: ProtocolParams, utxo: NodeUtxo, txs: Vector[DijkstraTx]): Long =
    if pp.protocolMajor <= 10 then
      txs.map(tx => DijkstraTxMeasures.refScriptsSize(DijkstraTxMeasures.inUtxo(utxo), tx.body)).sum
    else
      txs
        .foldLeft((Map.empty[TxIn, Long], 0L)) { case ((added, sum), tx) =>
          val body = tx.body
          val created =
            if tx.isValid then
              body.outputs.zipWithIndex.map((o, i) => TxIn(body.id, BigInt(i)) -> o)
            else
              body.collateralReturn
                .map(r => TxIn(body.id, BigInt(body.outputs.size)) -> r)
                .toVector
          val size = DijkstraTxMeasures.refScriptsSize(
            in => added.get(in).orElse(DijkstraTxMeasures.inUtxo(utxo)(in)),
            body
          )
          val sizes =
            created.map((in, o) => in -> o.referenceScript.fold(0L)(DijkstraTxMeasures.scriptSize))
          (added ++ sizes, sum + size)
        }
        ._2

  private def fail(f: DijkstraBbodyFailure): DijkstraBlockFailure = DijkstraBlockFailure.Rejected(f)

  def transition[F[_]: Monad](env: BbodyEnv, signal: Signal)(using
      ask: Ask[F, Globals],
      st: Stateful[F, DijkstraBbodyState],
      raise: Raise[F, DijkstraBlockFailure]
  ): F[Unit] =
    val pp = env.pp
    val block = signal.block
    val header = block.header
    val txs = block.transactions
    val highest = header.leios.versionInfo.highestSupportedMajor
    val units = txs.foldLeft(ExUnits.zero)(_ + DijkstraTxMeasures.totalExUnits(_))
    for
      _ <- Sts.check[F, DijkstraBlockFailure](
        BigInt(block.bodySize) == header.bodySize,
        fail(
          DijkstraBbodyFailure.WrongBlockBodySize(Mismatch(BigInt(block.bodySize), header.bodySize))
        )
      )
      _ <- Sts.check[F, DijkstraBlockFailure](
        block.bodyHash == header.bodyHash,
        fail(DijkstraBbodyFailure.InvalidBodyHash(Mismatch(block.bodyHash, header.bodyHash)))
      )
      _ <- Sts.check[F, DijkstraBlockFailure](
        highest >= pp.protocolMajor,
        fail(DijkstraBbodyFailure.HeaderProtVerTooLow(Mismatch(highest, pp.protocolMajor)))
      )
      before <- st.get
      _ <- Sts.trans[F, LedgersEnv, DijkstraLedger, DijkstraBbodyState, Vector[
        DijkstraTx
      ], DijkstraBlockFailure, DijkstraBlockFailure](
        DijkstraLedgers,
        LedgersEnv(env.ctx, env.treasury),
        txs
      )
      _ <- Sts.check[F, DijkstraBlockFailure](
        units.fits(pp.maxBlockExUnits),
        fail(DijkstraBbodyFailure.TooManyExUnits(Mismatch(units, pp.maxBlockExUnits)))
      )
      // `ls ^. utxoL`: the UTxO set the block met (`Bbody.hs:396`)
      refBytes = refScriptsSize(pp, before.ledger.state.utxo.utxo.utxo, txs)
      _ <- Sts.check[F, DijkstraBlockFailure](
        BigInt(refBytes) <= pp.maxRefScriptSizePerBlock,
        fail(
          DijkstraBbodyFailure.BodyRefScriptsSizeTooBig(
            Mismatch(refBytes, pp.maxRefScriptSizePerBlock.toLong)
          )
        )
      )
      _ <- block.perasCertificate.fold(Monad[F].unit)(cert =>
        fail(DijkstraBbodyFailure.PerasCertValidationFailed(cert, Nonce.Neutral))
          .raise[F, Unit]
      )
      _ <- st.modify(s =>
        s.copy(blocksMade =
          BlocksMade(
            s.blocksMade.blocks.updatedWith(signal.issuer)(n =>
              Some(Word64.fromUnsigned(n.fold(1L)(_.bits + 1)))
            )
          )
        )
      )
    yield ()

  /** The pool that made a block: the Blake2b-224 hash of its header's issuer key. */
  def issuer(block: LeiosBlock): Option[KeyHash[StakePool]] =
    KeyHash.fromBytes[StakePool](Blake2b.hash224.hash(block.header.issuerVkey)).toOption
