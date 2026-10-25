// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.ledger.{CapacityDimension, TransactionDecodeProblem, MempoolProblem}
import cats.data.NonEmptyList
import cats.syntax.all.*
import cats.Id
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.plutus.{ScalusEvaluator, ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.node.read.{ChainRead, ConwayView}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{Block, EpochGeometry, PosixClock}
import xyz.kd5ujc.scarab.ledger.{
  Credential,
  LedgerFailure,
  LedgerGlobals,
  PlutusStats,
  PredicateFailure,
  Tx,
  TxEnv,
  Utxo,
  UtxoFailure,
  UtxosFailure,
  UtxowFailure
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Gov,
  Globals,
  Ledger,
  LedgerEnv,
  Sts,
  Tick,
  TickFailure
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Conway, LedgerState, NewEpochState, SlotNo, UtxoTables}

/** Why a transaction is not (or no longer) in the mempool. */
enum Rejection:
  /** The bytes are not a Conway transaction `[body, witnesses, is_valid, auxiliary_data]`. */
  case Malformed(cause: TransactionDecodeProblem)

  /** The same transaction is already held. */
  case AlreadyHeld(id: Bytes)

  /** The ledger rules (phase 1, and phase 2 for scripts) refuse it in the current state. */
  case Ledger(failure: LedgerFailure)

  /** The transaction on its own exceeds a limit of the protocol parameters, its size or execution
    * units (the failures are those of the reference's `txMeasurePhase1`, all of them as it reports
    * them), and the ledger rules did not run.
    */
  case Limits(id: Bytes, failures: NonEmptyList[PredicateFailure])

  /** Holding it would exceed the mempool's capacity in a component of the measure vector: `needed`
    * is what the transaction counts for and `free` the capacity left, whose components are negative
    * where the pool is over capacity (after a parameter change). [[MempoolState.add]] returns it
    * for any shortage; [[Mempool.add]] waits for room unless `needed` exceeds the whole capacity,
    * and only that case reaches its caller.
    */
  case Full(needed: TxMeasure, free: TxMeasure)

  /** The submission names an era other than the ledger's (Conway). */
  case WrongEra(era: Int)

  /** The chain has no Conway ledger state to validate against (it is in Byron or an earlier era, or
    * its state cannot be read): the pool takes nothing until it has.
    */
  case NoLedger(why: ConwayView.Unavailable)

  def message: String = this match
    case Malformed(d)    => s"malformed transaction: ${d.message}"
    case AlreadyHeld(id) => s"tx ${id.hex.take(16)} is already in the mempool"
    case Ledger(f)       => f.message
    case Limits(id, fs) =>
      s"tx ${id.hex.take(16)} violates ${fs.map(_.detail).toList.mkString("; ")}"
    case f: Full       => f.problem.message
    case WrongEra(e)   => s"era $e is not Conway"
    case NoLedger(why) => s"no Conway ledger state: ${cats.Show[ConwayView.Unavailable].show(why)}"

object Rejection:
  extension (full: Full)
    /** The component that does not fit (the first, when several do not). */
    def dimension: CapacityDimension =
      import Capacity.exceeded
      full.needed.exceeded(full.free).getOrElse(CapacityDimension.Bytes)

    def problem: MempoolProblem.Capacity =
      import Capacity.component
      val d = full.dimension
      MempoolProblem.Capacity(d, full.needed.component(d), full.free.component(d))

/** Where a submission comes from: `WhetherToIntervene` of the reference
  * (`Ledger/SupportsMempool.hs`). `Mempool/Update.hs` `implAddTx` runs a transaction from a local
  * client (`AddTxForLocalClient`) as `Intervene` and one from a peer (`AddTxForRemotePeer`) as
  * `DoNotIntervene`.
  */
enum Source:
  /** LocalTxSubmission: the transaction is validated as received, `is_valid` included. */
  case Local

  /** TxSubmission2: the transaction is validated with `is_valid` forced to true, and if only the
    * scripts disagree with that, kept with `is_valid` false (the submitter loses the collateral).
    */
  case Remote

/** A held transaction. `ticket` grows with arrival order and is how peers are given each
  * transaction once. `refScriptBytes` is the size of the reference scripts the transaction names
  * (spent and reference inputs, counted per input as the ledger's `txNonDistinctRefScriptsSize`),
  * measured against the state it was added to; LocalTxMonitor reports it.
  */
final case class Entry(id: Bytes, raw: Bytes, tx: Tx, ticket: Long, refScriptBytes: Int = 0):
  def size: Int = raw.size

/** What the transaction rules need besides the ledger state. `clock` is the slot to time
  * translation of the era history at the chain tip the pool is judged against, with its forecast
  * horizon; none is the geometry alone, without a horizon, for pools of tests that have no chain.
  */
final case class MempoolEnv(
    globals: LedgerGlobals,
    geometry: EpochGeometry,
    evaluator: ScriptEvaluator[Id] = ScalusEvaluator.default,
    decoder: ScriptDecoder[Id] = ScalusEvaluator.defaultDecoder,
    clock: Option[PosixClock] = None
):
  /** The environment in which a transaction is validated for inclusion in the next block: the slot
    * after the tip, in the epoch of the fully ticked ledger.
    */
  def txEnv(tipSlot: BigInt, ledger: MempoolLedger, index: Int): TxEnv =
    val slot = tipSlot + 1
    TxEnv(
      slot = slot,
      epoch = ledger.epoch,
      network = globals.network,
      firstSlotNextEpoch = geometry.firstSlot(ledger.epoch + 1),
      stabilityWindow = geometry.stabilityWindow,
      txIndex = index,
      updateQuorum = globals.updateQuorum,
      clock = Some(clock.getOrElse(PosixClock.total(geometry))),
      evaluator = evaluator,
      decoder = decoder
    )

  /** The constants the rules of the era-indexed state read. */
  def rules: Globals = Globals(globals, geometry)

/** The ledger a transaction is validated against: the Conway ledger state at the chain tip, the
  * treasury of the chain account (the transaction's `currentTreasuryValue` is checked against it)
  * and the epoch. The mempool is Conway's: the transaction is `[body, witnesses, is_valid,
  * auxiliary_data]`, and the rules are those of the era-indexed state ([[Ledger]]).
  */
final case class MempoolLedger(ls: LedgerState[Conway], treasury: Coin, epoch: BigInt):
  /** The protocol parameters in force. */
  def params: xyz.kd5ujc.scarab.ledger.ProtocolParams = ls.utxo.gov.curPParams

  /** The handle on the UTxO set. */
  def utxo: Utxo = ls.utxo.utxo.utxo

  def withUtxo(u: Utxo): MempoolLedger =
    copy(ls = ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(u))))

  /** The transaction applied (LEDGER of Conway), or the rule that refuses it. */
  def apply(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, MempoolLedger] =
    Sts(Ledger)(rules, LedgerEnv(env, treasury), Counted(ls, PlutusStats.zero), tx)
      .map(after => copy(ls = after.state))

  /** The transaction admitted from `source`, with the transaction as it is to be held (its
    * `is_valid` possibly changed). A local transaction is applied as received. A remote one is
    * applied with `is_valid` true; when exactly that flag is what the rules refuse, because a
    * script fails (`UtxosFailure.ValidationTagMismatch(true, _)`), it is applied again with
    * `is_valid` false and that result is the answer. This is `applyAlonzoBasedTx` with
    * `DoNotIntervene`
    * (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Eras.hs` at
    * `82ecba329d7d054340bf707d44fe6e9ac27cec40`, `isIncorrectClaimedFlag`). The reference takes a
    * refusal that is the only element of the failure list; this ledger reports its first failure
    * and checks the phase-1 rules before the scripts run, so a mismatch is the first failure only
    * when nothing before it failed.
    */
  def admit(
      tx: Tx,
      env: TxEnv,
      rules: Globals,
      source: Source
  ): Either[LedgerFailure, (MempoolLedger, Tx)] =
    source match
      case Source.Local => apply(tx, env, rules).map(_ -> tx)
      case Source.Remote =>
        val claimed = tx.copy(isValid = true)
        apply(claimed, env, rules) match
          case Left(
                LedgerFailure.Rejected(
                  _,
                  PredicateFailure.Utxow(
                    UtxowFailure.Utxo(
                      UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(true, _))
                    )
                  )
                )
              ) =>
            val failed = tx.copy(isValid = false)
            apply(failed, env, rules).map(_ -> failed)
          case other => other.map(_ -> claimed)

