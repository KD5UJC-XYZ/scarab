// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes

class ForkAuditSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes("UTF-8"))
  private val h = "ab" * 32
  private val input = "825820" + h + "00"
  private def tx(address: String, fee: String): Bytes =
    raw("84a30081" + input + "01818241" + address + "0a02" + fee + "a0f5f6")
  private val ta = ForkAuditCommand.transaction(tx("aa", "01"))
  private val tb = ForkAuditCommand.transaction(tx("bb", "02"))
  private val initial = hex("a1" + input + "8241cc0b")
  private def outputs(t: ForkAuditCommand.Transaction, address: String): Bytes =
    hex("a1825820" + t.id.hex + "008241" + address + "0a")
  private val a = outputs(ta, "aa")
  private val b = outputs(tb, "bb")
  private def effects(
      c: Bytes = initial,
      left: Bytes = a,
      rollback: Bytes = initial,
      right: Bytes = b,
      af: BigInt = 11,
      rf: BigInt = 10,
      bf: BigInt = 12
  ): Unit =
    ForkAuditCommand.checkStateEffects(c, left, rollback, right, 10, af, rf, bf, ta, tb)
  test("fixed bounded fork shape requires longer B and no capacity override") {
    for a <- 1 to 2; b <- a + 1 to 4 do ForkAuditCommand.branchBounds(a, b)
    Vector((0, 2), (3, 4), (1, 1), (2, 2), (2, 5)).foreach((a, b) =>
      intercept[IllegalArgumentException](ForkAuditCommand.branchBounds(a, b))
    )
  }
  test("one-block A captures retain exact originals without padding") {
    val row =
      "{\"record\":\"transfer-range-block\",\"headerEnvelopeHex\":\"00\",\"rawBlockHex\":\"01\"}"
    assertEquals(ForkAuditCommand.originals(raw(row), 2).size, 1)
    intercept[IllegalArgumentException](
      ForkAuditCommand.originals(raw(Vector.fill(3)(row).mkString("\n")), 2)
    )
    intercept[IllegalArgumentException](
      ForkAuditCommand.originals(raw(row.replace("\"00\"", "\"0A\"")), 2)
    )
  }
  test("marker requires exact body and witness inclusion and excludes other branch") {
    val memoA = hex(new String(tx("aa", "01").toArray, "UTF-8"))
    val memoB = hex(new String(tx("bb", "02").toArray, "UTF-8"))
    ForkAuditCommand.membership(Vector(memoA), ta, tb)
    Vector(Vector.empty, Vector(memoB), Vector(memoA, memoB), Vector(memoA, memoA)).foreach(m =>
      intercept[IllegalArgumentException](ForkAuditCommand.membership(m, ta, tb))
    )
    intercept[IllegalArgumentException](ForkAuditCommand.membership(Vector(memoA), ta, ta))
  }
  test("nonempty undo restores C input, removes A outputs and B installs distinct effects") {
    effects()
    assertEquals(ta.spent, tb.spent)
    assert(ta.id != tb.id)
  }
  test("unchanged effects, retained A outputs, wrong branch and fee mismatch reject") {
    intercept[IllegalArgumentException](effects(left = initial))
    intercept[IllegalArgumentException](effects(right = initial))
    intercept[IllegalArgumentException](effects(rollback = a))
    intercept[IllegalArgumentException](effects(right = a))
    intercept[IllegalArgumentException](effects(af = 10))
    intercept[IllegalArgumentException](effects(rf = 11))
    intercept[IllegalArgumentException](effects(bf = 11))
  }
  test("malformed duplicate records and overbounded original files reject") {
    Vector("{", "{\"record\":0,\"record\":1}", "[]", "{}").foreach(s =>
      intercept[Exception](ForkAuditCommand.originals(raw(s), 2))
    )
  }
