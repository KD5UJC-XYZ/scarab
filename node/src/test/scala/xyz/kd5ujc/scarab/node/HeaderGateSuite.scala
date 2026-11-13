// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.annotation.unused
import xyz.kd5ujc.scarab.consensus.{BlockRef, Compute, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header, PeerError}
import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.network.genesis.{LeakyBucket, LimitOnPatience}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.{HeaderChain, HeaderRules}
import xyz.kd5ujc.scarab.ledger.HeaderError
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import scala.concurrent.duration.*

/** The header gate over a model rule set: the ledger state is the height of the node's chain and
  * the header state counts the headers validated, so what the gate does with ordering, rollbacks,
  * waiting and refusals shows in plain numbers.
  */
class HeaderGateSuite extends CatsEffectSuite with ScalaCheckSuite:
  private val tip = ChainSync.Tip.Origin

  /** A provider defect met while verifying a `defective` header. */
  private val providerDefect = xyz.kd5ujc.scarab.ledger.ProviderDefect.Kes(
    xyz.kd5ujc.scarab.crypto.kes.Sum6Kes.Failure.Defect(new IllegalStateException("provider broke"))
  )

  /** A header further than `horizon` blocks above the anchor must wait: as in the reference, the
    * rules run only on a forecast ledger view. Within it `bad` ones are invalid and `defective`
    * ones meet a provider defect.
    */
  private final class Model(
      bad: Set[Bytes],
      horizon: Int,
      defective: Set[Bytes] = Set.empty
  ) extends HeaderRules[BigInt, Int]:
    def at(state: BigInt): Int = state.toInt + 1
    def validate(
        anchor: BigInt,
        tipRef: Option[BlockRef],
        prev: Int,
        header: Header
    ): Outcome[Int] =
      if header.blockNo > anchor + horizon then
        Outcome.Later(
          xyz.kd5ujc.scarab.consensus.HeaderRules.ForecastReason.Horizon(header.slot, anchor)
        )
      else if defective(header.hash) then Outcome.Unverifiable(providerDefect)
      else if bad(header.hash) then Outcome.Invalid(badError)
      else Outcome.Valid(prev + 1)

  private val badError =
    HeaderError.Structure(xyz.kd5ujc.scarab.ledger.HeaderStructureProblem.EpochPrecedes(0, 1))

  /** What a gate did to the patience bucket, in order. */
  private enum Bucket:
    case Paused(reason: LimitOnPatience.PauseReason, paused: Boolean)
    case Validated(blockNo: BigInt)

  /** A patience bucket that only records what it is told. */
  private def recording(trace: Ref[IO, Vector[Bucket]]): LimitOnPatience[IO] =
    new LimitOnPatience[IO]:
      def setPaused(reason: LimitOnPatience.PauseReason, paused: Boolean): IO[Unit] =
        trace.update(_ :+ Bucket.Paused(reason, paused))
      def validated(blockNo: BigInt): IO[Unit] = trace.update(_ :+ Bucket.Validated(blockNo))
      def jumpedTo(blockNo: BigInt): IO[Unit] = IO.unit
      def configure(config: LeakyBucket.Config): IO[Unit] = IO.unit
      def snapshot: IO[LeakyBucket.State] =
        IO.pure(LeakyBucket.State(Rational.zero, 0, false, 0, LeakyBucket.Config.disabled))

  @unused private def ref(h: Header) = BlockRef(h.slot, h.hash, h.blockNo)

  private final class Rig(
      val chain: Ref[IO, VolatileChain[BigInt]],
      val gate: HeaderGate[IO],
      val seen: Ref[IO, Vector[HeaderGate.Counts]]
  ):
    def apply(headers: Vector[Header]): IO[Unit] =
      headers.traverse_(h => chain.update(c => c.extend(h, h.blockNo).toOption.get._1))

  private def rig(
      bad: Set[Bytes] = Set.empty,
      horizon: Int = 1000,
      applied: Vector[Header] = Vector.empty,
      defective: Set[Bytes] = Set.empty
  )(using Handle[IO, PeerError]): IO[Rig] =
    Ref
      .of[IO, VolatileChain[BigInt]](VolatileChain.genesis(50, BigInt(-1)))
      .flatMap(chain => rigOn(chain, horizon, bad, applied, defective))

  /** [[rig]] over the node's chain `chain`, which a test can advance from outside a `TestControl`
    * run; with `patience`, the gate's bucket.
    */
  private def rigOn(
      chain: Ref[IO, VolatileChain[BigInt]],
      horizon: Int,
      bad: Set[Bytes] = Set.empty,
      applied: Vector[Header] = Vector.empty,
      defective: Set[Bytes] = Set.empty,
      patience: Option[LimitOnPatience[IO]] = None
  )(using Handle[IO, PeerError]): IO[Rig] =
    given Stateful[IO, VolatileChain[BigInt]] = RefStateful(chain)
    for
      seen <- Ref.of[IO, Vector[HeaderGate.Counts]](Vector.empty)
      check = HeaderCheck.forChain[IO, BigInt, Int](
        new Model(bad, horizon, defective),
        Compute.inline[IO],
        poll = 10.millis,
        observe = Some(c => seen.update(_ :+ c)),
        patience = patience
      )
      _ <- applied.traverse_(h => chain.update(c => c.extend(h, h.blockNo).toOption.get._1))
      start <- chain.get.map(_.tip)
      gate <- check.start(start)
    yield new Rig(chain, gate, seen)

  private def forward(h: Header): SyncEvent = SyncEvent.RollForward(h, tip)
  private def backward(p: ChainSync.Point): SyncEvent = SyncEvent.RollBackward(p, tip)

  private def typed[A](program: Handle[IO, PeerError] ?=> IO[A]): IO[Either[PeerError, A]] =
    Handle.allow[PeerError](program.map(_.asRight[PeerError])).rescue(e => IO.pure(e.asLeft[A]))

  test("headers that pass are counted and every batch is observed") {
    val main = Fixtures.chain(10)
    typed {
      for
        r <- rig()
        _ <- r.gate.accept(main.take(4).map(forward))
        _ <- r.gate.accept(main.drop(4).map(forward))
        counts <- r.gate.counts
        seen <- r.seen.get
      yield (counts, seen.map(_.validated))
    }.map(result => assertEquals(result, Right((HeaderGate.Counts(10, 0, 0), Vector(4L, 10L)))))
  }

  test("each batch reports what it added, so sessions that each count from zero add up") {
    val main = Fixtures.chain(10)
    typed {
      for
        chain <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(50, BigInt(-1)))
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(chain)
        added <- Ref.of[IO, Vector[HeaderGate.Counts]](Vector.empty)
        check = HeaderCheck.forChain[IO, BigInt, Int](
          new Model(Set.empty, 1000, Set.empty),
          Compute.inline[IO],
          added = Some(c => added.update(_ :+ c))
        )
        start <- chain.get.map(_.tip)
        first <- check.start(start)
        second <- check.start(start)
        _ <- first.accept(main.take(4).map(forward))
        _ <- second.accept(main.take(3).map(forward))
        _ <- first.accept(main.slice(4, 10).map(forward))
        seen <- added.get
      yield seen
    }.map(result =>
      assertEquals(
        result,
        Right(
          Vector(HeaderGate.Counts(4, 0, 0), HeaderGate.Counts(3, 0, 0), HeaderGate.Counts(6, 0, 0))
        )
      )
    )
  }

  test("an invalid header raises InvalidHeader naming it; the headers before it were accepted") {
    val main = Fixtures.chain(8)
    typed {
      for
        r <- rig(bad = Set(main(5).hash))
        _ <- r.gate.accept(main.map(forward))
      yield ()
    }.map(result =>
      assertEquals(
        result,
        Left(
          PeerError.InvalidHeader(
            main(5).point,
            HeaderError.Structure(
              xyz.kd5ujc.scarab.ledger.HeaderStructureProblem.EpochPrecedes(0, 1)
            )
          )
        )
      )
    )
  }

  test("a provider defect on a header disconnects the peer: no record, reconnect allowed (393)") {
    val main = Fixtures.chain(8)
    typed {
      for
        r <- rig(defective = Set(main(5).hash))
        _ <- r.gate.accept(main.map(forward))
      yield ()
    }.map { result =>
      val held = xyz.kd5ujc.scarab.ledger.VerificationDefect(
        main(5).point,
        xyz.kd5ujc.scarab.ledger.VerificationDefect.Site.Header,
        providerDefect
      )
      assertEquals(result, Left(PeerError.Unverifiable(held)))
      val end = SessionEnd.of(PeerError.Unverifiable(held))
      assertEquals(end, SessionEnd.Failed(PeerError.Unverifiable(held)))
      assertEquals(
        FailurePolicy.classify(PeerError.Unverifiable(held)),
        xyz.kd5ujc.scarab.network.selection.FailureClass.NotThePeers
      )
      assertEquals(FailurePolicy.disposition(end), Disposition.Backoff)
    }
  }

  test("a header that does not extend the candidate chain is a protocol error") {
    val main = Fixtures.chain(4)
    val stray = Fixtures.chain(3, salt = 2).last
    typed {
      for
        r <- rig()
        _ <- r.gate.accept((main :+ stray).map(forward))
      yield ()
    }.map {
      case Left(PeerError.Protocol(_)) => ()
      case other                       => fail(other.toString)
    }
  }

  test("a rollback inside the candidate chain continues from there") {
    val main = Fixtures.chain(8)
    val fork = Fixtures.chain(3, salt = 1, from = Some(main(4)))
    typed {
      for
        r <- rig()
        _ <- r.gate.accept(
          main.map(forward) ++ Vector(backward(main(4).point)) ++ fork.map(forward)
        )
        counts <- r.gate.counts
      yield counts
    }.map(result => assertEquals(result, Right(HeaderGate.Counts(11, 0, 0))))
  }

  test("a rollback to a block of our own chain before the join point re-bases the candidate") {
    val shared = Fixtures.chain(6)
    val branch = Fixtures.chain(3, salt = 1, from = Some(shared(2)))
    typed {
      for
        r <- rig(applied = shared)
        _ <- r.gate.accept(Vector(backward(shared(2).point)) ++ branch.map(forward))
        counts <- r.gate.counts
      yield counts
    }.map(result => assertEquals(result, Right(HeaderGate.Counts(3, 0, 0))))
  }

  test("a header beyond the horizon waits for earlier batches to be applied, then is validated") {
    val main = Fixtures.chain(16)
    TestControl.executeEmbed(
      typed {
        for
          r <- rig(horizon = 8)
          // the first batch is within the horizon of the empty chain and released
          _ <- r.gate.accept(main.take(8).map(forward))
          fiber <- r.gate.accept(main.drop(8).map(forward)).start
          // the node applies the released batch a little at a time; each step moves the horizon
          _ <- (0 until 2).toList.traverse_ { i =>
            IO.sleep(200.millis) *> r(main.slice(i * 4, i * 4 + 4))
          }
          _ <- fiber.joinWithNever
          counts <- r.gate.counts
        yield counts
      }.map {
        case Right(c) =>
          assertEquals((c.validated, c.unchecked), (16L, 0L))
          assert(c.waited > 0, c.toString)
        case other => fail(other.toString)
      }
    )
  }

  private def emptyChain: IO[Ref[IO, VolatileChain[BigInt]]] =
    Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(50, BigInt(-1)))

  /** The node's chain with the blocks of `headers` applied. */
  private def applying(headers: Vector[Header])(
      chain: VolatileChain[BigInt]
  ): VolatileChain[BigInt] =
    headers.foldLeft(chain)((c, h) => c.extend(h, h.blockNo).toOption.get._1)

  /** What a `TestControl` run has returned so far: `None` while it still runs. */
  private def finished[A](control: TestControl[A]): IO[Option[A]] =
    control.results.flatMap {
      case None                                          => IO.pure(None)
      case Some(cats.effect.kernel.Outcome.Succeeded(a)) => IO.pure(Some(a))
      case Some(other) => IO.raiseError(new AssertionError(other.toString))
    }

  // Issue 879. Past the forecast horizon the reference's ChainSync client waits until our chain and
  // the intersection have advanced (ouroboros-consensus 82ecba329, ChainSync/Client.hs 1861-1878);
  // it never takes the header unchecked, however long the chain stands still and whether or not
  // anything is left in flight. Virtual time runs for minutes between the test's own changes of
  // the node's chain.
  test(
    "a header beyond the horizon is not taken while our chain stands still; it is validated once the chain advances"
  ) {
    val main = Fixtures.chain(20)
    for
      chain <- emptyChain
      control <- TestControl.execute(typed {
        for
          r <- rigOn(chain, horizon = 5)
          // within the horizon of the empty chain: released for their blocks
          _ <- r.gate.accept(main.take(5).map(forward))
          _ <- r.gate.accept(main.drop(5).map(forward))
          counts <- r.gate.counts
        yield counts
      })
      // the released blocks are not applied: the chain stands still
      _ <- control.tickFor(10.minutes)
      standing <- finished(control)
      // they are applied: nothing of the earlier batch is left in flight, and 10 is still beyond
      _ <- chain.update(applying(main.take(5)))
      _ <- control.tickFor(10.minutes)
      drained <- finished(control)
      _ <- chain.update(applying(main.slice(5, 15)))
      _ <- control.tickFor(1.second)
      done <- finished(control)
    yield
      assertEquals(standing, None)
      assertEquals(drained, None)
      done match
        case Some(Right(c)) =>
          assertEquals((c.validated, c.unchecked), (20L, 0L))
          assert(c.waited >= 2, c.toString)
        case other => fail(other.toString)
  }

  // A batch's blocks are requested only once `accept` returns for it (`Peer.acceptUpdates`): a
  // header refused while it waits is refused as a header, and no block of its batch is fetched.
  test(
    "an invalid header beyond the horizon is refused as a header once validated; its batch is never released"
  ) {
    val main = Fixtures.chain(12)
    for
      chain <- emptyChain
      released <- Ref.of[IO, Int](0)
      control <- TestControl.execute(typed {
        for
          r <- rigOn(chain, bad = Set(main(8).hash), horizon = 5)
          _ <- r.gate.accept(main.take(5).map(forward)) *> released.update(_ + 1)
          _ <- r.gate.accept(main.drop(5).map(forward)) *> released.update(_ + 1)
        yield ()
      })
      _ <- control.tickFor(10.minutes)
      waiting <- finished(control)
      before <- released.get
      _ <- chain.update(applying(main.take(5)))
      _ <- control.tickFor(1.second)
      done <- finished(control)
      after <- released.get
    yield
      assertEquals((waiting, before), (None, 1))
      assertEquals(done, Some(Left(PeerError.InvalidHeader(main(8).point, badError))))
      assertEquals(after, 1)
  }

  // `checkTime` pauses the Limit-on-Patience bucket for the whole wait (Client.hs 1719-1733,
  // `pauseBucket` 1880-1889): once, however often the gate looks again, and resumed before the
  // header's token is granted.
  test("the patience bucket is paused once for the whole wait beyond the horizon") {
    val main = Fixtures.chain(8)
    val forecast = LimitOnPatience.PauseReason.Forecast
    for
      chain <- emptyChain
      trace <- Ref.of[IO, Vector[Bucket]](Vector.empty)
      control <- TestControl.execute(typed {
        for
          r <- rigOn(chain, horizon = 5, patience = Some(recording(trace)))
          _ <- r.gate.accept(main.take(5).map(forward))
          _ <- r.gate.accept(main.drop(5).map(forward))
        yield ()
      })
      _ <- control.tickFor(10.minutes)
      during <- trace.get
      _ <- chain.update(applying(main.take(5)))
      _ <- control.tickFor(1.second)
      done <- finished(control)
      after <- trace.get
    yield
      val first = main.take(5).map(h => Bucket.Validated(h.blockNo))
      assertEquals(during, first :+ Bucket.Paused(forecast, true))
      assertEquals(done, Some(Right(())))
      assertEquals(
        after,
        first ++ Vector(Bucket.Paused(forecast, true), Bucket.Paused(forecast, false)) ++
          main.drop(5).map(h => Bucket.Validated(h.blockNo))
      )
  }

  @unused private final case class Op(forward: Int, back: Int)

  property("the candidate chain is a model: extend, roll back and prune keep it a path") {
    forAll(Gen.choose(1, 40), Gen.choose(0, 40), Gen.choose(1, 12)) { (n, back, keep) =>
      val headers = Fixtures.chain(n)
      val base = HeaderChain.at[Int](ChainSync.Point.Origin, Some(0))
      val grown =
        headers.foldLeft(base)((c, h) => c.extend(h, Some(c.entries.size + 1)).toOption.get)
      val target = headers.lift(n - 1 - back.min(n - 1)).map(_.point)
      val rolled = target.flatMap(grown.rollback)
      val pruned = grown.pruned(keep)
      grown.tipHash == headers.lastOption.map(_.hash) &&
      rolled.forall(r => r.tipPoint == target.get && r.entries.size == n - back.min(n - 1)) &&
      pruned.entries.size <= keep &&
      pruned.tipHash == grown.tipHash &&
      pruned.entries == headers
        .takeRight(keep.min(n))
        .zip(grown.entries.takeRight(keep.min(n)).map(_.state))
        .map((h, s) => HeaderChain.Entry(h, s)) &&
      (n == 0 || pruned.stateAfter(headers.last.hash).contains(Some(n)))
    }
  }

  test(
    "a header that does not extend the chain is refused; the first one names the chain's origin"
  ) {
    val main = Fixtures.chain(3)
    val c = HeaderChain.at[Int](ChainSync.Point.Origin, Some(0))
    // the first header of a chain from Origin may name any parent (Byron genesis hash), as in Follower
    val first =
      main(0).asInstanceOf[Header.Shelley].copy(prevHash = Some(Bytes(Vector.fill(32)(9.toByte))))
    assert(c.extend(first, Some(1)).isRight)
    val one = c.extend(main(0), Some(1)).toOption.get
    assert(one.extend(main(2), Some(2)).isLeft)
    assert(one.extend(main(1), Some(2)).isRight)
  }
