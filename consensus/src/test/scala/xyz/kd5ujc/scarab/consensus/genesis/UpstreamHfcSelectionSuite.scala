// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.genesis

import java.nio.file.{Files, Path}
import io.circe.{ACursor, Decoder}
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.protocol.Era

/** Actual Cardano HFC instances at consensus82ecba329, not a reconstruction of acrossEraSelection.
  * All finite native inputs/outputs are retained; these do not claim upstream-generated headers.
  */
class UpstreamHfcSelectionSuite extends FunSuite:
  private def field[A: Decoder](c: ACursor, name: String): A =
    c.get[A](name).fold(e => fail(e.message), identity)
  private def era(c: ACursor): Era =
    val index = field[Int](c, "era")
    Era.values.lift(index).filter(_ => index <= 6).getOrElse(fail(s"unsupported native era $index"))
  private def bytes(value: Int, size: Int): Bytes =
    Bytes.fromArray(Array.fill[Byte](size)(value.toByte))
  private def view(c: ACursor): SelectView =
    val actualEra = era(c)
    val blockNo = field[BigInt](c, "blockNo")
    val slot = field[BigInt](c, "slot")
    if actualEra == Era.Byron then
      SelectView(blockNo, slot, None, 0, None, field[Boolean](c, "isEBB"), Some(actualEra))
    else
      SelectView(
        blockNo,
        slot,
        Some(bytes(field[Int](c, "issuer"), 32)),
        field[BigInt](c, "issueNo"),
        Some(bytes(field[Int](c, "vrf"), 64)),
        era = Some(actualEra)
      )

  test(
    "all 55 actual HFC cases agree on Ord, both eligibility directions and complete sorted IDs"
  ) {
    val rows = Files
      .readString(Path.of("fixtures/reference/candidate-order/hfc.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(line => parse(line).fold(e => fail(e.message), identity).hcursor)
      .toVector
    assertEquals(rows.size, 55)
    val cross = rows
      .map(_.downField("input"))
      .map(c => era(c.downField("local")) -> era(c.downField("candidate")))
      .filter((a, b) => a != b)
      .toSet
    assertEquals(
      cross,
      (for a <- Era.values.take(7); b <- Era.values.take(7) if a != b yield a -> b).toSet
    )
    rows.foreach { row =>
      val input = row.downField("input")
      val output = row.downField("output")
      val label = field[String](input, "label")
      assertEquals(
        output.keys.map(_.toSet),
        Some(Set("ord", "preferCandidate", "preferLocal", "sortedIds")),
        label
      )
      val localCursor = input.downField("local")
      val candidateCursor = input.downField("candidate")
      val local = view(localCursor)
      val candidate = view(candidateCursor)
      // Protocol.hs:76–98 selects across-era Ord independently of configured eligibility.
      val oursBetter =
        ChainSelection.prefers(ChainSelection.Tiebreaker.Unrestricted, candidate, local)
      val theirsBetter =
        ChainSelection.prefers(ChainSelection.Tiebreaker.Unrestricted, local, candidate)
      val order = if oursBetter then "GT" else if theirsBetter then "LT" else "EQ"
      assertEquals(order, field[String](output, "ord"), label)
      assertEquals(
        ChainSelection
          .prefersCandidate(ChainSelection.tiebreakerFor(era(candidateCursor)), local, candidate),
        field[Boolean](output, "preferCandidate"),
        label
      )
      assertEquals(
        ChainSelection
          .prefersCandidate(ChainSelection.tiebreakerFor(era(localCursor)), candidate, local),
        field[Boolean](output, "preferLocal"),
        label
      )
      val ids = Vector(field[Int](localCursor, "id"), field[Int](candidateCursor, "id"))
      // There are precisely two inputs per native sort: ties preserve the original order.
      val sorted = if theirsBetter then ids.reverse else ids
      assertEquals(sorted, field[Vector[Int]](output, "sortedIds"), label)
    }
  }
