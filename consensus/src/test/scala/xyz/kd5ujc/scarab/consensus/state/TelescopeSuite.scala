// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.syntax.all.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import xyz.kd5ujc.scarab.consensus.state.StateGen.*
import xyz.kd5ujc.scarab.consensus.ByronState
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  EpochNo,
  LedgerStateGen,
  NewEpochState,
  ShelleyBasedEra,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.Era

/** The telescope: era `n` has `n` past eras, consecutive eras abut, and the smart constructor is
  * the only way in.
  */
class TelescopeSuite extends ScalaCheckSuite:
  private val start = Bound(0, 0, 0)

  private def bound(n: Int) = Bound(BigInt(n) * 1000, BigInt(n), BigInt(n))

  private def pasts(n: Int): Vector[Past] = Vector.tabulate(n)(i => Past(bound(i), bound(i + 1)))

  private val conway: Gen[EraLedgerState] = eraLedgerState(Era.Conway)

  property("a generated telescope has as many past eras as its era's index") {
    forAll(cardanoLedgerState)(t => t.past.size == t.era.index && t.state.era == t.era)
  }

  property("make reads back the parts of a telescope") {
    forAll(cardanoLedgerState)(t => Telescope.make(t.past, t.current) === Right(t))
    forAll(chainDepTelescope)(t => Telescope.make(t.past, t.current) === Right(t))
  }

  property("the era of the state decides how many past eras it takes") {
    forAll(anyEra, anyEra) { (stateEra, placed) =>
      forAll(eraLedgerState(stateEra)) { state =>
        val made = Telescope.make(pasts(placed.index), Current(bound(placed.index), state))
        if stateEra == placed then made.isRight else made === Left(TelescopeError.WrongEra(placed))
      }
    }
  }

  private def protocol(era: Era): Int =
    if era == Era.Byron then 0 else if era.usesTPraos then 1 else 2

  property("a protocol state is admitted in the eras that run its protocol") {
    forAll(anyEra, anyEra) { (stateEra, placed) =>
      forAll(chainDepState(stateEra)) { state =>
        val made = Telescope.make(pasts(placed.index), Current(bound(placed.index), state))
        made.isRight == (protocol(stateEra) == protocol(placed))
      }
    }
  }

  property("more past eras than the chain has eras before the last are refused") {
    forAll(conway)(state =>
      Telescope.make(pasts(Era.values.length), Current(bound(Era.values.length), state)) ===
        Left(TelescopeError.TooManyPast(Era.values.length))
    )
  }

  property("a gap or an overlap between eras is refused where it is") {
    forAll(Gen.choose(1, 6), Gen.choose(0, 5)) { (n, at) =>
      val index = at % n
      forAll(eraLedgerState(Era.values(n))) { state =>
        val broken = pasts(n).updated(index, Past(bound(index), bound(index + 1).copy(slot = 99)))
        Telescope.make(broken, Current(bound(n), state)) === Left(
          TelescopeError.Discontinuity(index)
        )
      }
    }
  }

  property("the current era must start where the last past era ends") {
    forAll(eraLedgerState(Era.Allegra))(state =>
      Telescope.make(pasts(2), Current(bound(5), state)) === Left(TelescopeError.Discontinuity(1))
    )
  }

  property("extend moves to the next era, one more past era, same pasts before") {
    forAll(cardanoLedgerState) { t =>
      val next = Era.values.lift(t.era.index + 1)
      val end =
        Bound(t.current.start.timePs + 1, t.current.start.slot + 1, t.current.start.epoch + 1)
      next.fold(
        forAll(conway)(s =>
          t.extend(end, s) === Left(TelescopeError.TooManyPast(Era.values.length))
        )
      )(era =>
        forAll(eraLedgerState(era)) { s =>
          t.extend(end, s)
            .exists(e =>
              e.era == era && e.past.size == t.past.size + 1 && e.past.startsWith(t.past) &&
                e.past.last === Past(t.current.start, end) && e.current === Current(end, s)
            )
        }
      )
    }
  }

  test("a telescope of the first era has no past, and only Byron's state fits it") {
    val byron = EraLedgerState.ByronCurrent(ByronState.initial)
    val conwayState = EraLedgerState.inEra(
      ShelleyLedgerState[Conway](
        WithOrigin.Origin,
        NewEpochState.empty[Conway](EpochNo.fromUnsigned(0), LedgerStateGen.params),
        ShelleyTransition(Word32.fromUnsigned(0))
      )
    )(using ShelleyBasedEra[Conway])
    assertEquals(
      Telescope.first(start, byron).map(t => (t.era, t.past)),
      Right((Era.Byron, Vector.empty))
    )
    assertEquals(Telescope.first(start, conwayState), Left(TelescopeError.WrongEra(Era.Byron)))
  }
