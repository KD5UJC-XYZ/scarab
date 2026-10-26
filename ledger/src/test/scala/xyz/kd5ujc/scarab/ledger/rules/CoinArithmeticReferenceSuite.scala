// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{Ptr, TestLedger}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given

/** Results from the unchanged native Coin, account and stake APIs; provenance accompanies the
  * corpus.
  */
class CoinArithmeticReferenceSuite extends FunSuite:
  import NativeRewardFixture.{accounts, cred, pool}

  private val rows = Files
    .readString(Path.of("fixtures/reference/coin-arithmetic/cases.jsonl"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(line => parse(line).fold(e => fail(e.message), identity))
    .toVector

  private def text(row: Json, key: String): String =
    row.hcursor.get[String](key).fold(e => fail(e.message), identity)
  private def number(row: Json, key: String): BigInt = BigInt(text(row, key))
  private def optional(row: Json, key: String): Option[String] =
    row.hcursor.get[Option[String]](key).fold(e => fail(e.message), identity)

  test("the native corpus includes every signed and compact boundary case") {
    assertEquals(rows.size, 11)
    assertEquals(rows.map(text(_, "name")).distinct.size, 11)
  }

  rows.foreach { row =>
    test(s"native arithmetic: ${text(row, "name")}") {
      val left = Coin(number(row, "left"))
      val right = Coin(number(row, "right"))
      assertEquals(left.value + right.value, number(row, "coinSum"))
      val a = CompactCoin.fromCoin(left).toOption
      val b = CompactCoin.fromCoin(right).toOption
      assertEquals(a.map(_.toBigInt.toString), optional(row, "compactLeft"))
      assertEquals(b.map(_.toBigInt.toString), optional(row, "compactRight"))
      val expected = row.hcursor.get[Option[Json]]("native").fold(e => fail(e.message), identity)
      assertEquals(expected.isDefined, a.isDefined && b.isDefined)
      expected.foreach { native =>
        val ca = a.getOrElse(fail("native compact left missing"))
        val cb = b.getOrElse(fail("native compact right missing"))
        assertEquals(ca.add(cb).toBigInt, number(native, "sum"))
        assertEquals(ca.subtract(cb).toBigInt, number(native, "difference"))
        val ledger = accounts(
          NewEpochState.empty[Shelley](EpochNo.fromUnsigned(5), TestLedger.params(2)),
          List(
            cred(1) -> EraLedger.AccountView(ca, ca, Some(pool(1))),
            cred(2) -> EraLedger.AccountView(cb, cb, Some(pool(1)))
          )
        ).es.ls
        assertEquals(EraLedger.keyDeposits(ledger), number(native, "accountDeposits"))
        assertEquals(
          CompactCoin.sum(EraLedger.accounts(ledger).iterator.map(_._2.balance)).toBigInt,
          number(native, "accountBalances")
        )
        val credited = EraLedger.credit(ledger, Map(cred(1) -> cb))
        assertEquals(
          credited.cert.dState.accounts.states(cred(1)).balance.toBigInt.toString,
          text(native, "credited")
        )
        val resolved = StakeDistribution.resolve(
          Sorted.map(List(cred(1) -> cb)),
          Iterator(cred(1) -> EraLedger.AccountView(ca, ca, Some(pool(1)))),
          Sorted.empty[Ptr, Credential[Staking]],
          Sorted.empty[Ptr, CompactCoin]
        )
        assertEquals(
          resolved.entries.get(cred(1)).map(_.stake.value.toBigInt.toString),
          optional(native, "activeStake")
        )
        assertEquals(resolved.total.value.value, number(native, "activeTotal"))
      }
    }
  }