object MempoolLedger:
  /** The ledger of the new-epoch state at the tip. */
  def of(nes: NewEpochState[Conway]): MempoolLedger =
    MempoolLedger(nes.es.ls, nes.es.chainAccount.treasury, nes.epoch.toBigInt)

  /** Tick the complete Conway state before projecting its transaction view. This is
    * `tickLedgerState (ForgeInUnknownSlot _)` in consensus Mempool/Impl/Common.hs: the next
    * available slot, including epoch transitions, rewards, certificate state and treasury. `None`
    * denotes Origin, whose next available slot is zero. The selected state's epoch agrees with its
    * tip, so its successor crosses at most one epoch boundary. Conway is the final era: no
    * hard-fork translation precedes this tick. Reference: ouroboros-consensus
    * `82ecba329d7d054340bf707d44fe6e9ac27cec40`, `Mempool/Impl/Common.hs`, `tickLedgerState`.
    */
  def tick(
      nes: NewEpochState[Conway],
      tipSlot: Option[BigInt],
      env: MempoolEnv
  ): Either[TickFailure, MempoolLedger] =
    for
      slot <- SlotNo.fromBigInt(tipSlot.fold(BigInt(0))(_ + 1)).leftMap(TickFailure.Vocab(_))
      next <- Sts(Tick.sts[Conway])(env.rules, (), nes, slot)
    yield of(next)

  /** The next-slot transaction view of a selected chain. A tick failure is an internal ledger
    * failure, distinct from the chain being in an unsupported era.
    */
  def at[S: ChainRead](
      s: S,
      tip: Option[BlockRef],
      env: MempoolEnv
  ): Either[TickFailure, Either[ConwayView.Unavailable, MempoolLedger]] =
    ConwayView(s, tip).traverse(tick(_, tip.map(_.slot), env))

