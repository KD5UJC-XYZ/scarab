// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path as FsPath
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{
  EraLedgerState,
  MirrorState,
  MirrorStep,
  ShelleyLedgerState
}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  Header,
  NodeError,
  StorageError,
  TxIn,
  TxOut,
  ValidationError
}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraFixture
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, Globals, RewardPulserFixture, Translate}
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.storage.{MemoryUtxoStore, UtxoPinnedReadView, UtxoStore}
import xyz.kd5ujc.scarab.node.read.ChainRead.given

/** When the block path upgrades the stored outputs on entering Dijkstra (#815): only for a block
  * the rules accepted that took the chain into Dijkstra from any earlier era
  * ([[MirrorRules.DijkstraCrossing]]). The node refuses Dijkstra blocks for now (OD-4), so the
  * crossing is checked on the states, and the pass over the store is counted on the era-walking
  * capture (`fixtures/eras`, Byron to Conway) and on a block whose header fails its checks.
  */
class DijkstraCrossingSuite extends CatsEffectSuite:
  private val fixtures = Path.of("fixtures/eras")

  private lazy val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  private lazy val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))

  private lazy val headers: Vector[Header] =
    raws.map(
      Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), _.header)
    )

  private val conway = EraNes.InEra(ShelleyBasedEra.SConway, RewardPulserFixture.conway(5, 4, 1))

  private val dijkstra: EraNes =
    Translate
      .next(
        RewardPulserFixture.globals.copy(ledger =
          RewardPulserFixture.globals.ledger.copy(dijkstra = Some(DijkstraFixture.genesis))
        ),
        conway
      )
      .fold(e => fail(e.toString), _.getOrElse(fail("no next era")))

  test("entering Dijkstra from any earlier era is the crossing; staying in an era is not") {
    applied.map { (states, _, _) =>
      val babbage = states
        .flatMap(MirrorStep.nesOf)
        .findLast { case i: EraNes.InEra[x] => i.era == ShelleyBasedEra.SBabbage }
        .getOrElse(fail("no Babbage state"))
      assert(MirrorRules.DijkstraCrossing.crosses(Some(conway), dijkstra))
      assert(MirrorRules.DijkstraCrossing.crosses(Some(babbage), dijkstra))
      assert(MirrorRules.DijkstraCrossing.crosses(None, dijkstra))
      assert(!MirrorRules.DijkstraCrossing.crosses(Some(conway), conway))
      assert(!MirrorRules.DijkstraCrossing.crosses(Some(babbage), conway))
      assert(!MirrorRules.DijkstraCrossing.crosses(Some(dijkstra), dijkstra))
      assert(!MirrorRules.DijkstraCrossing.crosses(Some(dijkstra), conway))
    }
  }

  /** A store that counts its passes over the set. */
  private final class Counting(underlying: UtxoStore[IO], passes: Ref[IO, Int])
      extends UtxoStore[IO]:
    def read(keys: Iterable[TxIn])(using Raise[IO, StorageError]) = underlying.read(keys)
    def stamp(using Raise[IO, StorageError]) = underlying.stamp
    def all(using Raise[IO, StorageError]) = underlying.all
    def select(keep: TxOut => Boolean)(using Raise[IO, StorageError]) =
      passes.update(_ + 1) *> underlying.select(keep)
    def selectAddresses(addresses: Set[Bytes])(using Raise[IO, StorageError]) =
      underlying.selectAddresses(addresses)
    def write(changes: Iterable[(TxIn, Option[TxOut])], stamp: Long)(using
        Raise[IO, StorageError]
    ) = underlying.write(changes, stamp)
    def flushed(using Raise[IO, StorageError]) = underlying.flushed
    def readView: Resource[IO, Either[StorageError, UtxoPinnedReadView[IO]]] = underlying.readView
    def scan[A](zero: A)(step: (A, TxIn, TxOut) => A)(using Raise[IO, StorageError]) =
      underlying.scan(zero)(step)
    def entries(using Raise[IO, StorageError]): Stream[IO, (TxIn, TxOut)] = underlying.entries
    def size(using Raise[IO, StorageError]) = underlying.size
    def checkpoint(dir: FsPath)(using Raise[IO, StorageError]) = underlying.checkpoint(dir)
    def restore(dir: FsPath)(using Raise[IO, StorageError]) = underlying.restore(dir)
    def reset(using Raise[IO, StorageError]) = underlying.reset
    def memory(using Raise[IO, StorageError]) = underlying.memory

  /** The capture's last block with a byte of its header's KES signature changed: it decodes, and
    * its header fails the KES check.
    */
  private lazy val forged: (Header, Bytes) =
    val hex = raws.last.hex
    val at = hex.indexOf("5901c0") + 6 + 200
    val raw = Bytes
      .fromHex(hex.patch(at, if hex.slice(at, at + 2) == "00" then "01" else "00", 2))
      .fold(e => fail(e.message), identity)
    Block.decode(raw, setup.geometry.byron.epochSlots).fold(e => fail(e.message), _.header) -> raw

  /** Each block of the capture applied with a store behind the set, then [[forged]] in place of the
    * last: the states, the refusal and the passes over the store.
    */
  private def applied: IO[(Vector[MirrorState], Option[NodeError], Int)] =
    for
      passes <- Ref.of[IO, Int](0)
      memory <- MemoryUtxoStore[IO]
      rules = new MirrorRules[IO](
        setup.praos,
        setup.globals,
        setup.byronRules,
        setup.handOver,
        setup.byronHistory,
        utxoStore = Some(Counting(memory, passes))
      )
      states <- Handle
        .allow[NodeError] {
          headers.indices.toVector.foldLeftM(Vector(setup.mirrorStart)) { (acc, i) =>
            rules(acc.last, headers(i), raws(i)).map(acc :+ _)
          }
        }
        .rescue(e => IO(fail(e.message)))
      refused <- Handle
        .allow[NodeError](
          rules(states(states.size - 2), forged._1, forged._2).as(Option.empty[NodeError])
        )
        .rescue(e => IO.pure(Some(e)))
      count <- passes.get
    yield (states.tail, refused, count)

  test("no block of the capture is a crossing into Dijkstra, and none passes over the store") {
    applied.map { (states, _, count) =>
      assertEquals(states.size, headers.size)
      val crossings = states.indices
        .drop(1)
        .filter(i => MirrorRules.DijkstraCrossing.of(states(i - 1), states(i)).isDefined)
      assertEquals(crossings, IndexedSeq.empty)
      assertEquals(states.last.era, xyz.kd5ujc.scarab.protocol.Era.Conway)
      assertEquals(count, 0)
    }
  }

  test("a block whose header fails its checks is refused without a pass over the store") {
    applied.map { (_, refused, count) =>
      refused match
        case Some(ValidationError(_, xyz.kd5ujc.scarab.ledger.BlockFailure.Praos(_))) => ()
        case other => fail(s"expected a refusal by the rules, got $other")
      assertEquals(count, 0)
    }
  }

  private lazy val withGenesis: Globals =
    Globals(setup.globals.copy(dijkstra = Some(DijkstraFixture.genesis)), setup.praos.geometry)

  /** `m` with its ledger state translated into Dijkstra through every era after its own, as one
    * tick that crosses several boundaries translates it: the state a crossing block would leave,
    * which no block can make yet (OD-4).
    */
  private def inDijkstra(m: MirrorState): MirrorState =
    m.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        val start: EraNes = EraNes.InEra(i.era, i.state.nes)
        // at most one step for each era after Shelley
        val translated = (1 to 6).foldLeft(start) { (e, _) =>
          if MirrorRules.DijkstraCrossing.crosses(None, e) then e
          else Translate.next(withGenesis, e).fold(f => fail(f.toString), _.getOrElse(e))
        }
        translated match
          case d: EraNes.InEra[x] if MirrorRules.DijkstraCrossing.crosses(Some(start), d) =>
            val state = EraLedgerState.InShelleyEra(
              d.era,
              ShelleyLedgerState(i.state.tip, d.nes, i.state.transition, i.state.layout)
            )
            m.copy(ext = m.ext.copy(ledgerState = m.ext.ledgerState.withState(state)))
          case other => fail(s"no Dijkstra state: $other")
      case other => fail(s"not a Shelley-based state: $other")

  private def eraOf(m: MirrorState): Option[ShelleyBasedEra[?]] =
    MirrorStep.nesOf(m).map { case i: EraNes.InEra[x] => i.era }

  private val upgradedCase = "refScript/anyOf/indefinite-empty"

  private lazy val row: io.circe.Json =
    Files
      .readAllLines(Path.of("fixtures/reference/dijkstra-translation/cases.jsonl"))
      .asScala
      .find(_.contains(s"\"$upgradedCase\""))
      .flatMap(io.circe.parser.parse(_).toOption)
      .getOrElse(fail("no case"))

  private def field(path: String*): Bytes =
    path
      .foldLeft(row.hcursor: io.circe.ACursor)(_.downField(_))
      .as[String]
      .toOption
      .flatMap(Bytes.fromHex(_).toOption)
      .getOrElse(fail(path.mkString(".")))

  for (label, era) <- Vector(
      "Conway" -> ShelleyBasedEra.SConway,
      "Babbage, through Conway," -> ShelleyBasedEra.SBabbage
    )
  do
    test(
      s"a block from $label into Dijkstra upgrades the stored outputs its state does not decide"
    ) {
      val out = {
        given xyz.kd5ujc.scarab.ledger.ByteStrings =
          xyz.kd5ujc.scarab.ledger.ByteStrings.of(
            xyz.kd5ujc.scarab.ledger.DecoderVersion.of(xyz.kd5ujc.scarab.protocol.Era.Conway)
          )
        TxOut
          .fromBytes(
            field("txOut"),
            xyz.kd5ujc.scarab.ledger.Address.Mode.of(xyz.kd5ujc.scarab.protocol.Era.Conway)
          )
          .fold(e => fail(e.toString), identity)
      }
      val in = TxIn(RewardPulserFixture.bytes(7, 32), 0)
      for
        states <- applied.map(_._1)
        before <- IO(states.filter(eraOf(_).contains(era)).lastOption.getOrElse(fail("no state")))
        after = inDijkstra(before)
        crossing <- IO(
          MirrorRules.DijkstraCrossing.of(before, after).getOrElse(fail("not a crossing"))
        )
        store <- MemoryUtxoStore[IO]
        upgraded <- Handle
          .allow[StorageError](
            store.write(Vector(in -> Some(out)), 0) *> MirrorRules.entered(store, crossing)
          )
          .rescue(e => IO(fail(e.message)))
      yield
        val (was, now) =
          (MirrorStep.utxoOf(after).getOrElse(fail("no set")), MirrorStep.utxoOf(upgraded))
        val stored = now.flatMap(_.get(in))
        assertEquals(stored, Some(Translate.upgradeTxOut(out)))
        assertEquals(stored.map(_.raw), Some(field("reference", "dijkstraTxOut")))
        assertEquals(
          stored.flatMap(_.scriptRef).map(_.hash),
          Some(field("reference", "dijkstraScript", "hash"))
        )
        assertNotEquals(stored, Some(out))
        assertEquals(now.map(_.diff.keySet -- was.diff.keySet), Some(Set(in)))
        assertEquals(
          now.map(u => (u.coin, u.count, u.stake)),
          Some((was.coin, was.count, was.stake))
        )
        assertEquals(eraOf(upgraded), Some(ShelleyBasedEra.SDijkstra))
    }
