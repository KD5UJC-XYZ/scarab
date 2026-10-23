// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era

/** Multi-asset values of outputs and mint, read at the decoder version of the era.
  *
  * Reference: `decodeMultiAsset`, `decodeMaryValue`, `decodeIntegerBounded64` and
  * `isMultiAssetSmallEnough` in cardano-ledger `eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs`,
  * and `decodeMap` in `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs`
  * (commit f649f975). Below version 9 (Mary, Alonzo, Babbage) duplicate keys are merged with the
  * last entry winning, then zero quantities and empty policies are pruned; from version 9 (Conway)
  * a duplicate key, a zero quantity and an empty asset map fail the decode. In every version output
  * quantities are `Word64`, mint quantities `Int64`, and the compact-form bound
  * `44 * assets + 28 * policies <= 65535` holds. The cases follow the reference's `ValueSpec`
  * (`eras/mary/impl/test/Test/Cardano/Ledger/Mary/ValueSpec.hs`): zero-valued, nested-empty,
  * completely empty, negative and too-many-assets values for each version range.
  */
class MultiAssetDecodeSuite extends FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def node(hex: String) = CborRead.decode(bytes(hex)).fold(e => fail(e.toString), identity)

  private def head(major: Int, n: BigInt): String =
    def hex(v: BigInt, width: Int) = v.toString(16).reverse.padTo(width * 2, '0').reverse
    if n < 24 then hex(BigInt(major << 5) + n, 1)
    else if n < 256 then hex(BigInt((major << 5) + 24), 1) + hex(n, 1)
    else if n < 65536 then hex(BigInt((major << 5) + 25), 1) + hex(n, 2)
    else if n < (BigInt(1) << 32) then hex(BigInt((major << 5) + 26), 1) + hex(n, 4)
    else hex(BigInt((major << 5) + 27), 1) + hex(n, 8)

  private def uint(n: BigInt) = head(0, n)
  private def int(n: BigInt) = if n.signum >= 0 then head(0, n) else head(1, -1 - n)
  private def bstr(hex: String) = head(2, hex.length / 2) + hex
  private def map(entries: Seq[(String, String)]) =
    head(5, entries.size) + entries.map((k, v) => k + v).mkString

  private def policy(i: Int) = bstr(f"$i%02x" * 28)
  private def name(hex: String) = bstr(hex)

  /** `{policy: {name: quantity}}` with the quantity already encoded. */
  private def assets(entries: (Int, Seq[(String, String)])*) =
    map(entries.map((p, names) => policy(p) -> map(names.map((n, q) => name(n) -> q))))

  private def value(coin: BigInt, ma: String) = "82" + uint(coin) + ma

  private def read(
      hex: String,
      decoding: Value.Decoding
  ): Either[LedgerDecodeError, Value] = Value.decode(node(hex), "value", decoding)

  private def mint(
      hex: String,
      decoding: Value.Decoding
  ): Either[LedgerDecodeError, Map[Bytes, Map[Bytes, BigInt]]] =
    Value.multiasset(node(hex), "mint", signed = true, decoding)

  private val Pruning = Value.Decoding.Pruning
  private val Rejecting = Value.Decoding.Rejecting
  private val both = List(Pruning, Rejecting)

  private def key(i: Int) = bytes(f"$i%02x" * 28)
  private def pruned(hex: String) = read(hex, Pruning).map(_.assets)

  private val TwoTo64 = BigInt(1) << 64
  private val TwoTo63 = BigInt(1) << 63

  test("decoding follows the era: Mary to Babbage prune, Conway rejects") {
    assertEquals(Address.Mode.of(Era.Mary).values, Pruning)
    assertEquals(Address.Mode.of(Era.Alonzo).values, Pruning)
    assertEquals(Address.Mode.of(Era.Babbage).values, Pruning)
    assertEquals(Address.Mode.of(Era.Conway).values, Rejecting)
  }

  test("a well-formed value reads the same in both versions") {
    val hex = value(7, assets(1 -> Seq("01" -> uint(5), "02" -> uint(6)), 2 -> Seq("" -> uint(1))))
    val expected = Value(
      7,
      Map(
        key(1) -> Map(bytes("01") -> BigInt(5), bytes("02") -> BigInt(6)),
        key(2) -> Map(Bytes.empty -> BigInt(1))
      )
    )
    both.foreach(d => assertEquals(read(hex, d), Right(expected)))
  }

  test("a zero quantity fails from version 9 and is pruned below it") {
    val hex = value(1, assets(1 -> Seq("01" -> uint(0), "02" -> uint(3))))
    assertEquals(read(hex, Rejecting), Left(LedgerDecodeError.ZeroQuantity("value")))
    assertEquals(pruned(hex), Right(Map(key(1) -> Map(bytes("02") -> BigInt(3)))))
  }

  test("a policy whose quantities are all zero disappears below version 9") {
    val hex = value(1, assets(1 -> Seq("01" -> uint(0))))
    assertEquals(read(hex, Rejecting), Left(LedgerDecodeError.ZeroQuantity("value")))
    assertEquals(pruned(hex), Right(Map.empty))
  }

  test("an empty asset map fails from version 9 and is dropped below it") {
    val hex = value(1, assets(1 -> Seq.empty, 2 -> Seq("01" -> uint(4))))
    assertEquals(read(hex, Rejecting), Left(LedgerDecodeError.Empty("value assets")))
    assertEquals(pruned(hex), Right(Map(key(2) -> Map(bytes("01") -> BigInt(4)))))
  }

  test("a completely empty multi-asset map is accepted in both versions") {
    both.foreach(d => assertEquals(read(value(9, "a0"), d), Right(Value(9, Map.empty))))
    both.foreach(d => assertEquals(mint("a0", d), Right(Map.empty)))
  }

  test("a duplicate asset name fails from version 9 and the last entry wins below it") {
    val twice = value(1, assets(1 -> Seq("01" -> uint(5), "01" -> uint(6))))
    assertEquals(read(twice, Rejecting), Left(LedgerDecodeError.DuplicateKey("value asset name")))
    assertEquals(pruned(twice), Right(Map(key(1) -> Map(bytes("01") -> BigInt(6)))))
    // the last entry is the one kept, so a trailing zero removes the asset
    val zeroLast = value(1, assets(1 -> Seq("01" -> uint(5), "01" -> uint(0))))
    assertEquals(pruned(zeroLast), Right(Map.empty))
    val zeroFirst = value(1, assets(1 -> Seq("01" -> uint(0), "01" -> uint(5))))
    assertEquals(pruned(zeroFirst), Right(Map(key(1) -> Map(bytes("01") -> BigInt(5)))))
  }

  test("a duplicate policy fails from version 9 and the last map wins below it") {
    val hex = value(
      1,
      assets(1 -> Seq("01" -> uint(5)), 1 -> Seq("02" -> uint(6)))
    )
    assertEquals(read(hex, Rejecting), Left(LedgerDecodeError.DuplicateKey("value policy")))
    assertEquals(pruned(hex), Right(Map(key(1) -> Map(bytes("02") -> BigInt(6)))))
  }

  test("a duplicate in the mint map fails from version 9 and merges below it") {
    val hex = assets(1 -> Seq("01" -> int(-5), "01" -> int(-6)))
    assertEquals(mint(hex, Rejecting), Left(LedgerDecodeError.DuplicateKey("mint asset name")))
    assertEquals(mint(hex, Pruning), Right(Map(key(1) -> Map(bytes("01") -> BigInt(-6)))))
  }

  test("a zero mint quantity fails from version 9 and is pruned below it") {
    val hex = assets(1 -> Seq("01" -> uint(0)), 2 -> Seq("01" -> int(-1)))
    assertEquals(mint(hex, Rejecting), Left(LedgerDecodeError.ZeroQuantity("mint")))
    assertEquals(mint(hex, Pruning), Right(Map(key(2) -> Map(bytes("01") -> BigInt(-1)))))
  }

  private val bignum = "c249010000000000000000" // 2^64, which no unsigned integer item holds

  test("an output quantity is a Word64 in every version") {
    both.foreach { d =>
      assertEquals(
        read(value(1, assets(1 -> Seq("01" -> uint(TwoTo64 - 1)))), d).map(_.assets),
        Right(Map(key(1) -> Map(bytes("01") -> (TwoTo64 - 1))))
      )
      assert(read(value(1, assets(1 -> Seq("01" -> bignum))), d).isLeft)
      assert(read(value(1, assets(1 -> Seq("01" -> int(-1)))), d).isLeft)
    }
  }

  test("the coin of an output is a Word64 in every version") {
    both.foreach { d =>
      assertEquals(read(uint(TwoTo64 - 1), d), Right(Value(TwoTo64 - 1, Map.empty)))
      assert(read(bignum, d).isLeft)
      assert(read("82" + bignum + assets(1 -> Seq("01" -> uint(1))), d).isLeft)
    }
  }

  test("a mint quantity is an Int64 in every version") {
    both.foreach { d =>
      assertEquals(
        mint(assets(1 -> Seq("01" -> int(-TwoTo63), "02" -> int(TwoTo63 - 1))), d),
        Right(Map(key(1) -> Map(bytes("01") -> -TwoTo63, bytes("02") -> (TwoTo63 - 1))))
      )
      assertEquals(
        mint(assets(1 -> Seq("01" -> int(TwoTo63))), d).map(_ => ()),
        Left(LedgerDecodeError.NumberOutOfRange("mint", TwoTo63, -TwoTo63, TwoTo63 - 1))
      )
      assertEquals(
        mint(assets(1 -> Seq("01" -> int(-TwoTo63 - 1))), d).map(_ => ()),
        Left(LedgerDecodeError.NumberOutOfRange("mint", -TwoTo63 - 1, -TwoTo63, TwoTo63 - 1))
      )
    }
  }

  private def names(count: Int) = (0 until count).map(i => f"$i%04x" -> uint(1))

  test("the compact-form bound holds in every version: 1488 assets of one policy, not 1489") {
    // 44 * 1488 + 28 = 65500; 44 * 1489 + 28 = 65544 > 65535
    both.foreach { d =>
      assert(read(value(1, assets(1 -> names(1488))), d).isRight)
      assertEquals(
        read(value(1, assets(1 -> names(1489))), d).map(_ => ()),
        Left(LedgerDecodeError.MultiAssetTooBig("value", 1489, 1))
      )
      assertEquals(
        mint(assets(1 -> names(1489)), d).map(_ => ()),
        Left(LedgerDecodeError.MultiAssetTooBig("mint", 1489, 1))
      )
    }
  }

  test("each policy counts against the bound as well as each asset") {
    def spread(policies: Int, perPolicy: Int) =
      assets((1 to policies).map(p => p -> names(perPolicy))*)
    // 255 policies, 5 assets each: 44 * 1275 + 28 * 255 = 63240 fits
    assert(read(value(1, spread(255, 5)), Rejecting).isRight)
    // 255 policies, 6 assets each: 44 * 1530 + 28 * 255 = 74460 does not
    assertEquals(
      read(value(1, spread(255, 6)), Rejecting).map(_ => ()),
      Left(LedgerDecodeError.MultiAssetTooBig("value", 1530, 255))
    )
  }

  test("below version 9 the bound is taken after pruning") {
    val padded = names(1488) ++ (0 until 5).map(i => f"f$i%03x" -> uint(0))
    val oversized = (0 until 1489).map(i => f"$i%04x" -> uint(0))
    assertEquals(pruned(value(1, assets(1 -> padded))).map(_(key(1)).size), Right(1488))
    assertEquals(pruned(value(1, assets(1 -> oversized))), Right(Map.empty))
  }

  test("an asset name of 33 bytes is refused in every version") {
    both.foreach { d =>
      assertEquals(
        read(value(1, assets(1 -> Seq("00" * 33 -> uint(1)))), d).map(_ => ()),
        Left(LedgerDecodeError.AssetNameLength("value", 33))
      )
    }
  }

  private val enterprise = bstr("61" + "11" * 28)

  private def output(ma: String) = "82" + enterprise + value(2000000, ma)

  test("an output is read at the decoder version of its era") {
    val zero = output(assets(1 -> Seq("01" -> uint(0))))
    assertEquals(
      TxOut.decode(node(zero), Address.Mode.of(Era.Babbage)).map(_.value.assets),
      Right(Map.empty)
    )
    assertEquals(
      TxOut.decode(node(zero), Address.Mode.of(Era.Conway)).map(_ => ()),
      Left(LedgerDecodeError.ZeroQuantity("output value"))
    )
    // the post-Alonzo map form takes the same path
    val mapForm = "a2" + "00" + enterprise + "01" + value(2000000, assets(1 -> Seq.empty))
    assert(TxOut.decode(node(mapForm), Address.Mode.of(Era.Babbage)).isRight)
    assertEquals(
      TxOut.decode(node(mapForm), Address.Mode.of(Era.Conway)).map(_ => ()),
      Left(LedgerDecodeError.Empty("output value assets"))
    )
  }

  private def body(mintField: String) =
    "a4" + "00" + "81" + "82" + bstr("22" * 32) + "00" + "01" + "81" +
      output(assets(1 -> Seq("01" -> uint(1)))) + "02" + uint(170000) + "09" + mintField

  test("the mint field of a body is read at the decoder version of its era") {
    val zero = body(assets(1 -> Seq("01" -> uint(0))))
    assertEquals(
      TxBody.decode(bytes(zero), Era.Mary).map(_.mint),
      Right(Map.empty)
    )
    assertEquals(
      TxBody.decode(bytes(zero), Era.Conway).map(_ => ()),
      Left(LedgerDecodeError.ZeroQuantity("mint"))
    )
    val big = body(assets(1 -> Seq("01" -> uint(TwoTo63))))
    Era.values.filter(_.ordinal >= Era.Mary.ordinal).foreach { era =>
      assert(TxBody.decode(bytes(big), era).isLeft, era.toString)
    }
  }

  private val coinOnly = List(Era.Shelley, Era.Allegra)

  test("Shelley and Allegra read a coin only; Mary onward is unchanged") {
    coinOnly.foreach(e =>
      assertEquals(Address.Mode.of(e).values, Value.Decoding.CoinOnly, e.toString)
    )
    assertEquals(Address.Mode.of(Era.Mary).values, Pruning)
    // a multi-asset value is no `Coin` (`ShelleyTxOut`, cardano-ledger f649f975)
    val refused = Left(LedgerDecodeError.MultiAssetNotAllowed("value"))
    val CoinOnly = Value.Decoding.CoinOnly
    assertEquals(read(uint(5), CoinOnly), Right(Value.coin(5)))
    assertEquals(read(value(5, assets(1 -> Seq("01" -> uint(3)))), CoinOnly), refused)
    // an empty multi-asset map is still not a coin
    assertEquals(read(value(5, "a0"), CoinOnly), refused)
    assertEquals(read(value(5, assets(1 -> Seq("01" -> uint(0)))), CoinOnly), refused)
    assertEquals(
      Value.multiasset(node(assets(1 -> Seq("01" -> int(-1)))), "mint", signed = true, CoinOnly),
      Left(LedgerDecodeError.MultiAssetNotAllowed("mint"))
    )
  }

  test("a Shelley or Allegra output refuses a multi-asset value, in both output forms") {
    val adaOnly = "82" + enterprise + uint(2000000)
    val multi = output(assets(1 -> Seq("01" -> uint(3))))
    val emptyMulti = output("a0")
    val mapForm =
      "a2" + "00" + enterprise + "01" + value(2000000, assets(1 -> Seq("01" -> uint(3))))
    coinOnly.foreach { era =>
      val mode = Address.Mode.of(era)
      assertEquals(
        TxOut.decode(node(adaOnly), mode).map(_.value),
        Right(Value.coin(2000000)),
        era.toString
      )
      List(multi, emptyMulti, mapForm).foreach { hex =>
        assertEquals(
          TxOut.decode(node(hex), mode).map(_ => ()),
          Left(LedgerDecodeError.MultiAssetNotAllowed("output value")),
          s"$era $hex"
        )
      }
    }
    // the same bytes are an output from Mary
    List(Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway).foreach { era =>
      assert(TxOut.decode(node(multi), Address.Mode.of(era)).isRight, era.toString)
    }
  }

  test("a Shelley or Allegra body refuses an output with a multi-asset value") {
    def withOutput(out: String) =
      "a3" + "00" + "81" + "82" + bstr("22" * 32) + "00" + "01" + "81" + out + "02" + uint(170000)
    val multi = withOutput(output(assets(1 -> Seq("01" -> uint(3)))))
    val adaOnly = withOutput("82" + enterprise + uint(2000000))
    coinOnly.foreach { era =>
      assertEquals(
        TxBody.decode(bytes(multi), era).map(_ => ()),
        Left(LedgerDecodeError.MultiAssetNotAllowed("output value")),
        era.toString
      )
      assertEquals(
        TxBody.decode(bytes(adaOnly), era).map(_.outputs.map(_.value)),
        Right(Vector(Value.coin(2000000)))
      )
    }
    assert(TxBody.decode(bytes(multi), Era.Mary).isRight)
  }
