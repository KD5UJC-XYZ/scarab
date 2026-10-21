// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** The production big-ledger classification adapter receives the same explicit ledger answer as the
  * pinned native requestPublicRootPeersImpl. Actual worker selection is a separate boundary.
  */
class NativeRootFallbackSuite extends FunSuite:
  test("all native big-ledger fallback partitions and advertise flags agree") {
    val rows = Files
      .readAllLines(Path.of("fixtures/peer-selection/root-fallback/decisions.jsonl"))
      .asScala
      .toList
    assertEquals(rows.size, 9)
    rows.zipWithIndex.foreach { (line, index) =>
      val c = parse(line).fold(e => fail(e.message), identity).hcursor
      def get[A: io.circe.Decoder](field: String): A =
        c.get[A](field).fold(e => fail(s"case $index $field: ${e.message}"), identity)
      assert(get[Boolean]("big"))
      val supplied = get[Int]("ledgerState") match
        case 0     => None
        case 1     => Some(Set.empty[Int])
        case 2     => Some(Set(1, 2))
        case other => fail(s"unknown supplied ledger input $other")
      assert(Set(0, 1, 4)(get[Int]("requested")))
      val configured =
        PublicRoots(Set(3011, 3012), Set.empty[Int], advertise = Map(3011 -> true, 3012 -> false))
      val result = BigLedgerRoots.fromLedger(supplied, configured)
      val (ledger, ordinary, advertise) = result match
        case BigLedgerRoots.Ledger(peers)   => (peers, Set.empty[Int], Map.empty[Int, Boolean])
        case BigLedgerRoots.Fallback(roots) => (Set.empty[Int], roots.ordinary, roots.advertise)
      val extras = get[List[io.circe.Json]]("extra").map { entry =>
        val e = entry.hcursor
        e.get[Int]("peer").fold(err => fail(err.message), identity) -> e
          .get[Boolean]("advertise")
          .fold(err => fail(err.message), identity)
      }.toMap
      assertEquals(get[Set[Int]]("ledger"), Set.empty[Int])
      assertEquals(ledger, get[Set[Int]]("bigLedger"), s"case $index big partition")
      assertEquals(ordinary, extras.keySet, s"case $index ordinary partition")
      assertEquals(advertise, extras, s"case $index advertise flags")
      assertEquals(result.offered, ledger ++ ordinary)
      result.offered.foreach { peer =>
        assertEquals(result.origin(peer), if ledger(peer) then Origin.BigLedger else Origin.Public)
      }
      val injectedTtlSeconds = if supplied.isDefined then 30 else 60
      assertEquals(get[BigInt]("ttlPicos"), BigInt(injectedTtlSeconds) * BigInt(10).pow(12))
    }
  }
