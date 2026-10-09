// SPDX-License-Identifier: Apache-2.0
package lab.submission

import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point

class StatePinSuite extends munit.FunSuite:
  private def b(n: Int): Bytes = Bytes(Vector.fill(32)(n.toByte))
  private def checked(
      owner: Bytes = b(1),
      generation: BigInt = 2,
      point: Point = Point(b(3), 4, 5),
      coherent: Bytes = b(6),
      ledger: Bytes = b(7),
      environment: Bytes = b(8),
      slot: BigInt = 4,
      profile: String = StatePin.Profile
  ): Either[String, StatePin] =
    StatePin.checked(owner, generation, point, coherent, ledger, environment, slot, profile)
  private def get(e: Either[String, StatePin]): StatePin = e.fold(fail(_), identity)

  test("equal pins compare by every value and work as immutable map keys") {
    val first = get(checked())
    val second = get(checked())
    assertEquals(first, second)
    assertEquals(first.hashCode, second.hashCode)
    assertEquals(Map(first -> "owned").get(second), Some("owned"))
    assert(!first.equals(null))
    assert(!first.equals("pin"))
  }
  test("owner generation full point state environment and validation slot distinguish pins") {
    val first = get(checked())
    Vector(
      checked(owner = b(9)),
      checked(generation = 3),
      checked(point = Point(b(9), 4, 5)),
      checked(point = Point(b(3), 6, 5)),
      checked(point = Point(b(3), 4, 6)),
      checked(coherent = b(9)),
      checked(ledger = b(9)),
      checked(environment = b(9)),
      checked(slot = 6)
    ).foreach { changed =>
      assertNotEquals(get(changed), first)
    }
  }
  test("every numeric field requires uint64 including exact point block number") {
    val max = StatePin.MaxUInt64
    assert(checked(generation = max, point = Point(b(3), max, max), slot = max).isRight)
    assert(checked(generation = 0, point = Point(b(3), 0, 0), slot = 0).isRight)
    Vector(BigInt(-1), max + 1).foreach { invalid =>
      assert(checked(generation = invalid).isLeft)
      assert(checked(point = Point(b(3), invalid, 5)).isLeft)
      assert(checked(point = Point(b(3), 4, invalid)).isLeft)
      assert(checked(slot = invalid).isLeft)
    }
  }
  test("all identity widths point and fixed profile are checked without null exceptions") {
    Vector(Bytes.empty, Bytes(Vector.fill(31)(0.toByte)), Bytes(Vector.fill(33)(0.toByte)), null)
      .foreach { invalid =>
        assert(checked(owner = invalid).isLeft)
        assert(checked(coherent = invalid).isLeft)
        assert(checked(ledger = invalid).isLeft)
        assert(checked(environment = invalid).isLeft)
        assert(checked(point = Point(invalid, 4, 5)).isLeft)
      }
    assert(checked(point = null).isLeft)
    assert(checked(generation = null).isLeft)
    assert(checked(slot = null).isLeft)
    assert(checked(point = Point(b(3), null, 5)).isLeft)
    assert(checked(point = Point(b(3), 4, null)).isLeft)
    assert(checked(profile = "future-profile").isLeft)
    assert(checked(profile = null).isLeft)
  }

  test("closed opt-in profiles remain distinct in the complete pin") {
    assertEquals(StatePin.Profile, AdmissionProfile.AdaVkey.id)
    assertEquals(AdmissionProfile.fromId(StatePin.Profile), Some(AdmissionProfile.AdaVkey))
    assertEquals(
      AdmissionProfile.fromId(AdmissionProfile.NativeScript.id),
      Some(AdmissionProfile.NativeScript)
    )
    assertEquals(AdmissionProfile.fromId("future-profile"), None)
    assertEquals(AdmissionProfile.fromId(null), None)
    val native = get(checked(profile = AdmissionProfile.NativeScript.id))
    assertNotEquals(native, get(checked()))
    assertEquals(native.profileId, AdmissionProfile.NativeScript.id)
  }
