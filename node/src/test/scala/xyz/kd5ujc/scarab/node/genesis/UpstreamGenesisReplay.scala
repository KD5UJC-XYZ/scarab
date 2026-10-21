// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Handle, Raise, Stateful, Tell}
import cats.syntax.all.*
import com.comcast.ip4s.*
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.consensus.*
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.genesis.PicosClock
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.*
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era, NtNHeader}
import UpstreamGenesisCases.{Component, End, PeerId, Scenario}

/** Executes original generated schedules against shared production Genesis controls and the real
  * protocol engines. Virtual nanoseconds represent native picoseconds; policy clocks retain the
  * original units, including the native microsecond bucket timer rounding.
  */
object UpstreamGenesisReplay:
  enum Problem extends DomainError:
    case Storage(cause: StorageError)
    case Journal(cause: UpstreamGenesisStorage.Problem)
    case NonIntegralBucketCapacity(value: xyz.kd5ujc.scarab.codec.Rational)
    case Blocks(cause: UpstreamGenesisBlocks.Problem)
    case Network(cause: NetworkError)
    case Node(cause: NodeError)
    case Invariant(cause: GenesisInternalProblem)
    case Defect(cause: Throwable)
  final case class Ledger(point: ChainSync.Point, blockNo: BigInt, slot: BigInt):
    def previous: Option[BlockRef] = point match
      case ChainSync.Point.Origin         => None
      case ChainSync.Point.Block(_, hash) => Some(BlockRef(slot, hash, blockNo))
  final case class Closure(peer: Option[PeerId], problem: Problem)
  final case class Terminal(peer: PeerId, component: Component, end: End, cause: Option[Problem])
  final case class Downloaded(peer: PeerId, header: ChainSync.Point.Block)
  final case class GddDecision(atPicos: BigInt, value: GenesisGovernor.Observation)
  final case class Result(
      selection: Fragment,
      closures: Vector[Closure],
      terminals: Vector[Terminal],
      gdd: Vector[GddDecision],
      downloaded: Vector[Downloaded]
  )

  private def end(problem: Problem): End = problem match
    case Problem.Node(PeerError.Genesis(PeerError.GenesisProblem.LoPExpired)) => End.PatienceExpired
    case Problem.Node(PeerError.Genesis(PeerError.GenesisProblem.DensityTooLow)) =>
      End.DensityTooLow
    case Problem.Node(PeerError.Genesis(_: PeerError.GenesisProblem.CandidateTooSparse)) =>
      End.CandidateTooSparse
    case Problem.Network(_: NetworkError.Timeout) => End.Timeout
    case Problem.Node(
          PeerError.Unavailable(PeerError.Unavailability.Network(_: NetworkError.Timeout))
        ) =>
      End.Timeout
    case Problem.Node(error) =>
      SessionEnd.of(error) match
        case SessionEnd.NoMoreIntersection | SessionEnd.RolledBackPastIntersection(_) => End.Success
        case _ => End.Unexpected
    case _ => End.Unexpected

  private def densityKey(peer: PeerId): Long = peer match
    case PeerId.Honest(index)      => index.toLong - Int.MinValue.toLong
    case PeerId.Adversarial(index) => (1L << 32) + index.toLong - Int.MinValue.toLong

  private val clock = new PicosClock[IO]:
    def now = IO.monotonic.map(t => BigInt(t.toNanos))
    def sleep(picos: BigInt) = IO.sleep(picos.max(0).toLong.nanos)
  private def deadline(enabled: Boolean, value: Option[BigInt]): ProtocolDeadline =
    if !enabled then ProtocolDeadline.Unbounded
    else
      value.fold[ProtocolDeadline](ProtocolDeadline.Unbounded)(picos =>
        ProtocolDeadline.After(picos.toLong.nanos)
      )

  /** TestControl accepts an encoded decimal seed; original case identity fixes it without
    * consulting the native outcome. The upstream generator seed remains a separate input.
    */
  def runtimeSeed(scenario: Scenario): String =
    val identity = scenario.label.hashCode.toString
    Base64.getEncoder.encodeToString(identity.getBytes(StandardCharsets.UTF_8))

  def run(scenario: Scenario): IO[Either[Problem, Result]] = TestControl.executeEmbed(
    {
      (if scenario.bucketCapacity.denominator != 1 then
         Left(Problem.NonIntegralBucketCapacity(scenario.bucketCapacity))
       else UpstreamGenesisBlocks.build(scenario).leftMap(Problem.Blocks(_))) match
        case Left(problem) => IO.pure(Left(problem))
        case Right(universe) =>
          val geometry = EpochGeometry(Instant.EPOCH, 20000, 1000000, scenario.k, BigDecimal(1))
          val protocol = ProtocolState.initial(Era.Babbage, Bytes(Vector.fill(32)(0.toByte)))
          def headerState(previous: Option[BlockRef]): NodeHeaderState =
            NodeHeaderState.Shelley(protocol, previous, None, None)
          val headerRules = new HeaderRules[Ledger, NodeHeaderState]
            with HeaderRules.Timing[Ledger, NodeHeaderState]:
            def at(state: Ledger) = headerState(state.previous)
            def tiebreaker(anchor: Ledger) = ChainSelection.Tiebreaker.Unrestricted
            def forecastTime(
                anchor: Ledger,
                tip: Option[BlockRef],
                previous: NodeHeaderState,
                header: Header
            ) =
              HeaderRules.TimeForecast.Known(header.slot * scenario.slotLengthPicos)
            def prepareValidation(
                anchor: Ledger,
                tip: Option[BlockRef],
                previous: NodeHeaderState,
                header: Header
            ): HeaderRules.Prepared[NodeHeaderState] =
              if header.slot >= anchor.slot + 1 + scenario.forecastRange then
                HeaderRules.Prepared.Unknown(
                  HeaderRules.ForecastReason.Horizon(header.slot, anchor.slot)
                )
              else HeaderRules.Prepared.Check(() => validate(anchor, tip, previous, header))
            def validate(
                anchor: Ledger,
                tip: Option[BlockRef],
                previous: NodeHeaderState,
                header: Header
            ) =
              if header.slot >= anchor.slot + 1 + scenario.forecastRange then
                HeaderRules.Outcome.Later(
                  HeaderRules.ForecastReason.Horizon(header.slot, anchor.slot)
                )
              else
                HeaderRules.Outcome.Valid(
                  headerState(Some(BlockRef(header.slot, header.hash, header.blockNo)))
                )
          for
            chain <- Ref.of[IO, VolatileChain[Ledger]](
              VolatileChain.genesis(scenario.k, Ledger(ChainSync.Point.Origin, 0, -1))
            )
            closed <- Ref.of[IO, Vector[Closure]](Vector.empty)
            terminals <- Ref.of[IO, Vector[Terminal]](Vector.empty)
            gdd <- Ref.of[IO, Vector[GddDecision]](Vector.empty)
            downloaded <- Ref.of[IO, Vector[Downloaded]](Vector.empty)
            record = (peer: PeerId, component: Component, outcome: Either[Problem, Unit]) =>
              terminals.update(
                _ :+ Terminal(
                  peer,
                  component,
                  outcome.fold(end, _ => End.Success),
                  outcome.left.toOption
                )
              )
            cancelled = (peer: PeerId, component: Component) =>
              terminals.update(_ :+ Terminal(peer, component, End.Cancelled, None))
            outcome <- {
              given Stateful[IO, VolatileChain[Ledger]] = RefStateful(chain)
              val rules = BlockRules.from[IO, Ledger] { (_, header, bytes) =>
                universe.encoded.get(header.point) match
                  case Some(block) if block.body == bytes =>
                    IO.pure(Ledger(header.point, header.blockNo, header.slot))
                  case _ =>
                    Raise[IO, ValidationError].raise(
                      ValidationError(header.point, BlockFailure.HeaderDiffers)
                    )
              }
              Fibers.scoped[IO, Problem, Result](Problem.Defect(_)) {
                for
                  servers <- scenario.schedules
                    .traverse(s =>
                      UpstreamGenesisServer
                        .create(
                          universe,
                          p =>
                            closed.update(_ :+ Closure(Some(s.peer), Problem.Blocks(p))) *> IO
                              .never[Nothing]
                        )
                        .map(s.peer -> _)
                    )
                    .map(_.toMap)
                  order = (scenario.startOrder ++ scenario.schedules.map(_.peer)).distinct
                  result <- fs2.io.file.Files.forAsync[IO].tempDirectory.use { dir =>
                    given Raise[IO, StorageError] =
                      Peer.contramapped(summon[Raise[IO, Problem]])(Problem.Storage(_))
                    given Raise[IO, UpstreamGenesisStorage.Problem] =
                      Peer.contramapped(summon[Raise[IO, Problem]])(Problem.Journal(_))
                    def start(active: Set[PeerId]): Resource[IO, Unit] =
                      UpstreamGenesisStorage.open(dir, scenario.k, universe).flatMap { opened =>
                        Resource.eval(chain.set(opened.chain)) *> Resource.eval {
                          for
                            follower <- Follower.create[IO, Ledger](
                              rules,
                              _ => IO.unit,
                              geometry,
                              persistence = Some(opened.selected)
                            )
                            given Ask[IO, ChainSelection.Local] = new Ask[IO, ChainSelection.Local]:
                              val applicative = IO.asyncForIO
                              def ask[A >: ChainSelection.Local]: IO[A] =
                                follower.fetchSelection.map(identity[A])
                            given Tell[IO, GenesisInternalProblem] = new Tell[
                              IO,
                              GenesisInternalProblem
                            ]:
                              val functor = IO.asyncForIO
                              def tell(problem: GenesisInternalProblem) =
                                closed.update(_ :+ Closure(None, Problem.Invariant(problem)))
                            pool <- BlockPool.create[IO](
                              BlockPool.FetchPolicy(
                                bulkConcurrency = 50,
                                deadlineConcurrency = 50,
                                maxRequestsPerPeer = 10
                              ),
                              follower.fetchSelection,
                              BlockPool.Recovered(
                                opened.received.hashes,
                                hash => opened.received.get(hash).map(_.map(_._2)),
                                opened.received.invalidate
                              )
                            )
                            _ <- follower.installFetchCoordinator(pool.fetchCoordinator)
                            governor <- GenesisGovernor.create[IO](
                              follower.genesisView((_, _, _) => Some(scenario.genesisWindow)),
                              GenesisGovernor.Config(scenario.config.loe, Duration.Zero),
                              Some(observation =>
                                clock.now
                                  .flatMap(now => gdd.update(_ :+ GddDecision(now, observation)))
                              )
                            )
                            settings = GenesisSettings(
                              enableCSJ = scenario.config.jumping,
                              enableLoEAndGDD = scenario.config.loe,
                              enableLoP = scenario.config.lop,
                              bucketCapacity = scenario.bucketCapacity.numerator,
                              bucketRate = scenario.bucketRate,
                              jumpSlots = scenario.jumpSize,
                              gddIntervalPicos = 0
                            )
                            runtime <- GenesisRuntime.create[IO](
                              governor,
                              settings,
                              follower.fetchSelection,
                              IO.pure(geometry),
                              clock.now,
                              pool.fetchCoordinator.refreshGenesis,
                              Some(clock)
                            )
                            _ <- follower.installGenesis(governor)
                            _ <- pool.fetchCoordinator.installGenesis(
                              FetchCoordinator.GenesisControl(
                                (governor.phase, clock.now, runtime.work.snapshot).mapN(
                                  (phase, now, starvation) =>
                                    FetchCoordinator.GenesisContext(
                                      phase,
                                      now,
                                      starvation,
                                      if scenario.config.starvation then BigInt(10).pow(13)
                                      else BigInt(10).pow(18)
                                    )
                                ),
                                runtime.demoteDynamo
                              )
                            )
                          yield (
                            Resource.make(governor.run.start)(_.cancel),
                            Resource.make(
                              pool.fetchCoordinator
                                .watchDecisions(
                                  settings.blockFetchGracePicos,
                                  Some(clock),
                                  FetchCoordinator.DecisionIntervals(0, 0)
                                )
                                .start
                            )(_.cancel),
                            order.zipWithIndex.filter((id, _) => active(id)).traverse {
                              (id, ordinal) =>
                                Resource.eval(servers(id).restart).flatMap { server =>
                                  (
                                    Resource.eval(Deferred[IO, Problem]),
                                    Resource.eval(Deferred[IO, Unit])
                                  ).tupled.flatMap { (refused, ready) =>
                                    val owned = Fibers.scoped[IO, Problem, Unit](
                                      Problem.Defect(_)
                                    ) {
                                      given Raise[IO, NetworkError] =
                                        Peer.contramapped(summon[Raise[IO, Problem]])(
                                          Problem.Network(_)
                                        )
                                      val connection =
                                        Peers
                                          .linked(
                                            protocols =
                                              Set(Protocols.ChainSync, Protocols.BlockFetch),
                                            client = Mux.Config(),
                                            server = Mux.Config(role = Mux.Role.Responder)
                                          )
                                          .flatMap { (clientMux, serverMux) =>
                                            for
                                              clientCS <- Resource.eval(
                                                clientMux.channel(Protocols.ChainSync)
                                              )
                                              serverCS <- Resource.eval(
                                                serverMux.channel(Protocols.ChainSync)
                                              )
                                              clientBF <- Resource.eval(
                                                clientMux.channel(Protocols.BlockFetch)
                                              )
                                              serverBF <- Resource.eval(
                                                serverMux.channel(Protocols.BlockFetch)
                                              )
                                              servingChainSync <- Resource.make(
                                                Fibers
                                                  .scoped[IO, NetworkError, Unit](
                                                    NetworkError.Defect(_)
                                                  )(
                                                    ChainSyncServer.run(
                                                      serverCS,
                                                      NtNHeader.codec,
                                                      server.chainSync,
                                                      ChainSyncServer
                                                        .Settings(idleDeadline =
                                                          Some(ProtocolDeadline.Unbounded)
                                                        )
                                                    )
                                                  )
                                                  .map(_.leftMap(Problem.Network(_)))
                                                  .flatMap(record(id, Component.ChainSyncServer, _))
                                                  .onCancel(
                                                    cancelled(id, Component.ChainSyncServer)
                                                  )
                                                  .start
                                              )(_.cancel)
                                              _ <- Resource.make(
                                                Fibers
                                                  .scoped[IO, NetworkError, Unit](
                                                    NetworkError.Defect(_)
                                                  )(
                                                    BlockFetchServer.run(
                                                      serverBF,
                                                      server.blocks,
                                                      BlockFetchServer
                                                        .Settings(idleDeadline =
                                                          Some(ProtocolDeadline.Unbounded)
                                                        )
                                                    )
                                                  )
                                                  .map(_.leftMap(Problem.Network(_)))
                                                  .flatMap(
                                                    record(id, Component.BlockFetchServer, _)
                                                  )
                                                  .onCancel(
                                                    cancelled(id, Component.BlockFetchServer)
                                                  )
                                                  .start
                                              )(_.cancel)
                                              chainSync <- Resource.eval(
                                                ChainSyncClient(
                                                  clientCS,
                                                  NtNHeader.codec,
                                                  ChainSyncClient.Settings(timeouts =
                                                    Some(
                                                      ChainSyncRuntime.Timeouts(
                                                        deadline(
                                                          scenario.config.chainSyncTimeouts,
                                                          scenario.chainSync.intersect
                                                        ),
                                                        deadline(
                                                          scenario.config.chainSyncTimeouts,
                                                          scenario.chainSync.canAwait
                                                        ),
                                                        deadline(
                                                          scenario.config.chainSyncTimeouts,
                                                          scenario.chainSync.mustReply
                                                        )
                                                      )
                                                    )
                                                  )
                                                )
                                              )
                                              blockFetch <- BlockFetchClient.resource(
                                                clientBF,
                                                clientMux.abort,
                                                BlockFetchClient.Settings(timeouts =
                                                  Some(
                                                    BlockFetchRuntime.Timeouts(
                                                      deadline(
                                                        scenario.config.blockFetchTimeouts,
                                                        scenario.blockFetch.busy
                                                      ),
                                                      deadline(
                                                        scenario.config.blockFetchTimeouts,
                                                        scenario.blockFetch.streaming
                                                      ),
                                                      ProtocolDeadline.Unbounded
                                                    )
                                                  )
                                                )
                                              )
                                              _ <- Resource.onFinalize(
                                                cancelled(id, Component.BlockFetchClient)
                                              )
                                              bound <- runtime.bind(
                                                pool,
                                                ordinal.toLong,
                                                e =>
                                                  closed.update(
                                                    _ :+ Closure(Some(id), Problem.Node(e))
                                                  ) *> refused.complete(Problem.Node(e)).void,
                                                p =>
                                                  closed.update(
                                                    _ :+ Closure(Some(id), Problem.Invariant(p))
                                                  ) *> refused.complete(Problem.Invariant(p)).void,
                                                densityOrder =
                                                  GenesisGovernor.OrderKey.Model(densityKey(id))
                                              )
                                              (fetchSession, controls) = bound
                                              connection = NodeToNodeConnection(
                                                15,
                                                chainSync,
                                                blockFetch,
                                                clientMux
                                              )
                                              peer <- Peer.over(
                                                connection,
                                                Peer.Settings(
                                                  SocketAddress[Host](ip"10.1.1.1", port"3001"),
                                                  42L,
                                                  byronEpochSlots = 1
                                                ),
                                                _ => IO.unit,
                                                headers = Some(
                                                  HeaderCheck
                                                    .forGenesisChain(
                                                      headerRules,
                                                      Compute.inline[IO],
                                                      controls.retainedHeaders,
                                                      stall = 120000000000000L.nanos,
                                                      poll = 20000000000L.nanos
                                                    )
                                                    .withPatience(controls.patience)
                                                ),
                                                pool = Some(pool),
                                                patience = Some(controls.patience),
                                                received = Some(event =>
                                                  (event match
                                                    case SyncEvent.RollForward(header, _) =>
                                                      downloaded
                                                        .update(
                                                          _ :+ Downloaded(
                                                            id,
                                                            ChainSync.Point.Block(
                                                              ChainSync.UInt64
                                                                .saturating(header.slot),
                                                              header.hash
                                                            )
                                                          )
                                                        )
                                                    case _: SyncEvent.RollBackward => IO.unit
                                                  ) *> controls.received(event)
                                                ),
                                                chainSyncObserver = Some(controls.observer),
                                                retainedHeaders = controls.retainedHeaders,
                                                fetchSession = Some(fetchSession),
                                                genesis = Some(controls.control),
                                                selectionWork = Some(controls.work),
                                                receivedBody = Some((header, raw) =>
                                                  Fibers
                                                    .scoped[IO, StorageError, Unit](e =>
                                                      StorageError
                                                        .Io("simulator received journal", e)
                                                    ) {
                                                      opened.received.put(
                                                        BlockRef(
                                                          header.slot,
                                                          header.hash,
                                                          header.blockNo
                                                        ),
                                                        raw
                                                      )
                                                    }
                                                    .flatMap(
                                                      _.fold(
                                                        e =>
                                                          refused
                                                            .complete(Problem.Storage(e))
                                                            .void *> IO.never[Unit],
                                                        IO.pure
                                                      )
                                                    )
                                                )
                                              )
                                            yield peer -> servingChainSync
                                          }
                                      connection.use { (peer, servingChainSync) =>
                                        ready.complete(()).void *> Fibers
                                          .scoped[IO, Problem, Unit](Problem.Defect(_)) {
                                            given Raise[IO, NodeError] =
                                              Peer.contramapped(summon[Raise[IO, Problem]])(
                                                Problem.Node(_)
                                              )
                                            IO.race(
                                              follower.session(peer),
                                              refused.get.flatMap(e =>
                                                Raise[IO, Problem].raise[Problem, Unit](e)
                                              )
                                            ).void
                                          }
                                          .flatMap { result =>
                                            record(id, Component.ChainSyncClient, result) *>
                                              servingChainSync.join.void.whenA(
                                                result.fold(end, _ => End.Success) == End.Success
                                              ) *>
                                              result.left.toOption.traverse_(e =>
                                                closed.update(_ :+ Closure(Some(id), e))
                                              )
                                          }
                                          .onCancel(cancelled(id, Component.ChainSyncClient))
                                      }
                                    }
                                    Resource.make(
                                      owned
                                        .flatMap(
                                          _.left.toOption
                                            .traverse_(e =>
                                              closed.update(_ :+ Closure(Some(id), e))
                                            )
                                        )
                                        .start
                                    )(_.cancel) *>
                                      Resource.eval(ready.get)
                                  }
                                }
                            }
                          ).tupled.evalMap(_ => governor.setPhase(Gsm.Phase.Syncing)).void
                        }.flatten
                      }
                    Resource.make(Ref.of[IO, IO[Unit]](IO.unit))(_.get.flatten).use { release =>
                      def launch(active: Set[PeerId]): IO[Unit] = IO.uncancelable { _ =>
                        start(active).allocated.flatMap((_, close) => release.set(close))
                      }
                      def smartDelay(duration: BigInt): IO[Boolean] =
                        if !scenario.config.downtimePicos.exists(duration > _) then
                          clock.sleep(duration).as(false)
                        else
                          for
                            before <- terminals.get
                            ended = before.map(_.peer).toSet
                            close <- release.getAndSet(IO.unit)
                            _ <- close
                            _ <- terminals.set(before)
                            _ <- clock.sleep(duration)
                            _ <- launch(order.filterNot(ended).toSet)
                          yield true
                      for
                        _ <- launch(order.toSet)
                        _ <- scenario.timeline.traverse_(tick =>
                          servers(tick.peer)
                            .publish(tick.state) *> smartDelay(tick.durationPicos).void
                        )
                        restarted <- smartDelay(
                          (scenario.minEndTimePicos - scenario.timeline.lastOption
                            .fold(BigInt(0))(_.atPicos)).max(0)
                        )
                        _ <- clock.sleep(scenario.minEndTimePicos).whenA(restarted)
                        result <- (chain.get, closed.get, terminals.get, gdd.get, downloaded.get)
                          .mapN { (selected, failures, ended, decisions, headers) =>
                            Result(
                              Fragment(
                                selected.anchor.point,
                                selected.blocks.map(_._1.point).collect {
                                  case point: ChainSync.Point.Block => point
                                }
                              ),
                              failures,
                              ended,
                              decisions,
                              headers
                            )
                          }
                      yield result
                    }
                  }
                  original <- (
                    universe.decode(result.selection.anchor),
                    result.selection.headers.traverse(p =>
                      universe.decode(p).flatMap {
                        case block: ChainSync.Point.Block => Right(block)
                        case other => Left(UpstreamGenesisBlocks.Problem.MissingPoint(other))
                      }
                    ),
                    result.downloaded.traverse(download =>
                      universe.decode(download.header).flatMap {
                        case block: ChainSync.Point.Block => Right(download.copy(header = block))
                        case other => Left(UpstreamGenesisBlocks.Problem.MissingPoint(other))
                      }
                    )
                  ).mapN((anchor, headers, downloads) =>
                    result.copy(selection = Fragment(anchor, headers), downloaded = downloads)
                  ).leftMap(Problem.Blocks(_))
                    .fold(Raise[IO, Problem].raise, IO.pure)
                yield original
              }
            }
          yield outcome
    },
    seed = Some(runtimeSeed(scenario))
  )
