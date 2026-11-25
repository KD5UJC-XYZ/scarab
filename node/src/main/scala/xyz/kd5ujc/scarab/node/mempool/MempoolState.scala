// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.ledger.{CapacityDimension, TransactionDecodeProblem, MempoolProblem}
import xyz.kd5ujc.scarab.ledger.byron.ByronTransport
import scala.concurrent.duration.{Duration, FiniteDuration}
import cats.data.NonEmptyList
import cats.syntax.all.*
import cats.Id
import xyz.kd5ujc.scarab.network.{DiffTime, Word32}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node}
import xyz.kd5ujc.scarab.node.read.ConwayView
import xyz.kd5ujc.scarab.protocol.{Era, LeiosTxHash, ReferenceBuild}
import xyz.kd5ujc.scarab.storage.LeiosEb
import cats.data.NonEmptyChain
import xyz.kd5ujc.scarab.ledger.era.EraTxError
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayLedgerPredFailure, DijkstraMempoolPredFailure}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Dijkstra}
import xyz.kd5ujc.scarab.ledger.{Block, DetachedRecords, EpochGeometry, PosixClock}
import xyz.kd5ujc.scarab.ledger.{
  Credential,
  LedgerDecodeError,
  LedgerFailure,
  LedgerGlobals,
  PredicateFailure,
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

  /** The chain has no ledger state the pool validates against (it is in Byron, or in Dijkstra under
    * a release build, or its state cannot be read): the pool takes nothing until it has.
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

  /** The validation of the transaction `id`, submitted in `era`, took `took`, longer than the soft
    * limit, or was cut off by the hard one for a local client, and the era of the pool's ledger has
    * an error for it ([[Mempool.tooSlow]]): the reference's soft rejection, `MempoolTxTooSlow`
    * (ouroboros-consensus 82ecba32 `Mempool/Update.hs` 266-296).
    */
  case TooSlow(id: Bytes, era: Era, took: FiniteDuration)

  /** The hard limit cut the validation off where the reference throws ([[MempoolTimeout]]): no
    * verdict, and the connection the transaction came from ends.
    */
  case TimedOut(timeout: MempoolTimeout)

  /** Dijkstra's refusal of the transaction `id` (`DijkstraApplyTxError`, cardano-ledger 9df9f98c
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:88-89`): MEMPOOL's failures in the
    * reference's order, or a measure's (`txMeasurePhase1`, `txMeasurePhase2`), each in the era's
    * types.
    */
  case DijkstraRefused(id: Bytes, failures: NonEmptyChain[DijkstraMempoolPredFailure[Dijkstra]])

  /** No verdict on a Dijkstra transaction, its id when it was read: the node's own limit or defect
    * ([[DijkstraUnjudged]]), which no peer is charged for.
    */
  case DijkstraUnjudged(id: Option[Bytes], cause: xyz.kd5ujc.scarab.node.mempool.DijkstraUnjudged)

  /** Conway's refusal of the transaction `id` (`ConwayApplyTxError`, cardano-ledger f649f975
    * `eras/conway/impl/src/Cardano/Ledger/Conway.hs:50-53`): MEMPOOL's failures in the reference's
    * order, or a measure's (`txMeasurePhase1`, `txMeasurePhase2`), each in the era's types.
    */
  case ConwayRefused(id: Bytes, failures: NonEmptyChain[ConwayLedgerPredFailure[Conway]])

  /** No verdict on a Conway transaction, its id when it was read: the node's own limit or defect
    * ([[ConwayUnjudged]]), which no peer is charged for.
    */
  case ConwayUnjudged(id: Option[Bytes], cause: xyz.kd5ujc.scarab.node.mempool.ConwayUnjudged)

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
    case TooSlow(id, era, took) => MempoolProblem.TooSlow(took, era, id).message
    case TimedOut(timeout)      => timeout.message
    case DijkstraRefused(id, fs) =>
      s"tx ${id.hex.take(16)} is refused by Dijkstra's rules: ${fs.toChain.toVector.mkString("; ")}"
    case DijkstraUnjudged(id, cause) =>
      s"no verdict on ${id.fold("the transaction")(i => s"tx ${i.hex.take(16)}")}: $cause"
    case ConwayRefused(id, fs) =>
      s"tx ${id.hex.take(16)} is refused by Conway's rules: ${fs.toChain.toVector.mkString("; ")}"
    case ConwayUnjudged(id, cause) =>
      s"no verdict on ${id.fold("the transaction")(i => s"tx ${i.hex.take(16)}")}: ${cause.message}"

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

/** A held transaction, of the era of the pool's ledger: one of an era from Shelley to Babbage, its
  * parts with the flag of the verdict and the transaction they make in that era ([[Held.Flat]]);
  * one of Conway ([[Held.InConway]]) or of Dijkstra ([[Held.InDijkstra]]), each judged by its era's
  * rules, with what it was validated with.
  */
enum Held:
  case Flat(of: Era, parts: Block.Transaction, tx: Tx)
  case InConway(held: ConwayAdmitted)
  case InDijkstra(held: DijkstraAdmitted)

  def era: Era = this match
    case Flat(era, _, _) => era
    case InConway(_)     => Era.Conway
    case InDijkstra(_)   => Era.Dijkstra

  /** The transaction as it was read, in its era. */
  def in: InEra = this match
    case Flat(era, parts, tx) => InEra.Flat(era, EraTx(parts, tx))
    case InConway(held)       => InEra.InConway(held.read)
    case InDijkstra(held)     => InEra.InDijkstra(held.read)

/** A held transaction ([[Held]]). `ticket` grows with arrival order and is how peers are given each
  * transaction once. `refScriptBytes` is the size of the reference scripts the transaction names
  * (the union of its spent and reference inputs, each output once, as the ledger's
  * `txNonDistinctRefScriptsSize`), measured against the state it was added to from Conway, and zero
  * before (the measure of those eras has no such component, [[Capacity]]); LocalTxMonitor reports
  * it. `validation` is how long its admission's timed validation took
  * ([[MempoolState.validationTime]]), which the reference records with each held transaction and
  * keeps when the pool is validated again (ouroboros-consensus 82ecba32 `Mempool/Impl/Common.hs`
  * 368-376, 444-445); zero for a transaction held without a clock ([[MempoolState.add]]).
  */
final case class Entry(
    held: Held,
    ticket: Long,
    refScriptBytes: Long,
    validation: FiniteDuration
):
  /** The era of the pool's ledger it is held in. */
  def era: Era = held.era

  /** The id: the hash of the body. */
  def id: Bytes = held.in.id

  /** The transaction as the reference holds, lists and relays it ([[InEra.raw]]). */
  lazy val raw: Bytes = held.in.raw

  def size: Int = raw.size

  /** The transaction as an endorser block lists it, its hash and its bytes, for a Dijkstra one:
    * `leiosTxHashOfGenTx` and `leiosTxBytesOfGenTx` (ouroboros-consensus-cardano 2416493b
    * `Shelley/Ledger/Leios.hs:155-159`), the bytes the forger writes in its endorser block
    * (`serializeTxForLeios`, [[DijkstraTx.forBlockInclusion]]) of the transaction with the flag of
    * its verdict, and their hash ([[LeiosEb.hashTx]]). None for a transaction of an earlier era, as
    * the combinator's has none (`Cardano/Block.hs:1690-1696`). Computed when the entry is made, at
    * its admission, as the reference keeps it with the transaction's ticket
    * (`ValidatedTxWithDiffs`, `Mempool/Impl/Common.hs:493-523`), so a revalidation that keeps the
    * entry does not hash it again.
    */
  val leiosTx: Option[(LeiosTxHash, Bytes)] = held match
    case Held.InDijkstra(admitted) =>
      val bytes = DijkstraTx.forBlockInclusion(admitted.read.tx)
      Some(LeiosEb.hashTx(bytes) -> bytes)
    case Held.Flat(_, _, _) | Held.InConway(_) => None

  /** What it counts for against the pool's capacity ([[Capacity.measure]]), computed once, as the
    * reference keeps a transaction's measure in its ticket (`txTicketSize`, ouroboros-consensus
    * 2416493b
    * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/TxSeq.hs:62-69`).
    */
  lazy val measure: TxMeasure = Capacity.measure(this)

object Entry:
  /** A held transaction of an era from Shelley to Babbage. */
  def apply(
      era: Era,
      parts: Block.Transaction,
      tx: Tx,
      ticket: Long,
      refScriptBytes: Long = 0L,
      validation: FiniteDuration = Duration.Zero
  ): Entry = Entry(Held.Flat(era, parts, tx), ticket, refScriptBytes, validation)

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
      network = globals.network.wire,
      firstSlotNextEpoch = geometry.firstSlot(ledger.epoch + 1),
      stabilityWindow = geometry.stabilityWindow.toBigInt,
      txIndex = 0,
      updateQuorum = globals.updateQuorum,
      clock = Some(clock.fold(PosixClock.total(globals.systemStart, geometry))(_(ledger.era))),
      evaluator = evaluator,
      decoder = decoder,
      verdicts = verdicts,
      referenceBuild = globals.referenceBuild
    )

  /** The constants the rules of the era-indexed state read. */
  def rules: Globals = Globals(globals, geometry)

  /** How a transaction of another era is taken: the selected reference build's way (REF-3). */
  def injection: EraInjection = EraInjection.of(globals.referenceBuild)

  /** Where one that does not reach the ledger's era is refused: the selected build's place (REF-3).
    */
  def mismatch: EraInjection.Mismatch = EraInjection.Mismatch.of(globals.referenceBuild)

