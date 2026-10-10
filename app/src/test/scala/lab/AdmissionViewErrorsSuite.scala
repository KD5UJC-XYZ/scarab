// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.submission.*
import SubmissionOwner.{ViewConstructionError as E, ViewConstructionFailure}
import scala.compiletime.testing.typeCheckErrors

class AdmissionViewErrorsSuite extends munit.FunSuite:
  private def withOwner[A](use: SubmissionOwner[IO] => IO[A]): IO[A] =
    SubmissionOwner.resource[IO](EphemeralStreamingFixture.runtime).use(use)

  test("actual owner uses the typed assembly with unchanged complete pin and ledger fields") {
    withOwner { owner =>
      for
        actual <- owner.current
        snapshot <- owner.snapshot
        pin = SubmissionOwner
          .pinFor(actual.pin.ownerId, actual.pin.generation, snapshot.state, owner.profile)
          .toOption
          .get
        typed = AdmissionView.checkedTyped(pin, snapshot.state.ledger).toOption.get
        legacy = AdmissionView.checked(pin, snapshot.state.ledger).toOption.get
        _ = assertEquals(pin, actual.pin)
        _ = assertEquals(pin.hashCode, actual.pin.hashCode)
        _ = assertEquals(typed.pin, legacy.pin)
        _ = assert(typed.ledger eq actual.ledger)
        _ = assert(legacy.ledger eq typed.ledger)
      yield ()
    }.unsafeToFuture()
  }

  test("typed view errors retain distinct missing-input and full-pin-mismatch causes") {
    withOwner { owner =>
      owner.current.map { actual =>
        val p = actual.pin
        val mismatch = StatePin
          .checked(
            p.ownerId,
            p.generation,
            p.point,
            p.coherentStateId,
            p.ledgerStateId,
            p.environmentId,
            p.validationSlot + 1,
            p.profileId
          )
          .toOption
          .get
        assertEquals(
          AdmissionView.checkedTyped(null, actual.ledger),
          Left(AdmissionView.ConstructionError.MissingInput)
        )
        assertEquals(
          AdmissionView.checkedTyped(p, null),
          Left(AdmissionView.ConstructionError.MissingInput)
        )
        assertEquals(
          AdmissionView.checkedTyped(mismatch, actual.ledger),
          Left(AdmissionView.ConstructionError.PinMismatch)
        )
        assertEquals(
          AdmissionView.checked(null, actual.ledger),
          Left("pin and ledger view required")
        )
        assertEquals(
          AdmissionView.checked(mismatch, actual.ledger),
          Left("ledger view differs from complete state pin")
        )
      }
    }.unsafeToFuture()
  }

  test("owner assembly retains domain role and pin causes before the single effect renderer") {
    withOwner { owner =>
      for
        view <- owner.current
        snapshot <- owner.snapshot
        invalidOwner = SubmissionOwner.pinFor(null, -1, snapshot.state, owner.profile)
        invalidGeneration = SubmissionOwner.pinFor(
          view.pin.ownerId,
          -1,
          snapshot.state,
          owner.profile
        )
        invalidProfile = SubmissionOwner.pinFor(view.pin.ownerId, 0, snapshot.state, null)
        _ = assertEquals(
          invalidOwner,
          Left(E.Domain(PinDomain.Error.InvalidHash(PinDomain.Role.Owner)))
        )
        _ = assertEquals(
          invalidGeneration,
          Left(E.Domain(PinDomain.Error.InvalidUInt64(PinDomain.Role.Generation)))
        )
        _ = assertEquals(invalidProfile, Left(E.Pin(StatePin.ConstructionError.UnsupportedProfile)))
        lifted <- IO.fromEither(invalidGeneration.leftMap(new ViewConstructionFailure(_))).attempt
        _ = lifted match
          case Left(error: ViewConstructionFailure) =>
            assertEquals(
              error.error,
              E.Domain(PinDomain.Error.InvalidUInt64(PinDomain.Role.Generation))
            )
            assertEquals(error.getMessage, "uint64 generation and validation slot required")
            assert(error.isInstanceOf[IllegalStateException])
          case other => fail(s"expected typed construction failure, got $other")
        viewError = new ViewConstructionFailure(E.View(AdmissionView.ConstructionError.PinMismatch))
        _ = assertEquals(viewError.error, E.View(AdmissionView.ConstructionError.PinMismatch))
        _ = assertEquals(viewError.getMessage, "ledger view differs from complete state pin")
      yield ()
    }.unsafeToFuture()
  }

  test("internal construction cause cannot be replaced by an arbitrary diagnostic string") {
    assert(
      typeCheckErrors(
        """import lab.submission.AdmissionView; val error: AdmissionView.ConstructionError = "mismatch""""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import lab.SubmissionOwner; val error = SubmissionOwner.ViewConstructionError.Domain("bad")"""
      ).nonEmpty
    )
  }
