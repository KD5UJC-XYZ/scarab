// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, BlockRules, FollowerEvent, VolatileChain}
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.{Fragment, LatestSlot}
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  BlockPoolProblem,
  Header,
  NodeError,
  StorageError,
  ValidationError
}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ChainSync

/** A block this node forged that the Limit on Eagerness holds back is kept, and the selection that
  * a move of the LoE or the node's catching up triggers considers it again, without the block being
  * offered a second time. The reference's ChainDB puts the forged block in its VolatileDB before
  * chain selection (ouroboros-consensus 82ecba329 `Storage/ChainDB/Impl/ChainSel.hs:450`), and
  * `ChainSelReprocessLoEBlocks` (ChainSel.hs:358-413), which the GDD governor triggers when the LoE
  * tip changes in Syncing and when the node is caught up (`Genesis/Governor.hs:172-184`), selects
  * among the successors of the current chain's blocks that the VolatileDB holds, are not on that
  * chain and are not known invalid; the same at 808e40054 (ChainSel.hs:363-418, 455). The block is
  * adopted then if it is still valid and preferred; a better chain that arrived meanwhile wins over
  * it, and a refusal of it blames nobody (the forging loop's `noPunishment`, NodeKernel.hs:747). A
  * failure to keep the block leaves the forger's outcome as it is, but a storage failure stops the
  * node, as it does when a peer's block is written to the received log.
  *
  * The node is a follower in Genesis mode with its selection worker and GDD governor, as `Main`
  * installs them, and block rules that record the tip's block number; the blocks are a chain forged
  * with valid headers ([[ForgedChain]]). With `k` = 2 and the LoE at the immutable tip (Origin),
  * the first two blocks fill the volatile chain and a third is past the LoE plus `k`.
  */
