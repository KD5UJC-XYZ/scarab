// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.{Json, parser}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.byron.ByronCbor
import xyz.kd5ujc.scarab.ledger.state.VocabError
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** `DebugLedgerConfig` on configurations the recorded ones do not exercise: the Conway devnet's or
  * the eras capture's genesis directory with a file edited, read as the node reads its
  * configuration at start ([[NodeSetup.fromFiles]]), and the values each case edits written as the
  * reference's types write them: negative and bignum coins, a ratio object and absent minima, 24
  * delegates and 26 extra cost models (indefinite maps), wrapped stability windows, addresses of
  * one root, a system start in year 1,000,000,000 and anchor urls past 128 bytes. The last cases
  * give [[LedgerConfig.encode]] and [[GenesisConfig.encode]] values outside their types' ranges and
  * require the typed reasons.
  */
class LedgerConfigInputsSuite extends FunSuite:
  private val devnet = Path.of("fixtures/devnet/lsq/genesis")

  /** The eras capture's, whose chain starts in Byron: the Conway genesis is read, but no state is
    * translated with it at start.
    */
  private val eras = Path.of("fixtures/eras/genesis")

  private def file(dir: Path, name: String): Option[Array[Byte]] =
    Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name)))

  private def json(name: String, dir: Path = devnet): Json =
    parser.parse(Files.readString(dir.resolve(name))).fold(e => fail(e.message), identity)

  /** The setup of the directory `dir` with the files of `edits` replaced. */
  private def setupWith(edits: Map[String, Json], dir: Path = devnet): NodeSetup =
    NodeSetup
      .fromFiles(ReferenceBuild.Default)(name =>
        edits.get(name).map(_.noSpaces.getBytes(UTF_8)).orElse(file(dir, name))
      )
      .fold(e => fail(e.message), identity)

  private def answer(setup: NodeSetup): Bytes =
    setup.ledgerConfig.fold(e => fail(e.message), identity)

  private def decoded(b: Bytes): Node = Cbor.decode(b).fold(e => fail(e.message), identity)

  /** The item at `path`, each step an index into an array. */
  private def at(n: Node, path: Int*): Node =
    path.foldLeft(n)((node, i) =>
      node.value match
        case Value.Arr(xs) if i < xs.size => xs(i)
        case other                        => fail(s"no item $i in $other")
    )

  private def shelley: Json = json("shelley-genesis.json")
  private def params: Json = shelley.hcursor.downField("protocolParams").focus.getOrElse(Json.Null)

  /** The Shelley genesis with its protocol parameters replaced by `p`, not merged into them. */
  private def withParams(p: Json): Json = shelley.mapObject(_.add("protocolParams", p))

  private val recorded: Bytes =
    val golden = parser
      .parse(Files.readString(Path.of("fixtures/ntc-golden/debug-ledger-config.json")))
      .fold(e => fail(e.message), identity)
    golden.hcursor
      .downField("cases")
      .downN(0)
      .get[String]("result")
      .flatMap(h => Bytes.fromHex(h).left.map(e => io.circe.DecodingFailure(e.message, Nil)))
      .fold(e => fail(e.message), identity)

  test("the unedited directory answers the reference's bytes") {
    assertEquals(answer(setupWith(Map.empty)).hex, recorded.hex)
  }

  // `Coin` is an `Integer` (`Coin.hs:93-106`, cardano-ledger f649f975)
  test("a negative coin of the Alonzo or Conway genesis is written as the negative integer it is") {
    val alonzo =
      json("alonzo-genesis.json").deepMerge(Json.obj("lovelacePerUTxOWord" -> Json.fromInt(-1)))
    val n = decoded(answer(setupWith(Map("alonzo-genesis.json" -> alonzo))))
    assertEquals(at(n, 1, 4, 11, 0).original.hex, "20", "the Alonzo coins per word, -1")
    val conway = json("conway-genesis.json", eras).deepMerge(
      Json.obj("govActionDeposit" -> Json.fromInt(-5), "dRepDeposit" -> Json.fromInt(-300))
    )
    val m = decoded(answer(setupWith(Map("conway-genesis.json" -> conway), eras)))
    assertEquals(at(m, 1, 6, 11, 0, 5).original.hex, "24", "the governance action deposit, -5")
    assertEquals(at(m, 1, 6, 11, 0, 6).original.hex, "39012b", "the DRep deposit, -300")
  }

  test("a coin beyond 64 bits is written as the bignum it is") {
    val conway = json("conway-genesis.json", eras).deepMerge(
      Json.obj("govActionDeposit" -> Json.fromBigInt(BigInt(2).pow(70)))
    )
    val n = decoded(answer(setupWith(Map("conway-genesis.json" -> conway), eras)))
    assertEquals(at(n, 1, 6, 11, 0, 5).original.hex, "c249400000000000000000", "2^70, tag 2")
  }

  // `FromJSON (BoundedRatio b Word64)` reads a rational object (`BaseTypes.hs:437-442`), and
  // the minimum output and pool cost are `.:? .!= mempty` (`Shelley/Genesis.hs:423-424`),
  // cardano-ledger f649f975
  test(
    "a Shelley genesis with a rational object and without the minima answers as the decimal and zeros"
  ) {
    val edited = withParams(
      params
        .mapObject(_.remove("minUTxOValue").remove("minPoolCost"))
        .deepMerge(
          Json.obj(
            "rho" -> Json.obj("numerator" -> Json.fromInt(1), "denominator" -> Json.fromInt(10))
          )
        )
    )
    assertEquals(answer(setupWith(Map("shelley-genesis.json" -> edited))).hex, recorded.hex)
    assertEquals(
      GenesisConfig.encode(edited).map(_.hex),
      GenesisConfig.encode(shelley).map(_.hex),
      "GetGenesisConfig reads the genesis alike"
    )
  }

  // `encodeMap` from version 2 is indefinite above 23 pairs (`Binary/Encoding/Encoder.hs:391-408`,
  // cardano-ledger-binary 048b78d2)
  test(
    "24 genesis delegates are written as an indefinite map, in the answer and in GetGenesisConfig"
  ) {
    def hash(size: Int, i: Int): String = f"$i%02x" * size
    val delegates = Json.fromFields((1 to 24).map { i =>
      hash(28, i) -> Json.obj(
        "delegate" -> Json.fromString(hash(28, 100 + i)),
        "vrf" -> Json.fromString(hash(32, 200 + i))
      )
    })
    val edited = shelley.mapObject(_.add("genDelegs", delegates))
    val n = decoded(answer(setupWith(Map("shelley-genesis.json" -> edited))))
    val compactDelegates = at(n, 1, 1, 0, 12)
    assert(compactDelegates.isIndefinite, compactDelegates.original.hex.take(8))
    assert(at(n, 1, 1, 11, 0).isIndefinite, "the Shelley translation context's delegates")
    val genesisConfig = decoded(GenesisConfig.encode(edited).fold(e => fail(e.message), identity))
    assert(at(genesisConfig, 12).isIndefinite, "GetGenesisConfig's delegates")
  }

  test("26 extra cost models of the Alonzo genesis are written as an indefinite map") {
    val unknown = Json.fromFields((10 to 33).map(tag => tag.toString -> Json.arr(Json.fromInt(1))))
    val alonzo = json("alonzo-genesis.json").mapObject(
      _.add(
        "extraConfig",
        json("alonzo-genesis.json").hcursor
          .downField("extraConfig")
          .focus
          .getOrElse(Json.obj())
          .deepMerge(Json.obj("costModels" -> Json.obj("Unknown" -> unknown)))
      )
    )
    val n = decoded(answer(setupWith(Map("alonzo-genesis.json" -> alonzo))))
    val models = at(n, 1, 4, 11, 8, 0, 0)
    assert(models.isIndefinite, models.original.hex.take(8))
    models.value match
      case Value.Map(pairs) =>
        assertEquals(
          pairs.map(_._1.value),
          ((0 to 1) ++ (10 to 33)).map(i => Value.UInt(BigInt(i))).toVector
        )
      case other => fail(s"not a map: $other")
  }

  // `computeStabilityWindow` is a `Word64` (`Shelley/StabilityWindow.hs:14-37`, cardano-ledger
  // f649f975)
  test("the stability windows wrap as Word64s, alike in the shape and in Globals") {
    val edited = shelley.deepMerge(
      Json.obj(
        "activeSlotsCoeff" -> Json.obj(
          "numerator" -> Json.fromInt(1),
          "denominator" -> Json.fromBigInt(BigInt(10).pow(19))
        )
      )
    )
    val n = decoded(answer(setupWith(Map("shelley-genesis.json" -> edited))))
    val word64 = BigInt(1) << 64
    val stability = (BigInt(15) * BigInt(10).pow(19)).mod(word64)
    val randomness = (BigInt(20) * BigInt(10).pow(19)).mod(word64)
    assertEquals(stability, BigInt("2426047410323587072"))
    assertEquals(at(n, 1, 1, 2).value, Value.UInt(stability), "Globals' stability window")
    assertEquals(at(n, 1, 1, 3).value, Value.UInt(randomness), "Globals' randomness window")
    assertEquals(at(n, 0, 1, 2, 1).value, Value.UInt(stability), "the shape's safe zone")
  }

  // `Ord Address` compares the root, then the attributes, then the type
  // (`Common/Address.hs:141-151`, `Attributes.hs:80-86`, `AddrAttributes.hs:47-51`, cardano-ledger
  // f649f975)
  test(
    "non-AVVM addresses of one root are ordered by their attributes, as the reference orders them"
  ) {
    val root = Bytes(Vector.fill(28)(7.toByte))
    val magic = ByronCbor.concat(ByronCbor.uint(2), ByronCbor.bytes(ByronCbor.uint(1082026)))
    // no derivation path, an unknown attribute of 50 bytes
    val unparsed = ByronCbor.concat(
      ByronCbor.mapHead(2),
      magic,
      ByronCbor.uint(5),
      ByronCbor.bytes(Bytes(Vector.fill(50)(1.toByte)))
    )
    // a derivation path of one byte
    val derived = ByronCbor.concat(
      ByronCbor.mapHead(2),
      ByronCbor.uint(1),
      ByronCbor.bytes(ByronCbor.bytes(Bytes(Vector(9.toByte)))),
      magic
    )
    def address(attributes: Bytes): Bytes =
      val payload =
        ByronCbor.concat(ByronCbor.of(0x83), ByronCbor.bytes(root), attributes, ByronCbor.uint(0))
      ByronCbor.concat(
        ByronCbor.of(0x82, 0xd8, 0x18),
        ByronCbor.bytes(payload),
        ByronCbor.uint(BigInt(ByronCbor.crc32(payload)))
      )
    val first = address(unparsed)
    val second = address(derived)
    val byron = json("byron-genesis.json").mapObject(
      _.add(
        "nonAvvmBalances",
        Json.obj(
          base58(second) -> Json.fromString("1000"),
          base58(first) -> Json.fromString("2000")
        )
      )
    )
    val n = decoded(answer(setupWith(Map("byron-genesis.json" -> byron))))
    val keys = at(n, 1, 0, 0, 0, 3, 0).value match
      case Value.Map(pairs) => pairs.map(_._1.original)
      case other            => fail(s"not a map: $other")
    assertEquals(keys.map(_.hex), Vector(first.hex, second.hex))
  }

  private def base58(b: Bytes): String =
    val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    val digits = LazyList
      .iterate(BigInt(1, b.toArray))(_ / 58)
      .takeWhile(_ > 0)
      .map(n => alphabet((n % 58).toInt))
      .reverse
      .mkString
    "1" * b.toArray.takeWhile(_ == 0).length + digits

  // `encodeUTCTime` (`Binary/Encoding/Encoder.hs:549-557`, cardano-ledger-binary 048b78d2): the year
  // an `Integer` of `toOrdinalDate`, whatever its size
  test("a system start in year 1,000,000,000 is written, in the answer and in GetGenesisConfig") {
    val edited =
      shelley.mapObject(_.add("systemStart", Json.fromString("+1000000000-01-01T00:00:00Z")))
    val n = decoded(answer(setupWith(Map("shelley-genesis.json" -> edited))))
    assertEquals(at(n, 1, 1, 0, 0).original.hex, "831a3b9aca000100", "the compact genesis' start")
    val genesisConfig = decoded(GenesisConfig.encode(edited).fold(e => fail(e.message), identity))
    assertEquals(at(genesisConfig, 0).original.hex, "831a3b9aca000100")
  }

  test(
    "the system start's year, day of the year and picoseconds are java.time's where it has them"
  ) {
    val instants = Vector(
      "1970-01-01T00:00:00Z",
      "1969-12-31T23:59:59.999999999Z",
      "2000-02-29T12:00:00.5Z",
      "2000-12-31T23:59:59Z",
      "1900-03-01T00:00:00Z",
      "2100-12-31T00:00:00Z",
      "0000-01-01T00:00:00Z",
      "-0001-12-31T23:59:59Z",
      "-0400-03-01T01:02:03Z",
      "+999999999-12-31T23:59:59.999999999Z",
      "-999999999-01-01T00:00:00Z"
    ).map(java.time.Instant.parse) ++
      (0 until 200).map(i =>
        java.time.Instant
          .ofEpochSecond(
            (BigInt(i).pow(7) * 7919)
              .mod(BigInt(6) * BigInt(10).pow(16))
              .toLong - 30000000000000000L
          )
      )
    instants.foreach { at =>
      val utc = at.atZone(java.time.ZoneOffset.UTC)
      val picos = BigInt(utc.toLocalTime.toNanoOfDay) * 1000
      val expected =
        CborOut.build(_.array(3).integer(utc.getYear).integer(utc.getDayOfYear).integer(picos))
      assertEquals(CborOut.build(GenesisConfig.utcTime(_, at)).hex, expected.hex, at.toString)
    }
  }

  // `Url` is any text for `FromJSON` and `EncCBOR` (`BaseTypes.hs:674-676`, cardano-ledger f649f975)
  test("a constitution and a DRep anchor url past 128 bytes are written") {
    val url = "https://example.org/" + "a" * 200
    val conway = json("conway-genesis.json", eras).deepMerge(
      Json.obj(
        "constitution" -> Json.obj(
          "anchor" -> Json
            .obj("url" -> Json.fromString(url), "dataHash" -> Json.fromString("11" * 32))
        ),
        "initialDReps" -> Json.obj(
          ("keyHash-" + "22" * 28) -> Json.obj(
            "expiry" -> Json.fromInt(7),
            "deposit" -> Json.fromInt(9),
            "anchor" -> Json.obj(
              "url" -> Json.fromString(url),
              "dataHash" -> Json.fromString("33" * 32)
            )
          )
        )
      )
    )
    val n = decoded(answer(setupWith(Map("conway-genesis.json" -> conway), eras)))
    assertEquals(at(n, 1, 6, 11, 1, 0, 0).value, Value.Text(url), "the constitution's url")
    val drep = at(n, 1, 6, 11, 4).value match
      case Value.Map(Vector((_, state))) => state
      case other                         => fail(s"not one DRep: $other")
    assertEquals(at(drep, 1, 0, 0).value, Value.Text(url), "the DRep's url")
  }

  /** The source of the unedited Conway devnet's answer, whose files a case then replaces. */
  private def source: LedgerConfig.Source =
    val setup = setupWith(Map.empty)
    LedgerConfig.Source(
      setup.byronParams.getOrElse(fail("no Byron genesis")),
      true,
      setup.geometry,
      setup.genesis.raw,
      json("alonzo-genesis.json"),
      json("conway-genesis.json"),
      xyz.kd5ujc.scarab.ledger.GenesisState.InjectionSource.legacy(Vector.empty),
      xyz.kd5ujc.scarab.ledger.GenesisState.InjectionSource.legacy(Vector.empty),
      None,
      Map.empty
    )

  test("a value outside its type's range is the typed reason the answer has none") {
    val maxValue =
      json("alonzo-genesis.json").deepMerge(Json.obj("maxValueSize" -> Json.fromLong(1L << 32)))
    assertEquals(
      LedgerConfig.encode(source.copy(alonzo = maxValue)).left.toOption,
      Some(LedgerConfig.Error.OutOfRange(WordOutOfRange(BigInt(1L << 32), WordWidth.W32)))
    )
    val big = BigInt(2).pow(65) + 1
    val bigger = BigInt(2).pow(66) + 1
    val threshold = json("conway-genesis.json").deepMerge(
      Json.obj(
        "poolVotingThresholds" -> Json.obj(
          "motionNoConfidence" -> Json.obj(
            "numerator" -> Json.fromBigInt(big),
            "denominator" -> Json.fromBigInt(bigger)
          )
        )
      )
    )
    assertEquals(
      LedgerConfig.encode(source.copy(conway = threshold)).left.toOption,
      Some(
        LedgerConfig.Error.RatioOutOfRange(
          RatioOutOfRange(xyz.kd5ujc.scarab.codec.Rational(big, bigger))
        )
      )
    )
    val shortHash = json("conway-genesis.json").deepMerge(
      Json.obj(
        "constitution" -> Json.obj("anchor" -> Json.obj("dataHash" -> Json.fromString("00" * 31)))
      )
    )
    assertEquals(
      LedgerConfig.encode(source.copy(conway = shortHash)).left.toOption,
      Some(LedgerConfig.Error.Hash(VocabError.WrongHashLength(VocabError.Kind.Hash32, 32, 31)))
    )
    val magic = shelley.deepMerge(Json.obj("networkMagic" -> Json.fromLong(1L << 32)))
    assertEquals(
      GenesisConfig.encode(magic).left.toOption,
      Some(
        GenesisConfig.Error
          .OutOfRange("networkMagic", WordOutOfRange(BigInt(1L << 32), WordWidth.W32))
      )
    )
    assertEquals(
      LedgerConfig.encode(source.copy(shelley = magic)).left.toOption,
      Some(
        LedgerConfig.Error.Shelley(
          GenesisConfig.Error
            .OutOfRange("networkMagic", WordOutOfRange(BigInt(1L << 32), WordWidth.W32))
        )
      )
    )
    val shortDelegate = shelley.mapObject(
      _.add(
        "genDelegs",
        Json.obj(
          "01" * 28 -> Json.obj(
            "delegate" -> Json.fromString("02" * 27),
            "vrf" -> Json.fromString("03" * 32)
          )
        )
      )
    )
    assertEquals(
      GenesisConfig.encode(shortDelegate).left.toOption,
      Some(
        GenesisConfig.Error.InvalidHash(
          "delegate",
          VocabError.WrongHashLength(VocabError.Kind.KeyHash, 28, 27)
        )
      )
    )
    val longEntropy = shelley.mapObject(
      _.add(
        "protocolParams",
        shelley.hcursor
          .downField("protocolParams")
          .focus
          .fold(Json.obj())(identity)
          .mapObject(
            _.add(
              "extraEntropy",
              Json.obj("tag" -> Json.fromString("Nonce"), "contents" -> Json.fromString("04" * 33))
            )
          )
      )
    )
    assertEquals(
      GenesisConfig.encode(longEntropy).left.toOption,
      Some(
        GenesisConfig.Error.InvalidHash(
          "contents",
          VocabError.WrongHashLength(VocabError.Kind.Hash32, 32, 33)
        )
      )
    )
  }
