// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, BlockRules, FollowerEvent, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Header, NodeError, StorageError, ValidationError}
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.ReceivedBlocks

/** A follower with its selection worker, as `Main` installs it, for the suites of the forged blocks
  * the node keeps ([[Follower.submitForged]]): block rules that record the tip's block number, a
  * GDD governor in Genesis mode, and optionally a received log the pool reads bodies back from.
  */
private[node] object ForgedRetention extends munit.Assertions:
  private given Assertions[IO] = Assertions.noop[IO]
  private val windowAt: (BigInt, BigInt, BigInt) => Option[BigInt] = (_, _, _) => Some(30)

  /** A hang names itself; nothing in these cases waits for time. */
  val backstop: FiniteDuration = 1.minute

  /** What the worker's writing of a forged block to the received log does, after it records it. */
  enum Preparation:
    case Records
    case Raises(error: NodeError)
    case Throws(cause: Throwable)

  final case class Node(
      pool: BlockPool[IO],
      follower: Follower[IO, BigInt],
      governor: Option[GenesisGovernor[IO]],
      events: SignallingRef[IO, Vector[FollowerEvent]],
      prepared: Ref[IO, Vector[Bytes]],
      stored: Ref[IO, Vector[Bytes]],
      fatals: Ref[IO, Vector[Fatal]],
      faults: Ref[IO, Set[Bytes]]
  ):
    def offer(header: Header, raw: Bytes)(using Handle[IO, NodeError]): IO[Follower.Submission] =
      follower.submitForged(header, raw)

    /** Until our chain's tip is `point`. */
    def tipAt(point: ChainSync.Point): IO[VolatileChain[BigInt]] =
      follower.committedChain.discrete.find(_.tip == point).compile.lastOrError

    def extended: IO[Vector[BlockRef]] = events.get.map(Node.extended)

    /** Until the chain's extension by `hash` is reported. */
    def extendedBy(hash: Bytes): IO[Unit] =
      events.discrete.find(Node.extended(_).exists(_.hash == hash)).compile.drain

    /** Until the worker has finished the selection it is running and every one queued: its own
      * reselection, queued behind them, has run (`BlockAdmissions.reprocess`, first in, first out).
      */
    def settled: IO[Unit] = pool.admissions.reprocess

    /** A peer's session delivers `blocks`, and the worker has decided them. */
    def deliver(blocks: Vector[BlockPool.Delivered]): IO[Unit] =
      pool.session.use(sender =>
        sender.claim(blocks.map(_.header.hash)) *> sender.fillDelivered(blocks) *> settled
      )

    def phase(value: Gsm.Phase): IO[Unit] =
      governor.fold(IO(fail("no GDD governor in Praos mode")))(_.setPhase(value))

  object Node:
    def extended(events: Vector[FollowerEvent]): Vector[BlockRef] =
      events.collect { case FollowerEvent.Extended(ref, _, _, _) => ref }

  /** The rules have no verdict on `header` 's block: the evaluator failed with a defect, a cause
    * that may pass (`BlockNoVerdict.Recovery.Passing`), on one of its scripts.
    */
  def noVerdict(header: Header): ValidationError =
    ValidationError(
      header.point,
      BlockFailure.Tx(
        xyz.kd5ujc.scarab.ledger.LedgerFailure.Unavailable(
          header.hash,
          xyz.kd5ujc.scarab.ledger.ScriptLocation.Phase1(header.hash),
          xyz.kd5ujc.scarab.plutus.EvalFailure.Unsupported(
            xyz.kd5ujc.scarab.plutus.BackendFailure.Defect(new IllegalStateException("evaluator"))
          )
        )
      )
    )

  def ref(header: Header): BlockRef =
    BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)

  def adopted(header: Header): Follower.Submission =
    Follower.Submission(Follower.Adoption.Adopted(ref(header)), Follower.Retention.Kept)

  def lost(loss: Follower.AdoptionLoss): Follower.Submission =
    Follower.Submission(Follower.Adoption.Lost(loss), Follower.Retention.Kept)

  /** The blocks of `chain` from `from` until `until`, checked as a peer's delivery is. */
  def delivered(chain: ForgedChain, from: Int, until: Int): IO[Vector[BlockPool.Delivered]] =
    chain.headers
      .zip(chain.raws)
      .slice(from, until)
      .traverse((h, raw) =>
        BlockPool.Delivered.checked(h, raw).fold(e => IO(fail(e.message)), IO.pure)
      )

  /** The chain of `headers` over the recording rules' states, as a restart resumes it. */
  def resumed(k: Int, headers: Vector[Header]): VolatileChain[BigInt] =
    headers.foldLeft(VolatileChain.genesis(k, BigInt(-1)))((chain, h) =>
      chain.extend(h, h.blockNo).fold(e => fail(e.message), _._1)
    )

  /** Writes to `received` as `Main` 's preparation does, and records what it was given: `prepare`,
    * a delivered block (its verification cache is not warmed here); `store`, a forged one, which
    * then does what `preparation` says.
    */
  private def recording(
      prepared: Ref[IO, Vector[Bytes]],
      stored: Ref[IO, Vector[Bytes]],
      preparation: Preparation,
      received: Option[ReceivedBlocks[IO]]
  ): BlockSelection.Before[IO] =
    new BlockSelection.Before[IO]:
      private def put(header: Header, raw: Bytes)(using Raise[IO, NodeError]): IO[Unit] =
        received.traverse_(_.put(ref(header), raw))
      def prepare(header: Header, raw: Bytes)(using Raise[IO, NodeError]): IO[Unit] =
        prepared.update(_ :+ header.hash) *> put(header, raw)
      def store(header: Header, raw: Bytes)(using Raise[IO, NodeError]): IO[Unit] =
        stored.update(_ :+ header.hash) *> (preparation match
          case Preparation.Records       => put(header, raw)
          case Preparation.Raises(error) => error.raise[IO, Unit]
          case Preparation.Throws(cause) => IO.raiseError(cause))
      def ahead(
          @scala.annotation.unused header: Header,
          @scala.annotation.unused raw: Bytes
      ): IO[Unit] = IO.unit

  /** The pool's view of `received`, as `Main` builds it; a storage failure fails the case. */
  private def recovered(received: ReceivedBlocks[IO]): BlockPool.Recovered[IO] =
    def read(
        get: Bytes => Raise[IO, StorageError] ?=> IO[Option[(BlockRef, Bytes)]]
    ): Bytes => IO[Option[Bytes]] = hash =>
      Handle
        .allow[StorageError](get(hash).map(_.map(_._2)))
        .rescue(error => IO(fail(s"the received log failed: ${error.message}")))
    BlockPool.Recovered[IO](
      received.holds,
      read(hash => received.get(hash)),
      read(hash => received.original(hash)),
      received.invalidate
    )

  /** A node with its worker started: in Genesis mode with its governor in `phase`, or in Praos mode
    * (`None`); keeping `k` volatile blocks from `initial`; `refused` are the blocks its rules
    * refuse, `withheldOnce` those whose first judgement has no verdict for a cause that may pass
    * (`noVerdict`) and whose later ones are valid (and the node's `faults`, set by the case, those
    * whose judgement has none while they are in it), `preparation` what writing a forged block
    * does, and `received` the log the pool reads bodies back from (whose recovered blocks
    * `Main.seedReceived` seeds first). `use` runs once the worker's startup selection is done.
    */
  def node[A](
      phase: Option[Gsm.Phase],
      k: Int = 2,
      refused: Set[Bytes] = Set.empty,
      withheldOnce: Set[Bytes] = Set.empty,
      preparation: Preparation = Preparation.Records,
      received: Option[ReceivedBlocks[IO]] = None,
      initial: Option[VolatileChain[BigInt]] = None
  )(
      use: Handle[IO, NodeError] ?=> Node => IO[A]
  ): IO[A] =
    for
      state <- Ref.of[IO, VolatileChain[BigInt]](initial.getOrElse(VolatileChain.genesis(k, -1)))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      events <- SignallingRef.of[IO, Vector[FollowerEvent]](Vector.empty)
      prepared <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      stored <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      judged <- Ref.of[IO, Set[Bytes]](Set.empty)
      faults <- Ref.of[IO, Set[Bytes]](Set.empty)
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, h, _) =>
          if refused(h.hash) then
            Raise[IO, ValidationError]
              .raise(ValidationError(h.point, BlockFailure.EpochPrecedesLedger(0)))
          else
            (judged.modify(seen => (seen + h.hash, seen(h.hash))), faults.get).flatMapN {
              (before, faulty) =>
                if faulty(h.hash) || (withheldOnce(h.hash) && !before) then
                  Raise[IO, ValidationError].raise(noVerdict(h))
                else IO.pure(h.blockNo)
            }
        ),
        event => events.update(_ :+ event),
        ForgedChain.geometry
      )
      pool <- received.fold(BlockPool.create[IO])(log =>
        BlockPool.create[IO](BlockPool.FetchPolicy(), follower.fetchSelection, recovered(log))
      )
      _ <- follower.installFetchCoordinator(pool.fetchCoordinator) *>
        follower.installInvalidBlocks(pool.invalidBlocks)
      governor <- phase.traverse(value =>
        follower
          .genesisView(windowAt)
          .flatMap(GenesisGovernor.create[IO](_))
          .flatTap(follower.installGenesis(_, windowAt))
          .flatTap(_.setPhase(value))
      )
      work <- SelectionWork.create[IO](pool.fetchCoordinator.refreshGenesis)
      given BlockSelection.Before[IO] = recording(prepared, stored, preparation, received)
      result <- Handle
        .allow[NodeError] {
          given xyz.kd5ujc.scarab.node.log.Log[IO] = xyz.kd5ujc.scarab.node.log.Log.none[IO]
          Main.seedReceived(pool, received, BigInt(21600)) *>
            follower
              .blockSelection(pool, work, fault => fatals.update(_ :+ fault))
              .use(_ =>
                use(Node(pool, follower, governor, events, prepared, stored, fatals, faults))
              )
              .timeoutTo(backstop, IO(fail(s"the case did not finish within $backstop")))
        }
        .rescue(error => IO(fail(error.message)))
    yield result
