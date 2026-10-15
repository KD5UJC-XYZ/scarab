// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as C}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.plutus.{DataSerialiser, PlutusData, ScalusEvaluator}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.TestLedger.*

/** Preprod transaction f90dce57 (block 5,183,974, slot 133,883,340, epoch 313) carries a native
  * script nested 5,383 levels deep (CBOR depth 10,772). The first preprod sync of the Scala node
  * stopped there (`decode block: depth limit exceeded`); the Haskell nodes accepted the block,
  * having no nesting limit but the size of the block. These tests run on a 256 KiB stack, which a
  * recursive function over that depth overflows, with items nested as deeply as a 90 KB block (the
  * mainnet and preprod maximum block body size) can hold them.
  */
class DeepNestingSuite extends FunSuite:
  private val dir = Path.of("fixtures/preprod")
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(fail(_), identity)
  private def raw(xs: Int*): Bytes = Bytes.fromArray(xs.map(_.toByte).toArray)
  private def repeat(unit: Bytes, n: Int): Bytes =
    val a = unit.toArray
    val out = new Array[Byte](a.length * n)
    var i = 0
    while i < n do
      System.arraycopy(a, 0, out, i * a.length, a.length)
      i += 1
    Bytes.fromArray(out)

  /** The depth of a 90,112-byte block body, the most a block can nest items. */
  private val MaxLevels = 90000

  /** Run `body` on a thread whose stack is far too small for recursion at these depths. */
  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.get.fold(throw _, identity)

  // --- the recorded transaction ------------------------------------------------------------------

  private val txBytes = hex(Files.readString(dir.resolve("tx-f90dce57.hex")).trim)

  private def parsed: Tx =
    val root = Cbor.decode(txBytes).fold(fail(_), identity)
    root.value match
      case C.Arr(xs) =>
        Tx
          .fromBlock(Block.Transaction(xs(0).original, xs(1).original, None, true), Era.Conway)
          .fold(e => fail(e), identity)
      case other => fail(s"not a transaction: $other")

  private val input: (TxIn, TxOut) =
    val e = parser
      .parse(Files.readString(dir.resolve("tx-f90dce57-inputs.json")))
      .toOption
      .get
      .hcursor
      .downField("inputs")
      .downArray
    val lovelace = e.get[String]("lovelace").toOption.get.toLong
    val addr = hex(e.get[String]("address").toOption.get)
    val legacy = Bytes(
      Vector(0x82, 0x58, addr.size).map(_.toByte) ++ addr.value ++
        Vector(0x1a, lovelace >> 24, lovelace >> 16, lovelace >> 8, lovelace).map(_.toByte)
    )
    TxIn(
      hex(e.get[String]("txHash").toOption.get),
      BigInt(e.get[Int]("index").toOption.get)
    ) -> TxOut.fromBytes(legacy).fold(e => fail(e), identity)

  private def nativeDepth(s: NativeScript): Int =
    var depth = 0
    var cur = s
    var more = true
    while more do
      cur match
        case NativeScript.AllOf(Vector(next)) =>
          depth += 1
          cur = next
        case _ => more = false
    depth

  test("the recorded transaction decodes on a small stack, its script 5,383 levels deep") {
    val tx = onSmallStack(parsed)
    assertEquals(
      tx.body.id.hex,
      "f90dce5765108da976abdbb9fc618f9a6ffd9fa4d93b2f288eed1808545424c9"
    )
    assertEquals(tx.witnesses.scripts.size, 1)
    val script = tx.witnesses.scripts.head.native.get
    assertEquals(nativeDepth(script), 5383)
    assertEquals(
      tx.witnesses.scripts.head.hash.hex,
      "ff3efca65569f6b0b868a3d34abdb1ad8eccf745e0da71fa94fb4f18"
    )
    // the one vkey witness is the signature the innermost script asks for
    assertEquals(tx.witnesses.vkeys.size, 1)
    assert(
      script.eval(
        Set(xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(tx.witnesses.vkeys.head._1)),
        None,
        None
      )
    )
    assert(!onSmallStack(script.eval(Set.empty, None, None)))
  }

  test("the recorded transaction passes the ledger rules") {
    val ada = BigInt(1000000)
    val params = TestLedger.params(10).copy(minFeeA = 44, minFeeB = 155381, keyDeposit = 2 * ada)
    val base = state(10, Vector(input)).copy(params = params, epoch = 313)
    val world = base.copy(reserves = Supply - (base.total - base.reserves))
    val e = env.copy(slot = 133883340, epoch = 313)
    val outcome = onSmallStack(TxRules(world, parsed, e))
    assert(outcome.isRight, outcome.toString)
  }

  // --- native scripts ------------------------------------------------------------------------------

  private def nested(levels: Int): Bytes =
    // all [ all [ ... sig ]]: `82 01 81` per level, then `82 00 581c <hash>`
    repeat(raw(0x82, 0x01, 0x81), levels) ++ raw(0x82, 0x00, 0x58, 0x1c) ++ repeat(raw(0xab), 28)

  test("a native script as deep as a block holds decodes, evaluates, and persists") {
    val n = 30000 // 3 bytes a level
    onSmallStack {
      val root = Cbor.decode(nested(n)).fold(fail(_), identity)
      val script = NativeScript.decode(root).fold(e => fail(e.message), identity)
      assertEquals(nativeDepth(script), n)
      assert(script.eval(Set(repeat(raw(0xab), 28)), None, None))
      assert(!script.eval(Set.empty, None, None))
      assertEquals(Script.native(root).map(_.hash.size), Right(28))
      // compared through their encodings: `==` on the case classes recurses
      val out = Codec.encode(script)
      val back = Codec.decode[NativeScript](out).fold(fail(_), identity)
      assertEquals(nativeDepth(back), n)
      assert(java.util.Arrays.equals(Codec.encode(back), out))
      // the recorded bytes come back out of the value, level for level
      assertEquals(Cbor.encode(root.value).map(_.size.toLong), Right(root.original.size.toLong))
    }
  }

  test("native scripts of every shape nest alike, a malformed one is an error not a crash") {
    // any [ n_of_k 1 [ invalid_before 5, ... ] ] nested 10000 times
    val unit = raw(0x82, 0x02, 0x81, 0x83, 0x03, 0x01, 0x81)
    val leaf = raw(0x82, 0x04, 0x05)
    onSmallStack {
      val n = 10000
      val ok = Cbor.decode(repeat(unit, n) ++ leaf).fold(fail(_), identity)
      val script = NativeScript.decode(ok).fold(e => fail(e.message), identity)
      assert(script.eval(Set.empty, Some(BigInt(5)), None))
      assert(!script.eval(Set.empty, Some(BigInt(4)), None))
      val bad = Cbor.decode(repeat(unit, n) ++ raw(0x82, 0x09, 0x00)).fold(fail(_), identity)
      assert(NativeScript.decode(bad).isLeft)
    }
  }

  // --- Plutus data ------------------------------------------------------------------------------------

  private def decodeData(b: Bytes): PlutusData = PlutusData.decode(b).fold(fail(_), identity)

  private def depthOf(d: PlutusData): Int =
    var depth = 0
    var cur = d
    var more = true
    while more do
      cur match
        case PlutusData.Constr(_, Vector(next)) => depth += 1; cur = next
        case PlutusData.List(Vector(next))      => depth += 1; cur = next
        case PlutusData.Map(Vector((_, next)))  => depth += 1; cur = next
        case _                                  => more = false
    depth

  private val shapes: Vector[(String, Bytes, Bytes, Int)] = Vector(
    // (name, one level, innermost, bytes a level)
    ("lists", raw(0x81), raw(0x00), 1),
    ("indefinite lists", raw(0x9f), raw(0x00), 1),
    ("maps", raw(0xa1, 0x00), raw(0x00), 2),
    ("constructors", raw(0xd8, 0x79, 0x81), raw(0x00), 3),
    ("long constructors", raw(0xd9, 0x05, 0x00, 0x81), raw(0x00), 4),
    ("tag 102 constructors", raw(0xd8, 0x66, 0x82, 0x01, 0x81), raw(0x00), 5)
  )

  shapes.foreach { (name, unit, inner, width) =>
    test(s"Plutus data of nested $name, as deep as a block, decodes and re-encodes") {
      val levels = MaxLevels / width
      val closing = if name == "indefinite lists" then repeat(raw(0xff), levels) else Bytes.empty
      val wire = repeat(unit, levels) ++ inner ++ closing
      onSmallStack {
        val data = decodeData(wire)
        assertEquals(depthOf(data), levels)
        // through the machine's Data and the reference's serialiseData, and back
        val scalus = ScalusEvaluator.toData(data)
        val serialised = Bytes.fromArray(DataSerialiser.serialise(scalus))
        // compared through their encodings: `==` on the case classes recurses
        val back = decodeData(serialised)
        assertEquals(depthOf(back), levels)
        assertEquals(
          Bytes.fromArray(DataSerialiser.serialise(ScalusEvaluator.toData(back))),
          serialised
        )
      }
    }
  }

  test("Plutus data too deep for the stack still hashes by its original bytes") {
    val wire = repeat(raw(0x81), MaxLevels) ++ raw(0x00)
    val root = onSmallStack(Cbor.decode(wire).fold(fail(_), identity))
    assertEquals(root.original, wire)
    assertEquals(
      xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(root.original),
      xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(wire)
    )
  }

  // --- metadata ----------------------------------------------------------------------------------------

  test("auxiliary data nested as deep as a block is carried by its bytes and hashed") {
    // a Shelley block (preprod, from Origin) with the auxiliary data of its one transaction
    // replaced by metadata `{0: {0: {0: ... 0}}}`, 44,000 levels
    val blocks =
      Files.readAllLines(dir.resolve("blocks.hex")).toArray.toVector.map(l => hex(l.toString))
    val shelley = blocks
      .map(b => Cbor.decode(b).fold(fail(_), identity))
      .find { n =>
        n.value match
          case C.Arr(Vector(tag, _)) => tag.value == C.UInt(2)
          case _                     => false
      }
      .get
    val parts = shelley.value match
      case C.Arr(Vector(_, body)) =>
        body.value match
          case C.Arr(xs) => xs
          case other     => fail(s"$other")
      case other => fail(s"$other")
    val metadata = repeat(raw(0xa1, 0x00), 44000) ++ raw(0x00)
    val deep = Cbor.decode(metadata).fold(fail(_), identity)
    val aux = C.Map(Vector(Node(C.UInt(0), Bytes.empty) -> deep))
    val block = Cbor
      .encode(
        C.Arr(
          Vector(
            Node(C.UInt(2), Bytes.empty),
            Node(
              C.Arr(
                Vector(
                  parts(0),
                  Node(C.Arr(Vector(Node(C.Map(Vector.empty), Bytes.empty))), Bytes.empty),
                  Node(C.Arr(Vector(Node(C.Map(Vector.empty), Bytes.empty))), Bytes.empty),
                  Node(aux, Bytes.empty)
                )
              ),
              Bytes.empty
            )
          )
        )
      )
      .fold(fail(_), identity)
    val decoded = onSmallStack(Block.decodeShelley(block))
    decoded match
      case Right(b: Block.Shelley) =>
        assertEquals(b.transactions.size, 1)
        assertEquals(b.transactions.head.auxiliaryData, Some(metadata))
      case other => fail(other.toString)
  }
end DeepNestingSuite
