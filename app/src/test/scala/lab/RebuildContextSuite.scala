// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.ledger.AdaPool
import lab.submission.*
import scala.compiletime.testing.typeCheckErrors

class RebuildContextSuite extends munit.FunSuite:
  private def get[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  private def withView(use: AdmissionView => IO[Unit]): IO[Unit] =
    SubmissionOwner.resource[IO](EphemeralStreamingFixture.runtime).use(_.current.flatMap(use))
  private def pin(p: StatePin, generation: BigInt, ledger: Bytes, environment: Bytes) =
    get(
      StatePin.checked(
        p.ownerId,
        generation,
        p.point,
        p.coherentStateId,
        ledger,
        environment,
        p.validationSlot,
        p.profileId
      )
    )
  private def moved(p: StatePin, profile: AdmissionProfile = AdmissionProfile.AdaVkey) =
    val previous = pin(p, p.generation + 1, p.ledgerStateId, p.environmentId)
    AdaPool.move(AdaPool.empty(previous, profile = profile), p, Set.empty, 0L)

  test("complete pin differences in generation, ledger and environment cannot pair") {
    withView { view =>
      IO {
        val p = view.pin
        val different = Bytes(Vector.fill(32)(9.toByte))
        Vector(
          pin(p, p.generation + 2, p.ledgerStateId, p.environmentId),
          pin(p, p.generation, different, p.environmentId),
          pin(p, p.generation, p.ledgerStateId, different)
        ).foreach { changed =>
          assertEquals(
            RebuildContext.checked(view, moved(changed)._2).left.toOption,
            Some(RebuildContext.Mismatch.PinMismatch)
          )
        }
      }
    }.unsafeToFuture()
  }
  test("matching pins cannot conceal mismatched profile or missing inputs") {
    withView { view =>
      IO {
        val work = moved(view.pin, AdmissionProfile.PlutusV3)._2
        assertEquals(
          RebuildContext.checked(view, work).left.toOption,
          Some(RebuildContext.Mismatch.ProfileMismatch)
        )
        assertEquals(
          RebuildContext.checked(null, work).left.toOption,
          Some(RebuildContext.Mismatch.MissingInput)
        )
        assertEquals(
          RebuildContext.checked(view, null).left.toOption,
          Some(RebuildContext.Mismatch.MissingInput)
        )
      }
    }.unsafeToFuture()
  }
  test("valid context preserves original references and rebuild completion") {
    withView { view =>
      IO {
        val (pool, work) = moved(view.pin)
        val context = get(RebuildContext.checked(view, work))
        assert(context.view eq view)
        assert(context.work eq work)
        val direct = AdaPool.finish(pool, AdaPool.revalidate(work, view.ledger), 0L)
        val bound = AdaPool.finish(pool, AdaPool.revalidate(context.work, context.view.ledger), 0L)
        assertEquals(bound._2, direct._2)
        assert(bound._2)
        assertEquals(bound._1.pin, direct._1.pin)
        assertEquals(bound._1.eligible(0L), direct._1.eligible(0L))
        assertEquals(bound._1.rebuilding, direct._1.rebuilding)
      }
    }.unsafeToFuture()
  }
  test("completed old work cannot clear newer work with equal pin and profile") {
    withView { view =>
      val old = get(RebuildContext.checked(view, moved(view.pin)._2))
      val same = get(RebuildContext.checked(view, old.work))
      val newer = get(RebuildContext.checked(view, moved(view.pin)._2))
      for
        pending <- Ref.of[IO, Option[RebuildContext]](Some(newer))
        _ <- pending.update(_.filterNot(_.sameWork(old)))
        retained <- pending.get
        _ = assert(retained.exists(_ eq newer))
        _ = assert(same.sameWork(old))
        _ <- pending.update(_.filterNot(_.sameWork(newer)))
        cleared <- pending.get
        _ = assertEquals(cleared, None)
      yield ()
    }.unsafeToFuture()
  }
  test("unchecked view-work pairing cannot construct a context") {
    assert(
      typeCheckErrors(
        """import lab.RebuildContext; import lab.submission.*; import lab.ledger.AdaPool; def bad(v: AdmissionView,w: AdaPool.Rebuild[StatePin]) = new RebuildContext(v,w)"""
      ).nonEmpty
    )
  }
