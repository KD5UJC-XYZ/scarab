// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Ref, Resource}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{ChainSelection, HeaderChain}
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.{Fragment, LatestSlot}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, GenesisInternalProblem, PeerError}
import xyz.kd5ujc.scarab.network.ChainSyncClient
import xyz.kd5ujc.scarab.network.genesis.{LeakyBucket, LimitOnPatience, PicosClock}
import xyz.kd5ujc.scarab.network.selection.{ChainSyncJumping, ChainSyncJumpingRuntime, Gsm}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Shared Genesis controls for both directions of a duplex node. Connection callbacks carry typed
  * values into their resource owner; no control stores an error capability from its constructor.
  */
final class GenesisRuntime[F[_]: Async] private (
    val governor: GenesisGovernor[F],
    val work: SelectionWork[F],
    jumping: ChainSyncJumpingRuntime[F, Long, GenesisPeer.Checkpoint],
    settings: GenesisSettings,
    selection: F[ChainSelection.Local],
    geometry: F[EpochGeometry],
    elapsed: F[BigInt],
    clock: Option[PicosClock[F]],
    readings: Ref[F, Map[Long, (Option[HeaderChain[NodeHeaderState]], Boolean)]],
    headers: HeaderCheck.Grafting[F]
):
  private val F = Async[F]
  import ChainSyncJumping.{Instruction, JumpInfo}

  def demoteDynamo(id: Long): F[Unit] = jumping.rotate(id)

  def gsmReadings(
      versus: HeaderChain[NodeHeaderState] => F[Gsm.Candidate]
  ): F[Vector[Gsm.PeerReading]] =
    readings.get.flatMap(_.toVector.sortBy(_._1).traverse { case (_, (candidate, idle)) =>
      candidate
        .fold(F.pure(Gsm.Candidate.Better(false): Gsm.Candidate))(versus)
        .map(Gsm.PeerReading(idle, _))
    })

  def bind(
      pool: BlockPool[F],
      identity: Long,
      disconnect: PeerError => F[Unit],
      fatal: GenesisInternalProblem => F[Unit],
      densityOrder: PeerOrder
  ): Resource[F, (FetchCoordinator.Session[F], GenesisRuntime.Bound[F])] =
    for
      fetching <- pool.fetchCoordinator.session(identity, Some(densityOrder))
      current <- Resource.eval(selection)
      bound <- session(fetching.identity, current.anchor, disconnect, fatal, Some(densityOrder))
    yield fetching -> bound

  def session(
      identity: Long,
      anchor: Point,
      disconnect: PeerError => F[Unit],
      fatal: GenesisInternalProblem => F[Unit],
      densityOrder: Option[PeerOrder] = None
  ): Resource[F, GenesisRuntime.Bound[F]] =
    def refused[A](problem: PeerError.GenesisProblem): F[A] =
      disconnect(PeerError.Genesis(problem)) *> F.never[A]
    def invariant[A](problem: GenesisInternalProblem): F[A] = fatal(problem) *> F.never[A]
    for
      _ <- Resource.make(readings.update(_.updated(identity, None -> false)))(_ =>
        readings.update(_ - identity)
      )
      history <- Resource.eval(geometry)
      historicity = NodeGenesisHistory.runtime[F](true, governor.phase, elapsed)
      ready <- work.session
      // what the session last published of its own header state, as the reference's client
      // writes its candidate (ChainSync/Client.hs `setCandidate`); only the session writes it
      published <- Resource.eval(Ref.of[F, Option[HeaderChain[NodeHeaderState]]](None))
      raw <- Resource.eval(Ref.of[F, LatestSlot](LatestSlot.Unknown))
      idle <- Resource.eval(Ref.of[F, Boolean](false))
      patience <- LimitOnPatience.resource[F](
        LeakyBucket.Config.disabled,
        disconnect(PeerError.Genesis(PeerError.GenesisProblem.LoPExpired)),
        cause => fatal(GenesisInternalProblem.PatienceWatcherFailed(cause)),
        clock
      )
      peer <- governor.session(
        phase =>
          patience.configure(
            if phase == Gsm.Phase.Syncing then settings.bucket
            else LeakyBucket.Config.disabled
          ),
        disconnect(PeerError.Genesis(PeerError.GenesisProblem.DensityTooLow)),
        densityOrder.orElse(Some(PeerOrder(identity)))
      )
      phase <- Resource.eval(governor.phase)
      jumper <- jumping.session(
        identity,
        anchor,
        phase == Gsm.Phase.CaughtUp || !settings.enableCSJ
      )
    yield
      def snapshot: F[Option[HeaderChain[NodeHeaderState]]] = published.get
      // the session's gate's history, as the session publishes it
      def publishedBy(gate: HeaderGate.Grafting[F]): F[Option[HeaderChain[NodeHeaderState]]] =
        gate.snapshot.flatTap(published.set)
      def fragment(history: HeaderChain[NodeHeaderState]): Fragment =
        Fragment(
          history.nonEmptyPoints.head,
          history.nonEmptyPoints.tail.collect { case point: Point.Block =>
            point
          }
        )
      def publish: F[Unit] =
        (snapshot, raw.get, idle.get).tupled.flatMap { (history, latest, idling) =>
          peer.publish(history.map(fragment), latest, idling) *> readings.update { current =>
            current.updatedWith(identity)(_.map(_ => history -> idling))
          }
        }
      def headerTime(
          point: Point,
          known: Option[HeaderChain[NodeHeaderState]]
      ): F[NodeGenesisHistory.HeaderTime] = point match
        case Point.Origin => F.pure(NodeGenesisHistory.HeaderTime(point, 0))
        case Point.Block(slot, hash) =>
          geometry.flatMap { history =>
            val state = known.flatMap { h =>
              h.stateAfter(hash).flatten
            }
            val offset = state.flatMap {
              case _: NodeHeaderState.Byron =>
                Some(BigInt(history.byron.slotLengthMillis) * slot.value)
              case NodeHeaderState.Shelley(_, _, firstEpoch, _) =>
                if !history.byron.derived then Some(history.slotOffsetMillis(slot.value))
                else firstEpoch.map(epoch => history.resolved(epoch).slotOffsetMillis(slot.value))
            }
            offset.fold(invariant(GenesisInternalProblem.HeaderTimeUnavailable(point)))(time =>
              F.pure(NodeGenesisHistory.HeaderTime(point, time * 1000000000))
            )
          }
      def historical(
          message: NodeGenesisHistory.Message,
          point: Point,
          known: Option[HeaderChain[NodeHeaderState]]
      ): F[Unit] =
        Handle
          .allow[PeerError](historicity.at(message, headerTime(point, known)))
          .rescue(error => disconnect(error) *> F.never[Unit])
      def observeRequest(
          history: F[Option[HeaderChain[NodeHeaderState]]]
      ): ChainSyncClient.Observer[F] =
        new ChainSyncClient.Observer[F]:
          override def capture(using cats.Applicative[F]): F[ChainSyncClient.Observer[F]] =
            history.map(known => observeRequest(F.pure(known)))
          def awaitReply: F[Unit] =
            history.flatMap(
              _.traverse_(h =>
                historical(NodeGenesisHistory.Message.AwaitReply, h.tipPoint, Some(h))
              )
            ) *> idle.set(true) *> jumper.onAwaitReply *> publish
          def reply: F[Unit] = idle.set(false) *> publish
      val observer = observeRequest(snapshot)
      val control = new GenesisPeer[F]:
        def failed[A](error: ChainSyncJumping.Error): F[A] = invariant(error match
          case ChainSyncJumping.Error.MissingDynamoJumpInfo =>
            GenesisInternalProblem.MissingDynamoJumpInfo
          case ChainSyncJumping.Error.UnexpectedFoundIntersectionJump =>
            GenesisInternalProblem.UnexpectedIntersection)
        override def nextAfterReply: F[Instruction[GenesisPeer.Checkpoint]] =
          if !settings.enableCSJ then F.pure(Instruction.RunNormally)
          else
            // Client.hs:1267-1291: pause while waiting AND while offering a jump.
            patience.setPaused(LimitOnPatience.PauseReason.Jumping, true) *>
              jumper.next.flatTap {
                case Instruction.RunNormally | Instruction.Restart =>
                  patience.setPaused(LimitOnPatience.PauseReason.Jumping, false)
                case _ => F.unit
              }
        def next: F[Instruction[GenesisPeer.Checkpoint]] = nextAfterReply
        def current(gate: HeaderGate.Grafting[F]): F[Unit] = publishedBy(gate).void
        def observed(gate: HeaderGate.Grafting[F], event: SyncEvent): F[Unit] =
          publishedBy(gate)
            .flatMap { history =>
              history.traverse_ { h =>
                gate.intersectionSelection
                  .flatMap(
                    _.fold(
                      invariant[ChainSelection.Local](
                        GenesisInternalProblem.HeaderSelectionUnavailable(h.base)
                      )
                    )(F.pure)
                  )
                  .flatMap { selected =>
                    h.points.reverseIterator
                      .find(selected.contains)
                      .fold(
                        invariant[Unit](GenesisInternalProblem.HeaderSelectionUnavailable(h.base))
                      )(intersection =>
                        jumper.update(
                          JumpInfo(
                            h.nonEmptyPoints.head,
                            h.nonEmptyPoints.tail,
                            GenesisPeer.Checkpoint(h, selected, intersection)
                          )
                        )
                      )
                  }
              }
            } *> (event match
            // Client.hs:1607-1619: jump-info/candidate precede rollback notification.
            case SyncEvent.RollBackward(point, _) =>
              raw.set(point match
                case Point.Origin         => LatestSlot.Origin
                case Point.Block(slot, _) => LatestSlot.At(slot.value)) *> jumper.onRollback(point)
            case _ => F.unit) *> publish
        def restartCandidates: F[Vector[Point]] =
          selection.map(current => current.blocks.reverse.map(_.point) :+ current.anchor)
        def restarted(gate: HeaderGate.Grafting[F], point: Point): F[Unit] =
          publishedBy(gate)
            .flatMap(
              _.traverse_(h =>
                gate.intersectionSelection
                  .flatMap(
                    _.fold(
                      invariant[ChainSelection.Local](
                        GenesisInternalProblem.HeaderSelectionUnavailable(h.base)
                      )
                    )(F.pure)
                  )
                  .flatMap { selected =>
                    if selected.contains(point) && h.points.contains(point) then
                      jumper.update(
                        JumpInfo(
                          h.nonEmptyPoints.head,
                          h.nonEmptyPoints.tail,
                          GenesisPeer.Checkpoint(h, selected, point)
                        )
                      )
                    else invariant[Unit](GenesisInternalProblem.HeaderSelectionUnavailable(point))
                  }
              )
            ) *> raw.set(point match
            case Point.Origin         => LatestSlot.Origin
            case Point.Block(slot, _) => LatestSlot.At(slot.value)) *> publish
        def processJump(
            gate: HeaderGate.Grafting[F],
            info: JumpInfo[GenesisPeer.Checkpoint],
            goodPoint: Boolean,
            found: Option[Point]
        ): F[GenesisPeer.Applied] =
          found match
            case Some(actual) if actual != info.tip =>
              refused(PeerError.GenesisProblem.InvalidJumpResponse(info.tip, Some(actual)))
            case _ =>
              jumper.processJump(info, goodPoint, found.contains(info.tip)).flatMap {
                case None => F.pure(GenesisPeer.Applied.Retained)
                case Some(accepted) =>
                  gate
                    .graft(accepted.payload.history, accepted.tip, accepted.payload.selection)
                    .flatMap {
                      case Left(HeaderCheck.GraftError.MissingPoint(point)) =>
                        invariant(GenesisInternalProblem.GraftMissingPoint(point))
                      case Left(HeaderCheck.GraftError.MissingSelection(point)) =>
                        invariant(GenesisInternalProblem.HeaderSelectionUnavailable(point))
                      case Left(HeaderCheck.GraftError.MissingState(point)) =>
                        invariant(GenesisInternalProblem.HeaderStateUnavailable(point))
                      case Right(_) =>
                        publishedBy(gate).flatMap {
                          case None =>
                            invariant(GenesisInternalProblem.GraftMissingPoint(accepted.tip))
                          case Some(installed) =>
                            installed.entries.lastOption
                              .map(_.header.blockNo)
                              .orElse(installed.baseState.flatMap {
                                case NodeHeaderState.Byron(_, _, _, blockNo) => blockNo
                                case NodeHeaderState.Shelley(_, previous, _, _) =>
                                  previous.map(_.blockNo)
                              })
                              .orElse {
                                val ours = accepted.payload.selection
                                if installed.base == ours.anchor then Some(ours.anchorView.blockNo)
                                else
                                  ours.blocks
                                    .find(_.point == installed.base)
                                    .map(_.view.blockNo)
                              }
                              // Client.hs:1340-1385: only JumpToGoodPoint installs validator state.
                              .traverse_(n => patience.jumpedTo(n).whenA(goodPoint)) *>
                              raw.set(accepted.tip match
                                case Point.Origin => LatestSlot.Origin
                                case Point.Block(slot, _) =>
                                  LatestSlot.At(slot.value)) *> publish
                                .as(
                                  GenesisPeer.Applied.Grafted(
                                    ChainSelection
                                      .Candidate(
                                        installed.base,
                                        installed.entries.map(_.header)
                                      )
                                  )
                                )
                        }
                    }
              }
      val received: SyncEvent => F[Unit] = {
        case SyncEvent.RollForward(header, _) =>
          jumper.onRollForward(header.point) *> raw.set(LatestSlot.At(header.slot)) *> publish
        case SyncEvent.RollBackward(point, _) =>
          snapshot.flatMap { history =>
            val oldest = history.flatMap { h =>
              val index = h.points.indexOf(point)
              Option.when(index >= 0)(h.points.drop(index + 1).headOption).flatten
            }
            oldest.traverse_(point =>
              historical(NodeGenesisHistory.Message.RollBackward, point, history)
            )
          }
      }
      GenesisRuntime.Bound(
        headers.withPatience(patience).withInvariant(fatal),
        patience,
        observer,
        received,
        control,
        ready,
        GenesisRuntime.retainedHeaders(history)
      )