class HeldBackForgedBlockSuite extends FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]
  private val k = 2
  private val windowAt: (BigInt, BigInt, BigInt) => Option[BigInt] = (_, _, _) => Some(30)

  /** A hang names itself; nothing in these cases waits for time. */
  private val backstop = 1.minute

  private final case class Node(
      pool: BlockPool[IO],
      follower: Follower[IO, BigInt],
      governor: GenesisGovernor[IO],
      events: SignallingRef[IO, Vector[FollowerEvent]],
      prepared: Ref[IO, Vector[Bytes]],
      fatals: Ref[IO, Vector[Fatal]]
  ):
    def offer(header: Header, raw: Bytes)(using Handle[IO, NodeError]): IO[Follower.Adoption] =
      follower.adoptForged(header, raw)

    /** Until our chain's tip is `point`. */
    def tipAt(point: ChainSync.Point): IO[VolatileChain[BigInt]] =
      follower.committedChain.discrete.find(_.tip == point).compile.lastOrError

    def extended: IO[Vector[BlockRef]] = events.get.map(Node.extended)

    /** Until the chain's extension by `hash` is reported. */
    def extendedBy(hash: Bytes): IO[Unit] =
      events.discrete.find(Node.extended(_).exists(_.hash == hash)).compile.drain

    /** Until the worker has finished the selection it is running and every one queued: its own
      * reselection, queued behind them, has run (`BlockAdmissions.reprocess`, first in, first out).
      */
    def settled: IO[Unit] = pool.admissions.reprocess

  private object Node:
    def extended(events: Vector[FollowerEvent]): Vector[BlockRef] =
      events.collect { case FollowerEvent.Extended(ref, _, _, _) => ref }

  /** What the worker's preparation of a block does, after it records the block. */
  private enum Preparation:
    case Records
    case Raises(error: NodeError)
    case Throws(cause: Throwable)

  /** What is put in the received log before a block joins the processed forest, recorded. */
  private def recording(
      prepared: Ref[IO, Vector[Bytes]],
      preparation: Preparation
  ): BlockSelection.Before[IO] =
    new BlockSelection.Before[IO]:
      def prepare(header: Header, @scala.annotation.unused raw: Bytes)(using
          Raise[IO, NodeError]
      ): IO[Unit] = prepared.update(_ :+ header.hash) *> (preparation match
        case Preparation.Records       => IO.unit
        case Preparation.Raises(error) => error.raise[IO, Unit]
        case Preparation.Throws(cause) => IO.raiseError(cause))

  /** A node in `phase` keeping `volatile` blocks, its worker started; `refused` are the blocks its
    * rules refuse, and `preparation` what its preparation of a block does.
    */
  private def node[A](
      phase: Gsm.Phase,
      refused: Set[Bytes] = Set.empty,
      volatile: Int = k,
      preparation: Preparation = Preparation.Records
  )(
      use: Handle[IO, NodeError] ?=> Node => IO[A]
  ): IO[A] =
    for
      state <- Ref.of[IO, VolatileChain[BigInt]](VolatileChain.genesis(volatile, -1))
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      pool <- BlockPool.create[IO]
      events <- SignallingRef.of[IO, Vector[FollowerEvent]](Vector.empty)
      prepared <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      fatals <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      follower <- Follower.create[IO, BigInt](
        BlockRules.from((_, h, _) =>
          if refused(h.hash) then
            Raise[IO, ValidationError]
              .raise(ValidationError(h.point, BlockFailure.EpochPrecedesLedger(0)))
          else IO.pure(h.blockNo)
        ),
        event => events.update(_ :+ event),
        ForgedChain.geometry
      )
      _ <- follower.installFetchCoordinator(pool.fetchCoordinator) *>
        follower.installInvalidBlocks(pool.invalidBlocks)
      governor <- follower.genesisView(windowAt).flatMap(GenesisGovernor.create[IO](_))
      _ <- follower.installGenesis(governor, windowAt) *> governor.setPhase(phase)
      work <- SelectionWork.create[IO](pool.fetchCoordinator.refreshGenesis)
      given BlockSelection.Before[IO] = recording(prepared, preparation)
      result <- Handle
        .allow[NodeError] {
          follower
            .blockSelection(pool, work, fault => fatals.update(_ :+ fault))
            .use(_ => use(Node(pool, follower, governor, events, prepared, fatals)))
            .timeoutTo(backstop, IO(fail(s"the case did not finish within $backstop")))
        }
        .rescue(error => IO(fail(error.message)))
    yield result

  private def adopted(header: Header): Follower.Adoption =
    Follower.Adoption.Adopted(BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary))

  private def heldBack(
      tip: Header,
      retention: Follower.Retention = Follower.Retention.Kept
  ): Follower.Adoption =
    Follower.Adoption.Lost(Follower.AdoptionLoss.LimitOnEagerness(tip.point, retention))

  private def block(header: Header): ChainSync.Point.Block = header.point match
    case point: ChainSync.Point.Block => point
    case ChainSync.Point.Origin       => fail(s"a forged header at Origin: $header")

  /** What a node in `phase` makes of the first three blocks of `chain` offered as forged ones, and
    * what is kept of the third.
    */
  private final case class Offered(
      adoptions: Vector[Follower.Adoption],
      kept: Option[BlockPool.Source],
      prepared: Vector[Bytes]
  )

  private def offerThree(n: Node, chain: ForgedChain)(using Handle[IO, NodeError]): IO[Offered] =
    for
      adoptions <- chain.headers.zip(chain.raws).take(3).traverse(n.offer(_, _))
      forest <- n.pool.processedSnapshot
      prepared <- n.prepared.get
    yield Offered(adoptions, forest.get(chain.headers(2).hash).map(_.origin), prepared)

  private def assertKept(offered: Offered, chain: ForgedChain, name: String): Unit =
    val headers = chain.headers
    assertEquals(
      offered.adoptions,
      headers.take(2).map(adopted) :+ heldBack(headers(1)),
      s"$name: the first two blocks fill the volatile chain; the third is held back by the LoE"
    )
    assertEquals(
      offered.kept,
      Some(BlockPool.Source.Forged),
      s"$name: the held-back block is in the processed forest, as a block this node forged"
    )
    assertEquals(
      offered.prepared,
      Vector(headers(2).hash),
      s"$name: it was put in the received log first; the adopted blocks were not"
    )

  test("PreSyncing, then caught up: the held-back forged block is adopted, not offered again") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      third = chain.headers(2)
      _ <- TestControl.executeEmbed(node(Gsm.Phase.PreSyncing) { n =>
        for
          offered <- offerThree(n, chain)
          before <- n.extended
          _ <- n.governor.setPhase(Gsm.Phase.CaughtUp)
          _ <- n.extendedBy(third.hash) *> n.settled
          after <- n.follower.committedChain.get
          extended <- n.extended
          fatals <- n.fatals.get
        yield
          assertKept(offered, chain, "PreSyncing")
          assertEquals(before.map(_.hash), chain.headers.take(2).map(_.hash), "adopted at once")
          assertEquals(after.tip, third.point, "caught up, selection takes the kept block")
          assertEquals(
            extended.map(_.hash),
            chain.headers.take(3).map(_.hash),
            "its adoption is reported as the chain's extension, once"
          )
          assertEquals(after.anchor.point, chain.headers(0).point, "the first block is immutable")
          assertEquals(fatals, Vector.empty)
      })
    yield ()
  }

  test("Syncing, then the GDD moves the LoE: the held-back forged block is adopted") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      third = chain.headers(2)
      _ <- TestControl.executeEmbed(node(Gsm.Phase.Syncing) { n =>
        // a peer whose candidate is our two blocks: the GDD's LoE fragment ends at the second
        val candidate = Fragment(ChainSync.Point.Origin, chain.headers.take(2).map(block))
        n.governor.session(_ => IO.unit, IO.unit).use { peer =>
          for
            _ <- peer.publish(Some(candidate), LatestSlot.At(chain.headers(1).slot), true)
            // the governor has not evaluated yet: the LoE fragment is at the immutable tip
            offered <- offerThree(n, chain)
            unmoved <- n.follower.committedChain.get
            after <- n.governor.run.background.surround(n.tipAt(third.point) <* n.settled)
            limit <- n.governor.limit
            fatals <- n.fatals.get
          yield
            assertKept(offered, chain, "Syncing")
            assertEquals(unmoved.tip, chain.headers(1).point, "held back until the LoE moves")
            assertEquals(limit, Some(candidate), "the LoE fragment ends at our second block")
            assertEquals(after.tip, third.point, "the LoE moved: selection takes the kept block")
            assertEquals(fatals, Vector.empty)
        }
      })
    yield ()
  }

  test("a better chain that arrived meanwhile is selected over the held-back forged block") {
    for
      // a common prefix forged by one pool, then the branch of each of two pools from it
      forks <- ForgedChain.competingForks(5000 until 5010, 5010 until 5060, 1, 2)
      (ours, theirs) = forks
      common = ours.headers.zip(theirs.headers).takeWhile(_ == _).size
      forged = ours.headers(common)
      better = theirs.headers.drop(common)
      delivered <- theirs.headers
        .zip(theirs.raws)
        .drop(common)
        .traverse((h, raw) =>
          BlockPool.Delivered.checked(h, raw).fold(e => IO(fail(e.message)), IO.pure)
        )
      _ <- IO {
        assert(common >= 1, s"the forks share a prefix, got $common blocks")
        assert(better.size >= 2, s"the better branch is longer than the forged block's")
      }
      _ <- TestControl.executeEmbed(node(Gsm.Phase.PreSyncing, volatile = common) { n =>
        for
          prefix <- ours.headers
            .zip(ours.raws)
            .take(common)
            .traverse(n.offer(_, _))
          held <- n.offer(forged, ours.raws(common))
          // the peer's branch, delivered while the LoE holds it back too
          _ <- n.pool.session.use(sender =>
            sender.claim(delivered.map(_.header.hash)) *> sender.fillDelivered(delivered) *>
              n.pool.admissions.reprocess
          )
          unmoved <- n.follower.committedChain.get
          _ <- n.governor.setPhase(Gsm.Phase.CaughtUp)
          after <- n.tipAt(better.last.point) <* n.settled
          extended <- n.extended
          fatals <- n.fatals.get
        yield
          assertEquals(prefix, ours.headers.take(common).map(adopted), "the prefix is adopted")
          assertEquals(held, heldBack(ours.headers(common - 1)), "the forged block is held back")
          assertEquals(unmoved.tip, ours.headers(common - 1).point, "and so is the peer's branch")
          assertEquals(after.tip, better.last.point, "caught up, the longer branch is selected")
          assert(
            !extended.exists(_.hash == forged.hash),
            "the forged block, shorter than the branch, is never adopted"
          )
          assertEquals(fatals, Vector.empty)
      })
    yield ()
  }

  test("a held-back forged block refused when selection reaches it is invalid, and blames nobody") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      third = chain.headers(2)
      _ <- TestControl.executeEmbed(node(Gsm.Phase.PreSyncing, refused = Set(third.hash)) { n =>
        // a peer's session, open while the block is refused: it is not told it sent it
        Ref.of[IO, Vector[ValidationError]](Vector.empty).flatMap { reports =>
          n.pool.session(Some(e => reports.update(_ :+ e))).use { session =>
            for
              offered <- offerThree(n, chain)
              known <- n.pool.invalidBlocks.snapshot
              _ <- n.governor.setPhase(Gsm.Phase.CaughtUp)
              refused <- n.pool.invalidBlocks.awaitChange(known.revision) <* n.settled
              after <- n.follower.committedChain.get
              blamed <- session.blamedYet
              reported <- reports.get
              fatals <- n.fatals.get
            yield
              assertKept(offered, chain, "PreSyncing")
              assertEquals(refused.entries.keySet, Set(third.hash), "recorded invalid")
              assertEquals(after.tip, chain.headers(1).point, "the tip stays")
              assertEquals((blamed, reported), (None, Vector.empty), "no session is blamed")
              assertEquals(fatals, Vector.empty, "the node goes on")
          }
        }
      })
    yield ()
  }

  test("a failure to keep the held-back block leaves the forger's outcome Lost, with its cause") {
    val failure = BlockPoolProblem.MissingDeliveredHeader(ChainSync.Point.Origin)
    val defect = new IllegalStateException("keeping test fault")
    def unkept(preparation: Preparation, expected: Follower.Retention): IO[Unit] =
      for
        chain <- ForgedChain.forge(5000 until 5060)
        _ <- TestControl.executeEmbed(node(Gsm.Phase.PreSyncing, preparation = preparation) { n =>
          for
            adoptions <- chain.headers.zip(chain.raws).take(3).traverse(n.offer(_, _))
            forest <- n.pool.processedSnapshot
            _ <- n.governor.setPhase(Gsm.Phase.CaughtUp) *> n.settled
            after <- n.follower.committedChain.get
            fatals <- n.fatals.get
          yield
            assertEquals(
              adoptions,
              chain.headers.take(2).map(adopted) :+ heldBack(chain.headers(1), expected),
              s"$preparation: the forger is told the block is held back, and why it is not kept"
            )
            assertEquals(forest.get(chain.headers(2).hash), None, "not in the processed forest")
            assertEquals(after.tip, chain.headers(1).point, "so it is not selected later")
            assertEquals(fatals, Vector.empty, "the node goes on")
        })
      yield ()
    unkept(Preparation.Raises(failure), Follower.Retention.Failed(failure)) *>
      unkept(Preparation.Throws(defect), Follower.Retention.Defect(defect))
  }

  test("a storage failure while the held-back block is kept is raised, as for a peer's block") {
    val failure = StorageError.Io("received-log", new java.io.IOException("keeping test fault"))
    for
      chain <- ForgedChain.forge(5000 until 5060)
      _ <- TestControl.executeEmbed(
        node(Gsm.Phase.PreSyncing, preparation = Preparation.Raises(failure)) { n =>
          for
            adoptions <- chain.headers.zip(chain.raws).take(2).traverse(n.offer(_, _))
            third <- Handle
              .allow[NodeError](n.offer(chain.headers(2), chain.raws(2)).map(_.asRight[NodeError]))
              .rescue(error => IO.pure(error.asLeft[Follower.Adoption]))
          yield
            assertEquals(adoptions, chain.headers.take(2).map(adopted))
            assertEquals(third, Left(failure), "the storage failure reaches the forger, typed")
        }
      )
    yield ()
  }
