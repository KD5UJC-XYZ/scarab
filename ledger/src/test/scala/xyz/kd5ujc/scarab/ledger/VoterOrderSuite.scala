// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.Order
import cats.kernel.laws.discipline.OrderTests
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Prop.forAll
import scala.math.Ordering.Implicits.seqOrdering
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Voter.*

/** [[Voter.order]] is the reference's derived `Ord Voter` (`Conway/Governance/Procedures.hs`
  * 338-342, f649f975), and the order it had when it compared hex strings.
  */
class VoterOrderSuite extends DisciplineSuite:
  // Some bytes are high (signed bytes must not be compared as signed), the 28-byte hashes differ
  // in the first four bytes or only after them, and the short ones put a prefix first.
  private val alphabet = Gen.oneOf(0, 1, 127, 128, 255).map(_.toByte)

  private val genHash: Gen[Bytes] =
    val short = Gen.choose(0, 3).flatMap(Gen.listOfN(_, alphabet))
    val full = for
      head <- Gen.listOfN(4, Gen.frequency(4 -> Gen.const(0.toByte), 1 -> alphabet))
      tail <- Gen.listOfN(24, Gen.frequency(8 -> Gen.const(0.toByte), 1 -> alphabet))
    yield head ++ tail
    Gen.oneOf(short, full).map(bs => Bytes.fromArray(bs.toArray))

  private val genCredential: Gen[Credential] =
    Gen.zip(Gen.oneOf(true, false), genHash).map { (script, h) =>
      if script then Credential.Script(h) else Credential.Key(h)
    }

  private given Arbitrary[Voter] = Arbitrary(
    Gen.oneOf(
      genCredential.map(CommitteeHot(_)),
      genCredential.map(DRepVoter(_)),
      genHash.map(Pool(_))
    )
  )

  private given Cogen[Voter] = Cogen[(Int, Int, List[Byte])].contramap(model)

  /** The derived order written out: the index of the constructor of `Voter`, then the index of the
    * constructor of `Credential` (`Credential.hs` 98-101: `ScriptHashObj` 0, `KeyHashObj` 1), then
    * the bytes of the hash as unsigned numbers, compared as lists (a prefix first).
    */
  private def model(v: Voter): (Int, Int, List[Byte]) = v match
    case CommitteeHot(c) => (0, credentialIndex(c), c.hash.value.toList)
    case DRepVoter(c)    => (1, credentialIndex(c), c.hash.value.toList)
    case Pool(h)         => (2, 0, h.value.toList)

  private def credentialIndex(c: Credential): Int = c match
    case Credential.Script(_) => 0
    case Credential.Key(_)    => 1

  private def reference(v: Voter): (Int, Int, List[Int]) =
    val (constructor, credential, bytes) = model(v)
    (constructor, credential, bytes.map(_ & 0xff))

  /** The order this replaced: the constructor, whether the credential is a key, the hex. */
  private def hexKey(v: Voter): (Int, Boolean, String) = v match
    case CommitteeHot(c) => (0, c.isInstanceOf[Credential.Key], c.hash.hex)
    case DRepVoter(c)    => (1, c.isInstanceOf[Credential.Key], c.hash.hex)
    case Pool(h)         => (2, false, h.hex)

  checkAll("Voter", OrderTests[Voter].order)

  property("voters compare as the derived Ord Voter does") {
    forAll { (a: Voter, b: Voter) =>
      Integer.signum(Order[Voter].compare(a, b)) ==
        Integer.signum(Ordering[(Int, Int, List[Int])].compare(reference(a), reference(b)))
    }
  }

  property("voters sort as they did by the hex of the hash") {
    forAll(Gen.listOf(Arbitrary.arbitrary[Voter])) { vs =>
      vs.sorted == vs.sortBy(hexKey)
    }
  }

  private def hash(byte: Int): Bytes = Bytes.fromArray(Array.fill(28)(byte.toByte))

  private def lastByte(byte: Int): Bytes =
    Bytes.fromArray(Array.fill(27)(0.toByte) :+ byte.toByte)

  // Each pair is in the reference's order, the first before the second.
  private val ascending: List[(String, Voter, Voter)] = List(
    ("a high byte after a low one", Pool(hash(0x7f)), Pool(hash(0x80))),
    (
      "a script before a key, whatever the hash",
      CommitteeHot(Credential.Script(hash(0xff))),
      CommitteeHot(Credential.Key(hash(0x00)))
    ),
    (
      "a script DRep before a key DRep",
      DRepVoter(Credential.Script(hash(0xff))),
      DRepVoter(Credential.Key(hash(0x00)))
    ),
    (
      "the committee before a DRep",
      CommitteeHot(Credential.Key(hash(0xff))),
      DRepVoter(Credential.Script(hash(0x00)))
    ),
    ("a DRep before a pool", DRepVoter(Credential.Key(hash(0xff))), Pool(hash(0x00))),
    (
      "the last byte decides",
      DRepVoter(Credential.Script(lastByte(0x00))),
      DRepVoter(Credential.Script(lastByte(0x01)))
    ),
    ("a prefix first", Pool(Bytes.fromArray(Array.fill(27)(0.toByte))), Pool(hash(0x00)))
  )

  ascending.foreach { (name, lower, higher) =>
    test(s"in the reference's order: $name") {
      val (forward, backward, self) =
        (
          Order[Voter].compare(lower, higher),
          Order[Voter].compare(higher, lower),
          Order[Voter].compare(lower, lower)
        )
      assert(forward < 0, forward)
      assert(backward > 0, backward)
      assertEquals(self, 0)
      assert(Ordering[(Int, Int, List[Int])].lt(reference(lower), reference(higher)))
      assertEquals(Vector(higher, lower).sorted, Vector(lower, higher))
    }
  }
