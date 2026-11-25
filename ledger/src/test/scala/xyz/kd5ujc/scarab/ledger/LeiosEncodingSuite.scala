// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.era.IsPhase2Valid
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraBlock, DijkstraTx}
import xyz.kd5ujc.scarab.protocol.HfcHeader

/** The encoders of a Dijkstra block ([[LeiosHeaderBody.encode]], [[LeiosHeader.signed]],
  * [[LeiosBlockBody.encode]], [[LeiosBlock.encode]]) against every Dijkstra header and block the
  * fixtures hold, all of them written by the reference (provenance in `fixtures/leios/README.md`
  * and `fixtures/dijkstra-devnet/README.md`): each is decoded, its header body's fields and its
  * block body's contents are encoded again, and the bytes must be the recorded ones, byte for byte:
  * the header body encoded from its fields is the bytes its KES signature is over
  * ([[LeiosHeader.signable]]), the header assembled from those bytes and the signature is the
  * recorded header, and the block body's digest and length are the header's body hash and size. A
  * header whose body was written with non-minimal heads, which the reference accepts, keeps its
  * signed bytes ([[LeiosHeader.signed]]), which an encoding of its fields does not reproduce.
  */
class LeiosEncodingSuite extends munit.FunSuite:
  private def rows(path: Path): Vector[Array[String]] =
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .filterNot(l => l.startsWith("#") || l.startsWith("tag\t") || l.isEmpty)
      .map(_.split('\t'))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val testnet = Path.of("fixtures", "leios", "testnet")
  private val devnet = Path.of("fixtures", "dijkstra-devnet")

  /** A ChainSync envelope `[7, tag24(header)]` of the testnet, unwrapped to the header. */
  private def unwrapped(envelope: String): Bytes =
    HfcHeader.decode(hex(envelope)) match
      case Right(HfcHeader.Dijkstra(raw)) => raw
      case other                          => fail(other.toString)

  /** Every recorded Dijkstra header, named by its file and block number. */
  private lazy val headers: Vector[(String, Bytes)] =
    rows(testnet.resolve("headers-from-origin.tsv")).map(r =>
      s"origin ${r(1)}" -> unwrapped(r(4))
    ) ++
      rows(testnet.resolve("headers-after-2168639.tsv")).map(r =>
        s"after-2168639 ${r(1)}" -> unwrapped(r(4))
      ) ++
      rows(testnet.resolve("headers-notable.tsv")).map(r =>
        s"notable ${r(0)}" -> unwrapped(r(5))
      ) ++
      rows(devnet.resolve("headers.tsv"))
        .filter(_(0) == HfcHeader.DijkstraBlockTag.toString)
        .map(r => s"devnet ${r(1)}" -> hex(r(4)))

  /** Every recorded Dijkstra block `[8, block]`. */
  private lazy val blocks: Vector[(String, Bytes)] =
    rows(testnet.resolve("blocks-after-2168639.tsv")).map(r =>
      s"after-2168639 ${r(0)}" -> hex(r(5))
    ) ++
      rows(testnet.resolve("blocks-rc-2026w44.tsv")).map(r => s"rc-2026w44 ${r(0)}" -> hex(r(5))) ++
      rows(devnet.resolve("blocks.tsv"))
        .filter(_(0) == HfcHeader.DijkstraBlockTag.toString)
        .map(r => s"devnet ${r(1)}" -> hex(r(4)))

  private def decodedBlock(label: String, raw: Bytes): LeiosBlock =
    LeiosBlock.decode(raw).fold(e => fail(s"$label: ${e.message}"), identity)

  test("the fixtures hold the headers and blocks the round trips are claimed for") {
    // 1,100 from Origin, the 8 after slot 2,168,639, the 4 notable ones, the devnet's 8
    assertEquals(headers.size, 1100 + 8 + 4 + 8)
    // block 97,276, the five of the release candidate, the devnet's 33 to 40
    assertEquals(blocks.size, 1 + 5 + 8)
  }

  test(
    "a Leios header body encoded from its fields is the signed bytes, and the header the recorded"
  ) {
    headers.foreach { (label, raw) =>
      val header = LeiosHeader.decode(raw).fold(e => fail(s"$label: ${e.message}"), identity)
      assertEquals(header.body.encode, header.signable, s"$label: header body")
      val encoded = LeiosHeader.signed(header.signable, header.kesSignature)
      assertEquals(encoded, raw, s"$label: header")
      assertEquals(Blake2b.hash256.hash(encoded), header.hash, label)
    }
  }

  test(
    "a header body with non-minimal heads keeps its signed bytes, which its fields do not give"
  ) {
    // `header-non-minimal-heads` of the Leios reference cases: a header the testnet's build
    // accepts (`fixtures/reference/leios-testnet-blocks/cases.jsonl`), whose body starts `980c`
    val row = Files
      .readAllLines(Path.of("fixtures", "reference", "leios-testnet-blocks", "cases.jsonl"))
      .asScala
      .find(_.contains("\"id\":\"header-non-minimal-heads\""))
      .getOrElse(fail("no non-minimal header case"))
    def field(name: String): Bytes =
      hex(s""""$name":"([0-9a-f]+)"""".r.findFirstMatchIn(row).getOrElse(fail(name)).group(1))
    val header = LeiosHeader.decode(field("bytes")).fold(e => fail(e.message), identity)
    assertEquals(header.signable, field("signable"))
    assertEquals(header.signable.hex.take(4), "980c")
    // its fields encoded again are another, minimal, body: the signature is not over them
    assertNotEquals(header.body.encode, header.signable)
    assertEquals(header.body.encode.hex.take(2), "8c")
    // the header assembled again holds the body as it was signed, with its signature
    val again = LeiosHeader
      .decode(LeiosHeader.signed(header.signable, header.kesSignature))
      .fold(e => fail(e.message), identity)
    assertEquals(again.signable, header.signable)
    assertEquals(again.kesSignature, header.kesSignature)
    assertEquals(again.body, header.body)
  }

  test("the headers cover each Leios field: an announcement, the certificate flag, neither") {
    val decoded = headers.map((_, raw) => LeiosHeader.decode(raw).toOption.get.leios)
    assert(decoded.exists(l => l.announcement.isEmpty && !l.blockBodyContainsLeiosCert))
    assert(decoded.exists(l => l.announcement.isDefined && !l.blockBodyContainsLeiosCert))
    assert(decoded.exists(l => l.announcement.isDefined && l.blockBodyContainsLeiosCert))
    // the software tag is the producer's own: one of the notable headers reports 67
    assertEquals(decoded.map(_.versionInfo.highestSupportedMajor).distinct, Vector(BigInt(12)))
    assertEquals(
      decoded.map(_.versionInfo.softwareTag).distinct.sorted,
      Vector(BigInt(0), BigInt(67))
    )
  }

  test("a Dijkstra block encoded from its header and its body's contents is the recorded block") {
    blocks.foreach { (label, raw) =>
      val block = decodedBlock(label, raw)
      // the body's contents in the era's types, its transactions read by the era's decoder
      val era = DijkstraBlock.decode(raw).fold(e => fail(s"$label: $e"), identity)
      val body = LeiosBlockBody(
        era.blockBody.txs,
        block.leiosCertificate.map(_.certificate),
        block.perasCertificate
      )
      assertEquals(body.hash, block.header.bodyHash, s"$label: body hash")
      assertEquals(BigInt(body.size), block.header.bodySize, s"$label: body size")
      assertEquals(body.hash, block.bodyHash, label)
      val header = LeiosHeader.signed(block.header.signable, block.header.kesSignature)
      assertEquals(LeiosBlock.encode(header, body.encode), raw, s"$label: block")
    }
  }

  test("the certificates written again are the bytes the certifying blocks carry") {
    val certified =
      blocks.flatMap((label, raw) => decodedBlock(label, raw).leiosCertificate.map(label -> _))
    assertEquals(certified.map(_._1), Vector("after-2168639 certifying", "rc-2026w44 certifying"))
    certified.foreach((label, c) => assertEquals(c.certificate.encode, c.raw, label))
  }

  test(
    "the blocks cover transactions with and without auxiliary data, and more than 23 in a block"
  ) {
    def counts(label: String) =
      blocks
        .collectFirst { case (`label`, raw) => decodedBlock(label, raw).transactions }
        .map(ts => (ts.size, ts.count(_.auxData.isDefined)))
    // 40 transactions: a list longer than 23, written indefinite; 18 of them with auxiliary data
    assertEquals(counts("rc-2026w44 announcing-with-txs"), Some((40, 18)))
    // the devnet's one transaction
    assertEquals(counts("devnet 37"), Some((1, 0)))
    // the block bodies hold no transaction marked invalid: that flag's byte is not covered here
    assert(
      blocks.forall((label, raw) =>
        decodedBlock(label, raw).transactions
          .forall(t => DijkstraTx.isValid(t) == IsPhase2Valid.Phase2Valid)
      )
    )
  }

  test("the minimal body: no transactions and no certificates, the 4 bytes of the first block") {
    val empty = LeiosBlockBody(Vector.empty, None, None)
    assertEquals(empty.encode.hex, "8380f6f6")
    assertEquals(empty.size, 4L)
    val first = blocks.collectFirst { case ("rc-2026w44 block-0", raw) => raw }.get
    assertEquals(empty.hash, decodedBlock("block-0", first).header.bodyHash)
  }
