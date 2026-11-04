// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.kes.{Sum6Kes, Sum6KesSigning}
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
import xyz.kd5ujc.scarab.protocol.Era

/** Babbage and Conway headers are Praos headers, whose `HeaderRaw` and `HeaderBody` are `RecD`
  * records (`libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:203-229`,
  * cardano-ledger f649f975); Shelley to Alonzo headers are TPraos headers read by
  * `decodeRecordNamed` (`TPraos/BlockHeader.hs:193-241`). The KES signature is over the header body
  * encoded again (`getSignableRepresentation`, `Praos/BlockHeader.hs:113-117`,
  * `TPraos/BlockHeader.hs:170-171`). The Leios header follows the build of the Leios testnet
  * instead, and its cases are `LeiosTestnetBlockReferenceSuite` 's (#711).
  *
  * `fixtures/reference/header-decoding/cases.jsonl` holds headers, header bodies and blocks of each
  * form, definite, indefinite, detached and with non-minimal heads, with the verdict of the
  * unchanged released decoders and, for an accepted one, the header hash and the bytes the KES
  * signature is checked over. Scarab must give the same verdict, hash the same bytes and sign the
  * same bytes. Evidence for #698. The reference reads a header with no budget of items, string
  * bytes or nesting: a Byron epoch boundary header with 200 attributes is read whole (#749), as is
  * a Leios header written in hundreds of chunks (`LeiosTestnetBlockReferenceSuite`).
  */
