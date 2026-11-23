// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.Id
import cats.syntax.all.*
import fs2.Stream
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.SharedAnswers.{Ending, Entry, Step, Table}

/** [[SharedAnswers]], the one-at-a-time builds of `GET /state` (#1150): the table against a model,
  * and the concurrent requests under `TestControl`, where `tick` runs every fiber until each waits.
  */
class SharedAnswersSuite extends FailsOnUncaught with ScalaCheckSuite:
  import SharedAnswersSuite.*

  private val keys: Gen[Key] = for
    point <- Gen.choose(0, 2)
    view <- Gen.choose(0, 1)
  yield Key(point, view)

  private val sequential: Gen[OneAtATime] =
    Gen.frequency(4 -> keys.map(OneAtATime.Ask(_)), 1 -> Gen.choose(0, 2).map(OneAtATime.Move(_)))

  property("asked one at a time, a key is built again only after its kept answer was dropped") {
    forAll(Gen.listOf(sequential)) { ops =>
      final case class Run(
          table: Table[Int, Key, Int, String],
          answers: Vector[String],
          builds: Int
      )
      final case class Model(
          current: Option[Int],
          kept: Map[Key, String],
          answers: Vector[String],
          builds: Int
      )
      val run = ops.foldLeft(Run(Table.empty, Vector.empty, 0)) { (r, op) =>
        op match
          case OneAtATime.Move(p) => r.copy(table = r.table.moved(p, _.point))
          case OneAtATime.Ask(key) =>
            r.table.ask(key, r.builds) match
              case (t, Step.Kept(a)) => Run(t, r.answers :+ a, r.builds)
              case (t, Step.Build) =>
                val a = s"$key#${r.builds}"
                Run(t.settle(key, Ending.Answered(a), _.point), r.answers :+ a, r.builds + 1)
              case (t, Step.Wait(_)) => Run(t, r.answers :+ "waited", r.builds)
      }
      val model = ops.foldLeft(Model(None, Map.empty, Vector.empty, 0)) { (m, op) =>
        op match
          case OneAtATime.Move(p) =>
            m.copy(current = Some(p), kept = m.kept.filter(_._1.point == p))
          case OneAtATime.Ask(key) =>
            m.kept.get(key) match
              case Some(a) => m.copy(answers = m.answers :+ a)
              case None =>
                val a = s"$key#${m.builds}"
                val kept = if m.current.contains(key.point) then m.kept.updated(key, a) else m.kept
                Model(m.current, kept, m.answers :+ a, m.builds + 1)
      }
      val built = run.table.entries.collect { case (k, Entry.Built(a)) => k -> a }
      (run.answers ?= model.answers) &&
      (run.builds ?= model.builds) &&
      (built ?= model.kept) &&
      (run.table.entries.size ?= built.size) :| "no build is left under way"
    }
  }

  property("with builds under way, each key has one build at most and the waiters wait on it") {
    val ops: Gen[Op] = Gen.frequency(
      4 -> keys.map(Op.Ask(_)),
      3 -> Gen.choose(0, 4).flatMap(i => Gen.choose(0, 2).map(Op.End(i, _))),
      1 -> Gen.choose(0, 2).map(Op.Move(_))
    )
    forAll(Gen.listOf(ops)) { ops =>
      // the builds under way, by the id their entry holds
      final case class Run(
          table: Table[Int, Key, Int, String],
          underWay: Vector[(Int, Key)],
          next: Int,
          checks: Vector[Boolean]
      )
      val end = ops.foldLeft(Run(Table.empty, Vector.empty, 0, Vector.empty)) { (r, op) =>
        val next = op match
          case Op.Move(p) => r.copy(table = r.table.moved(p, _.point))
          case Op.Ask(key) =>
            r.table.ask(key, r.next) match
              case (t, Step.Build) =>
                Run(t, r.underWay :+ (r.next -> key), r.next + 1, r.checks :+ true)
              case (t, Step.Wait(id)) =>
                r.copy(table = t, checks = r.checks :+ r.underWay.contains(id -> key))
              case (t, Step.Kept(_)) =>
                r.copy(table = t, checks = r.checks :+ !r.underWay.exists(_._2 == key))
          case Op.End(i, how) =>
            r.underWay.lift(i).fold(r) { (id, key) =>
              val ending = how match
                case 0 => Ending.Answered(s"$key#$id")
                case 1 => Ending.Failed(Boom)
                case _ => Ending.Abandoned
              r.copy(
                table = r.table.settle(key, ending, _.point),
                underWay = r.underWay.patch(i, Nil, 1)
              )
            }
        val building = next.table.entries.collect { case (k, Entry.Building(id)) => id -> k }.toSet
        val builtAtCurrent = next.table.entries.forall {
          case (k, Entry.Built(_)) => next.table.current.contains(k.point)
          case _                   => true
        }
        next.copy(checks = next.checks :+ (building == next.underWay.toSet) :+ builtAtCurrent)
      }
      end.checks.forall(identity) :| "a check failed"
    }
  }

  test("requests for a key whose answer is being built wait for that build and share its answer") {
    for
      gate <- Deferred[IO, Unit]
      builds <- Ref.of[IO, Int](0)
      build = builds.updateAndGet(_ + 1).flatMap(n => gate.get.as(s"answer $n"))
      // the key's point is never the current one: nothing is kept, so a request that came after the
      // build would build again
      control <- TestControl.execute(
        SharedAnswers.of[IO, Int, Key, String](_.point).flatMap { answers =>
          (
            answers.answer(Key(1, 0))(build),
            answers.answer(Key(1, 0))(build),
            answers.answer(Key(1, 0))(build)
          ).parTupled
        }
      )
      _ <- control.tick
      underWay <- builds.get
      _ <- gate.complete(())
      _ <- control.tick
      results <- control.results
      total <- builds.get
    yield
      assertEquals(underWay, 1)
      assertEquals(results, succeeded((("answer 1", "answer 1", "answer 1"))))
      assertEquals(total, 1)
  }

  test("a build waits until the build under way, of another key, has ended") {
    for
      gates <- (Deferred[IO, Unit], Deferred[IO, Unit]).tupled
      started <- Ref.of[IO, Vector[Key]](Vector.empty)
      running <- Ref.of[IO, Int](0)
      most <- Ref.of[IO, Int](0)
      build = (key: Key, gate: Deferred[IO, Unit]) =>
        started.update(_ :+ key) *>
          running.updateAndGet(_ + 1).flatMap(n => most.update(_.max(n))) *>
          gate.get *> running.update(_ - 1).as(key.toString)
      control <- TestControl.execute(
        SharedAnswers.of[IO, Int, Key, String](_.point).flatMap { answers =>
          (
            answers.answer(Key(1, 0))(build(Key(1, 0), gates._1)),
            answers.answer(Key(2, 0))(build(Key(2, 0), gates._2))
          ).parTupled
        }
      )
      _ <- control.tick
      first <- started.get
      _ <- (if first == Vector(Key(1, 0)) then gates._1 else gates._2).complete(())
      _ <- control.tick
      both <- started.get
      _ <- gates._1.complete(()) *> gates._2.complete(())
      _ <- control.tick
      results <- control.results
      overlapping <- most.get
    yield
      assertEquals(first.size, 1, "one build at a time")
      assertEquals(both.toSet, Set(Key(1, 0), Key(2, 0)), "the other built once the first ended")
      assertEquals(overlapping, 1)
      assertEquals(results, succeeded(((Key(1, 0).toString, Key(2, 0).toString))))
  }

  test("when the request that builds is cancelled, a request that waited builds the answer") {
    for
      gate <- Deferred[IO, Unit]
      stop <- Deferred[IO, Unit]
      began <- Queue.unbounded[IO, Unit]
      builds <- Ref.of[IO, Int](0)
      build = began.offer(()) *> builds.updateAndGet(_ + 1).flatMap(n => gate.get.as(s"answer $n"))
      control <- TestControl.execute(
        SharedAnswers.of[IO, Int, Key, String](_.point).flatMap { answers =>
          // each worker is a resource, so it is cancelled however the program ends (TST-5)
          IO.race(stop.get, answers.answer(Key(1, 0))(build)).background.use { first =>
            began.take *> answers.answer(Key(1, 0))(build).background.use { second =>
              (first.flatMap(_.embedNever), second.flatMap(_.embedNever)).tupled
            }
          }
        }
      )
      _ <- control.tick
      _ <- stop.complete(())
      _ <- control.tick
      rebuilt <- builds.get
      _ <- gate.complete(())
      _ <- control.tick
      results <- control.results
    yield
      assertEquals(rebuilt, 2, "the waiting request built it again")
      assertEquals(results, succeeded[(Either[Unit, String], String)]((Left(()), "answer 2")))
  }

  test("a build that fails fails the requests that waited for it, and is not kept") {
    for
      gate <- Deferred[IO, Unit]
      builds <- Ref.of[IO, Int](0)
      failing = builds.update(_ + 1) *> gate.get *> IO.raiseError[String](Boom)
      control <- TestControl.execute(
        SharedAnswers.of[IO, Int, Key, String](_.point).flatMap { answers =>
          answers.moved(1) *>
            (
              answers.answer(Key(1, 0))(failing).attempt,
              answers.answer(Key(1, 0))(failing).attempt
            ).parTupled.flatMap(failed =>
              answers.answer(Key(1, 0))(builds.update(_ + 1).as("built")).tupleLeft(failed)
            )
        }
      )
      _ <- control.tick
      _ <- gate.complete(())
      _ <- control.tick
      results <- control.results
      total <- builds.get
    yield
      assertEquals(
        results,
        succeeded[((Either[Throwable, String], Either[Throwable, String]), String)](
          ((Left(Boom), Left(Boom)), "built")
        )
      )
      assertEquals(total, 2)
  }

  test("the answer at the current point is kept until the point moves") {
    for
      builds <- Ref.of[IO, Int](0)
      build = (key: Key) => builds.updateAndGet(_ + 1).map(n => s"$key#$n")
      answers <- SharedAnswers.of[IO, Int, Key, String](_.point)
      before <- answers.answer(Key(1, 0))(build(Key(1, 0)))
      _ <- answers.moved(1)
      kept <- answers.answer(Key(1, 0))(build(Key(1, 0)))
      again <- answers.answer(Key(1, 0))(build(Key(1, 0)))
      other <- answers.answer(Key(1, 1))(build(Key(1, 1)))
      _ <- answers.moved(2)
      dropped <- answers.answer(Key(1, 0))(build(Key(1, 0)))
      total <- builds.get
    yield
      assertEquals(before, "Key(1,0)#1", "no point is current before the first move: not kept")
      assertEquals(kept, "Key(1,0)#2")
      assertEquals(again, kept)
      assertEquals(other, "Key(1,1)#3", "the other view of the point is an answer of its own")
      assertEquals(dropped, "Key(1,0)#4", "dropped when the point moved")
      assertEquals(total, 4)
  }

  test("following a stream of points keeps the answers at the last one") {
    for
      seen <- Deferred[IO, Unit]
      builds <- Ref.of[IO, Int](0)
      build = builds.updateAndGet(_ + 1).map(n => s"answer $n")
      points = Stream.emit(1) ++ Stream.exec(seen.complete(()).void) ++ Stream.never[IO]
      answered <- SharedAnswers
        .following[IO, Int, Key, String](points, _.point, IO.raiseError)
        .use(answers =>
          seen.get *> (answers.answer(Key(1, 0))(build), answers.answer(Key(1, 0))(build)).tupled
        )
      total <- builds.get
    yield
      assertEquals(answered, ("answer 1", "answer 1"))
      assertEquals(total, 1)
  }

object SharedAnswersSuite:
  def succeeded[A](a: A): Option[Outcome[Id, Throwable, A]] = Some(
    Outcome.succeeded[Id, Throwable, A](a)
  )

  final case class Key(point: Int, view: Int)

  /** A request, answered before the next step, or a move of the current point. */
  enum OneAtATime:
    case Ask(key: Key)
    case Move(point: Int)

  /** A request, the end of the build under way at `index` (answered, failed or abandoned by `how`),
    * or a move of the current point.
    */
  enum Op:
    case Ask(key: Key)
    case End(index: Int, how: Int)
    case Move(point: Int)

  case object Boom extends RuntimeException("boom")
