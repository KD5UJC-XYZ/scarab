// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.mempack

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{CborRead, Datum, ScriptKind, TxOut, Value}
import xyz.kd5ujc.scarab.ledger.state.{
  Allegra,
  Alonzo,
  Babbage,
  Conway,
  EraTag,
  Mary,
  Shelley,
  ShelleyBasedEra
}

import MemPack.{Failure, Feature, MultiAssetFault, Packer, Quantity, TagSite, Unpacker}

/** The MemPack `TxOut` codec against bytes the Haskell node wrote and against itself.
  *
  * Real bytes: `fixtures/mempack/preprod-conway-sample.tsv` (95 outputs picked from the 4,526,058
  * of the `tables` file of the Mithril preprod snapshot at slot 136,511,997, written by
  * cardano-node 11.1.3; provenance in `fixtures/mempack/README.md`), each with the projection that
  * the independent Python decoder `scripts/oracle/mempack.py` gives, and the `tables` files of the
  * seven devnet snapshots of `fixtures/haskell-state`, one per era. The properties generate outputs
  * for every era and check the round trips, the choice of constructor and that no input makes the
  * decoder throw.
  */
class TxOutMemPackSuite extends ScalaCheckSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(fail(_), identity)

  private def right[A](e: Either[Failure, A]): A = e.fold(f => fail(f.toString), identity)

  // ---------------------------------------------------------------- VarLen and the cursor

  private val varLenVectors: Vector[(Long, String)] = Vector(
    0L -> "00",
    1L -> "01",
    127L -> "7f",
    128L -> "8100",
    255L -> "817f",
    16383L -> "ff7f",
    16384L -> "818000",
    0xffffffffL -> "8fffffff7f",
    Long.MaxValue -> "ffffffffffffffff7f",
    Long.MinValue -> "81808080808080808000",
    -1L -> "81ffffffffffffffff7f"
  )

  test("VarLen: most significant group first, minimal, up to 64 bits") {
    varLenVectors.foreach { (v, expected) =>
      val p = new Packer(MemPack.varLenSize(v))
      p.varLen(v)
      assertEquals(p.result.hex, expected, s"pack $v")
      assertEquals(Unpacker.of(hex(expected)).varLen, Right(v), s"unpack $expected")
    }
  }

  test("VarLen: more than 64 bits, a missing end and a missing byte are refused") {
    // eleven groups, and a tenth group that sets a bit above the 64th
    assertEquals(
      Unpacker.of(hex("818080808080808080" + "8000")).varLen,
      Left(Failure.VarLenTooLong)
    )
    assertEquals(Unpacker.of(hex("82808080808080808000")).varLen, Left(Failure.VarLenTooLong))
    assertEquals(Unpacker.of(hex("8180")).varLen, Left(Failure.Truncated(1, 0)))
    assertEquals(Unpacker.of(Bytes.empty).tag, Left(Failure.Truncated(1, 0)))
  }

  test("a length that cannot be an Int is refused") {
    assertEquals(
      Unpacker.of(hex("8880808000")).length,
      Left(Failure.LengthOutOfRange(1L << 31))
    )
  }

  // ---------------------------------------------------------------- real bytes: the preprod sample

  private final case class Sample(cls: String, key: Bytes, value: Bytes, projection: Json)

  private lazy val samples: Vector[Sample] =
    Files
      .readAllLines(Path.of("fixtures/mempack/preprod-conway-sample.tsv"))
      .asScala
      .toVector
      .map { line =>
        val f = line.split('\t')
        Sample(f(0), hex(f(1)), hex(f(2)), parser.parse(f(3)).fold(e => fail(e.message), identity))
      }

  test("the sample covers every constructor, address kind, script language and value shape") {
    val tags = samples.map(_.value(0) & 0xff).toSet
    assertEquals(tags, Set(0, 1, 2, 3, 4, 5))
    List(
      "script-native",
      "script-v1",
      "script-v2",
      "script-v3",
      "addr8",
      "addr4",
      "ma-policies>=24"
    )
      .foreach(c => assert(samples.exists(_.cls.contains(c)), c))
    assert(samples.size >= 90, samples.size)
  }

  private def assetsOf(j: Json): Map[Bytes, Map[Bytes, BigInt]] =
    j.hcursor.downField("assets").as[Map[String, Map[String, BigInt]]].getOrElse(Map.empty).map {
      (p, names) => hex(p) -> names.map((n, q) => hex(n) -> q)
    }

  test("every output of the sample decodes to what the independent decoder reads") {
    samples.foreach { s =>
      val out = right(TxOutMemPack.decode[Conway](s.value))
      val j = s.projection.hcursor
      assertEquals(out.address.raw.hex, j.get[String]("address").toOption.get, s.cls)
      assertEquals(out.value.coin, j.get[BigInt]("lovelace").toOption.get, s.cls)
      assertEquals(out.value.assets, assetsOf(s.projection), s.cls)
      val datum = (j.get[String]("datumHash").toOption, j.get[String]("inlineDatum").toOption) match
        case (Some(h), _) => Datum.Hash(hex(h))
        case (_, Some(d)) => Datum.Inline(hex(d))
        case _            => Datum.NoDatum
      assertEquals(out.datum, datum, s.cls)
      assertEquals(
        out.scriptRef.map(_.hash.hex),
        j.get[String]("scriptRef").toOption,
        s.cls
      )
    }
  }

  test("every output of the sample packs back to the bytes the node wrote") {
    // the sample is of a young network: every constructor is the canonical one
    samples.foreach { s =>
      assert(checkPacking[Conway](s.cls, s.value), s.cls)
    }
  }

  test("the canonical CBOR of a decoded output reads back to the same output") {
    samples.foreach { s =>
      val out = right(TxOutMemPack.decode[Conway](s.value))
      assertEquals(TxOut.fromBytes(out.raw), Right(out), s.cls)
      assertEquals(out.valueSize > 0, true)
    }
  }

  test("the sample is refused by an era that lacks its constructor, with the reason") {
    val byTag = samples.groupBy(_.value(0) & 0xff)
    val two = byTag(2).head.value
    val inline = byTag(4).head.value
    val ref = samples.find(_.cls.contains("script-v3")).get.value
    assertEquals(
      TxOutMemPack.decode[Mary](two),
      Left(Failure.NotInEra(Feature.Addr28Constructor, xyz.kd5ujc.scarab.protocol.Era.Mary))
    )
    assertEquals(
      TxOutMemPack.decode[Alonzo](inline),
      Left(Failure.NotInEra(Feature.InlineDatum, xyz.kd5ujc.scarab.protocol.Era.Alonzo))
    )
    assertEquals(
      TxOutMemPack.decode[Alonzo](ref),
      Left(Failure.NotInEra(Feature.ReferenceScript, xyz.kd5ujc.scarab.protocol.Era.Alonzo))
    )
    assertEquals(
      TxOutMemPack.decode[Babbage](ref).left.map(_.getClass.getSimpleName.takeWhile(_ != '$')),
      Left("NotInEra")
    )
  }

  // ---------------------------------------------------------------- real bytes: the devnet snapshots

  /** Entries of a `tables` file read whole: `[ {key bytes: value bytes} ]`. */
  private def tableEntries(path: Path): Vector[(Bytes, Bytes)] =
    val node = Cbor.decode(Bytes.fromArray(Files.readAllBytes(path))).fold(fail(_), identity)
    val items = CborRead.array(node, "tables").fold(fail(_), identity)
    assertEquals(items.size, 1)
    CborRead.map(items.head, "tables").fold(fail(_), identity).map { (k, v) =>
      (
        CborRead.bytes(k, "key").fold(fail(_), identity),
        CborRead.bytes(v, "value").fold(fail(_), identity)
      )
    }

  /** A packed output is canonical when packing what it decodes to gives the same bytes. The
    * devnet's genesis outputs are not in Alonzo and later: they are base-address, ada-only outputs
    * that the eras before Alonzo stored as tag 0 and `upgradeTxOut` kept (`Alonzo/TxOut.hs:340`).
    */
  private def checkPacking[E <: EraTag](label: String, value: Bytes)(using
      ShelleyBasedEra[E]
  ): Boolean =
    val out = right(TxOutMemPack.decode[E](value))
    val again = right(TxOutMemPack.encode[E](out))
    assertEquals(TxOutMemPack.decode[E](again), Right(out), label)
    assertEquals(TxOut.fromBytes(out.raw), Right(out), label)
    val same = again == value
    if !same then
      // the only difference allowed: the compact form of a base address (tag 2 and 3) against the
      // general one (0 and 1), the same output
      assert(
        (value(0) == 0 && again(0) == 2) || (value(0) == 1 && again(0) == 3),
        s"$label: ${value.hex} against ${again.hex}"
      )
    same

  private def devnet[E <: EraTag](dir: String)(using ShelleyBasedEra[E]): Unit =
    test(s"the devnet snapshot $dir: its outputs decode and pack back to the same output") {
      val entries = tableEntries(Path.of("fixtures/haskell-state", dir, "tables"))
      assert(entries.nonEmpty)
      entries.foreach { (key, value) =>
        assertEquals(key.size, 34)
        checkPacking[E](key.hex, value)
      }
    }

  devnet[Shelley]("shelley-1000")
  devnet[Allegra]("allegra-1800")
  devnet[Mary]("mary-2308")
  devnet[Alonzo]("alonzo-3013")
  devnet[Babbage]("babbage-4000")
  devnet[Conway]("conway-4593")

  test("the devnet snapshot of Byron has an empty table") {
    assertEquals(tableEntries(Path.of("fixtures/haskell-state/byron-25/tables")), Vector.empty)
  }

  // ---------------------------------------------------------------- by hand

  private val keyHash = hex("00112233445566778899aabbccddeeff00112233445566778899aabb")
  private val stakeHash = hex("aabbccddeeff00112233445566778899aabbccddeeff001122334455")
  private val datumHash = hex("0f" * 32)
  private def baseAddress(net: Int, payScript: Boolean, stakeScript: Boolean): Bytes =
    val header = (if payScript then 0x10 else 0) | (if stakeScript then 0x20 else 0) | net
    Bytes.fromArray(Array(header.toByte)) ++ keyHash ++ stakeHash
  private val enterprise = Bytes.fromArray(Array(0x61.toByte)) ++ keyHash

  private def parts(
      address: Bytes,
      value: Value = Value.coin(2000000),
      datum: Datum = Datum.NoDatum,
      script: Option[RefScript] = None
  ) = TxOutParts(address, value, datum, script)

  private def tagOf[E <: EraTag](p: TxOutParts)(using ShelleyBasedEra[E]): Int =
    right(TxOutMemPack.encodeParts[E](p))(0) & 0xff

  test("the constructor follows mkTxOut: ada-only to a base address is the compact tags 2 and 3") {
    val base = baseAddress(1, false, false)
    assertEquals(tagOf[Conway](parts(base)), 2)
    assertEquals(tagOf[Conway](parts(base, datum = Datum.Hash(datumHash))), 3)
    assertEquals(tagOf[Conway](parts(enterprise)), 0)
    assertEquals(tagOf[Conway](parts(enterprise, datum = Datum.Hash(datumHash))), 1)
    assertEquals(tagOf[Conway](parts(base, datum = Datum.Inline(hex("4100")))), 4)
    assertEquals(
      tagOf[Conway](parts(base, Value(5, Map(keyHash -> Map(Bytes.empty -> BigInt(1)))))),
      0
    )
    val script = RefScript(ScriptKind.PlutusV2, hex("4e4d01000033222220051200120011"))
    assertEquals(tagOf[Conway](parts(base, script = Some(script))), 5)
    // earlier eras have only tag 0 (Shelley to Mary) and tags 0 to 3 (Alonzo)
    assertEquals(tagOf[Mary](parts(base)), 0)
    assertEquals(tagOf[Alonzo](parts(base)), 2)
    assertEquals(tagOf[Alonzo](parts(base, datum = Datum.Hash(datumHash))), 3)
  }

  test(
    "a decoder takes the constructor an upgraded output kept; the encoder does not reproduce it"
  ) {
    // a base-address, ada-only output with tag 0: built by hand as the Mary era would have stored it
    val base = baseAddress(0, false, true)
    val packed = hex("00" + "39" + base.hex + "00" + "64")
    val out = right(TxOutMemPack.decode[Conway](packed))
    assertEquals(out.value.coin, BigInt(100))
    assertEquals(out.address.raw, base)
    val again = right(TxOutMemPack.encode[Conway](out))
    assertEquals(again(0).toInt, 2)
    assertEquals(right(TxOutMemPack.decode[Conway](again)), out)
  }

  test(
    "a multi-asset value is laid out as the reference's example: names descending, equal names stable"
  ) {
    val p1 = hex("a519f84e" + "00" * 24)
    val p2 = hex("f820a82c" + "00" * 24)
    def n(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
    val assets = Map(
      p1 -> Map(Bytes.empty -> BigInt(42)),
      p2 -> Map(n("Snark") -> BigInt(1), n("Boojum") -> BigInt(1))
    )
    // policies ascending: p1 (a5...) then p2 (f8...); names descending: Snark, Boojum, ""
    val (count, rep) = MultiAssetRep.build(assets).fold(f => fail(f.toString), identity)
    assertEquals(count, 3)
    // A: Snark 1, Boojum 1, "" 42; B: p2, p2, p1 offsets; C: ascending name offsets
    val expectedLength = 12 * 3 + 28 * 2 + "SnarkBoojum".length
    assertEquals(rep.size, expectedLength)
    val q = (0 until 3).map(i => rep(8 * i).toInt)
    assertEquals(q.toList, List(1, 1, 42))
    assertEquals(MultiAssetRep.read(3, rep), Right(assets))
  }

  test("values that have no compact form are refused with the field that is out of range") {
    val big = Value(BigInt(1) << 64, Map.empty)
    assertEquals(
      TxOutMemPack.encodeParts[Conway](parts(enterprise, big)),
      Left(Failure.OutOfRange(Quantity.Coin))
    )
    val negative = Value(1, Map(keyHash -> Map(Bytes.empty -> BigInt(-1))))
    assertEquals(
      TxOutMemPack.encodeParts[Conway](parts(enterprise, negative)),
      Left(Failure.OutOfRange(Quantity.Asset))
    )
    val tooMany =
      Value(
        1,
        (0 until 1500).map { i =>
          val policy = Bytes.fromArray(Array((i >> 8).toByte, i.toByte)) ++ Bytes.fromArray(
            new Array[Byte](26)
          )
          policy -> Map(Bytes.fromArray(Array(i.toByte)) -> BigInt(1))
        }.toMap
      )
    assert(TxOutMemPack.encodeParts[Conway](parts(enterprise, tooMany)).isLeft)
  }

  test("what an era does not have is refused, not guessed") {
    val withAssets = Value(1, Map(keyHash -> Map(Bytes.empty -> BigInt(1))))
    assertEquals(
      TxOutMemPack.encodeParts[Allegra](parts(enterprise, withAssets)),
      Left(Failure.NotInEra(Feature.MultiAssetValue, xyz.kd5ujc.scarab.protocol.Era.Allegra))
    )
    assertEquals(
      TxOutMemPack.encodeParts[Shelley](parts(enterprise, datum = Datum.Hash(datumHash))),
      Left(Failure.NotInEra(Feature.DatumHash, xyz.kd5ujc.scarab.protocol.Era.Shelley))
    )
    assertEquals(
      TxOutMemPack.encodeParts[Alonzo](parts(enterprise, datum = Datum.Inline(hex("00")))),
      Left(Failure.NotInEra(Feature.InlineDatum, xyz.kd5ujc.scarab.protocol.Era.Alonzo))
    )
    val v3 = RefScript(ScriptKind.PlutusV3, hex("4100"))
    assertEquals(
      TxOutMemPack.encodeParts[Babbage](parts(enterprise, script = Some(v3))),
      Left(Failure.NotInEra(Feature.PlutusV3, xyz.kd5ujc.scarab.protocol.Era.Babbage))
    )
    // the other direction: tag 1 of the value in a Shelley output is not a CompactCoin
    assertEquals(
      TxOutMemPack
        .decode[Shelley](hex("00" + "1d" + enterprise.hex + "01" + "00" + "00"))
        .left
        .map(_ => ()),
      Left(())
    )
  }

  test("malformed bytes are refused with the cause") {
    assertEquals(TxOutMemPack.decodeParts[Conway](Bytes.empty), Left(Failure.Truncated(1, 0)))
    assertEquals(
      TxOutMemPack.decodeParts[Conway](hex("09")),
      Left(Failure.UnknownTag(TagSite.TxOutConstructor, 9))
    )
    val good = samples.find(_.value(0) == 0.toByte).get.value
    assertEquals(
      TxOutMemPack.decodeParts[Conway](good ++ hex("00")),
      Left(Failure.TrailingBytes(1))
    )
    assertEquals(
      TxOutMemPack.decodeParts[Conway](good.take(good.size - 1)).left.map(_ => ()),
      Left(())
    )
    // multi-asset representations that are too short, point outside, or whose name offsets descend
    val tiny = hex("0100000000000000" + "0c00" + "0d00")
    assertEquals(
      MultiAssetRep.read(1, tiny.take(4)),
      Left(Failure.BadMultiAsset(MultiAssetFault.ShorterThanRegions))
    )
    val quantities = "0100000000000000" * 2
    val outside = hex(quantities + "2c00" + "2c00" + "0400" + "0300")
    assertEquals(
      MultiAssetRep.read(2, outside),
      Left(Failure.BadMultiAsset(MultiAssetFault.PolicyOutOfRange))
    )
    val beyond = hex(quantities + "1800" + "1800" + "3900" + "3900" + "00" * 28 + "00" * 4)
    assertEquals(
      MultiAssetRep.read(2, beyond),
      Left(Failure.BadMultiAsset(MultiAssetFault.NameOutOfRange))
    )
    val descend = hex(quantities + "1800" + "1800" + "3600" + "3400" + "00" * 28 + "00" * 4)
    assertEquals(
      MultiAssetRep.read(2, descend),
      Left(Failure.BadMultiAsset(MultiAssetFault.NameOffsetsDescend))
    )
  }

  // ---------------------------------------------------------------- generated outputs

  private def genBytes(n: Int): Gen[Bytes] =
    Gen.listOfN(n, Gen.choose(0, 255)).map(l => Bytes.fromArray(l.map(_.toByte).toArray))

  private val genNet: Gen[Int] = Gen.choose(0, 1)

  private val genAddress: Gen[Bytes] = Gen.oneOf(
    for
      net <- genNet
      ps <- Gen.oneOf(true, false)
      ss <- Gen.oneOf(true, false)
      pay <- genBytes(28)
      stake <- genBytes(28)
    yield
      val header = (if ps then 0x10 else 0) | (if ss then 0x20 else 0) | net
      Bytes.fromArray(Array(header.toByte)) ++ pay ++ stake
    ,
    for
      net <- genNet
      ps <- Gen.oneOf(true, false)
      pay <- genBytes(28)
    yield Bytes.fromArray(Array((0x60 | (if ps then 0x10 else 0) | net).toByte)) ++ pay,
    for
      net <- genNet
      pay <- genBytes(28)
      slot <- Gen.choose(0, 127)
      tx <- Gen.choose(0, 127)
      cert <- Gen.choose(0, 127)
    yield Bytes.fromArray(Array((0x40 | net).toByte)) ++ pay ++ Bytes.fromArray(
      Array(slot.toByte, tx.toByte, cert.toByte)
    ),
    for body <- Gen.choose(20, 60).flatMap(genBytes)
    yield Bytes.fromArray(Array(0x82.toByte)) ++ body
  )

  private val genCoin: Gen[BigInt] = Gen.oneOf(
    Gen.choose(0L, 100000000L).map(BigInt(_)),
    Gen.choose(0L, Long.MaxValue).map(BigInt(_)),
    Gen.const((BigInt(1) << 64) - 1)
  )

  private val genQuantity: Gen[BigInt] = Gen.oneOf(
    Gen.choose(1L, 1000L).map(BigInt(_)),
    Gen.choose(1L, Long.MaxValue).map(BigInt(_)),
    Gen.const((BigInt(1) << 64) - 1)
  )

  // a small alphabet of names so that equal names under different policies occur
  private val genName: Gen[Bytes] = Gen.oneOf(
    Gen.const(Bytes.empty),
    Gen.oneOf("a", "b", "ab", "Snark", "Boojum").map(s => Bytes.fromArray(s.getBytes("UTF-8"))),
    Gen.choose(0, 32).flatMap(genBytes)
  )

  private val genAssets: Gen[Map[Bytes, Map[Bytes, BigInt]]] =
    Gen.choose(0, 5).flatMap { policies =>
      Gen
        .listOfN(
          policies,
          for
            p <- genBytes(28)
            names <- Gen
              .choose(1, 4)
              .flatMap(k => Gen.listOfN(k, Gen.zip(genName, genQuantity)).map(_.toMap))
          yield p -> names
        )
        .map(_.toMap)
    }

  private val genNativeScript: Gen[Bytes] = for
    h1 <- genBytes(28)
    h2 <- genBytes(28)
    shape <- Gen.choose(0, 2)
  yield
    val sig1 = hex("8200581c") ++ h1
    val sig2 = hex("8200581c") ++ h2
    shape match
      case 0 => sig1
      case 1 => hex("820182") ++ sig1 ++ sig2
      case _ => hex("82041a000f4240") // [4, slot]: invalid before

  private def genScript(languages: Int): Gen[RefScript] =
    val plutus = (1 to languages).map(l => ScriptKind.values.find(_.tag == l).get)
    Gen.oneOf(
      genNativeScript.map(RefScript(ScriptKind.Native, _)),
      for
        k <- Gen.oneOf(plutus)
        b <- Gen.choose(1, 300).flatMap(genBytes)
      yield RefScript(k, b)
    )

  private def genDatum(hashes: Boolean, inline: Boolean): Gen[Datum] =
    Gen.frequency(
      3 -> Gen.const(Datum.NoDatum),
      (if hashes then 2 else 0) -> genBytes(32).map(Datum.Hash(_)),
      (if inline then 2 else 0) -> Gen.choose(1, 80).flatMap(genBytes).map(Datum.Inline(_))
    )

  /** Outputs valid in the era: no more than the era's constructors can hold. */
  private def genParts(
      assets: Boolean,
      hashes: Boolean,
      inline: Boolean,
      languages: Int
  ): Gen[TxOutParts] =
    for
      address <- genAddress
      coin <- genCoin
      as <- if assets then genAssets else Gen.const(Map.empty[Bytes, Map[Bytes, BigInt]])
      datum <- genDatum(hashes, inline)
      script <- if languages == 0 then Gen.const(None) else Gen.option(genScript(languages))
    yield TxOutParts(address, Value(coin, as), datum, script)

  private def era[E <: EraTag](
      name: String,
      addr28: Boolean,
      assets: Boolean,
      hashes: Boolean,
      inline: Boolean,
      languages: Int
  )(using ShelleyBasedEra[E]): Unit =
    val gen = genParts(assets, hashes, inline, languages)

    property(s"$name: parts pack and unpack to themselves") {
      forAll(gen) { p =>
        TxOutMemPack.encodeParts[E](p).flatMap(TxOutMemPack.decodeParts[E]) == Right(p)
      }
    }

    property(s"$name: the output's canonical CBOR reads back, and its parts are the parts") {
      forAll(gen) { p =>
        val out = right(TxOutMemPack.toTxOut(p))
        TxOut.fromBytes(out.raw) == Right(out) && TxOutMemPack.parts(out) == Right(p)
      }
    }

    property(s"$name: an output packs and unpacks to itself, and packing is stable") {
      forAll(gen) { p =>
        val out = right(TxOutMemPack.toTxOut(p))
        val bytes = right(TxOutMemPack.encode[E](out))
        TxOutMemPack.decode[E](bytes) == Right(out) && TxOutMemPack.encode[E](out) == Right(bytes)
      }
    }

    property(s"$name: the tag is the one mkTxOut chooses") {
      forAll(gen) { p =>
        val ada = p.value.assets.isEmpty
        val base = p.address.size == 57
        val expected =
          if p.script.nonEmpty then 5
          else
            p.datum match
              case Datum.Inline(_) => 4
              case Datum.Hash(_)   => if addr28 && ada && base then 3 else 1
              case Datum.NoDatum   => if addr28 && ada && base then 2 else 0
        tagOf[E](p) == expected
      }
    }

  era[Shelley](
    "Shelley",
    addr28 = false,
    assets = false,
    hashes = false,
    inline = false,
    languages = 0
  )
  era[Allegra](
    "Allegra",
    addr28 = false,
    assets = false,
    hashes = false,
    inline = false,
    languages = 0
  )
  era[Mary]("Mary", addr28 = false, assets = true, hashes = false, inline = false, languages = 0)
  era[Alonzo]("Alonzo", addr28 = true, assets = true, hashes = true, inline = false, languages = 0)
  era[Babbage]("Babbage", addr28 = true, assets = true, hashes = true, inline = true, languages = 2)
  era[Conway]("Conway", addr28 = true, assets = true, hashes = true, inline = true, languages = 3)

  property("the decoder returns a typed failure or a value for any bytes, and never throws") {
    forAll(Gen.choose(0, 120).flatMap(genBytes)) { b =>
      TxOutMemPack.decodeParts[Conway](b).fold(_ => true, _ => true)
    }
  }

  property("the decoder takes every proper prefix of a valid encoding as a failure") {
    forAll(genParts(assets = true, hashes = true, inline = true, languages = 3)) { p =>
      val bytes = right(TxOutMemPack.encodeParts[Conway](p))
      (0 until bytes.size).forall(n => TxOutMemPack.decodeParts[Conway](bytes.take(n)).isLeft)
    }
  }
