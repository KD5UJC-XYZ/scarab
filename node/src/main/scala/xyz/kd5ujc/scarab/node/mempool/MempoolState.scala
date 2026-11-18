// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.ledger.{CapacityDimension, TransactionDecodeProblem, MempoolProblem}
import cats.data.NonEmptyList
import cats.syntax.all.*
import cats.Id
import xyz.kd5ujc.scarab.network.Word32
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node}
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{Block, DetachedRecords, EpochGeometry, PosixClock}
import xyz.kd5ujc.scarab.ledger.{
  Credential,
  LedgerDecodeError,
  LedgerFailure,
  LedgerGlobals,
  PredicateFailure,
  RefScripts,
  Tx,
  TxEnv
}
import xyz.kd5ujc.scarab.ledger.rules.Globals

/** Why a transaction is not (or no longer) in the mempool. */
enum Rejection:
  /** The bytes are not a transaction of the era the submission names. */
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

  /** The submission is of an era other than the ledger's, `ledger`, and does not reach it
    * ([[EraInjection]]): the hard-fork combinator's `HardForkApplyTxErrWrongEra`, naming the era
    * `transaction` (its index in the combinator's era list) it is reported in.
    */
  case WrongEra(transaction: Int, ledger: Era)

  /** The chain has no ledger state the pool validates against (it is in Byron or Dijkstra, or its
    * state cannot be read): the pool takes nothing until it has.
    */
  case NoLedger(why: ConwayView.Unavailable)

  /** The transaction's decoder threw on the submitted bytes instead of answering: no verdict on the
    * transaction and no refusal of it, but a defect met on the input of the connection it came
    * from, which is closed, the node going on (issue 465; ADR 0017 decision 5). In the reference
    * the transaction is decoded by the mini-protocol's codec, where an exception ends the
    * connection (network `RethrowPolicy.hs`: `ShutdownPeer`).
    */
  case InputDefect(cause: Throwable)

  /** The ledger rules threw while they judged the transaction instead of answering: no verdict on
    * it and no refusal of it. Its connection is closed and the node goes on, as the exception ends
    * the mini-protocol's thread in the reference, where the mempool adds the transaction in the
    * caller's thread (consensus 82ecba329 `Mempool/Update.hs:183-245`, `doAddTx`, `evaluate
    * (pureTryAddTx ...)`; only the sync watcher is linked to the node, `Mempool/Init.hs:66`). The
    * submitter is not charged, since nothing it did is shown wrong (owner decision 2026-10-28,
    * issue 564).
    */
  case RulesDefect(cause: Throwable)

  def message: String = this match
    case Malformed(d)    => s"malformed transaction: ${d.message}"
    case AlreadyHeld(id) => s"tx ${id.hex.take(16)} is already in the mempool"
    case Ledger(f)       => f.message
    case Limits(id, fs) =>
      s"tx ${id.hex.take(16)} violates ${fs.map(_.detail).toList.mkString("; ")}"
    case f: Full        => f.problem.message
    case WrongEra(e, l) => s"era $e is not the ledger's, $l"
    case NoLedger(why)  => s"no ledger state: ${cats.Show[ConwayView.Unavailable].show(why)}"
    case InputDefect(t) => s"the transaction decoder threw: $t"
    case RulesDefect(t) => s"the ledger rules threw on the transaction: $t"

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

/** A held transaction of `era`, the era of the pool's ledger: its parts, with the flag of the
  * verdict, and the transaction they make in that era. `ticket` grows with arrival order and is how
  * peers are given each transaction once. `refScriptBytes` is the size of the reference scripts the
  * transaction names (the union of its spent and reference inputs, each output once, as the
  * ledger's `txNonDistinctRefScriptsSize`), measured against the state it was added to in Conway,
  * and zero before (the measure of those eras has no such component, [[Capacity]]); LocalTxMonitor
  * reports it.
  */
final case class Entry(
    era: Era,
    parts: Block.Transaction,
    tx: Tx,
    ticket: Long,
    refScriptBytes: Long = 0L
):
  /** The id: the hash of the body. */
  def id: Bytes = parts.id

  /** The transaction as the reference holds, lists and relays it ([[EraInjection.envelope]]). */
  lazy val raw: Bytes = EraInjection.envelope(parts, era)

  def size: Int = raw.size

