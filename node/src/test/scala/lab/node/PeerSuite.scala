// SPDX-License-Identifier: Apache-2.0
package lab.node

import lab.cbor.Bytes
import lab.network.Era
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class PeerSuite extends ScalaCheckSuite:
  property("thinned intersection candidates fit one FindIntersect and keep both ends") {
    forAll(Gen.choose(0, 5000)) { n =>
      val all = Vector.range(0, n)
      val thin = Peer.thin(all)
      assert(thin.size <= 64)
      if n > 0 then
        assertEquals(thin.head, 0)
        assertEquals(thin.last, n - 1)
      assertEquals(thin, thin.sorted)
      assertEquals(thin, thin.distinct)
      // The newest candidates are all there: a short rollback finds its intersection at once.
      assertEquals(thin.take(math.min(n, 16)), all.take(16))
    }
  }

  private def header(i: Int, bodySize: Long): Header =
    Header.Shelley(
      Era.Babbage,
      Bytes.wrap(Array.fill(900)(1.toByte)),
      Bytes.empty,
      Bytes.wrap(Array.fill(32)(i.toByte)),
      BigInt(i),
      BigInt(i),
      None,
      Bytes.empty,
      Bytes.empty,
      HeaderVrf.Praos(VrfCert(Bytes.empty, Bytes.empty)),
      BigInt(bodySize),
      Bytes.empty,
      Header.OpCert(Bytes.empty, BigInt(0), BigInt(0), Bytes.empty),
      (BigInt(8), BigInt(0)),
      Bytes.empty
    )

  property("ranges cover the headers in order within the request limits") {
    forAll(Gen.listOf(Gen.choose(0L, 90112L)).map(_.take(600))) { sizes =>
      val headers = sizes.zipWithIndex.map((s, i) => header(i, s)).toVector
      val runs = Peer.ranges(headers)
      assertEquals(runs.flatten, headers)
      runs.foreach { run =>
        assert(run.size <= 100)
        val bytes = run.map {
          case h: Header.Shelley => h.bodySize.toLong + h.raw.size + 512
          case _                 => 0L
        }
        // A run over the budget is a single oversized header, never several.
        assert(bytes.sum <= 8000000L || run.size == 1)
      }
    }
  }
