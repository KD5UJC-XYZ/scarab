// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.legacy.*
import io.circe.parser
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as C}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.legacy.FlatTestLedger.*

import TestLedger.*

/** Preprod tx b1db2a41 (block 5,184,855, slot 133,902,219, epoch 313, Conway) carries the native
  * script `[3, -1, [sig, sig]]`. The reference decodes `n` of `script_n_of_k` as an `Int` (the
  * Allegra `Timelock`, `TimelockMOf !Int`): 64-bit signed, negative allowed, and `evalTimelock`
  * (`isValidMOf n xs`) holds whenever `n <= 0` and otherwise needs `n` valid children. The first
  * preprod sync of the Scala node stopped here, demanding an unsigned integer. A slot is a `Word64`
  * (`SlotNo`).
  */
class PreprodNOfKSuite extends ScalaCheckSuite:
  private val rs = RuleSet
  private val ada = BigInt(1000000)
  private val dir = Path.of("fixtures/preprod")
  private val Int64Min = -(BigInt(1) << 63)
  private val Int64Max = (BigInt(1) << 63) - 1
  private val Word64Max = (BigInt(1) << 64) - 1

  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get

  private val tx: Tx =
    val root = Cbor.decode(hex(Files.readString(dir.resolve("tx-b1db2a41.hex")).trim)).toOption.get
    root.value match
      case C.Arr(xs) =>
        val block = Block.Transaction(xs(0).original, xs(1).original, None, true)
        Tx.fromBlock(block, Era.Babbage).fold(e => fail(e.toString), identity)
      case other => fail(s"not a transaction: $other")

  private val inputs: Map[TxIn, TxOut] =
    val json = parser.parse(Files.readString(dir.resolve("tx-b1db2a41-inputs.json"))).toOption.get
    json.hcursor
      .downField("inputs")
      .focus
      .get
      .asArray
      .get
      .map { e =>
        val c = e.hcursor
        val addr = hex(c.get[String]("address").toOption.get)
        val lovelace = c.get[String]("lovelace").toOption.get.toLong
        // legacy output `[address, coin]`
        val raw = Bytes(
          Vector(0x82, 0x58, addr.size).map(_.toByte) ++ addr.value ++
            Vector(0x1a, lovelace >> 24, lovelace >> 16, lovelace >> 8, lovelace).map(_.toByte)
        )
        TxIn(hex(c.get[String]("txHash").toOption.get), BigInt(c.get[Int]("index").toOption.get)) ->
          TxOut.fromBytes(raw).fold(e => fail(e.toString), identity)
      }
      .toMap

  private val world: LedgerState =
    val params = TestLedger.params(10).copy(minFeeA = 44, minFeeB = 155381, keyDeposit = 2 * ada)
    val base = state(10, inputs.toVector).copy(params = params, epoch = 313)
    base.copy(reserves = Supply - (base.total - base.reserves))

  private val conwayEnv = env.copy(slot = 133902219, epoch = 313)

  private def rule(t: Tx): String = rs.tx(world, t, conwayEnv) match
    case Left(LedgerFailure.Rejected(_, f)) => f.ruleName
    case Left(other)                        => other.message
    case Right(_)                           => "accepted"

  test("the fixture is the recorded transaction: one native script, n = -1, no vkey witness") {
    assertEquals(
      tx.body.id.hex,
      "b1db2a411cb651a413840d3c8b112895a5bda2519a8ba6a372b8dd1ffc7746c2"
    )
    assert(tx.witnesses.vkeys.isEmpty)
    val scripts = tx.witnesses.scripts.flatMap(_.native)
    assertEquals(scripts.size, 1)
    scripts.head match
      case NativeScript.NOfK(n, ss) =>
        assertEquals(n, BigInt(-1))
        assertEquals(ss.size, 2)
      case other => fail(s"not n_of_k: $other")
  }

  test("the transaction validates: n = -1 holds with no signature at all") {
    assertEquals(rule(tx), "accepted")
  }

  // --- CBOR by hand, so any integer can be put on the wire ------------------------------------

  private def head(major: Int, v: BigInt): Vector[Byte] =
    val m = major << 5
    if v < 24 then Vector((m | v.toInt).toByte)
    else if v < 256 then Vector((m | 24).toByte, v.toByte)
    else if v < 65536 then Vector((m | 25).toByte, (v >> 8).toByte, v.toByte)
    else if v < (BigInt(1) << 32) then
      (m | 26).toByte +: (3 to 0 by -1).map(i => (v >> (8 * i)).toByte).toVector
    else if v <= Word64Max then
      (m | 27).toByte +: (7 to 0 by -1).map(i => (v >> (8 * i)).toByte).toVector
    else sys.error("beyond 64 bits")

  /** An integer as the wire has it: a major 0 or 1 item, or a bignum beyond 64 bits. */
  private def int(v: BigInt): Vector[Byte] =
    if v >= 0 && v <= Word64Max then head(0, v)
    else if v < 0 && -v - 1 <= Word64Max then head(1, -v - 1)
    else
      val mag = if v >= 0 then v else -v - 1
      val b = mag.toByteArray.dropWhile(_ == 0).toVector
      Vector((if v >= 0 then 0xc2 else 0xc3).toByte) ++ head(2, b.size) ++ b

  private val keyHash: Vector[Byte] =
    Vector(0x82, 0x00, 0x58, 0x1c).map(_.toByte) ++ Vector.fill(28)(7.toByte)

  private def nOfK(n: BigInt, k: Int): Bytes =
    Bytes(head(4, 3) ++ head(0, 3) ++ int(n) ++ head(4, k) ++ Vector.fill(k)(keyHash).flatten)

  private def timelock(tag: Int, slot: BigInt): Bytes =
    Bytes(head(4, 2) ++ head(0, tag) ++ int(slot))

  private def decode(b: Bytes): Either[NativeScript.DecodeError, NativeScript] =
    NativeScript.decode(Cbor.decode(b).toOption.get)

  // --- the reference's decode: `decodeInt` / `Word64` ----------------------------------------

  private val interesting: Gen[BigInt] = Gen.oneOf(
    Gen.oneOf(BigInt(-1), BigInt(0), BigInt(1), BigInt(2), BigInt(3), BigInt(4)),
    Gen.oneOf(
      Int64Min,
      Int64Min - 1,
      Int64Max,
      Int64Max + 1,
      Word64Max,
      Word64Max + 1,
      -Word64Max - 1,
      -Word64Max - 2
    ),
    Gen.choose(-1000L, 1000L).map(BigInt(_)),
    Gen.long.map(BigInt(_)),
    Gen.listOfN(12, Gen.choose(0, 255)).map(bs => BigInt(1, bs.map(_.toByte).toArray)),
    Gen.listOfN(12, Gen.choose(0, 255)).map(bs => -BigInt(1, bs.map(_.toByte).toArray))
  )

  property("n of n_of_k decodes exactly when it fits a signed 64-bit integer") {
    forAll(interesting, Gen.choose(0, 4)) { (n, k) =>
      val r = decode(nOfK(n, k))
      if n >= Int64Min && n <= Int64Max then
        assertEquals(
          r.map { case NativeScript.NOfK(m, ss) => (m, ss.size); case _ => (0, -1) },
          Right((n, k))
        )
      else if n >= -Word64Max - 1 && n <= Word64Max then
        assertEquals(r.left.map(identity), Left(NativeScript.DecodeError.RequiredOutOfRange(n)))
      else assert(r.isLeft, s"$n decoded")
      true
    }
  }

  property("a slot decodes exactly when it fits an unsigned 64-bit integer") {
    forAll(interesting, Gen.oneOf(4, 5)) { (v, tag) =>
      val r = decode(timelock(tag, v))
      if v >= 0 && v <= Word64Max then
        assertEquals(
          r,
          Right(
            if tag == 4 then NativeScript.InvalidBefore(v) else NativeScript.InvalidHereafter(v)
          )
        )
      else assert(r.isLeft, s"$v decoded")
      true
    }
  }

  test("the boundaries, by name") {
    assertEquals(decode(nOfK(Int64Min, 0)), Right(NativeScript.NOfK(Int64Min, Vector.empty)))
    assertEquals(
      decode(nOfK(Int64Max + 1, 0)),
      Left(NativeScript.DecodeError.RequiredOutOfRange(Int64Max + 1))
    )
    assertEquals(
      decode(nOfK(Int64Min - 1, 0)),
      Left(NativeScript.DecodeError.RequiredOutOfRange(Int64Min - 1))
    )
    assert(decode(timelock(4, Word64Max)).isRight)
    assert(decode(timelock(5, Word64Max + 1)).isLeft)
    assert(decode(timelock(4, BigInt(-1))).isLeft)
  }

  // --- the reference's evaluation: `isValidMOf` -------------------------------------------------

  /** `evalTimelock` 's `RequireMOf`, transcribed: stop at `n <= 0`, else count the valid children.
    */
  @annotation.tailrec
  private def isValidMOf(n: BigInt, rest: List[Boolean]): Boolean = rest match
    case Nil         => n <= 0
    case _ if n <= 0 => true
    case ok :: tail  => isValidMOf(if ok then n - 1 else n, tail)

  property("evaluation of n_of_k equals the reference's isValidMOf for any n and any signers") {
    val keys = Vector.tabulate(5)(i => Bytes(Vector.fill(28)(i.toByte)))
    forAll(interesting.map(_.max(Int64Min).min(Int64Max)), Gen.listOfN(5, Gen.oneOf(true, false))) {
      (n, present) =>
        val signers = keys.zip(present).collect { case (k, true) => k }.toSet
        val script = NativeScript.NOfK(n, keys.map(NativeScript.Sig(_)))
        script.eval(signers, None, None) == isValidMOf(n, keys.map(signers.contains).toList)
    }
  }

  test("n of 0, below 0, above the list, by name") {
    val k = Bytes(Vector.fill(28)(1.toByte))
    val one = Vector(NativeScript.Sig(k))
    def holds(n: BigInt, ss: Vector[NativeScript], signers: Set[Bytes]) =
      NativeScript.NOfK(n, ss).eval(signers, None, None)
    assert(holds(-1, one, Set.empty))
    assert(holds(Int64Min, one, Set.empty))
    assert(holds(0, one, Set.empty))
    assert(holds(0, Vector.empty, Set.empty))
    assert(holds(1, one, Set(k)))
    assert(!holds(1, one, Set.empty))
    assert(!holds(2, one, Set(k)))
    assert(!holds(Int64Max, one, Set(k)))
    assert(!holds(1, Vector.empty, Set.empty))
  }
