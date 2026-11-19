// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{
  BlockRef,
  BlockRules,
  ChainSelection,
  FollowerEvents,
  VolatileChain
}
import xyz.kd5ujc.scarab.ledger.{
  EpochGeometry,
  Fixtures,
  Header,
  HeaderVrf,
  NodeError,
  TestGeometry,
  VrfCert
}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.protocol.ChainSync

/** A block this node forged goes through the Limit on Eagerness as a peer's candidate does: the
  * reference hands it to `ChainDB.addBlockAsync` (ouroboros-consensus 82ecba329 NodeKernel.hs:756),
  * whose chain selection trims every candidate to the LoE (`constructPreferableCandidates`,
  * ChainSel.hs:767-777); the LoE is the empty fragment at the immutable tip in PreSyncing, the
  * GDD's fragment in Syncing, and off once caught up (Node/Genesis.hs:252-278). With `k` = 2 the
  * chain keeps two volatile blocks, so a third block would make the first immutable.
  */
class ForgedLimitOnEagernessSuite extends FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]
  private val k = 2
  private val geometry =
    EpochGeometry(
      java.time.Instant.EPOCH,
      1000,
      epochLength = 100,
      securityParam = TestGeometry.k(k),
      activeSlotsCoeff = TestGeometry.f("0.5")
    )

  /** Records the block number of the tip as the state. */
  private val rules: BlockRules[IO, BigInt] =
    BlockRules.from((_, header, _) => IO.pure(header.blockNo))
  private val noEvents: FollowerEvents[IO] = _ => IO.unit
  private val windowAt: (BigInt, BigInt, BigInt) => Option[BigInt] = (_, _, _) => Some(30)
  private val headers = Fixtures.chain(k + 3)

  private def adopted(header: Header): Follower.Adoption =
    Follower.Adoption.Adopted(
      BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
    )

  /** What came of offering `blocks` in turn as forged blocks; the chain after them; and what chain
    * selection makes, under the same LoE, of a peer's candidate carrying the block of `headers`
    * that follows the tip.
    */
  private final case class Offered(
      adoptions: Vector[Follower.Adoption],
      chain: VolatileChain[BigInt],
      peer: Vector[ChainSelection.Candidate]
  )

  /** A follower in Praos mode (`None`) or in Genesis mode with its GDD governor in `phase`. */
  private def offer(
      blocks: Vector[Header],
      phase: Option[Gsm.Phase],
      config: GenesisGovernor.Config = GenesisGovernor.Config()
  ): IO[Offered] =
    TestControl.executeEmbed(
      for
        ref <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(k, -1))
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(ref)
        follower <- Follower.create[IO, BigInt](rules, noEvents, geometry)
        governor <- phase.traverse(p =>
          follower
            .genesisView(windowAt)
            .flatMap(GenesisGovernor.create[IO](_, config))
            .flatTap(follower.installGenesis(_, windowAt))
            .flatTap(_.setPhase(p))
        )
        adoptions <- blocks.traverse(h =>
          Handle
            .allow[NodeError](follower.adoptForged(h, Bytes.empty))
            .rescue(e => IO(fail(e.message)))
        )
        after <- ref.get
        selected <- follower.fetchSelection
        limit <- governor.flatTraverse(_.limit)
        next = headers.lift(headers.indexWhere(_.point == after.tip) + 1).toVector
        peer = CandidateSelection.project(
          k,
          selected,
          Vector(ChainSelection.Candidate(after.tip, next)),
          limit
        )
      yield Offered(adoptions, after, peer)
    )

  private def heldBack(phase: Gsm.Phase): IO[Unit] =
    offer(headers.take(k + 1), Some(phase)).map { offered =>
      // no selection worker runs here to keep the held-back block for
      assertEquals(
        offered.adoptions,
        headers.take(k).map(adopted) :+ Follower.Adoption.Lost(
          Follower.AdoptionLoss.LimitOnEagerness(headers(k - 1).point, Follower.Retention.NoWorker)
        ),
        s"$phase: the first $k blocks fill the volatile chain; the next is past the LoE plus k"
      )
      assertEquals(offered.chain.tip, headers(k - 1).point, s"$phase: the tip stays")
      assertEquals(
        offered.chain.anchor.point,
        ChainSync.Point.Origin,
        s"$phase: the immutable tip does not advance"
      )
      // the chain is the same after the refused block, so a peer's candidate with the block that
      // comes after the tip is trimmed alike
      assertEquals(offered.peer, Vector.empty, s"$phase: a peer's candidate is held back too")
    }

  test("PreSyncing: a forged block past the LoE plus k is not adopted; the immutable tip stays") {
    heldBack(Gsm.Phase.PreSyncing)
  }

  test("Syncing, with the LoE fragment at the immutable tip: the same") {
    heldBack(Gsm.Phase.Syncing)
  }

  private def unlimited(phase: Option[Gsm.Phase], config: GenesisGovernor.Config): IO[Unit] =
    offer(headers, phase, config).map { offered =>
      val name = phase.fold("Praos mode")(p => s"$p, $config")
      assertEquals(offered.adoptions, headers.map(adopted), s"$name: every block is adopted")
      assertEquals(offered.chain.tip, headers.last.point, s"$name: the tip")
      assertEquals(
        offered.chain.anchor.point,
        headers(headers.size - 1 - k).point,
        s"$name: the immutable tip follows k blocks behind"
      )
    }

  test("CaughtUp: the LoE is off and forged blocks are adopted past k, as before") {
    unlimited(Some(Gsm.Phase.CaughtUp), GenesisGovernor.Config())
  }

  test("Praos mode, and Genesis mode with LoE and GDD disabled: no LoE, as before") {
    unlimited(None, GenesisGovernor.Config()) *>
      List(Gsm.Phase.PreSyncing, Gsm.Phase.Syncing).traverse_(p =>
        unlimited(Some(p), GenesisGovernor.Config(enabled = false))
      )
  }

  /** A block on the tip's predecessor, of the tip's height and 1 slot after it (within the
    * restricted tie-breaker's 5 slots), with `output` in every byte of its VRF output: `Fixtures`
    * gives the tip 3 in every byte, and the lower output wins (`ChainSelection.prefers`).
    */
  private def rival(output: Int): Header =
    Fixtures.chain(1, salt = 1, from = headers.lift(k - 2)).head match
      case h: Header.Shelley =>
        val vrf = VrfCert(Bytes(Vector.fill(64)(output.toByte)), Bytes(Vector.fill(80)(4.toByte)))
        h.copy(vrf = HeaderVrf.Praos(vrf))
      case other => fail(s"Fixtures builds Shelley headers, got $other")

  test("a block of the tip's height under the LoE is decided by the VRF tie-break: won, adopted") {
    val winner = rival(1)
    offer(headers.take(k) :+ winner, Some(Gsm.Phase.PreSyncing)).map { offered =>
      // the switch keeps the selection within k blocks of the LoE fragment, so the LoE allows it
      assertEquals(offered.adoptions, headers.take(k).map(adopted) :+ adopted(winner))
      assertEquals(offered.chain.tip, winner.point, "the rival replaced the tip")
      assertEquals(offered.chain.anchor.point, ChainSync.Point.Origin, "the immutable tip stays")
    }
  }

  test("a block of the tip's height under the LoE is decided by the VRF tie-break: lost, kept") {
    val loser = rival(5)
    offer(headers.take(k) :+ loser, Some(Gsm.Phase.PreSyncing)).map { offered =>
      // lost to the tip by chain selection, not held back by the LoE
      assertEquals(
        offered.adoptions,
        headers.take(k).map(adopted) :+ Follower.Adoption.Lost(
          Follower.AdoptionLoss.SelectionPrefersTip(headers(k - 1).point)
        )
      )
      assertEquals(offered.chain.tip, headers(k - 1).point, "the tip stays")
      assertEquals(offered.chain.anchor.point, ChainSync.Point.Origin, "the immutable tip stays")
    }
  }
