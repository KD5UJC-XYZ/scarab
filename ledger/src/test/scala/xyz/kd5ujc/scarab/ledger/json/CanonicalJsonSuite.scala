// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx

/** The parse and the rendering of canonical JSON walk nesting on the heap (ADR 0011): the
  * reference's Parsec parser has no depth limit of its own. Its verdicts and hashes are measured
  * case by case in `ByronGenesisBalancesReferenceSuite`.
  */
class CanonicalJsonSuite extends FunSuite:
  private def bytes(text: String): Bytes = Bytes.fromArray(text.getBytes("UTF-8"))

  test("nesting deeper than a small stack parses, renders and converts") {
    val depth = 200000
    val text = "[" * depth + "]" * depth
    val outcome = PreprodDeepTx.onSmallStack {
      CanonicalJson.parse(bytes(text)).map { v =>
        (CanonicalJson.render(v), CanonicalJson.toCirce(v).isArray)
      }
    }
    assertEquals(outcome, Right((bytes(text), true)))
  }

  test(
    "rendering sorts keys by byte, keeps a repeated key, and escapes only the quote and backslash"
  ) {
    val parsed = CanonicalJson.parse(bytes("{ \"z\" : 1 , \"é\":\"a\\\"b\\\\c\nd\" , \"z\": -2 }"))
    assertEquals(
      parsed.map(CanonicalJson.render),
      Right(bytes("{\"z\":1,\"z\":-2,\"é\":\"a\\\"b\\\\c\nd\"}"))
    )
    assertEquals(parsed.toOption.flatMap(_.field("z")), Some(CanonicalJson.Num(1)))
  }

  test("a deep value of the wrong kind is refused, and the refusal prints on a small stack") {
    val deep = "[" * 200000 + "]" * 200000
    val text = s"""{"avvmDistr":$deep,"nonAvvmBalances":{}}"""
    val message = PreprodDeepTx.onSmallStack {
      CanonicalJson
        .parse(bytes(text))
        .left
        .map(_.toString)
        .flatMap(xyz.kd5ujc.scarab.ledger.ByronGenesisFile.genesisBalances(_).left.map(_.message))
    }
    assertEquals(message, Left("config: byron genesis avvmDistr: expected object but got array"))
  }
