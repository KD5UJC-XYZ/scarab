// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.*

/** The end of a script's flat program as the ledger meets it, with the real decoder and evaluator:
  * bits or bytes after the program's filler are a malformed script (the reference's strict
  * `unflatWith`), at Babbage's phase 1 (`MalformedScriptWitnesses`, `MalformedReferenceScripts`)
  * and as a failed script in Alonzo's phase 2. Bytes after the script's CBOR byte string are a
  * separate layer: V1 and V2 keep them as a remainder, V3 refuses them (`deserialiseScript`). The
  * reference's verdicts for the same endings: `fixtures/reference/plutus-flat-decode` (`filler-*`,
  * `cbor-remainder*`).
  */
class FlatEndingSuite extends FunSuite:
  private val c = PlutusCase.load(Path.of("fixtures/preprod/plutus/tx-c70243bb.json"))
  private val scripts: Map[Bytes, Script] =
    (c.spent.values ++ c.references.values)
      .flatMap(_.referenceScript)
      .map(script => script.hash -> script)
      .toMap ++ c.tx.witnesses.scriptsByHash
  private val code =
    scripts.values
      .collectFirst { case Script(ScriptKind.PlutusV2, _, _, Some(bytes)) => bytes }
      .getOrElse(fail("fixture has no Plutus V2 script with its bytes"))
  private val flat =
    PlutusScriptBytes.flatOf(code.toArray).getOrElse(fail("fixture script is not a byte string"))

  private def wrapped(content: Array[Byte]): Bytes =
    Bytes.fromArray(FlatProgram.cborByteString(content))

  /** The fixture's program with one byte after its filler, inside the CBOR byte string. */
  private val trailing = wrapped(flat :+ 0x00.toByte)

  /** The fixture's program, unchanged, with one byte after its CBOR byte string. */
  private val remainder = Bytes.fromArray(code.toArray :+ 0x00.toByte)

  private def params(protocol: Int) = c.params.copy(protocolMajor = BigInt(protocol))

  private def env = TxEnv(c.slot, 189, 0)

  test("the fixture's own script ends exactly behind its filler") {
    assert(FlatScan.decode(flat).isRight)
    assertEquals(
      FlatScan.decode(flat :+ 0x00.toByte).map(_ => ()),
      Left(FlatScan.Failure.TooMuchSpace(8))
    )
  }

  test("Babbage phase 1: a byte after the filler makes a witness or reference script malformed") {
    val bad = Script.plutus(ScriptKind.PlutusV2, trailing)
    for protocol <- Vector(7, 8) do
      val witnessed = c.tx.copy(witnesses = c.tx.witnesses.copy(scripts = Vector(bad)))
      assertEquals(
        TxChecks.scriptsWellFormed(params(protocol), witnessed, env),
        Left(
          LedgerFailure.Rejected(
            witnessed.body.id,
            PredicateFailure.utxow(UtxowFailure.MalformedScriptWitnesses(Set(bad.hash)))
          )
        ),
        s"protocol $protocol"
      )
      val output = c.tx.body.outputs.headOption.getOrElse(fail("fixture has no output"))
      val referenced = c.tx.copy(
        witnesses = c.tx.witnesses.copy(scripts = Vector.empty),
        body = c.tx.body.copy(outputs = Vector(output.copy(scriptRef = Some(bad))))
      )
      assertEquals(
        TxChecks.scriptsWellFormed(params(protocol), referenced, env),
        Left(
          LedgerFailure.Rejected(
            referenced.body.id,
            PredicateFailure.utxow(UtxowFailure.MalformedReferenceScripts(Set(bad.hash)))
          )
        ),
        s"protocol $protocol"
      )
  }

  test("Babbage phase 1: a byte after the CBOR item is a V2 remainder, a V3 refusal") {
    for protocol <- Vector(7, 8) do
      val kept = Script.plutus(ScriptKind.PlutusV2, remainder)
      val tx = c.tx.copy(witnesses = c.tx.witnesses.copy(scripts = Vector(kept)))
      assertEquals(TxChecks.scriptsWellFormed(params(protocol), tx, env), Right(()))
    val v3 = Script.plutus(ScriptKind.PlutusV3, remainder)
    val tx = c.tx.copy(witnesses = c.tx.witnesses.copy(scripts = Vector(v3)))
    assertEquals(
      TxChecks.scriptsWellFormed(params(9), tx, env),
      Left(
        LedgerFailure.Rejected(
          tx.body.id,
          PredicateFailure.utxow(UtxowFailure.MalformedScriptWitnesses(Set(v3.hash)))
        )
      )
    )
  }

  test("Alonzo phase 2: a byte after the filler fails a V1 script; a remainder does not") {
    import FlatProgram.{Lam, unit}
    // a V1 validator that ignores its datum, redeemer and context
    val validator = FlatProgram.flat((1, 0, 0), Lam(Lam(Lam(unit))))
    val redeemer = Redeemer(0, 0, Bytes.empty, ExUnits.zero)
    def run(script: Bytes, protocol: Int) =
      Phase2.run(
        Vector(
          redeemer -> EvalRequest(
            PlutusLanguage.V1,
            protocol,
            script,
            Vector.fill(3)(PlutusData.unit),
            FlatDecodeCorpus.costModel(PlutusLanguage.V1, protocol),
            FlatDecodeCorpus.budget
          )
        ),
        ScalusEvaluator.pure
      )
    for protocol <- Vector(5, 6) do
      assert(run(wrapped(validator), protocol).passed, s"control at protocol $protocol")
      assert(
        run(Bytes.fromArray(wrapped(validator).toArray :+ 0x00.toByte), protocol).passed,
        s"remainder at protocol $protocol"
      )
      run(wrapped(validator :+ 0x00.toByte), protocol) match
        case Phase2.Outcome.Failed(_, failures) =>
          assertEquals(
            failures.map(_.reason).toVector,
            Vector(EvalFailure.Decode(ScriptProblem.Flat(FlatScan.Failure.TooMuchSpace(8))))
          )
        case other => fail(s"protocol $protocol: unexpected $other")
  }
