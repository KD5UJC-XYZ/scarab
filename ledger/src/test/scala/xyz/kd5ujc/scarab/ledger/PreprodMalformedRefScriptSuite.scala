// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.plutus.*
import munit.FunSuite

import TestLedger.*

/** Preprod transaction aee75c1c (block 4,045,972, epoch 248, protocol 10): an output carries a
  * Plutus V2 reference script whose bytes are the CBOR-wrapped flat program twice over. The
  * reference `deserialiseScript` decodes the first item and, for V1 and V2, ignores the remainder
  * (V3 raises `RemainderError`); Scalus's `fromCbor` rejects the trailing input, which made the
  * node report `MalformedReferenceScripts` on a transaction the chain accepted.
  */
class PreprodMalformedRefScriptSuite extends FunSuite:
  private val c = PlutusCase.load(Path.of("fixtures/preprod/plutus/tx-aee75c1c.json"))
  private val ada = BigInt(1000000)
  private val decoder = ScalusEvaluator.pureDecoder

  private val script: Bytes =
    c.tx.body.outputs.flatMap(_.referenceScript).head.plutus.get

  private def world: LedgerState =
    val params = TestLedger
      .params(c.protocolMajor)
      .copy(
        minFeeA = 44,
        minFeeB = 155381,
        maxTxSize = 16384,
        maxValueSize = 5000,
        coinsPerUTxOByte = 4310,
        keyDeposit = 2 * ada,
        priceMem = Rational(577, 10000),
        priceSteps = Rational(721, 10000000),
        maxTxExUnits = ExUnits(14000000, BigInt("10000000000")),
        maxBlockExUnits = ExUnits(62000000, BigInt("20000000000")),
        collateralPercentage = 150,
        maxCollateralInputs = 3,
        minFeeRefScriptCostPerByte = Rational(15),
        costModels = c.costModels
      )
    val base = state(c.protocolMajor, c.spent.toVector).copy(params = params, epoch = 248)
    base.copy(reserves = Supply - (base.total - base.reserves))

  private def env = TxRules.Env(
    slot = c.slot,
    epoch = 248,
    network = 0,
    firstSlotNextEpoch = c.slot + 100,
    stabilityWindow = 129600,
    clock = None,
    evaluator = ScalusEvaluator.default
  )

  test("the script is 2156 bytes: one CBOR byte string of 1078 bytes, twice") {
    assertEquals(script.value.length, 2156)
    assertEquals(script.value.take(1078), script.value.drop(1078))
  }

  test("the reference script deserialises as Plutus V2 at protocol 10, remainder ignored") {
    assertEquals(decoder.validate(PlutusLanguage.V2, 10, script), Right(()))
    assertEquals(decoder.validate(PlutusLanguage.V1, 10, script), Right(()))
  }

  test("the same bytes are a RemainderError as Plutus V3") {
    assert(decoder.validate(PlutusLanguage.V3, 10, script).left.exists(_.contains("remain")))
  }

  test("a script without the remainder is well formed for every language") {
    val single = Bytes(script.value.take(1078))
    List(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3).foreach { l =>
      assertEquals(decoder.validate(l, 10, single), Right(()), l.toString)
    }
  }

  test("a really malformed script is still rejected, with or without a remainder") {
    val item = script.value.take(1078)
    val garbage = item.take(3) ++ Vector.fill(1075)(0xff.toByte)
    List(garbage, garbage ++ garbage, item.take(500), Vector.empty[Byte], Vector(0x01.toByte))
      .foreach { b =>
        assert(decoder.validate(PlutusLanguage.V2, 10, Bytes(b)).isLeft, b.length.toString)
      }
  }

  test("the Conway rules accept the transaction") {
    assert(c.tx.isValid)
    val after = TxRules(world, c.tx, env).fold(f => fail(f.message), identity)
    assertEquals(after.fees, c.tx.body.fee)
  }

  test("with the output's script as V3 bytes plus remainder the rules reject it") {
    // the same transaction with a V3 reference script of the same bytes is MalformedReferenceScripts
    val at = c.tx.body.outputs.indexWhere(_.scriptRef.isDefined)
    val out = c.tx.body.outputs(at)
    // the rules re-read the script from the output's encoding: patch the language tag 2 to 3
    val at2 = out.raw.value.indexOfSlice(Vector(0x82, 0x02, 0x59, 0x08, 0x6c).map(_.toByte))
    assert(at2 > 0)
    val raw3 = Bytes(out.raw.value.updated(at2 + 1, 3.toByte))
    val bad = c.tx.copy(body =
      c.tx.body.copy(outputs = c.tx.body.outputs.updated(at, out.copy(raw = raw3)))
    )
    val r = PreConwayRules.scriptsWellFormed(world, bad, env)
    assert(r.left.exists(_.message.contains("MalformedReferenceScripts")), r.toString)
  }
