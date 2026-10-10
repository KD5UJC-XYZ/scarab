// SPDX-License-Identifier: Apache-2.0
package lab.submission

import cats.kernel.{Eq, Order}
import cats.kernel.laws.discipline.{EqTests, OrderTests}
import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}
import lab.cbor.Bytes
import PinDomain.*

class PinDomainLaws extends munit.DisciplineSuite:
  private val hashes =
    Gen.listOfN(32, Arbitrary.arbitrary[Byte]).map(xs => Bytes.fromArray(xs.toArray))
  private val quantities = Gen.oneOf(
    Gen.const(BigInt(0)),
    Gen.const(StatePin.MaxUInt64),
    Gen.chooseNum(Long.MinValue, Long.MaxValue).map(n => BigInt(n) - BigInt(Long.MinValue))
  )
  given Arbitrary[OwnerId] = Arbitrary(hashes.map(raw => OwnerId.checked(raw).toOption.get))
  given Cogen[OwnerId] = Cogen[List[Byte]].contramap(_.bytes.value.toList)
  checkAll("OwnerId.Eq", EqTests[OwnerId].eqv)
  given Arbitrary[CoherentStateId] = Arbitrary(
    hashes.map(raw => CoherentStateId.checked(raw).toOption.get)
  )
  given Cogen[CoherentStateId] = Cogen[List[Byte]].contramap(_.bytes.value.toList)
  checkAll("CoherentStateId.Eq", EqTests[CoherentStateId].eqv)
  given Arbitrary[LedgerStateId] = Arbitrary(
    hashes.map(raw => LedgerStateId.checked(raw).toOption.get)
  )
  given Cogen[LedgerStateId] = Cogen[List[Byte]].contramap(_.bytes.value.toList)
  checkAll("LedgerStateId.Eq", EqTests[LedgerStateId].eqv)
  given Arbitrary[EnvironmentId] = Arbitrary(
    hashes.map(raw => EnvironmentId.checked(raw).toOption.get)
  )
  given Cogen[EnvironmentId] = Cogen[List[Byte]].contramap(_.bytes.value.toList)
  checkAll("EnvironmentId.Eq", EqTests[EnvironmentId].eqv)
  given Arbitrary[Generation] = Arbitrary(
    quantities.map(raw => Generation.checked(raw).toOption.get)
  )
  given Cogen[Generation] = Cogen[BigInt].contramap(_.value)
  checkAll("Generation.Order", OrderTests[Generation].order)
  given Arbitrary[ValidationSlot] = Arbitrary(
    quantities.map(raw => ValidationSlot.checked(raw).toOption.get)
  )
  given Cogen[ValidationSlot] = Cogen[BigInt].contramap(_.value)
  checkAll("ValidationSlot.Order", OrderTests[ValidationSlot].order)
  property("owner Eq agrees with raw bytes and universal equality/hashCode") {
    Prop.forAll { (a: OwnerId, b: OwnerId) =>
      val equal = summon[Eq[OwnerId]].eqv(a, b)
      equal == (a.bytes == b.bytes) && equal == (a == b) && (!equal || a.hashCode == b.hashCode)
    }
  }
  property("quantity ordering agrees with all uint64 BigInt values") {
    Prop.forAll { (a: Generation, b: Generation) =>
      Integer.signum(summon[Order[Generation]].compare(a, b)) == Integer.signum(
        a.value.compare(b.value)
      )
    }
  }

  private def rawEquality[A](unwrap: A => Bytes)(using Arbitrary[A], Eq[A]): Prop =
    Prop.forAll { (a: A, b: A) =>
      val equal = summon[Eq[A]].eqv(a, b)
      equal == (unwrap(a) == unwrap(b)) && equal == (a == b) && (!equal || a.hashCode == b.hashCode)
    }
  property("coherent identity Eq preserves complete raw bytes") {
    rawEquality[CoherentStateId](_.bytes)
  }
  property("ledger identity Eq preserves complete raw bytes") {
    rawEquality[LedgerStateId](_.bytes)
  }
  property("environment identity Eq preserves complete raw bytes") {
    rawEquality[EnvironmentId](_.bytes)
  }
  property("validation slot ordering preserves complete uint64 denotation") {
    Prop.forAll { (a: ValidationSlot, b: ValidationSlot) =>
      Integer.signum(summon[Order[ValidationSlot]].compare(a, b)) == Integer.signum(
        a.value.compare(b.value)
      )
    }
  }