/** The mempool as a value. The invariant, for every state reachable through this API:
  *
  * `entries` applied in order to `base` through the transaction rules succeed and give `current`.
  *
  * `base` is the complete ledger ticked to the successor of the chain tip the pool was last
  * synchronised with. `tipSlot` is -1 at Origin, making its successor slot zero. Every operation is
  * a pure function; [[Mempool]] serialises them.
  */
final case class MempoolState(
    base: Either[ConwayView.Unavailable, MempoolLedger],
    tipSlot: BigInt,
    current: Either[ConwayView.Unavailable, MempoolLedger],
    entries: Vector[Entry],
    bytes: Long,
    nextTicket: Long,
    capacityOverride: Option[Long]
):
  /** The capacity: `computeMempoolCapacity` of the parameters of the ticked ledger at the base of
    * the pool, so it follows a parameter change at the next revalidation ([[sync]]), and is zero
    * while there is no ledger. A pool over its capacity is not trimmed.
    */
  def capacity: TxMeasure =
    base.fold(_ => TxMeasure.zero, l => Capacity.of(l.params, capacityOverride))

  /** What the held transactions count for, the sum of their measures. */
  def used: TxMeasure = entries.foldLeft(TxMeasure.zero)(_ + Capacity.measure(_))

  /** The capacity left, in each component. */
  def free: TxMeasure =
    import Capacity.-
    capacity - used

  /** Whether `needed` fits in what is free, in every component (`pureTryAddTx`: the transaction is
    * added if and only if it would not overrun any component of the capacity).
    */
  private def room(needed: TxMeasure): Either[Rejection, Unit] =
    import Capacity.fits
    Either.cond(needed.fits(free), (), Rejection.Full(needed, free))

  def contains(id: Bytes): Boolean = entries.exists(_.id == id)

  /** Validate and append a transaction (original bytes) submitted from `source`. A remote
    * transaction may be held with the `is_valid` flag it did not carry ([[MempoolLedger.admit]]);
    * its relayed bytes are then the received ones with only that flag byte changed.
    *
    * The order is that of `Mempool/Update.hs` `doAddTx` and `pureTryAddTx`: the first measure (size
    * and execution units against the parameters), the capacity in those components, the second
    * measure (reference scripts against the state after the held transactions), the capacity in all
    * of them, and only then the rules of the ledger. A refusal of a measure is the only failure
    * returned; the ledger does not run. Scarab checks for a duplicate between the first measure and
    * the capacity: the reference has none (a duplicate fails the MEMPOOL rule, after waiting for
    * room), so a duplicate here is answered even by a full pool. A held transaction passes both
    * measures, which its revalidation keeps true (the LEDGER rule checks the same limits), so the
    * check does not hide a measure refusal.
    */
  def add(raw: Bytes, env: MempoolEnv, source: Source): Either[Rejection, (MempoolState, Entry)] =
    for
      parsed <- MempoolState.parseParts(raw)
      MempoolState.Parsed(id, tx, parts) = parsed
      ledger <- current.leftMap(Rejection.NoLedger(_))
      baseLedger <- base.leftMap(Rejection.NoLedger(_))
      phase1 <- Capacity.phase1(tx, ledger.params).leftMap(Rejection.Limits(id, _))
      _ <- Either.cond(!contains(id), (), Rejection.AlreadyHeld(id))
      _ <- room(phase1)
      refScriptBytes <- Capacity
        .phase2(ledger, tx)
        .leftMap(f => Rejection.Ledger(LedgerFailure.Rejected(id, f)))
      _ <- room(phase1.copy(refScriptsBytes = refScriptBytes.toLong))
      // The MEMPOOL rule of the ledger (`Rules/Mempool.hs`) runs before LEDGER: a transaction none
      // of whose inputs is unspent is "probably a duplicate" and is refused with that alone. Votes
      // of unelected committee members are refused here, and only here, before protocol version 11;
      // from 11 the GOV rule does.
      pv = ledger.params.protocolMajor
      _ <- Either.cond(
        pv < 9 || tx.body.inputs.exists(ledger.utxo.contains),
        (),
        Rejection.Ledger(
          LedgerFailure.Rejected(id, PredicateFailure.Mempool(MempoolProblem.AllInputsSpent))
        )
      )
      unelected = MempoolState.unelectedCommitteeVoters(ledger, tx.body)
      _ <- Either.cond(
        pv < 9 || pv > 10 || unelected.isEmpty,
        (),
        Rejection.Ledger(
          LedgerFailure.Rejected(
            id,
            PredicateFailure.Mempool(
              MempoolProblem.UnelectedCommittee(unelected)
            )
          )
        )
      )
      admitted <- ledger
        .admit(tx, env.txEnv(tipSlot, baseLedger, entries.size), env.rules, source)
        .leftMap(Rejection.Ledger(_))
    yield
      val (next, held) = admitted
      // The flag is neither hashed nor signed (the id is the hash of the body alone), so the
      // transaction is relayed with its other three parts as received and the flag of the verdict,
      // as the reference relays `[body, witnesses, flag, auxiliary_data]` it rebuilt.
      val kept =
        if held.isValid == tx.isValid then raw
        else MempoolState.encode(parts.copy(isValid = held.isValid))
      val entry = Entry(id, kept, held, nextTicket, refScriptBytes)
      (
        copy(
          current = Right(next),
          entries = entries :+ entry,
          bytes = bytes + kept.size,
          nextTicket = nextTicket + 1
        ),
        entry
      )

  /** Move to a new chain tip: keep, in order, the transactions that still apply to `ledger` and
    * report the others with the rule that now refuses them. Transactions that a block contains fail
    * here because their inputs are spent.
    */
  def sync(
      ledger: Either[ConwayView.Unavailable, MempoolLedger],
      slot: BigInt,
      env: MempoolEnv
  ): (MempoolState, Vector[(Entry, Rejection)]) =
    ledger match
      case Left(why) =>
        // no ledger to validate against: nothing is held, and everything held is dropped
        (
          copy(
            base = Left(why),
            tipSlot = slot,
            current = Left(why),
            entries = Vector.empty,
            bytes = 0
          ),
          entries.map(_ -> Rejection.NoLedger(why))
        )
      case Right(l) =>
        val start =
          copy(
            base = Right(l),
            tipSlot = slot,
            current = Right(l),
            entries = Vector.empty,
            bytes = 0
          )
        entries.foldLeft((start, Vector.empty[(Entry, Rejection)])) {
          case ((state, dropped), entry) =>
            state.current
              .leftMap(Rejection.NoLedger(_))
              .flatMap(
                _(entry.tx, env.txEnv(slot, l, state.entries.size), env.rules)
                  .leftMap(Rejection.Ledger(_))
              ) match
              case Right(next) =>
                (
                  state.copy(
                    current = Right(next),
                    entries = state.entries :+ entry,
                    bytes = state.bytes + entry.size
                  ),
                  dropped
                )
              case Left(rejection) => (state, dropped :+ (entry -> rejection))
        }

  /** Remove transactions by id (and, because later ones may spend their outputs, revalidate the
    * rest).
    */
  def remove(ids: Set[Bytes], env: MempoolEnv): (MempoolState, Vector[(Entry, Rejection)]) =
    copy(entries = entries.filterNot(e => ids(e.id)))
      .sync(base, tipSlot, env)

  /** The longest prefix of the pool, in order, within `maxBytes`. */
  def forBlock(maxBytes: Long): Vector[Entry] =
    entries
      .scanLeft((0L, Option.empty[Entry]))((acc, e) => (acc._1 + e.size, Some(e)))
      .drop(1)
      .takeWhile(_._1 <= maxBytes)
      .flatMap(_._2)

  /** Entries with a ticket above `ticket`, oldest first. */
  def after(ticket: Long): Vector[Entry] = entries.filter(_.ticket > ticket)

