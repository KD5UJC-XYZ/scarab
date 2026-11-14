// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import munit.FunSuite
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultUni,
  PlutusLanguage,
  Program,
  ProgramVersion,
  ProtocolMajor,
  ReferenceCorpus,
  SmallStack,
  Term
}
import xyz.kd5ujc.scarab.uplc.bls.G1Element
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** [[FlatEncoder]] against the reference's own Flat encoder: every program of the plutus 1.70.0.0
  * conformance corpus that upstream gives in Flat form, read from its text, encodes to the bytes of
  * its `.flat` file; and what it writes, [[FlatDecoder]] and the ledger's `deserialiseScript` read
  * back.
  */
class FlatEncoderSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val texts = ReferenceCorpus
    .conformance("1.70.0.0")
    .fold(e => fail(e.toString), identity)
    .map(c => c.name -> c.program)
    .toMap

  private val flats =
    ReferenceCorpus.flatConformance("1.70.0.0").fold(e => fail(e.toString), identity)

  test("every corpus program in Flat form encodes, from its text, to upstream's bytes") {
    val compared = flats.cases.flatMap { c =>
      texts.get(c.name).flatMap(text => UplcParser.program(text).toOption).map(c -> _)
    }
    assert(compared.size > 800, s"compared ${compared.size} programs")
    val mismatches = compared.flatMap { (c, program) =>
      FlatEncoder.program(program) match
        case Right(bytes) if bytes == c.program => None
        case Right(bytes) => Some(s"${c.name}: ${bytes.hex} against ${c.program.hex}")
        case Left(error)  => Some(s"${c.name}: $error")
    }
    assertEquals(
      mismatches.take(10),
      Vector.empty,
      s"${mismatches.size} of ${compared.size} differ"
    )
  }

  test("a script written in Flat is the program deserialiseScript reads back") {
    val program = UplcParser
      .program(
        "(program 1.1.0 (constr 3 (con (list integer) [1, -2]) (case (con integer 1) (error))))"
      )
      .fold(e => fail(e.toString), identity)
    val script = FlatEncoder.script(program).fold(e => fail(e.toString), identity)
    assertEquals(
      SerialisedScript.deserialiseScript(PlutusLanguage.V3, ProtocolMajor(11), script),
      Right(program)
    )
  }

  test("a BLS12-381 point has no Flat encoding; an empty list of them has one") {
    val g1 = G1Element.fromCompressed(
      xyz.kd5ujc.scarab.codec.Bytes
        .fromHex(
          "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"
        )
        .fold(e => fail(e.toString), identity)
    )
    val v100 = ProgramVersion.of(1, 0, 0).getOrElse(fail("version"))
    assertEquals(
      FlatEncoder.program(Program(v100, Term.constant(Constant.Bls12_381_G1_Element(g1)))),
      Left(FlatEncodeError.Unencodable(DefaultUni.Bls12_381_G1_Element))
    )
    val empty = Constant.List.empty(DefaultUni.Bls12_381_G1_Element)
    assert(FlatEncoder.program(Program(v100, Term.constant(empty))).isRight)
  }

  test("a deep term and a deeply nested type are written on a small stack") {
    val v100 = ProgramVersion.of(1, 0, 0).getOrElse(fail("version"))
    val deep = (1 to 100000).foldLeft(Term.error)((t, _) => Term.delay(t))
    val nested = (1 to 10000).foldLeft[DefaultUni](DefaultUni.Integer)((u, _) => DefaultUni.List(u))
    val written = SmallStack.run(
      (
        FlatEncoder.program(Program(v100, deep)),
        FlatEncoder.program(Program(v100, Term.constant(Constant.List.empty(nested))))
      )
    )
    assert(written._1.isRight && written._2.isRight)
  }
