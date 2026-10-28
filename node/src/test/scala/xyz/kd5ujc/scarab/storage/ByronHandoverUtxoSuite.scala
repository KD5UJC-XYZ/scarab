// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Handle
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import io.circe.parser
import java.nio.file.{Files as JFiles, Path as JPath}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, ByronGenesisFile, Header, NodeError, TxIn, TxOut}
import xyz.kd5ujc.scarab.ledger.rules.Handover
import xyz.kd5ujc.scarab.ledger.state.EpochNo
import xyz.kd5ujc.scarab.ledger.byron.{ByronBody, ByronLedger, ByronOut}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.node.{MirrorRules, MirrorSteps, NodeSetup}
import scala.jdk.CollectionConverters.*

/** Mainnet sync from genesis, stop 4: at slot 4,536,140 the node refused a transaction (ce1c09a1)
  * for `BadInputsUTxO: c1092b9c...#0`. That output (12,932,000,000 lovelace) was created in Byron
  * epoch 190, slot 4,105,943, by transaction c1092b9c, and never spent before. The Byron ledger had
  * it; the node lost it at the handover to Shelley.
  *
  * The UTxO store (UTxO-HD style, [[UtxoStore]]) had been written once while the chain was in
  * Byron, with the genesis placeholder of the Shelley state (stamp 0). The handover then built the
  * Shelley UTxO as a whole, every entry at stamp 0, which the store already counted as written: the
  * entries were never stored and the first trim of the diff dropped them. The reference emits the
  * translated UTxO as diffs (`translateLedgerStateByronToShelleyWrapper`, `valuesAsDiffs`,
  * Ouroboros/Consensus/Cardano/CanHardFork.hs:343-358).
  *
  * The capture of `fixtures/eras` cannot show it (its Byron epoch has no transactions, so the
  * handover set equals the placeholder); this suite adds the real transaction's outputs to its
  * Byron UTxO (`fixtures/mainnet/byron-tx-c1092b9c-block.hex`).
  */
