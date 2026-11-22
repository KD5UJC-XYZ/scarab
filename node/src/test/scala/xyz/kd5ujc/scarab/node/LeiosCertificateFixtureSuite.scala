// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.{BlockRef, Praos2}
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.consensus.leios.{
  Certified,
  LeiosCertification,
  LeiosExtValidationError,
  TipCommittee
}
import xyz.kd5ujc.scarab.consensus.state.{ChainDepState, MirrorState}
import xyz.kd5ujc.scarab.crypto.bls.{Certificate, RbHash, Signers, VerificationError, Weight}
import cats.mtl.Raise
import xyz.kd5ujc.scarab.consensus.leios.CertifyDecision
import xyz.kd5ujc.scarab.ledger.{Header, LeiosBlock, LeiosHeader, NodeError, StorageError}
import xyz.kd5ujc.scarab.node.forge.{BlockBuilder, DijkstraForge, DijkstraParent, LeiosCertifying}
import xyz.kd5ujc.scarab.protocol.EbHash
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, ShelleyBasedEra, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{EbPoint, NtNHeader, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile

/** The Leios checks of block application (`Forker.applyBlock`, ouroboros-consensus 2416493b
  * `Storage/LedgerDB/Forker.hs:631-666`, [[LeiosCertification.check]]) on a certifying block the
  * reference adopted: block 97,276 of the public Leios testnet
  * (`fixtures/leios/testnet/blocks-after-2168639.tsv`), whose parent 97,275 announced the endorser
  * block it certifies.
  *
  *   - The parent's chain-dependent state is the one the node's header rules reach from the
  *     testnet's state at slot 2,168,639 (`fixtures/leios/state/testnet-2168639`, block 97,273)
  *     through the headers of 97,274 and 97,275 (`headers-after-2168639.tsv`).
  *   - The committee is that state's: the three blocks are in epoch 100, as the state is, and a
  *     block within an epoch changes neither the set snapshot nor the parameters, so the committee
  *     of 97,275's state is the committee of 97,273's.
  */
class LeiosCertificateFixtureSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)
  private val leios = Path.of("fixtures", "leios")
  private def rows(file: String): Vector[Array[String]] =
    Files
      .readAllLines(leios.resolve("testnet").resolve(file))
      .asScala
      .toVector
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map(_.split('\t'))

  private lazy val setup = NodeSetup
    .fromConfigFile(ReferenceBuild.LeiosB004fe0c)(leios.resolve("testnet/config/config.json"))
    .fold(e => fail(e.message), identity)
  private lazy val anchor: MirrorState =
    val raw = Bytes.wrap(Files.readAllBytes(leios.resolve("state/testnet-2168639/state")))
    val file =
      HaskellLedgerFile.decode(raw, StateLayout.Leios).fold(e => fail(e.render), identity)
    setup.mirrorStart.copy(ext = file.ext.getOrElse(fail("no Shelley-based ledger")))
  private val anchorTip = BlockRef(
    BigInt(2168639),
    hex("aed73a1050263343a16d9a980b2f6dce1152c2e959b69987230a9e5f4844f619"),
    BigInt(97273)
  )
  private lazy val rules = NodeHeaderRules[MirrorState](
    setup.praos,
    setup.globals.copy(referenceBuild = ReferenceBuild.LeiosB004fe0c),
    setup.byronRules,
    setup.handOver
  )

  /** The headers of 97,274 and 97,275, read from their ChainSync envelopes. */
  private lazy val toParent: Vector[Header] = rows("headers-after-2168639.tsv").take(2).map { r =>
    NtNHeader.codec
      .decode(hex(r(4)))
      .left
      .map(_.toString)
      .flatMap(Header.fromNtN(_, setup.geometry.byron.epochSlots).left.map(_.message))
      .fold(e => fail(e), identity)
  }

  /** The chain-dependent state after 97,275: the parent of the certifying block. */
  private lazy val parent: Praos2.State =
    val start = rules.at(anchor) match
      case s: NodeHeaderState.Shelley => s.copy(previous = Some(anchorTip))
      case other                      => fail(s"not a Shelley header state: $other")
    val end = toParent.foldLeft(start) { (state, h) =>
      rules.validate(anchor, Some(anchorTip), state, h) match
        case Outcome.Valid(next) => next
        case other               => fail(s"block ${h.blockNo}: $other")
    }
    end.protocol.map(_.dep) match
      case Some(ChainDepState.Praos2(p)) => p
      case other                         => fail(s"not a Praos2 state: $other")

  private lazy val certifying: LeiosBlock =
    rows("blocks-after-2168639.tsv") match
      case Vector(r) =>
        LeiosBlock.decode(hex(r(5))).fold(e => fail(e.message), identity)
      case other => fail(s"not one block: ${other.size}")
  private lazy val cert: Certificate =
    certifying.leiosCertificate.map(_.certificate).getOrElse(fail("no certificate"))

  private lazy val tip: TipCommittee =
    val nes: NewEpochState[Dijkstra] = anchor.ledger(None).toOption.flatten match
      case Some(i: InEra.Of[e]) =>
        i.era match
          case ShelleyBasedEra.SDijkstra => i.read.nes.fold(e => fail(e.toString), identity)
          case other                     => fail(s"not in Dijkstra: $other")
      case other => fail(s"no Shelley-based ledger: $other")
    LeiosCertification
      .leiosCommitteeOfTip(nes)(using ShelleyBasedEra.SDijkstra)
      .fold(e => fail(e.toString), _.getOrElse(fail("no committee in Dijkstra")))

  private def rbHash(b: Bytes): RbHash = RbHash.fromBytes(b).getOrElse(fail("not 32 bytes"))

  private def check(c: Certificate, committee: TipCommittee = tip) =
    LeiosCertification.check(
      ChainDepState.Praos2(parent),
      certifying.header.prevHash,
      Some(committee),
      c
    )

  private def refusal(c: Certificate, committee: TipCommittee = tip): VerificationError =
    check(c, committee) match
      case Right(Left(LeiosExtValidationError.LeiosInvalidCertificate(_, _, _, error))) => error
      case other => fail(s"not refused as an invalid certificate: $other")

  test("the block is the certifying child of the announcing block, in the state's epoch") {
    assertEquals(certifying.header.blockNo, BigInt(97276))
    assertEquals(certifying.header.slot, BigInt(2168714))
    assertEquals(
      certifying.header.hash,
      hex("6668d781d704fa5b2155bad2678e3a56e5cf5cddf4ecca547423dd4049a371eb")
    )
    assert(certifying.header.leios.blockBodyContainsLeiosCert)
    assertEquals(certifying.transactions.size, 0)
    assertEquals(certifying.header.prevHash, Some(toParent(1).hash))
    assertEquals(parent.praos.lastSlot, WithOrigin.At(SlotNo.fromUnsigned(2168669L)))
    // epochs of 21,600 slots: 2,168,639 to 2,168,714 are all of epoch 100
    assertEquals(
      Vector(anchorTip.slot, certifying.header.slot).map(_ / 21600),
      Vector.fill(2)(BigInt(100))
    )
    assertEquals(tip.committee.size, 154)
    assertEquals(tip.threshold, Weight(Rational(3, 4)))
  }

  test("the certificate the reference adopted passes, certifying the parent's announcement") {
    val announced = parent.announcement.getOrElse(fail("the parent announced nothing"))
    val point = EbPoint(Word64.fromUnsigned(2168669L), announced.announcement.hash)
    check(cert) match
      case Right(Right(Certified(p, h, weight))) =>
        assertEquals(p, point)
        assertEquals(Some(h.bytes), certifying.header.prevHash)
        assert(Ordering[Weight].gteq(weight, tip.threshold), s"$weight")
      case other => fail(s"not certified: $other")
  }

  test("tampered, the certificate is refused as verifyLeiosCert refuses it") {
    val members = cert.signers.members(tip.committee.size).getOrElse(fail("malformed signers"))
    // one signer fewer: below the quorum, and with none required the signature no longer matches
    val fewer = Signers.of(154, members.tail.toSet).fold(e => fail(e.toString), identity)
    assert(refusal(cert.copy(signers = fewer)) match
      case VerificationError.InsufficientWeight(_) => true
      case _                                       => false)
    assertEquals(
      refusal(cert.copy(signers = fewer), tip.copy(threshold = Weight.zero)),
      VerificationError.InvalidSignature
    )
    // the signature of another certificate
    val other = rows("blocks-rc-2026w44.tsv").find(_(0) == "certifying").getOrElse(fail("64850"))
    val foreign = LeiosBlock
      .decode(hex(other(5)))
      .toOption
      .flatMap(_.leiosCertificate)
      .getOrElse(fail("no certificate in 64,850"))
      .certificate
    assertEquals(
      refusal(cert.copy(signature = foreign.signature)),
      VerificationError.InvalidSignature
    )
    // a bitfield one byte short
    val short = Signers.fromBytes(cert.signers.bytes.take(19)).getOrElse(fail("signers"))
    assertEquals(refusal(cert.copy(signers = short)), VerificationError.MalformedSigners)
    // over another block's hash: the announcing block's own parent, 97,274
    assertEquals(
      LeiosCertification.check(
        ChainDepState.Praos2(parent),
        Some(toParent(0).hash),
        Some(tip),
        cert
      ) match
        case Right(Left(LeiosExtValidationError.LeiosInvalidCertificate(_, _, h, e))) =>
          (h.bytes, e)
        case unexpected => fail(s"$unexpected")
      ,
      (rbHash(toParent(0).hash).bytes, VerificationError.InvalidSignature)
    )
    // a threshold above the signers' weight
    val weight = check(cert).toOption.flatMap(_.toOption).map(_.weight).getOrElse(fail("weight"))
    val above = tip.copy(threshold = Weight(weight.value + Rational(1, BigInt(10).pow(30))))
    assertEquals(refusal(cert, above), VerificationError.InsufficientWeight(weight))
    // a bit on a seat made keyless
    val first = members.head
    val keyless = TipCommittee(
      xyz.kd5ujc.scarab.crypto.bls.Committee
        .honoured(
          tip.committee.seats
            .updated(first.index, tip.committee.seats(first.index).copy(key = None))
        )
        .fold(e => fail(e.toString), identity),
      tip.threshold
    )
    assertEquals(
      refusal(cert, keyless),
      VerificationError.SignerWithoutKey(NonEmptyVector.one(first))
    )
  }

  test("without the parent's announcement, without a parent, or without a committee: refused so") {
    assertEquals(
      LeiosCertification.check(
        ChainDepState.Praos2(parent.copy(announcement = None)),
        certifying.header.prevHash,
        Some(tip),
        cert
      ),
      Right(Left(LeiosExtValidationError.LeiosCertificateWithoutAnnouncement(cert)))
    )
    val point =
      LeiosCertification.announcement(ChainDepState.Praos2(parent)).getOrElse(fail("none"))
    assertEquals(
      LeiosCertification.check(ChainDepState.Praos2(parent), None, Some(tip), cert),
      Right(Left(LeiosExtValidationError.LeiosCertificateAfterGenesis(cert, point)))
    )
    assertEquals(
      LeiosCertification
        .check(ChainDepState.Praos2(parent), certifying.header.prevHash, None, cert),
      Right(Left(LeiosExtValidationError.LeiosMissingCommittee(point, cert)))
    )
  }
  // the forger's decision on the parent and the block it assembles of it (`decideLeiosCertify`,
  // `mkBody`, `mkHeader`: ouroboros-consensus 2416493b `NodeKernel/Forge.hs:324-396`,
  // `Shelley/Ledger/Forge.hs:90-160`): what the fixtures hold of 97,276 is reproduced; its KES
  // signature, the leader proof and the endorser block it announces (whose transactions the
  // fixtures do not hold) are the recorded ones, not made here
  test("the forger's decision on the parent and its body reproduce block 97,276, byte for byte") {
    type F[A] = Either[Fatal, A]
    given Raise[F, StorageError] = new Raise[F, StorageError]:
      def functor = cats.Functor[F]
      def raise[E <: StorageError, A](e: E): F[A] = Left(Fatal.Storage(e))
    val raw =
      rows("blocks-after-2168639.tsv").headOption.map(r => hex(r(5))).getOrElse(fail("no row"))
    val header = certifying.header
    val parentRef = BlockRef(BigInt(2168669), toParent(1).hash, BigInt(97275))
    val nes: NewEpochState[Dijkstra] = anchor.ledger(None).toOption.flatten match
      case Some(i: InEra.Of[e]) =>
        i.era match
          case ShelleyBasedEra.SDijkstra => i.read.nes.fold(e => fail(e.toString), identity)
          case other                     => fail(s"not in Dijkstra: $other")
      case other => fail(s"no Shelley-based ledger: $other")
    // the vote state holds the recorded certificate for the parent, the store the closure
    val certifyingSource = new LeiosCertifying[F, MirrorState]:
      def closureHeld(eb: EbHash)(using Raise[F, StorageError]): F[Boolean] = Right(true)
      def certificate(rb: RbHash): F[Option[Certificate]] =
        Right(Option.when(Some(rb.bytes) == header.prevHash)(cert))
      def closed(
          chain: MirrorState,
          t: Option[BlockRef],
          p: NewEpochState[Dijkstra],
          point: EbPoint,
          slot: BigInt
      )(using Raise[F, StorageError]): F[Either[NodeError, NewEpochState[Dijkstra]]] =
        Right(Right(p))
    // the parent's ledger state is 97,273's within its epoch for what the decision reads of it:
    // the parameters, and so the certification gap
    val parentOf = DijkstraParent(
      anchor,
      Some(parentRef),
      nes,
      nes,
      LeiosCertification.announcement(ChainDepState.Praos2(parent))
    )
    val point = parentOf.announced.getOrElse(fail("the parent announced nothing"))
    assertEquals(
      DijkstraForge.decide[F, MirrorState](
        header.slot,
        BigInt(setup.geometry.slotLengthMillis),
        parentOf,
        certifyingSource
      ),
      Right(CertifyDecision.Certify(cert, point))
    )
    // a slot short of the testnet's gap of 14 slots, too early
    assertEquals(
      DijkstraForge.decide[F, MirrorState](
        point.slot.toBigInt + 13,
        BigInt(setup.geometry.slotLengthMillis),
        parentOf,
        certifyingSource
      ),
      Right(CertifyDecision.TooEarly(point, BigInt(14)))
    )
    val body = BlockBuilder.Assembly.dijkstra(Vector.empty, Some(cert), None) match
      case BlockBuilder.Assembly.InDijkstra(b, _) => b
      case other                                  => fail(s"not a Dijkstra body: $other")
    assertEquals(body.size, certifying.bodySize)
    assertEquals(body.hash, header.bodyHash)
    val headerBody = BlockBuilder.leiosHeaderBody(
      header.blockNo,
      header.slot,
      header.prevHash,
      header.issuerVkey,
      header.vrfVkey,
      header.vrf,
      body,
      header.leios.announcement,
      header.opCert,
      (header.leios.versionInfo.highestSupportedMajor, header.leios.versionInfo.softwareTag)
    )
    assertEquals(headerBody.encode, header.signable)
    assertEquals(
      LeiosBlock.encode(LeiosHeader.signed(headerBody.encode, header.kesSignature), body.encode),
      raw
    )
  }
end LeiosCertificateFixtureSuite
