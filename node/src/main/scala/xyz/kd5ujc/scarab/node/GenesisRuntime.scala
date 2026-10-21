// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Ref, Resource}
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
    readings: Ref[F, Map[Long, (Option[HeaderChain[NodeHeaderState]], Boolean)]]
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
      densityOrder: Option[GenesisGovernor.OrderKey] = None
  ): Resource[F, (FetchCoordinator.Session[F], GenesisRuntime.Bound[F])] =
    for
      fetching <- pool.fetchCoordinator.session(identity)
      current <- Resource.eval(selection)
      bound <- session(fetching.identity, current.anchor, disconnect, fatal, densityOrder)
    yield fetching -> bound

  def session(
      identity: Long,
      anchor: Point,
      disconnect: PeerError => F[Unit],
      fatal: GenesisInternalProblem => F[Unit],
      densityOrder: Option[GenesisGovernor.OrderKey] = None
  ): Resource[F, GenesisRuntime.Bound[F]] =
    def refused[A](problem: PeerError.GenesisProblem): F[A] =
      disconnect(PeerError.Genesis(problem)) *> F.never[A]
    def invariant[A](problem: GenesisInternalProblem): F[A] = fatal(problem) *> F.never[A]
    for
      _ <- Resource.make(readings.update(_.updated(identity, None -> false)))(_ =>
        readings.update(_ - identity)
      )
      history <- Resource.eval(geometry)
      ready <- work.session
      gateRef <- Resource.eval(Ref.of[F, Option[HeaderGate[F]]](None))
      requestHistory <- Resource.eval(Ref.of[F, Option[HeaderChain[NodeHeaderState]]](None))
      raw <- Resource.eval(Ref.of[F, LatestSlot](LatestSlot.Unknown))
      idle <- Resource.eval(Ref.of[F, Boolean](false))
      patience <- LimitOnPatience.resource[F](
        LeakyBucket.Config.disabled,
        disconnect(PeerError.Genesis(PeerError.GenesisProblem.LoPExpired)),
        clock
      )
      peer <- governor.session(
        phase =>
          patience.configure(
            if phase == Gsm.Phase.Syncing then settings.bucket
            else LeakyBucket.Config.disabled
          ),
        disconnect(PeerError.Genesis(PeerError.GenesisProblem.DensityTooLow)),
        densityOrder.orElse(Some(GenesisGovernor.OrderKey(identity)))
      )
      phase <- Resource.eval(governor.phase)
      jumper <- jumping.session(
        identity,
        anchor,
        phase == Gsm.Phase.CaughtUp || !settings.enableCSJ
      )
    yield
      def snapshot: F[Option[HeaderChain[NodeHeaderState]]] =
        gateRef.get.flatMap(_.traverse(_.snapshot).map(_.flatten))
      def fragment(history: HeaderChain[NodeHeaderState]): Fragment =
        Fragment(
          history.points.head,
          history.points.tail.collect { case point: Point.Block =>
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
        governor.phase.flatMap {
          case Gsm.Phase.CaughtUp => F.unit
          case current =>
            (headerTime(point, known), elapsed).tupled.flatMap { (header, now) =>
              NodeGenesisHistory
                .judge(message, header, current, now)
                .fold(problem => refused(problem).void, _ => F.unit)
            }
        }
      val observer = new ChainSyncClient.Observer[F]:
        def awaitReply: F[Unit] =
          requestHistory.get.flatMap(
            _.traverse_(h => historical(NodeGenesisHistory.Message.AwaitReply, h.tipPoint, Some(h)))
          ) *> idle.set(true) *> jumper.onAwaitReply *> publish
        def reply: F[Unit] = idle.set(false) *> publish
      val control = new GenesisPeer[F]:
        def failed[A](error: ChainSyncJumping.Error): F[A] = invariant(error match
          case ChainSyncJumping.Error.MissingDynamoJumpInfo =>
            GenesisInternalProblem.MissingDynamoJumpInfo
          case ChainSyncJumping.Error.UnexpectedFoundIntersectionJump =>
            GenesisInternalProblem.UnexpectedIntersection)
        def next: F[Instruction[GenesisPeer.Checkpoint]] =
          val instruction: F[Instruction[GenesisPeer.Checkpoint]] =
            if !settings.enableCSJ then F.pure(Instruction.RunNormally)
            else
              // Client.hs:1267-1291: pause while waiting AND while offering a jump.
              patience.setPaused(LimitOnPatience.PauseReason.Jumping, true) *>
                jumper.next.flatTap {
                  case Instruction.RunNormally | Instruction.Restart =>
                    patience.setPaused(LimitOnPatience.PauseReason.Jumping, false)
                  case _ => F.unit
                }
          instruction.flatTap {
            // Client.hs:1397 captures kis at submission, rather than reading its later successor.
            case Instruction.RunNormally => snapshot.flatMap(requestHistory.set)
            case _                       => F.unit
          }
        def observed(gate: HeaderGate[F], event: SyncEvent): F[Unit] =
          gateRef.set(Some(gate)) *> snapshot.flatMap { history =>
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
                          h.points.head,
                          h.points.tail,
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
        def restarted(point: Point, gate: HeaderGate[F]): F[Unit] =
          gateRef.set(Some(gate)) *> snapshot.flatMap(
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
                        h.points.head,
                        h.points.tail,
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
            info: JumpInfo[GenesisPeer.Checkpoint],
            goodPoint: Boolean,
            found: Option[Point],
            gate: HeaderGate[F]
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
                      case Left(HeaderCheck.GraftError.Unsupported) =>
                        invariant(GenesisInternalProblem.UnsupportedHeaderFamily)
                      case Right(_) =>
                        gate.snapshot.flatMap {
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
                                else ours.blocks.find(_.point == installed.base).map(_.view.blockNo)
                              }
                              // Client.hs:1340-1385: only JumpToGoodPoint installs validator state.
                              .traverse_(n => patience.jumpedTo(n).whenA(goodPoint)) *> gateRef.set(
                              Some(gate)
                            ) *>
                              raw.set(accepted.tip match
                                case Point.Origin         => LatestSlot.Origin
                                case Point.Block(slot, _) => LatestSlot.At(slot.value)) *> publish
                                .as(
                                  GenesisPeer.Applied.Grafted(
                                    ChainSelection
                                      .Candidate(installed.base, installed.entries.map(_.header))
                                  )
                                )
                        }
                    }
              }
      val received: SyncEvent => F[Unit] = {
        case SyncEvent.RollForward(header, _) =>
          jumper.onRollForward(header.point) *> raw.set(LatestSlot.At(header.slot)) *>
            idle.set(false) *> publish
        case SyncEvent.RollBackward(point, _) =>
          snapshot.flatMap { history =>
            val oldest = history.flatMap { h =>
              val index = h.points.indexOf(point)
              Option.when(index >= 0)(h.points.drop(index + 1).headOption).flatten
            }
            oldest.traverse_(point =>
              historical(NodeGenesisHistory.Message.RollBackward, point, history)
            )
          } *> idle.set(false)
      }
      GenesisRuntime.Bound(
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
    (2 * geometry.stabilityWindow.max(BigInt(2) * geometry.securityParam) +
      BigInt(2) * geometry.securityParam + 1)
      .max(BlockPool.Retained)
      .min(BigInt(Int.MaxValue))
      .toInt

  def controls[F[_]: Async](
      runtime: Option[GenesisRuntime[F]],
      pool: Option[BlockPool[F]],
      identity: Long,
      disconnect: PeerError => F[Unit],
      fatal: GenesisInternalProblem => F[Unit]
  ): Resource[F, (Option[FetchCoordinator.Session[F]], Option[Bound[F]])] =
    (runtime, pool) match
      case (Some(genesis), Some(shared)) =>
        genesis
          .bind(shared, identity, disconnect, fatal)
          .map((fetching, bound) => Some(fetching) -> Some(bound))
      case (Some(_), None) =>
        Resource.eval(
          fatal(GenesisInternalProblem.MissingSharedBlockPool) *>
            Async[F].never[(Option[FetchCoordinator.Session[F]], Option[Bound[F]])]
        )
      case (None, _) => Resource.pure(None -> None)

  final case class Bound[F[_]](
      patience: LimitOnPatience[F],
      observer: ChainSyncClient.Observer[F],
      received: SyncEvent => F[Unit],
      control: GenesisPeer[F],
      work: SelectionWork.Session[F],
      retainedHeaders: Int
  )
  def create[F[_]: Async](
      governor: GenesisGovernor[F],
      settings: GenesisSettings,
      selection: F[ChainSelection.Local],
      geometry: F[EpochGeometry],
      elapsed: F[BigInt],
      changed: F[Unit],
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
      readings
    )
