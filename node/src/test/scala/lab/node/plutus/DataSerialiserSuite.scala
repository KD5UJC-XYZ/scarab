// SPDX-License-Identifier: Apache-2.0
package lab.node.plutus

import lab.cbor.Bytes
import lab.node.ledger.ExUnits
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import scalus.cardano.ledger.CardanoInfo
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.Program
import scalus.uplc.builtin.{Builtins, ByteString, Data}

/** `serialiseData` as the reference writes it (`PlutusCore.Data.encodeData`), the builtin the Hydra
  * fanout validator hashes its outputs with. Scalus 1.2.0 writes a constructor index outside
  * `[0, 2^63)` through a `Long`; preprod transaction 2edd684f carries such a datum
  * (`PreprodConwayRulesSuite`).
  */
class DataSerialiserSuite extends ScalaCheckSuite:
  private def hex(h: String): Bytes = Bytes.fromHex(h).toOption.get

  private def decode(b: Bytes): PlutusData =
    PlutusData.decode(b).fold(e => fail(e), identity)

  private val chainDatums = Vector(
    // outputs of preprod 2edd684f: constructor indices 2^64 - 1, 1313480702 and 2025506762 in
    // tag 102, an empty list, a map of two entries
    "d866821a78bac7ca9fa2d866821bffffffffffffffff9fd87980ffa09fd866821a4e4a1ffe80ffa04429a5f68b401a0065edfbff",
    "9f417044b41915fba2d879801bffffffffffffffffa0401a6ca3c91cff"
  )

  test("the datums of a preprod transaction serialise to the bytes the chain carries") {
    chainDatums.foreach { h =>
      val data = ScalusEvaluator.toData(decode(hex(h)))
      assertEquals(Bytes.fromArray(DataSerialiser.serialise(data)), hex(h))
    }
  }

  test("tags: 0-6 in 121-127, 7-127 in 1280-1400, the rest in 102 with the index") {
    def s(i: BigInt) = Bytes.fromArray(DataSerialiser.serialise(Data.Constr(i, PList.Nil)))
    assertEquals(s(0), hex("d87980"))
    assertEquals(s(6), hex("d87f80"))
    assertEquals(s(7), hex("d9050080"))
    assertEquals(s(127), hex("d9057880"))
    assertEquals(s(128), hex("d8668218" + "80" + "80"))
    assertEquals(s(BigInt("18446744073709551615")), hex("d866821bffffffffffffffff80"))
    // outside Word64 the reference writes an integer (a big number) and the result does not decode
    assertEquals(s(BigInt("18446744073709551616")), hex("d86682c249010000000000000000" + "80"))
  }

  test("integers and byte strings: big numbers, and 64-byte chunks above 64 bytes") {
    def i(v: BigInt) = Bytes.fromArray(DataSerialiser.serialise(Data.I(v)))
    assertEquals(i(BigInt("18446744073709551615")), hex("1bffffffffffffffff"))
    assertEquals(i(BigInt("18446744073709551616")), hex("c249010000000000000000"))
    assertEquals(i(BigInt("-18446744073709551616")), hex("3bffffffffffffffff"))
    assertEquals(i(BigInt("-18446744073709551617")), hex("c349010000000000000000"))
    def b(n: Int) = Bytes.fromArray(
      DataSerialiser.serialise(Data.B(ByteString.fromArray(Array.fill[Byte](n)(1))))
    )
    assertEquals(b(64).size, 66)
    assertEquals(b(65), hex("5f5840" + "01" * 64 + "4101" + "ff"))
  }

  private val genBigInt: Gen[BigInt] = Gen.oneOf(
    Gen.choose(-1000L, 1000L).map(BigInt(_)),
    Gen.long.map(BigInt(_)),
    Gen
      .choose(0, 3)
      .flatMap(n => Gen.listOfN(n + 1, Gen.long).map(_.map(BigInt(_)).reduce(_ * 4294967296L + _))),
    Gen.oneOf(
      BigInt(1) << 63,
      (BigInt(1) << 64) - 1,
      BigInt(1) << 64,
      -(BigInt(1) << 64),
      -1 - (BigInt(1) << 64)
    )
  )

  private def genData(depth: Int): Gen[Data] =
    val leaf = Gen.oneOf(
      genBigInt.map(Data.I(_)),
      Gen
        .choose(0, 130)
        .flatMap(n =>
          Gen
            .listOfN(n, Gen.choose(0, 255))
            .map(l => Data.B(ByteString.fromArray(l.map(_.toByte).toArray)))
        )
    )
    if depth <= 0 then leaf
    else
      val sub = Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, genData(depth - 1)))
      Gen.frequency(
        3 -> leaf,
        2 -> Gen.zip(genConstr, sub).map((i, fs) => Data.Constr(i, PList.from(fs))),
        1 -> sub.map(fs => Data.List(PList.from(fs))),
        1 -> Gen
          .choose(0, 3)
          .flatMap(n => Gen.listOfN(n, Gen.zip(genData(depth - 1), genData(depth - 1))))
          .map(es => Data.Map(PList.from(es)))
      )

  /** Indices the ledger can carry in a datum (`decodeWord64`). */
  private val genConstr: Gen[BigInt] = Gen.oneOf(
    Gen.choose(0L, 200L).map(BigInt(_)),
    Gen.long.map(l => BigInt(l) + (if l < 0 then BigInt(1) << 64 else BigInt(0))),
    Gen.oneOf(BigInt(Long.MaxValue), BigInt(1) << 63, (BigInt(1) << 64) - 1)
  )

  property("serialising and decoding is the identity on data a datum can carry") {
    forAll(genData(3)) { d =>
      val plain = PlutusData.decode(Bytes.fromArray(DataSerialiser.serialise(d)))
      Prop(plain.map(ScalusEvaluator.toData) == Right(d))
    }
  }

  property("it agrees with Scalus wherever Scalus is right (constructor indices below 2^63)") {
    def small(d: Data): Boolean = d match
      case Data.Constr(i, fs) => i >= 0 && i < Long.MaxValue && PList.asScala(fs).forall(small)
      case Data.Map(es)       => PList.asScala(es).forall((k, v) => small(k) && small(v))
      case Data.List(xs)      => PList.asScala(xs).forall(small)
      case _                  => true
    forAll(genData(3).suchThat(small)) { d =>
      Prop(Builtins.serialiseData(d).bytes.sameElements(DataSerialiser.serialise(d)))
    }
  }

  // The machine, not only the encoder: a script compares serialiseData of a constructor whose index
  // is 2^64 - 1 with the bytes the reference gives. (V2, protocol 10.)
  test("the evaluator's serialiseData is the reference's for a constructor index of 2^64 - 1") {
    val expected = "d866821bffffffffffffffff80"
    def run(index: String, bytes: String): EvalResult =
      val program = Program
        .parseUplc(
          s"""(program 1.0.0
             |  (force [ (force (builtin ifThenElse))
             |    [ [ (builtin equalsByteString)
             |      [ (builtin serialiseData)
             |        [ [ (builtin constrData) (con integer $index) ] (con (list data) []) ] ] ]
             |      (con bytestring #$bytes) ]
             |    (delay (con unit ()))
             |    (delay (error)) ]))""".stripMargin
        )
        .fold(sys.error, identity)
      ScalusEvaluator.pure.evaluate(
        EvalRequest(
          PlutusLanguage.V2,
          10,
          Bytes.fromArray(program.cborEncoded),
          Vector.empty,
          CardanoInfo.mainnet.protocolParams.costModels.models(1).toVector,
          ExUnits(BigInt(10000000), BigInt(10000000000L))
        )
      )
    assert(run("18446744073709551615", expected).isSuccess)
    assert(run("18446744073709551615", "d866822080").isSuccess == false, "the truncated encoding")
    // 2^64 + 200 must not collide with 200
    assert(!run("18446744073709551816", "d8668218c880").isSuccess)
    assert(run("200", "d8668218c880").isSuccess)
  }
