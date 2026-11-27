// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.mtl.{Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.{BlockPoolProblem, Header, NodeError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.{
  BlockPool,
  BlockSelection,
  CandidateSelection,
  GenesisGovernor,
  RefStateful,
  SelectionWork,
  WorkerTesting
}
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

  /** With the node's selection worker running, as `Main` runs it: the blocks held back in
    * PreSyncing are kept, and once the node is caught up the worker selects the one of them chain
    * selection prefers, with the real block rules and without the forger offering it again. The
    * forger's traces stay those of the submissions (`TraceDidntAdoptBlock`): the reference traces
    * the later selection as the ChainDB's (`ChainSelReprocessLoEBlocks`, ouroboros-consensus
    * 82ecba329 ChainSel.hs:358-413), not the forging loop's.
    */
  test("PreSyncing, then caught up: a held-back block is selected later; its trace is unchanged") {
    TestControl
      .executeEmbed(withHandle {
        for
          h <- harness(p, ledgerWith(p, 1))
          governor <- h.follower.genesisView(noWindow).flatMap(GenesisGovernor.create[IO](_))
          _ <- h.follower.installGenesis(governor, noWindow)
          given Stateful[IO, VolatileChain[MirrorState]] = RefStateful(h.ref)
          pool <- WorkerTesting.pool(h.follower)
          outcome <- WorkerTesting.selection(h.follower, pool).surround {
            for
              _ <- slots.toList.traverse_(s => h.forger.step(s))
              forged <- forgedIn(h)
              heldBack = forged.drop(k).map(_.hash).toSet
              before <- h.chain
              forest <- pool.processedSnapshot
              kept = forest.values.toVector
                .collect {
                  case kept if kept.origin == BlockPool.Source.Forged => kept.delivered.header
                }
                .filter(h => heldBack(h.hash))
              keptAll = forest.values.count(_.origin == BlockPool.Source.Forged)
              _ <- governor.setPhase(Gsm.Phase.CaughtUp)
              after <- h.ref.discrete
                .find(_.tipRef.exists(tip => heldBack(tip.hash)))
                .compile
                .lastOrError
                .timeoutTo(1.minute, IO(fail("no held-back block was selected")))
              traces <- tracesIn(h)
            yield (forged, traces, before, kept, keptAll, after)
          }
        yield outcome
      })
      .map { (forged, traces, before, kept, keptAll, after) =>
        assert(forged.size > k + 1, s"more than ${k + 1} of 40 slots won, got ${forged.size}")
        assertEquals(before.tipRef.map(_.hash), forged.lift(k - 1).map(_.hash), "held back")
        assertEquals(kept.map(_.hash).toSet, forged.drop(k).map(_.hash).toSet, "and kept")
        assertEquals(keptAll, forged.size, "the adopted blocks are kept as well")
        val selected = kept.filter(h => after.tipRef.exists(_.hash == h.hash))
        assertEquals(selected.map(_.hash), after.tipRef.map(_.hash).toVector, "one of them")
        assertEquals(
          selected.flatMap(s => kept.filter(o => CandidateSelection.compareTips(s, o) > 0)),
          Vector.empty,
          "caught up, selection takes the held-back block it prefers to all the others"
        )
        // caught up, the chain is the first k blocks and the held-back block selection took
        assertEquals(after.anchor.block.map(_.hash), forged.headOption.map(_.hash), "immutable")
        assertEquals(after.blocks.dropRight(1).map(_._1.hash), forged.slice(1, k).map(_.hash))
        assertEquals(adoptedIn(traces), forged.take(k).map(_.hash), "adopted at submission")
        assertEquals(lostIn(traces), forged.drop(k).map(_.hash), "the rest DidntAdoptBlock")
      }
  }

  /** Writing a forged block to the received log fails, with a typed failure that is not a storage
    * failure: no forged block is kept, each gets a `notKept` line, and the forger's outcomes are
    * those of a run that keeps them: the first `k` adopted, each later one `TraceDidntAdoptBlock`;
    * the loop goes on to its last slot.
    */
  test("PreSyncing, keeping fails: the forger's outcomes are as they were, and the loop goes on") {
    val failing = new BlockSelection.Before[IO]:
      def prepare(@scala.annotation.unused header: Header, @scala.annotation.unused raw: Bytes)(
          using Raise[IO, NodeError]
      ): IO[Unit] = IO.unit
      def store(header: Header, @scala.annotation.unused raw: Bytes)(using
          Raise[IO, NodeError]
      ): IO[Unit] = BlockPoolProblem.MissingDeliveredHeader(header.point).raise[IO, Unit]
      def ahead(
          @scala.annotation.unused header: Header,
          @scala.annotation.unused raw: Bytes
      ): IO[Unit] = IO.unit
    TestControl
      .executeEmbed(withHandle {
        for
          h <- harness(p, ledgerWith(p, 1))
          governor <- h.follower.genesisView(noWindow).flatMap(GenesisGovernor.create[IO](_))
          _ <- h.follower.installGenesis(governor, noWindow)
          pool <- WorkerTesting.pool(h.follower)
          _ <- h.follower.installFetchCoordinator(pool.fetchCoordinator) *>
            h.follower.installInvalidBlocks(pool.invalidBlocks)
          work <- SelectionWork.create[IO](pool.fetchCoordinator.refreshGenesis)
          given BlockSelection.Before[IO] = failing
          outcome <- h.follower.blockSelection(pool, work, WorkerTesting.unexpected).surround {
            for
              _ <- slots.toList.traverse_(s => h.forger.step(s))
              forged <- forgedIn(h)
              traces <- tracesIn(h)
              lost <- h.seen.map(_.collect { case l: ForgeEvent.Lost => l.hash })
              unkept <- h.seen.map(_.collect { case n: ForgeEvent.NotKept => n.hash })
              forest <- pool.processedSnapshot
              chain <- h.chain
            yield (forged, traces, lost, unkept, forest.keySet, chain)
          }
        yield outcome
      })
      .map { (forged, traces, lost, unkept, forest, chain) =>
        assert(forged.size > k, s"more than $k of 40 slots won, got ${forged.size}")
        assertEquals(adoptedIn(traces), forged.take(k).map(_.hash), "adopted at submission")
        assertEquals(lostIn(traces), forged.drop(k).map(_.hash), "the rest DidntAdoptBlock")
        assertEquals(lost, forged.drop(k).map(_.hash), "each logged lost")
        assertEquals(unkept, forged.map(_.hash), "each logged notKept")
        assertEquals(forest, Set.empty, "none is kept")
        assertEquals(chain.tipRef.map(_.hash), forged.lift(k - 1).map(_.hash), "the tip stays")
      }
  }