object MempoolState:
  /** The text of the ledger's MEMPOOL failure for a transaction whose inputs are all spent. */
  val AllInputsSpent: String =
    "All inputs are spent. Transaction has probably already been included"

  /** `unelectedCommitteeVoters` (`Gov.hs:652`): the committee hot credentials among the voters of
    * `body` that no member of the current committee has authorised. From protocol version 11 the
    * GOV rule refuses such votes; before that the reference refuses them in its mempool only
    * (`Rules/Mempool.hs`), so blocks may carry them.
    */
  def unelectedCommitteeVoters(
      ledger: MempoolLedger,
      body: xyz.kd5ujc.scarab.ledger.TxBody
  ): Vector[Credential] =
    Gov.unelected(
      ledger.ls.utxo.gov.committee,
      ledger.ls.cert,
      body.votes.map(_._1)
    )

  /** A credential as the ledger's `Show` instance prints it. */
  def showCredential(c: Credential): String = c match
    case Credential.Key(h)    => "KeyHashObj (KeyHash {unKeyHash = \"" + h.hex + "\"})"
    case Credential.Script(h) => "ScriptHashObj (ScriptHash \"" + h.hex + "\")"

  /** The reference-script bytes of the transaction's inputs and reference inputs in `ledger`, an
    * output counted once per set it appears in.
    */
  def refScriptBytes(ledger: MempoolLedger, tx: Tx): Int =
    (tx.body.inputs.iterator ++ tx.body.referenceInputs.iterator)
      .flatMap(ledger.utxo.get)
      .map(_.referenceScriptSize)
      .sum

  def empty(
      ledger: Either[ConwayView.Unavailable, MempoolLedger],
      tipSlot: BigInt,
      capacityOverride: Option[Long]
  ): MempoolState =
    MempoolState(ledger, tipSlot, ledger, Vector.empty, 0L, 1L, capacityOverride)

  private val limits = Cbor.Limits(
    maxInputBytes = 1048576,
    maxItems = 100000,
    maxStringBytes = 1048576
  )

  /** A parsed submission: its id (the hash of the original body), the decoded transaction and the
    * original bytes of its parts with the flag it carried.
    */
  final case class Parsed(id: Bytes, tx: Tx, parts: Block.Transaction)

  /** The transaction `[body, witnesses, is_valid, aux]` of its parts, each as its original bytes
    * and the flag as the boolean that is encoded.
    */
  def encode(t: Block.Transaction): Bytes =
    Bytes.fromArray(Array(0x84.toByte)) ++ t.body ++ t.witnesses ++
      Bytes.fromArray(Array((if t.isValid then 0xf5 else 0xf4).toByte)) ++
      t.auxiliaryData.getOrElse(Bytes.fromArray(Array(0xf6.toByte)))

  /** Split `[body, witnesses, is_valid, aux]` keeping each component's original bytes. */
  def parse(raw: Bytes): Either[Rejection, (Bytes, Tx)] =
    parseParts(raw).map(p => (p.id, p.tx))

  def parseParts(raw: Bytes): Either[Rejection, Parsed] =
    def malformed[A](detail: TransactionDecodeProblem): Either[Rejection, A] = Left(
      Rejection.Malformed(detail)
    )
    for
      root <- Cbor
        .decode(raw, limits)
        .leftMap(e => Rejection.Malformed(TransactionDecodeProblem.Syntax(e)))
      parts <- root.value match
        case Value.Arr(xs) if xs.size == 4 => Right(xs)
        case _ => malformed(TransactionDecodeProblem.Envelope(root.value))
      valid <- parts(2).value match
        case Value.Bool(b) => Right(b)
        case _             => malformed(TransactionDecodeProblem.ValidityFlag(parts(2).value))
      aux = parts(3).value match
        case Value.Null => None
        case _          => Some(parts(3).original)
      transaction = Block.Transaction(parts(0).original, parts(1).original, aux, valid)
      tx <- Tx
        .fromBlock(transaction, Era.Conway)
        .leftMap(e => Rejection.Malformed(TransactionDecodeProblem.Ledger(e)))
    yield Parsed(Blake2b.hash256.hash(parts(0).original), tx, transaction)
