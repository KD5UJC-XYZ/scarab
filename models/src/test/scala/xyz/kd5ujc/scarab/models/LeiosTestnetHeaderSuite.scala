// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.LeiosHeader
import xyz.kd5ujc.scarab.protocol.HfcHeader

/** The generated `dijkstra.Header` codec (from the pinned `dijkstra.cddl`, `models.conf`) against
  * headers of the public Leios testnet and against the hand-written [[LeiosHeader]]: same fields,
  * same bytes back. The testnet sends the 12-element header in blocks tagged Conway too, which the
  * generated Conway header (10 elements) refuses.
  */
class LeiosTestnetHeaderSuite extends munit.FunSuite:
  private val dir = Path.of("fixtures", "leios", "testnet")

  private val raws: Vector[Bytes] =
    Vector("headers-from-origin.tsv", "headers-notable.tsv").flatMap { file =>
      Files
        .readAllLines(dir.resolve(file))
        .asScala
        .toVector
        .filterNot(l => l.startsWith("#") || l.isEmpty)
        .map { l =>
          val envelope = Bytes.fromHex(l.split('\t').last).fold(e => fail(e.message), identity)
          HfcHeader.decode(envelope) match
            case Right(HfcHeader.Supported(h)) => h.raw
            case Right(HfcHeader.Dijkstra(r))  => r
            case other                         => fail(other.toString)
        }
    }

  test(
    "the generated Dijkstra header reads every captured header as the hand-written decoder does"
  ) {
    assert(raws.size > 1000)
    raws.foreach { raw =>
      val generated = dijkstra.Header.decodeBytes(raw).fold(e => fail(e.render), identity)
      val ours = LeiosHeader.decode(raw).fold(e => fail(e.message), identity)
      val b = generated.headerBody
      assertEquals(b.blockNumber.value, ours.blockNo)
      assertEquals(b.slot.value, ours.slot)
      assertEquals(b.prevHash.map(_.value), ours.prevHash)
      assertEquals(b.issuerVkey.value, ours.issuerVkey)
      assertEquals(b.vrfVkey.value, ours.vrfVkey)
      assertEquals((b.vrfResult.output, b.vrfResult.proof), (ours.vrf.output, ours.vrf.proof))
      assertEquals(BigInt(b.blockBodySize), ours.bodySize)
      assertEquals(b.blockBodyHash.value, ours.bodyHash)
      assertEquals(
        (
          b.operationalCert.hotVkey.value,
          b.operationalCert.sequenceNumber.value,
          b.operationalCert.kesPeriod.value,
          b.operationalCert.sigma.value
        ),
        (ours.opCert.hotVkey, ours.opCert.sequence, ours.opCert.kesPeriod, ours.opCert.sigma)
      )
      assertEquals(
        (
          BigInt(b.headerVersionInfo.highestSupportedMajorVersion),
          BigInt(b.headerVersionInfo.selfReportedSoftwareTag)
        ),
        (ours.leios.versionInfo.highestSupportedMajor, ours.leios.versionInfo.softwareTag)
      )
      assertEquals(b.blockBodyContainsLeiosCert, ours.leios.blockBodyContainsLeiosCert)
      assertEquals(
        b.ebReferencesAnnouncement.map(a => (a.ebHash.value, a.ebSize)),
        ours.leios.announcement.map(a => (a.hash.bytes, a.size))
      )
      assertEquals(generated.bodySignature.value, ours.kesSignature)
      assertEquals(b.raw, ours.bodyRaw)
      // preserved bytes: what is hashed and signed is written back as received
      assertEquals(dijkstra.Header.encodeUnchecked(generated), raw)
    }
  }

  test(
    "the generated Conway header refuses the 12-element header the testnet sends in Conway blocks"
  ) {
    assert(conway.Header.decodeBytes(raws.head).isLeft)
  }
end LeiosTestnetHeaderSuite
