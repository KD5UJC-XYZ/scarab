// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ForkTrigger, HardForkConfig}
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.protocol.Era

/** The extension of a telescope into the era a block enters (`MirrorStep.into`), the combinator's
  * `Telescope.extend` (`HardFork/Combinator/State.hs`, `extendToSlot`).
  */
class MirrorStepSuite extends FunSuite:
  private val genesisHash = Bytes.fromArray(Array.fill(32)(7.toByte))
  private def dep(era: Era): ChainDepState = ProtocolState.initial(era, genesisHash).dep

  private val geometry = EpochGeometry(
    java.time.Instant.EPOCH,
    1000,
    epochLength = 500,
    securityParam = 5,
    activeSlotsCoeff = 0.5
  )
  private val byron = EraHistory.ByronParams(50, 20000, 5)

  private def bound(epoch: Int) = EraHistory.boundAt(geometry, byron, epoch)

  private val inShelley: Telescope[ChainDepState] =
    Telescope
      .make(Vector(Past(bound(0), bound(0))), Current(bound(0), dep(Era.Shelley)))
      .fold(e => fail(e.toString), identity)

  test("in the era the telescope is in, the state is replaced and the bounds stay") {
    val next = MirrorStep
      .into(inShelley, Era.Shelley, bound(3), dep(Era.Shelley))
      .fold(e => fail(e.toString), identity)
    assertEquals(next.era, Era.Shelley)
    assertEquals(next.past, inShelley.past)
    assertEquals(next.current.start, inShelley.current.start)
  }

  test("a later era: the current era ends where the next begins") {
    val next = MirrorStep
      .into(inShelley, Era.Allegra, bound(4), dep(Era.Shelley))
      .fold(e => fail(e.toString), identity)
    assertEquals(next.era, Era.Allegra)
    assertEquals(next.past, inShelley.past :+ Past(bound(0), bound(4)))
    assertEquals(next.current.start, bound(4))
  }

  test("several eras at once: the ones skipped are empty at the bound they are entered at") {
    val next = MirrorStep
      .into(inShelley, Era.Conway, bound(2), dep(Era.Conway))
      .fold(e => fail(e.toString), identity)
    assertEquals(next.era, Era.Conway)
    assertEquals(next.past.size, Era.Conway.index)
    assertEquals(next.past(1), Past(bound(0), bound(2)))
    assertEquals(next.past.drop(2), Vector.fill(4)(Past(bound(2), bound(2))))
    // the protocol must be that of the era: TPraos does not run Conway
    assert(MirrorStep.into(inShelley, Era.Conway, bound(2), dep(Era.Shelley)).isLeft)
  }

  test("an earlier era is refused") {
    assert(MirrorStep.into(inShelley, Era.Byron, bound(2), dep(Era.Byron)).isLeft)
  }

  test("an era begins at its configured epoch, else in the epoch of the block, never before") {
    val placement = Placement(
      geometry,
      byron,
      HardForkConfig(Map(Era.Mary -> ForkTrigger.AtEpoch(BigInt(9))), 0)
    )
    val slotInEpoch6 = geometry.firstSlot(6) + 3
    assertEquals(
      MirrorStep.startOf(placement, Era.Mary, slotInEpoch6, bound(2)),
      bound(9)
    )
    assertEquals(MirrorStep.startOf(placement, Era.Alonzo, slotInEpoch6, bound(2)), bound(6))
    assertEquals(MirrorStep.startOf(placement, Era.Alonzo, slotInEpoch6, bound(8)), bound(8))
  }
