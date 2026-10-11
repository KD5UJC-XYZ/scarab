// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.cbor.Bytes
import lab.network.Era
import lab.node.Block
import lab.node.plutus.{PlutusData, PlutusLanguage, ScalusEvaluator}
import munit.FunSuite
import scalus.cardano.ledger as sl
import scalus.cardano.ledger.LedgerToPlutusTranslation as Oracle
import scalus.serialization.cbor.Cbor as ScalusCbor
import scalus.uplc.builtin.{ByteString, Data}
import scalus.uplc.builtin.Data.toData as dataOf

/** Script contexts of a hand-built transaction, checked three ways: against literal expectations of
  * the encoding (the shape Plutus V1, V2 and V3 scripts see) and against Scalus's translation of
  * the same bytes (Scalus is a test oracle only), for every purpose of the transaction.
  */
class ScriptContextGoldenSuite extends FunSuite:
  // ---- a minimal CBOR writer producing hex ----------------------------------------------------
  private object Cb:
    def head(major: Int, n: BigInt): String =
      val b: Vector[Int] =
        if n < 24 then Vector(((major << 5) | n.toInt))
        else if n < 256 then Vector((major << 5) | 24, n.toInt)
        else if n < 65536 then Vector((major << 5) | 25, (n >> 8).toInt & 0xff, n.toInt & 0xff)
        else if n < (BigInt(1) << 32) then
          (major << 5 | 26) +: Vector(24, 16, 8, 0).map(s => ((n >> s) & 0xff).toInt)
        else
          (major << 5 | 27) +: Vector(56, 48, 40, 32, 24, 16, 8, 0).map(s =>
            ((n >> s) & 0xff).toInt
          )
      b.map(x => f"$x%02x").mkString
    def uint(n: BigInt): String = head(0, n)
    def int(n: BigInt): String = if n >= 0 then head(0, n) else head(1, -1 - n)
    def bytes(hex: String): String = head(2, hex.length / 2) + hex
    def arr(xs: String*): String = head(4, xs.size) + xs.mkString
    def map(kvs: (String, String)*): String =
      head(5, kvs.size) + kvs.map(kv => kv._1 + kv._2).mkString
    def tag(n: Int, x: String): String = head(6, n) + x
    def set(xs: String*): String = tag(258, arr(xs*))
    val Null = "f6"

  import Cb.*

  private def h(seed: Int, length: Int): String =
    (0 until length).map(i => f"${(seed * 31 + i * 7 + 1) & 0xff}%02x").mkString

  private def hash28(seed: Int) = h(seed, 28)
  private def hash32(seed: Int) = h(seed, 32)

  // Plutus data: Constr 0 [1], 5, "ab", [], {1: 2}
  private val datumA = tag(121, arr(uint(1)))
  private val datumB = uint(5)
  private val datumC = bytes("abcd")
  private val datumD = arr()
  private val datumE = map(uint(1) -> uint(2))
  private def dataHash(datumHex: String): String =
    lab.Blake2b.hash256.hash(Bytes.fromHex(datumHex).toOption.get).hex

  // ---- addresses and outputs ------------------------------------------------------------------
  private val scriptHash = hash28(1)
  private val scriptAddr = "70" + scriptHash // testnet enterprise, script payment
  private val keyAddr = "00" + hash28(2) + hash28(3) // base: key payment, key stake
  private val scriptStakeAddr = "11" + hash28(4) + hash28(5) // base: script payment, script stake
  private val pointerAddr = "40" + hash28(6) + "050201" // pointer (5, 2, 1)
  private val policyA = hash28(10)
  private val policyB = hash28(11)

  private def out(
      address: String,
      coin: BigInt,
      assets: Option[String] = None,
      datum: Option[String] = None,
      script: Option[String] = None
  ) =
    map(
      Seq(
        Some(uint(0) -> bytes(address)),
        Some(uint(1) -> assets.fold(uint(coin))(a => arr(uint(coin), a))),
        datum.map(d => uint(2) -> d),
        script.map(s => uint(3) -> s)
      ).flatten*
    )

  private val multiasset = map(
    bytes(policyA) -> map(bytes("") -> uint(3), bytes("6e6674") -> uint(7)),
    bytes(policyB) -> map(bytes("00") -> uint(1))
  )

  private val inlineDatum = arr(uint(1), tag(24, bytes(datumA)))
  private val hashDatum = arr(uint(0), bytes(dataHash(datumB)))
  private val refScript =
    tag(24, bytes(arr(uint(2), bytes("4e4d01000033222220051200120011")))) // a V2 script

  // inputs: a script output with an inline datum, a script output with a datum hash, key outputs
  private val input1 = (hash32(20), 0) // script, inline datum
  private val input2 = (hash32(21), 3) // script, datum hash
  private val input3 = (hash32(22), 1) // key + stake, assets
  private val reference1 = (hash32(30), 0) // script ref + inline datum
  private val reference2 = (hash32(31), 2) // pointer address

  private val hashDatumA = arr(uint(0), bytes(dataHash(datumA)))

  private def resolved(language: PlutusLanguage): Map[(String, Int), String] = Map(
    input1 -> out(
      scriptAddr,
      5000000,
      Some(multiasset),
      Some(if language == PlutusLanguage.V1 then hashDatumA else inlineDatum)
    ),
    input2 -> out(scriptAddr, 6000000, None, Some(hashDatum)),
    input3 -> out(keyAddr, 7000000, Some(multiasset)),
    reference1 -> out(scriptStakeAddr, 2000000, None, Some(inlineDatum), Some(refScript)),
    reference2 -> out(pointerAddr, 3000000)
  )

  private def txIn(i: (String, Int)) = arr(bytes(i._1), uint(i._2))

  // ---- the transaction ------------------------------------------------------------------------
  private val rewardKey = "e0" + hash28(40)
  private val rewardScript = "f0" + hash28(41)
  private val poolId = hash28(50)
  private val vrf = hash32(51)
  private val govActionId = arr(bytes(hash32(60)), uint(2))
  private val committeeCold = arr(uint(0), bytes(hash28(61)))
  private val committeeHot = arr(uint(1), bytes(hash28(62)))

  private def voteMap(voter: String, votes: (String, Int)*) =
    bytes("") -> bytes("") // placeholder never used

  private def certificates(conway: Boolean) =
    if conway then
      Vector(
        arr(uint(7), arr(uint(0), bytes(hash28(70))), uint(2000000)), // reg with deposit
        arr(uint(8), arr(uint(1), bytes(scriptHash)), uint(2000000)), // unreg script with refund
        arr(uint(2), arr(uint(0), bytes(hash28(71))), bytes(poolId)), // delegate to pool
        arr(
          uint(9),
          arr(uint(1), bytes(hash28(72))),
          arr(uint(0), bytes(hash28(73)))
        ), // vote deleg
        arr(uint(9), arr(uint(0), bytes(hash28(74))), arr(uint(2))), // always abstain
        arr(uint(10), arr(uint(0), bytes(hash28(75))), bytes(poolId), arr(uint(3))), // stake+vote
        arr(uint(11), arr(uint(0), bytes(hash28(76))), bytes(poolId), uint(1)), // reg+stake
        arr(
          uint(13),
          arr(uint(0), bytes(hash28(77))),
          bytes(poolId),
          arr(uint(1), bytes(scriptHash)),
          uint(5)
        ),
        arr(uint(16), arr(uint(1), bytes(scriptHash)), uint(500), Null), // drep reg
        arr(uint(17), arr(uint(0), bytes(hash28(78))), uint(500)), // drep unreg
        arr(uint(18), arr(uint(0), bytes(hash28(79))), Null), // drep update
        arr(uint(14), committeeCold, committeeHot),
        arr(uint(15), committeeCold, Null),
        arr(uint(4), bytes(poolId), uint(77)) // pool retire
      )
    else
      Vector(
        arr(uint(0), arr(uint(0), bytes(hash28(70)))),
        arr(uint(1), arr(uint(1), bytes(scriptHash))),
        arr(uint(2), arr(uint(0), bytes(hash28(71))), bytes(poolId)),
        arr(uint(4), bytes(poolId), uint(77))
      )

  private def proposals = Vector(
    arr(uint(1000), bytes(rewardKey), arr(uint(6)), arr(text("https://x"), bytes(hash32(80)))),
    arr(
      uint(1000),
      bytes(rewardScript),
      arr(uint(3), govActionId),
      arr(text("u"), bytes(hash32(81)))
    ),
    arr(
      uint(1000),
      bytes(rewardKey),
      arr(uint(1), Null, arr(uint(10), uint(0))),
      arr(text("u"), bytes(hash32(82)))
    ),
    arr(
      uint(1000),
      bytes(rewardKey),
      arr(
        uint(2),
        map(bytes(rewardKey) -> uint(10), bytes(rewardScript) -> uint(20)),
        bytes(scriptHash)
      ),
      arr(text("u"), bytes(hash32(83)))
    ),
    arr(
      uint(1000),
      bytes(rewardKey),
      arr(
        uint(4),
        govActionId,
        tag(258, arr(committeeCold)),
        map(committeeHot -> uint(300)),
        tag(30, arr(uint(2), uint(3)))
      ),
      arr(text("u"), bytes(hash32(84)))
    ),
    arr(
      uint(1000),
      bytes(rewardKey),
      arr(uint(5), Null, arr(arr(text("u"), bytes(hash32(85))), bytes(scriptHash))),
      arr(text("u"), bytes(hash32(86)))
    )
  )

  private def text(s: String): String =
    Cb.head(3, s.length) + s.getBytes.map(b => f"$b%02x").mkString

  private val voters = map(
    arr(uint(3), bytes(scriptHash)) -> map(govActionId -> arr(uint(1), Null)),
    arr(uint(2), bytes(hash28(90))) -> map(
      govActionId -> arr(uint(0), Null),
      arr(bytes(hash32(61)), uint(0)) -> arr(uint(2), Null)
    ),
    arr(uint(4), bytes(poolId)) -> map(govActionId -> arr(uint(1), Null))
  )

  private def body(language: PlutusLanguage): String =
    val conway = language == PlutusLanguage.V3
    val v1 = language == PlutusLanguage.V1
    val inputs = Vector(input1, input2, input3)
    val fields = Seq(
      Some(uint(0) -> set(inputs.map(txIn)*)),
      Some(
        uint(1) -> arr(
          out(keyAddr, 1000000, Some(multiasset)),
          out(pointerAddr, 1500000, None, Some(if v1 then hashDatum else inlineDatum)),
          out(scriptAddr, 1100000, None, Some(hashDatum)),
          out(scriptStakeAddr, 1200000, None, None, if v1 then None else Some(refScript))
        )
      ),
      Some(uint(2) -> uint(187000)),
      Some(uint(3) -> uint(1000200)),
      Some(uint(4) -> arr(certificates(conway)*)),
      Some(uint(5) -> map(bytes(rewardScript) -> uint(10), bytes(rewardKey) -> uint(5))),
      Some(uint(8) -> uint(1000100)),
      Some(
        uint(9) -> map(
          bytes(policyB) -> map(bytes("00") -> int(-4)),
          bytes(policyA) -> map(bytes("6e6674") -> uint(2))
        )
      ),
      Some(uint(11) -> bytes(hash32(99))),
      Some(uint(14) -> set(bytes(hash28(101)), bytes(hash28(100)))),
      Option.when(!v1)(uint(18) -> set(txIn(reference1), txIn(reference2))),
      Option.when(conway)(uint(19) -> voters),
      Option.when(conway)(uint(20) -> arr(proposals*)),
      Option.when(conway)(uint(21) -> uint(777)),
      Option.when(conway)(uint(22) -> uint(1))
    ).flatten
    map(fields*)

  private def redeemerList(rs: Vector[(Int, Int, String)]) =
    map(rs.map { case (tag, ix, data) =>
      arr(uint(tag), uint(ix)) -> arr(data, arr(uint(1000), uint(2000)))
    }*)

  /** Sorted inputs: by id then index. */
  private val sortedInputs = Vector(input1, input2, input3).sortBy(i => (i._1, i._2))

  private def redeemers(language: PlutusLanguage) =
    // only the script inputs carry redeemers
    val spend = sortedInputs.zipWithIndex.collect {
      case (i, ix) if i == input1 || i == input2 => (0, ix, uint(100 + ix))
    }
    val mint = Vector((1, 0, datumA), (1, 1, datumB))
    val certs = (0 until certificates(language == PlutusLanguage.V3).size).toVector.map(i =>
      (2, i, uint(200 + i))
    )
    val rewards = Vector((3, 0, datumC), (3, 1, datumD))
    val gov =
      if language == PlutusLanguage.V3 then
        Vector((4, 0, datumE), (4, 1, datumE), (4, 2, datumE)) ++ proposals.indices.map(i =>
          (5, i, uint(300 + i))
        )
      else Vector.empty
    spend ++ mint ++ certs ++ rewards ++ gov

  private def witnesses(language: PlutusLanguage) =
    map(
      uint(4) -> arr(datumB, datumA, datumC),
      uint(5) -> redeemerList(redeemers(language))
    )

  private def fullTx(language: PlutusLanguage) =
    arr(body(language), witnesses(language), "f5", Null)

  // ---- decoding both ways -----------------------------------------------------------------------
  private def bytesOf(hex: String) = Bytes.fromHex(hex).toOption.get

  private def txOf(language: PlutusLanguage): Tx =
    val parts = fullTxParts(language)
    Tx.fromBlock(Block.Transaction(parts._1, parts._2, None, true), Era.Conway)
      .fold(e => fail(e), identity)

  private def fullTxParts(language: PlutusLanguage) =
    (bytesOf(body(language)), bytesOf(witnesses(language)))

  private def outputsOf(language: PlutusLanguage): Map[TxIn, TxOut] =
    resolved(language).map { case ((id, ix), hex) =>
      TxIn(bytesOf(id), ix) -> TxOut.fromBytes(bytesOf(hex)).fold(e => fail(e), identity)
    }

  private def clock(slot: BigInt): Either[TranslationError, BigInt] = Right(
    1666656000000L + slot * 1000
  )

  private val config = sl.SlotConfig(1666656000000L, 0, 1000)

  private def environment(language: PlutusLanguage, protocol: Int, tx: Tx) =
    val all = outputsOf(language)
    val spent = tx.body.inputs.map(i => i -> all(i)).toMap
    val refs = tx.body.referenceInputs.map(i => i -> all(i)).toMap
    ScriptContexts.Environment(protocol, spent, refs, clock)

  private def scalusSide(language: PlutusLanguage, protocol: Int) =
    val bytes = bytesOf(arr(body(language), witnesses(language), "f5", Null))
    val tx = sl.Transaction.fromCbor(bytes.toArray)(using sl.ProtocolVersion(protocol, 0))
    val utxos: sl.Utxos = resolved(language).map { case ((id, ix), hex) =>
      sl.TransactionInput(sl.TransactionHash.fromHex(id), ix) ->
        ScalusCbor.decode[sl.TransactionOutput](bytesOf(hex).toArray)
    }
    (tx, utxos)

  private def hexOf(d: Data): String = d.toCbor.map(b => f"${b & 0xff}%02x").mkString

  private def checkAgainstScalus(language: PlutusLanguage, protocol: Int): Unit =
    val tx = txOf(language)
    val env = environment(language, protocol, tx)
    val (stx, utxos) = scalusSide(language, protocol)
    val pv = sl.MajorProtocolVersion(protocol)
    val info = ScriptContexts.txInfo(language, tx, env).fold(e => fail(e.reason), identity)
    assert(tx.witnesses.redeemers.nonEmpty)
    tx.witnesses.redeemers.foreach { r =>
      val purpose = Purpose
        .resolve(tx.body, env.spent, r.tag, r.index)
        .getOrElse(fail(s"purpose ${r.tag}.${r.index}"))
      val args =
        ScriptContexts.arguments(language, info, tx, r, purpose).fold(e => fail(e.reason), identity)
      val sr = sl.Redeemer(
        sl.RedeemerTag.fromOrdinal(r.tag),
        r.index.toInt,
        ScalusEvaluator.toData(PlutusData.decode(r.data).toOption.get),
        sl.ExUnits(1000, 2000)
      )
      val theirs: Data = language match
        case PlutusLanguage.V1 => dataOf(Oracle.getScriptContextV1(sr, stx, utxos, config, pv))
        case PlutusLanguage.V2 => dataOf(Oracle.getScriptContextV2(sr, stx, utxos, config, pv))
        case PlutusLanguage.V3 =>
          val datum = purpose match
            case Purpose.Spending(_, _, o) =>
              o.datum match
                case Datum.Inline(d) => PlutusData.decode(d).toOption
                case Datum.Hash(hh) =>
                  tx.witnesses.datums
                    .find(d => lab.Blake2b.hash256.hash(d) == hh)
                    .flatMap(PlutusData.decode(_).toOption)
                case _ => None
            case _ => None
          dataOf(
            Oracle.getScriptContextV3(sr, datum.map(ScalusEvaluator.toData), stx, utxos, config, pv)
          )
      assertEquals(
        hexOf(ScalusEvaluator.toData(args.last)),
        hexOf(theirs),
        s"$language redeemer ${r.tag}.${r.index}"
      )
    }

  test("Plutus V1 contexts equal Scalus's (Alonzo, protocol 6)") {
    checkAgainstScalus(PlutusLanguage.V1, 6)
  }

  test("Plutus V2 contexts equal Scalus's (Babbage, protocol 8)") {
    checkAgainstScalus(PlutusLanguage.V2, 8)
  }

  test("Plutus V3 contexts equal Scalus's (Conway, protocol 10), governance included") {
    checkAgainstScalus(PlutusLanguage.V3, 10)
  }

  // ---- literal expectations --------------------------------------------------------------------
  private def constr(tag: Int, fields: PlutusData*): PlutusData = PlutusData.constr(tag, fields*)
  private def pint(n: BigInt): PlutusData = PlutusData.I(n)
  private val negInf = constr(0)
  private val posInf = constr(2)
  private def finite(t: BigInt) = constr(1, pint(t))
  private def bound(e: PlutusData, closed: Boolean) = constr(0, e, PlutusData.bool(closed))
  private def at(slot: Int) = 1666656000000L + slot * 1000L

  private def validRange(protocol: Int, start: Option[BigInt], ttl: Option[BigInt]): PlutusData =
    val b =
      TestLedger.body("range", Vector.empty, Vector.empty, 0, ttl = ttl, validityStart = start)
    val tx = Tx(b, Witnesses.empty, 0, true, None)
    val env = ScriptContexts.Environment(protocol, Map.empty, Map.empty, clock)
    ScriptContexts.txInfo(PlutusLanguage.V2, tx, env) match
      case Right(PlutusData.Constr(_, fields)) => fields(7)
      case other                               => fail(other.toString)

  test(
    "validity intervals: [start, ttl) with the upper bound inclusive only without a lower bound before Conway"
  ) {
    assertEquals(
      validRange(8, None, None),
      constr(0, bound(negInf, true), bound(posInf, true))
    )
    assertEquals(
      validRange(8, Some(10), None),
      constr(0, bound(finite(at(10)), true), bound(posInf, true))
    )
    assertEquals(
      validRange(8, Some(10), Some(20)),
      constr(0, bound(finite(at(10)), true), bound(finite(at(20)), false))
    )
    // Babbage (7, 8): `to t` is inclusive; Conway (9+): the upper bound is exclusive
    assertEquals(
      validRange(8, None, Some(20)),
      constr(0, bound(negInf, true), bound(finite(at(20)), true))
    )
    assertEquals(
      validRange(9, None, Some(20)),
      constr(0, bound(negInf, true), bound(finite(at(20)), false))
    )
  }

  test("a plain V2 transaction has the documented TxInfo shape") {
    val key = hash28(7)
    val addr = Address.decode(bytesOf("60" + key)).fold(e => fail(e), identity)
    val spentIn = TxIn(bytesOf(hash32(1)), 2)
    val b = TestLedger
      .body("shape", Vector(spentIn), Vector(TxOut(addr, Value.coin(1500000))), 200000)
      .copy(requiredSigners = Vector(bytesOf(key)))
    val tx = Tx(b, Witnesses.empty, 0, true, None)
    val env = ScriptContexts.Environment(
      8,
      Map(spentIn -> TxOut(addr, Value.coin(1700000))),
      Map.empty,
      clock
    )
    val address = constr(0, constr(0, PlutusData.B(bytesOf(key))), constr(1))
    def value(coin: Int) =
      PlutusData.Map(
        Vector(
          PlutusData.B(Bytes.empty) -> PlutusData.Map(
            Vector(PlutusData.B(Bytes.empty) -> pint(coin))
          )
        )
      )
    def txOut(coin: Int) = constr(0, address, value(coin), constr(0), constr(1))
    val expected = constr(
      0,
      PlutusData.List(
        Vector(constr(0, constr(0, constr(0, PlutusData.B(spentIn.id)), pint(2)), txOut(1700000)))
      ),
      PlutusData.List(Vector.empty),
      PlutusData.List(Vector(txOut(1500000))),
      value(200000),
      value(0),
      PlutusData.List(Vector.empty),
      PlutusData.Map(Vector.empty),
      constr(0, bound(negInf, true), bound(posInf, true)),
      PlutusData.List(Vector(PlutusData.B(bytesOf(key)))),
      PlutusData.Map(Vector.empty),
      PlutusData.Map(Vector.empty),
      constr(0, PlutusData.B(b.id))
    )
    assertEquals(ScriptContexts.txInfo(PlutusLanguage.V2, tx, env), Right(expected))
  }

  test("Plutus V1 rejects reference inputs, inline datums and reference scripts") {
    val addr = Address.decode(bytesOf("60" + hash28(7))).fold(e => fail(e), identity)
    val in = TxIn(bytesOf(hash32(1)), 0)
    val plain = TxOut(addr, Value.coin(1))
    def info(body: TxBody, spent: Map[TxIn, TxOut], refs: Map[TxIn, TxOut] = Map.empty) =
      ScriptContexts.txInfo(
        PlutusLanguage.V1,
        Tx(body, Witnesses.empty, 0, true, None),
        ScriptContexts.Environment(8, spent, refs, clock)
      )
    val base = TestLedger.body("v1", Vector(in), Vector(plain), 1)
    assert(info(base, Map(in -> plain)).isRight)
    assert(info(base.copy(referenceInputs = Vector(in)), Map(in -> plain), Map(in -> plain)).isLeft)
    val inline = plain.copy(datum = Datum.Inline(bytesOf("01")))
    assert(info(base, Map(in -> inline)).isLeft)
    assert(info(base.copy(outputs = Vector(inline)), Map(in -> plain)).isLeft)
  }

  test("the same output is a valid input for V2 and the spent datum is passed to the script") {
    val addr = Address.decode(bytesOf("70" + scriptHash)).fold(e => fail(e), identity)
    val in = TxIn(bytesOf(hash32(1)), 0)
    val datum = bytesOf(datumA)
    val out = TxOut(addr, Value.coin(5)).copy(datum = Datum.Inline(datum))
    val b = TestLedger.body("spend", Vector(in), Vector(out), 1)
    val redeemer = Redeemer(0, 0, bytesOf(datumB), ExUnits(1, 2))
    val tx = Tx(b, Witnesses(Vector.empty, redeemers = Vector(redeemer)), 0, true, None)
    val env = ScriptContexts.Environment(8, Map(in -> out), Map.empty, clock)
    val info = ScriptContexts.txInfo(PlutusLanguage.V2, tx, env).toOption.get
    val purpose = Purpose.resolve(b, env.spent, 0, 0).get
    val args = ScriptContexts.arguments(PlutusLanguage.V2, info, tx, redeemer, purpose)
    // [datum, redeemer, context]; the context's purpose is Spending (constructor 1)
    assertEquals(args.map(_.size), Right(3))
    val Right(Vector(d, r, PlutusData.Constr(_, Vector(i, p)))) = args: @unchecked
    assertEquals(d, constr(0, pint(1)))
    assertEquals(r, pint(5))
    assertEquals(i, info)
    assertEquals(p, constr(1, constr(0, constr(0, PlutusData.B(in.id)), pint(0))))
  }
