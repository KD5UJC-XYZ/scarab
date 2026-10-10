// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.plutus.PlutusExecution.Budget

class PlutusFeesSuite extends munit.FunSuite:
  import PlutusFees.*
  private def get[A](v: Either[?, A]): A = v.fold(e => fail(e.toString), identity)
  private def p(
      memN: BigInt = 577,
      memD: BigInt = 10000,
      stepN: BigInt = 721,
      stepD: BigInt = 10000000,
      tx: Budget = Budget(14000000, 10000000000L),
      block: Budget = Budget(62000000, 20000000000L)
  ): Parameters =
    get(parameters(get(price(memN, memD)), get(price(stepN, stepD)), tx, block, 150, 3))
  private val linear = get(FeeSize.Parameters.create("Conway", 9, 44, 155381, 16384))

  test("reference example prices and fixed declared profile budget cost 7933 lovelace") {
    assertEquals(get(executionFee(p(), Budget(100000, 30000000))), BigInt(7933))
  }
  test("one combined ceiling differs from separately rounded memory and steps") {
    assertEquals(get(executionFee(p(1, 3, 1, 3), Budget(1, 1))), BigInt(1))
    assertEquals(get(executionFee(p(1, 3, 1, 3), Budget(2, 2))), BigInt(2))
    assertEquals(get(executionFee(p(0, 1, 0, 1), Budget(1, 1))), BigInt(0))
  }
  test("exact fee boundary uses memo size and declared rather than consumed units") {
    val declared = Budget(100000, 30000000)
    // Memo is 1 + 100 + 200 + 1 = 302; 44*302 + 155381 + 7933 = 176602.
    val accepted = get(check(linear, p(), 100, 200, 176602, declared, 264903, 1))
    assertEquals(accepted.minimumFee, BigInt(176602))
    assertEquals(
      check(linear, p(), 100, 200, 176601, declared, 264903, 1),
      Left(Failure.FeeTooSmall(176601, 176602))
    )
  }
  test("collateral uses supplied fee including overpayment and rounds upward") {
    val declared = Budget(100000, 30000000)
    assertEquals(
      check(linear, p(), 100, 200, 176603, declared, 264904, 1),
      Left(Failure.CollateralTooSmall(264904, 264905))
    )
    assertEquals(
      get(check(linear, p(), 100, 200, 176603, declared, 264905, 1)).minimumCollateral,
      BigInt(264905)
    )
  }
  test("each execution dimension and block sum independently enforce inclusive limits") {
    val small = p(tx = Budget(2, 3), block = Budget(3, 5))
    assertEquals(get(checkBlock(small, Vector(Budget(2, 3), Budget(1, 2)))), Budget(3, 5))
    assertEquals(
      checkBlock(small, Vector(Budget(2, 3), Budget(2, 2))),
      Left(Failure.BlockExecutionUnitsTooLarge)
    )
    assertEquals(
      checkBlock(small, Vector(Budget(1, 3), Budget(1, 3))),
      Left(Failure.BlockExecutionUnitsTooLarge)
    )
    assertEquals(checkBlock(small, Vector(Budget(3, 0))), Left(Failure.ExecutionUnitsTooLarge))
    assertEquals(checkBlock(small, Vector(Budget(0, 4))), Left(Failure.ExecutionUnitsTooLarge))
  }
  test("rational multiplication does not overflow machine-width intermediate values") {
    val max = (BigInt(1) << 64) - 1
    assertEquals(get(executionFee(p(max, max, max, max), Budget(max, max))), max * 2)
  }
  test("malformed numeric inputs and unbounded work fail closed") {
    assert(price(1, 0).isLeft)
    assert(price(-1, 3).isLeft)
    assert(price(null, 1).isLeft)
    assert(executionFee(p(), Budget(-1, 0)).isLeft)
    assert(checkBlock(p(), Vector.fill(17)(Budget(0, 0))).isLeft)
    assert(check(linear, p(), 100, 200, 176602, Budget(100000, 30000000), 999999, 0).isLeft)
    assertEquals(
      check(linear, p(), 100, 200, 176602, Budget(100000, 30000000), 999999, 4),
      Left(Failure.TooManyCollateralInputs)
    )
    assertEquals(
      check(linear, p(), 16384, 1, 9999999, Budget(1, 1), 99999999, 1),
      Left(Failure.TransactionTooLarge(16387, 16384))
    )
  }