class HeaderDecodingReferenceSuite extends FunSuite:
  import HeaderDecodingReferenceSuite.*

  private val rows: Vector[Row] = load

  /** A header body alone is read as the body of a definite header with a signature: the reference
    * reads `HeaderRaw` 's body with the same decoder (`From`), and the signature is read after it.
    */
  private def scarab(row: Row): Either[ChainError, Verdict] = (row.era, row.kind) match
    case (_, Kind.VrfCheck) => fail(s"${row.name} is a VRF check, read by vrfCheck")
    case (era, Kind.Header) =>
      Header.fromRaw(era, row.raw).map(h => Verdict(h.hash, h.signable, None))
    case (era, Kind.HeaderBody) =>
      Header.fromRaw(era, signed(row.raw)).map(h => Verdict(h.hash, h.signable, None))
    // a Byron boundary header is read as consensus reads it from ChainSync; the corpus records only
    // its verdict, so the bytes it would sign are none
    case (Era.Byron, Kind.BoundaryHeader) =>
      Header
        .byronFromRaw(row.raw, boundary = true, ByronEpochSlots)
        .map(h => Verdict(h.hash, Bytes.empty, None))
    case (Era.Byron, Kind.MainHeader) =>
      Header
        .byronFromRaw(row.raw, boundary = false, ByronEpochSlots)
        .map(h => Verdict(h.hash, Bytes.empty, None))
    case (Era.Byron, kind) => fail(s"${row.name}: a Byron $kind is not in the corpus")
    case (era, Kind.BoundaryHeader) =>
      fail(s"${row.name}: a boundary header of $era is not in the corpus")
    case (era, Kind.MainHeader) =>
      fail(s"${row.name}: a Byron main header of $era is not in the corpus")
    case (era, Kind.Block) =>
      Block.decodeShelley(hex("82" + f"${era.blockTag}%02x") ++ row.raw).flatMap {
        case b: Block.Shelley => Right(Verdict(b.header.hash, b.header.signable, Some(b.bodyHash)))
        case _                => Left(Header.fail(LedgerDecodeError.ByronLayout))
      }

  test("the corpus covers every protocol and form, refused and accepted") {
    assertEquals(
      rows.map(_.era).toSet,
      Set(Era.Byron, Era.Shelley, Era.Alonzo, Era.Babbage, Era.Conway)
    )
    assertEquals(rows.map(_.kind).toSet, Kind.values.toSet)
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for era <- rows.map(_.era).distinct do
      assert(rows.exists(r => r.era == era && r.accepted), s"no accepted $era case")
      assert(rows.exists(r => r.era == era && !r.accepted), s"no refused $era case")
    // the signed bytes differ from the body's original bytes in some accepted case of each
    // protocol: the cases where reading the original bytes would check another signature
    for era <- rows.map(_.era).distinct if era != Era.Byron do
      assert(
        rows.exists(r =>
          r.era == era && r.kind == Kind.Header && r.signable.exists(s => !r.raw.hex.contains(s))
        ),
        s"no $era case whose signed bytes are not in the input"
      )
  }

  /** A certificate of the row's key with each output the reference tried: it decodes as the
    * reference decodes it, and Scarab's VRF check verifies it exactly when `verifyCertified` does,
    * refusing (never as a provider defect) every other output (#712).
    */
  private def vrfCheck(row: Row, era: Era, vrf: VrfRow): Unit =
    val message = row.raw.drop(32)
    val proof = hex(vrf.proof)
    for check <- vrf.checks do
      val output = check.length match
        case 64 => hex(vrf.output)
        case 0  => Bytes.empty
        case 65 => hex(vrf.output) ++ hex("00")
        case _  => hex(vrf.output).take(check.length)
      assertEquals(output.size, check.length)
      val encoded = hex("82") ++ bstr(output) ++ bstr(proof)
      val cert = CborRead
        .decode(encoded)
        .leftMap(Header.fail)
        .flatMap(Header.vrfCert(_, ByteStrings.of(DecoderVersion.of(era))))
      assertEquals(
        cert.isRight,
        check.decoded,
        s"${row.name}: decoding an output of ${check.length}"
      )
      cert.foreach { c =>
        val result = Verdicts.Direct.tpraosVrf(message, hex(vrf.vk), c.proof, c.output)
        assertEquals(ProviderDefect.ofVrf(result), None, s"${row.name}: ${check.length}")
        assertEquals(
          result == Vrf.Result.VerifiedCertificate(output),
          check.verified.contains(true),
          s"${row.name}: the VRF check of an output of ${check.length}: $result"
        )
      }

  for row <- rows if row.kind == Kind.VrfCheck do
    test(row.name) {
      row.vrf match
        case Some(vrf) => vrfCheck(row, row.era, vrf)
        case None      => fail(s"a VRF check needs its result: ${row.name}")
    }

  for row <- rows if row.kind != Kind.VrfCheck do
    test(row.name) {
      scarab(row) match
        case Right(verdict) =>
          assert(row.accepted, s"the reference refuses this encoding: ${row.failure}")
          row.signable.foreach(s => assertEquals(verdict.signable.hex, s, "the signed bytes"))
          row.hash.foreach(h => assertEquals(verdict.hash.hex, h, "the header hash"))
          if row.kind == Kind.Header then
            assertEquals(verdict.hash, Blake2b.hash256.hash(row.raw), "the hash of the input")
          row.bodyHash.foreach(h =>
            assertEquals(verdict.bodyHash.map(_.hex), Some(h), "the block body hash")
          )
        case Left(e) => assert(!row.accepted, s"the reference accepts this encoding: $e")
    }

  /** A KES key of its own signs a header body as the reference does, the canonical encoding; the
    * header carries the body in another form. Its signature checks; a signature over the body's
    * original bytes does not.
    */
  private def signedOver(era: Era, form: Bytes => Bytes): Unit =
    val key = Sum6KesSigning.generate(hex("02" * 32)).fold(e => fail(e.toString), identity)
    val vrf = VrfCert(hex("11" * 64), hex("12" * 80))
    val fields = HeaderBodyEncoding.Fields(
      BigInt(42),
      BigInt(1000),
      Some(hex("13" * 32)),
      hex("14" * 32),
      hex("15" * 32),
      if era.usesTPraos then HeaderVrf.TPraos(vrf, vrf) else HeaderVrf.Praos(vrf),
      BigInt(1024),
      hex("16" * 32),
      Header.OpCert(key.verificationKey, BigInt(0), BigInt(0), hex("17" * 64)),
      (if era.usesTPraos then BigInt(6) else BigInt(10), BigInt(0))
    )
    val canonical = HeaderBodyEncoding.encode(fields)
    val original = form(canonical)
    assertNotEquals(original, canonical)
    def header(signature: Bytes): Header.Shelley =
      Header
        .fromRaw(era, hex("82") ++ original ++ hex("5901c0") ++ signature)
        .fold(e => fail(e.toString), identity)
    val certificate: (Bytes, Bytes, BigInt, BigInt, Bytes) => Either[
      OperationalCertificate.Failure,
      OperationalCertificate.Result
    ] = (_, _, _, _, _) =>
      Right(OperationalCertificate.Result.OperationalCertificateSignatureVerified)
    def check(h: Header.Shelley) =
      OCertRules.verifySignaturesWith(h, BigInt(129600), BigInt(62), Sum6Kes.verify, certificate)
    val reference = header(key.sign(canonical))
    assertEquals(reference.signable, canonical)
    assertEquals(check(reference), Right(()))
    assertEquals(check(header(key.sign(original))), Left(OCertError.KesSignatureRejected))

  test("a Praos header body with non-minimal heads is signed as its canonical encoding") {
    // the list head of ten items in four bytes, and the block number in eight
    signedOver(
      Era.Conway,
      b => hex("9a0000000a") ++ hex("1b000000000000002a") ++ b.drop(3)
    )
  }

  test("a detached Praos header body is signed as its canonical encoding") {
    signedOver(Era.Babbage, b => hex("9fff") ++ b.drop(1))
  }

  test("an indefinite TPraos header body is signed as its canonical encoding") {
    signedOver(Era.Alonzo, b => hex("9f") ++ b.drop(1) ++ hex("ff"))
  }