/** A transaction as a client or a peer hands it over: the era its `GenTx` names, as its index in
  * the hard-fork combinator's era list (`[era, tx]`, `dispatchDecoder`,
  * `HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:362-374`, ouroboros-consensus
  * 82ecba32), and the transaction's bytes.
  */
final case class Submission(era: Int, raw: Bytes)

/** A revalidation of held Dijkstra transactions ([[MempoolState.dijkstraRevalidated]]): those that
  * still apply, in order, with what they were admitted with, those the rules have no verdict on,
  * and the state the kept ones leave.
  */
final case class DijkstraRevalidation(
    kept: Vector[(Entry, DijkstraAdmitted)],
    withheld: Vector[(Entry, Rejection)],
    state: DijkstraState
)

object DijkstraRevalidation:
  /** None revalidated yet, on `state`. */
  def from(state: DijkstraState): DijkstraRevalidation =
    DijkstraRevalidation(Vector.empty, Vector.empty, state)

/** The mempool as a value. The invariant, for every state reachable through this API:
  *
  * `entries` applied in order to `base` through the transaction rules succeed and give `current`,
  * and every entry is of the era of `base` and `current`.
  *
  * `base` is the complete ledger ticked to the successor of the chain tip the pool was last
  * synchronised with, in the era of that slot ([[PoolLedger]]). `tipSlot` is -1 at Origin, making
  * its successor slot zero. Every operation is a pure function; [[Mempool]] serialises them.
  *
  * The entries, their bytes, their measures' sum, their ids and their Leios index change together,
  * in one step of [[MempoolState.appended]], [[MempoolState.cleared]] or
  * [[MempoolState.retaining]]; the constructor and `copy` are private, so no state has a sum or an
  * index that is not its entries'.
  *
  * @param entries
  *   The held transactions, oldest first, their tickets strictly increasing: a transaction is
  *   appended with the next ticket ([[MempoolState.add]]), and removal and revalidation keep the
  *   order and each ticket, as the reference's sequence is ordered by ticket
  *   (`Mempool/TxSeq.hs:72-79` at ouroboros-consensus 2416493b). [[after]] relies on the order.
  * @param used
  *   What the held transactions count for, the sum of their measures ([[Entry.measure]]), kept as
  *   they are added and removed, as the reference's sequence caches its measure (`toSize` of
  *   `isTxs`, ouroboros-consensus 2416493b `Mempool/TxSeq.hs:115-124,287-288`), so that admission
  *   does not measure every held transaction again.
  * @param ids
  *   The held transactions' ids (`isTxIds`, `Mempool/Impl/Common.hs:131-137` at 2416493b), kept in
  *   step with the entries.
  * @param leiosTxIndex
  *   The held transactions by the hash an endorser block lists them by, with their bytes as it
  *   lists them (`isLeiosTxIndex`, ouroboros-consensus 2416493b `Mempool/Impl/Common.hs:138-144`,
  *   read by `getLeiosTxIndex`, `Mempool/API.hs:241-244`): what the Leios fetch logic pulls an
  *   endorser block's transactions from when its body arrives. Each entry's key is computed at its
  *   admission ([[Entry.leiosTx]]), and the index is kept in step with the entries by every
  *   addition, removal and revalidation, as the reference keeps it with `isTxs`
  *   (`validateNewTransaction`, `Mempool/Impl/Common.hs:467-523`; `revalidateTxsFor'`, 575-644);
  *   empty before Dijkstra.
  * @param tipHash
  *   The hash of that chain tip, none at Origin: the reference's `snapshotStateHash`, the
  *   `pointHash` of the tip the pool's state was computed for (consensus 82ecba329
  *   `Mempool/Impl/Common.hs` `snapshotFromTxSeq`), which the forger traces
  *   (`TraceForgingMempoolSnapshot`).
  */
