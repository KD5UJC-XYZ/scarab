// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.annotation.unused
import xyz.kd5ujc.scarab.consensus.{BlockRef, Compute, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header, PeerError}
import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
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

  /** A header further than `horizon` blocks above the anchor must wait; `bad` ones are invalid. */
  private final class Model(bad: Set[Bytes], horizon: Int, unchecked: Set[Bytes] = Set.empty)
      extends HeaderRules[BigInt, Int]:
    def at(state: BigInt): Int = state.toInt + 1
    def validate(
        anchor: BigInt,
        tipRef: Option[BlockRef],
        prev: Int,
        header: Header
    ): Outcome[Int] =
      if bad(header.hash) then Outcome.Invalid(HeaderError.Structure("model"))
      else if unchecked(header.hash) then Outcome.Unchecked("model: left to the block", None)
      else if header.blockNo > anchor + horizon then Outcome.Later("beyond the horizon")
      else Outcome.Valid(prev + 1)

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
      unchecked: Set[Bytes] = Set.empty,
      horizon: Int = 1000,
      applied: Vector[Header] = Vector.empty,
      stall: FiniteDuration = 5.seconds
  )(using Handle[IO, PeerError]): IO[Rig] =
    for
      chain <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(50, BigInt(-1)))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(chain)
      seen <- Ref.of[IO, Vector[HeaderGate.Counts]](Vector.empty)
      check = HeaderCheck.forChain[IO, BigInt, Int](
        new Model(bad, horizon, unchecked),
        Compute.inline[IO],
        stall = stall,
        poll = 10.millis,
        observe = Some(c => seen.update(_ :+ c))
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
        Left(PeerError.InvalidHeader(main(5).point, HeaderError.Structure("model")))
      )
    )
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

  test(
    "with nothing of earlier batches left to apply, a header beyond the horizon is left to its block"
  ) {
    val main = Fixtures.chain(20)
    typed {
      for
        r <- rig(horizon = 5)
        _ <- r.gate.accept(main.map(forward))
        counts <- r.gate.counts
      yield counts
    }.map {
      // 0 to 4 are within the horizon of the empty chain; the rest could only wait for their own batch
      case Right(c) => assertEquals((c.validated, c.unchecked), (5L, 15L))
      case other    => fail(other.toString)
    }
  }

  test("a header left to its block does not make the rest of its batch wait for it") {
    val main = Fixtures.chain(10)
    // like the first Shelley header after a Byron prefix: no header state follows it
    typed {
      for
        r <- rig(unchecked = Set(main(3).hash))
        _ <- r.gate.accept(main.map(forward))
        counts <- r.gate.counts
      yield counts
    }.map {
      case Right(c) => assertEquals((c.validated, c.unchecked, c.waited), (3L, 7L, 0L))
      case other    => fail(other.toString)
    }
  }

  test("after the batch with a header left to its block is applied, the next batch is validated") {
    val main = Fixtures.chain(12)
    TestControl.executeEmbed(
      typed {
        for
          r <- rig(unchecked = Set(main(3).hash))
          _ <- r.gate.accept(main.take(6).map(forward))
          next <- r.gate.accept(main.drop(6).map(forward)).start
          // the first batch's blocks are applied; the gate then takes the header state from the chain
          _ <- IO.sleep(300.millis) *> r(main.take(6))
          _ <- next.joinWithNever
          counts <- r.gate.counts
        yield counts
      }.map {
        case Right(c) =>
          assertEquals((c.validated, c.unchecked), (9L, 3L))
          assert(c.waited >= 1, c.toString)
        case other => fail(other.toString)
      }
    )
  }

  test("a chain that stands still while a header waits is not a reason to drop the peer") {
    val main = Fixtures.chain(20)
    TestControl.executeEmbed(
      typed {
        for
          r <- rig(horizon = 5, stall = 3.seconds)
          // 5 are released; the node never applies them, so the next header can only wait
          _ <- r.gate.accept(main.take(5).map(forward))
          _ <- r.gate.accept(main.drop(5).map(forward))
          counts <- r.gate.counts
        yield counts
      }.map {
        case Right(c) => assertEquals((c.validated, c.unchecked), (5L, 15L))
        case other    => fail(other.toString)
      }
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
