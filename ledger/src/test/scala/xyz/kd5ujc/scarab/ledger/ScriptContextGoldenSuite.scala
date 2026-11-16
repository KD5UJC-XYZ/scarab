// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage}
import munit.FunSuite

/** Script contexts of hand-built transactions against literal expectations of the encoding: the
  * shape Plutus V1, V2 and V3 scripts see.
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
    def arr(xs: String*): String = head(4, xs.size) + xs.mkString
    def tag(n: Int, x: String): String = head(6, n) + x

  import Cb.*

  private def h(seed: Int, length: Int): String =
    (0 until length).map(i => f"${(seed * 31 + i * 7 + 1) & 0xff}%02x").mkString

  private def hash28(seed: Int) = h(seed, 28)
  private def hash32(seed: Int) = h(seed, 32)

  // Plutus data: Constr 0 [1] and 5
  private val datumA = tag(121, arr(uint(1)))
  private val datumB = uint(5)

  private val scriptHash = hash28(1)

  private def bytesOf(hex: String) = Bytes.fromHex(hex).toOption.get

  private def clock(slot: BigInt): Either[TranslationError, BigInt] = Right(
    1666656000000L + slot * 1000
  )

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
    val env = ScriptContexts.Environment(
      TestLedger.usualEra(protocol),
      protocol,
      Map.empty,
      Map.empty,
      clock
    )
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
    val addr = Address.decode(bytesOf("60" + key)).fold(e => fail(e.toString), identity)
    val spentIn = TxIn(bytesOf(hash32(1)), 2)
    val b = TestLedger
      .body("shape", Vector(spentIn), Vector(TxOut(addr, Value.coin(1500000))), 200000)
      .copy(requiredSigners = Vector(bytesOf(key)))
    val tx = Tx(b, Witnesses.empty, 0, true, None)
    val env = ScriptContexts.Environment(
      TestLedger.usualEra(8),
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
    val addr = Address.decode(bytesOf("60" + hash28(7))).fold(e => fail(e.toString), identity)
    val in = TxIn(bytesOf(hash32(1)), 0)
    val plain = TxOut(addr, Value.coin(1))
    def info(body: TxBody, spent: Map[TxIn, TxOut], refs: Map[TxIn, TxOut] = Map.empty) =
      ScriptContexts.txInfo(
        PlutusLanguage.V1,
        Tx(body, Witnesses.empty, 0, true, None),
        ScriptContexts.Environment(TestLedger.usualEra(8), 8, spent, refs, clock)
      )
    val base = TestLedger.body("v1", Vector(in), Vector(plain), 1)
    assert(info(base, Map(in -> plain)).isRight)
    assert(info(base.copy(referenceInputs = Vector(in)), Map(in -> plain), Map(in -> plain)).isLeft)
    val inline = plain.copy(datum = Datum.Inline(bytesOf("01")))
    assert(info(base, Map(in -> inline)).isLeft)
    assert(info(base.copy(outputs = Vector(inline)), Map(in -> plain)).isLeft)
  }

  test("the same output is a valid input for V2 and the spent datum is passed to the script") {
    val addr = Address.decode(bytesOf("70" + scriptHash)).fold(e => fail(e.toString), identity)
    val in = TxIn(bytesOf(hash32(1)), 0)
    val datum = bytesOf(datumA)
    val out = TxOut(addr, Value.coin(5)).copy(datum = Datum.Inline(datum))
    val b = TestLedger.body("spend", Vector(in), Vector(out), 1)
    val redeemer = Redeemer(0, 0, bytesOf(datumB), ExUnits(1, 2))
    val tx = Tx(b, Witnesses(Vector.empty, redeemers = Vector(redeemer)), 0, true, None)
    val env =
      ScriptContexts.Environment(TestLedger.usualEra(8), 8, Map(in -> out), Map.empty, clock)
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

  // `getConwayScriptsNeeded` gives a voting redeemer the index of its voter among the keys of the
  // `VotingProcedures` map (`Conway/UTxO.hs:82-87`, cardano-ledger f649f975), which `Ord Voter`
  // orders (`Voter.ordering`, checked against the reference's derived order by `VoterOrderSuite`):
  // not the order in which the transaction lists them.
  test("voting redeemer indices name the voters in Ord Voter's order, not the transaction's") {
    def hash(b: Int) = Bytes.fromArray(Array.fill(28)(b.toByte))
    val ballot = Vector(GovActionId(Bytes.fromArray(Array.fill(32)(1.toByte)), 0) -> Vote.Yes)
    val listed = Vector(
      Voter.Pool(hash(1)),
      Voter.DRepVoter(Credential.Key(hash(2))),
      Voter.DRepVoter(Credential.Script(hash(3))),
      Voter.CommitteeHot(Credential.Key(hash(4))),
      Voter.CommitteeHot(Credential.Script(hash(0xff)))
    )
    val body =
      TestLedger.body("votes", Vector.empty, Vector.empty, 0, votes = listed.map(_ -> ballot))
    val expected = Vector(
      Voter.CommitteeHot(Credential.Script(hash(0xff))),
      Voter.CommitteeHot(Credential.Key(hash(4))),
      Voter.DRepVoter(Credential.Script(hash(3))),
      Voter.DRepVoter(Credential.Key(hash(2))),
      Voter.Pool(hash(1))
    )
    expected.zipWithIndex.foreach { (voter, index) =>
      assertEquals(
        Purpose.resolve(body, Map.empty, 4, index),
        Some(Purpose.Voting(index, voter)),
        s"voting redeemer $index"
      )
    }
    assertEquals(Purpose.resolve(body, Map.empty, 4, listed.size), None)
  }