class ByronHandoverUtxoSuite extends CatsEffectSuite:
  private val Key = "c1092b9c0375d8c19c50313a48d6b4ee15d92421eaa75f53c66736d0d41d3ef2"

  private def hex(file: String): Bytes =
    Bytes.fromHex(JFiles.readString(JPath.of(file)).trim).fold(e => fail(e.message), identity)

  // ---- the real transaction, applied by the Byron ledger to the output it spends

  private val mainnetGenesis =
    ByronGenesisFile
      .fromJson(
        parser
          .parse(JFiles.readString(JPath.of("fixtures/mainnet/byron-genesis-trimmed.json")))
          .fold(e => fail(e.getMessage), identity),
        None
      )
      .fold(e => fail(e.message), identity)

  private val mainnetBlock: Block.Byron =
    Block.decode(hex("fixtures/mainnet/byron-tx-c1092b9c-block.hex"), BigInt(21600)) match
      case Right(b: Block.Byron) => b
      case other                 => fail(s"unexpected $other")

  /** The outputs of c1092b9c, as the Byron ledger leaves them. */
  private val created: Map[TxIn, ByronOut] =
    val body = ByronBody.decode(mainnetBlock.body).fold(e => fail(e.message), identity)
    val inputs = parser
      .parse(JFiles.readString(JPath.of("fixtures/mainnet/byron-tx-c1092b9c-inputs.json")))
      .fold(e => fail(e.getMessage), identity)
      .asArray
      .getOrElse(fail("inputs"))
      .map { j =>
        val c = j.hcursor
        TxIn(
          Bytes.fromHex(c.get[String]("txid").toOption.get).toOption.get,
          c.get[Int]("index").toOption.get
        ) -> ByronOut(
          Bytes.fromHex(c.get[String]("address").toOption.get).toOption.get,
          BigInt(c.get[String]("lovelace").toOption.get)
        )
      }
      .toMap
    val config = ByronLedger.Config(mainnetGenesis, requiresNetworkMagic = false)
    val after = ByronLedger
      .applyTx(config, mainnetGenesis.params, inputs, body.txs.head)
      .fold(e => fail(e.toString), identity)
    assertEquals(after.keySet.map(_.id.hex), Set(Key))
    after

  test("the fixture is the block at slot 4,105,943 and its transaction is c1092b9c") {
    assertEquals(mainnetBlock.header.slot, BigInt(4105943))
    assert(mainnetBlock.header.hash.hex.startsWith("ff78a9ec7703935d"))
    val out0 = created(TxIn(Bytes.fromHex(Key).toOption.get, 0))
    assertEquals(out0.value, BigInt(12932000000L)) // Koios tx_info
    assertEquals(created.size, 2)
  }

  // ---- the handover over a store

  private val setup =
    NodeSetup.fromDirectory(JPath.of("fixtures/eras/genesis")).fold(e => fail(e.message), identity)

  /** The genesis placeholder an earlier build wrote: the UTxO of the Shelley state the genesis
    * Byron UTxO hands over to (`Handover.toShelley`; the Shelley genesis of the capture has no
    * initial funds), every entry at stamp 0.
    */
  private val placeholder: Vector[(TxIn, Option[TxOut])] =
    val genesisUtxo =
      setup.mirrorStart.consensus.byron.ledger.fold(Map.empty[TxIn, ByronOut])(_.utxo)
    val context = setup.handOver.getOrElse(fail("the capture starts in Byron: it has a hand-over"))
    Handover
      .toShelley(context, EpochNo.fromUnsigned(0L), genesisUtxo)
      .fold(
        e => fail(s"the hand-over refuses: $e"),
        _.es.ls.utxo.utxo.utxo.changesAfter(-1).toVector
      )

  private val raws: Vector[Bytes] =
    JFiles
      .readAllLines(JPath.of("fixtures/eras/blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))
  private val headers: Vector[Header] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), _.header))
  private val firstShelley = headers.indexWhere(_.isInstanceOf[Header.Shelley])

  private val start: MirrorState =
    MirrorSteps.withConsensus(setup.mirrorStart)(c =>
      c.copy(byron =
        c.byron.copy(ledger = c.byron.ledger.map(l => l.copy(utxo = l.utxo ++ created)))
      )
    )

  private val wanted = created.keys.toVector

  private def surviving(storeBeforeHandover: UtxoStore[IO] => IO[Unit]) =
    Handle
      .allow[NodeError] {
        for
          store <- MemoryUtxoStore[IO]
          backing = new UtxoBacking[IO, MirrorState](store, fs2.io.file.Path("unused"))
          rules = MirrorRules[IO](
            setup.praos,
            setup.globals,
            setup.byronRules,
            setup.handOver,
            setup.byronHistory,
            utxoStore = Some(store)
          )
          // the Byron blocks, then each one's state reaches the immutable tip in turn
          byronEnd = headers.indices.take(firstShelley).foldLeft(start) { (s, i) =>
            MirrorSteps.block(setup, s, headers(i), raws(i)) match
              case Right(next) => next
              case Left(f)     => fail(s"block $i rejected by ${f.rule.name}: ${f.reason}")
          }
          _ <- backing.advance(start)
          _ <- storeBeforeHandover(store)
          entered <- rules.apply(byronEnd, headers(firstShelley), raws(firstShelley))
          _ <- backing.advance(entered)
          settled <- backing.settled(entered)
          found <- UtxoStore.complete(Some(store), settled.utxo, wanted)
        yield wanted.map(k => k -> found.get(k).map(_.value.coin))
      }
      .rescue(e => IO.raiseError(new AssertionError(e.message)))

  private val expected = created.toVector.map((k, o) => k -> Some(o.value))

  test("the Byron UTxO is in the store after the handover and the diff is trimmed") {
    surviving(_ => IO.unit).map(r => assertEquals(r.toMap, expected.toMap))
  }

  test("a store that holds the genesis placeholder (a database of an earlier build) is emptied") {
    surviving(store => StorageTestKit.ok(store.write(placeholder, 0))).map(r =>
      assertEquals(r.toMap, expected.toMap)
    )
  }
