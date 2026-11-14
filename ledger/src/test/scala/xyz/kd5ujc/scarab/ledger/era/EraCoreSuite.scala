// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ByteStrings,
  CborRead,
  LedgerDecodeError,
  NativeScript as FlatNativeScript,
  PreprodDeepTx,
  Script as FlatScript,
  ScriptKind,
  TxBody as FlatTxBody
}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageScript
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayScript, ConwayTimelock, ConwayTxBody}
import xyz.kd5ujc.scarab.protocol.Era

/** The core of the era types (stage 1 of `docs/design/era-typed-transactions.md`): each era's
  * native and Plutus scripts read as the flat decoder reads them, with the reference's hash and the
  * bytes of every level, and the compiler refusing what an era does not have. Every refusal has a
  * twin that differs in the one thing refused and compiles, so a refusal for another reason does
  * not pass.
  */
class EraCoreSuite extends munit.FunSuite:
  private given ByteStrings = ByteStrings.Definite

  private def node(hex: String): Node =
    Bytes.fromHex(hex).flatMap(CborRead.decode(_)).toOption.get

  private val keyHash = "1c" * 28

  // `[1, [[0, h], [4, 10]]]`: all of a signature and a time start
  private val allOfHex = s"8201828200581c${keyHash}82040a"

  test("a native script reads into its era with the bytes of every level and the flat hash") {
    val n = node(allOfHex)
    val t = ConwayTimelock.decode(n).toOption.get
    val conway = EraScript.conway
    val script = conway.fromNativeScript(t)
    assertEquals(
      conway.hashScript(script),
      FlatScript.native(n, FlatNativeScript.Form.Timelock).toOption.get.hash
    )
    val subs = conway.getRequireAllOf(t).get
    assertEquals(subs.size, 2)
    assertEquals(
      subs.map(s => conway.originalBytes(conway.fromNativeScript(s)).hex),
      Vector(s"8200581c$keyHash", "82040a")
    )
    assertEquals(conway.getRequireSignature(subs(0)), Some(Bytes.fromHex(keyHash).toOption.get))
    assertEquals(conway.getTimeStart(subs(1)), Some(BigInt(10)))
    assertEquals(conway.getTimeExpire(subs(1)), None)
    assertEquals(conway.getRequireAnyOf(t), None)
  }

  test(
    "a native script as deep as a preprod transaction reads on a small stack, owning its bytes"
  ) {
    // the witness set's native scripts (key 1) of the preprod transaction whose script nests 5,383
    // `all` levels over a signature
    val scripts = CborRead.decode(PreprodDeepTx.bytes).toOption.get.value match
      case CborValue.Arr(xs) =>
        val set =
          CborRead.fields(xs(1), xyz.kd5ujc.scarab.ledger.DecodeField.WitnessSet).toOption.get(1)
        // a set is tagged 258 from Conway
        val list = set.value match
          case CborValue.Tag(t, inner) if t == 258 => inner
          case _                                   => set
        list.value match
          case CborValue.Arr(ss) => ss
          case other             => fail(s"no native scripts: ${other.toString.take(80)}")
      case other => fail(s"not a transaction: ${other.toString.take(80)}")
    assert(scripts.nonEmpty)
    scripts.foreach { n =>
      val typed = PreprodDeepTx.onSmallStack(ConwayTimelock.decode(n).toOption.get)
      val again = PreprodDeepTx.onSmallStack(ConwayTimelock.decode(n).toOption.get)
      val flat = FlatScript.native(n, FlatNativeScript.Form.Timelock).toOption.get
      assertEquals(EraScript.conway.hashScript(EraScript.conway.fromNativeScript(typed)), flat.hash)
      assert(PreprodDeepTx.onSmallStack(typed == again))
      // the script owns exactly its bytes, and a level's bytes are a window of them
      val conway = EraScript.conway
      val root = conway.originalBytes(conway.fromNativeScript(typed))
      assertEquals(root.unsafeArray.length, root.size)
      assert(root.size < PreprodDeepTx.bytes.size)
      val child = conway.getRequireAllOf(typed).get.head
      assert(conway.originalBytes(conway.fromNativeScript(child)).unsafeArray eq root.unsafeArray)
    }
  }

  test("an era's script decoder refuses a language the era lacks, as the flat decoder refuses it") {
    // `[3, h'0102']`: a Plutus V3 script
    val v3 = node("8203420102")
    assertEquals(
      BabbageScript.decode(v3),
      Left(
        EraTxError.Decode(
          LedgerDecodeError.UnsupportedLanguage(ScriptKind.PlutusV3, ScriptKind.PlutusV2)
        )
      )
    )
    assertEquals(
      FlatScript.decode(v3, ScriptKind.PlutusV2).left.toOption,
      Some(LedgerDecodeError.UnsupportedLanguage(ScriptKind.PlutusV3, ScriptKind.PlutusV2))
    )
    val conway = ConwayScript.decode(v3).toOption.get
    assertEquals(
      EraScript.conway.hashScript(conway),
      FlatScript.decode(v3, ScriptKind.PlutusV3).toOption.get.hash
    )
    // an unknown tag is refused alike
    val tag5 = node("8205420102")
    val unknown = LedgerDecodeError.UnknownTag(xyz.kd5ujc.scarab.ledger.DecodeField.ScriptType, 5)
    assertEquals(ConwayScript.decode(tag5), Left(EraTxError.Decode(unknown)))
    assertEquals(FlatScript.decode(tag5, ScriptKind.PlutusV3).left.toOption, Some(unknown))
  }

  test("a Babbage Plutus script carried into Conway keeps its language, bytes and hash") {
    val v2 = node("8202420102")
    val babbage = BabbageScript.decode(v2).toOption.get
    val conway = ConwayScript.translate(babbage)
    assertEquals(EraScript.conway.hashScript(conway), EraScript.babbage.hashScript(babbage))
    assertEquals(
      EraScript.conway.toPlutusScript(conway).map(_.kind),
      EraScript.babbage.toPlutusScript(babbage).map(_.kind)
    )
  }

  // recorded blocks with Babbage and Conway transactions
  private lazy val blocks: Vector[Block.Shelley] =
    Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/preview/babbage-mint.hex",
      "fixtures/preview/conway.hex",
      "fixtures/preview/conway-governance.hex"
    )
      .flatMap(f => Files.readString(Path.of(f)).linesIterator)
      .map(_.trim.split("\\s+").last)
      .filter(_.nonEmpty)
      .flatMap(h => Bytes.fromHex(h).toOption)
      .flatMap(raw => Block.decodeShelley(raw).toOption)
      .collect { case b: Block.Shelley => b }
      .toVector

  test(
    "recorded outputs read into their era, their reference scripts with the flat output's hash"
  ) {
    def bodies(era: Era) = blocks.filter(_.era == era).flatMap(_.transactions.map(_.body))
    val babbage = bodies(Era.Babbage)
    val conway = bodies(Era.Conway)
    assert(babbage.nonEmpty && conway.nonEmpty, (babbage.size, conway.size))
    val withScripts = babbage.map { raw =>
      val flat = FlatTxBody.decode(raw, Era.Babbage).toOption.get.outputs
      val typed =
        flat.map(xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxOut.fromStored(_).toOption.get)
      assertEquals(typed.map(EraTxOut.babbage.raw), flat.map(_.raw))
      val hashes = typed.map(EraTxOut.babbage.referenceScript(_).map(EraScript.babbage.hashScript))
      assertEquals(hashes, flat.map(_.scriptRef.map(_.hash)))
      hashes.count(_.isDefined)
    }.sum + conway.map { raw =>
      val flat = FlatTxBody.decode(raw, Era.Conway).toOption.get.outputs
      val typed = ConwayTxBody.decode(raw).toOption.get.outputs
      assertEquals(typed.map(EraTxOut.conway.raw), flat.map(_.raw))
      val hashes = typed.map(EraTxOut.conway.referenceScript(_).map(EraScript.conway.hashScript))
      assertEquals(hashes, flat.map(_.scriptRef.map(_.hash)))
      assertEquals(typed.map(EraTxOut.conway.datum), flat.map(_.datum))
      hashes.count(_.isDefined)
    }.sum
    // the recorded blocks have no output with a reference script: those are the next test's
    assertEquals(withScripts, 0)
  }

  // map-form outputs `{0: address, 1: coin, 3: #6.24(bytes .cbor script)}`, read by the flat
  // output decoder as the store reads them
  private def storedWith(scriptHex: String): xyz.kd5ujc.scarab.ledger.TxOut =
    val script = Bytes.fromHex(scriptHex).toOption.get
    val wrapped =
      if script.size < 24 then f"d818${0x40 + script.size}%02x$scriptHex"
      else f"d81858${script.size}%02x$scriptHex"
    val hex = s"a300581d61${"ab" * 28}011a000f424003$wrapped"
    xyz.kd5ujc.scarab.ledger.TxOut
      .decode(
        node(hex),
        xyz.kd5ujc.scarab.ledger.Address.Mode.Rigorous,
        xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap,
        ScriptKind.newestKnown
      )
      .toOption
      .get

  test("a stored output's reference script reads into its era with the flat output's hash") {
    val plutusV2 = storedWith("8202420102")
    val native = storedWith(s"82008200581c$keyHash")
    Vector(plutusV2, native).foreach { out =>
      val b = babbage.BabbageTxOut.fromStored(out).toOption.get
      val c = conway.ConwayTxOut.fromStored(out).toOption.get
      val flat = out.scriptRef.map(_.hash)
      assert(flat.isDefined)
      assertEquals(EraTxOut.babbage.referenceScript(b).map(EraScript.babbage.hashScript), flat)
      assertEquals(EraTxOut.conway.referenceScript(c).map(EraScript.conway.hashScript), flat)
      // and carried from Babbage into Conway, the same output
      assertEquals(conway.ConwayTxOut.translate(b), c)
    }
    // a Plutus V3 reference script is not a Babbage script
    val plutusV3 = storedWith("8203420102")
    assertEquals(
      babbage.BabbageTxOut.fromStored(plutusV3),
      Left(
        EraTxError.Decode(
          LedgerDecodeError.UnsupportedLanguage(ScriptKind.PlutusV3, ScriptKind.PlutusV2)
        )
      )
    )
    assert(conway.ConwayTxOut.fromStored(plutusV3).isRight)
  }

  test("the compiler refuses equality across eras; within an era it compiles") {
    val across = compileErrors(
      "import scala.language.strictEquality; def f(b: babbage.BabbageTxOut, c: conway.ConwayTxOut) = b == c"
    )
    assert(across.contains("cannot be compared"), across)
    val within = compileErrors(
      "import scala.language.strictEquality; def f(b: conway.ConwayTxOut, c: conway.ConwayTxOut) = b == c"
    )
    assertEquals(within, "")
  }

  test("the compiler refuses another era's value; the era's own compiles") {
    val other = compileErrors("def f(a: allegra.AllegraTxOut) = EraTxOut.mary.value(a)")
    assert(other.contains("AllegraTxOut"), other)
    assertEquals(compileErrors("def f(a: allegra.AllegraTxOut) = EraTxOut.allegra.value(a)"), "")
  }

  test("the compiler refuses a language an era lacks; an era that has it compiles") {
    val babbageV3 =
      compileErrors(
        "val p = babbage.BabbagePlutusScript.V3(Plutus(xyz.kd5ujc.scarab.codec.Bytes.empty))"
      )
    assert(babbageV3.contains("V3"), babbageV3)
    assertEquals(
      compileErrors(
        "val p = conway.ConwayPlutusScript.V3(Plutus(xyz.kd5ujc.scarab.codec.Bytes.empty))"
      ),
      ""
    )
  }

  test("the compiler refuses a capability an era lacks; an era that has it compiles") {
    val mary = compileErrors("val o = summon[BabbageEraTxOut[xyz.kd5ujc.scarab.ledger.state.Mary]]")
    assert(mary.contains("BabbageEraTxOut"), mary)
    assertEquals(
      compileErrors("val o = summon[BabbageEraTxOut[xyz.kd5ujc.scarab.ledger.state.Conway]]"),
      ""
    )
  }

  test(
    "the compiler refuses an era value made outside its file; reading its representation compiles"
  ) {
    val made =
      compileErrors("def f(r: BabbageTxOutRep[conway.ConwayScript]): conway.ConwayTxOut = r")
    assert(made.contains("ConwayTxOut"), made)
    assertEquals(
      compileErrors("def f(c: conway.ConwayTxOut): BabbageTxOutRep[conway.ConwayScript] = c.rep"),
      ""
    )
  }
