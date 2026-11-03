// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{CborRead, PreprodDeepTx}

/** A Dijkstra transaction's nesting comes from the block (ADR 0011): a native script in the witness
  * set and in a reference script, and transaction metadata, nested far deeper than a 256 KiB stack
  * could recurse, are read on the heap. The reference has no depth limit, and accepts them (the
  * corpus case "tx witnesses native script nested 1000 deep").
  */
class DijkstraTxDepthSuite extends FunSuite:
  private val depth = 50000

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  /** `[1, [inner]]` around `inner`, `depth` times. */
  private def nested(inner: String, open: String, close: String): String =
    open * depth + inner + close * depth

  private val native = nested("8200581c" + "33" * 28, "820181", "")
  private val input = "81825820" + "11" * 32 + "00"

  private def tx(output: String, witnesses: String, aux: String): Bytes =
    hex(s"84a300${input}01${output}0201${witnesses}${aux}f5")

  private def decodes(raw: Bytes): Boolean =
    PreprodDeepTx.onSmallStack(
      CborRead.decode(raw, V12.nestedLimits).flatMap(DijkstraTx.inBlock).isRight
    )

  test("a native script nested deeper than the stack in the witness set") {
    val decoded = decodes(tx("80", s"a10181$native", "f6"))
    assert(decoded)
  }

  test("a native script nested deeper than the stack in a reference script") {
    val script = s"8200$native"
    val wrapped = s"d8185a${f"${script.length / 2}%08x"}$script"
    val decoded = decodes(tx(s"81a300581d61${"22" * 28}010503$wrapped", "a0", "f6"))
    assert(decoded)
  }

  test("metadata nested deeper than the stack") {
    val decoded = decodes(tx("80", "a0", s"a101${nested("01", "81", "")}"))
    assert(decoded)
  }
