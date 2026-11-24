// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.{HCursor, Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.GenesisState
import xyz.kd5ujc.scarab.ledger.json.CanonicalJson
import xyz.kd5ujc.scarab.ledger.rules.{GenesisInjections, GenesisStart, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  LedgerState,
  NewEpochState,
  ProtVer,
  ShelleyBasedEra,
  StakePool,
  Word64
}
import xyz.kd5ujc.scarab.protocol.StateLayout
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs, UtxoCodecs, Wire}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given

/** The genesis injection of a chain that starts in Conway, in the Leios layout, against the native
  * `injectIntoTestState` of the Leios testnet's build (`fixtures/reference/leios-genesis`): the
  * testnet's genesis files and changes to its pool's voting key. Each case's files are read as the
  * Leios build reads them ([[GenesisInjections.read]]) and injected into the native start state
  * ([[GenesisStart.inject]]); every verdict is compared, and of every accepted case the resulting
  * state as a ledger snapshot holds it (its SHA-256; the UTxO is in the tables), its UTxO, its
  * snapshots and its pool states. No case is excluded.
  */
class LeiosGenesisReferenceSuite extends FunSuite:
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private val codecs = LeiosStateCodecs
  private val state = codecs.newEpochStateAt[Conway]
  private val pools = Wire.mapOf(codecs.keyHash[StakePool], codecs.stakePoolState)
  private val utxo = Wire.sortedMapOf(UtxoCodecs.txIn, UtxoCodecs.txOut)

  private val config = Path.of("fixtures/leios/testnet/config")

  // the node's globals for the testnet's configuration (Conway's injection reads none of them)
  private lazy val globals: Globals =
    def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)
    val parsed = right(
      NodeConfig
        .parse(xyz.kd5ujc.scarab.protocol.ReferenceBuild.Leios)(
          Files.readString(config.resolve("config.json")),
          true
        )
    )
    val setup = right(
      NodeSetup
        .assemble(xyz.kd5ujc.scarab.protocol.ReferenceBuild.Leios)(
          parsed,
          name =>
            Option
              .when(Files.exists(config.resolve(name)))(Files.readAllBytes(config.resolve(name)))
        )
    )
    Globals(setup.globals, setup.geometry)
  private def json(name: String): Json =
    parser.parse(Files.readString(config.resolve(name))).fold(e => fail(e.toString), identity)

  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)

  private val rows: Vector[HCursor] = Files
    .readString(Path.of("fixtures/reference/leios-genesis/cases.jsonl"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(line => right(parser.parse(line)).hcursor)
    .toVector

  private def text(c: HCursor, name: String): String = right(c.get[String](name))

  private val startBytes: Bytes =
    right(
      Bytes.fromHex(text(rows.find(text(_, "name") == "start").getOrElse(fail("no start")), "cbor"))
    )

  private val start: NewEpochState[Conway] =
    right(state.decodeBytes(startBytes, HaskellState.limits).left.map(_.render))

  /** The testnet's Shelley genesis at Conway's protocol version, as the oracle builds its base. */
  private val base: Json =
    json("shelley-genesis.json").hcursor
      .downField("protocolParams")
      .downField("protocolVersion")
      .set(Json.obj("major" -> Json.fromInt(10), "minor" -> Json.fromInt(0)))
      .top
      .getOrElse(fail("no protocol version"))

  /** The value `doc` with `f` applied at `path` (`None`: removed). */
  private def at(doc: Json, path: List[String], f: Option[Json] => Option[Json]): Json =
    path match
      case Nil => fail("an empty path")
      case last :: Nil =>
        val obj = doc.asObject.getOrElse(fail(s"not an object at $last"))
        Json.fromJsonObject(f(obj(last)).fold(obj.remove(last))(obj.add(last, _)))
      case step :: rest =>
        val obj = doc.asObject.getOrElse(fail(s"not an object at $step"))
        Json.fromJsonObject(obj.add(step, at(obj(step).getOrElse(fail(s"no $step")), rest, f)))

  private def changed(row: HCursor): Json =
    val path = right(row.get[List[String]]("path"))
    text(row, "op") match
      case "none"    => base
      case "remove"  => at(base, path, _ => None)
      case "replace" => at(base, path, _ => Some(right(row.get[Json]("value"))))
      // the extra source's pools moved to `staking.pools`, with the voting key replaced there
      case "legacy" =>
        val moved = right(
          base.hcursor.downField("extraConfig").downField("stakePools").downField("data").as[Json]
        )
        val legacy = at(
          at(base, List("extraConfig", "stakePools"), _ => None),
          List("staking", "pools"),
          _ => Some(moved)
        )
        at(
          legacy,
          List("staking", "pools") ++ path.drop(3),
          _ => Some(right(row.get[Json]("value")))
        )
      case other => fail(s"unknown op $other")

  test("the corpus and its start state are present, and the start state reads back to its bytes") {
    assert(rows.size > 1)
    assertEquals(state.encodeUnchecked(start), startBytes)
  }

  rows.filterNot(text(_, "name") == "start").foreach { row =>
    test(s"${text(row, "name")} agrees with the native genesis injection") {
      val files = GenesisState.Files(
        changed(row),
        json("alonzo-genesis.json"),
        json("conway-genesis.json"),
        CanonicalJson.Obj(Vector.empty)
      )
      val injected = GenesisInjections
        .read(StateLayout.Leios)(files)
        .left
        .map(_.toString)
        .flatMap(GenesisStart.inject[Conway](globals, _, start).left.map(_.toString))
      val reference = row.downField("reference")
      val accepted = right(reference.get[Boolean]("accepted"))
      assertEquals(injected.isRight, accepted, s"Scarab: $injected; native: ${reference.focus}")
      injected.foreach { nes =>
        def expected(name: String): String = right(reference.get[String](name))
        assertEquals(codecs.snapShots.encodeUnchecked(nes.es.snapshots).hex, expected("snapshots"))
        val registered = LedgerState.pState[Conway].get(nes.es.ls).stakePools
        assertEquals(pools.encodeUnchecked(registered).hex, expected("pools"))
        assertEquals(utxo.encodeUnchecked(nes.es.ls.utxo.utxo.utxo.toMap).hex, expected("utxo"))
        // the state as a ledger snapshot holds it: its UTxO is in the tables
        val digest = java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(state.encodeUnchecked(nes).toArray)
        assertEquals(Bytes.fromArray(digest).hex, expected("stateSha256"))
      }
    }
  }
