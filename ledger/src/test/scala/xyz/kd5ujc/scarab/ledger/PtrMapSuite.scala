// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** The pointer map with its reverse index behaves as the plain map it replaced: after any sequence
  * of registrations (`updated`) and deregistrations (`removedCredential`, once a `filterNot` over
  * every pointer), it holds the same entries.
  */
class PtrMapSuite extends ScalaCheckSuite:
  private def credential(n: Int): Credential =
    Credential.Key(Bytes.wrap(Array.tabulate(28)(i => (n + i).toByte)))

  private enum Step:
    case Register(ptr: Ptr, credential: Credential)
    case Deregister(credential: Credential)

  private val genStep: Gen[Step] = Gen.oneOf(
    for
      slot <- Gen.choose(0, 5)
      cert <- Gen.choose(0, 2)
      who <- Gen.choose(0, 4)
    yield Step.Register(Ptr(slot.toLong, 0, cert.toLong), credential(who)),
    Gen.choose(0, 4).map(who => Step.Deregister(credential(who)))
  )

  property("registrations and deregistrations agree with the plain map") {
    forAll(Gen.listOf(genStep)) { steps =>
      val (model, indexed) = steps.foldLeft((Map.empty[Ptr, Credential], PtrMap.empty)) {
        case ((m, p), Step.Register(ptr, c)) => (m.updated(ptr, c), p.updated(ptr, c))
        case ((m, p), Step.Deregister(c))    => (m.filterNot(_._2 == c), p.removedCredential(c))
      }
      indexed.toMap == model && indexed == PtrMap(model) && indexed.size == model.size
    }
  }

  test("a map built from entries removes the pointers of a credential") {
    val a = credential(1)
    val b = credential(2)
    val ptrs = PtrMap(Map(Ptr(1, 0, 0) -> a, Ptr(2, 0, 0) -> b, Ptr(3, 0, 0) -> a))
    assertEquals(ptrs.removedCredential(a).toMap, Map(Ptr(2, 0, 0) -> b))
    assertEquals(ptrs.removedCredential(credential(9)), ptrs)
  }
