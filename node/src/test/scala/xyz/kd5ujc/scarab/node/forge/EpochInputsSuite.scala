// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{BlockFailure, Nonce}
import xyz.kd5ujc.scarab.ledger.state.{Conway, NewEpochState}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.mempool.TxKit

/** The inputs of an epoch's leader check, derived from a chain (#1101), are those the forger checks
  * each slot with: one derivation, which the forger calls for every slot.
  */
class EpochInputsSuite extends FailsOnUncaught with ForgeFixture:
  /** `ledger` whose mark snapshot, which elects the next epoch's leaders, gives each pool `stake`.
    */
  private def withMark(
      ledger: NewEpochState[Conway],
      stakes: Vector[(Pool, BigInt)]
  ): NewEpochState[Conway] =
    val mark = ForgedChain.snapshotOf(stakes.map((p, s) => (p.stake.credential, p.creds, s)))
    ledger.copy(es = ledger.es.copy(snapshots = ledger.es.snapshots.copy(mark = mark)))

  test(
    "the inputs derived from a chain are those the forger checks each slot with, either side of an epoch boundary"
  ) {
    val tipEpoch = TxKit.native(1).epoch.toBigInt
    val p = pool(kesStart = (geometry.firstSlot(tipEpoch) / slotsPerKes).toInt, seed = 9)
    val other = pool(kesStart = 0, seed = 3)
    // the pool has all the stake of the epoch's distribution, and half of the mark snapshot
    val ledger = withMark(ledgerWith(p, 1), Vector(p -> BigInt(500000), other -> BigInt(500000)))
    val slots = Vector(tipEpoch, tipEpoch + 1).flatMap(e =>
      (geometry.firstSlot(e) until geometry.firstSlot(e) + 40).toVector
    )
    withHandle {
      for
        // our own rules refuse every block, so the chain stays at its origin, and every slot is
        // forecast from the same tip
        h <- harness(p, ledger, ruleFailure = Some(BlockFailure.HeaderDiffers))
        chain <- h.chain
        vrf <- currentHot(h.forger.hot).map(_.vrf)
        _ <- slots.traverse_(h.forger.step)
        traces <- tracesIn(h)
        forged <- forgedIn(h)
        after <- h.chain
      yield
        assertEquals(after.tipRef, None)
        val derived = slots
          .map(s => s -> h.forger.inputsAt(chain, s, vrf).fold(e => fail(e.message), identity))
          .toMap
        // the boundary moves the nonce (to the candidate's, neutral here) and the distribution
        // (to the mark snapshot)
        val here = derived(geometry.firstSlot(tipEpoch))
        val there = derived(geometry.firstSlot(tipEpoch + 1))
        assertEquals(
          (here.epoch, here.nonce, here.stake, here.total),
          (tipEpoch, epochNonce, BigInt(1000000), BigInt(1000000))
        )
        assertEquals(
          (there.epoch, there.nonce, there.stake, there.total),
          (tipEpoch + 1, Nonce.Neutral, BigInt(500000), BigInt(1000000))
        )
        // the forger's decision of each slot is the check of the slot with the derived inputs
        val decided = traces.collect {
          case ForgeTrace.NodeNotLeader(s)      => s -> false
          case ForgeTrace.NodeIsLeader(s)       => s -> true
          case ForgeTrace.NodeCannotForge(s, _) => s -> true
        }.toMap
        slots.foreach(s =>
          assertEquals(decided.get(s), Some(Leadership.check(s, derived(s)).isRight), s"slot $s")
        )
        // and a forged block's stake is the derived inputs'
        assert(forged.exists(f => geometry.epochOf(f.slot) == tipEpoch), "none forged before")
        assert(forged.exists(f => geometry.epochOf(f.slot) == tipEpoch + 1), "none forged after")
        forged.foreach(f =>
          assertEquals(
            (f.stake, f.totalStake),
            (derived(f.slot).stake, derived(f.slot).total),
            s"slot ${f.slot}"
          )
        )
    }
  }
