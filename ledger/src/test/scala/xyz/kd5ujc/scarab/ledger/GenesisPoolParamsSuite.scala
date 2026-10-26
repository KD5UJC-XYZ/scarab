// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes

/** Native whole-pool JSON decisions and all accepted fields. The original numeric spelling is
  * retained because Aeson's Integer exponent guard applies before integral conversion.
  */
class GenesisPoolParamsSuite extends FunSuite:
  private val outerId = "01" * 28
  private val cases = Files
    .readAllLines(Path.of("fixtures/reference/genesis-pool-params/cases.jsonl"))
    .asScala
    .toVector
    .map(s => parser.parse(s).fold(e => fail(e.message), identity))

  private def field[A: io.circe.Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(e.message), identity)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  cases.foreach { c =>
    test(field[String](c, "name")) {
      val input = parser.parse(field[String](c, "poolJson")).fold(e => fail(e.message), identity)
      val decoded = GenesisState.pool(outerId, input)
      if field[Boolean](c, "accepted") then
        val (key, p) = decoded.fold(e => fail(e.message), identity)
        assertEquals(key, hex(outerId))
        assertEquals(p.operator, hex(field[String](c, "poolId")))
        assertEquals(p.vrfKeyHash, hex(field[String](c, "vrf")))
        assertEquals(p.pledge, field[BigInt](c, "pledge"))
        assertEquals(p.cost, field[BigInt](c, "cost"))
        assertEquals(p.margin.numerator, field[BigInt](c, "marginNumerator"))
        assertEquals(p.margin.denominator, field[BigInt](c, "marginDenominator"))
        val account = field[Json](c, "accountAddress")
        val network = field[String](account, "network")
        assertEquals(p.rewardAccount.network, if network == "Mainnet" then 1 else 0)
        val credential = p.rewardAccount.credential match
          case Credential.Key(hash)    => Json.obj("keyHash" -> Json.fromString(hash.hex))
          case Credential.Script(hash) => Json.obj("scriptHash" -> Json.fromString(hash.hex))
        assertEquals(credential, field[Json](account, "credential"))
        assertEquals(p.owners.map(_.hex).toVector.sorted, field[Vector[String]](c, "owners"))
        assertEquals(p.relays, hex(field[String](c, "relays")))
        val metadata = field[Vector[String]](c, "metadata")
        assert(metadata.size <= 1)
        assertEquals(p.metadata, metadata.headOption.map(hex))
      else assert(decoded.isLeft, s"reference refused ${field[String](c, "name")}")
    }
  }
