// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import cats.mtl.{Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ExUnits}
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, Redeemer, ScriptLocation}
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, BlockRules, FollowerEvents, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  ChainError,
  EpochGeometry,
  Fixtures,
  Header,
  NodeError,
  PeerError,
  StorageError,
  ValidationError
}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.ChainPersistence
import scala.concurrent.duration.*

/** How a session of the follower ends, whatever goes wrong in it (issue 154): every failure is one
  * of the cases of [[SessionEnd]], the policy gives it a [[Disposition]], and nothing is an
  * exception that the session's fiber dies of. The failures before the new tip is published (the
  * rules refuse a block, the storage fails), during its publication (what follows the new chain's
  * publication fails) and an exception that is not ours.
  */
class SessionFailureSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 30.seconds

  private val geometry =
    EpochGeometry(
      java.time.Instant.EPOCH,
      1000,
      epochLength = 100,
      securityParam = 5,
      activeSlotsCoeff = 0.5
    )
  private val tip = ChainSync.Tip.Origin
  private val noEvents: FollowerEvents[IO] = _ => IO.unit
  private val headers: Vector[Header] = Fixtures.chain(5)

  /** What a session left behind. */
  private final case class Result(
      end: SessionEnd,
      chain: VolatileChain[BigInt],
      stored: Vector[BlockRef],
      published: Vector[Header]
  )

  /** Run a session of the follower over a peer that announces `headers` in turn and then waits.
    * `refuse` is the header the rules reject, `breakStorage` the number of the block whose
    * persistence fails, `breakTip` the number of the block after which telling the mempool fails,
    * `explode` the number of the block whose rules throw.
    */
  private def session(
      script: List[Either[PeerError, SyncEvent]] =
        headers.toList.map(h => Right(SyncEvent.RollForward(h, tip))),
      refuse: Option[Int] = None,
      breakStorage: Option[Int] = None,
      breakTip: Option[Int] = None,
      explode: Option[Int] = None,
      unavailable: Option[Int] = None
  ): IO[Result] =
    NodeScope.resource.use { node =>
      for
        ref <- Ref.of[IO, VolatileChain[BigInt]](
          VolatileChain.genesis(geometry.securityParam, -1)
        )
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
        stored <- Ref.of[IO, Vector[BlockRef]](Vector.empty)
        published <- Ref.of[IO, Vector[Header]](Vector.empty)
        persistence = new ChainPersistence[IO, BigInt]:
          def extended(
              block: BlockRef,
              raw: Bytes,
              immutable: Vector[BlockRef],
              anchor: Anchor[BigInt]
          )(using
              Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError],
              Raise[IO, xyz.kd5ujc.scarab.ledger.ChainError]
          ) =
            if breakStorage.contains(block.blockNo.toInt) then
              node.raise[NodeError, Unit](
                StorageError.Io("volatile", new java.io.IOException("disk full"))
              )
            else stored.update(_ :+ block)
          def rolledBack(to: ChainSync.Point)(using
              Raise[IO, xyz.kd5ujc.scarab.ledger.StorageError],
              Raise[IO, xyz.kd5ujc.scarab.ledger.ChainError]
          ) = IO.unit
        rules: BlockRules[IO, BigInt] = BlockRules.from { (_, header, _) =>
          if explode.contains(header.blockNo.toInt) then
            IO.raiseError(new IllegalStateException("a bug in the rules"))
          else if unavailable.contains(header.blockNo.toInt) then
            Raise[IO, ValidationError].raise[ValidationError, BigInt](
              ValidationError(
                header.point,
                BlockFailure.Tx(
                  LedgerFailure.Unavailable(
                    Bytes.empty,
                    ScriptLocation.Phase2(Redeemer(0, 0, Bytes.empty, ExUnits.zero)),
                    EvalFailure
                      .Unsupported(
                        xyz.kd5ujc.scarab.plutus.BackendFailure
                          .BuiltinUnavailable(scalus.uplc.DefaultFun.VerifyEd25519Signature)
                      )
                  )
                )
              )
            )
          else if refuse.contains(header.blockNo.toInt) then
            Raise[IO, ValidationError].raise[ValidationError, BigInt](
              ValidationError(header.point, BlockFailure.HeaderDiffers)
            )
          else IO.pure(header.blockNo)
        }
        follower <- Follower.create[IO, BigInt](
          rules,
          noEvents,
          geometry,
          persistence = Some(persistence),
          onTip = Some(
            Follower.OnTip((chain, _) =>
              chain.tipRef.fold(IO.unit)(tipRef =>
                published.update(_ :+ headers.find(_.hash == tipRef.hash).get) *>
                  Raise[IO, NodeError]
                    .raise[NodeError, Unit](ChainError.NoIntersection)
                    .whenA(
                      breakTip.contains(tipRef.blockNo.toInt)
                    )
              )
            )
          )
        )
        queue <- Queue.unbounded[IO, Either[PeerError, SyncEvent]]
        _ <- script.traverse_(queue.offer)
        peer = new Peer[IO]:
          def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
            IO.pure(candidates.headOption)
          def next(using raise: Raise[IO, PeerError]) = queue.tryTake.flatMap {
            case Some(Right(event)) => IO.pure(event)
            case Some(Left(error))  => raise.raise[PeerError, SyncEvent](error)
            case None               => IO.never[SyncEvent]
          }
          def fetch(header: Header)(using Raise[IO, PeerError]) = IO.pure(Bytes.empty)
        // the session as the governor runs it: in its own scope, ending with a value
        end <- SessionEnd
          .scoped(node) { (_, _) ?=>
            follower.session(peer).as(SessionEnd.Returned)
          }
          .timeoutTo(2.seconds, IO.pure(SessionEnd.Stopped(false)))
        chain <- ref.get
        left <- (stored.get, published.get).mapN(Result(end, chain, _, _))
      yield left
    }

  private def blockNos(blocks: Vector[BlockRef]) = blocks.map(_.blockNo.toInt)

  test("control: the peer announces five blocks and waits: all are stored and published") {
    session().map { left =>
      assertEquals(left.end, SessionEnd.Stopped(false))
      assertEquals(left.chain.tip, headers.last.point)
      assertEquals(blockNos(left.stored), Vector(0, 1, 2, 3, 4))
    }
  }

  test(
    "before the tip: the rules refuse a block: a rejection, the chain stays at the block before"
  ) {
    session(refuse = Some(2)).map { left =>
      left.end match
        case SessionEnd.Rejected(e) => assertEquals(e.point, headers(2).point)
        case other                  => fail(s"unexpected $other")
      assertEquals(left.chain.tip, headers(1).point)
      assertEquals(blockNos(left.stored), Vector(0, 1))
      // the peer sent invalid data: forgotten, as for a broken header
      assertEquals(FailurePolicy.disposition(left.end), Disposition.Forget)
    }
  }

  test("before the tip: the storage fails: the node stops, the tip is not published") {
    session(breakStorage = Some(2)).map { left =>
      left.end match
        case SessionEnd.Storage(StorageError.Io("volatile", _)) => ()
        case other                                              => fail(s"unexpected $other")
      // the block that could not be stored is not on the published chain
      assertEquals(left.chain.tip, headers(1).point)
      assertEquals(blockNos(left.stored), Vector(0, 1))
      FailurePolicy.disposition(left.end) match
        case Disposition.StopNode(Fatal.Storage(_)) => ()
        case other                                  => fail(s"unexpected $other")
    }
  }

  test("during publication: the chain is published and stored when telling the mempool fails") {
    session(breakTip = Some(2)).map { left =>
      assertEquals(left.end, SessionEnd.Internal(ChainError.NoIntersection))
      // the block is on the chain and on disk: nothing published outruns what is stored
      assertEquals(left.chain.tip, headers(2).point)
      assertEquals(blockNos(left.stored), Vector(0, 1, 2))
      FailurePolicy.disposition(left.end) match
        case Disposition.StopNode(Fatal.Internal(_)) => ()
        case other                                   => fail(s"unexpected $other")
    }
  }

  test("an exception that is not ours is a defect, not a failure of the connection") {
    session(explode = Some(1)).map { left =>
      left.end match
        case SessionEnd.Defect(cause) => assertEquals(cause.getMessage, "a bug in the rules")
        case other                    => fail(s"unexpected $other")
      assertEquals(left.chain.tip, headers(0).point)
      FailurePolicy.disposition(left.end) match
        case Disposition.StopNode(Fatal.Defect(_)) => ()
        case other                                 => fail(s"unexpected $other")
    }
  }

  test("a peer that breaks the protocol is forgotten, one that is unreachable is tried again") {
    for
      broken <- session(script =
        List(
          Right(SyncEvent.RollForward(headers(0), tip)),
          Left(
            PeerError.Protocol(
              xyz.kd5ujc.scarab.protocol.ProtocolError.UnknownTagOrWrongExactOuterArity
            )
          )
        )
      )
      lost <- session(script =
        List(Left(PeerError.Unavailable(PeerError.Unavailability.SessionEnded)))
      )
    yield
      assertEquals(FailurePolicy.disposition(broken.end), Disposition.Forget)
      assertEquals(FailurePolicy.disposition(lost.end), Disposition.Backoff)
      assertEquals(broken.chain.tip, headers(0).point)
  }

  test("no intersection is the reference normal ChainSync result, not a failed connection") {
    session(script = List(Left(PeerError.NoIntersection))).map { result =>
      assertEquals(result.end, SessionEnd.NoMoreIntersection)
      assertEquals(FailurePolicy.disposition(result.end), Disposition.Settled)
      assertEquals(SessionEnd.peerError(result.end), None)
      assertEquals(SessionEnd.networkFailure(result.end), None)
    }
  }

  test("rollback before the intersection is a normal result with the native 180-second delay") {
    val point = ChainSync.Point.Origin
    session(script = List(Left(PeerError.RolledBackPastIntersection(point)))).map { result =>
      assertEquals(result.end, SessionEnd.RolledBackPastIntersection(point))
      assertEquals(FailurePolicy.disposition(result.end), Disposition.Settled)
      assertEquals(SessionEnd.returnDelay(result.end), Some(180.seconds))
      assertEquals(SessionEnd.networkFailure(result.end), None)
    }
  }

  test("an implementation limit stops without a ledger verdict or peer failure, exit eight") {
    session(unavailable = Some(2)).map { left =>
      left.end match
        case SessionEnd.Unavailable(error) =>
          assertEquals(error.point, headers(2).point)
          assertEquals(Main.FaultExitCode(Fatal.Unavailable(error)).code, 8)
          assertEquals(
            FailurePolicy.disposition(left.end),
            Disposition.StopNode(Fatal.Unavailable(error))
          )
        case other => fail(s"unexpected $other")
      assertEquals(SessionEnd.peerError(left.end), None)
      assertEquals(left.chain.tip, headers(1).point)
      assertEquals(blockNos(left.stored), Vector(0, 1))
    }
  }
