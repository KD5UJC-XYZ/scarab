// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraGenesis
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.ledger.rules.{RewardPulserFixture, Translate}
import xyz.kd5ujc.scarab.ledger.state.*

/** The translation of a state into Dijkstra (#826) against the Leios build's: the Conway state of
  * `fixtures/leios/state/conway-195`, translated by [[Translate.conwayToDijkstra]] with the
  * network's Dijkstra genesis and written at version 12, is the reference's `TranslateEra
  * DijkstraEra NewEpochState` of it byte for byte (cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Translation.hs:70-80`), and each output of its
  * UTxO table is the reference's `upgradeTxOut` of it in the table's MemPack form
  * (`fixtures/reference/dijkstra-state-translation`). The consensus parts of the crossing (the
  * header state, the transition counter, the telescope) are not compared; the fixture's README says
  * which. The state holds no proposal, no future parameters and no pool re-registration, so
  * agreement here says nothing of those paths.
  */
class DijkstraStateTranslationSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val reference: Json =
    parser
      .parse(
        Files.readString(Path.of("fixtures/reference/dijkstra-state-translation/cases.jsonl")).trim
      )
      .fold(e => fail(e.message), identity)

  private def field(path: String*): Json =
    path
      .foldLeft(reference.hcursor: io.circe.ACursor)(_.downField(_))
      .focus
      .getOrElse(fail(s"${path.mkString(".")} missing"))

  private def text(j: Json): String = j.asString.getOrElse(fail(s"not a string: $j"))

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private val conwayState: Node =
    val bytes = Files.readAllBytes(Path.of("fixtures/leios/state/conway-195/state"))
    val root =
      Cbor.decode(Bytes.wrap(bytes), HaskellState.limits).fold(e => fail(e.toString), identity)
    // `[1, [ledger, header, Peras]]`, the ledger's telescope at its seventh era, Conway, the current
    // state's `[version, [tip, state]]`
    List(1, 0, 6, 1, 1, 1).foldLeft(root)((m, i) => items(m)(i))

  private val genesis: DijkstraGenesis =
    val json = parser
      .parse(Files.readString(Path.of("fixtures/leios/state/genesis/dijkstra-genesis.json")))
      .fold(e => fail(e.message), identity)
    DijkstraGenesis.read(json).fold(e => fail(e.message), identity)

  private val globals = RewardPulserFixture.globals.copy(ledger =
    RewardPulserFixture.globals.ledger.copy(dijkstra = Some(genesis))
  )

  test("the oracle read the same Conway NewEpochState") {
    val span = field("newEpochStateSpan").asArray.getOrElse(fail("no span")).flatMap(_.asNumber)
    val bytes = conwayState.original
    assertEquals(span.flatMap(_.toInt), Vector(119, 119 + bytes.size))
    assertEquals(
      Bytes.wrap(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).hex,
      text(field("newEpochStateSha256"))
    )
  }

  test("conway-195's NewEpochState translated into Dijkstra is the reference's byte for byte") {
    val conway = LeiosStateCodecs.newEpochState[Conway]
    val dijkstra = LeiosStateCodecs.newEpochState[Dijkstra]
    val state = conway.decode(conwayState).fold(e => fail(e.render), identity)
    val translated =
      Translate.conwayToDijkstra(globals, state).fold(e => fail(e.toString), identity)
    val written = dijkstra.write(translated).fold(e => fail(e.toString), identity)
    assertEquals(written.hex, text(field("reference", "dijkstraNewEpochState")))
  }

  test("each output of the table upgraded into Dijkstra is the reference's MemPack byte for byte") {
    val outputs = field("reference", "outputs").asArray.getOrElse(fail("no outputs"))
    assertEquals(outputs.size, 7)
    outputs.foreach { o =>
      def at(name: String): Bytes =
        hex(text(o.hcursor.downField(name).focus.getOrElse(fail(s"$name missing"))))
      val conway =
        TxOutMemPack.decode[Conway](at("conwayTxOut")).fold(e => fail(e.toString), identity)
      val upgraded = Translate.upgradeTxOut(conway)
      assertEquals(
        TxOutMemPack.encode[Dijkstra](upgraded).fold(e => fail(e.toString), identity),
        at("dijkstraTxOutMemPack")
      )
    }
  }
