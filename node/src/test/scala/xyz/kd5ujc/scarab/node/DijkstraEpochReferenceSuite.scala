// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import io.circe.{ACursor, HCursor, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Sts, Tick}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, ShelleyBasedEra, SlotNo}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs}

/** Dijkstra's tick across epoch boundaries, against the native TICK of the Leios build
  * (`fixtures/reference/dijkstra-epoch`, oracle `scripts/oracle/dijkstra-epoch`): the two Dijkstra
  * states the build wrote (`fixtures/leios/state`: the devnet's first and the public testnet's at
  * epoch 100), each ticked to the first slot of the next two epochs, and on a second path also once
  * inside each epoch past the start of its reward update, so that the boundary applies it. After
  * every tick the state as the build writes it equals the native one (its SHA-256, and its bytes
  * where the fixture holds them): Dijkstra's EPOCH, its POOLREAP and its SNAP, with the Leios
  * committee the mark seats and the key age the KES constants give, and the reward update. The
  * globals are the node's for each network's configuration, checked against those the native tick
  * ran with. No case is excluded.
  */
class DijkstraEpochReferenceSuite extends FunSuite:
  private given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra
  private val state = LeiosStateCodecs.newEpochState[Dijkstra]

  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)

  private def globalsOf(dir: Path): Globals =
    val config = right(
      NodeConfig
        .parse(ReferenceBuild.LeiosB004fe0c)(Files.readString(dir.resolve("config.json")), true)
        .left
        .map(_.message)
    )
    val setup = right(
      NodeSetup.assemble(ReferenceBuild.LeiosB004fe0c)(
        config,
        name => Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name)))
      )
    )
    Globals(setup.globals, setup.geometry)

  private val fixture = Path.of("fixtures/reference/dijkstra-epoch")
  private val cases: Vector[HCursor] =
    right(parser.parse(Files.readString(fixture.resolve("cases.json")))).hcursor
      .downField("cases")
      .values
      .getOrElse(fail("no cases"))
      .map(_.hcursor)
      .toVector

  private def text(c: ACursor, name: String): String = right(c.get[String](name))
  private def number(c: ACursor, name: String): BigInt = right(c.get[BigInt](name))

  private def sha256(bytes: Bytes): String =
    Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).hex

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  /** The Dijkstra `NewEpochState` of a `state` file the build wrote: the extended state's ledger
    * telescope at its eighth era, `[start, [2, [tip, NewEpochState, transition, bytes]]]`.
    */
  private def input(name: String, sha: String): NewEpochState[Dijkstra] =
    val raw = Bytes.wrap(Files.readAllBytes(Path.of(s"fixtures/leios/state/$name/state")))
    assertEquals(sha256(raw), sha, s"$name: the input is the oracle's")
    val root = right(Cbor.decode(raw, HaskellState.limits))
    val nes = Vector(1, 0, 7, 1, 1, 1).foldLeft(root)((n, i) => items(n)(i))
    right(state.decode(nes).left.map(_.render))

  /** The configuration directory of the network whose Shelley genesis the oracle read. */
  private def configOf(genesis: String): Path = Path.of(genesis).getParent

  test("the corpus holds both states on both paths, and the node's globals are the native tick's") {
    assertEquals(
      cases.map(c => (text(c, "name"), text(c, "path"))).toSet,
      Set(
        ("dijkstra-203", "boundaries"),
        ("dijkstra-203", "rewards"),
        ("testnet-2168639", "boundaries"),
        ("testnet-2168639", "rewards")
      )
    )
    cases.foreach { c =>
      val g = globalsOf(configOf(text(c, "genesis")))
      val native = c.downField("globals")
      val at = text(c, "name")
      assertEquals(BigInt(g.geometry.epochLength), number(native, "epochSize"), at)
      assertEquals(g.securityParameter.value.toBigInt, number(native, "securityParameter"), at)
      assertEquals(g.stabilityWindow, number(native, "stabilityWindow"), at)
      assertEquals(
        g.randomnessStabilisationWindow,
        number(native, "randomnessStabilisationWindow"),
        at
      )
      assertEquals(g.ledger.kes.maxEvolutions.toBigInt, number(native, "maxKESEvo"), at)
      assertEquals(g.ledger.kes.slotsPerPeriod.toBigInt, number(native, "slotsPerKESPeriod"), at)
      assertEquals(BigInt(right(g.maxKeyAgeEpochs).toLong), number(native, "maxKeyAge"), at)
    }
  }

  cases.foreach { c =>
    val name = text(c, "name")
    val path = text(c, "path")
    test(s"$name, $path: Dijkstra's tick agrees with the native tick after every slot") {
      val globals = globalsOf(configOf(text(c, "genesis")))
      val start = input(name, text(c, "stateSha256"))
      assertEquals(sha256(state.encodeUnchecked(start)), text(c, "newEpochStateSha256"))
      val ticks = right(c.downField("ticks").values.toRight("no ticks")).toVector
      ticks.foldLeft(start) { (before, tickJson) =>
        val tick = tickJson.hcursor
        val slot = right(SlotNo.fromBigInt(number(tick, "slot")))
        val at = s"at slot ${number(tick, "slot")}"
        val after = right(Sts(Tick.sts[Dijkstra])(globals, (), before, slot).leftMap(_.show))
        val encoded = right(state.write(after).left.map(_.toString))
        tick.get[String]("file").toOption.foreach { file =>
          val expected = Bytes.wrap(Files.readAllBytes(fixture.resolve(file)))
          assertEquals(encoded.hex, expected.hex, at)
        }
        assertEquals(sha256(encoded), text(tick, "stateSha256"), at)
        assertEquals(after.epoch.toBigInt, number(tick.downField("facts"), "epoch"), at)
        after
      }
    }
  }
