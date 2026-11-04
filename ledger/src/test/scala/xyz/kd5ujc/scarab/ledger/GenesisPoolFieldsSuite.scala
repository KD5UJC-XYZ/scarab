// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes

/** Native `StakePoolParams` JSON decisions and encoded relay/metadata fields captured from the
  * pinned Haskell library; this does not establish conformance of the other genesis pool fields.
  */
class GenesisPoolFieldsSuite extends FunSuite:
  private val cases = Files
    .readAllLines(Path.of("fixtures/reference/genesis-pool-fields/cases.jsonl"))
    .asScala
    .toVector
    .map(s => parser.parse(s).fold(e => fail(e.message), identity))

  private def field[A: io.circe.Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(e.message), identity)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  cases.foreach { c =>
    test(field[String](c, "name")) {
      val pool = field[Json](c, "pool")
      val decoded = GenesisState.pool(field[String](pool, "poolId"), pool)
      if field[Boolean](c, "accepted") then
        val (_, params) = decoded.fold(e => fail(e.message), identity)
        val relays = field[String](c, "relays")
        assertEquals(params.relays, if relays == "80" then Bytes.empty else hex(relays))
        val metadata = field[Vector[String]](c, "metadata")
        assert(metadata.size <= 1)
        assertEquals(params.metadata, metadata.headOption.map(hex))
      else assert(decoded.isLeft, s"reference refused ${field[String](c, "name")}")
    }
  }
