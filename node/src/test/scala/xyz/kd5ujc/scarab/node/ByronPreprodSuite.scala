// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent, NodeState}
import xyz.kd5ujc.scarab.ledger.{Block, BlockFailure, BlockRule, Header}
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.consensus.ByronRules
import xyz.kd5ujc.scarab.ledger.byron.{ByronLedger, ByronVersion}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** The whole Byron era of the public preprod testnet (45 main blocks and the epoch 0 boundary
  * block, `fixtures/preprod`) and the first Shelley blocks, replayed from the genesis files and the
  * node configuration of the network: the chain has no `TestShelleyHardForkAtEpoch`, so the epoch
  * in which it enters Shelley is derived from the Byron update proposals, votes and endorsements
  * the blocks carry.
  */
class ByronPreprodSuite extends FunSuite:
  private val setup: NodeSetup =
    NodeSetup
      .fromDirectory(Path.of("fixtures/preprod/genesis"))
      .fold(e => fail(e.message), identity)

  private given xyz.kd5ujc.scarab.node.read.ChainRead[xyz.kd5ujc.scarab.consensus.NodeState] =
    xyz.kd5ujc.scarab.node.read.ChainRead.nodeState(setup.mirrorEnv())

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(Path.of("fixtures/preprod/blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))

  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  private val byronCount = blocks.count(_.era == Era.Byron)

  private def ref(i: Int): BlockRef =
    BlockRef(
      blocks(i).header.slot,
      blocks(i).header.hash,
      blocks(i).header.blockNo,
      blocks(i).header.isBoundary
    )

  private def step(state: NodeState, i: Int): Either[BlockFailure, NodeState] =
    NodeRules.applyBlock(
      state,
      blocks(i).header,
      raws(i),
      setup.praos,
      setup.globals,
      setup.byronRules
    )

  private lazy val states: Vector[NodeState] =
    blocks.indices.foldLeft(Vector(setup.start)) { (acc, i) =>
      step(acc.last, i).fold(
        f =>
          fail(
            s"block $i (${blocks(i).era}, slot ${blocks(i).header.slot}) rejected by ${f.rule.name}: ${f.reason}"
          ),
        acc :+ _
      )
    }

  test("the configuration fixes neither the Byron epoch length nor the Shelley epoch") {
    assertEquals(setup.config.firstShelleyEpoch, None)
    assert(setup.geometry.byron.derived)
    // epoch length from the Byron genesis (k = 2160), not a default
    assertEquals(setup.geometry.byron.epochSlots, BigInt(2160) * 10)
    assertEquals(setup.byronRules.get.configuredShelleyEpoch, None)
    assertEquals(setup.byronRules.get.signatureThreshold, 0.22)
    assert(setup.byronRules.get.ledger.requiresNetworkMagic)
  }

  test("Byron slots come from the genesis epoch length: the first block of epoch 1 is slot 10k") {
    val first = blocks(11).header.asInstanceOf[Header.Byron]
    assertEquals((first.epoch, first.slotInEpoch), (BigInt(1), BigInt(0)))
    assertEquals(first.slot, BigInt(2160) * 10)
    // read with another epoch length the same bytes land on another slot
    val other = Block.decode(raws(11), BigInt(50)).toOption.get.header
    assertEquals(other.slot, BigInt(50))
  }

  test("the capture is the epoch 0 boundary block, 45 main blocks, then Shelley blocks") {
    assertEquals(byronCount, 46)
    assert(blocks.take(46).forall(_.era == Era.Byron))
    assert(blocks.drop(46).forall(_.era == Era.Shelley))
    assert(blocks.head.header.isBoundary)
    assertEquals(blocks.head.header.prevHash, setup.origin)
  }

  test("every Byron block is accepted: signatures, delegation, PBFT window, body proofs, ledger") {
    assertEquals(states.size, blocks.size + 1)
    val epochs = blocks.indices.flatMap { i =>
      NodeRules.observe(setup.geometry)(states(i), states(i + 1), ref(i)).collect {
        case e: FollowerEvent.ByronEpochValidated => e
      }
    }
    assertEquals(epochs.map(_.epoch).toVector, Vector(BigInt(0), BigInt(1), BigInt(2), BigInt(3)))
    // every main block verified, on the round-robin schedule, with no transaction on this chain
    assertEquals(epochs.map(_.boundary).sum + epochs.map(_.verified).sum, 46L)
    assertEquals(epochs.map(_.verified).sum, 45L)
    assertEquals(epochs.map(_.offSchedule).sum, 0L)
    assertEquals(epochs.map(_.txs).sum, 0L)
  }

  test("the update proposals of the chain make the protocol 1.0.0 and then 2.0.0, a candidate") {
    val versions =
      (0 to byronCount).flatMap(i => states(i).byron.ledger.map(_.update.adoptedVersion))
    assertEquals(versions.head, ByronVersion.zero)
    assertEquals(versions.distinct, Vector(ByronVersion.zero, ByronVersion(1, 0, 0)))
    val last = states(byronCount).byron.ledger.get
    assertEquals(last.update.candidates.map(_.version), Vector(ByronVersion(2, 0, 0)))
  }

  test("the transition to Shelley is derived from the update state: epoch 4, as on the network") {
    val last = states(byronCount).byron.ledger.get
    assertEquals(ByronLedger.transition(setup.byronRules.get.ledger, last), Some(BigInt(4)))
    assertEquals(states.last.byron.firstShelleyEpoch, Some(BigInt(4)))
    assertEquals(states.last.byron.ledger, None)
    assertEquals(states(byronCount + 1).ledger.epoch, BigInt(4))
    assertEquals(
      states.last.geometry(setup.geometry).firstShelleySlot,
      BigInt(4) * setup.geometry.byron.epochSlots
    )
  }

  test(
    "the Shelley ledger starts from the Byron UTxO; the zero-lovelace genesis outputs are dropped"
  ) {
    val ledger = states(byronCount + 1).ledger
    // Eight genesis balances, seven of them zero lovelace: the ledger's Byron to Shelley translation
    // (`translateUTxOByronToShelley`) drops those, as the Haskell node does on Preview.
    assertEquals(ledger.utxo.size, 1)
    val byronTotal = states(byronCount).byron.ledger.get.utxo.values.map(_.value).sum
    assertEquals(ledger.utxo.coin, byronTotal)
    assertEquals(ledger.reserves, setup.genesis.maxLovelaceSupply - byronTotal)
  }

  test("the era entered is Shelley at epoch 4, observed once") {
    val seen = blocks.indices.flatMap { i =>
      NodeRules.observe(setup.geometry)(states(i), states(i + 1), ref(i)).collect {
        case FollowerEvent.EraEntered(from, to, _, epoch) => (from, to, epoch)
      }
    }
    assertEquals(
      seen.toVector,
      Vector((None, Era.Byron, BigInt(0)), (Some(Era.Byron), Era.Shelley, BigInt(4)))
    )
  }

  test("a Shelley block before the Byron ledger has decided on the transition is refused") {
    val early = states(30) // protocol 2.0.0 is proposed but not yet a stable candidate
    val result = step(early, byronCount)
    assert(result.left.exists(_.rule == BlockRule.Byron), result.toString)
  }

  test("a configured Shelley epoch overrides the derivation, and Byron blocks end there") {
    val later = setup.byronRules.map(_.copy(configuredShelleyEpoch = Some(BigInt(5))))
    val firstShelley = NodeRules.applyBlock(
      states(byronCount),
      blocks(byronCount).header,
      raws(byronCount),
      setup.praos,
      setup.globals,
      later
    )
    // the block lies in epoch 4, before the configured epoch 5
    assert(firstShelley.left.exists(_.rule == BlockRule.Byron), firstShelley.toString)
    val earlier = setup.byronRules.map(_.copy(configuredShelleyEpoch = Some(BigInt(2))))
    val lastByron = NodeRules.applyBlock(
      states(30),
      blocks(30).header,
      raws(30),
      setup.praos,
      setup.globals,
      earlier
    )
    assert(lastByron.left.exists(_.rule == BlockRule.Structure), lastByron.toString)
  }

  test("the header and body of a Byron block are bound: a changed update payload is refused") {
    val i = 23 // the block carrying the version 2.0.0 proposal and its votes
    val b = blocks(i).asInstanceOf[Block.Byron]
    val raw = raws(i)
    val at = raw.size - 70 // inside the update payload's signatures
    val bad = Bytes(raw.value.updated(at, (raw.value(at) ^ 1).toByte))
    val result =
      NodeRules.applyBlock(states(i), b.header, bad, setup.praos, setup.globals, setup.byronRules)
    assert(result.isLeft, result.toString)
  }

  test(
    "the Byron state, and the Shelley state after the hand-over, survive a snapshot round trip"
  ) {
    import xyz.kd5ujc.scarab.legacy
    Vector(1, 20, byronCount, byronCount + 5).foreach { i =>
      val state = states(i)
      val old = legacy.SnapshotV2.encode(
        legacy.StateSnapshot(Option(ref(i - 1)), legacy.Downgrade.node(state))
      )(using legacy.NodeCodecs.nodeSnapshot)
      val back = legacy.SnapshotV2
        .decodeNode(old)
        .fold(e => fail(e.message), identity)
      val upgraded = legacy.Upgrade
        .node(back.state, setup.praos.forks, back.anchor)
        .fold(e => fail(e.toString), identity)
      assertEquals(upgraded.byron, state.byron, s"state before block $i")
      assertEquals(upgraded.ledger.utxo.size, state.ledger.utxo.size)
    }
  }

  test("PBFT is evaluated against the genesis keys in ascending hash order") {
    val keys = setup.byron.get.genesisKeys
    assertEquals(keys.size, 7)
    assertEquals(keys, keys.sortBy(_.hex))
    assertEquals(ByronRules.scheduledLeader(setup.byron.get, 7), keys(0))
    assertEquals(ByronRules.scheduledLeader(setup.byron.get, 13), keys(6))
  }
