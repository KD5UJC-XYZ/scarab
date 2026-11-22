// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Applicative, Functor, Id, Monad}
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{CborRead, LedgerDefect, TranslationError, TxEnv, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge}
import xyz.kd5ujc.scarab.ledger.state.{
  ChainAccountState,
  Dijkstra,
  LedgerState,
  SlotNo,
  TxIx,
  Word64
}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.{EbHash, LeiosTxHash}

// The application of a certified endorser block's closure as the Leios testnet's build makes it
// (ouroboros-consensus 2416493b, `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/
// Shelley/Ledger/Leios.hs:167-252`, through `resolveAndApplyLeiosClosure`,
// `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/LedgerDB/Forker.hs:
// 1066-1104`): the closure's transactions decoded as an endorser block holds them, then each applied
// to the state before the certifying block by Dijkstra's LEDGER with no predicate checked
// (`ValidateNone`), at the slot of that state's tip.

/** Why a certified endorser block's closure cannot be applied: `LeiosClosureError`
  * (`LeiosDemoTypes.hs:1828-1835`, ouroboros-consensus 2416493b). The reference meets either with
  * `error` in `resolveAndApplyLeiosClosure` (`Forker.hs:1084-1087`) and in `resolveLeiosBlock`
  * (`:1048-1052`): a defect of the node's own, never a refusal of the block, since chain selection
  * applies a certifying block only once its endorser block's closure is in the store
  * (`isUnacquiredCertRB`, `ChainSel.hs:1778-1805`). Whoever applies the closure stops the node on
  * it.
  */
enum LeiosClosureDefect:
  /** `LeiosClosureMissing`: the store holds no complete closure for the endorser block. */
  case Missing(eb: EbHash)

  /** `LeiosClosureTxUndecodable`: a stored transaction does not decode as an endorser block's
    * transaction, which the reference renders as text and the node keeps typed.
    */
  case TxUndecodable(eb: EbHash, tx: LeiosTxHash, cause: EraTxError)

object LeiosClosureDefect:
  given CanEqual[LeiosClosureDefect, LeiosClosureDefect] = CanEqual.derived

/** The application of an endorser block's closure (`ResolveLeiosBlock (ShelleyBlock (Praos2 c)
  * DijkstraEra)`, `Leios.hs:150-252`, ouroboros-consensus 2416493b).
  */
