// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.PBftState
import xyz.kd5ujc.scarab.ledger.{Block, ByronChainError}
import xyz.kd5ujc.scarab.ledger.byron.*
import xyz.kd5ujc.scarab.ledger.byron.ByronTestKit.*
import xyz.kd5ujc.scarab.ledger.byron.ByronTestKit.Cb.*

/** A boundary header names the genesis as its predecessor when its epoch is 0 or its attributes
  * hold the "Genesis" tag (`decCBORABoundaryHeader`, `Block/Header.hs:623-624`, cardano-ledger
  * f649f975). The chain's first block is such a header, and a later boundary header names its
  * predecessor (`updateChainBoundary`, `Chain/Block/Validation.hs:276-287`; consensus's
  * `headerPrevHash`, `Byron/Ledger/Block.hs:241-246`, ouroboros-consensus 82ecba32). #754.
  */
class ByronBoundaryPredecessorSuite extends FunSuite:
  private val genesisKeys = Vector.fill(4)(key())
  private val genesis = ByronTestKit.genesis(genesisKeys, Vector.fill(4)(key()))
  private val config = ByronLedger.Config(genesis, requiresNetworkMagic = true)
  private val rulesConfig = ByronRulesConfig(config, 0.6, None)
  private val initial = ByronLedger.initial(config).fold(e => fail(e.toString), identity)
  private val genesisTag = map(uint(255) -> bytes(Bytes.fromArray("Genesis".getBytes("US-ASCII"))))

  /** A boundary block of `epoch` after `prev`, with `attributes` in its header's extra data and
    * `magic` (encoded) as its protocol magic.
    */
  private def boundary(
      epoch: Long,
      prev: Bytes,
      attributes: Bytes,
      magic: Bytes = uint(Magic)
  ): Block.Byron =
    val header = arr(
      magic,
      bytes(prev),
      bytes(Bytes(Vector.fill(32)(0x22.toByte))),
      // the chain's first block is numbered 0 (`expectedFirstBlockNo`); the block rules check no
      // later number, which is chain selection's
      arr(uint(epoch), arr(uint(0))),
      arr(attributes)
    )
    val raw = arr(uint(0), arr(header, indef(), arr(map())))
    Block.decode(raw, config.epochSlots).fold(e => fail(e.toString), identity) match
      case b: Block.Byron => b
      case other          => fail(s"not a Byron block: $other")

  private def first(block: Block.Byron) =
    ByronRules.update(ByronRules.State(None, initial, PBftState.empty), block, rulesConfig)

  private def after(tip: ByronTip, block: Block.Byron) =
    ByronRules.update(ByronRules.State(Some(tip), initial, PBftState.empty), block, rulesConfig)

  test("the tag is read, and a header of epoch 0 or with it names the genesis") {
    assert(boundary(0, genesis.hash, map()).header.namesGenesis)
    assert(boundary(5, genesis.hash, genesisTag).header.genesisTag)
    assert(boundary(5, genesis.hash, genesisTag).header.namesGenesis)
    assert(!boundary(5, genesis.hash, map()).header.namesGenesis)
    // the tag is attribute 255 holding exactly "Genesis"
    assert(!boundary(5, genesis.hash, map(uint(255) -> bytes(Bytes.empty))).header.genesisTag)
  }

  test("a chain starts with a boundary block of epoch 0, or of a later epoch with the tag") {
    assert(first(boundary(0, genesis.hash, map())).isRight)
    assert(first(boundary(5, genesis.hash, genesisTag)).isRight)
    assertEquals(
      first(boundary(5, genesis.hash, map())).left.toOption,
      Some(ByronChainError.InitialBoundaryRequired)
    )
  }

  test("a later boundary block with the tag names the genesis, not its predecessor") {
    val tip = ByronTip(Bytes(Vector.fill(32)(0x44.toByte)), 4, 4 * config.epochSlots + 7, false)
    assert(after(tip, boundary(5, tip.hash, map())).isRight)
    assertEquals(
      after(tip, boundary(5, tip.hash, genesisTag)).left.toOption,
      Some(ByronChainError.GenesisPredecessor(tip.hash))
    )
  }

  test("a boundary block's magic is not compared: it is dropped as it is read (#634)") {
    // `dropInt32` (`Block/Header.hs:617`) and `updateChainBoundary` (`Validation.hs:271-297`);
    // only a main block's magic is compared (`updateBlock`, `:520-525`)
    val other = first(boundary(0, genesis.hash, map(), uint(Magic + 1)))
    assert(other.isRight, other.toString)
    // a negative magic, -1 (`0x20`), is an `Int32` too
    val negative = first(boundary(0, genesis.hash, map(), Bytes(Vector(0x20.toByte))))
    assert(negative.isRight, negative.toString)
  }
