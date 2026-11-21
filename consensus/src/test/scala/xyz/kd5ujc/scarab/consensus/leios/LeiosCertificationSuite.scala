// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, StateGen}
import xyz.kd5ujc.scarab.crypto.bls.{
  BlsError,
  Certificate,
  MinSig,
  RbHash,
  SeatId,
  Signature,
  Signers,
  VerificationError,
  Weight
}
import xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.{key, rbHash, right}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  Dijkstra,
  EpochNo,
  LedgerStateGen,
  LeiosCommittee,
  LeiosSeat,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  SnapShots,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

/** The checks `Forker.applyBlock` runs on a certifying block before it applies the certified
  * endorser block (ouroboros-consensus 2416493b `Storage/LedgerDB/Forker.hs:631-666`): each of the
  * four failures, the order in which they are reached, and a certificate that passes.
  */
class LeiosCertificationSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(20)

  private val seats = 5
  private lazy val keys = Vector.tabulate(seats)(i => key(300 + i))

  /** Five seats of exact stake ratios 1/10, 2/10, ..., the third keyless (a pool whose key expired
    * or whose proof failed), as the ledger stores them.
    */
  private lazy val seated: LeiosCommittee = LeiosCommittee(
    keys.zipWithIndex.map { case ((_, vk), i) =>
      LeiosSeat(Rational(i + 1, 10), Option.when(i != 2)(vk.bytes))
    }
  )
  private val quorum = Rational(1, 2)
  private lazy val tip: TipCommittee = right(LeiosCertification.tipCommittee(seated, quorum))

  private def seat(i: Int): SeatId = right(SeatId.fromInt(i))

  private def certify(hash: RbHash, signers: Set[Int]): Certificate =
    right(
      Certificate.aggregate(
        tip.committee,
        signers.map(i => seat(i) -> right(MinSig.sign(keys(i)._1, hash.signable))).toMap
      )
    )

  private val announcing: RbHash = rbHash(5)
  // seats 1, 3 and 4: 2/10 + 4/10 + 5/10 = 11/10 of the stake ratios, at least the quorum of 1/2
  private lazy val good: Certificate = certify(announcing, Set(1, 3, 4))

  private val parents: Gen[(Praos2.State, Praos2.AnnouncedBy)] =
    for
      praos <- StateGen.praosState
      announced <- StateGen.announcedBy
    yield (Praos2.State(praos, Some(announced)), announced)

  private def pointOf(state: Praos2.State, announced: Praos2.AnnouncedBy): EbPoint =
    val slot = state.praos.lastSlot match
      case WithOrigin.At(s)  => s.toBigInt
      case WithOrigin.Origin => BigInt(0)
    EbPoint(right(Word64.from(slot)), announced.announcement.hash)

  private def check(
      parent: ChainDepState,
      previous: Option[Bytes],
      committee: Option[TipCommittee],
      cert: Certificate
  ) = LeiosCertification.check(parent, previous, committee, cert)

  property("a certificate of enough signers over the parent's hash certifies the announcement") {
    forAll(parents) { (state, announced) =>
      assertEquals(
        check(ChainDepState.Praos2(state), Some(announcing.bytes), Some(tip), good),
        Right(Right(Certified(pointOf(state, announced), announcing, Weight(Rational(11, 10)))))
      )
    }
  }

  property("1. no announcement in the parent's state: refused first, whatever else holds") {
    val states = Gen.oneOf(
      StateGen.praosState.map(p => ChainDepState.Praos2(Praos2.State(p, None))),
      StateGen.praosState.map(ChainDepState.Praos(_)),
      StateGen.chainDepState(xyz.kd5ujc.scarab.protocol.Era.Byron),
      StateGen.chainDepState(xyz.kd5ujc.scarab.protocol.Era.Alonzo)
    )
    forAll(states, Gen.option(Gen.const(announcing.bytes)), Gen.option(Gen.const(tip))) {
      (parent, previous, committee) =>
        assertEquals(
          check(parent, previous, committee, good),
          Right(Left(LeiosExtValidationError.LeiosCertificateWithoutAnnouncement(good)))
        )
    }
  }

  property("2. no parent: refused as certifying at genesis, before the committee is looked up") {
    forAll(parents, Gen.option(Gen.const(tip))) { case ((state, announced), committee) =>
      assertEquals(
        check(ChainDepState.Praos2(state), None, committee, good),
        Right(
          Left(
            LeiosExtValidationError.LeiosCertificateAfterGenesis(good, pointOf(state, announced))
          )
        )
      )
    }
  }

  property("3. no committee at the parent's tip: refused before the certificate is read") {
    forAll(parents) { (state, announced) =>
      val garbage = good.copy(signers = Signers.fromBytes(Bytes.wrap(Array[Byte](1, 2, 3))).get)
      assertEquals(
        check(ChainDepState.Praos2(state), Some(announcing.bytes), None, garbage),
        Right(
          Left(LeiosExtValidationError.LeiosMissingCommittee(pointOf(state, announced), garbage))
        )
      )
    }
  }

  property("4. a certificate verifyLeiosCert refuses, with the reference's reason") {
    forAll(parents)((state, _) => refusals(ChainDepState.Praos2(state)))
  }

  private def refusals(parent: ChainDepState): Unit =
    def refused(cert: Certificate, over: RbHash, committee: TipCommittee = tip) =
      check(parent, Some(over.bytes), Some(committee), cert) match
        case Right(Left(LeiosExtValidationError.LeiosInvalidCertificate(c, _, h, error))) =>
          assertEquals((c, h), (cert, over))
          Some(error)
        case other => fail(s"not refused as an invalid certificate: $other")
    // signed over another block's hash
    assertEquals(refused(good, rbHash(6)), Some(VerificationError.InvalidSignature))
    // another set of signers under the same signature
    val moved = good.copy(signers = right(Signers.of(seats, Set(seat(0), seat(3), seat(4)))))
    assertEquals(refused(moved, announcing), Some(VerificationError.InvalidSignature))
    // another signature under the same signers
    val other = good.copy(signature = certify(announcing, Set(1, 3)).signature)
    assertEquals(refused(other, announcing), Some(VerificationError.InvalidSignature))
    // a bit on the keyless seat
    val keyless = good.copy(signers = right(Signers.of(seats, Set(seat(1), seat(2), seat(4)))))
    assertEquals(
      refused(keyless, announcing),
      Some(VerificationError.SignerWithoutKey(cats.data.NonEmptyVector.one(seat(2))))
    )
    // too little weight: seats 0 and 1 are 3/10 of the stake ratios
    val light = certify(announcing, Set(0, 1))
    assertEquals(
      refused(light, announcing),
      Some(VerificationError.InsufficientWeight(Weight(Rational(3, 10))))
    )
    // a bitfield of another length
    val long = good.copy(signers = Signers.fromBytes(Bytes.wrap(Array[Byte](0x58, 0))).get)
    assertEquals(refused(long, announcing), Some(VerificationError.MalformedSigners))
    // the threshold is the quorum, exactly: 11/10 is enough for 11/10 and not for more
    val exact = tip.copy(threshold = Weight(Rational(11, 10)))
    assertEquals(
      check(parent, Some(announcing.bytes), Some(exact), good).map(_.isRight),
      Right(true)
    )
    val more = tip.copy(threshold = Weight(Rational(11, 10) + Rational(1, BigInt(10).pow(40))))
    assertEquals(
      refused(good, announcing, more),
      Some(VerificationError.InsufficientWeight(Weight(Rational(11, 10))))
    )

  property(
    "an identity signature decodes, as the reference's does, and is refused as a signature"
  ) {
    // the compressed identity of G1: the compression and infinity flags, then zeros
    val identity =
      right(Signature.decoded(Bytes.wrap(Array[Byte](0xc0.toByte) ++ Array.fill[Byte](47)(0))))
    val cert = good.copy(signature = identity)
    forAll(parents) { (state, announced) =>
      assertEquals(
        check(ChainDepState.Praos2(state), Some(announcing.bytes), Some(tip), cert),
        Right(
          Left(
            LeiosExtValidationError.LeiosInvalidCertificate(
              cert,
              pointOf(state, announced),
              announcing,
              VerificationError.InvalidSignature
            )
          )
        )
      )
    }
  }

  property(
    "an empty Dijkstra committee is a committee: the certificate is invalid, not unchecked"
  ) {
    val empty = right(LeiosCertification.tipCommittee(LeiosCommittee.empty, quorum))
    forAll(parents) { (state, _) =>
      def verdict(c: Certificate) =
        check(ChainDepState.Praos2(state), Some(announcing.bytes), Some(empty), c) match
          case Right(Left(LeiosExtValidationError.LeiosInvalidCertificate(_, _, _, e))) => Some(e)
          case _                                                                        => None
      assertEquals(verdict(good), Some(VerificationError.MalformedSigners))
      val none = good.copy(signers = Signers.fromBytes(Bytes.empty).get)
      assertEquals(verdict(none), Some(VerificationError.InsufficientWeight(Weight.zero)))
    }
  }

  property("padding bits of the bitfield name no seat and do not refuse the certificate") {
    forAll(parents) { (state, _) =>
      // seats 1, 3 and 4 are 0101 1, then the three padding bits of a five-seat committee set
      val padded = good.copy(signers = Signers.fromBytes(Bytes.wrap(Array[Byte](0x5f))).get)
      assertEquals(good.signers.bytes, Bytes.wrap(Array[Byte](0x58)))
      assertEquals(
        check(ChainDepState.Praos2(state), Some(announcing.bytes), Some(tip), padded)
          .map(_.map(_.weight)),
        Right(Right(Weight(Rational(11, 10))))
      )
    }
  }

  property("an announcement at Origin is at slot 0, and a hash not of 32 bytes is a defect") {
    forAll(parents)((state, announced) => edges(state, announced))
  }

  private def edges(state: Praos2.State, announced: Praos2.AnnouncedBy): Unit =
    val atOrigin = state.copy(praos = state.praos.copy(lastSlot = WithOrigin.Origin))
    assertEquals(
      LeiosCertification.announcement(ChainDepState.Praos2(atOrigin)),
      Some(EbPoint(Word64.fromUnsigned(0L), announced.announcement.hash))
    )
    val wide =
      Praos2.State(
        atOrigin.praos.copy(lastSlot = WithOrigin.At(SlotNo.fromUnsigned(-1L))),
        Some(announced)
      )
    assertEquals(
      LeiosCertification.announcement(ChainDepState.Praos2(wide)).map(_.slot.toBigInt),
      Some(Word64.Max)
    )
    assertEquals(
      check(
        ChainDepState.Praos2(atOrigin),
        Some(Bytes.wrap(Array.fill[Byte](28)(1))),
        Some(tip),
        good
      ),
      Left(LeiosCertificationDefect.PreviousHashWidth(28))
    )

  test("the tip's committee: each seat its stake ratio and stored key, the quorum the threshold") {
    assertEquals(tip.committee.seats.map(_.weight), seated.seats.map(s => Weight(s.weight)))
    assertEquals(tip.committee.seats.map(_.key.map(_.bytes)), seated.seats.map(_.verificationKey))
    assertEquals(tip.threshold, Weight(quorum))
    val broken = LeiosCommittee(
      seated.seats.updated(3, LeiosSeat(Rational(1, 10), Some(Bytes.wrap(Array.fill[Byte](96)(0)))))
    )
    assert(
      LeiosCertification.tipCommittee(broken, quorum) match
        case Left(LeiosCertificationDefect.UndecodableSeatKey(3, _: BlsError)) => true
        case _                                                                 => false
    )
  }

  test("leiosCommitteeOfTip: the set snapshot's committee in Dijkstra, none before it") {
    val epoch = right(EpochNo.fromBigInt(4))
    val params = LedgerStateGen.params.copy(leiosQuorumStakeThreshold = quorum)
    val dijkstra =
      given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra
      val empty = NewEpochState.empty[Dijkstra](epoch, params)
      val layout = SnapShots.Layout.Leios(epoch, BigInt(5), BigInt(10), seated)
      empty.copy(es = empty.es.copy(snapshots = empty.es.snapshots.copy(layout = layout)))
    assertEquals(
      LeiosCertification
        .leiosCommitteeOfTip(dijkstra)(using ShelleyBasedEra.SDijkstra)
        .map(_.map(t => (t.committee.seats, t.threshold))),
      Right(Some((tip.committee.seats, tip.threshold)))
    )
    val conway = NewEpochState.empty[Conway](epoch, params)(using ShelleyBasedEra.SConway)
    assertEquals(
      LeiosCertification.leiosCommitteeOfTip(conway)(using ShelleyBasedEra.SConway).map(_.isEmpty),
      Right(true)
    )
    val dijkstra11 = NewEpochState.empty[Dijkstra](epoch, params)(using ShelleyBasedEra.SDijkstra)
    assertEquals(
      LeiosCertification
        .leiosCommitteeOfTip(dijkstra11)(using ShelleyBasedEra.SDijkstra)
        .map(_.isEmpty),
      Left(LeiosCertificationDefect.CommitteeOfNode11_1)
    )
  }
end LeiosCertificationSuite