/** What the transaction rules need besides the ledger state. `clock` is the slot to time
  * translation of the era history at the chain tip the pool is judged against, with its forecast
  * horizon, for a transaction of the era it is given (the era of the pool's ledger); none is the
  * geometry alone, without a horizon, for pools of tests that have no chain. `verdicts` are the
  * signature verifiers of the witness checks. `evaluator` and `decoder` are the node's Plutus
  * backend (`PlutusBackend`), with no default.
  */
final case class MempoolEnv(
    globals: LedgerGlobals,
    geometry: EpochGeometry,
    evaluator: ScriptEvaluator[Id],
    decoder: ScriptDecoder[Id],
    clock: Option[Era => PosixClock] = None,
    verdicts: xyz.kd5ujc.scarab.ledger.Verdicts = xyz.kd5ujc.scarab.ledger.Verdicts.Direct
):
  /** The environment in which a transaction is validated for inclusion in the next block: the slot
    * after the tip, in the epoch and the era of the fully ticked ledger, and at transaction index 0
    * whatever its place in the pool: the reference's mempool applies every transaction at
    * `ledgerIx = minBound` (`mkMempoolEnv`, `Shelley/API/Mempool.hs:363-377`, cardano-ledger
    * f649f975), which before Conway is the transaction index of a registration's pointer
    * (`Delegs.hs:194-197`).
    */
  def txEnv(tipSlot: BigInt, ledger: PoolLedger): TxEnv =
    val slot = tipSlot + 1
    TxEnv(
      slot = slot,
      epoch = ledger.epoch,
      network = globals.network,
      firstSlotNextEpoch = geometry.firstSlot(ledger.epoch + 1),
      stabilityWindow = geometry.stabilityWindow.toBigInt,
      txIndex = 0,
      updateQuorum = globals.updateQuorum,
      clock = Some(clock.fold(PosixClock.total(geometry))(_(ledger.era))),
      evaluator = evaluator,
      decoder = decoder,
      verdicts = verdicts,
      referenceBuild = globals.referenceBuild
    )

  /** The constants the rules of the era-indexed state read. */
  def rules: Globals = Globals(globals, geometry)

  /** How a transaction of another era is taken: the selected reference build's way (REF-3). */
  def injection: EraInjection = EraInjection.of(globals.referenceBuild)

/** A transaction as a client or a peer hands it over: the era its `GenTx` names, as its index in
  * the hard-fork combinator's era list (`[era, tx]`, `dispatchDecoder`,
  * `HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:362-374`, ouroboros-consensus
  * 82ecba32), and the transaction's bytes.
  */
final case class Submission(era: Int, raw: Bytes)

/** The mempool as a value. The invariant, for every state reachable through this API:
  *
  * `entries` applied in order to `base` through the transaction rules succeed and give `current`,
  * and every entry is of the era of `base` and `current`.
  *
  * `base` is the complete ledger ticked to the successor of the chain tip the pool was last
  * synchronised with, in the era of that slot ([[PoolLedger]]). `tipSlot` is -1 at Origin, making
  * its successor slot zero. Every operation is a pure function; [[Mempool]] serialises them.
  *
  * @param tipHash
  *   The hash of that chain tip, none at Origin: the reference's `snapshotStateHash`, the
  *   `pointHash` of the tip the pool's state was computed for (consensus 82ecba329
  *   `Mempool/Impl/Common.hs` `snapshotFromTxSeq`), which the forger traces
  *   (`TraceForgingMempoolSnapshot`).
  */