final case class MempoolState private (
    base: Either[ConwayView.Unavailable, PoolLedger],
    tipSlot: BigInt,
    current: Either[ConwayView.Unavailable, PoolLedger],
    entries: Vector[Entry],
    bytes: Long,
    used: TxMeasure,
    ids: Set[Bytes],
    leiosTxIndex: Map[LeiosTxHash, Bytes],
    nextTicket: Long,
    capacityOverride: Option[Word32],
    tipHash: Option[Bytes]
):
  /** The era of the pool's ledger, which its transactions are of; none while it has no ledger. */
  def era: Option[Era] = base.toOption.map(_.era)

  /** `protVerParity (leiosCurrentProtVer lcfg (isLedgerState st))` (`getLeiosTxIndex`,
    * `Mempool/Init.hs:135-140` at ouroboros-consensus 2416493b): the parity of the protocol version
    * of the pool's ledger after its transactions, none while it has no ledger.
    */
  def leiosParity: Option[xyz.kd5ujc.scarab.consensus.leios.ProtVerParity] =
    current.toOption.map(l =>
      xyz.kd5ujc.scarab.consensus.leios.ProtVerParity.of(l.params.protocolMajor)
    )

  /** The capacity: `computeMempoolCapacity` of the parameters of the ticked ledger at the base of
    * the pool, in the measure of its era ([[Capacity.of]]), so it follows a parameter change at the
    * next revalidation ([[sync]]), and is zero while there is no ledger. A pool over its capacity
    * is not trimmed.
    */
  def capacity: TxMeasure =
    base.fold(_ => TxMeasure.zero, l => Capacity.of(l, capacityOverride))

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

  def contains(id: Bytes): Boolean = ids.contains(id)

  /** The pool's own revalidated Dijkstra transactions as the snapshot of `slot` on the chain whose
    * tip's hash is `tip`, when they are that snapshot already (`getSnapshotFor` 's cache,
    * `getSnapshotUsingPolicyFor` with `UseCache`, ouroboros-consensus d91b3557
    * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/Query.hs:91-127`):
    * the pool is at that tip, and `slot` is the one after it, which the pool judged its
    * transactions at, or a later one that every held transaction's validity interval contains, and
    * the pool's ledger's range, the slots of its epoch by `geometry` (`ledgerSlotRange`,
    * `Shelley/Ledger/Mempool.hs:241-250` of ouroboros-consensus-cardano d91b3557): `cheapTick`,
    * where reapplying them would give the same. The range is the epoch's slots, not the epoch of
    * the state ticked to `slot`, which a tick past an epoch with no block does not advance to the
    * slot's. None otherwise, and while the pool's ledger is not Dijkstra's: the snapshot is then
    * revalidated ([[MempoolState.dijkstraSnapshot]]).
    */
  def dijkstraCached(
      tip: Option[Bytes],
      slot: BigInt,
      geometry: EpochGeometry
  ): Option[Vector[(Entry, DijkstraAdmitted)]] =
    val judgedAt = tipSlot + 1
    base.toOption.collect { case l: DijkstraLedger => l }.filter(_ => tip == tipHash).flatMap {
      ledger =>
        // as in a revalidation, a Dijkstra snapshot takes only the Dijkstra transactions
        val held = entries.flatMap(e =>
          e.held match
            case Held.InDijkstra(admitted)             => Vector(e -> admitted)
            case Held.Flat(_, _, _) | Held.InConway(_) => Vector.empty
        )
        lazy val cheapTick = slot > judgedAt &&
          (held.foldMap((_, admitted) => SlotRange.dijkstraTx(admitted.read.tx)) |+|
            SlotRange.epoch(geometry, ledger.epoch)).contains(slot)
        Option.when(slot == judgedAt || cheapTick)(held)
    }

  /** `entry` held after the others: the entries, their bytes, their measures' sum, their ids and
    * the Leios index in one step (`validateNewTransaction`'s `isTxs`, `isTxIds` and
    * `isLeiosTxIndex`, `Mempool/Impl/Common.hs:490-501` at ouroboros-consensus 2416493b).
    */
  private def appended(entry: Entry): MempoolState =
    copy(
      entries = entries :+ entry,
      bytes = bytes + entry.size,
      used = used + entry.measure,
      ids = ids + entry.id,
      leiosTxIndex = entry.leiosTx.fold(leiosTxIndex)(leiosTxIndex + _)
    )

  /** Nothing held: no entry, no bytes, nothing used, no id and an empty index. */
  private def cleared: MempoolState =
    copy(
      entries = Vector.empty,
      bytes = 0,
      used = TxMeasure.zero,
      ids = Set.empty,
      leiosTxIndex = Map.empty
    )

  /** The entries `keep` holds, in order, with their bytes and their keys alone. */
  private def retaining(keep: Entry => Boolean): MempoolState =
    entries.filter(keep).foldLeft(cleared)(_.appended(_))

  /** The pool with its ledger after the held transactions read from `ledger`: the same state, with
    * the inputs it reads completed from the UTxO store.
    */
  def withCurrent(ledger: PoolLedger): MempoolState = copy(current = Right(ledger))

  /** The pool with its base ledger `ledger`: the same state, with the inputs it reads completed. */
  def withBase(ledger: Either[ConwayView.Unavailable, PoolLedger]): MempoolState =
    copy(base = ledger)

  /** The pool as of the chain tip whose hash is `hash` ([[tipHash]]). */
  def at(hash: Option[Bytes]): MempoolState = copy(tipHash = hash)

  /** The pool's own fields with `held` in place of its entries, its bytes and its index rebuilt
    * from them: for tests that set up a pool's entries directly.
    */
  private[mempool] def holding(held: Vector[Entry], ticket: Long = nextTicket): MempoolState =
    held.foldLeft(cleared)(_.appended(_)).copy(nextTicket = ticket)

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
    * A refusal of a measure is the only failure returned; the ledger does not run. In Conway a
    * duplicate of a held transaction is refused where the reference's MEMPOOL rule refuses it,
    * first among the rules, so it waits for room as any other transaction; before Conway there is
    * no MEMPOOL rule, and LEDGER refuses it, its inputs being spent. A transaction that does not
    * reach the ledger's era is refused where the selected build refuses it
    * ([[EraInjection.Mismatch]]).
    */
  def add(
      submission: Submission,
      env: MempoolEnv,
      source: Source
  ): Either[Rejection, (MempoolState, Entry)] =
    MempoolState.read(submission, env.globals.referenceBuild).flatMap(add(_, env, source))

  /** [[add]] of a submission already read in its era ([[MempoolState.read]]), with no clock: the
    * entry records no validation time.
    */
  def add(
      read: MempoolState.Read,
      env: MempoolEnv,
      source: Source
  ): Either[Rejection, (MempoolState, Entry)] =
    for
      measured <- measure(read, env)
      refScriptBytes <- checked(measured)
      admitted <- admitted(measured, refScriptBytes, env, source)
    yield hold(admitted, Duration.Zero)

  /** The steps of an admission before the timed one, as `doAddTx` takes them before it times
    * `pureTryAddTx` (ouroboros-consensus 82ecba32 `Mempool/Update.hs` 205-238): the transaction in
    * the ledger's era, its first measure (size, and execution units from Alonzo, against the
    * parameters) and the capacity in those components. A transaction that does not reach the
    * ledger's era is refused here where the selected build refuses it in its first measure, and
    * otherwise measures nothing and is refused by the rules, after the waits and in the timed step
    * ([[EraInjection.Mismatch]]).
    */
  def measure(
      read: MempoolState.Read,
      env: MempoolEnv
  ): Either[Rejection, MempoolState.Measured] =
    for
      ledger <- current.leftMap(Rejection.NoLedger(_))
      baseLedger <- base.leftMap(Rejection.NoLedger(_))
      matched <- MempoolState.inject(read, env.injection, ledger.era) match
        case Right((submitted, in)) =>
          MempoolState.projected(ledger, in).map(MempoolState.Measured.Matched(submitted, _))
        case Left(mismatch: Rejection.WrongEra) =>
          env.mismatch match
            case EraInjection.Mismatch.InMeasure => Left(mismatch)
            case EraInjection.Mismatch.InRules =>
              Right(MempoolState.Measured.Unmatched(mismatch, MempoolState.named(read)))
        case Left(other) => Left(other)
      // the measures and the rules run as `pureTryAddTx` does in the caller's thread: an exception
      // of theirs is no verdict and ends the submitter's connection, not the node (issue 564)
      phase1 <- MempoolState.judging(
        for
          phase1 <- matched match
            case MempoolState.Measured.Matched(_, in) => MempoolState.phase1(ledger, in)
            // `txMeasurePhase1` of a transaction of another era: `Measure.zero`
            // (`HardFork/Combinator/Mempool.hs` 376, ouroboros-consensus 82ecba32)
            case MempoolState.Measured.Unmatched(_, _) => Right(TxMeasure.zero)
          _ <- room(phase1)
        yield phase1
      )
    yield MempoolState.Measured(ledger, baseLedger, matched, phase1)

  /** The timed step of an admission, `pureTryAddTx` (ouroboros-consensus 82ecba32
    * `Mempool/Update.hs` 321-451): the second measure (reference scripts against the state after
    * the held transactions, in Conway), the capacity in all its components, then, with a `capacity`
    * of time, the time the held transactions took ([[validationTime]]), and only then the rules of
    * the ledger. The pool waits while the held transactions took longer than `capacity` together
    * ([[MempoolState.Tried.NoTime]]), so one transaction can take them past it.
    */
  def tryAdd(
      measured: MempoolState.Measured,
      env: MempoolEnv,
      source: Source,
      capacity: Option[DiffTime]
  ): MempoolState.Tried =
    checked(measured).fold(
      rejection => MempoolState.Tried.Done(Left(rejection)),
      refScriptBytes =>
        capacity
          .filter(c => validationTime.toPicoseconds > c.toPicoseconds)
          .fold(MempoolState.Tried.Done(admitted(measured, refScriptBytes, env, source)))(c =>
            MempoolState.Tried.NoTime(validationTime, c)
          )
    )

  /** The second measure and the capacity in all its components: the reference-script bytes, none
    * for a transaction of another era (`txMeasurePhase2`, `HardFork/Combinator/Mempool.hs` 421,
    * ouroboros-consensus 82ecba32).
    */
  private def checked(measured: MempoolState.Measured): Either[Rejection, Long] =
    MempoolState.judging(
      for
        refScriptBytes <- measured.matched match
          case MempoolState.Measured.Matched(_, in)  => MempoolState.phase2(measured.ledger, in)
          case MempoolState.Measured.Unmatched(_, _) => Right(0L)
        _ <- room(measured.phase1.copy(refScriptsBytes = refScriptBytes))
      yield refScriptBytes
    )

  /** The era's rule judges the transaction: in Conway MEMPOOL (`Rules/Mempool.hs`, [[Mempool]]),
    * which refuses one none of whose inputs is unspent as "probably a duplicate" with that alone,
    * then LEDGER; before Conway LEDGER. A duplicate of a held transaction is refused first, as
    * MEMPOOL refuses it, in Conway. A transaction that does not reach the ledger's era is refused
    * here with the era mismatch, as the hard-fork combinator's `applyTx` refuses it (`applyHelper`,
    * `HardFork/Combinator/Mempool.hs` 501-506, ouroboros-consensus 82ecba32).
    */
  private def admitted(
      measured: MempoolState.Measured,
      refScriptBytes: Long,
      env: MempoolEnv,
      source: Source
  ): Either[Rejection, MempoolState.Admitted] =
    measured.matched match
      case MempoolState.Measured.Unmatched(mismatch, _) => Left(mismatch)
      case MempoolState.Measured.Matched(_, in) =>
        MempoolState.judging(
          for
            _ <- Either.cond(
              measured.ledger.era != Era.Conway || !contains(in.id),
              (),
              Rejection.AlreadyHeld(in.id)
            )
            admitted <- MempoolState
              .admit(measured.ledger, in, env, tipSlot, measured.base, source)
              .map((next, held) => MempoolState.Admitted(next, held, refScriptBytes))
          yield admitted
        )

  /** The pool holding an admitted transaction whose validation took `took`. */
  def hold(admitted: MempoolState.Admitted, took: FiniteDuration): (MempoolState, Entry) =
    val entry = Entry(admitted.held, nextTicket, admitted.refScriptBytes, took)
    (
      appended(entry).copy(current = Right(admitted.next), nextTicket = nextTicket + 1),
      entry
    )

  /** How long the validations of the held transactions took, together: the time component of the
    * reference's measure of its transactions (`TxSeq.toSize`, ouroboros-consensus 82ecba32
    * `Mempool/Update.hs` 423-426, `Mempool/API.hs` 459-538).
    */
  def validationTime: DiffTime =
    DiffTime.picoseconds(entries.map(e => DiffTime.of(e.validation).toPicoseconds).sum)

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
          cleared.copy(base = Left(why), tipSlot = slot, current = Left(why)),
          entries.map(_ -> Rejection.NoLedger(why))
        )
      case Right(l) =>
        val start = cleared.copy(base = Right(l), tipSlot = slot, current = Right(l))
        entries.foldLeft((start, Vector.empty[(Entry, Rejection)])) {
          case ((state, dropped), entry) =>
            MempoolState.judging(
              state.current
                .leftMap(Rejection.NoLedger(_))
                .flatMap(MempoolState.revalidate(entry, _, l, env, slot))
            ) match
              case Right((next, kept)) =>
                (state.appended(kept).copy(current = Right(next)), dropped)
              // a refusal, or no verdict (`Ledger(Unverifiable | Unavailable)`, `RulesDefect`):
              // the transaction leaves the pool with its reason
              case Left(rejection) => (state, dropped :+ (entry -> rejection))
        }

  /** Remove transactions by id (and, because later ones may spend their outputs, revalidate the
    * rest).
    */
  def remove(ids: Set[Bytes], env: MempoolEnv): (MempoolState, Vector[(Entry, Rejection)]) =
    retaining(e => !ids(e.id)).sync(base, tipSlot, env)

  /** The longest prefix of the pool, in order, within `maxBytes`. */
  def forBlock(maxBytes: Long): Vector[Entry] =
    entries
      .scanLeft((0L, Option.empty[Entry]))((acc, e) => (acc._1 + e.size, Some(e)))
      .drop(1)
      .takeWhile(_._1 <= maxBytes)
      .flatMap(_._2)

  /** Entries with a ticket above `ticket`, oldest first: the second part of `splitAfterTicketNo`
    * (`Mempool/TxSeq.hs:194-205` at ouroboros-consensus 2416493b), as `snapshotTxsAfter` takes it
    * (`Mempool/Impl/Common.hs:750-758`). The entries are in ticket order, so the split is found by
    * a binary search, not by reading every entry.
    */
  def after(ticket: Long): Vector[Entry] =
    import scala.collection.Searching.{Found, InsertionPoint}
    entries.drop(entries.view.map(_.ticket).search(ticket) match
      case Found(at)          => at + 1
      case InsertionPoint(at) => at)

  /** Whether an entry has a ticket above `ticket`: the newest has the largest. */
  def hasAfter(ticket: Long): Boolean = entries.lastOption.exists(_.ticket > ticket)

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

  /** The inputs whose stored outputs the rules of `txs` read, the pool fetching them from the UTxO
    * store as the block path does: of a flat transaction its spent, reference and collateral inputs
    * ([[xyz.kd5ujc.scarab.storage.UtxoStore.readSet]]); of a Conway one every input of its body
    * ([[ConwayRead.inputs]]); of a Dijkstra one every level's ([[DijkstraLedger.readSet]]).
    */
  def readSet(txs: Iterable[InEra]): Vector[xyz.kd5ujc.scarab.ledger.TxIn] =
    val all = txs.toVector
    val flat = all.collect { case InEra.Flat(_, t) => t.tx }
    val conway = all.collect { case InEra.InConway(read) => read }
    val dijkstra = all.collect { case InEra.InDijkstra(read) => read }
    xyz.kd5ujc.scarab.storage.UtxoStore.readSet(flat) ++ conway.iterator.flatMap(_.inputs) ++
      DijkstraLedger.readSet(dijkstra)

  def empty(
      ledger: Either[ConwayView.Unavailable, PoolLedger],
      tipSlot: BigInt,
      capacityOverride: Option[Word32],
      tipHash: Option[Bytes] = None
  ): MempoolState =
    MempoolState(
      ledger,
      tipSlot,
      ledger,
      Vector.empty,
      0L,
      TxMeasure.zero,
      Set.empty,
      Map.empty,
      1L,
      capacityOverride,
      tipHash
    )

  /** A submission as read before the ledger is consulted: a transaction of a Shelley-based era,
    * decoded in that era ([[InEra]]), or the index of an era whose transactions the pool does not
    * take, which is refused with the era mismatch: Byron, whose payload its decoder has read whole,
    * Dijkstra outside the Leios build, whose transaction is not read, or no era of the combinator's
    * list.
    */
  enum Read:
    case Decoded(transaction: InEra)
    case Foreign(index: Int)

  /** The submission read in the era it names under `build` ([[Read]]): a transaction of an era from
    * Shelley to Conway by that era's decoders ([[parseIn]]), Dijkstra's by its mempool decoder in
    * the Leios build (`decodeDijkstraTopTx`, [[DijkstraRead.submitted]]), and Byron's
    * `MempoolPayload` whole by the decoder of its kind, as the reference's `decodeByronGenTx` reads
    * it
    * (`ouroboros-consensus-cardano/src/byron/Ouroboros/Consensus/Byron/Ledger/Mempool.hs:338-339`,
    * ouroboros-consensus 82ecba32; [[ByronTransport.mempoolPayload]]), a payload it refuses being
    * malformed. The release builds do not read a Dijkstra transaction: their node never enters the
    * era (REF-3).
    */
  def read(submission: Submission, build: ReferenceBuild): Either[Rejection, Read] =
    Era.fromIndex(BigInt(submission.era)) match
      case Some(
            era @ (Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage | Era.Conway)
          ) =>
        parseIn(submission.raw, era).map(p => Read.Decoded(InEra.Flat(era, EraTx(p.parts, p.tx))))
      case Some(Era.Byron) =>
        decoding(
          ByronTransport
            .mempoolPayload(submission.raw)
            .bimap(
              e => Rejection.Malformed(TransactionDecodeProblem.ByronPayload(e)),
              _ => Read.Foreign(submission.era)
            )
        )
      case Some(Era.Dijkstra) =>
        build match
          case ReferenceBuild.Leios =>
            decoding(
              DijkstraRead
                .submitted(submission.raw)
                .bimap(dijkstraUnread, r => Read.Decoded(InEra.InDijkstra(r)))
            )
          case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 =>
            Right(Read.Foreign(submission.era))
      case None => Right(Read.Foreign(submission.era))

  /** Why a Dijkstra transaction is not read, by whose the failure is, as a block's
    * (`DijkstraBlockError.of`): the bytes', which the reference's decoder refuses too; or the
    * node's own, whose readings disagree, which is no verdict.
    */
  private def dijkstraUnread(cause: EraTxError): Rejection = cause match
    case EraTxError.Decode(e) => Rejection.Malformed(TransactionDecodeProblem.Ledger(e))
    case EraTxError.NotInEra(field) =>
      Rejection.Malformed(TransactionDecodeProblem.DijkstraField(field))
    case EraTxError.ScriptsReadAgain | EraTxError.WitnessDataReadAgain | EraTxError.OtherLevel |
        EraTxError.NoDelegatee | EraTxError.Vocab(_) | EraTxError.PParams(_) |
        EraTxError.StoredOutputDiffers | EraTxError.TranslationReadBack(_) =>
      Rejection.DijkstraUnjudged(None, DijkstraUnjudged.Projection(cause))

  /** The transaction read, in the ledger's era `to`, or the era mismatch. */
  private[mempool] def inject(
      read: Read,
      injection: EraInjection,
      to: Era
  ): Either[Rejection, (Era, InEra)] =
    read match
      case Read.Foreign(index) => Left(Rejection.WrongEra(index, to))
      case Read.Decoded(transaction) =>
        decoding(
          injection
            .into(transaction, to)
            .bimap(e => Rejection.WrongEra(e.index, to), transaction.era -> _)
        )

  /** `in`, a transaction of the ledger's era, as the ledger's rules read it: a Conway one read into
    * Conway's types too, in the layout of the Conway ledger ([[ConwayPoolLedger.read]]); any other
    * as it is.
    */
  private def projected(ledger: PoolLedger, in: InEra): Either[Rejection, InEra] =
    (ledger, in) match
      case (c: ConwayPoolLedger, InEra.Flat(_, t)) => decoding(c.read(t)).map(InEra.InConway(_))
      case (_: FlatLedger | _: DijkstraLedger, _) |
          (_: ConwayPoolLedger, InEra.InConway(_) | InEra.InDijkstra(_)) =>
        Right(in)

  /** The first measure of `in` against `ledger` ([[Capacity.phase1]], [[DijkstraLedger.phase1]]): a
    * transaction of another era than the ledger's is the era mismatch.
    */
  private def phase1(ledger: PoolLedger, in: InEra): Either[Rejection, TxMeasure] =
    (ledger, in) match
      case (flat: FlatLedger, InEra.Flat(_, t)) =>
        Capacity.phase1(t.tx, flat).leftMap(Rejection.Limits(t.parts.id, _))
      case (c: ConwayPoolLedger, InEra.InConway(t)) => c.phase1(t)
      case (d: DijkstraLedger, InEra.InDijkstra(t)) => d.phase1(t)
      case (_: FlatLedger, InEra.InDijkstra(_) | InEra.InConway(_)) |
          (_: ConwayPoolLedger, InEra.Flat(_, _) | InEra.InDijkstra(_)) |
          (_: DijkstraLedger, InEra.Flat(_, _) | InEra.InConway(_)) =>
        Left(Rejection.WrongEra(in.era.index, ledger.era))

  /** The second measure of `in` against `ledger`: the reference-script bytes in Conway
    * ([[ConwayPoolLedger.phase2]]) and in Dijkstra ([[DijkstraLedger.phase2]]); before Conway the
    * phase is trivial (`TrivialTxMeasurePhase2`, `Shelley/Ledger/Mempool.hs:522-544, 668-677,
    * 751-761`, ouroboros-consensus 82ecba32, Babbage's included): nothing is measured.
    */
  private def phase2(ledger: PoolLedger, in: InEra): Either[Rejection, Long] =
    (ledger, in) match
      case (_: FlatLedger, InEra.Flat(_, _))        => Right(0L)
      case (c: ConwayPoolLedger, InEra.InConway(t)) => c.phase2(t)
      case (d: DijkstraLedger, InEra.InDijkstra(t)) => d.phase2(t)
      case (_: FlatLedger, InEra.InDijkstra(_) | InEra.InConway(_)) |
          (_: ConwayPoolLedger, InEra.Flat(_, _) | InEra.InDijkstra(_)) |
          (_: DijkstraLedger, InEra.Flat(_, _) | InEra.InConway(_)) =>
        Left(Rejection.WrongEra(in.era.index, ledger.era))

  /** `in` admitted from `source` by `ledger` 's rule, the pool's base `base` ticked to the
    * successor of `tipSlot`, with the transaction as it is to be held. A flat transaction is held
    * with its other parts as received and the flag of the verdict: the flag is neither hashed nor
    * signed (the id is the hash of the body alone), as the reference relays the transaction it
    * rebuilt.
    */
  private def admit(
      ledger: PoolLedger,
      in: InEra,
      env: MempoolEnv,
      tipSlot: BigInt,
      base: PoolLedger,
      source: Source
  ): Either[Rejection, (PoolLedger, Held)] =
    (ledger, in) match
      case (flat: FlatLedger, InEra.Flat(era, t)) =>
        flat
          .admit(t.tx, env.txEnv(tipSlot, base), env.rules, source)
          .bimap(
            Rejection.Ledger(_),
            (next, held) => next -> Held.Flat(era, t.parts.copy(isValid = held.isValid), held)
          )
      case (c: ConwayPoolLedger, InEra.InConway(t)) =>
        c.admit(t, ConwayPoolEnv.of(env, tipSlot + 1), source)
          .map((next, held) => next -> Held.InConway(held))
      case (d: DijkstraLedger, InEra.InDijkstra(t)) =>
        d.admit(t, DijkstraPoolEnv.of(env, tipSlot + 1), source)
          .map((next, held) => next -> Held.InDijkstra(held))
      case (_: FlatLedger, InEra.InDijkstra(_) | InEra.InConway(_)) |
          (_: ConwayPoolLedger, InEra.Flat(_, _) | InEra.InDijkstra(_)) |
          (_: DijkstraLedger, InEra.Flat(_, _) | InEra.InConway(_)) =>
        Left(Rejection.WrongEra(in.era.index, ledger.era))

  /** A transaction past the steps of an admission before the timed one ([[MempoolState.measure]]):
    * the ledger and its base, the transaction in the ledger's era or the era mismatch the rules
    * will refuse it with ([[Measured.Unmatched]]), and its first measure.
    */
  final case class Measured(
      ledger: PoolLedger,
      base: PoolLedger,
      matched: Measured.Matched | Measured.Unmatched,
      phase1: TxMeasure
  ):
    /** The submitted transaction's id, when it was read. */
    def id: Option[Bytes] = named.map(_._2)

    /** The era the transaction was submitted in and its id, when it was read. */
    def named: Option[(Era, Bytes)] = matched match
      case Measured.Matched(submitted, in)  => Some(submitted -> in.id)
      case Measured.Unmatched(_, submitted) => submitted

  object Measured:
    /** The transaction in the ledger's era, read in `submitted`. */
    final case class Matched(submitted: Era, in: InEra)

    /** A transaction that does not reach the ledger's era, where the selected build measures it as
      * nothing and the rules refuse it ([[EraInjection.Mismatch.InRules]]); `submitted` is the era
      * it was read in and its id, none when the pool does not take transactions of that era.
      */
    final case class Unmatched(mismatch: Rejection.WrongEra, submitted: Option[(Era, Bytes)])

  /** The era a submission was read in and its id, when the pool reads transactions of that era. */
  private def named(read: Read): Option[(Era, Bytes)] = read match
    case Read.Decoded(transaction) => Some(transaction.era -> transaction.id)
    case Read.Foreign(_)           => None

  /** A transaction the rules admit: the ledger after it, the transaction as held and its
    * reference-script bytes.
    */
  final case class Admitted(next: PoolLedger, held: Held, refScriptBytes: Long)

  /** What the timed step of an admission gives ([[MempoolState.tryAdd]]), the reference's
    * `TriedToAddTx` (ouroboros-consensus 82ecba32 `Mempool/Update.hs` 133-143): the transaction
    * admitted or refused, or no time left in the pool, which it waits on (`NotEnoughSpaceLeft`). A
    * lack of room in a component of the measure is the refusal [[Rejection.Full]], which the pool
    * waits on as well.
    */
  enum Tried:
    case Done(result: Either[Rejection, Admitted])
    case NoTime(used: DiffTime, capacity: DiffTime)

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
    (current, entry.held) match
      case (flat: FlatLedger, Held.Flat(era, _, tx)) if era == ledger.era =>
        flat
          .reapply(tx, env.txEnv(slot, ledger), env.rules)
          .bimap(Rejection.Ledger(_), _ -> entry)
      case (c: ConwayPoolLedger, Held.InConway(held)) =>
        c.reapply(held, ConwayPoolEnv.of(env, slot + 1)).map(_ -> entry)
      case (d: DijkstraLedger, Held.InDijkstra(held)) =>
        d.reapply(held, DijkstraPoolEnv.of(env, slot + 1)).map(_ -> entry)
      case _ =>
        for
          into <- decoding(
            env.injection
              .into(entry.held.in, ledger.era)
              .leftMap(e => Rejection.WrongEra(e.index, ledger.era))
          )
          in <- projected(current, into)
          out <- env.injection.revalidation match
            case EraInjection.Revalidation.Reapply =>
              (current, in) match
                case (flat: FlatLedger, InEra.Flat(era, t)) =>
                  flat
                    .reapply(t.tx, env.txEnv(slot, ledger), env.rules)
                    .bimap(
                      Rejection.Ledger(_),
                      _ -> entry.copy(held = Held.Flat(era, t.parts, t.tx))
                    )
                // a transaction of the era before translated into Conway, still held as validated
                // ([[ConwayPoolLedger.reapplyTranslated]])
                case (c: ConwayPoolLedger, InEra.InConway(t)) =>
                  c.reapplyTranslated(t, ConwayPoolEnv.of(env, slot + 1))
                    .map((next, held) => next -> entry.copy(held = Held.InConway(held)))
                // a transaction translated into Dijkstra: none is, [[EraInjection.Translate]]
                // stops short of it
                case (_, _) => Left(Rejection.WrongEra(entry.era.index, ledger.era))
            case EraInjection.Revalidation.Apply =>
              admit(current, in, env, slot, ledger, Source.Remote)
                .map((next, held) => next -> entry.copy(held = held))
        yield out

  /** The held Dijkstra transactions of `entries` that still apply on `state`, the ledger state
    * ticked to the slot being forged, in order, each reapplied at that slot (`env`) on the state
    * the ones before it left (`getSnapshotFor`, ouroboros-consensus 2416493b
    * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/API.hs:187-203`,
    * `computeSnapshot`, `Mempool/Query.hs:138-155`, with `reapplyTx`), and those the rules have no
    * verdict on, which the forger withdraws from the pool, as it withdraws a Conway one (issue
    * 584). A refused transaction is left out of the snapshot and stays in the pool, which its next
    * revalidation drops. The forger takes the pool's own transactions instead when they are the
    * snapshot already ([[MempoolState.dijkstraCached]], `Query.hs:91-127` at d91b3557, its
    * `cheapTick`).
    */
  def dijkstraSnapshot(
      entries: Vector[Entry],
      state: DijkstraState,
      env: DijkstraPoolEnv
  ): (Vector[(Entry, DijkstraAdmitted)], Vector[(Entry, Rejection)]) =
    val done = dijkstraRevalidated(entries, DijkstraRevalidation.from(state), env)
    (done.kept, done.withheld)

  /** [[dijkstraSnapshot]] of `entries` continued from `from`: each reapplied on the state the ones
    * before it left, after `from` 's, the kept and the withheld appended to `from` 's, and the
    * state the kept ones leave (`revalidateTxsFor'`, `Mempool/Impl/Common.hs:575-644` at
    * ouroboros-consensus d91b3557, which the certifying view continues from its own state,
    * `catchUpCertifyingView`, `Mempool/CertifyingView.hs:74-99`).
    */
  def dijkstraRevalidated(
      entries: Vector[Entry],
      from: DijkstraRevalidation,
      env: DijkstraPoolEnv
  ): DijkstraRevalidation =
    entries.foldLeft(from) { (acc, entry) =>
      entry.held match
        case Held.InDijkstra(admitted) =>
          judging(acc.state.reapply(admitted, env)) match
            case Right(next) => acc.copy(state = next, kept = acc.kept :+ (entry -> admitted))
            case Left(
                  noVerdict @ (Rejection.DijkstraUnjudged(_, _) | Rejection.RulesDefect(_))
                ) =>
              acc.copy(withheld = acc.withheld :+ (entry -> noVerdict))
            case Left(_) => acc
        // a Dijkstra block takes Dijkstra transactions: the pool's ledger is of the era of the slot
        // after the tip, so a pool behind a crossing holds none yet
        case Held.Flat(_, _, _) | Held.InConway(_) => acc
    }

  /** The held Conway transactions of `entries` that still apply on `ledger`, the ledger state
    * ticked to the slot being forged, in order, each applied again at that slot (`env`) as the
    * build's consensus applies it ([[ConwayPoolLedger.reapplied]]: 11.1's `reapplyTx`, 11.2's and
    * the Leios build's `reapplyValidatedTx`) on the state the ones before it left
    * (`implGetSnapshotFor`, ouroboros-consensus 82ecba32 `Mempool/Query.hs:14-55`, and
    * `computeSnapshot`, `Mempool/Impl/Common.hs:459-484`, whose `reapplyTxs @Discard` leaves out
    * the transactions that no longer apply), and those the rules have no verdict on, which the
    * forger withdraws from the pool (issue 584). Which is which is the type of the run's outcome: a
    * defect of Conway's rules or an exception of the rules ([[Rejection.RulesDefect]]) is no
    * verdict, a refusal leaves the transaction out of the snapshot and in the pool, which its next
    * revalidation drops. The reference keeps the pool's own snapshot when the slot and the tip are
    * the pool's (`Query.hs:29-36`); the Conway forger always revalidates, where the Dijkstra one
    * takes the pool's own ([[dijkstraCached]]).
    */
  def conwaySnapshot(
      entries: Vector[Entry],
      ledger: ConwayPoolLedger,
      env: ConwayPoolEnv
  ): (Vector[(Entry, ConwayAdmitted)], Vector[(Entry, Rejection)]) =
    final case class Acc(
        ledger: ConwayPoolLedger,
        kept: Vector[(Entry, ConwayAdmitted)],
        withheld: Vector[(Entry, Rejection)]
    )
    val done = entries.foldLeft(Acc(ledger, Vector.empty, Vector.empty)) { (acc, entry) =>
      entry.held match
        case Held.InConway(admitted) =>
          judging(acc.ledger.reapplied(admitted, env)) match
            case Right(Right(next)) =>
              acc.copy(ledger = next, kept = acc.kept :+ (entry -> admitted))
            case Right(Left(_))  => acc
            case Left(noVerdict) => acc.copy(withheld = acc.withheld :+ (entry -> noVerdict))
        // a Conway block takes Conway transactions: the pool's ledger is of the era of the slot
        // after the tip, so a pool behind the crossing holds those of the era before, and one in
        // Dijkstra (the Leios build) none of Conway
        case Held.Flat(_, _, _) | Held.InDijkstra(_) => acc
    }
    (done.kept, done.withheld)

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
