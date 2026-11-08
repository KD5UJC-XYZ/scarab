// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.consensus.state.PBftState
import xyz.kd5ujc.scarab.ledger.{Block, ByronChainError, Header, TxIn}
import xyz.kd5ujc.scarab.ledger.byron.*
import xyz.kd5ujc.scarab.ledger.byron.ByronTestKit.*
import xyz.kd5ujc.scarab.ledger.byron.ByronTestKit.Cb.*

/** A Byron block's slot is its epoch and slot count flattened as the reference flattens them:
  * `toSlotNumber` (`Slotting/EpochAndSlotCount.hs:71-81`, cardano-ledger f649f975) in `Word64`
  * arithmetic, which wraps, with no check of the slot count against the epoch's length; a boundary
  * block's is `boundaryBlockSlot` (`Block/Block.hs:533-539`). The order of blocks is the envelope's
  * (`minimumNextSlotNo` and `UnexpectedEBBInSlot`, ouroboros-consensus 82ecba32
  * `Byron/Ledger/HeaderValidation.hs:40-69`). #818.
  */
class ByronSlotSuite extends FunSuite:
  private val genesisKeys = Vector.fill(4)(key())
  private val delegates = Vector.fill(4)(key())
  private val holder = key()
  private val holderAddress = address(holder, Some(Magic))
  private val genesis = ByronTestKit.genesis(
    genesisKeys,
    delegates,
    nonAvvm = Vector(holderAddress -> BigInt(10000000))
  )
  private val config = ByronLedger.Config(genesis, requiresNetworkMagic = true)
  private val rulesConfig = ByronRulesConfig(config, 0.6, None)
  private val initial = ByronLedger.initial(config).fold(e => fail(e.toString), identity)
  private val epochSlots = config.epochSlots

  private def decoded(raw: Bytes): Block.Byron =
    Block.decode(raw, epochSlots).fold(e => fail(e.toString), identity) match
      case b: Block.Byron => b
      case other          => fail(s"not a Byron block: $other")

  /** A boundary block of `epoch` after `prev` (its body empty, no attributes). */
  private def boundary(epoch: BigInt, prev: Bytes): Block.Byron =
    val header = arr(
      uint(Magic),
      bytes(prev),
      bytes(Bytes(Vector.fill(32)(0x22.toByte))),
      arr(ByronCbor.uint(epoch), arr(uint(1))),
      arr(map())
    )
    decoded(arr(uint(0), arr(header, indef(), arr(map()))))

  private def after(tip: ByronTip, block: Block.Byron) =
    ByronRules.update(ByronRules.State(Some(tip), initial, PBftState.empty), block, rulesConfig)

  test("the slot is flattened in Word64 arithmetic, as the reference's toSlotNumber gives it") {
    // the reference's own values (cardano-ledger-byron 1.3.0.0, `toSlotNumber` and
    // `slotNumberEpoch` evaluated in GHC 9.10.3): epoch 2^64 - 1 and slot count 5 of a 21600-slot
    // epoch is slot 18446744073709530021; slot count 21600 of epoch 3 is slot 86400, of epoch 4
    val max = (BigInt(1) << 64) - 1
    assertEquals(Header.byronSlot(max, 21600, 5), BigInt("18446744073709530021"))
    assertEquals(Header.byronSlot(3, 21600, 21600), BigInt(86400))
  }

  test("a signed main block whose slot count is the epoch's length is applied") {
    val transaction = aux(
      config,
      Seq((TxIn(Blake2b.hash256.hash(holderAddress), 0), holder, false)),
      Seq(holderAddress -> 9000000L)
    )
    val raw = ByronTestKit.signedBlock(
      config,
      genesis,
      genesisKeys(0),
      delegates(0),
      transaction,
      ByronTestKit.signedProposal(config, delegates(0), (1L, 0L, 0L)),
      (0L, 0L, 0L),
      slot = (0L, epochSlots.toLong)
    )
    val block = decoded(raw)
    assertEquals(block.header.slot, epochSlots)
    // the epoch of the flattened slot (`slotNumberEpoch`), not the header's epoch field
    assertEquals((block.header.epoch, block.header.slotEpoch), (BigInt(0), BigInt(1)))
    val applied = after(ByronTip(genesis.hash, 0, 0, boundary = true), block)
    assert(applied.isRight, applied.toString)
  }

  test("a boundary block may follow a boundary block, an epoch with no blocks between them") {
    val tip = ByronTip(Bytes(Vector.fill(32)(0x44.toByte)), 4, 4 * epochSlots, boundary = true)
    val next = after(tip, boundary(6, tip.hash))
    assert(next.isRight, next.toString)
    // a second boundary block in the same slot is not after the tip
    assertEquals(
      after(tip, boundary(4, tip.hash)).left.toOption,
      Some(ByronChainError.SlotOrder(4 * epochSlots, 4 * epochSlots + 1))
    )
  }

  test("a boundary block whose flattened slot wrapped off a multiple of the epoch is refused") {
    // epoch * epochSlots past 2^64 wraps to a slot that is not a multiple of epochSlots
    val epoch = ((BigInt(1) << 64) / epochSlots) + 1
    val slot = (epoch * epochSlots).mod(BigInt(1) << 64)
    assertNotEquals(slot.mod(epochSlots), BigInt(0))
    val tip = ByronTip(Bytes(Vector.fill(32)(0x44.toByte)), 0, 0, boundary = false)
    assert(slot >= 1, "the wrapped slot is after the tip")
    assertEquals(
      after(tip, boundary(epoch, tip.hash)).left.toOption,
      Some(ByronChainError.BoundarySlot(slot, epochSlots))
    )
  }