final case class MempoolState(
    base: Either[ConwayView.Unavailable, PoolLedger],
    tipSlot: BigInt,
    current: Either[ConwayView.Unavailable, PoolLedger],
    entries: Vector[Entry],
    bytes: Long,
    nextTicket: Long,
    capacityOverride: Option[Word32],
    tipHash: Option[Bytes] = None
):
  /** The era of the pool's ledger, which its transactions are of; none while it has no ledger. */
  def era: Option[Era] = base.toOption.map(_.era)

  /** The capacity: `computeMempoolCapacity` of the parameters of the ticked ledger at the base of
    * the pool, in the measure of its era ([[Capacity.of]]), so it follows a parameter change at the
    * next revalidation ([[sync]]), and is zero while there is no ledger. A pool over its capacity
    * is not trimmed.
    */
  def capacity: TxMeasure =
    base.fold(_ => TxMeasure.zero, l => Capacity.of(l, capacityOverride))

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

  /** Validate and append a transaction of the pool's own era (original bytes) submitted from
    * `source`: a submission that names that era.
    */
  def add(raw: Bytes, env: MempoolEnv, source: Source): Either[Rejection, (MempoolState, Entry)] =
    add(Submission(era.fold(Era.Conway.index)(_.index), raw), env, source)

  /** Validate and append a submitted transaction from `source`. It is read in the era it names, and
    * one of another era than the ledger's is brought into it as the selected build does
    * ([[EraInjection]]) or refused with the era mismatch. A remote transaction may be held with the
    * `is_valid` flag it did not carry ([[PoolLedger.admit]]). What is held and relayed is the
    * transaction the reference relays ([[Entry.raw]]): the received parts and the flag of the
    * verdict, in the record of the ledger's era.
    *
    * The order is that of `Mempool/Update.hs` `doAddTx` and `pureTryAddTx`: the first measure
    * (size, and execution units from Alonzo, against the parameters), the capacity in those
    * components, the second measure (reference scripts against the state after the held
    * transactions, in Conway), the capacity in all of them, and only then the rules of the ledger.
    * A refusal of a measure is the only failure returned; the ledger does not run. In Conway,
    * Scarab checks for a duplicate between the first measure and the capacity: the reference has
    * none (a duplicate fails the MEMPOOL rule, after waiting for room), so a duplicate here is
    * answered even by a full pool. A held transaction passes both measures, which its revalidation
    * keeps true (the LEDGER rule checks the same limits), so the check does not hide a measure
    * refusal. Before Conway there is no MEMPOOL rule and so no such check: LEDGER refuses a
    * duplicate, whose inputs the held transaction spent.
    */
  def add(
      submission: Submission,
      env: MempoolEnv,
      source: Source
  ): Either[Rejection, (MempoolState, Entry)] =
    MempoolState.read(submission).flatMap(add(_, env, source))

  /** [[add]] of a submission already read in its era ([[MempoolState.read]]). */
  def add(
      read: MempoolState.Read,
      env: MempoolEnv,
      source: Source
  ): Either[Rejection, (MempoolState, Entry)] =
    for
      ledger <- current.leftMap(Rejection.NoLedger(_))
      baseLedger <- base.leftMap(Rejection.NoLedger(_))
      in <- MempoolState.inject(read, env.injection, ledger.era)
      id = in.parts.id
      tx = in.tx
      // the measures and the rules run as `pureTryAddTx` does in the caller's thread: an exception
      // of theirs is no verdict and ends the submitter's connection, not the node (issue 564)
      judged <- MempoolState.judging(
        for
          phase1 <- Capacity.phase1(tx, ledger).leftMap(Rejection.Limits(id, _))
          _ <- Either.cond(
            ledger.era != Era.Conway || !contains(id),
            (),
            Rejection.AlreadyHeld(id)
          )
          _ <- room(phase1)
          refScriptBytes <- Capacity
            .phase2(ledger, tx)
            .leftMap(f => Rejection.Ledger(LedgerFailure.Rejected(id, f)))
          _ <- room(phase1.copy(refScriptsBytes = refScriptBytes))
          // the era's rule judges the transaction: in Conway MEMPOOL (`Rules/Mempool.hs`,
          // [[Mempool]]), which refuses one none of whose inputs is unspent as "probably a
          // duplicate" with that alone, then LEDGER; before Conway LEDGER
          admitted <- ledger
            .admit(tx, env.txEnv(tipSlot, baseLedger), env.rules, source)
            .leftMap(Rejection.Ledger(_))
          (next, held) = admitted
        yield (refScriptBytes, next, held)
      )
      (refScriptBytes, next, held) = judged
    yield
      // The flag is neither hashed nor signed (the id is the hash of the body alone), so the
      // transaction is relayed with its other parts as received and the flag of the verdict, as
      // the reference relays the transaction it rebuilt.
      val entry =
        Entry(ledger.era, in.parts.copy(isValid = held.isValid), held, nextTicket, refScriptBytes)
      (
        copy(
          current = Right(next),
          entries = entries :+ entry,
          bytes = bytes + entry.size,
          nextTicket = nextTicket + 1
        ),
        entry
      )

  /** Move to a new chain tip: keep, in order, the transactions that still apply to `ledger` and
    * report the others with the rule that now refuses them. Transactions that a block contains fail
    * here because their inputs are spent. A transaction the rules have no verdict on (a crypto
    * provider or the evaluator failed on it, or the rules threw) is dropped too, with that reason,
    * and the revalidation goes on: a failure of the node's own implementation on one held
    * transaction does not stop the node (owner decision 2026-10-28, issue 567; the reference's
    * revalidation watcher is linked to its node, `Mempool/Init.hs` 64–77, and stops it).
    *
    * When `ledger` is of another era than the held transactions, each is brought into it as the
    * selected build does ([[EraInjection]]): the reference's `reapplyTxs` of the hard-fork
    * combinator (`HardFork/Combinator/Mempool.hs:144-239`, ouroboros-consensus 82ecba32; lines
    * 132-222 at 808e4005). One that does not reach the era leaves the pool with the era mismatch;
    * the others are validated there as [[EraInjection.revalidation]] says, and keep their tickets.
    */
  def sync(
      ledger: Either[ConwayView.Unavailable, PoolLedger],
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
            MempoolState.judging(
              state.current
                .leftMap(Rejection.NoLedger(_))
                .flatMap(MempoolState.revalidate(entry, _, l, env, slot))
            ) match
              case Right((next, kept)) =>
                (
                  state.copy(
                    current = Right(next),
                    entries = state.entries :+ kept,
                    bytes = state.bytes + kept.size
                  ),
                  dropped
                )
              // a refusal, or no verdict (`Ledger(Unverifiable | Unavailable)`, `RulesDefect`):
              // the transaction leaves the pool with its reason
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
  /** A held transaction that revalidation has no verdict on: a crypto provider failed on one of its
    * witnesses, or the evaluator on one of its scripts. In the reference the exception aborts the
    * state operation, so the pool keeps its state and nothing is traced (consensus 82ecba32
    * `Mempool/Update.hs` 467–495 and 587–622, `withTMVar`), and the revalidation watcher, linked to
    * the node, stops it (`Mempool/Init.hs` 64–77).
    */
  type NoVerdict = LedgerFailure.Unverifiable | LedgerFailure.Unavailable

  /** A credential as the ledger's `Show` instance prints it. */
  def showCredential(c: Credential): String = c match
    case Credential.Key(h)    => "KeyHashObj (KeyHash {unKeyHash = \"" + h.hex + "\"})"
    case Credential.Script(h) => "ScriptHashObj (ScriptHash \"" + h.hex + "\")"

  /** The reference-script bytes of the transaction's inputs and reference inputs in `ledger`: the
    * ledger's measure over their union ([[RefScripts.refScriptsSize]]), so an output that is both
    * spent and a reference input counts once, as `txMeasureRefScripts` measures it with
    * `txNonDistinctRefScriptsSize` (`Shelley/Ledger/Mempool.hs:714-726`, ouroboros-consensus
    * 82ecba32; `Conway/UTxO.hs:164-170`, cardano-ledger f649f975).
    */
  def refScriptBytes(ledger: MempoolLedger, tx: Tx): Long =
    RefScripts.refScriptsSize(ledger.utxo, tx.body)

  def empty(
      ledger: Either[ConwayView.Unavailable, PoolLedger],
      tipSlot: BigInt,
      capacityOverride: Option[Word32],
      tipHash: Option[Bytes] = None
  ): MempoolState =
    MempoolState(ledger, tipSlot, ledger, Vector.empty, 0L, 1L, capacityOverride, tipHash)

  /** A submission as read before the ledger is consulted: a transaction of a Shelley-based era up
    * to Conway, decoded in that era, or the index of an era whose transactions the pool does not
    * read (Byron, Dijkstra, or no era of the combinator's list), which is refused with the era
    * mismatch.
    */
  enum Read:
    case Decoded(era: Era, transaction: EraTx)
    case Foreign(index: Int)

  /** The submission read in the era it names ([[Read]]). */
  def read(submission: Submission): Either[Rejection, Read] =
    Era.fromIndex(BigInt(submission.era)) match
      case Some(
            era @ (Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage | Era.Conway)
          ) =>
        parseIn(submission.raw, era).map(p => Read.Decoded(era, EraTx(p.parts, p.tx)))
      case Some(Era.Byron | Era.Dijkstra) | None => Right(Read.Foreign(submission.era))

  /** The transaction read, in the ledger's era `to`, or the era mismatch. */
  private[mempool] def inject(
      read: Read,
      injection: EraInjection,
      to: Era
  ): Either[Rejection, EraTx] =
    read match
      case Read.Foreign(index) => Left(Rejection.WrongEra(index, to))
      case Read.Decoded(from, transaction) =>
        decoding(
          injection.into(transaction, from, to).leftMap(e => Rejection.WrongEra(e.index, to))
        )

  /** A held transaction validated again on `current`, the state after the transactions kept before
    * it, whose base `ledger` is ticked to the successor of `slot`: in its own era it is applied
    * without the static checks (`reapplyTx`); of another era, it is brought into the ledger's
    * ([[EraInjection.into]]) and validated as the build says ([[EraInjection.revalidation]]).
    */
  private def revalidate(
      entry: Entry,
      current: PoolLedger,
      ledger: PoolLedger,
      env: MempoolEnv,
      slot: BigInt
  ): Either[Rejection, (PoolLedger, Entry)] =
    val txEnv = env.txEnv(slot, ledger)
    if entry.era == ledger.era then
      current.reapply(entry.tx, txEnv, env.rules).bimap(Rejection.Ledger(_), _ -> entry)
    else
      for
        in <- decoding(
          env.injection
            .into(EraTx(entry.parts, entry.tx), entry.era, ledger.era)
            .leftMap(e => Rejection.WrongEra(e.index, ledger.era))
        )
        moved = entry.copy(era = ledger.era, parts = in.parts, tx = in.tx)
        out <- env.injection.revalidation match
          case EraInjection.Revalidation.Reapply =>
            current.reapply(in.tx, txEnv, env.rules).bimap(Rejection.Ledger(_), _ -> moved)
          case EraInjection.Revalidation.Apply =>
            current
              .admit(in.tx, txEnv, env.rules, Source.Remote)
              .bimap(
                Rejection.Ledger(_),
                (next, held) =>
                  next -> moved.copy(parts = in.parts.copy(isValid = held.isValid), tx = held)
              )
      yield out

  /** A submitted transaction is read as the reference's mempool reads it, with no budget but its
    * size (`unwrapCBORinCBOR (eraDecoder @era decCBOR)`, `Shelley/Ledger/Mempool.hs:261-266`,
    * ouroboros-consensus 82ecba32): its channel bounds the bytes and the rules its size
    * (`maxTxSize`, #749).
    */
  private val limits = Cbor.Limits.Unbounded

  /** A parsed submission: its id (the hash of the original body), the decoded transaction and the
    * original bytes of its parts with the flag it carried.
    */
  final case class Parsed(id: Bytes, tx: Tx, parts: Block.Transaction)

  /** Split a whole Conway transaction ([[Tx.envelopeParts]]) keeping each component's original
    * bytes.
    */
  def parse(raw: Bytes): Either[Rejection, (Bytes, Tx)] =
    parseParts(raw).map(p => (p.id, p.tx))

  def parseParts(raw: Bytes): Either[Rejection, Parsed] = parseIn(raw, Era.Conway)

  /** [[parseParts]] of a transaction of `era`. */
  def parseIn(raw: Bytes, era: Era): Either[Rejection, Parsed] = decoding(decodeParts(raw, era))

  /** `judge` with an exception of the rules as [[Rejection.RulesDefect]]: no verdict on the
    * transaction, the connection's end rather than the node's (issue 564).
    */
  private[mempool] def judging[A](judge: => Either[Rejection, A]): Either[Rejection, A] =
    Either.catchNonFatal(judge).fold(cause => Left(Rejection.RulesDefect(cause)), identity)

  /** `decode` with an exception of the decoders as [[Rejection.InputDefect]]: a defect of the
    * submitted bytes' connection, never a refusal of the transaction (issue 465).
    */
  private[mempool] def decoding[A](decode: => Either[Rejection, A]): Either[Rejection, A] =
    Either.catchNonFatal(decode).fold(cause => Left(Rejection.InputDefect(cause)), identity)

  /** [[parseIn]] with the decoders' own exceptions not yet caught. */
  private def decodeParts(raw: Bytes, era: Era): Either[Rejection, Parsed] =
    // a transaction holding a `RecD` record written as an empty indefinite list is read as the
    // reference reads it, its parts spanning the fields after the list (`DetachedRecords`)
    DetachedRecords.read(raw, limits, DetachedRecords.Shapes.transaction(era))(e =>
      Rejection.Malformed(TransactionDecodeProblem.Syntax(e))
    )(envelope(_, era))

  /** The parts of the transaction `root` of `era`, and the transaction they make. */
  private def envelope(root: Node, era: Era): Either[Rejection, Parsed] =
    def malformed(cause: LedgerDecodeError) =
      Rejection.Malformed(TransactionDecodeProblem.Ledger(cause))
    // with or without its validity flag, as the reference reads it
    for
      transaction <- Tx.envelopeParts(root, era).leftMap(malformed)
      tx <- Tx.fromBlock(transaction, era).leftMap(malformed)
    yield Parsed(Blake2b.hash256.hash(transaction.body), tx, transaction)
