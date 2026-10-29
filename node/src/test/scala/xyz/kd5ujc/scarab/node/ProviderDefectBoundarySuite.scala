// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.mtl.{Handle, Raise, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockNoVerdict
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent, VolatileChain}
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.crypto.kes.Sum6Kes
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  BlockRule,
  Header,
  HeaderVrf,
  OCertError,
  PeerError,
  ProviderDefect,
  NodeError,
  ValidationError,
  VerificationDefect,
  Verdicts
}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync
import scala.concurrent.duration.*

/** A crypto provider that fails on a peer's header or block is no verdict on it (issue 393). The
  * real Praos rules, header-first and in block validation, return the [[ProviderDefect]] beside
  * their refusals; a session that meets it in a block ends [[SessionEnd.Unverifiable]]: the node
  * stops, nothing is recorded in [[InvalidBlocks]] and the peer is not blamed. A genuinely invalid
  * proof is still an invalid header.
  *
  * The reference throws a library failure, which the Praos rules do not catch (ouroboros-consensus
  * 82ecba32 `Protocol/Praos.hs` 587–592 and 637–640), and a ChainSync client's exception that no
  * rethrow policy names shuts down that peer, not the node (`Node/RethrowPolicy.hs` 40–45,
  * 131–139).
  */
class ProviderDefectBoundarySuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val boom = new IllegalStateException("provider broke")

  private lazy val forged: ForgedChain =
    ForgedChain.forge(0 until 90, epoch = 0).unsafeRunSync()(using munitIORuntime)

  private def ref(h: Header.Shelley) = BlockRef(h.slot, h.hash, h.blockNo)

  private val index = 10

  private def target: Header.Shelley = forged.headers(index)

  private def before: NodeHeaderState.Shelley =
    NodeHeaderState.Shelley(
      forged.states(index - 1).protocol,
      Some(ref(forged.headers(index - 1))),
      forged.genesis.consensus.byron.firstShelleyEpoch,
      None
    )

  private def validate(verdicts: Verdicts, header: Header.Shelley) =
    NodeHeaderRules[MirrorState](
      ForgedChain.config.copy(verdicts = verdicts),
      ForgedChain.globals
    ).validate(
      forged.states(index - 1),
      Some(ref(forged.headers(index - 1))),
      before,
      header
    )

  /** The real verifiers, except that the VRF verifier answers `vrf` for the target header. */
  private def failingVrf(vrf: Vrf.Result) = new Verdicts:
    export Verdicts.Direct.{keyHash, opCert, takePrepared, tpraosVrf, witness}
    def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
      target.vrf match
        case HeaderVrf.Praos(cert) if cert.proof == proof => vrf
        case _ => Verdicts.Direct.praosVrf(input, vkey, proof, output)

  /** The real verifiers, except that the OCERT verifiers report `defect` for the target header. */
  private def failingOCert(defect: ProviderDefect) = new Verdicts:
    export Verdicts.Direct.{keyHash, praosVrf, takePrepared, tpraosVrf, witness}
    def opCert(
        header: Header.Shelley,
        slotsPerKesPeriod: BigInt,
        maxKesEvolutions: BigInt
    ): Either[OCertError | ProviderDefect, Unit] =
      if header.hash == target.hash then Left(defect)
      else Verdicts.Direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions)

  /** The real block rules of the forged chain over `verdicts`. */
  private def blockRules(verdicts: Verdicts): MirrorRules[IO] =
    new MirrorRules[IO](
      ForgedChain.config.copy(verdicts = verdicts),
      ForgedChain.globals,
      None,
      None,
      ForgedChain.mirrorEnv.byron
    )

  /** Each primitive of a Praos header, with an injected failing provider. */
  private val primitives: List[(String, Verdicts, ProviderDefect)] =
    List(
      (
        "VRF internal failure",
        failingVrf(Vrf.Result.InternalFailure(boom)),
        ProviderDefect.VrfVerifier(Vrf.Result.InternalFailure(boom))
      ),
      (
        "VRF broken contract",
        failingVrf(Vrf.Result.PrimitiveContractViolation(None)),
        ProviderDefect.VrfVerifier(Vrf.Result.PrimitiveContractViolation(None))
      ), {
        val kes = ProviderDefect.Kes(Sum6Kes.Failure.Defect(boom))
        ("KES verifier defect", failingOCert(kes), kes)
      }, {
        val cert = ProviderDefect.OpCertSignature(
          OperationalCertificate.Failure.PrimitiveFailure(
            xyz.kd5ujc.scarab.crypto.witness.VerificationError.ImplementationFailure(boom)
          )
        )
        ("certificate signature verifier failure", failingOCert(cert), cert)
      }
    )

  /** No verdict on the target block (issue 567): withheld, neither adopted nor recorded as invalid,
    * nobody blamed, the session going on (its timeout ends it), the defect reported.
    */
  private def withheld(
      end: SessionEnd,
      invalid: Set[Bytes],
      events: Vector[FollowerEvent],
      tip: ChainSync.Point,
      held: VerificationDefect
  ): Unit =
    assertEquals(end, SessionEnd.Stopped(false))
    assertEquals(invalid, Set.empty[Bytes])
    assertEquals(tip, forged.headers(index - 1).point)
    assert(
      events.contains(FollowerEvent.NoVerdict(target.point, BlockNoVerdict.Verification(held))),
      events.toString
    )

  primitives.foreach { (name, verdicts, defect) =>
    test(s"header-first: a $name is no verdict, not an invalid header") {
      assertEquals(validate(verdicts, target), Outcome.Unverifiable(defect))
    }

    test(s"block validation: a $name is no verdict, not a refused block") {
      val result = Handle
        .allow[NodeError](
          blockRules(verdicts)
            .apply(forged.states(index - 1), target, forged.raws(index))
            .as(Option.empty[NodeError])
        )
        .rescue(e => IO.pure(Some(e)))
      result.map { refused =>
        assertEquals(
          refused,
          Some(ValidationError(target.point, BlockFailure.Unverifiable(BlockRule.Praos, defect)))
        )
        assertEquals(
          refused.collect { case v: ValidationError => v }.flatMap(_.unverifiable),
          Some(VerificationDefect(target.point, VerificationDefect.Site.Header, defect))
        )
      }
    }

    test(
      s"a session over real rules withholds the block on a $name: no record, no blame, no stop"
    ) {
      session(verdicts).map { (end, invalid, events, tip) =>
        withheld(
          end,
          invalid,
          events,
          tip,
          VerificationDefect(target.point, VerificationDefect.Site.Header, defect)
        )
      }
    }
  }

  private enum Verifier:
    case OCert, Vrf

  /** The real verifiers, except that for the target header the OCERT checks answer `opCert` and the
    * VRF verifier answers `vrf`; `calls` records which of the two the rules asked, in order.
    */
  private final class Ordered(
      opCertAnswer: Either[OCertError | ProviderDefect, Unit],
      vrfAnswer: Vrf.Result
  ) extends Verdicts:
    export Verdicts.Direct.{keyHash, takePrepared, tpraosVrf, witness}
    private val asked = new java.util.concurrent.ConcurrentLinkedQueue[Verifier]()
    def calls: Vector[Verifier] = Vector.from(asked.toArray(Array.empty[Verifier]))
    def opCert(
        header: Header.Shelley,
        slotsPerKesPeriod: BigInt,
        maxKesEvolutions: BigInt
    ): Either[OCertError | ProviderDefect, Unit] =
      if header.hash == target.hash then
        asked.add(Verifier.OCert)
        opCertAnswer
      else Verdicts.Direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions)
    def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
      target.vrf match
        case HeaderVrf.Praos(cert) if cert.proof == proof =>
          asked.add(Verifier.Vrf)
          vrfAnswer
        case _ => Verdicts.Direct.praosVrf(input, vkey, proof, output)

  private val kesDefect = ProviderDefect.Kes(Sum6Kes.Failure.Defect(boom))
  private val rejectedProof = Vrf.Result.OutputMismatch

  test(
    "an OCERT defect beside an invalid VRF proof: the certificate is checked first, no verdict"
  ) {
    val headerFirst = Ordered(Left(kesDefect), rejectedProof)
    assertEquals(validate(headerFirst, target), Outcome.Unverifiable(kesDefect))
    assertEquals(headerFirst.calls, Vector(Verifier.OCert))
    val block = Ordered(Left(kesDefect), rejectedProof)
    session(block).map { (end, invalid, events, tip) =>
      val held = VerificationDefect(target.point, VerificationDefect.Site.Header, kesDefect)
      withheld(end, invalid, events, tip, held)
      assert(!block.calls.contains(Verifier.Vrf), block.calls.toString)
    }
  }

  test("a refused certificate beside a VRF defect: the header is invalid, the VRF is not asked") {
    val headerFirst =
      Ordered(Left(OCertError.KesSignatureRejected), Vrf.Result.InternalFailure(boom))
    validate(headerFirst, target) match
      case Outcome.Invalid(_) => ()
      case other              => fail(s"unexpected $other")
    assertEquals(headerFirst.calls, Vector(Verifier.OCert))
    val block = Ordered(Left(OCertError.KesSignatureRejected), Vrf.Result.InternalFailure(boom))
    session(block).map { (end, invalid, _, tip) =>
      FailurePolicy.disposition(end) match
        case Disposition.StopNode(fatal) => fail(s"the node stopped: $fatal")
        case _                           => ()
      assertEquals(invalid, Set(target.hash))
      assertEquals(tip, forged.headers(index - 1).point)
      assert(!block.calls.contains(Verifier.Vrf), block.calls.toString)
    }
  }

  /** The real verifiers, except that the first `failures` OCERT checks of the target header meet
    * `defect`: a provider that fails and then succeeds. `calls` counts the target's OCERT checks.
    * The first `overlap` of them wait until all `overlap` are in the provider at once, so that
    * concurrent warmers are known to overlap.
    */
  private final class Fails(defect: ProviderDefect, failures: Int, overlap: Int = 0)
      extends Verdicts:
    export Verdicts.Direct.{keyHash, praosVrf, takePrepared, tpraosVrf, witness}
    private val counted = new java.util.concurrent.atomic.AtomicInteger(0)
    private val together = new java.util.concurrent.CountDownLatch(overlap)
    def calls: Int = counted.get
    def opCert(
        header: Header.Shelley,
        slotsPerKesPeriod: BigInt,
        maxKesEvolutions: BigInt
    ): Either[OCertError | ProviderDefect, Unit] =
      if header.hash == target.hash then
        val call = counted.getAndIncrement()
        if call < overlap then
          together.countDown()
          together.await(10, java.util.concurrent.TimeUnit.SECONDS): Unit
        if call < failures then Left(defect)
        else Verdicts.Direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions)
      else Verdicts.Direct.opCert(header, slotsPerKesPeriod, maxKesEvolutions)

  /** The forged chain's blocks warmed through `cache` by `warmers` concurrent warmings. */
  private def warmed(cache: VerificationCache, warmers: Int): IO[Unit] =
    Vector
      .fill(warmers)(cache.warm[IO](forged.raws, ForgedChain.config, 2))
      .parSequence_

  test("a defect met while warming is discarded: validation asks again and adopts the block") {
    val provider = Fails(kesDefect, failures = 1)
    val cache = VerificationCache.unsafe(2, provider)
    val run = warmed(cache, 1).flatMap(_ => session(cache)).guarantee(IO(cache.shutdown()))
    run.map { (end, invalid, _, tip) =>
      FailurePolicy.disposition(end) match
        case Disposition.StopNode(fatal) => fail(s"the node stopped: $fatal")
        case _                           => ()
      assertEquals(invalid, Set.empty[Bytes])
      assertEquals(tip, forged.headers.last.point)
      // the warming met the defect; the validation asked itself and got its own answer
      assertEquals(provider.calls, 2)
    }
  }

  test("a defect at validation time withholds the block, whatever warming met") {
    val provider = Fails(kesDefect, failures = Int.MaxValue)
    val cache = VerificationCache.unsafe(2, provider)
    val run = warmed(cache, 1).flatMap(_ => session(cache)).guarantee(IO(cache.shutdown()))
    run.map { (end, invalid, events, tip) =>
      val held = VerificationDefect(target.point, VerificationDefect.Site.Header, kesDefect)
      withheld(end, invalid, events, tip, held)
      assertEquals(provider.calls, 2)
    }
  }

  test("overlapping duplicate warmers never hide a defect at validation time") {
    val provider = Fails(kesDefect, failures = Int.MaxValue, overlap = 2)
    val cache = VerificationCache.unsafe(2, provider)
    val run = warmed(cache, 2).flatMap(_ => session(cache)).guarantee(IO(cache.shutdown()))
    run.map { (end, invalid, events, tip) =>
      val held = VerificationDefect(target.point, VerificationDefect.Site.Header, kesDefect)
      withheld(end, invalid, events, tip, held)
      // both warmers asked, overlapping; the validation asked a third time
      assertEquals(provider.calls, 3)
    }
  }

  test("a defect met while warming headers is discarded: header-first validation asks again") {
    val provider = Fails(kesDefect, failures = 1)
    val cache = VerificationCache.unsafe(2, provider)
    cache
      .warmHeaders[IO](Vector(target), forged.states(index - 1).protocol, ForgedChain.config)
      .map { _ =>
        assert(validate(cache.forHeaders, target).isInstanceOf[Outcome.Valid[?]])
        assertEquals(provider.calls, 2)
      }
      .guarantee(IO(cache.shutdown()))
  }

  test("negative control: the real verifier still rejects an invalid proof as a typed failure") {
    val bad = target.vrf match
      case HeaderVrf.Praos(c) =>
        val proof = c.proof.toArray
        proof(3) = (proof(3) ^ 1).toByte
        target.copy(vrf = HeaderVrf.Praos(c.copy(proof = Bytes.fromArray(proof))))
      case other => fail(s"unexpected $other")
    validate(Verdicts.Direct, bad) match
      case Outcome.Invalid(_) => ()
      case other              => fail(s"unexpected $other")
    assert(validate(Verdicts.Direct, target).isInstanceOf[Outcome.Valid[?]])
  }

  test("negative control: a session over the real verifiers adopts the whole forged chain") {
    session(Verdicts.Direct).map { (end, invalid, _, tip) =>
      assertEquals(end, SessionEnd.Stopped(false))
      assertEquals(invalid, Set.empty[Bytes])
      assertEquals(tip, forged.headers.last.point)
    }
  }

  /** A follower session over the real block rules of the forged chain, with `verdicts` as the
    * crypto providers, against a peer that announces every forged header, whose blocks the node's
    * selection worker adopts: how it ended (`Stopped(false)`: still running once the worker decided
    * every block), what the invalid-block registry holds, the follower's events and the tip it
    * reached.
    */
  private def session(
      verdicts: Verdicts
  ): IO[(SessionEnd, Set[Bytes], Vector[FollowerEvent], ChainSync.Point)] =
    NodeScope.resource.use { node =>
      for
        ref <- Ref.of[IO, VolatileChain[MirrorState]](
          VolatileChain.genesis(ForgedChain.geometry.k.toInt, forged.genesis)
        )
        given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(ref)
        emitted <- Ref.of[IO, Vector[FollowerEvent]](Vector.empty)
        follower <- Follower.create[IO, MirrorState](
          blockRules(verdicts),
          event => emitted.update(_ :+ event),
          ForgedChain.geometry
        )
        pool <- WorkerTesting.pool(follower)
        queue <- Queue.unbounded[IO, SyncEvent]
        _ <- forged.headers.traverse_(h =>
          queue.offer(SyncEvent.RollForward(h, ChainSync.Tip.Origin))
        )
        done <- Deferred[IO, Unit]
        raws = forged.headers.map(_.hash).zip(forged.raws).toMap
        peer = new Peer[IO]:
          def intersect(candidates: Vector[ChainSync.Point])(using Raise[IO, PeerError]) =
            IO.pure(candidates.headOption)
          def next(using Raise[IO, PeerError]) = queue.tryTake.flatMap {
            case Some(event) => IO.pure(event)
            case None        => done.complete(()) *> IO.never[SyncEvent]
          }
          def fetch(header: Header)(using Raise[IO, PeerError]) = IO.pure(raws(header.hash))
        end <- SessionEnd
          .scoped(node) { (_, _) ?=>
            WorkerTesting.follow(follower, pool, peer, done.get).as(SessionEnd.Stopped(false))
          }
          .timeout(30.seconds)
        chain <- ref.get
        invalid <- pool.invalidBlocks.snapshot
        events <- emitted.get
      yield (end, invalid.entries.keySet, events, chain.tip)
    }