object HeaderDecodingReferenceSuite:
  enum Kind(val wire: String):
    case Header extends Kind("header")
    case HeaderBody extends Kind("headerbody")
    case Block extends Kind("block")
    case VrfCheck extends Kind("vrfcheck")
    case BoundaryHeader extends Kind("boundaryheader")
    case MainHeader extends Kind("mainheader")

  /** Mainnet's slots an epoch, which a boundary header's slot is computed with. */
  val ByronEpochSlots: BigInt = 21600

  /** A line of the corpus. */
  final case class Row(
      name: String,
      era: Era,
      kind: Kind,
      raw: Bytes,
      accepted: Boolean,
      failure: Option[String],
      hash: Option[String],
      bodyHash: Option[String],
      signable: Option[String],
      vrf: Option[VrfRow]
  )

  /** What the reference's VRF check made of one output: decoded, and if so verified. */
  final case class VrfCheckRow(length: Int, decoded: Boolean, verified: Option[Boolean])

  /** A key of the row's seed, its proof and output of the row's message, and each output tried. */
  final case class VrfRow(vk: String, proof: String, output: String, checks: Vector[VrfCheckRow])

  private given Decoder[VrfCheckRow] =
    Decoder.forProduct3("length", "decoded", "verified")(VrfCheckRow.apply)
  private given Decoder[VrfRow] =
    Decoder.forProduct4("vk", "proof", "output", "checks")(VrfRow.apply)

  /** What Scarab read: the header hash, the bytes it checks the KES signature over, and for a block
    * its body hash.
    */
  final case class Verdict(hash: Bytes, signable: Bytes, bodyHash: Option[Bytes])

  private val eras: Map[String, Era] =
    Era.values.map(e => e.toString.toLowerCase -> e).toMap

  private given Decoder[Row] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      era <- c.get[String]("era").flatMap(e => eras.get(e).toRight(failure(c, s"era $e")))
      kind <- c
        .get[String]("kind")
        .flatMap(k => Kind.values.find(_.wire == k).toRight(failure(c, s"kind $k")))
      raw <- c
        .get[String]("bytes")
        .flatMap(h => Bytes.fromHex(h).leftMap(e => failure(c, e.toString)))
      accepted <- c.get[Boolean]("accepted")
      fail <- c.get[Option[String]]("failure")
      hash <- c.get[Option[String]]("hash")
      bodyHash <- c.get[Option[String]]("bodyHash")
      signable <- c.get[Option[String]]("signable")
      vrf <- c.get[Option[VrfRow]]("vrf")
    yield Row(name, era, kind, raw, accepted, fail, hash, bodyHash, signable, vrf)
  }

  private def failure(c: io.circe.HCursor, what: String) =
    io.circe.DecodingFailure(s"unknown $what", c.history)

  private def load: Vector[Row] =
    val path = Path.of("fixtures/reference/header-decoding/cases.jsonl")
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => parser.decode[Row](line).fold(e => sys.error(s"$path: $e"), identity))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => sys.error(e.toString), identity)

  /** A definite byte string, its length head in the shortest form below 65536. */
  private def bstr(b: Bytes): Bytes =
    val n = b.size
    val head =
      if n < 24 then f"${0x40 + n}%02x" else if n < 256 then f"58$n%02x" else f"59$n%04x"
    hex(head) ++ b

  /** `[body, signature]` with a signature of 448 bytes. */
  private def signed(body: Bytes): Bytes = hex("82") ++ body ++ hex("5901c0" + "88" * 448)