object DijkstraClosure:
  /** `deserializeTxForLeios` (`LeiosDemoTypes.hs:1239-1246`, ouroboros-consensus 2416493b): a
    * transaction as an endorser block holds it, its block-body form with its validity flag
    * (`decodeDijkstraTopTxInBlock`, `Dijkstra/Tx.hs:175-182`, cardano-ledger 9df9f98c), the bytes
    * one item whole (`decodeFullAnnotator`), read with the bounds the block decoder reads a block
    * with (`LeiosBlock.decode`).
    */
  def deserializeTxForLeios(raw: Bytes): Either[EraTxError, Tx[TxLevel.Top, Dijkstra]] =
    CborRead
      .decode(raw, Cbor.Limits.Unbounded)
      .leftMap(EraTxError.Decode(_))
      .flatMap(DijkstraTx.inBlock)

  /** `resolveLeiosClosure` (`Leios.hs:167-177`, ouroboros-consensus 2416493b), on what the store's
    * `lookupEbClosure` gave for `eb`: none is [[LeiosClosureDefect.Missing]]; otherwise each
    * transaction in the endorser block's order with its hash, the first that does not decode
    * stopping the rest ([[LeiosClosureDefect.TxUndecodable]]), as a closure is only useful whole.
    */
  def resolveLeiosClosure(
      eb: EbHash,
      stored: Option[Vector[(LeiosTxHash, Bytes)]]
  ): Either[LeiosClosureDefect, Vector[(LeiosTxHash, Tx[TxLevel.Top, Dijkstra])]] =
    stored
      .toRight(LeiosClosureDefect.Missing(eb))
      .flatMap(
        _.traverse((txh, bs) =>
          deserializeTxForLeios(bs)
            .bimap(LeiosClosureDefect.TxUndecodable(eb, txh, _), txh -> _)
        )
      )

  /** `mkMempoolEnv nes slot` (`Shelley/API/Mempool.hs:363-377`, cardano-ledger 9df9f98c): the slot,
    * no epoch (LEDGER takes the slot's), the least index, the parameters in force and the chain's
    * account of the state, which the caller reads from it.
    */
  def mkMempoolEnv(
      slot: BigInt,
      pp: PParams[Dijkstra],
      account: ChainAccountState
  ): LedgerEnv[Dijkstra] =
    LedgerEnv[Dijkstra](slot, None, TxIx.minBound, pp, account)

  /** `fromWithOrigin (SlotNo 0) (getTipSlot lst)` (`Leios.hs:237`, ouroboros-consensus 2416493b):
    * the slot of the state's tip, zero at the origin.
    */
  def tipSlot(tip: Option[SlotNo]): BigInt = tip.fold(BigInt(0))(_.toBigInt)

  /** The bytes of the closure's transactions (`sum (fromIntegral . (^. sizeTxF) <$> innerTxs)`,
    * `Leios.hs:223-224`, ouroboros-consensus 2416493b), which the state's cumulative transaction
    * bytes gain with the closure (`shelleyCumulativeTxBytes`, `:225-230`): each transaction's
    * `sizeTxF` as a `Word64`, summed modulo 2^64 as `Word64` 's `sum` is.
    */
  def closureTxBytes(txs: Vector[Tx[TxLevel.Top, Dijkstra]]): Word64 =
    txs.foldLeft(Word64.fromUnsigned(0L))((total, tx) =>
      Word64.wrappingAdd(
        total,
        Word64.fromUnsigned(EraTx.dijkstra.sizeTxF[TxLevel.Top](tx)(using BothLevels.Top))
      )
    )

  /** The judgment of `ValidateNone` (`Control/State/Transition/Extended.hs:714-717, 737-742`,
    * cardano-ledger 9df9f98c): no predicate is checked, so nothing is recorded and the failing flag
    * stays down (`isFailing`, `:701-707`), every sub-rule run as the rule's own.
    */
  private def validateNone[F[_], Fail](using F: Applicative[F]): Judge[F, Fail] =
    new Judge[F, Fail]:
      val functor: Functor[F] = F
      def tell(failures: Chain[Fail]): F[Unit] = F.unit
      def failing: F[Boolean] = F.pure(false)
      def nest[A](fa: F[A]): F[A] = fa

  /** The events a rule run with `EPDiscard` tells (`ruleApplyTxValidation`, `asoEvents =
    * EPDiscard`, `Shelley/API/Mempool.hs:245-262`, cardano-ledger 9df9f98c): none kept.
    */
  private def discarded[F[_], Ev](using F: Applicative[F]): Tell[F, Chain[Ev]] =
    new Tell[F, Chain[Ev]]:
      val functor: Functor[F] = F
      def tell(events: Chain[Ev]): F[Unit] = F.unit

  /** `applyLeiosClosure` (`Leios.hs:210-252`, ouroboros-consensus 2416493b): the closure's
    * transactions, in order, each by Dijkstra's LEDGER (`ruleApplyTxValidation @"LEDGER"
    * ValidateNone`, `:240-252`) on the ledger state and the proposals it is given, the state before
    * the certifying block, unticked:
    *   - every transaction with the one environment `env` ([[mkMempoolEnv]] at the slot of that
    *     state's tip, [[tipSlot]]), its index the least;
    *   - each annotated by `mkStAnnTx` (Dijkstra's [[DijkstraStAnnTx.mkDijkstraStAnnTopTx]]) with
    *     an empty cache against the UTxO set as the transactions before it left it, the stored
    *     outputs of its every level's inputs, reference inputs and collateral read into the era;
    *   - with `ValidateNone`: no predicate checked (the judgment [[validateNone]]), so no failure
    *     is recorded and none is reported; the static checks and the scripts' evaluation, the only
    *     labelled parts of the rules (`lblStatic`), skipped, as under
    *     [[TxEnv.Validation.NonStatic]], which `context` is given: UTXOS evaluates no script and
    *     takes the transaction's validity flag as it is; the events discarded.
    *
    * The reference's checks are lazy and a skipped one is never evaluated; the node computes some
    * of its predicates before the judgment drops them, so a defect raised while computing one is
    * raised here where the reference meets none. A closure the committee certified passes them, and
    * the difference is a Gap (#54). The defects are the rules' ([[UtxoStateDefect]],
    * [[LedgerDefect]], [[PoolDefect]], [[GovDefect]], [[ScriptVerdictUnavailable]]).
    */
  def applyLeiosClosure[F[_]: Monad](
      env: LedgerEnv[Dijkstra],
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[Dijkstra]],
      proposals: Stateful[F, Proposals[Dijkstra]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      govDefect: Raise[F, GovDefect]
  ): F[Unit] =
    val validation = context.copy(validation = TxEnv.Validation.NonStatic)
    given Judge[F, LedgerPredFailure[Dijkstra]] = validateNone
    given Tell[F, Chain[LedgerEvent[Dijkstra]]] = discarded
    val bodies = EraTx.dijkstra.bodyOf
    txs.traverse_ { tx =>
      for
        current <- state.get
        utxo <- ShelleyUtxo.readStored[F, Dijkstra](
          ShelleyUtxo
            .extractKeys(
              current.utxo.utxo.utxo,
              bodies.allInputs(EraTx.dijkstra.body[TxLevel.Top](tx)(using BothLevels.Top))
            )
            ._2
        )
        // the script-decoding cache only saves work, and no script is evaluated: empty
        // (`Leios.hs:249-251`, ouroboros-consensus 2416493b)
        stAnnTx = DijkstraLedgers.mkStAnnTx(slotToPOSIXTime, env.ledgerPp)(utxo, Map.empty, tx)
        _ <- DijkstraLedger.dijkstraLedgerTransition[F](env, stAnnTx, validation, verdicts, decoder)
      yield ()
    }
