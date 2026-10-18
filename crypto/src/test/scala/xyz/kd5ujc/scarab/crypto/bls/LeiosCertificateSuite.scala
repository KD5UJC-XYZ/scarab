// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Gen, Test}
import supranational.blst.P2

/** Properties of votes, aggregation and certificate verification over a 24-seat committee. */
class LeiosCertificateSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(25)

  private val seatCount = 24
  private lazy val keys: Vector[(SecretKey, VerificationKey)] =
    Vector.tabulate(seatCount)(i => key(100 + i))
  private def weightOf(i: Int): Weight = Weight.fromNatural(BigInt(10 + 7 * i)).get
  private def seatId(i: Int): SeatId = right(SeatId.fromInt(i))

  /** A committee in which every seat has a valid key and proof of possession. */
  private lazy val committee: Committee =
    right(
      Committee.fromSeats(keys.zipWithIndex.map { case ((sk, vk), i) =>
        (weightOf(i), Some((vk, right(MinSig.provePossession(sk)))))
      })
    )

  private val genSeat: Gen[Int] = Gen.choose(0, seatCount - 1)
  private val genSigners: Gen[Set[Int]] = Gen.nonEmptyListOf(genSeat).map(_.toSet)
  private val genHash: Gen[RbHash] = Gen.choose(0, Int.MaxValue).map(rbHash)

  private def votes(hash: RbHash, seats: Set[Int]): Map[SeatId, Signature] =
    seats.map(i => seatId(i) -> right(MinSig.sign(keys(i)._1, hash.bytes))).toMap

  private def totalOf(seats: Set[Int]): Weight =
    seats.foldLeft(Weight.zero)((a, i) => a + weightOf(i))

  private def bs(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray

  private def flip(b: Bytes, bit: Int): Bytes =
    val a = b.toArray
    a(bit / 8) = (a(bit / 8) ^ (1 << (bit % 8))).toByte
    Bytes.wrap(a)

  test("the BLS12-381 library is the portable build (ADR 0012)") {
    import cats.effect.unsafe.implicits.global
    val found = xyz.kd5ujc.scarab.blst.Blst.build[cats.effect.IO].unsafeRunSync()
    if xyz.kd5ujc.scarab.blst.Platform.current == xyz.kd5ujc.scarab.blst.Platform.LinuxAmd64 then
      assertEquals(found, xyz.kd5ujc.scarab.blst.LibraryBuild.Portable)
  }

  property("any valid vote verifies and returns the voter's weight") {
    forAll(genSeat, genHash) { (i, h) =>
      val vote = right(Vote.sign(keys(i)._1, seatId(i), h))
      assertEquals(Vote.verify(committee, vote), Right(weightOf(i)))
    }
  }

  property("a vote does not verify under another announcing hash or from another seat") {
    forAll(genSeat, genSeat, genHash, genHash) { (i, j, h, h2) =>
      val vote = right(Vote.sign(keys(i)._1, seatId(i), h))
      val replay = vote.copy(announcingRbHash = h2)
      assertEquals(Vote.verify(committee, replay).isLeft, h != h2)
      assertEquals(Vote.verify(committee, vote.copy(voter = seatId(j))).isLeft, i != j)
    }
  }

  property("an aggregate of valid votes is a certificate that verifies, with the signers' weight") {
    forAll(genSigners, genHash) { (seats, h) =>
      val cert = right(Certificate.aggregate(committee, votes(h, seats)))
      assertEquals(Certificate.verify(committee, Weight.zero, h, cert), Right(totalOf(seats)))
      assertEquals(Certificate.decode(cert.encode), Right(cert))
    }
  }

  property("aggregation does not depend on the order of the votes") {
    forAll(genSigners, genHash) { (seats, h) =>
      val vs = votes(h, seats).toVector
      val a = right(MinSig.aggregateSignatures(vs.map(_._2)))
      val b = right(MinSig.aggregateSignatures(vs.reverse.map(_._2)))
      assertEquals(a, b)
    }
  }

  property("a certificate fails for another announcing hash") {
    forAll(genSigners, genHash, genHash) { (seats, h, h2) =>
      val cert = right(Certificate.aggregate(committee, votes(h, seats)))
      assertEquals(Certificate.verify(committee, Weight.zero, h2, cert).isLeft, h != h2)
    }
  }

  property("flipping any one bit of the aggregate signature fails (decode or verify)") {
    forAll(genSigners, genHash, Gen.choose(0, 48 * 8 - 1)) { (seats, h, bit) =>
      val cert = right(Certificate.aggregate(committee, votes(h, seats)))
      val ok = Signature.fromBytes(flip(cert.signature.bytes, bit)) match
        case Left(_) => false
        case Right(sig) =>
          Certificate.verify(committee, Weight.zero, h, cert.copy(signature = sig)).isRight
      assert(!ok)
    }
  }

  property("flipping any one bit of the signers bitfield fails") {
    forAll(genSigners, genHash, Gen.choose(0, 3 * 8 - 1)) { (seats, h, bit) =>
      val cert = right(Certificate.aggregate(committee, votes(h, seats)))
      val tampered = Signers.fromBytes(flip(cert.signers.bytes, bit)).get
      assert(Certificate.verify(committee, Weight.zero, h, cert.copy(signers = tampered)).isLeft)
    }
  }

  property("flipping any one bit of a vote's signature or of the hash fails") {
    forAll(genSeat, genHash, Gen.choose(0, 48 * 8 - 1), Gen.choose(0, 32 * 8 - 1)) {
      (i, h, sbit, hbit) =>
        val vote = right(Vote.sign(keys(i)._1, seatId(i), h))
        Signature.fromBytes(flip(vote.signature.bytes, sbit)).foreach { s =>
          assert(Vote.verify(committee, vote.copy(signature = s)).isLeft)
        }
        val h2 = RbHash.fromBytes(flip(h.bytes, hbit)).get
        assert(Vote.verify(committee, vote.copy(announcingRbHash = h2)).isLeft)
    }
  }

  property("flipping any one bit of a verification key fails (decode or verify)") {
    forAll(genSeat, genHash, Gen.choose(0, 96 * 8 - 1)) { (i, h, bit) =>
      val sig = right(MinSig.sign(keys(i)._1, h.bytes))
      VerificationKey.fromBytes(flip(keys(i)._2.bytes, bit)).foreach { vk =>
        assert(MinSig.verify(vk, h.bytes, sig).isLeft)
      }
    }
  }

  property("flipping any one bit of a proof of possession fails (decode or verify)") {
    forAll(genSeat, Gen.choose(0, 48 * 8 - 1)) { (i, bit) =>
      val pop = right(MinSig.provePossession(keys(i)._1))
      assertEquals(MinSig.verifyPossession(keys(i)._2, pop), Right(()))
      ProofOfPossession.fromBytes(flip(pop.bytes, bit)).foreach { p =>
        assert(MinSig.verifyPossession(keys(i)._2, p).isLeft)
      }
    }
  }

  property("a signature made under any other tag does not verify as a vote") {
    forAll(
      genSeat,
      genHash,
      Gen.oneOf(
        "BLS_SIG_BLS12381G1_XMD:SHA-256_SSWU_RO_NUL_",
        "BLS_POP_BLS12381G1_XMD:SHA-256_SSWU_RO_POP_",
        "x"
      )
    ) { (i, h, dst) =>
      val sig = right(MinSig.signUnder(keys(i)._1, h.bytes, dst))
      assert(MinSig.verifyUnder(keys(i)._2, h.bytes, sig, MinSig.signatureDst).isLeft)
    }
  }

  property("a duplicated vote in the aggregate (signature counted twice) fails") {
    forAll(genSigners, genHash) { (seats, h) =>
      val vs = votes(h, seats).toVector
      val doubled = right(MinSig.aggregateSignatures(vs.map(_._2) :+ vs.head._2))
      val cert = Certificate(right(Signers.of(seatCount, seats.map(seatId))), doubled)
      assert(Certificate.verify(committee, Weight.zero, h, cert).isLeft)
    }
  }

  property("removing or adding one signer to the bitfield without the signature fails") {
    forAll(genSigners, genHash, genSeat) { (seats, h, extra) =>
      val cert = right(Certificate.aggregate(committee, votes(h, seats)))
      val changed = if seats(extra) then seats - extra else seats + extra
      if changed.nonEmpty then
        val signers = right(Signers.of(seatCount, changed.map(seatId)))
        assert(Certificate.verify(committee, Weight.zero, h, cert.copy(signers = signers)).isLeft)
    }
  }

  property("the signature of a single vote is not a certificate for a different seat") {
    forAll(genSeat, genSeat, genHash) { (i, j, h) =>
      val sig = right(MinSig.sign(keys(i)._1, h.bytes))
      val cert = Certificate(right(Signers.of(seatCount, Set(seatId(j)))), sig)
      assertEquals(Certificate.verify(committee, Weight.zero, h, cert).isRight, i == j)
    }
  }

  property("certificate wire size is ceil(N/8) + 48 + a fixed CBOR overhead") {
    forAll(Gen.choose(1, 2000)) { n =>
      val signers = right(Signers.of(n, Set(seatId(0), seatId(n - 1))))
      val sig = right(MinSig.sign(keys(0)._1, rbHash(0).bytes))
      val len = (n + 7) / 8
      val hdr = if len < 24 then 1 else if len < 256 then 2 else 3
      assertEquals(Certificate(signers, sig).encode.size, 1 + hdr + len + 2 + 48)
    }
  }

  test("900 seats: the bitfield is 113 bytes and the certificate 166 bytes") {
    val signers = right(Signers.of(900, Set(seatId(0), seatId(899))))
    assertEquals(signers.bytes.size, 113)
    val sig = right(MinSig.sign(keys(0)._1, rbHash(0).bytes))
    assertEquals(Certificate(signers, sig).encode.size, 166)
  }

  test("rogue-key attack: forgeable by raw aggregation, stopped by the proof of possession") {
    val h = rbHash(7)
    val (_, victim) = keys(3)
    val (rogueSk, rogueBase) = key(9999)
    // rogue key = [x]G2 - victim; the sum victim + rogue is [x]G2, whose secret the attacker knows.
    val rogueP = new P2(rogueSk.blst).add(victim.point.to_jacobian().neg())
    val rogueKey = right(VerificationKey.fromBytes(Bytes.wrap(rogueP.compress())))
    assertNotEquals(rogueKey, rogueBase)
    val forged = right(MinSig.sign(rogueSk, h.bytes))
    // Without proofs of possession the pair "victim + rogue" verifies a signature the victim never made.
    assertEquals(MinSig.verifyAggregate(Vector(victim, rogueKey), h.bytes, forged), Right(()))
    // The attacker cannot produce a proof for the rogue key; the committee seats it keyless.
    val bogusPop = right(MinSig.provePossession(rogueSk))
    val seats = Vector(
      (weightOf(0), Some((victim, right(MinSig.provePossession(keys(3)._1))))),
      (weightOf(1), Some((rogueKey, bogusPop)))
    )
    val c = right(Committee.fromSeats(seats))
    assertEquals(c.seats(1).key, None)
    val cert = Certificate(right(Signers.of(2, Set(seatId(0), seatId(1)))), forged)
    assertEquals(
      Certificate.verify(c, Weight.zero, h, cert),
      Left(BlsError.SignerWithoutKey(Vector(seatId(1))))
    )
  }

  test("structural rejections: keyless signer, quorum, bitfield shape, empty") {
    val h = rbHash(11)
    val ks = keys.take(3)
    val pops = ks.map(k => right(MinSig.provePossession(k._1)))
    val c = right(
      Committee.fromSeats(
        Vector(
          (weightOf(0), Some((ks(0)._2, pops(0)))),
          (weightOf(1), None),
          (weightOf(2), Some((ks(2)._2, pops(2))))
        )
      )
    )
    val sig0 = right(MinSig.sign(ks(0)._1, h.bytes))
    val sig2 = right(MinSig.sign(ks(2)._1, h.bytes))
    val good = right(Certificate.aggregate(c, Map(seatId(0) -> sig0, seatId(2) -> sig2)))
    val weight = weightOf(0) + weightOf(2)
    assertEquals(Certificate.verify(c, weight, h, good), Right(weight))
    // quorum
    val tooMuch = weight + Weight.fromNatural(BigInt(1)).get
    assertEquals(
      Certificate.verify(c, tooMuch, h, good),
      Left(BlsError.InsufficientWeight(weight, tooMuch))
    )
    // keyless seat named by a bit
    assertEquals(
      Certificate.aggregate(c, Map(seatId(1) -> sig0)),
      Left(BlsError.SeatHasNoKey(seatId(1)))
    )
    val withKeyless =
      good.copy(signers = right(Signers.of(3, Set(seatId(0), seatId(1), seatId(2)))))
    assertEquals(
      Certificate.verify(c, Weight.zero, h, withKeyless),
      Left(BlsError.SignerWithoutKey(Vector(seatId(1))))
    )
    // bitfield shape: too long, too short, padding bit set, nothing set
    def withBits(raw: Array[Byte]) = good.copy(signers = Signers.fromBytes(Bytes.wrap(raw)).get)
    assertEquals(
      Certificate.verify(c, Weight.zero, h, withBits(Array(0xa0.toByte, 0))),
      Left(BlsError.SignersWrongLength(1, 2))
    )
    assertEquals(
      Certificate.verify(c, Weight.zero, h, withBits(Array.empty)),
      Left(BlsError.SignersWrongLength(1, 0))
    )
    assertEquals(
      Certificate.verify(c, Weight.zero, h, withBits(Array(0xa1.toByte))),
      Left(BlsError.SignersPaddingSet)
    )
    assertEquals(
      Certificate.verify(c, Weight.zero, h, withBits(Array(0x00))),
      Left(BlsError.NoSigners)
    )
    assertEquals(Certificate.aggregate(c, Map.empty), Left(BlsError.NoSigners))
    assertEquals(
      Certificate.aggregate(c, Map(seatId(5) -> sig0)),
      Left(BlsError.SignerNotInCommittee(seatId(5)))
    )
  }

  test("seat ids, signers and committees are bounded by the 16-bit voter id") {
    assertEquals(SeatId.fromInt(-1), Left(BlsError.SeatOutOfRange(-1)))
    assertEquals(SeatId.fromInt(65536), Left(BlsError.SeatOutOfRange(65536)))
    assert(SeatId.fromInt(65535).isRight)
    assertEquals(Signers.fromBytes(Bytes.wrap(new Array[Byte](8193))), None)
    assert(Signers.fromBytes(Bytes.wrap(new Array[Byte](8192))).isDefined)
    val keyless = Vector.fill(65537)((Weight.zero, None))
    assertEquals(Committee.fromSeats(keyless), Left(BlsError.CommitteeTooLarge(65537)))
  }

  test("the CBOR codec is strict: shape, widths, trailing bytes, non-canonical forms") {
    val vote = right(Vote.sign(keys(0)._1, seatId(5), rbHash(2)))
    val enc = vote.encode.toArray
    assertEquals(Vote.decode(Bytes.wrap(enc)), Right(vote))
    assertEquals(
      Vote.decode(Bytes.wrap(enc :+ 0.toByte)),
      Left(BlsError.MalformedCbor(CborFault.Syntax(Cbor.Error.TrailingBytes)))
    )
    assertEquals(
      Vote.decode(Bytes.wrap(enc.take(enc.length - 1))),
      Left(BlsError.MalformedCbor(CborFault.Syntax(Cbor.Error.TruncatedString)))
    )
    // voter id 5 written in the two-byte form 0x18 0x05 instead of 0x05
    val longForm = enc.take(35) ++ Array(0x18.toByte, 0x05.toByte) ++ enc.drop(36)
    assertEquals(
      Vote.decode(Bytes.wrap(longForm)),
      Left(BlsError.MalformedCbor(CborFault.NonCanonicalEncoding))
    )
    // an indefinite-length array
    val indef = Array(0x9f.toByte) ++ enc.drop(1) ++ Array(0xff.toByte)
    assertEquals(
      Vote.decode(Bytes.wrap(indef)),
      Left(BlsError.MalformedCbor(CborFault.NonCanonicalEncoding))
    )
    // wrong arity and wrong types
    assertEquals(
      Vote.decode(Bytes.wrap(Array(0x82.toByte, 0x01, 0x02))),
      Left(BlsError.MalformedCbor(CborFault.WrongArity(3, 2)))
    )
    assertEquals(
      Vote.decode(Bytes.wrap(Array(0x01))),
      Left(BlsError.MalformedCbor(CborFault.NotAnArray))
    )
    assertEquals(
      Certificate.decode(Bytes.wrap(Array(0x82.toByte, 0x01, 0x02))),
      Left(BlsError.MalformedCbor(CborFault.FieldNotBytes(0)))
    )
    // a 31-byte hash
    val shortHash = bs(0x83, 0x58, 31) ++ new Array[Byte](31) ++ bs(
      0x01,
      0x58,
      48
    ) ++ vote.signature.bytes.toArray
    assertEquals(
      Vote.decode(Bytes.wrap(shortHash)),
      Left(BlsError.MalformedCbor(CborFault.HashNotThirtyTwoBytes(31)))
    )
    // a seat above 16 bits
    val bigSeat = bs(0x83, 0x58, 32) ++ new Array[Byte](32) ++ bs(0x1a, 0, 1, 0, 0, 0x58,
      48) ++ vote.signature.bytes.toArray
    assertEquals(
      Vote.decode(Bytes.wrap(bigSeat)),
      Left(BlsError.MalformedCbor(CborFault.SeatAbove16Bits))
    )
  }

  property("votes round-trip through CBOR") {
    forAll(genSeat, genHash) { (i, h) =>
      val vote = right(Vote.sign(keys(i)._1, seatId(i), h))
      assertEquals(Vote.decode(vote.encode), Right(vote))
    }
  }

  property(
    "committee seating: stake descending, ties by pool id ascending, truncated (model check)"
  ) {
    val genCand = Gen.listOfN(12, Gen.zip(Gen.choose(0, 5), Gen.choose(0, 255))).map { xs =>
      xs.distinctBy(_._2)
        .map { case (stake, id) =>
          Candidate(
            Bytes.wrap(Array.fill[Byte](28)(id.toByte)),
            Weight.fromNatural(BigInt(stake)).get,
            None
          )
        }
        .toVector
    }
    forAll(genCand, Gen.choose(0, 14)) { (cands, n) =>
      val c = right(Committee.select(cands, n))
      val expected = cands.sortBy(x => (-x.stake.value.toInt, x.poolId.hex)).take(n).map(_.stake)
      assertEquals(c.seats.map(_.weight), expected)
      // the order of the input does not matter
      assertEquals(right(Committee.select(cands.reverse, n)).seats.map(_.weight), expected)
    }
  }

  test("quorum threshold is an exact ceiling") {
    val c = right(Committee.fromSeats(Vector.fill(3)((Weight.fromNatural(BigInt(10)).get, None))))
    assertEquals(c.totalWeight.value, BigInt(30))
    val total = c.totalWeight
    assertEquals(Weight.fractionCeil(total, 2, 3).map(_.value), Some(BigInt(20)))
    assertEquals(Weight.fractionCeil(total, 1, 7).map(_.value), Some(BigInt(5)))
    assertEquals(Weight.fractionCeil(total, 1, 0), None)
    assertEquals(Weight.fractionCeil(total, -1, 2), None)
  }
end LeiosCertificateSuite
