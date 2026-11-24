// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import io.circe.{ACursor, HCursor, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Sts, Tick}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  LedgerState,
  NewEpochState,
  ProtVer,
  ShelleyBasedEra,
  SlotNo,
  StakePool,
  Word64
}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs, Wire}

/** Conway's tick across epoch boundaries in the Leios layout, against the native TICK of the Leios
  * testnet's build (`fixtures/reference/leios-epoch`): the state Conway's genesis injection gives
  * for the testnet's genesis files, its pool with a devnet voting key or none, re-registered
  * without a key (as a certificate registers a pool up to Conway), retiring, or both; then ticked
  * to each epoch boundary and past the start of each reward update. The input of each case is the
  * native state, decoded; after every tick the state as a ledger snapshot holds it (its SHA-256),
  * its snapshots and its pool states are compared. The globals are the node's for the testnet's
  * configuration, checked against those the native tick ran with. No case is excluded.
  */
class LeiosEpochReferenceSuite extends FunSuite:
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
  private val codecs = LeiosStateCodecs
  private val state = codecs.newEpochStateAt[Conway]
  private val pools = Wire.mapOf(codecs.keyHash[StakePool], codecs.stakePoolState)

  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)

  private val dir = Path.of("fixtures", "leios", "testnet", "config")
  private val setup: NodeSetup =
    val config = right(
      NodeConfig
        .parse(ReferenceBuild.Leios)(Files.readString(dir.resolve("config.json")), true)
        .left
        .map(_.message)
    )
    right(
      NodeSetup.assemble(ReferenceBuild.Leios)(
        config,
        name => Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name)))
      )
    )
  private val globals = Globals(setup.globals, setup.geometry)

  private val rows: Vector[HCursor] = Files
    .readString(Path.of("fixtures/reference/leios-epoch/cases.jsonl"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(line => right(parser.parse(line)).hcursor)
    .toVector

  private def text(c: ACursor, name: String): String = right(c.get[String](name))
  private def number(c: ACursor, name: String): BigInt = right(c.get[BigInt](name))

  private def sha256(bytes: Bytes): String =
    Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).hex

  test("the corpus is present, and the node's globals are those the native tick ran with") {
    assert(rows.nonEmpty)
    rows.foreach { row =>
      val native = row.downField("reference").downField("globals")
      assertEquals(BigInt(setup.geometry.epochLength), number(native, "epochSize"))
      assertEquals(globals.securityParameter.value.toBigInt, number(native, "securityParameter"))
      assertEquals(globals.stabilityWindow, number(native, "stabilityWindow"))
      assertEquals(
        globals.randomnessStabilisationWindow,
        number(native, "randomnessStabilisationWindow")
      )
    }
  }

  rows.foreach { row =>
    test(s"${text(row, "name")} agrees with the native tick after every slot") {
      val reference = row.downField("reference")
      val inputBytes = right(Bytes.fromHex(text(reference, "input")))
      val input: NewEpochState[Conway] =
        right(state.decodeBytes(inputBytes, HaskellState.limits).left.map(_.render))
      assertEquals(state.encodeUnchecked(input), inputBytes)
      val ticks = right(reference.downField("ticks").values.toRight("no ticks")).toVector
      ticks.foldLeft(input) { (before, tickJson) =>
        val tick = tickJson.hcursor
        val slot = right(SlotNo.fromBigInt(number(tick, "slot")))
        val after = right(Sts(Tick.sts[Conway])(globals, (), before, slot).leftMap(_.show))
        val at = s"at slot ${number(tick, "slot")}"
        assertEquals(
          codecs.snapShots.encodeUnchecked(after.es.snapshots).hex,
          text(tick, "snapshots"),
          at
        )
        assertEquals(
          pools.encodeUnchecked(LedgerState.pState[Conway].get(after.es.ls).stakePools).hex,
          text(tick, "pools"),
          at
        )
        // the state as a ledger snapshot holds it (the layout of each part is checked first)
        val encoded = right(state.write(after).left.map(_.toString))
        assertEquals(sha256(encoded), text(tick, "stateSha256"), at)
        after
      }
    }
  }
