// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}

/** Native `StakePoolParams` JSON decisions and every accepted field, captured from the pinned
  * Haskell library. Each pool is read under a fixed genesis key, which the parameters do not
  * determine.
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

  private val genesisKey = "07" * 28

  private def account(j: Json): RewardAccount =
    val network = if field[String](j, "network") == "Mainnet" then 1 else 0
    val credential = field[Json](j, "credential")
    val script = credential.hcursor.get[String]("scriptHash").toOption
    RewardAccount(
      network,
      script.fold(Credential.Key(hex(field[String](credential, "keyHash"))))(h =>
        Credential.Script(hex(h))
      )
    )

  cases.foreach { c =>
    test(field[String](c, "name")) {
      val pool = parser.parse(field[String](c, "poolJson")).fold(e => fail(e.message), identity)
      val decoded = GenesisState.pool(genesisKey, pool)
      if field[Boolean](c, "accepted") then
        val (id, params) = decoded.fold(e => fail(e.message), identity)
        assertEquals(id, hex(genesisKey))
        assertEquals(params.operator, hex(field[String](c, "poolId")))
        assertEquals(params.vrfKeyHash, hex(field[String](c, "vrf")))
        assertEquals(params.pledge, field[BigInt](c, "pledge"))
        assertEquals(params.cost, field[BigInt](c, "cost"))
        assertEquals(
          params.margin,
          Rational(field[BigInt](c, "marginNumerator"), field[BigInt](c, "marginDenominator"))
        )
        assertEquals(params.rewardAccount, account(field[Json](c, "accountAddress")))
        assertEquals(params.owners, field[Vector[String]](c, "owners").map(hex).toSet)
        val relays = field[String](c, "relays")
        assertEquals(params.relays, if relays == "80" then Bytes.empty else hex(relays))
        val metadata = field[Vector[String]](c, "metadata")
        assert(metadata.size <= 1)
        assertEquals(params.metadata, metadata.headOption.map(hex))
      else assert(decoded.isLeft, s"reference refused ${field[String](c, "name")}")
    }
  }
