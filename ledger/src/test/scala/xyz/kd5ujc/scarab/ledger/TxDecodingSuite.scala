// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.Block
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** Era-parametric transaction decoding over the committed Preview blocks and certificates. */
class TxDecodingSuite extends FunSuite:
  private def txs(path: String): Vector[(Block.Shelley, Tx)] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))
      .map(Block.decodeShelley(_).fold(e => fail(e.message), identity))
      .collect { case b: Block.Shelley => b }
      .flatMap(b =>
        b.transactions
          .map(t => b -> Tx.fromBlock(t, b.era).fold(e => fail(s"${b.era} tx: $e"), identity))
      )

  test("Alonzo transactions decode: Byron inputs with bootstrap witnesses, fees, sizes") {
    val all = txs("fixtures/preview/alonzo-first-20.hex") ++ txs("fixtures/preview/alonzo-txs.hex")
    assert(all.nonEmpty)
    assert(all.exists(_._2.witnesses.bootstrap.nonEmpty))
    assert(all.forall(_._2.isValid))
    all.foreach { case (_, tx) =>
      assert(tx.body.fee > 0 && tx.size > tx.body.raw.size)
      assertEquals(tx.body.id, Blake2b.hash256.hash(tx.body.raw))
    }
    val pools =
      all.flatMap(_._2.body.certificates).collect { case c: Certificate.RegisterPool => c }
    assertEquals(pools.size, 3)
  }

  test("Babbage mint transaction: mint, auxiliary data hash and the fee size") {
    val all = txs("fixtures/preview/babbage-mint.hex")
    assertEquals(all.size, 1)
    val tx = all.head._2
    assertEquals(all.head._1.era.toString, "Babbage")
    assertEquals(tx.body.mint.size, 1)
    assertEquals(Some(Blake2b.hash256.hash(tx.auxiliary.get)), tx.body.auxDataHash)
    assert(tx.body.outputs.forall(_.address.kind.isInstanceOf[Address.Kind.Shelley]))
    // fee 185653 = 155381 + 44 * 688: the size excludes the is_valid byte
    assertEquals(tx.size, 688L)
    assertEquals(BigInt(155381) + 44 * tx.size, tx.body.fee)
  }

  test("Conway fixtures decode") {
    val all = txs("fixtures/preview/conway.hex")
    assert(all.nonEmpty)
    assert(all.forall(_._1.era.toString == "Conway"))
  }

  test("genesis delegation and instantaneous reward certificates decode") {
    def cert(hex: String) =
      Certificate.decode(
        CborRead.decode(Bytes.fromHex(hex).toOption.get).toOption.get
      )
    val g = "a" * 56
    val d = "b" * 56
    val v = "c" * 64
    assertEquals(
      cert(s"8405581c${g}581c${d}5820$v"),
      Right(
        Certificate.GenesisDelegate(
          Bytes.fromHex(g).toOption.get,
          Bytes.fromHex(d).toOption.get,
          Bytes.fromHex(v).toOption.get
        )
      )
    )
    // [6, [0, {[0, h]: 100}]] and [6, [1, 100]]
    val h = "d" * 56
    assertEquals(
      cert(s"82068200a18200581c${h}1864"),
      Right(
        Certificate.MoveRewards(
          Pot.Reserves,
          Right(Map(Credential.Key(Bytes.fromHex(h).toOption.get) -> BigInt(100)))
        )
      )
    )
    assertEquals(cert("820682011864"), Right(Certificate.MoveRewards(Pot.Treasury, Left(100))))
  }

  test("script hashes: native scripts hash their encoding, Plutus scripts their bytes") {
    val key = "e" * 56
    val node = CborRead
      .decode(Bytes.fromHex(s"8200581c$key").toOption.get)
      .toOption
      .get
    val native = Script.native(node).toOption.get
    assertEquals(
      native.hash,
      Blake2b.hash224.hash(Bytes(0.toByte +: Bytes.fromHex(s"8200581c$key").toOption.get.value))
    )
    val plutus = Script.plutus(ScriptKind.PlutusV2, Bytes(Vector(1, 2, 3)))
    assertEquals(plutus.hash, Blake2b.hash224.hash(Bytes(Vector(2, 1, 2, 3))))
  }

  test("pointer addresses decode their three variable-length naturals") {
    val hash = Vector.fill(28)(1.toByte)
    // slot 300 = 0x82 0x2c, tx index 5, cert index 0
    val raw = Bytes((0x40.toByte +: hash) ++ Vector(0x82, 0x2c, 0x05, 0x00).map(_.toByte))
    val a = Address.decode(raw).fold(e => fail(e), identity)
    assertEquals(
      a.kind,
      Address.Kind.Shelley(0, Credential.Key(Bytes(hash)), None, Some(Ptr(300, 5, 0)))
    )
  }

  test("a block whose datum is nested beyond 64 levels (Preview slot 22680061) decodes") {
    val all = txs("fixtures/preview/deeply-nested-datum.hex")
    assert(all.nonEmpty)
    assert(all.exists { case (_, tx) => tx.witnesses.datums.nonEmpty || tx.body.outputs.nonEmpty })
  }

  test("CBOR nesting decodes on an ordinary thread to the depth a block holds, or to a set limit") {
    def nested(levels: Int): Bytes = Bytes.wrap(Array.fill(levels)(0x81.toByte) :+ 0x00.toByte)
    val wide = Cbor.Limits(maxItems = 1 << 20)
    var results = Vector.empty[Boolean]
    val t = new Thread(() =>
      results = Vector(
        CborRead.decode(nested(90000), wide).isRight,
        CborRead.decode(nested(2049), wide.copy(maxDepth = 2048)).isLeft,
        CborRead.decode(nested(2048), wide.copy(maxDepth = 2048)).isRight
      )
    )
    t.start()
    t.join()
    assertEquals(results, Vector(true, true, true))
  }

  test(
    "a Conway witness set naming one redeemer purpose twice keeps it once (Preview slot 62567517)"
  ) {
    val all = txs("fixtures/preview/duplicate-redeemer-conway.hex")
    val duplicated = all.collect { case (_, tx) if tx.body.id.hex.startsWith("c373f449") => tx }
    assertEquals(duplicated.size, 1)
    val redeemers = duplicated.head.witnesses.redeemers
    assertEquals(redeemers.map(r => (r.tag, r.index)), Vector((1, BigInt(0))))
    assertEquals(duplicated.head.witnesses.totalExUnits, ExUnits(2301, 428149))
    // The fee the transaction paid covers the minimum fee only with the units counted once.
    val minFee = BigInt(44) * duplicated.head.size + 155381 +
      (Rational(577, 10000) * Rational(2301) + Rational(721, 10000000) * Rational(428149)).ceil
    assert(duplicated.head.body.fee >= minFee, s"${duplicated.head.body.fee} < $minFee")
  }