object GenesisRuntime:
  /** Retain the volatile chain plus the complete forecast span, including Byron EBBs which can
    * share a slot with a regular block. Forecasting, rather than a small fixed journal, bounds it.
    */
  def retainedHeaders(geometry: EpochGeometry): Int =
    (2 * geometry.stabilityWindow.toBigInt.max(BigInt(2) * geometry.k) +
      BigInt(2) * geometry.k + 1)
      .max(BlockPool.Retained)
      .min(BigInt(Int.MaxValue))
      .toInt

  /** The Genesis controls of one connection. `fatal` is told an invariant that does not hold in a
    * fiber of the connection; one met while the controls are acquired is raised to the caller.
    */
  def controls[F[_]: Async](
      runtime: Option[GenesisRuntime[F]],
      pool: BlockPool[F],
      identity: Long,
      connectionId: Option[Either[
        xyz.kd5ujc.scarab.protocol.ConnectionIdentityError,
        xyz.kd5ujc.scarab.network.ConnectionId
      ]],
      disconnect: PeerError => F[Unit],
      fatal: GenesisInternalProblem => F[Unit]
  )(using
      Raise[F, PeerError]
  ): Resource[F, (Option[FetchCoordinator.Session[F]], Option[Bound[F]])] =
    runtime match
      case Some(genesis) =>
        val key = connectionId match
          case Some(Right(connection)) =>
            Resource.pure[F, PeerOrder](PeerOrder.Tcp(connection))
          case Some(Left(cause)) =>
            Resource.eval(
              PeerError
                .GenesisInvariant(GenesisInternalProblem.InvalidConnectionIdentity(cause))
                .raise[F, PeerOrder]
            )
          case None =>
            Resource.eval(
              PeerError
                .GenesisInvariant(GenesisInternalProblem.MissingConnectionIdentity)
                .raise[F, PeerOrder]
            )
        key
          .flatMap(order => genesis.bind(pool, identity, disconnect, fatal, order))
          .map((fetching, bound) => Some(fetching) -> Some(bound))
      case None => Resource.pure(None -> None)

  /** The Genesis controls of one connection.
    *
    * @param headers
    *   the session's header check: the node's own, with this connection's patience and its
    *   invariants told to the connection; the session holds the gates it starts
    */
  final case class Bound[F[_]](
      headers: HeaderCheck.Grafting[F],
      patience: LimitOnPatience[F],
      observer: ChainSyncClient.Observer[F],
      received: SyncEvent => F[Unit],
      control: GenesisPeer[F],
      work: SelectionWork.Session[F],
      retainedHeaders: Int
  ):
    /** The session's chain: the check and the controls it hands its gates to. */
    def chain: Peer.Chain[F] = Peer.Chain.Genesis(headers, control)
  def create[F[_]: Async](
      governor: GenesisGovernor[F],
      settings: GenesisSettings,
      selection: F[ChainSelection.Local],
      geometry: F[EpochGeometry],
      elapsed: F[BigInt],
      changed: F[Unit],
      headers: HeaderCheck.Grafting[F],
      clock: Option[PicosClock[F]] = None
  ): F[GenesisRuntime[F]] =
    for
      work <- SelectionWork.create[F](changed, clock)
      readings <- Ref.of[F, Map[Long, (Option[HeaderChain[NodeHeaderState]], Boolean)]](Map.empty)
      jumping <- ChainSyncJumpingRuntime.create[F, Long, GenesisPeer.Checkpoint](settings.jumpSlots)
    yield new GenesisRuntime(
      governor,
      work,
      jumping,
      settings,
      selection,
      geometry,
      elapsed,
      clock,
      readings,
      headers
    )
