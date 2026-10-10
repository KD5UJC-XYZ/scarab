// SPDX-License-Identifier: Apache-2.0
package lab.submission

import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import PinDomain.*
import scala.compiletime.testing.typeCheckErrors

class PinDomainSuite extends munit.FunSuite:
  private def h(n: Int) = Bytes.fromArray(Array.fill[Byte](32)(n.toByte))
  private val point = Point(h(5), 10, 1)
  private def typed(generation: BigInt, slot: BigInt) = StatePin.checkedTyped(
    OwnerId.checked(h(1)).toOption.get,
    Generation.checked(generation).toOption.get,
    point,
    CoherentStateId.checked(h(2)).toOption.get,
    LedgerStateId.checked(h(3)).toOption.get,
    EnvironmentId.checked(h(4)).toOption.get,
    ValidationSlot.checked(slot).toOption.get,
    AdmissionProfile.PlutusV3
  )

  test("typed and compatibility constructors preserve every raw field and equality") {
    val raw = StatePin
      .checked(h(1), 7, point, h(2), h(3), h(4), 10, AdmissionProfile.PlutusV3.id)
      .toOption
      .get
    val checked = typed(7, 10).toOption.get
    assertEquals(checked, raw)
    assertEquals(checked.hashCode, raw.hashCode)
    assertEquals(checked.ownerId, h(1))
    assertEquals(checked.coherentStateId, h(2))
    assertEquals(checked.ledgerStateId, h(3))
    assertEquals(checked.environmentId, h(4))
    assertEquals(checked.profileId, "isolated-conway-pv9-plutus-v3-spend-v1")
    assertNotEquals(typed(8, 10), typed(7, 10))
    assertNotEquals(typed(7, 11), typed(7, 10))
  }
  test("hash roles reject null and incorrect lengths with typed role detail") {
    assertEquals(OwnerId.checked(null), Left(Error.InvalidHash(Role.Owner)))
    assertEquals(
      LedgerStateId.checked(Bytes.fromArray(new Array[Byte](31))),
      Left(Error.InvalidHash(Role.LedgerState))
    )
    assertEquals(
      EnvironmentId.checked(Bytes.fromArray(new Array[Byte](33))),
      Left(Error.InvalidHash(Role.Environment))
    )
    assertEquals(CoherentStateId.checked(Bytes(null)), Left(Error.InvalidHash(Role.CoherentState)))
  }
  test("quantity roles retain complete uint64 domain without narrowing") {
    Vector(BigInt(0), BigInt(Long.MaxValue) + 1, StatePin.MaxUInt64).foreach { n =>
      assertEquals(Generation.checked(n).toOption.get.value, n)
      assertEquals(ValidationSlot.checked(n).toOption.get.value, n)
      assert(typed(n, n).isRight)
    }
    Vector(BigInt(-1), StatePin.MaxUInt64 + 1).foreach { n =>
      assertEquals(Generation.checked(n), Left(Error.InvalidUInt64(Role.Generation)))
      assertEquals(ValidationSlot.checked(n), Left(Error.InvalidUInt64(Role.ValidationSlot)))
    }
    assertEquals(Generation.checked(null), Left(Error.InvalidUInt64(Role.Generation)))
  }
  test("compatibility error order/text and typed construction error remain explicit") {
    assertEquals(
      StatePin.checked(null, -1, null, h(2), h(3), h(4), -1, "bad"),
      Left("32-byte owner, coherent state, ledger state and environment identities required")
    )
    assertEquals(
      StatePin.checked(h(1), -1, null, h(2), h(3), h(4), -1, "bad"),
      Left("full point requires 32-byte hash and uint64 slot/block number")
    )
    assertEquals(
      StatePin.checked(h(1), -1, point, h(2), h(3), h(4), -1, "bad"),
      Left("uint64 generation and validation slot required")
    )
    assertEquals(
      StatePin.checked(h(1), 0, point, h(2), h(3), h(4), 0, "bad"),
      Left("unsupported state pin profile")
    )
    val result = StatePin.checkedTyped(
      OwnerId.checked(h(1)).toOption.get,
      Generation.checked(0).toOption.get,
      null,
      CoherentStateId.checked(h(2)).toOption.get,
      LedgerStateId.checked(h(3)).toOption.get,
      EnvironmentId.checked(h(4)).toOption.get,
      ValidationSlot.checked(0).toOption.get,
      AdmissionProfile.PlutusV3
    )
    assertEquals(result, Left(StatePin.ConstructionError.InvalidPoint))
  }
  test("role and unit substitution do not compile; no implicit byte conversion exists") {
    assert(
      typeCheckErrors(
        """import lab.submission.PinDomain.*; import lab.cbor.Bytes; val bad: OwnerId = LedgerStateId.checked(Bytes.fromArray(new Array[Byte](32))).toOption.get"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import lab.submission.PinDomain.*; val bad: Generation = ValidationSlot.checked(BigInt(0)).toOption.get"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """import lab.submission.PinDomain.*; import lab.cbor.Bytes; val bad: OwnerId = Bytes.fromArray(new Array[Byte](32))"""
      ).nonEmpty
    )
  }
