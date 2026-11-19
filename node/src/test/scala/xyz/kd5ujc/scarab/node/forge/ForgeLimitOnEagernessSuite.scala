// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.GenesisGovernor
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The forging loop of a node in Genesis mode: a block it forges past the Limit on Eagerness plus
  * `k` is not adopted and is traced `TraceDidntAdoptBlock`, as the reference's `forkBlockForging`
  * traces a block `ChainDB.addBlockAsync` did not select (ouroboros-consensus 82ecba329
  * NodeKernel.hs:756-769; Forge.hs:284-297 at 808e40054). The forger itself has no Genesis gate
  * (NodeKernel.hs:575-795): it forges in every slot it leads, on our selection.
  */
class ForgeLimitOnEagernessSuite extends FailsOnUncaught with ForgeFixture:
  private val p = pool(kesStart = 50)
  private val slots = 5000 until 5040

  /** No Genesis window: density is never decided, so the LoE fragment stays as the phase gives it.
    */
  private val noWindow: (MirrorState, BigInt, BigInt) => Option[BigInt] = (_, _, _) => None

  /** What the loop traced over `slots` and the chain after them, in Praos mode (`None`) or in
    * Genesis mode with the GDD governor in `phase`.
    */
  private def forging(
      phase: Option[Gsm.Phase]
  ): IO[(Vector[ForgeEvent.Forged], Vector[ForgeTrace], VolatileChain[MirrorState])] =
    TestControl.executeEmbed(
      withHandle {
        for
          h <- harness(p, ledgerWith(p, 1))
          _ <- phase.traverse_(value =>
            h.follower
              .genesisView(noWindow)
              .flatMap(GenesisGovernor.create[IO](_))
              .flatTap(h.follower.installGenesis(_, noWindow))
              .flatMap(_.setPhase(value))
          )
          _ <- slots.toList.traverse_(s => h.forger.step(s))
          forged <- forgedIn(h)
          traces <- tracesIn(h)
          chain <- h.chain
        yield (forged, traces, chain)
      }
    )

  private val k = geometry.k.toInt

  private def adoptedIn(traces: Vector[ForgeTrace]) =
    traces.collect { case t: ForgeTrace.AdoptedBlock => t.hash }
  private def lostIn(traces: Vector[ForgeTrace]) =
    traces.collect { case t: ForgeTrace.DidntAdoptBlock => t.hash }

  private def heldBack(phase: Gsm.Phase): IO[Unit] =
    forging(Some(phase)).map { (forged, traces, chain) =>
      assert(forged.size > k, s"$phase: f = 1/2 with all the stake: more than $k of 40 slots won")
      assertEquals(adoptedIn(traces), forged.take(k).map(_.hash), s"$phase: the first $k adopted")
      // every later block is forged on the same tip, k blocks past the LoE, and none is adopted
      assertEquals(lostIn(traces), forged.drop(k).map(_.hash), s"$phase: the rest not adopted")
      assertEquals(forged.drop(k).map(_.prev).distinct, Vector(forged.lift(k - 1).map(_.hash)))
      assertEquals(chain.tipRef.map(_.hash), forged.lift(k - 1).map(_.hash), s"$phase: the tip")
      assertEquals(chain.anchor.point, ChainSync.Point.Origin, s"$phase: the immutable tip stays")
    }

  test("PreSyncing: a block forged past the LoE plus k is not adopted: TraceDidntAdoptBlock") {
    heldBack(Gsm.Phase.PreSyncing)
  }

  test("Syncing, with the LoE fragment at the immutable tip: the same") {
    heldBack(Gsm.Phase.Syncing)
  }

  private def unlimited(phase: Option[Gsm.Phase]): IO[Unit] =
    forging(phase).map { (forged, traces, chain) =>
      val name = phase.fold("Praos mode")(_.toString)
      assert(forged.size > k, s"$name: f = 1/2 with all the stake: more than $k of 40 slots won")
      assertEquals(adoptedIn(traces), forged.map(_.hash), s"$name: every block adopted")
      assertEquals(lostIn(traces), Vector.empty, s"$name: none lost")
      assertEquals(chain.tipRef.map(_.hash), forged.lastOption.map(_.hash), s"$name: the tip")
      assertEquals(
        chain.anchor.block.map(_.hash),
        forged.lift(forged.size - 1 - k).map(_.hash),
        s"$name: the immutable tip follows k blocks behind"
      )
    }

  test("CaughtUp, and Praos mode: every forged block is adopted, as before") {
    unlimited(Some(Gsm.Phase.CaughtUp)) *> unlimited(None)
  }
