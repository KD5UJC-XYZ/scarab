// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{Json, parser}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}
import xyz.kd5ujc.scarab.ledger.{
  ConfigProblem,
  Genesis,
  GenesisFieldError,
  GenesisFile,
  GenesisSchema,
  PosixClock,
  SystemStart
}
import xyz.kd5ujc.scarab.ledger.json.JsonKind
import java.nio.file.{Files, Path}
import java.time.Instant
import munit.FunSuite

/** The slot clock starts where the reference's does: at the Byron genesis' `startTime` (H6). The
  * ledger's system start, from which the Plutus script contexts' times count, is the Shelley
  * genesis's `systemStart`, as the reference's `Globals` take it (`mkShelleyGlobals`,
  * cardano-ledger 9df9f98c `Shelley/Genesis.hs:768-789`), whatever the Byron `startTime` is
  * (#1337).
  */
class SystemStartSuite extends FunSuite:
  private val dir = Path.of("fixtures/eras/genesis")

  private def setupWith(shelleyStart: Instant): NodeSetup =
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(dir.resolve(name))) {
        val bytes = Files.readAllBytes(dir.resolve(name))
        if name == "shelley-genesis.json" then
          new String(bytes, "UTF-8")
            .replaceAll("\"systemStart\"\\s*:\\s*\"[^\"]*\"", s"\"systemStart\": \"$shelleyStart\"")
            .getBytes("UTF-8")
        else bytes
      }
    NodeSetup.fromFiles(ReferenceBuild.Default)(read).fold(e => fail(e.message), identity)

  private val byronStart: Instant =
    val unchanged =
      NodeSetup.fromDirectory(ReferenceBuild.Default)(dir).fold(e => fail(e.message), identity)
    Instant.ofEpochSecond(
      unchanged.byronParams.map(_.startTime).getOrElse(fail("no startTime"))
    )

  test(
    "a Shelley systemStart a second (or a fraction) off the Byron startTime does not move the clock"
  ) {
    List(
      byronStart.plusSeconds(1),
      byronStart.minusSeconds(1),
      byronStart.plusMillis(500),
      byronStart
    ).foreach { shelley =>
      val setup = setupWith(shelley)
      assertEquals(setup.geometry.systemStart, byronStart, s"Shelley systemStart $shelley")
      assertEquals(setup.praos.geometry.systemStart, byronStart)
    }
  }

  test("without a Byron startTime the Shelley systemStart stands") {
    val shelley = Genesis
      .shelley(new String(Files.readAllBytes(dir.resolve("shelley-genesis.json")), "UTF-8"))
      .fold(e => fail(e.message), identity)
    assertEquals(Genesis.withSystemStart(shelley, None), shelley)
  }

  // the two starts differ by a fraction of a second, a second, or not at all
  private val starts = List(
    byronStart.plusNanos(240586427),
    byronStart.plusSeconds(1),
    byronStart.minusSeconds(1).plusNanos(738356360),
    byronStart
  )

  test("the ledger's system start is the Shelley genesis's, the slot clock's the Byron one's") {
    starts.foreach { shelley =>
      val setup = setupWith(shelley)
      assertEquals(setup.genesis.systemStart, SystemStart(shelley))
      assertEquals(setup.globals.systemStart, SystemStart(shelley))
      assertEquals(setup.geometry.systemStart, byronStart)
      // a Byron slot and a Shelley slot: the slot clock counts from the Byron start
      List(BigInt(3), BigInt(60)).foreach { slot =>
        assertEquals(
          setup.geometry.slotStart(slot),
          byronStart.plusMillis(setup.geometry.slotOffsetMillis(slot).toLong)
        )
      }
    }
  }

  test(
    "Plutus times count from the Shelley start, cut to the millisecond after the offset is added"
  ) {
    // `slotToPOSIXTime`: `truncate . (* 1000)` of the start plus the slot's offset; every start
    // here is after 1970 and every offset a whole number of milliseconds, so the time is the
    // start's whole milliseconds plus the offset
    starts.foreach { shelley =>
      val setup = setupWith(shelley)
      val startMillis = BigInt(shelley.toEpochMilli)
      // the mempool's and the forger's clock, at the chain's start (the Byron era, inside its
      // safe zone)
      assertEquals(
        setup.txClock.at(setup.mirrorStart, Era.Byron, 3).posix(3),
        Right(startMillis + setup.geometry.slotOffsetMillis(3)),
        s"Shelley systemStart $shelley"
      )
      // the clock of a state with no era history
      assertEquals(
        PosixClock.total(setup.globals.systemStart, setup.geometry).posix(60),
        Right(startMillis + setup.geometry.slotOffsetMillis(60)),
        s"Shelley systemStart $shelley"
      )
    }
    // the fixture of `fixtures/devnet/lsq` writes its Shelley start 240.586427 ms after its Byron
    // start: its Plutus times are 240 ms later than the slot clock's
    val lsqLike = setupWith(byronStart.plusNanos(240586427))
    assertEquals(
      lsqLike.txClock.at(lsqLike.mirrorStart, Era.Byron, 3).posix(3),
      Right(BigInt(byronStart.toEpochMilli) + 240 + 3000)
    )
  }

  // `Genesis.shelley` reads `systemStart` first, as the reference's `parseJSON` does: a refusal of
  // the field is the check's, and comes before one of a later field
  private val shelleyJson: Json =
    parser
      .parse(new String(Files.readAllBytes(dir.resolve("shelley-genesis.json")), "UTF-8"))
      .fold(e => fail(e.message), identity)

  private def refusal(edited: Json): ConfigProblem =
    def read(name: String): Option[Array[Byte]] =
      Option.when(Files.exists(dir.resolve(name))) {
        if name == "shelley-genesis.json" then edited.noSpaces.getBytes("UTF-8")
        else Files.readAllBytes(dir.resolve(name))
      }
    NodeSetup.fromFiles(ReferenceBuild.Default)(read).fold(_.problem, _ => fail("accepted"))

  test("a genesis without systemStart, or with one that is not a string, is refused at the field") {
    def at(cause: GenesisFieldError) =
      ConfigProblem.GenesisField(GenesisFile.Shelley, Vector("systemStart"), cause)
    val badMagic = Json.obj("networkMagic" -> Json.fromString("one"))
    List(
      shelleyJson.mapObject(_.remove("systemStart")) -> at(GenesisFieldError.Missing),
      shelleyJson.deepMerge(Json.obj("systemStart" -> Json.fromInt(1))) ->
        at(GenesisFieldError.Kind(JsonKind.String, JsonKind.Number)),
      shelleyJson.mapObject(_.remove("systemStart")).deepMerge(badMagic) ->
        at(GenesisFieldError.Missing)
    ).foreach { (edited, expected) =>
      assertEquals(refusal(edited), expected)
      assertEquals(GenesisSchema.shelley(edited).left.map(_.problem), Left(expected))
    }
  }

  test("a genesis that is not an object is refused at its root, before its systemStart is read") {
    def root(found: JsonKind) = ConfigProblem.GenesisField(
      GenesisFile.Shelley,
      Vector.empty,
      GenesisFieldError.Kind(JsonKind.Object, found)
    )
    List(
      Json.arr() -> root(JsonKind.Array),
      Json.fromString(byronStart.toString) -> root(JsonKind.String)
    ).foreach { (edited, expected) =>
      assertEquals(refusal(edited), expected)
      assertEquals(GenesisSchema.shelley(edited).left.map(_.problem), Left(expected))
    }
  }

  // on the public networks and the Leios testnet the two starts agree, so the ledger's start is
  // the slot clock's, as it was before #1337
  List(
    "fixtures/mainnet/genesis",
    "fixtures/preprod/genesis",
    "fixtures/preview/genesis",
    "fixtures/leios/testnet/config"
  ).foreach { network =>
    test(s"$network: the Shelley systemStart is the Byron startTime") {
      val base = Path.of(network)
      val shelley = Genesis
        .shelley(new String(Files.readAllBytes(base.resolve("shelley-genesis.json")), "UTF-8"))
        .fold(e => fail(e.message), identity)
      val byron = parser
        .parse(new String(Files.readAllBytes(base.resolve("byron-genesis.json")), "UTF-8"))
        .flatMap(_.hcursor.get[Long]("startTime"))
        .fold(e => fail(e.getMessage), identity)
      assertEquals(shelley.systemStart, SystemStart(Instant.ofEpochSecond(byron)))
    }
  }
