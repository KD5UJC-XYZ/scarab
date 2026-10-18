// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage}

/** What each era's Plutus V1 context translation (`transTxOutV1`, `transTxInInfoV1`, the V1
  * `toPlutusTxInfo`) does with reference inputs, reference scripts, inline datums and Byron
  * addresses, at every protocol version, against the ledger source at f649f975:
  *
  *   - Alonzo (protocol 5, 6): a Byron output is left out of the context
  *     (`Alonzo/Plutus/TxInfo.hs:146-149`);
  *   - Babbage (7, 8): a transaction with a reference input is refused, and so is an input or
  *     output with a reference script, an inline datum or a Byron address
  *     (`Babbage/TxInfo.hs:119-126`, `:324-329`), in that order;
  *   - Conway (9 on): the reference script field is no longer refused, and each reference input is
  *     translated like an input although the V1 context does not show it
  *     (`Conway/TxInfo.hs:315-320`, `:410-411`), so an inline datum or a Byron address in one is
  *     still refused. The behaviour is the same at 9, 10 and 11.
  *
  * The reference node's own verdicts for the same situations are `V1ContextSuite` 's.
  */
class ScriptContextV1EraSuite extends FunSuite:
  // ---- a minimal CBOR writer ----------------------------------------------------------------
  private def head(major: Int, n: Int): String =
    if n < 24 then f"${(major << 5) | n}%02x"
    else if n < 256 then f"${(major << 5) | 24}%02x$n%02x"
    else if n < 65536 then f"${(major << 5) | 25}%02x${n >> 8}%02x${n & 0xff}%02x"
    else f"${(major << 5) | 26}%02x$n%08x"
  private def bytes(hex: String): String = head(2, hex.length / 2) + hex

  private def hash(seed: Int, length: Int): String =
    (0 until length).map(i => f"${(seed * 31 + i * 7 + 1) & 0xff}%02x").mkString

  private val keyAddress = "60" + hash(1, 28)
  private val byronAddress =
    "82d818" + hash(2, 30) // an array: a Byron address, which has no payment key

  private def out(
      address: String,
      coin: Int = 2000000,
      inline: Boolean = false,
      script: Boolean = false,
      datumHash: Boolean = false
  ): TxOut =
    val fields = Vector(
      Some("00" + bytes(address)),
      Some("01" + head(0, coin)),
      Option.when(inline)("02" + "8201" + "d818" + bytes("01")),
      Option.when(datumHash)("02" + "8200" + bytes(hash(5, 32))),
      Option.when(script)("03" + "d818" + bytes("8202" + bytes("4e4d01000033222220051200120011")))
    ).flatten
    TxOut.fromBytes(Bytes.fromHex(head(5, fields.size) + fields.mkString).toOption.get) match
      case Right(o) => o
      case Left(e)  => fail(e)

  private def in(n: Int) = TxIn(Bytes.fromHex(hash(10 + n, 32)).toOption.get, n)

  private val plain = out(keyAddress)
  private val inline = out(keyAddress, inline = true)
  private val withScript = out(keyAddress, script = true)
  private val byron = out(byronAddress)

  private def info(
      protocol: Int,
      spent: Vector[TxOut] = Vector(plain),
      references: Vector[TxOut] = Vector.empty,
      outputs: Vector[TxOut] = Vector(plain),
      range: (Option[BigInt], Option[BigInt]) = (None, None),
      clock: BigInt => Either[TranslationError, BigInt] = s => Right(s * 1000)
  ): Either[TranslationError, PlutusData] =
    val inputs = spent.indices.map(i => in(i)).toVector
    val refs = references.indices.map(i => in(100 + i)).toVector
    val body = TestLedger
      .body(
        "era",
        inputs,
        outputs,
        1,
        referenceInputs = refs,
        validityStart = range._1,
        ttl = range._2
      )
    val env = ScriptContexts.Environment(
      protocol,
      inputs.zip(spent).toMap,
      refs.zip(references).toMap,
      clock
    )
    ScriptContexts.txInfo(PlutusLanguage.V1, Tx(body, Witnesses.empty, 0, true, None), env)

  /** The V1 `TxInfo` is a constructor of ten fields; the outputs are its second. */
  private def outputsOf(data: Either[TranslationError, PlutusData]): Vector[PlutusData] =
    data match
      case Right(PlutusData.Constr(_, fields)) =>
        assertEquals(fields.size, 10, "a V1 TxInfo has no reference inputs")
        fields(1) match
          case PlutusData.List(xs) => xs
          case other               => fail(other.toString)
      case other => fail(other.toString)

  private val babbage = Vector(7, 8)
  private val conway = Vector(9, 10, 11)

  // ---- Alonzo -----------------------------------------------------------------------------------
  test("Alonzo (5, 6): a Byron output is left out of the V1 context, a plain one is shown") {
    for pv <- Vector(5, 6) do
      assertEquals(outputsOf(info(pv, outputs = Vector(byron, plain))).size, 1, s"pv $pv")
  }

  // ---- Babbage ----------------------------------------------------------------------------------
  test("Babbage (7, 8): a reference input is refused, before anything else is translated") {
    for pv <- babbage do
      assertEquals(
        info(pv, references = Vector(plain), spent = Vector(byron)),
        Left(TranslationError.ReferenceInputsNotSupported(Set(in(100)))),
        s"pv $pv"
      )
  }

  test("Babbage (7, 8): a reference script on an input or an output is refused") {
    for pv <- babbage do
      assertEquals(
        info(pv, spent = Vector(withScript)),
        Left(TranslationError.ReferenceScriptsNotSupported(TxOutSource.TxOutFromInput(in(0)))),
        s"input, pv $pv"
      )
      assertEquals(
        info(pv, outputs = Vector(withScript)),
        Left(TranslationError.ReferenceScriptsNotSupported(TxOutSource.TxOutFromOutput(0))),
        s"output, pv $pv"
      )
  }

  test("Babbage (7, 8): the reference script is looked at before the inline datum") {
    val both = out(keyAddress, inline = true, script = true)
    for pv <- babbage do
      assertEquals(
        info(pv, outputs = Vector(both)),
        Left(TranslationError.ReferenceScriptsNotSupported(TxOutSource.TxOutFromOutput(0))),
        s"pv $pv"
      )
  }

  test("Babbage (7, 8): an inline datum and a Byron address are refused") {
    for pv <- babbage do
      assertEquals(
        info(pv, spent = Vector(inline)),
        Left(TranslationError.InlineDatumsNotSupported(TxOutSource.TxOutFromInput(in(0)))),
        s"pv $pv"
      )
      assertEquals(
        info(pv, outputs = Vector(byron)),
        Left(TranslationError.ByronTxOutInContext(TxOutSource.TxOutFromOutput(0))),
        s"pv $pv"
      )
  }

  // ---- Conway -------------------------------------------------------------------------------------
  test(
    "Conway (9, 10, 11): a reference input that can be shown is accepted and not in the context"
  ) {
    for pv <- conway do
      assertEquals(outputsOf(info(pv, references = Vector(plain))).size, 1, s"pv $pv")
  }

  test(
    "Conway (9, 10, 11): a reference input holding an inline datum or a Byron address is refused"
  ) {
    for pv <- conway do
      assertEquals(
        info(pv, references = Vector(inline)),
        Left(TranslationError.InlineDatumsNotSupported(TxOutSource.TxOutFromInput(in(100)))),
        s"pv $pv"
      )
      assertEquals(
        info(pv, references = Vector(byron)),
        Left(TranslationError.ByronTxOutInContext(TxOutSource.TxOutFromInput(in(100)))),
        s"pv $pv"
      )
  }

  test(
    "Conway (9, 10, 11): a reference script is accepted wherever it is: reference input, input, output"
  ) {
    for pv <- conway do
      assert(info(pv, references = Vector(withScript)).isRight, s"reference input, pv $pv")
      assert(info(pv, spent = Vector(withScript)).isRight, s"input, pv $pv")
      val shown = outputsOf(info(pv, outputs = Vector(withScript, plain)))
      // the V1 output has no field for it: three fields, address, value and datum hash
      assertEquals(shown.size, 2)
      shown.foreach {
        case PlutusData.Constr(_, fields) => assertEquals(fields.size, 3)
        case other                        => fail(other.toString)
      }
      assertEquals(shown.head, shown(1), s"the reference script does not change the output, pv $pv")
  }

  test("Conway (9, 10, 11): inline datums and Byron addresses stay refused in inputs and outputs") {
    for pv <- conway do
      assertEquals(
        info(pv, spent = Vector(inline)),
        Left(TranslationError.InlineDatumsNotSupported(TxOutSource.TxOutFromInput(in(0))))
      )
      assertEquals(
        info(pv, outputs = Vector(inline)),
        Left(TranslationError.InlineDatumsNotSupported(TxOutSource.TxOutFromOutput(0)))
      )
      assertEquals(
        info(pv, spent = Vector(byron)),
        Left(TranslationError.ByronTxOutInContext(TxOutSource.TxOutFromInput(in(0))))
      )
      assertEquals(
        info(pv, outputs = Vector(byron)),
        Left(TranslationError.ByronTxOutInContext(TxOutSource.TxOutFromOutput(0)))
      )
  }

  test(
    "Conway: the output holding a datum hash is shown with it, with or without a reference script"
  ) {
    val hashed = out(keyAddress, datumHash = true)
    val hashedWithScript = out(keyAddress, datumHash = true, script = true)
    assertEquals(
      outputsOf(info(10, outputs = Vector(hashed))),
      outputsOf(info(10, outputs = Vector(hashedWithScript)))
    )
  }

  test("Conway: a reference input that is not among the resolved outputs is refused") {
    val body =
      TestLedger.body("missing", Vector(in(0)), Vector(plain), 1, referenceInputs = Vector(in(9)))
    val env = ScriptContexts.Environment(10, Map(in(0) -> plain), Map.empty, s => Right(s))
    assertEquals(
      ScriptContexts.txInfo(PlutusLanguage.V1, Tx(body, Witnesses.empty, 0, true, None), env),
      Left(TranslationError.UnresolvedInput(in(9)))
    )
  }

  // ---- the order in which the reference translates -------------------------------------------------
  test(
    "the validity range comes before the inputs, the inputs before the reference inputs before the outputs"
  ) {
    val horizon: BigInt => Either[TranslationError, BigInt] =
      s => Left(TranslationError.NoClockSample(s))
    // a range past the horizon is the first error, whatever the inputs hold
    assertEquals(
      info(10, spent = Vector(inline), range = (Some(5), None), clock = horizon),
      Left(TranslationError.NoClockSample(5))
    )
    // an input before a reference input before an output
    assertEquals(
      info(10, spent = Vector(byron), references = Vector(inline), outputs = Vector(inline)),
      Left(TranslationError.ByronTxOutInContext(TxOutSource.TxOutFromInput(in(0))))
    )
    assertEquals(
      info(10, references = Vector(byron), outputs = Vector(inline)),
      Left(TranslationError.ByronTxOutInContext(TxOutSource.TxOutFromInput(in(100))))
    )
  }

  test("the other languages translate reference inputs as they always did") {
    val body =
      TestLedger.body("v2", Vector(in(0)), Vector(plain), 1, referenceInputs = Vector(in(100)))
    val tx = Tx(body, Witnesses.empty, 0, true, None)
    for pv <- Vector(8, 10) do
      val env = ScriptContexts.Environment(
        pv,
        Map(in(0) -> plain),
        Map(in(100) -> inline),
        s => Right(s)
      )
      assert(ScriptContexts.txInfo(PlutusLanguage.V2, tx, env).isRight, s"V2, pv $pv")
    val byronRef =
      ScriptContexts.Environment(10, Map(in(0) -> plain), Map(in(100) -> byron), s => Right(s))
    assertEquals(
      ScriptContexts.txInfo(PlutusLanguage.V2, tx, byronRef),
      Left(TranslationError.ByronTxOutInContext(TxOutSource.TxOutFromInput(in(100))))
    )
  }
