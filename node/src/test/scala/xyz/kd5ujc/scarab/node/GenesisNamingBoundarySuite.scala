// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, HeaderRules, SelectView}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{Block, BlockFailure, ByronChainError, Header}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.{ChainStorage, ReceivedBlocks}

/** A Byron boundary block that names the genesis (epoch 0, or the "Genesis" tag) comes after
  * genesis for chain selection, whatever bytes its header holds as the previous hash: consensus's
  * `headerPrevHash` is then `GenesisHash` (`fromByronPrevHash`, `Byron/Ledger/Block.hs:240-246`,
  * ouroboros-consensus 82ecba32), the VolatileDB files the block under it (`biPrevHash`,
  * `Storage/VolatileDB/Impl/Parser.hs:117-123`; `insertMapSet`, `Storage/VolatileDB/Impl.hs:436`),
  * so chain selection finds it after genesis (after an empty selection, or walking back to genesis,
  * `constructPreferableCandidates`, `Storage/ChainDB/Impl/ChainSel.hs:710-777`), and only the
  * ledger reads the bytes, refusing a block whose bytes are not the genesis hash
  * (`updateChainBoundary`, `Chain/Block/Validation.hs:276-287`, cardano-ledger f649f975) (#1505).
  *
  * On the public networks the only such block is the first boundary block of mainnet and of
  * preprod, and its bytes are the configured genesis hash; preview has no Byron block. A boundary
  * block has no signature, so anyone can make one with other bytes there: the gate places it after
  * Origin, and so must the processed forest.
  */
