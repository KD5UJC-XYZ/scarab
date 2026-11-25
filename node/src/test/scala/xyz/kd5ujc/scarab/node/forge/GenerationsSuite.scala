// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.IORuntimeConfig
import cats.syntax.all.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** The leases on the credentials in force (#1418), on numbered generations whose erasures are
  * recorded: a superseded generation is erased once its last lease ends and not before, at once if
  * none is held, exactly once; a lease is taken only on the generation in force; a lease ends with
  * its holder, whether it returns, fails or is cancelled; and an install never waits for a lease.
  */
class GenerationsSuite extends FailsOnUncaught:

  /** The generations of `first` on, and the numbers of those erased, in order. */
  private def numbered(first: Int): IO[(Generations[IO, Int], Ref[IO, Vector[Int]])] =
    (Generations.of[IO, Int](first), Ref.of[IO, Vector[Int]](Vector.empty)).tupled

  private def recorded(erased: Ref[IO, Vector[Int]]): Int => IO[Unit] = n => erased.update(_ :+ n)

  /** A holder of a lease on the generation in force (one must be): it holds the lease once the
    * scope is open, and ends with the generation it leased when `release` is run. The holder is
    * released, and cancelled if it has not ended, when the scope ends.
    */
  private def holder(g: Generations[IO, Int]): Resource[IO, (IO[Unit], IO[Option[Int]])] =
    for
      held <- Resource.eval(Deferred[IO, Unit])
      release <- Resource.make(Deferred[IO, Unit])(_.complete(()).void)
      ended <- g.leased(n => held.complete(()) *> release.get.as(n)).background
      _ <- Resource.eval(held.get)
    yield (release.complete(()).void, ended.flatMap(_.embedNever))

  test("a superseded generation is erased once its last lease ends, and not before") {
    numbered(1).flatMap { (g, erased) =>
      (holder(g), holder(g)).tupled.use { case ((releaseOne, one), (releaseTwo, two)) =>
        for
          _ <- g.install(Some(2))(recorded(erased))
          whileTwo <- erased.get
          _ <- releaseOne
          first <- one
          whileOne <- erased.get
          _ <- releaseTwo
          second <- two
          afterBoth <- erased.get
          now <- g.leased(IO.pure)
        yield
          assertEquals(whileTwo, Vector.empty, "erased while two leases were held on it")
          assertEquals(whileOne, Vector.empty, "erased while a lease was held on it")
          assertEquals(afterBoth, Vector(1), "erased once, when its last lease ended")
          assertEquals((first, second), (Some(1), Some(1)), "a lease keeps the generation it took")
          assertEquals(now, Some(2), "the generation installed is in force at once")
      }
    }
  }

  test("a generation no lease is held on is erased at once when it is superseded") {
    for
      made <- numbered(1)
      (g, erased) = made
      _ <- g.install(Some(2))(recorded(erased))
      replaced <- erased.get
      _ <- g.install(None)(recorded(erased))
      disabled <- erased.get
      ran <- Ref.of[IO, Boolean](false)
      leased <- g.leased(n => ran.set(true).as(n))
      used <- ran.get
      present <- g.present
    yield
      assertEquals(replaced, Vector(1))
      assertEquals(disabled, Vector(1, 2))
      assertEquals(leased, None, "a lease with no generation in force")
      assert(!used, "a lease with no generation in force ran its use")
      assert(!present)
  }

  test("a lease is taken only on the generation in force, never on a retired one") {
    numbered(1).flatMap { (g, erased) =>
      holder(g).use { (release, held) =>
        for
          _ <- g.install(Some(2))(recorded(erased))
          // the retired generation still leased, the next lease takes the one in force
          next <- g.leased(IO.pure)
          _ <- g.install(None)(recorded(erased))
          none <- g.leased(IO.pure)
          beforeRelease <- erased.get
          _ <- release
          kept <- held
          after <- erased.get
        yield
          assertEquals(next, Some(2))
          assertEquals(none, None)
          assertEquals(beforeRelease, Vector(2), "the generation leased by no one, at once")
          assertEquals(kept, Some(1))
          assertEquals(after, Vector(2, 1))
      }
    }
  }

  test("a lease ends with its holder when the holder is cancelled or fails") {
    for
      made <- numbered(1)
      (g, erased) = made
      held <- Deferred[IO, Unit]
      waiting <- g.leased(_ => held.complete(()) *> IO.never[Unit]).start
      _ <- held.get
      _ <- g.install(Some(2))(recorded(erased))
      whileHeld <- erased.get
      _ <- waiting.cancel
      cancelled <- waiting.join
      afterCancel <- erased.get
      failing <- Deferred[IO, Unit]
      go <- Deferred[IO, Unit]
      raising <- g
        .leased(_ =>
          failing.complete(()) *> go.get *> IO.raiseError[Unit](new IllegalStateException)
        )
        .start
      _ <- failing.get
      _ <- g.install(Some(3))(recorded(erased))
      _ <- go.complete(())
      failed <- raising.join
      afterFailure <- erased.get
    yield
      assertEquals(whileHeld, Vector.empty)
      assert(cancelled.isCanceled, cancelled.toString)
      assertEquals(afterCancel, Vector(1), "the cancelled holder's lease kept it")
      assert(failed.isError, failed.toString)
      assertEquals(afterFailure, Vector(1, 2), "the failed holder's lease kept it")
  }

  // The runtime checks for cancelation at every step and yields at every second one, so the seeded
  // schedules interleave the leases, their cancellations and the installs at any step.
  test(
    "under any interleaving every superseded generation is erased exactly once, after its last " +
      "lease, and no lease sees its generation erased"
  ) {
    val everyStep = IORuntimeConfig(1, 2)
    (1 to 48).toList.traverse_ { seed =>
      val program =
        for
          erased <- Ref.of[IO, Vector[Int]](Vector.empty)
          // the generations a lease saw erased while it held them: none may be
          seen <- Ref.of[IO, Vector[Int]](Vector.empty)
          g <- Generations.of[IO, Int](0)
          look = (n: Int) =>
            erased.get.flatMap(e => seen.update(_ ++ Option.when(e.contains(n))(n)))
          leases = (1 to 24).toList.parTraverse(i =>
            val lease = g.leased(n => look(n) *> look(n).as(n))
            // every third holder is cancelled wherever it is
            if i % 3 == 0 then lease.start.flatMap(f => f.cancel *> f.join).as(Option.empty[Int])
            else lease
          )
          installs = (1 to 6).toList.traverse_(n => g.install(Some(n))(recorded(erased)))
          ran <- (leases, installs).parTupled
          last <- g.leased(IO.pure)
          // the generation in force retired at the end, with no lease left on it
          _ <- g.install(None)(recorded(erased))
          all <- erased.get
          violations <- seen.get
        yield
          assertEquals(last, Some(6), s"seed $seed")
          assertEquals(all.sorted, (0 to 6).toVector, s"seed $seed: each erased once, $all")
          assertEquals(all.lastOption, Some(6), s"seed $seed: the one in force last")
          assertEquals(violations, Vector.empty, s"seed $seed: leases that saw theirs erased")
          assert(ran._1.flatten.forall(n => n >= 0 && n <= 6), s"seed $seed")
      val seeded = java.util.Base64.getEncoder.encodeToString(seed.toString.getBytes)
      TestControl.executeEmbed(program, everyStep, Some(seeded))
    }
  }

  test("an install does not wait for the leases on the generation it supersedes") {
    numbered(1).flatMap { (g, erased) =>
      holder(g).use { (release, held) =>
        // the install returns while the lease is held: were it to wait, this would never end
        g.install(Some(2))(recorded(erased)) *> g.leased(IO.pure).flatMap { now =>
          erased.get.flatMap { whileHeld =>
            release *> held.map { kept =>
              assertEquals(now, Some(2))
              assertEquals(whileHeld, Vector.empty)
              assertEquals(kept, Some(1))
            }
          }
        }
      }
    }
  }
