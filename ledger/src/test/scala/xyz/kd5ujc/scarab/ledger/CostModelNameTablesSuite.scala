// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Json, JsonObject}
import munit.FunSuite
import scalus.uplc.Program
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.plutus.*

/** The PlutusV3 cost-model parameter names of plutus 1.70.0.0 (361, the reference node 11.1.3's)
  * and of 1.71.0.0 (369, the Leios testnet build's; `PlutusLedgerApi/V3/ParamName.hs` at
  * `261766ad`, recorded in the 1.71.0.0 view of the test resources `plutus-reference`) differ only
  * in eight names after the 361st, those of `keepPolicies` and `dropPolicies`. The ledger names
  * PlutusV3 and PlutusV4 cost models with them alike (`plutusVXParamNames`, `CostModels.hs:262-266`
  * at cardano-ledger 9df9f98c).
  *
  * The owner decided (2026-11-04) that the networks of the mainnet lineage read cost models by the
  * 1.70.0.0 names and the Leios testnet by the 1.71.0.0 names. This suite pins that no reader of
  * this node depends on which: a genesis cost model given by name is read by the first 251 names
  * only (`costModelFromMap` of `costModelInitParamNames`, `CostModels.hs:205-228`), every other
  * list by position at any length (`CostModelDecode`), and an evaluation reads the list by
  * position, where the values after the 361st cost only builtins of batch 7, which no protocol
  * version enables (`builtinsIntroducedIn`, `futurePV`). A reader that names parameters past the
  * 251st (such as `costModelToMap`, the JSON of a cost model) must select the table by the network
  * and extend this suite.
  */
class CostModelNameTablesSuite extends FunSuite:
  private val v170: Vector[String] = ParamNames.v3

  private val v171: Vector[String] =
    PlutusReference
      .view(PlutusRelease.Plutus1_71)
      .read("ledger-api/V3-param-names.txt")
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(ParamNames.show)
      .toVector

  private val lengths = Vector(361, 362, 365, 369)

  test("the 1.71.0.0 table is the 1.70.0.0 table and eight names of batch 7") {
    assertEquals(v170.size, 361)
    assertEquals(v171.size, 369)
    assertEquals(v171.take(361), v170)
    assert(v171.drop(361).forall(n => n.startsWith("keepPolicies") || n.startsWith("dropPolicies")))
  }

  test("a genesis cost model given by name reads the same under either table, at every length") {
    for
      language <- Vector(CostModelLanguage.PlutusV3, CostModelLanguage.PlutusV4)
      length <- lengths
    do
      def byName(names: Vector[String]): Json =
        Json.obj(
          language.key -> Json.fromJsonObject(
            JsonObject.fromIterable(
              names.take(length).zipWithIndex.map((n, i) => n -> Json.fromLong(i.toLong))
            )
          )
        )
      val old = GenesisCostModels.parse(lenient = true, Vector.empty, byName(v170))
      val newer = GenesisCostModels.parse(lenient = true, Vector.empty, byName(v171))
      assertEquals(newer, old, s"$language, $length names")
      assertEquals(
        old.map(_.valid.get(language).map(_.size)),
        Right(Some(language.initialCount)),
        s"$language: the first ${language.initialCount} names are read"
      )
  }

  private def head(major: Int, n: Int): String =
    if n < 24 then f"${(major << 5) | n}%02x"
    else if n < 256 then f"${(major << 5) | 24}%02x$n%02x"
    else f"${(major << 5) | 25}%02x$n%04x"

  test("an update's cost model is kept at every length, by position") {
    for
      language <- Vector(2, 3)
      length <- lengths
    do
      val raw = head(5, 1) + head(0, language) + head(4, length) + (0 until length)
        .map(i => head(0, i % 24))
        .mkString
      val node = Bytes
        .fromHex(raw)
        .flatMap(Cbor.decode(_))
        .fold(e => fail(e.toString), identity)
      assertEquals(
        CostModelDecode.decode(node, CostModelDecode.Format.Open).map(_.get(language).map(_.size)),
        Right(Some(length)),
        s"language $language, $length values"
      )
  }

  test("an evaluation gives the same verdict and budget for a list of 361 values or more") {
    val script = Bytes.fromArray(
      Program
        .parseUplc(
          "(program 1.1.0 [ (lam r (con unit ())) " +
            "[ [ (builtin appendByteString) (con bytestring #0102) ] (con bytestring #03) ] ])"
        )
        .fold(e => fail(e.toString), _.cborEncoded)
    )
    val base = scalus.cardano.ledger.CardanoInfo.mainnet.protocolParams.costModels
      .models(PlutusLanguage.V3.id)
      .toVector
    def model(length: Int): Vector[Long] = base.padTo(length, 1000L).take(length)
    for
      (language, protocol) <- Vector(PlutusLanguage.V3 -> 11, PlutusLanguage.V4 -> 12)
      length <- lengths
    do
      def run(m: Vector[Long]) = ScalusEvaluator.pure.evaluate(
        EvalRequest(
          language,
          protocol,
          script,
          Vector.empty,
          m,
          ExUnits(BigInt(1) << 30, BigInt(1) << 40)
        )
      )
      val truncated = run(model(361))
      assert(truncated.isSuccess, truncated.toString)
      assertEquals(run(model(length)), truncated, s"$language at $protocol, $length values")
  }
