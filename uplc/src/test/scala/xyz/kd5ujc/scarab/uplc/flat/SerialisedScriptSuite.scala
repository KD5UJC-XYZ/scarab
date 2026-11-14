// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.*

/** `deserialiseScript` (`PlutusLedgerApi/Common/SerialisedScript.hs:241-278`, plutus 1.70.0.0
  * `507cab8c`) and the version check of `mkTermToEvaluate` (`Common/Eval.hs:124-127`).
  */
final class SerialisedScriptSuite extends munit.FunSuite:
  private val unit = Term.constant(Constant.Unit)
  private val program = Program(ProgramVersion.V1_0_0, Term.app(Term.lam(unit), unit))
  private val flat = FlatWriter.flat(program).toArray
  private val script = Bytes.wrap(FlatWriter.cborBytes(flat))

  private def deserialise(language: PlutusLanguage, protocol: Int, bytes: Bytes) =
    SerialisedScript.deserialiseScript(language, ProtocolMajor(protocol), bytes)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  test("a script decodes to its program") {
    assertEquals(deserialise(PlutusLanguage.V1, 5, script), Right(program))
    assertEquals(deserialise(PlutusLanguage.V3, 11, script), Right(program))
  }

  // `SerialisedScript.hs:256-259`, with the ledger's own guard for V4 (`Availability.languageIntroducedIn`)
  test("a language before the protocol that introduces it is refused first") {
    assertEquals(
      deserialise(PlutusLanguage.V3, 8, Bytes.empty),
      Left(
        ScriptDecodeError.LanguageNotAvailable(
          PlutusLanguage.V3,
          ProtocolMajor.Chang,
          ProtocolMajor(8)
        )
      )
    )
    assertEquals(
      deserialise(PlutusLanguage.V2, 6, script),
      Left(
        ScriptDecodeError.LanguageNotAvailable(
          PlutusLanguage.V2,
          ProtocolMajor.Vasil,
          ProtocolMajor(6)
        )
      )
    )
    assertEquals(
      deserialise(PlutusLanguage.V4, 11, script),
      Left(
        ScriptDecodeError.LanguageNotAvailable(
          PlutusLanguage.V4,
          ProtocolMajor.Dijkstra,
          ProtocolMajor(11)
        )
      )
    )
  }

  // `decodeBytes` through `decodeViaFlatWith` (`SerialiseViaFlat.hs:28-32`); probes `outer-*` of
  // `fixtures/reference/plutus-flat-decode` pin the 8-byte length and the indefinite string
  test("the script is a definite-length CBOR byte string, its length in any width") {
    val wide =
      Bytes.wrap(Array(0x5b.toByte) ++ Array.fill[Byte](7)(0) ++ Array(flat.length.toByte) ++ flat)
    assertEquals(deserialise(PlutusLanguage.V3, 9, wide), Right(program))
    val indefinite = Bytes.wrap(Array(0x5f.toByte) ++ script.toArray ++ Array(0xff.toByte))
    assertEquals(
      deserialise(PlutusLanguage.V3, 9, indefinite),
      Left(ScriptDecodeError.ExpectedBytes)
    )
    assertEquals(deserialise(PlutusLanguage.V3, 9, Bytes.empty), Left(ScriptDecodeError.EndOfInput))
    assertEquals(
      deserialise(PlutusLanguage.V3, 9, hex("00")),
      Left(ScriptDecodeError.ExpectedBytes)
    )
    assertEquals(deserialise(PlutusLanguage.V3, 9, hex("58")), Left(ScriptDecodeError.EndOfInput))
    assertEquals(deserialise(PlutusLanguage.V3, 9, hex("4501")), Left(ScriptDecodeError.EndOfInput))
    assertEquals(
      deserialise(PlutusLanguage.V3, 9, hex("5bffffffffffffffff00")),
      Left(ScriptDecodeError.EndOfInput)
    )
    assertEquals(
      deserialise(PlutusLanguage.V3, 9, hex("4100")),
      Left(ScriptDecodeError.Flat(FlatError.NotEnoughSpace(8, 8)))
    )
  }

  // `SerialisedScript.hs:262-264`
  test("bytes after the CBOR byte string: kept by V1 and V2, refused by V3 and V4") {
    val trailing = script ++ hex("00ff")
    (5 to 11).foreach(pv =>
      assertEquals(deserialise(PlutusLanguage.V1, pv, trailing), Right(program))
    )
    (7 to 11).foreach(pv =>
      assertEquals(deserialise(PlutusLanguage.V2, pv, trailing), Right(program))
    )
    (9 to 11).foreach(pv =>
      assertEquals(
        deserialise(PlutusLanguage.V3, pv, trailing),
        Left(ScriptDecodeError.Remainder(hex("00ff")))
      )
    )
    assertEquals(
      deserialise(PlutusLanguage.V4, 12, trailing),
      Left(ScriptDecodeError.Remainder(hex("00ff")))
    )
    // the Flat program is read whole first: bytes inside the byte string after the filler are its
    val inside = Bytes.wrap(FlatWriter.cborBytes(flat :+ 0.toByte))
    assertEquals(
      deserialise(PlutusLanguage.V1, 9, inside),
      Left(ScriptDecodeError.Flat(FlatError.TooMuchSpace(flat.length.toLong * 8)))
    )
  }

  // `builtinsAvailableIn` (`Versions.hs:320-356`) through `checkBuiltin` (`SerialisedScript.hs:216-225`)
  test("builtins are those of the language and protocol, a V4 script's those of V3") {
    def using(fun: DefaultFun): Bytes =
      FlatWriter.script(Program(ProgramVersion.V1_0_0, Term.builtin(fun)))
    val serialise = using(DefaultFun.SerialiseData)
    assertEquals(
      deserialise(PlutusLanguage.V1, 10, serialise),
      Left(
        ScriptDecodeError.Flat(
          FlatError.Refused(
            Availability.Refusal
              .BuiltinNotAvailable(DefaultFun.SerialiseData, PlutusLanguage.V1, ProtocolMajor(10))
          )
        )
      )
    )
    assert(deserialise(PlutusLanguage.V1, 11, serialise).isRight)
    assert(deserialise(PlutusLanguage.V2, 7, serialise).isRight)
    val expMod = using(DefaultFun.ExpModInteger)
    assert(deserialise(PlutusLanguage.V3, 10, expMod).isLeft)
    assert(deserialise(PlutusLanguage.V3, 11, expMod).isRight)
    assert(deserialise(PlutusLanguage.V4, 12, expMod).isRight)
    assert(deserialise(PlutusLanguage.V4, 12, using(DefaultFun.DropPolicies)).isLeft)
  }

  // `Eval.hs:124-127`: the program's version is checked when it is evaluated, not when decoded
  test("a 1.1.0 program decodes in V1 before protocol 11 and is refused for evaluation") {
    val newer = Program(ProgramVersion.V1_1_0, Term.constr(Word64.Zero, Vector.empty))
    val bytes = FlatWriter.script(newer)
    assertEquals(deserialise(PlutusLanguage.V1, 10, bytes), Right(newer))
    assertEquals(
      SerialisedScript.forEvaluation(PlutusLanguage.V1, ProtocolMajor(10), newer),
      Left(
        Availability.Refusal
          .ProgramVersionNotAvailable(ProgramVersion.V1_1_0, PlutusLanguage.V1, ProtocolMajor(10))
      )
    )
    assertEquals(
      SerialisedScript.forEvaluation(PlutusLanguage.V1, ProtocolMajor(11), newer),
      Right(newer)
    )
    assertEquals(
      SerialisedScript.forEvaluation(PlutusLanguage.V3, ProtocolMajor(9), newer),
      Right(newer)
    )
  }
