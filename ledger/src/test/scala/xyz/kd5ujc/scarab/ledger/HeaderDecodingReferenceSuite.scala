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
import xyz.kd5ujc.scarab.protocol.Era

/** Babbage and Conway headers are Praos headers, whose `HeaderRaw` and `HeaderBody` are `RecD`
  * records, and the Leios header's are too (`libs/cardano-protocol/src/Cardano/Protocol/Praos/
  * BlockHeader.hs:203-229`, `Leios/BlockHeader.hs:229-257`, cardano-ledger f649f975); Shelley to
  * Alonzo headers are TPraos headers read by `decodeRecordNamed` (`TPraos/BlockHeader.hs:193-241`).
  * The KES signature is over the header body encoded again (`getSignableRepresentation`,
  * `Praos/BlockHeader.hs:113-117`, `TPraos/BlockHeader.hs:170-171`,
  * `Leios/BlockHeader.hs:139-142`).
  *
  * `fixtures/reference/header-decoding/cases.jsonl` holds headers, header bodies and blocks of each
  * form, definite, indefinite, detached and with non-minimal heads, with the verdict of the
  * unchanged released decoders and, for an accepted one, the header hash and the bytes the KES
  * signature is checked over. Scarab must give the same verdict, hash the same bytes and sign the
  * same bytes. Evidence for #698.
  */
class HeaderDecodingReferenceSuite extends FunSuite:
  import HeaderDecodingReferenceSuite.*

  private val rows: Vector[Row] = load

  /** A header body alone is read as the body of a definite header with a signature: the reference
    * reads `HeaderRaw` 's body with the same decoder (`From`), and the signature is read after it.
    */
  private def scarab(row: Row): Either[ChainError, Verdict] = (row.target, row.kind) match
    case (Target.Leios, Kind.Header) =>
      LeiosHeader.decode(row.raw).map(h => Verdict(h.hash, h.signable, None))
    case (Target.Leios, _) =>
      LeiosHeader.decode(signed(row.raw)).map(h => Verdict(h.hash, h.signable, None))
    case (Target.Shelley(era), Kind.Header) =>
      Header.fromRaw(era, row.raw).map(h => Verdict(h.hash, h.signable, None))
    case (Target.Shelley(era), Kind.HeaderBody) =>
      Header.fromRaw(era, signed(row.raw)).map(h => Verdict(h.hash, h.signable, None))
    case (Target.Shelley(era), Kind.Block) =>
      Block.decodeShelley(hex("82" + f"${era.blockTag}%02x") ++ row.raw).flatMap {
        case b: Block.Shelley => Right(Verdict(b.header.hash, b.header.signable, Some(b.bodyHash)))
        case _                => Left(Header.fail(LedgerDecodeError.ByronLayout))
      }

  test("the corpus covers every protocol and form, refused and accepted") {
    assertEquals(
      rows.map(_.target).toSet,
      Set(Era.Shelley, Era.Alonzo, Era.Babbage, Era.Conway).map(Target.Shelley(_)) + Target.Leios
    )
    assertEquals(rows.map(_.kind).toSet, Kind.values.toSet)
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for target <- rows.map(_.target).distinct do
      assert(rows.exists(r => r.target == target && r.accepted), s"no accepted $target case")
      assert(rows.exists(r => r.target == target && !r.accepted), s"no refused $target case")
    // the signed bytes differ from the body's original bytes in some accepted case of each
    // protocol: the cases where reading the original bytes would check another signature
    for target <- rows.map(_.target).distinct do
      assert(
        rows.exists(r =>
          r.target == target && r.kind == Kind.Header && r.signable.exists(s =>
            !r.raw.hex.contains(s)
          )
        ),
        s"no $target case whose signed bytes are not in the input"
      )
  }

  for row <- rows do
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

  /** The decoder a row is read with: a Shelley-based era's header, or the Leios header. */
  enum Target:
    case Shelley(era: Era)
    case Leios

  /** A line of the corpus. */
  final case class Row(
      name: String,
      target: Target,
      kind: Kind,
      raw: Bytes,
      accepted: Boolean,
      failure: Option[String],
      hash: Option[String],
      bodyHash: Option[String],
      signable: Option[String]
  )

  /** What Scarab read: the header hash, the bytes it checks the KES signature over, and for a block
    * its body hash.
    */
  final case class Verdict(hash: Bytes, signable: Bytes, bodyHash: Option[Bytes])

  private val targets: Map[String, Target] =
    Era.values
      .map(e => e.toString.toLowerCase -> Target.Shelley(e))
      .toMap + ("leios" -> Target.Leios)

  private given Decoder[Row] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      target <- c.get[String]("era").flatMap(e => targets.get(e).toRight(failure(c, s"era $e")))
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
    yield Row(name, target, kind, raw, accepted, fail, hash, bodyHash, signable)
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

  /** `[body, signature]` with a signature of 448 bytes. */
  private def signed(body: Bytes): Bytes = hex("82") ++ body ++ hex("5901c0" + "88" * 448)