class GenesisNamingBoundarySuite extends FunSuite:
  private def setupOf(network: String): NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(Path.of(s"fixtures/$network/genesis"))
      .fold(e => fail(e.message), identity)

  private def hexLines(file: String): Vector[Bytes] =
    Files
      .readAllLines(Path.of(file))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))

  private val preprod: NodeSetup = setupOf("preprod")
  private val epochSlots = preprod.geometry.byron.epochSlots
  private val genesis: Bytes = preprod.origin.getOrElse(fail("preprod has a Byron genesis"))
  private val raws: Vector[Bytes] = hexLines("fixtures/preprod/blocks.hex")
  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, epochSlots).fold(e => fail(e.message), identity))
  private val recorded: Vector[Header] = blocks.map(_.header)

  /** The recorded first boundary block with `previous` in place of the genesis hash it names. */
  private def forged(previous: Bytes): (Header.Byron, Bytes) =
    val raw = raws(0)
    val at = raw.value.indexOfSlice(genesis.value)
    assert(at > 0)
    val bytes = Bytes(raw.value.patch(at, previous.value, genesis.size))
    Block.decode(bytes, epochSlots).fold(e => fail(e.message), identity).header match
      case h: Header.Byron => (h, bytes)
      case other           => fail(s"not a Byron header: $other")

  private val origin =
    ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
  private def selected(headers: Header*): ChainSelection.Local =
    ChainSelection.Local(
      Point.Origin,
      SelectView(-1, 0, None, 0, None),
      headers.toVector.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
    )

  /** A real block, the first boundary block, and a hash no block has. */
  private val previousBytes: Vector[Bytes] =
    Vector(recorded(1).hash, recorded(0).hash, Bytes(Vector.fill(32)(0x5a.toByte)))

  test("mainnet's and preprod's first boundary blocks name their genesis hash, read as genesis") {
    val mainnet = setupOf("mainnet")
    val ebb = Header
      .byronFromRaw(
        hexLines("fixtures/mainnet/byron-ebb-header.hex").head,
        boundary = true,
        mainnet.geometry.byron.epochSlots
      )
      .fold(e => fail(e.message), identity)
      .withSizeHint(FetchTesting.sizeHint(1))
    for (h, setup) <- Vector[(Header, NodeSetup)](ebb -> mainnet, recorded(0) -> preprod) do
      h match
        case b: Header.Byron =>
          assert(b.boundary && b.namesGenesis)
          assertEquals(b.prevHash, setup.origin)
          assertEquals(b.predecessor, None)
        case other => fail(s"not a Byron header: $other")
    // the rest of preprod's Byron era names blocks, and no block is a genesis
    assert(recorded.drop(1).forall(_.predecessor.isDefined))
    assert(!recorded.exists(_.hash == genesis))
  }

  test("the forest files the first boundary block after genesis, and nothing under the genesis") {
    val forest = ProcessedForest.of(recorded).fold(e => fail(e.toString), identity)
    assertEquals(forest.successors(None).toVector, Vector(recorded(0).hash))
    assertEquals(forest.successors(Some(genesis)).toVector, Vector.empty)
    assertEquals(
      AvailableBlocks.candidates(origin, forest, _ => false).map(c => (c.base, c.headers)),
      Vector(Point.Origin -> recorded)
    )
  }

  test("a boundary block naming the genesis with other bytes there is after genesis") {
    for previous <- previousBytes do
      val (ebb, _) = forged(previous)
      assert(ebb.namesGenesis)
      assertEquals(ebb.prevHash, Some(previous))
      assertEquals(ebb.predecessor, None)
      val forest = ProcessedForest.of(recorded :+ ebb).fold(e => fail(e.toString), identity)
      assert(forest.successors(None).contains(ebb.hash))
      assert(!forest.successors(Some(previous)).contains(ebb.hash))
      // a fork from Origin, whether the first blocks are selected or not, never after `previous`
      for local <- Vector(origin, selected(recorded(0)), selected(recorded.take(2)*)) do
        assertEquals(
          AvailableBlocks.candidates(local, forest, _ => false, Some(Set(ebb.hash))),
          Vector(ChainSelection.Candidate(Point.Origin, Vector(ebb)))
        )
      // no candidate runs through a block to a boundary block that names the genesis
      for
        local <- Vector(origin, selected(recorded(0)))
        candidate <- AvailableBlocks.candidates(local, forest, _ => false)
      do assert(!candidate.headers.drop(1).exists(_.predecessor.isEmpty), candidate.toString)
  }

  test("the received log indexes such a block after genesis, as the VolatileDB's `biPrevHash`") {
    val predecessor = ChainStorage.predecessorOf(epochSlots)
    for previous <- previousBytes do
      assertEquals(predecessor(forged(previous)._2), Some(ReceivedBlocks.Predecessor.Genesis))
    assertEquals(predecessor(raws(0)), Some(ReceivedBlocks.Predecessor.Genesis))
    assertEquals(predecessor(raws(1)), Some(ReceivedBlocks.Predecessor.Block(recorded(0).hash)))
  }

  test("a fetch range never runs from a block into such a block: it starts after Origin") {
    val (ebb, _) = forged(recorded(1).hash)
    assertEquals(
      Peer.ownedRuns(Vector(recorded(1) -> true, ebb -> true)).map(_.toVector),
      Vector(Vector(recorded(1)), Vector(ebb))
    )
    // the recorded chain is one range
    assertEquals(
      Peer.ownedRuns(recorded.take(3).map(_ -> true)).map(_.toVector),
      Vector(recorded.take(3))
    )
  }

  test("the header gate takes such a block after Origin, as the reference's envelope does") {
    // `validateEnvelope` compares `headerPrevHash`, genesis here, with Origin (ouroboros-consensus
    // 82ecba32 `Ouroboros/Consensus/HeaderValidation.hs:364-376`); a boundary header is not signed
    val gate = new NodeHeaderRules[MirrorState](
      preprod.praos,
      preprod.globals,
      preprod.byronRules,
      preprod.handOver
    )
    for previous <- previousBytes do
      val (ebb, _) = forged(previous)
      gate.validate(preprod.mirrorStart, None, gate.at(preprod.mirrorStart), ebb) match
        case HeaderRules.Outcome.Valid(_) => ()
        case other                        => fail(s"refused at Origin: $other")
  }

  test("the rules refuse such a block after genesis, as `updateChainBoundary` refuses it") {
    for previous <- previousBytes do
      val (ebb, raw) = forged(previous)
      assertEquals(
        MirrorSteps.block(preprod, preprod.mirrorStart, ebb, raw),
        Left(BlockFailure.Byron(ByronChainError.PreviousHash(Some(previous), genesis)))
      )
    // the recorded one is accepted there
    assert(MirrorSteps.block(preprod, preprod.mirrorStart, recorded(0), raws(0)).isRight)
  }
