// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.TestGeometry
import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import cats.mtl.{Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ExUnits}
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, Redeemer, ScriptLocation}
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, BlockRules, FollowerEvent, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  BlockRule,
  ChainError,
  EpochGeometry,
  EvaluationUnavailable,
  Fixtures,
  Header,
  HeaderError,
  HeaderStructureProblem,
  NodeError,
  PeerError,
  ProviderDefect,
  StorageError,
  ValidationError,
  VerificationDefect
}
import xyz.kd5ujc.scarab.crypto.kes.Sum6Kes
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
import xyz.kd5ujc.scarab.crypto.witness.VerificationError
import xyz.kd5ujc.scarab.ledger.byron.ByronSig
import xyz.kd5ujc.scarab.ledger.rules.TickFailure
import xyz.kd5ujc.scarab.ledger.state.VocabError
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
      securityParam = TestGeometry.k(5),
      activeSlotsCoeff = TestGeometry.f("0.5")
    )
  private val tip = ChainSync.Tip.Origin
  private val headers: Vector[Header] = Fixtures.chain(5)

  /** What a session left behind. */
  private final case class Result(
      end: SessionEnd,
      chain: VolatileChain[BigInt],
      stored: Vector[BlockRef],
      published: Vector[Header],
      events: Vector[FollowerEvent] = Vector.empty,
      invalid: Set[Bytes] = Set.empty
  )

  /** Run a session of the follower over a peer that announces `headers` in turn and then waits.
    * `refuse` is the header the rules reject, `breakStorage` the number of the block whose
    * persistence fails, `breakTip` the number of the block after which telling the mempool fails,
    * `explode` the number of the block whose rules throw, `unverifiable` the number of the block on
    * which a crypto provider fails and the failure the rules report for it.
    */
  private def session(
      script: List[Either[PeerError, SyncEvent]] =
        headers.toList.map(h => Right(SyncEvent.RollForward(h, tip))),
      refuse: Option[Int] = None,
      breakStorage: Option[Int] = None,
      breakTip: Option[Int] = None,
      explode: Option[Int] = None,
      unavailable: Option[Int] = None,
      held: Option[Int] = None,
      unverifiable: Option[(Int, BlockFailure)] = None,
      outOfRange: Option[Int] = None
  ): IO[Result] =
    NodeScope.resource.use { node =>
      for
        ref <- Ref.of[IO, VolatileChain[BigInt]](
          VolatileChain.genesis(geometry.k.toInt, -1)
        )
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
        stored <- Ref.of[IO, Vector[BlockRef]](Vector.empty)
        published <- Ref.of[IO, Vector[Header]](Vector.empty)
        emitted <- Ref.of[IO, Vector[FollowerEvent]](Vector.empty)
        registry <- InvalidBlocks.create[IO]
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
          else if unverifiable.exists(_._1 == header.blockNo.toInt) then
            Raise[IO, ValidationError].raise[ValidationError, BigInt](
              ValidationError(header.point, unverifiable.fold(BlockFailure.HeaderDiffers)(_._2))
            )
          else if outOfRange.contains(header.blockNo.toInt) then
            Raise[IO, ChainError].raise[ChainError, BigInt](
              ChainError.StateOutOfRange(header.slot, pot)
            )
          else if held.contains(header.blockNo.toInt) then
            Raise[IO, ValidationError].raise[ValidationError, BigInt](
              ValidationError(header.point, BlockFailure.Tx(heldFailure))
            )
          else if refuse.contains(header.blockNo.toInt) then
            Raise[IO, ValidationError].raise[ValidationError, BigInt](
              ValidationError(header.point, BlockFailure.HeaderDiffers)
            )
          else IO.pure(header.blockNo)
        }
        follower <- Follower.create[IO, BigInt](
          rules,
          event => emitted.update(_ :+ event),
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
        _ <- follower.installInvalidBlocks(registry)
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
        left <- (stored.get, published.get, emitted.get, registry.snapshot).mapN(
          (s, p, e, invalid) => Result(end, chain, s, p, e, invalid.entries.keySet)
        )
      yield left
    }

  /** A block with no verdict: Scalus threw on its scripts (a defect the block triggers). */
  private val heldFailure: LedgerFailure.Unavailable = LedgerFailure.Unavailable(
    Bytes.empty,
    ScriptLocation.Phase2(Redeemer(0, 0, Bytes.empty, ExUnits.zero)),
    EvalFailure.Unsupported(
      xyz.kd5ujc.scarab.plutus.BackendFailure.Defect(new NullPointerException("in Scalus"))
    )
  )

  /** A pot that left the range of its type. */
  private val pot: VocabError = VocabError.OutOfRange(VocabError.Kind.CompactCoin, BigInt(1) << 64)

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

  // Issue 393: a crypto provider that fails on a block, whichever primitive it is, stops the node
  // (Fatal.Unverifiable), as the exception ends the reference's linked add-block thread: no invalid
  // record and no blame for the peer.
  private val boom = new IllegalStateException("provider broke")
  private val providerDefects: List[(String, BlockFailure, VerificationDefect.Site)] =
    List(
      (
        "Praos VRF",
        BlockFailure.Unverifiable(
          BlockRule.Praos,
          ProviderDefect.VrfVerifier(Vrf.Result.InternalFailure(boom))
        ),
        VerificationDefect.Site.Header
      ),
      (
        "TPraos VRF",
        BlockFailure.Unverifiable(
          BlockRule.TPraos,
          ProviderDefect.VrfVerifier(Vrf.Result.PrimitiveContractViolation(None))
        ),
        VerificationDefect.Site.Header
      ),
      (
        "KES",
        BlockFailure
          .Unverifiable(BlockRule.Praos, ProviderDefect.Kes(Sum6Kes.Failure.Defect(boom))),
        VerificationDefect.Site.Header
      ),
      (
        "certificate signature",
        BlockFailure.Unverifiable(
          BlockRule.TPraos,
          ProviderDefect.OpCertSignature(OperationalCertificate.Failure.Defect(boom))
        ),
        VerificationDefect.Site.Header
      ),
      (
        "Byron Ed25519",
        BlockFailure.Unverifiable(
          BlockRule.Byron,
          ProviderDefect.Ed25519(VerificationError.ImplementationFailure(boom))
        ),
        VerificationDefect.Site.Header
      ),
      // the failures the real Byron rules give when the provider fails on a signature of the
      // Byron ledger (issue 438): not a Byron refusal, so not recorded and not blamed
      (
        "Byron ledger transaction witness",
        ByronLedgerDefectKit.failure(ByronSig.SignTx),
        VerificationDefect.Site.Header
      ),
      (
        "Byron ledger update proposal",
        ByronLedgerDefectKit.failure(ByronSig.SignUpdateProposal),
        VerificationDefect.Site.Header
      ),
      (
        "Ed25519 witness",
        BlockFailure.Tx(
          LedgerFailure.Unverifiable(
            Bytes.fromArray(Array.fill(32)(3.toByte)),
            ProviderDefect.Ed25519(VerificationError.ImplementationFailure(boom))
          )
        ),
        VerificationDefect.Site.Witness(Bytes.fromArray(Array.fill(32)(3.toByte)))
      )
    )

  providerDefects.foreach { (name, failure, site) =>
    test(s"a $name provider defect stops the node: no invalid record, no blame") {
      session(unverifiable = Some(2 -> failure)).map { left =>
        val defect = failure match
          case BlockFailure.Unverifiable(_, d)                   => d
          case BlockFailure.Tx(LedgerFailure.Unverifiable(_, d)) => d
          case other                                             => fail(s"unexpected $other")
        val held = VerificationDefect(headers(2).point, site, defect)
        assertEquals(left.end, SessionEnd.Unverifiable(held))
        assertEquals(
          FailurePolicy.disposition(left.end),
          Disposition.StopNode(Fatal.Unverifiable(held))
        )
        assertEquals(SessionEnd.peerError(left.end), None)
        assertEquals(left.invalid, Set.empty[Bytes])
        assertEquals(left.chain.tip, headers(1).point)
        assertEquals(blockNos(left.stored), Vector(0, 1))
      }
    }
  }

  test("a provider defect on a header disconnects the peer: recorded nowhere, tried again later") {
    val held = VerificationDefect(
      headers(2).point,
      VerificationDefect.Site.Header,
      ProviderDefect.VrfVerifier(Vrf.Result.InternalFailure(boom))
    )
    val script =
      headers.take(2).toList.map(h => Right(SyncEvent.RollForward(h, tip))) :+
        Left(PeerError.Unverifiable(held))
    session(script = script).map { left =>
      assertEquals(left.end, SessionEnd.Failed(PeerError.Unverifiable(held)))
      assertEquals(FailurePolicy.disposition(left.end), Disposition.Backoff)
      assertEquals(left.invalid, Set.empty[Bytes])
    }
  }

  test("a provider defect on a stored block stops replay with exit 7, as a library failure") {
    val held = VerificationDefect(
      headers(2).point,
      VerificationDefect.Site.Header,
      ProviderDefect.Kes(Sum6Kes.Failure.Defect(boom))
    )
    val (_, code) = Main.startupFailure(held)
    assertEquals(code.code, 7)
  }

  test("a defect a block triggers stops the node: no invalid record, no blame") {
    session(held = Some(2)).map { left =>
      left.end match
        case SessionEnd.Unavailable(error) =>
          assertEquals(error, EvaluationUnavailable(headers(2).point, heldFailure))
          assertEquals(
            FailurePolicy.disposition(left.end),
            Disposition.StopNode(Fatal.Unavailable(error))
          )
        case other => fail(s"unexpected $other")
      assertEquals(SessionEnd.peerError(left.end), None)
      assertEquals(left.invalid, Set.empty[Bytes])
      assertEquals(left.chain.tip, headers(1).point)
      assertEquals(blockNos(left.stored), Vector(0, 1))
    }
  }

  // cardano-ledger f649f975 `compactCoinOrError` is an `error`; ouroboros-consensus 82ecba32
  // `Storage/ChainDB/Impl/Background.hs:107-109,142`: the block runner is a linked thread
  test("a ledger state out of range stops the node: no refusal, no invalid record, no blame") {
    session(outOfRange = Some(2)).map { left =>
      val fault = ChainError.StateOutOfRange(headers(2).slot, pot)
      assertEquals(left.end, SessionEnd.Internal(fault))
      assertEquals(
        FailurePolicy.disposition(left.end),
        Disposition.StopNode(Fatal.Internal(fault))
      )
      assertEquals(SessionEnd.peerError(left.end), None)
      assertEquals(left.invalid, Set.empty[Bytes])
      assertEquals(left.chain.tip, headers(1).point)
      assertEquals(blockNos(left.stored), Vector(0, 1))
    }
  }

  test(
    "a quantity of the state out of range in the tick or a transaction rule is a stop; a number of the block is a refusal"
  ) {
    val header = headers(2)
    val fault = Some(ChainError.StateOutOfRange(header.slot, pot))
    assertEquals(
      MirrorRules.stateOutOfRange(header, BlockFailure.Tick(TickFailure.Vocab(pot))),
      fault
    )
    assertEquals(
      MirrorRules.stateOutOfRange(
        header,
        BlockFailure.Tx(LedgerFailure.StateOutOfRange(Bytes.empty, pot))
      ),
      fault
    )
    assertEquals(
      MirrorRules.stateOutOfRange(header, BlockFailure.Tick(TickFailure.Input(pot))),
      None
    )
    assertEquals(
      MirrorRules.stateOutOfRange(header, BlockFailure.Tx(LedgerFailure.Vocab(pot))),
      None
    )
    assertEquals(MirrorRules.stateOutOfRange(header, BlockFailure.HeaderDiffers), None)
  }

  // the per-epoch transition counter of the consensus state is a Word32 whose `succ` throws at
  // maxBound in the reference: the node stops, the block is not refused (owner decision on #483)
  test(
    "the transition counter past Word32 is a stop; another state the mirror cannot hold is not"
  ) {
    val header = headers(2)
    assertEquals(
      MirrorRules.stateOutOfRange(
        header,
        BlockFailure.Mirror(
          xyz.kd5ujc.scarab.consensus.state.StateMirrorError.TransitionCounterOverflow
        )
      ),
      Some(
        ChainError.StateOutOfRange(
          header.slot,
          xyz.kd5ujc.scarab.ledger.state.VocabError
            .OutOfRange(xyz.kd5ujc.scarab.ledger.state.VocabError.Kind.Word32, BigInt(1) << 32)
        )
      )
    )
    assertEquals(
      MirrorRules.stateOutOfRange(
        header,
        BlockFailure.Mirror(
          xyz.kd5ujc.scarab.consensus.state.StateMirrorError.ExtrasMismatch(
            xyz.kd5ujc.scarab.protocol.Era.Conway
          )
        )
      ),
      None
    )
  }

  // the Byron to Shelley hand-over of the header forecast met the state out of range: the header
  // pipeline ends the session with it, and the node stops without blaming the peer
  test("a state out of range in the header forecast stops the node: no invalid record, no blame") {
    val header = headers(2)
    val forecast = PeerError.InvalidHeader(
      header.point,
      HeaderError.Structure(
        HeaderStructureProblem.Transition(BlockFailure.Tick(TickFailure.Vocab(pot)))
      )
    )
    val script = headers.take(2).toList.map(h => Right(SyncEvent.RollForward(h, tip))) :+
      Left(forecast)
    session(script).map { left =>
      val fault = ChainError.StateOutOfRange(header.slot, pot)
      assertEquals(left.end, SessionEnd.Internal(fault))
      assertEquals(
        FailurePolicy.disposition(left.end),
        Disposition.StopNode(Fatal.Internal(fault))
      )
      assertEquals(SessionEnd.peerError(left.end), None)
      assertEquals(left.invalid, Set.empty[Bytes])
      assertEquals(blockNos(left.stored), Vector(0, 1))
    }
  }

  test(
    "a number of the block that is out of range in the header forecast stays an invalid header"
  ) {
    val refused = PeerError.InvalidHeader(
      headers(2).point,
      HeaderError.Structure(
        HeaderStructureProblem.Transition(BlockFailure.Tick(TickFailure.Input(pot)))
      )
    )
    assertEquals(SessionEnd.of(refused), SessionEnd.Failed(refused))
  }

  test("an implementation limit is never recorded as an invalid block") {
    session(unavailable = Some(2)).map { left =>
      assertEquals(left.invalid, Set.empty[Bytes])
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
