// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{FollowerEvent, NoVerdictRetry}
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.{Fragment, LatestSlot}
import xyz.kd5ujc.scarab.ledger.{BlockPoolProblem, Header, NodeError, StorageError, ValidationError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Gsm
import xyz.kd5ujc.scarab.node.ForgedRetention.{Node, Preparation, adopted, lost, node}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ChainSync

/** A block this node forged that the Limit on Eagerness holds back is kept, and the selection that
  * a move of the LoE or the node's catching up triggers considers it again, without the block being
  * offered a second time. The reference's ChainDB puts the forged block in its VolatileDB before
  * chain selection (ouroboros-consensus 82ecba329 `Storage/ChainDB/Impl/ChainSel.hs:428-450`), and
  * `ChainSelReprocessLoEBlocks` (ChainSel.hs:358-413), which the GDD governor triggers when the LoE
  * tip changes in Syncing and when the node is caught up (`Genesis/Governor.hs:172-184`), selects
  * among the successors of the current chain's blocks that the VolatileDB holds, are not on that
  * chain and are not known invalid; the same at 808e40054 (ChainSel.hs:363-418, 433-455). The block
  * is adopted then if it is still valid and preferred; a better chain that arrived meanwhile wins
  * over it, and a refusal of it blames nobody (the forging loop's `noPunishment`,
  * NodeKernel.hs:747). A failure to keep the block leaves the adoption as it is, but a storage
  * failure stops the node, as it does when a peer's block is written to the received log.
  *
  * The node is a follower in Genesis mode with its selection worker and GDD governor
  * ([[ForgedRetention]]); the blocks are a chain forged with valid headers ([[ForgedChain]]). With
  * `k` = 2 and the LoE at the immutable tip (Origin), the first two blocks fill the volatile chain
  * and a third is past the LoE plus `k`.
  */
class HeldBackForgedBlockSuite extends FailsOnUncaught:

  private def heldBack(tip: Header): Follower.Submission =
    lost(Follower.AdoptionLoss.LimitOnEagerness(tip.point))

  private def block(header: Header): ChainSync.Point.Block = header.point match
    case point: ChainSync.Point.Block => point
    case ChainSync.Point.Origin       => fail(s"a forged header at Origin: $header")

  /** What a node makes of the first three blocks of `chain` offered as forged ones, and what is
    * kept of the third.
    */
  private final case class Offered(
      submissions: Vector[Follower.Submission],
      kept: Option[BlockPool.Source],
      stored: Vector[Bytes]
  )

  private def offerThree(n: Node, chain: ForgedChain)(using Handle[IO, NodeError]): IO[Offered] =
    for
      submissions <- chain.headers.zip(chain.raws).take(3).traverse(n.offer(_, _))
      forest <- n.pool.processedSnapshot
      stored <- n.stored.get
    yield Offered(submissions, forest.get(chain.headers(2).hash).map(_.origin), stored)

  private def assertKept(offered: Offered, chain: ForgedChain, name: String): Unit =
    val headers = chain.headers
    assertEquals(
      offered.submissions,
      headers.take(2).map(adopted) :+ heldBack(headers(1)),
      s"$name: the first two blocks fill the volatile chain; the third is held back by the LoE"
    )
    assertEquals(
      offered.kept,
      Some(BlockPool.Source.Forged),
      s"$name: the held-back block is in the processed forest, as a block this node forged"
    )
    assertEquals(
      offered.stored,
      headers.take(3).map(_.hash),
      s"$name: every forged block was written to the received log"
    )

  test("PreSyncing, then caught up: the held-back forged block is adopted, not offered again") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      third = chain.headers(2)
      _ <- TestControl.executeEmbed(node(Some(Gsm.Phase.PreSyncing)) { n =>
        for
          offered <- offerThree(n, chain)
          before <- n.extended
          _ <- n.phase(Gsm.Phase.CaughtUp)
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
      _ <- TestControl.executeEmbed(node(Some(Gsm.Phase.Syncing)) { n =>
        // a peer whose candidate is our two blocks: the GDD's LoE fragment ends at the second
        val candidate = Fragment(ChainSync.Point.Origin, chain.headers.take(2).map(block))
        n.governor.fold(IO(fail("no governor"))) { governor =>
          governor.session(_ => IO.unit, IO.unit).use { peer =>
            for
              _ <- peer.publish(Some(candidate), LatestSlot.At(chain.headers(1).slot), true)
              // the governor has not evaluated yet: the LoE fragment is at the immutable tip
              offered <- offerThree(n, chain)
              unmoved <- n.follower.committedChain.get
              after <- governor.run.background.surround(n.tipAt(third.point) <* n.settled)
              limit <- governor.limit
              fatals <- n.fatals.get
            yield
              assertKept(offered, chain, "Syncing")
              assertEquals(unmoved.tip, chain.headers(1).point, "held back until the LoE moves")
              assertEquals(limit, Some(candidate), "the LoE fragment ends at our second block")
              assertEquals(after.tip, third.point, "the LoE moved: selection takes the kept block")
              assertEquals(fatals, Vector.empty)
          }
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
      delivered <- ForgedRetention.delivered(theirs, common, theirs.headers.size)
      _ <- IO {
        assert(common >= 1, s"the forks share a prefix, got $common blocks")
        assert(better.size >= 2, s"the better branch is longer than the forged block's")
      }
      _ <- TestControl.executeEmbed(node(Some(Gsm.Phase.PreSyncing), k = common) { n =>
        for
          prefix <- ours.headers
            .zip(ours.raws)
            .take(common)
            .traverse(n.offer(_, _))
          held <- n.offer(forged, ours.raws(common))
          // the peer's branch, delivered while the LoE holds it back too
          _ <- n.deliver(delivered)
          unmoved <- n.follower.committedChain.get
          _ <- n.phase(Gsm.Phase.CaughtUp)
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
      _ <- TestControl.executeEmbed(node(Some(Gsm.Phase.PreSyncing), refused = Set(third.hash)) {
        n =>
          // a peer's session, open while the block is refused: it is not told it sent it
          Ref.of[IO, Vector[ValidationError]](Vector.empty).flatMap { reports =>
            n.pool.session(Some(e => reports.update(_ :+ e))).use { session =>
              for
                offered <- offerThree(n, chain)
                known <- n.pool.invalidBlocks.snapshot
                _ <- n.phase(Gsm.Phase.CaughtUp)
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

  test("a held-back forged block with no verdict when selection reaches it is not judged again") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      third = chain.headers(2)
      seen <- Ref.of[IO, Option[(Vector[Follower.Submission], Node)]](None)
      // the third block's first judgement, at the reselection once caught up, has no verdict for
      // a cause that may pass; later ones are valid
      control <- TestControl.execute(
        node(Some(Gsm.Phase.PreSyncing), withheldOnce = Set(third.hash)) { n =>
          for
            submissions <- chain.headers.zip(chain.raws).take(3).traverse(n.offer(_, _))
            _ <- n.phase(Gsm.Phase.CaughtUp)
            _ <- n.events.discrete
              .find(_.exists {
                case FollowerEvent.NoVerdict(point, _, _, _) => point == third.point
                case _                                       => false
              })
              .compile
              .drain
            _ <- seen.set(Some(submissions -> n))
            _ <- IO.never[Unit]
          yield ()
        }
      )
      _ <- control.tick
      reached <- seen.get
      // a peer's block withheld so would be judged again after 1 s, then 2, 4, 8, 16 s
      _ <- control.advanceAndTick(50.seconds)
      (submissions, n) = reached.getOrElse(fail("the reselection did not reach the block"))
      withheld <- n.pool.noVerdictBlocks.lookup(third.hash)
      after <- n.follower.committedChain.get
      events <- n.events.get
      running <- control.results
    yield
      assertEquals(submissions.last, heldBack(chain.headers(1)), "held back and kept")
      assertEquals(after.tip, chain.headers(1).point, "never adopted later without being delivered")
      assert(!Node.extended(events).exists(_.hash == third.hash), "nor reported adopted")
      assertEquals(
        withheld.map(_.schedule),
        Some(NoVerdictBlocks.Schedule.Never),
        "withheld for good, as our own block without a verdict"
      )
      assertEquals(
        events.collect {
          case FollowerEvent.NoVerdict(point, _, _, retry) if point == third.point => retry
        },
        Vector(NoVerdictRetry.Never)
      )
      assertEquals(running, None, "the node goes on")
  }

  test("a held-back forged block withheld for good, offered again, is neither judged nor adopted") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      third = chain.headers(2)
      _ <- TestControl.executeEmbed(
        node(Some(Gsm.Phase.PreSyncing), withheldOnce = Set(third.hash)) { n =>
          for
            _ <- chain.headers.zip(chain.raws).take(3).traverse(n.offer(_, _))
            _ <- n.phase(Gsm.Phase.CaughtUp)
            _ <- n.events.discrete
              .find(_.exists {
                case FollowerEvent.NoVerdict(point, _, _, _) => point == third.point
                case _                                       => false
              })
              .compile
              .drain
            // its parent is our tip, so the direct path would take it, and its fault has passed
            again <- n.offer(third, chain.raws(2))
            after <- n.follower.committedChain.get
          yield
            assert(
              again.adoption.isInstanceOf[Follower.Adoption.NoVerdict],
              s"stored already and withheld: not judged again, ${again.adoption}"
            )
            assertEquals(again.retention, Follower.Retention.Present)
            assertEquals(after.tip, chain.headers(1).point, "and not adopted")
        }
      )
    yield ()
  }

  test("a failure to keep forged blocks leaves what selection made of them, with the cause") {
    val failure = BlockPoolProblem.MissingDeliveredHeader(ChainSync.Point.Origin)
    val defect = new IllegalStateException("keeping test fault")
    def unkept(preparation: Preparation, expected: Follower.Retention): IO[Unit] =
      for
        chain <- ForgedChain.forge(5000 until 5060)
        _ <- TestControl.executeEmbed(
          node(Some(Gsm.Phase.PreSyncing), preparation = preparation) { n =>
            for
              submissions <- chain.headers.zip(chain.raws).take(3).traverse(n.offer(_, _))
              forest <- n.pool.processedSnapshot
              _ <- n.phase(Gsm.Phase.CaughtUp) *> n.settled
              after <- n.follower.committedChain.get
              fatals <- n.fatals.get
            yield
              assertEquals(
                submissions,
                (chain.headers.take(2).map(adopted) :+ heldBack(chain.headers(1)))
                  .map(_.copy(retention = expected)),
                s"$preparation: adopted and held back as before, and why none is kept"
              )
              assertEquals(forest, Map.empty, "none is in the processed forest")
              assertEquals(after.tip, chain.headers(1).point, "the held-back one is not selected")
              assertEquals(fatals, Vector.empty, "the node goes on")
          }
        )
      yield ()
    unkept(Preparation.Raises(failure), Follower.Retention.Failed(failure)) *>
      unkept(Preparation.Throws(defect), Follower.Retention.Defect(defect))
  }

  test("a storage failure while a forged block is kept is raised before its chain is published") {
    val failure = StorageError.Io("received-log", new java.io.IOException("keeping test fault"))
    for
      chain <- ForgedChain.forge(5000 until 5060)
      _ <- TestControl.executeEmbed(
        node(Some(Gsm.Phase.PreSyncing), preparation = Preparation.Raises(failure)) { n =>
          for
            first <- Handle
              .allow[NodeError](
                n.offer(chain.headers(0), chain.raws(0)).map(_.asRight[NodeError])
              )
              .rescue(error => IO.pure(error.asLeft[Follower.Submission]))
            after <- n.follower.committedChain.get
          yield
            assertEquals(first, Left(failure), "the storage failure reaches the forger, typed")
            assertEquals(after.tip, ChainSync.Point.Origin, "and the block was not adopted")
        }
      )
    yield ()
  }

  test("a forged block whose predecessors lead back to it is refused, typed, in its submission") {
    for
      chain <- ForgedChain.forge(5000 until 5060)
      forged = chain.headers(1)
      // a recovered block of the hash the forged one names as its predecessor, after the forged one
      after = Header
        .UnsizedByron(
          chain.headers(0).hash,
          chain.headers(0).hash,
          false,
          0,
          1,
          21600,
          1,
          Some(forged.hash)
        )
        .withSizeHint(FetchTesting.sizeHint(1))
      recovered <- IO.fromEither(
        BlockPool.DeliveredHeader
          .checkedAtOpen(after, xyz.kd5ujc.scarab.consensus.LeiosBlockInfo.Absent)
          .leftMap(e => new AssertionError(e.toString))
      )
      _ <- TestControl.executeEmbed(
        node(Some(Gsm.Phase.PreSyncing)) { n =>
          for
            seeded <- n.pool.seedRecoveredMetadata(Vector(recovered))
            submitted <- Handle
              .allow[NodeError](n.offer(forged, chain.raws(1)).map(_.asRight[NodeError]))
              .rescue(error => IO.pure(error.asLeft[Follower.Submission]))
            forest <- n.pool.processedSnapshot
            tip <- n.follower.committedChain.get
          yield
            assertEquals(seeded, Right(()))
            assertEquals(
              submitted,
              Left(BlockPoolProblem.AvailableCycle(forged.hash)),
              "the refusal reaches the forger, typed, as the selection for the block raised it"
            )
            assertEquals(forest.keySet, Set(after.hash), "the forged block is not in the forest")
            assertEquals(tip.tip, ChainSync.Point.Origin, "and it was not adopted")
        }
      )
    yield ()
  }
