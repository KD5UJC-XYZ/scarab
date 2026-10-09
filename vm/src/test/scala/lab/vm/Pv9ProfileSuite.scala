// SPDX-License-Identifier: Apache-2.0
package lab.vm

import scalus.uplc.{DefaultFun, Term, Constant}

class Pv9ProfileSuite extends munit.FunSuite:
  private def resource(name: String): String =
    val source =
      scala.io.Source.fromInputStream(getClass.getResourceAsStream("/plutus-pv9/" + name), "UTF-8")
    try source.mkString
    finally source.close()
  private val model = resource("cost-model.json")
  private val script = resource("spend.uplc")
  private def profile = Pv9Profile.create(model, script).fold(fail(_), identity)

  test("frozen PV9 profile returns Unit with deterministic local budget") {
    val first = profile.evaluate(SyntheticSpend())
    println(s"PV9 local observation: $first")
    first match
      case Outcome.Success(Term.Const(Constant.Unit, _), spent) =>
        assertEquals(profile.evaluate(SyntheticSpend()), first)
        assertEquals(profile.evaluate(SyntheticSpend(), spent), first)
        assert(
          profile
            .evaluate(SyntheticSpend(), spent.copy(cpu = spent.cpu - 1))
            .isInstanceOf[Outcome.BudgetExhausted]
        )
        assert(
          profile
            .evaluate(SyntheticSpend(), spent.copy(memory = spent.memory - 1))
            .isInstanceOf[Outcome.BudgetExhausted]
        )
      case other => fail(s"expected Unit: $other")
  }
  test("purpose, reference, datum, beneficiary, payout and redeemer mutations fail") {
    val base = SyntheticSpend()
    val mutations = Seq(
      base.copy(purpose = SyntheticPurpose.Minting),
      base.copy(purposeId = "12" * 32),
      base.copy(purposeIndex = 1),
      base.copy(purposeMinimum = 2000001),
      base.copy(purposeBeneficiary = "23" * 28),
      base.copy(paymentBeneficiary = "23" * 28),
      base.copy(payout = 1999999),
      base.copy(redeemer = 8)
    )
    mutations.foreach { input =>
      profile.evaluate(input) match
        case Outcome.EvaluationFailure("explicit-error", _) => ()
        case other                                          => fail(s"$input returned $other")
    }
  }
  test("matching explicit identities and exact minimum payment succeed") {
    assert(
      profile
        .evaluate(
          SyntheticSpend(
            inputId = "ab" * 32,
            purposeId = "ab" * 32,
            inlineBeneficiary = "cd" * 28,
            purposeBeneficiary = "cd" * 28,
            paymentBeneficiary = "cd" * 28,
            payout = 2000000
          )
        )
        .isInstanceOf[Outcome.Success]
    )
  }
  test("bounded contexts and budgets reject invalid inputs") {
    Seq(
      SyntheticSpend(inputId = "a"),
      SyntheticSpend(paymentBeneficiary = "AA" * 28),
      SyntheticSpend(payout = -1),
      SyntheticSpend(payout = 5000001),
      SyntheticSpend(inputIndex = 256)
    ).foreach { input =>
      assert(profile.evaluate(input).isInstanceOf[Outcome.InvalidInput])
    }
    assert(profile.evaluate(SyntheticSpend(), Budget(-1, 10)).isInstanceOf[Outcome.InvalidInput])
  }
  test("exact signed parsing preserves integers beyond binary64 precision") {
    def array(first: String) = (first +: Vector.fill(250)("0")).mkString("[", ",", "]")
    Seq("-9007199254740993", Long.MinValue.toString, Long.MaxValue.toString).foreach { n =>
      assertEquals(Pv9Profile.parseValues(array(n)).map(_.head), Right(n.toLong))
    }
    Seq("1.0", "1e2", "9223372036854775808", "01", "null").foreach { n =>
      assert(Pv9Profile.parseValues(array(n)).isLeft)
    }
    assert(Pv9Profile.parseValues(Vector.fill(250)("0").mkString("[", ",", "]")).isLeft)
    assert(Pv9Profile.parseValues(Vector.fill(252)("0").mkString("[", ",", "]")).isLeft)
  }
  test("every used cost must be present and unpadded") {
    val values = Pv9Profile.parseValues(model).toOption.get
    assertEquals(Pv9Profile.requiredCostIndices.size, 49)
    assertEquals(Pv9Profile.validateUsedCosts(values), Right(()))
    Pv9Profile.requiredCostIndices.values.foreach { index =>
      assert(Pv9Profile.validateUsedCosts(values.take(index)).isLeft)
      assert(Pv9Profile.validateUsedCosts(values.updated(index, Long.MaxValue)).isLeft)
    }
  }
  test("PV9 availability and closed script admission are separate checks") {
    assert(Pv9Profile.allowedBuiltins.forall(Pv9Profile.pv9Available))
    assert(!Pv9Profile.pv9Available(DefaultFun.Ripemd_160))
    assert(!Pv9Profile.pv9Available(DefaultFun.ExpModInteger))
    assert(!Pv9Profile.allowedBuiltins(DefaultFun.VerifyEd25519Signature))
    assert(Pv9Profile.create(model + " ", script).isLeft)
    assert(Pv9Profile.create(model, script + " ").isLeft)
    assert(Pv9Profile.create(model, "(program 1.0.0 (con unit ()))").isLeft)
  }
