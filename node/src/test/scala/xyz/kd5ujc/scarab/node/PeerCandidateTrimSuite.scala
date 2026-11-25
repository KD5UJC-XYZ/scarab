// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.consensus.HeaderChain
import xyz.kd5ujc.scarab.ledger.{Header, PeerError}
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.genesis.{LeakyBucket, LimitOnPatience}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.{ChainSync, NtNHeader}
import scala.concurrent.duration.*

/** A session's candidate follows the header gate's history as it is trimmed to our chain's anchor
  * (issue 810): once more headers than the retained window have passed while our anchor advanced,
  * the candidate is still short, its capacity room never runs out, and publication never starves.
  * Before, the candidate grew to the window and its oldest block, long ours and never fetched
  * again, held the session's ChainSync still.
  */
class PeerCandidateTrimSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val magic = 42L
  private val retained = 8
  // how far our anchor is behind what the consumer took, and how far past it the gate lets the
  // peer's headers run (the forecast range of the real gate)
  private val anchorLag = 2
  private val forecast = 3
  private val pipelineWindow =
    ChainSyncPipelineDecision.Window.checked(2, 4).fold(error => fail(error.toString), identity)

  private val forged: IO[ForgedChain] = ForgedChain.forge(5000 until 5180)

  /** A gate that accepts every header within the forecast range of what the consumer took, waiting
    * for the consumer otherwise (none: it waits for nothing), and whose history starts at our
    * chain's anchor. `accepted` is told of each header it accepts.
    */
  private def gate(
      taken: Option[SignallingRef[IO, Int]],
      anchor: Ref[IO, ChainSync.Point],
      accepted: Header => IO[Unit] = _ => IO.unit
  ): HeaderCheck[IO] = new HeaderCheck[IO]:
    def withLeios(leios: LeiosChainSync[IO]): HeaderCheck[IO] = this
    def start(point: ChainSync.Point) = IO.pure(new HeaderGate[IO]:
      def counts = IO.pure(HeaderGate.Counts(0, 0, 0))
      def accept(batch: Vector[SyncEvent])(using Raise[IO, PeerError]) =
        batch.traverse_ {
          case SyncEvent.RollForward(header, _) =>
            taken.traverse_(_.waitUntil(n => header.blockNo <= BigInt(n + forecast))) *>
              accepted(header)
          case SyncEvent.RollBackward(_, _) => IO.unit
        }
      override def snapshot(using cats.Applicative[IO]) =
        anchor.get.map(point => Some(HeaderChain.at[NodeHeaderState](point, None))))

  /** A patience bucket that only says when ChainSync first waits for local capacity. */
  private def capacityWatch(full: Deferred[IO, Unit]): LimitOnPatience[IO] =
    new LimitOnPatience[IO]:
      def setPaused(reason: LimitOnPatience.PauseReason, paused: Boolean): IO[Unit] =
        full.complete(()).void.whenA(reason == LimitOnPatience.PauseReason.LocalCapacity && paused)
      def validated(blockNo: BigInt): IO[Unit] = IO.unit
      def jumpedTo(blockNo: BigInt): IO[Unit] = IO.unit
      def configure(config: LeakyBucket.Config): IO[Unit] = IO.unit
      def snapshot: IO[LeakyBucket.State] =
        IO.pure(LeakyBucket.State(Rational.zero, 0, false, 0, LeakyBucket.Config.disabled))

  test(
    "a candidate trimmed to our advancing anchor never fills its window or starves publication"
  ) {
    forged.flatMap { chain =>
      // every header the forged chain has but the last few, which the peer serves at its tip
      val pulls = chain.headers.size - forecast - 2
      assert(pulls > retained * 4, s"${chain.headers.size} headers are too few to pass the window")
      FetchCoordinator
        .create[IO]()
        .flatMap { coordinator =>
          // this coordinator decides nothing: no block is ever fetched, as no window's oldest is
          coordinator.session(11L).use { session =>
            TestControl.executeEmbed {
              for
                taken <- SignallingRef.of[IO, Int](0)
                anchor <- Ref.of[IO, ChainSync.Point](ChainSync.Point.Origin)
                longest <- Ref.of[IO, Int](0)
                _ <- connected(chain, gate(Some(taken), anchor), session) { peer =>
                  val candidate =
                    peer.headerCandidate.getOrElse(fail("a streaming session has a candidate"))
                  val pull = peer.next.flatMap {
                    case SyncEvent.RollForward(header, _) =>
                      val index = chain.headers.indexWhere(_.point == header.point)
                      // our chain adopts what the consumer takes, its anchor `anchorLag` behind
                      chain.headers.lift(index - anchorLag).traverse_(h => anchor.set(h.point)) *>
                        taken.set(header.blockNo.toInt) *>
                        candidate.get.flatMap(c => longest.update(_.max(c.headers.size)))
                    case SyncEvent.RollBackward(_, _) => IO.unit
                  }
                  peer.intersect(Vector(ChainSync.Point.Origin)) *>
                    (1 to pulls).toList.traverse_(_ => pull)
                }
                most <- longest.get
              yield most
            }
          }
        }
        .map { most =>
          // the anchor, what the consumer has not taken yet and the forecast range, well inside the
          // window of eight
          assert(most < retained, s"the candidate reached $most headers")
        }
    }
  }

  test("a trim the publication held back is taken once publication passes our anchor again") {
    forged.flatMap { chain =>
      val first = 4
      val pulls = chain.headers.size - 2
      FetchCoordinator
        .create[IO]()
        .flatMap { coordinator =>
          // nothing is ever fetched: a full window waits for the trim alone
          coordinator.session(12L).use { session =>
            TestControl.executeEmbed {
              for
                anchor <- Ref.of[IO, ChainSync.Point](ChainSync.Point.Origin)
                full <- Deferred[IO, Unit]
                // our chain follows another peer: its anchor two headers behind what this session's
                // gate accepted, however far behind this session's consumer is
                follows = (header: Header) =>
                  chain.headers
                    .lift(chain.headers.indexWhere(_.point == header.point) - 2)
                    .traverse_(h => anchor.set(h.point))
                _ <- connected(
                  chain,
                  gate(None, anchor, follows),
                  session,
                  Some(capacityWatch(full))
                ) { peer =>
                  peer.intersect(Vector(ChainSync.Point.Origin)) *>
                    (1 to first).toList.traverse_(_ => peer.next) *>
                    // the consumer stops until the window is full: the trim waits for publication
                    full.get *> (first until pulls).toList.traverse_(_ => peer.next)
                }
              yield ()
            }
          }
        }
    }
  }

  private def connected[A](
      forged: ForgedChain,
      check: HeaderCheck[IO],
      fetchSession: FetchCoordinator.Session[IO],
      patience: Option[LimitOnPatience[IO]] = None
  )(
      body: Peer.Controlled[IO] => Raise[IO, PeerError] ?=> IO[A]
  ): IO[A] =
    for
      honest <- ServedChain(forged)
      handlers = new ServerHandlers[IO, NtNHeader](
        honest.codec,
        honest.chainSync,
        honest.blocks,
        honest.transactions
      )
      pair <- Bearer.inMemory[IO](256)
      (theirs, ours) = pair
      result <- Handle
        .allow[NetworkError](Served.accept(theirs, magic, handlers).void)
        .rescue(_ => IO.unit)
        .background
        .use { _ =>
          Handle
            .allow[NetworkError] {
              NodeToNode
                .over[IO, NtNHeader](
                  ours,
                  magic,
                  NtNHeader.codec,
                  NodeToNode.Settings().pipelining(pipelineWindow.high)
                )
                .use { connection =>
                  Peer
                    .over[IO](
                      connection,
                      Peer.Settings(
                        SocketAddress(host"127.0.0.1", port"3001"),
                        magic,
                        readAhead = 2,
                        byronEpochSlots = ForgedChain.geometry.byron.epochSlots,
                        pipelineWindow = pipelineWindow
                      ),
                      heightSnapshot = ChainSyncHeights.origin,
                      chain = Peer.Chain.Praos(Some(check)),
                      patience = patience,
                      retainedHeaders = retained,
                      fetchSession = Some(fetchSession)
                    )
                    .use(peer =>
                      Handle.allow[PeerError](body(peer)).rescue(e => IO(fail(e.message)))
                    )
                }
            }
            .rescue(e => IO(fail(e.message)))
        }
    yield result
