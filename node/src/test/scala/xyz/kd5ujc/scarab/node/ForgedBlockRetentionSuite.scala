// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.{FollowerEvent, NoVerdictRetry, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{Header, StorageError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.ForgedRetention.{adopted, delivered, lost, node, resumed}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.storage.{ChainStorage, ReceivedBlocks}

/** A block this node forged is kept whatever chain selection makes of it, as the reference's
  * ChainDB puts a block it is given in its VolatileDB before chain selection runs for it, unless
  * the block is there already, known invalid or no younger than the immutable tip
  * (ouroboros-consensus 82ecba329 `Storage/ChainDB/Impl/ChainSel.hs:428-450`, `olderThanImmTip`
  * 574-581; 433-455 and 579-586 at 808e40054). A later selection takes it when its branch is
  * preferred: a block delivered on it, or its missing predecessor delivered after it, feeds the
  * selection a delivery triggers, and an adopted block that a rollback left off our chain wins
  * again without being delivered, after a restart too.
  *
  * The node is a follower in Praos mode with its selection worker ([[ForgedRetention]]), keeping 3
  * volatile blocks; `left` and `right` are two chains forged from one genesis by one pool
  * ([[ForgedChain.sameIssuerForks]]), whose blocks of one height are tied on length and far apart
  * in slots, so that the restricted tie-break keeps the block our chain has.
  */
class ForgedBlockRetentionSuite extends FailsOnUncaught:
  private val k = 3

  /** Forged anew for each case: the pool's KES key evolves as it signs. */
  private def forks = ForgedChain.sameIssuerForks(5000 until 5060, 5060 until 5120)

  private def withLog[A](dir: Path, cacheBytes: Long = ReceivedBlocks.DefaultCacheBytes)(
      use: ReceivedBlocks[IO] => IO[A]
  ): IO[A] =
    Handle
      .allow[StorageError](
        ReceivedBlocks
          .open[IO](dir, cacheBytes = cacheBytes)(
            ChainStorage.predecessorOf(ForgedChain.geometry.byron.epochSlots)
          )
          .use(use)
      )
      .rescue(error => IO(fail(s"the received log failed: ${error.message}")))

  private def hashes(headers: Vector[Header]): Vector[Bytes] = headers.map(_.hash)

  test(
    "a forged block that lost to the tip is kept, and selected once a block on it is delivered"
  ) {
    for
      (left, right) <- forks
      l = left.headers
      r = right.headers
      onR <- delivered(right, 1, 2)
      _ <- TestControl.executeEmbed(node(None, k = k) { n =>
        for
          first <- n.offer(l(0), left.raws(0))
          rival <- n.offer(r(0), right.raws(0))
          _ <- n.deliver(onR)
          after <- n.tipAt(r(1).point) <* n.settled
          prepared <- n.prepared.get
          fatals <- n.fatals.get
        yield
          assertEquals(first, adopted(l(0)))
          assertEquals(
            rival,
            lost(Follower.AdoptionLoss.SelectionPrefersTip(l(0).point)),
            "the rival loses the tie to our tip, and is kept"
          )
          assertEquals(after.blocks.map(_._1.hash), hashes(r.take(2)), "its branch is selected")
          assertEquals(prepared, hashes(r.slice(1, 2)), "only the block on it was delivered")
          assertEquals(fatals, Vector.empty)
      })
    yield ()
  }

  test("a forged block whose predecessor left our chain is kept, and selected once it arrives") {
    for
      (left, right) <- forks
      l = left.headers
      r = right.headers
      peerTip <- delivered(right, 0, 1)
      predecessor <- delivered(left, 0, 1)
      _ <- TestControl.executeEmbed(node(None, k = k) { n =>
        for
          _ <- n.deliver(peerTip)
          detached <- n.offer(l(1), left.raws(1))
          // the missing predecessor arrives after its descendant
          _ <- n.deliver(predecessor)
          after <- n.tipAt(l(1).point) <* n.settled
          prepared <- n.prepared.get
          fatals <- n.fatals.get
        yield
          assertEquals(
            detached,
            lost(Follower.AdoptionLoss.DetachedPredecessor(r(0).point, Some(l(0).hash))),
            "the forged block's predecessor is not on our chain; it is kept"
          )
          assertEquals(after.blocks.map(_._1.hash), hashes(l.take(2)), "its branch is selected")
          assertEquals(prepared, Vector(r(0).hash, l(0).hash), "it was not delivered")
          assertEquals(fatals, Vector.empty)
      })
    yield ()
  }

  test("a forged block on a kept block off our chain is adopted at its submission when longer") {
    for
      (left, right) <- forks
      l = left.headers
      r = right.headers
      peerTip <- delivered(right, 0, 1)
      _ <- TestControl.executeEmbed(node(None, k = k) { n =>
        for
          _ <- n.deliver(peerTip)
          rival <- n.offer(l(0), left.raws(0))
          // forged on the kept rival, which our chain does not hold: no delivery and no
          // reselection follow, and the submission's own selection decides
          child <- n.offer(l(1), left.raws(1))
          after <- n.follower.committedChain.get
          extended <- n.extended
        yield
          assertEquals(rival, lost(Follower.AdoptionLoss.SelectionPrefersTip(r(0).point)))
          assertEquals(child, adopted(l(1)), "the branch through the kept block is longer")
          assertEquals(after.blocks.map(_._1.hash), hashes(l.take(2)), "and is our chain at once")
          assertEquals(extended.map(_.hash), Vector(r(0).hash, l(0).hash, l(1).hash))
      })
    yield ()
  }

  test("a forged block on an adopted block a rollback left off our chain is adopted when longer") {
    for
      (left, right) <- forks
      l = left.headers
      r = right.headers
      theirs <- delivered(right, 0, 2)
      _ <- TestControl.executeEmbed(node(None, k = k) { n =>
        for
          first <- n.offer(l(0), left.raws(0))
          _ <- n.deliver(theirs)
          // forged on our former tip: as long as the peer's chain, so not preferred
          tied <- n.offer(l(1), left.raws(1))
          // and one more on it: longer, so selected at its submission
          longer <- n.offer(l(2), left.raws(2))
          after <- n.follower.committedChain.get
        yield
          assertEquals(first, adopted(l(0)))
          assertEquals(
            tied,
            lost(Follower.AdoptionLoss.DetachedPredecessor(r(1).point, Some(l(0).hash))),
            "its predecessor is off our chain, and its branch is no longer than ours"
          )
          assertEquals(longer, adopted(l(2)), "its branch, through the kept blocks, is longer")
          assertEquals(after.blocks.map(_._1.hash), hashes(l.take(3)), "and is our chain at once")
      })
    yield ()
  }

  test("a block forged on a kept block, with no verdict at its submission, is never judged again") {
    for
      (left, right) <- forks
      l = left.headers
      r = right.headers
      peerTip <- delivered(right, 0, 1)
      seen <- Ref.of[IO, Option[(Follower.Submission, ForgedRetention.Node)]](None)
      // the child's first judgement has no verdict, for a cause that may pass; later ones are valid
      control <- TestControl.execute(node(None, k = k, withheldOnce = Set(l(1).hash)) { n =>
        n.deliver(peerTip) *> n.offer(l(0), left.raws(0)) *>
          n.offer(l(1), left.raws(1)).flatMap(child => seen.set(Some(child -> n))) *>
          IO.never[Unit]
      })
      _ <- control.tick
      submitted <- seen.get
      // a peer's block withheld so would be judged again after 1 s, then 2, 4, 8, 16 s
      _ <- control.advanceAndTick(50.seconds)
      (child, n) = submitted.getOrElse(fail("the submission did not end"))
      withheld <- n.pool.noVerdictBlocks.lookup(l(1).hash)
      after <- n.follower.committedChain.get
      events <- n.events.get
      running <- control.results
    yield
      assert(
        child.adoption.isInstanceOf[Follower.Adoption.NoVerdict],
        s"the submission's selection had no verdict on it: ${child.adoption}"
      )
      assertEquals(child.retention, Follower.Retention.Kept)
      assertEquals(after.tip, r(0).point, "never adopted later without being delivered")
      assert(
        !ForgedRetention.Node.extended(events).exists(_.hash == l(1).hash),
        "nor reported adopted"
      )
      assertEquals(
        withheld.map(_.schedule),
        Some(NoVerdictBlocks.Schedule.Never),
        "withheld, and not judged again, as our own block on the direct path"
      )
      assertEquals(
        events.collect {
          case FollowerEvent.NoVerdict(point, _, _, retry) if point == l(1).point =>
            retry
        },
        Vector(NoVerdictRetry.Never)
      )
      assertEquals(running, None, "the node goes on")
  }

  test("a forged block withheld for good is not released when a related peer block comes due") {
    for
      // a prefix forged by one pool and a branch of each of two pools on it; our forged block is
      // the prefix's tip, a peer's child of it the left branch's first block, and its siblings the
      // right branch's; the rival is a chain from Origin one block longer than the prefix
      branches <- ForgedChain.competingForks(5000 until 5010, 5010 until 5060, 1, 2)
      (ours, theirs) = branches
      common = ours.headers.zip(theirs.headers).takeWhile(_ == _).size
      prefix = ours.headers.take(common)
      forged = prefix.last
      child <- delivered(ours, common, common + 1)
      siblings <- delivered(theirs, common, common + 2)
      (_, other) <- forks
      rival <- delivered(other, 0, common + 1)
      _ <- IO {
        assert(common >= 1, s"the forks share a prefix, got $common blocks")
        assertEquals(siblings.size, 2, "two blocks on the right branch")
      }
      seen <- Ref.of[IO, Option[ForgedRetention.Node]](None)
      control <- TestControl.execute(
        node(None, k = common + 3, withheldOnce = child.map(_.header.hash).toSet) { n =>
          for
            _ <- prefix.zip(ours.raws).traverse(n.offer(_, _))
            // the peer's child has no verdict at its first judgement: withheld, due in 1 s
            _ <- n.deliver(child)
            // the rival takes the chain from our block
            _ <- n.deliver(rival)
            // the siblings' branch through our block is the longer; our block has no verdict now
            _ <- n.faults.update(_ + forged.hash) *> n.deliver(siblings)
            // and its fault passes
            _ <- n.faults.update(_ - forged.hash)
            _ <- seen.set(Some(n))
            _ <- IO.never[Unit]
          yield ()
        }
      )
      _ <- control.tick
      reached <- seen.get
      // the child is due after 1 s, then 2, 4, 8, 16 s
      _ <- control.advanceAndTick(50.seconds)
      n = reached.getOrElse(fail("the case did not reach its end"))
      withheld <- n.pool.noVerdictBlocks.lookup(forged.hash)
      after <- n.follower.committedChain.get
      events <- n.events.get
      running <- control.results
    yield
      assertEquals(
        after.tip,
        rival.last.header.point,
        "neither our block nor its branch is adopted"
      )
      assertEquals(
        ForgedRetention.Node.extended(events).count(_.hash == forged.hash),
        1,
        "our block was adopted at its submission only"
      )
      assertEquals(
        withheld.map(_.schedule),
        Some(NoVerdictBlocks.Schedule.Never),
        "withheld for good, and not released with the child"
      )
      assertEquals(running, None, "the node goes on")
  }

  /** Our forged `left(0)` is adopted, a peer's longer `right` takes the chain from it, and a peer
    * then delivers `left(1)` and `left(2)`: the branch through the kept `left(0)` is longer. With a
    * `log`, the node has a received log whose body cache holds one body at most, so each body put
    * there pushes the one before it out.
    */
  private def rolledBackForged(log: Boolean): IO[Unit] =
    def run(received: Option[ReceivedBlocks[IO]], left: ForgedChain, right: ForgedChain) =
      val l = left.headers
      val r = right.headers
      for
        theirs <- delivered(right, 0, 2)
        onOurs <- delivered(left, 1, 3)
        _ <- node(None, k = k, received = received) { n =>
          for
            first <- n.offer(l(0), left.raws(0))
            _ <- n.deliver(theirs)
            switched <- n.follower.committedChain.get
            _ <- n.deliver(onOurs)
            after <- n.tipAt(l(2).point) <* n.settled
            prepared <- n.prepared.get
            fatals <- n.fatals.get
          yield
            assertEquals(first, adopted(l(0)))
            assertEquals(switched.blocks.map(_._1.hash), hashes(r.take(2)), "rolled back")
            assertEquals(after.blocks.map(_._1.hash), hashes(l.take(3)), "and selected again")
            assertEquals(prepared, hashes(r.take(2) ++ l.slice(1, 3)), "without being delivered")
            assertEquals(fatals, Vector.empty)
        }
      yield ()
    forks.flatMap { (left, right) =>
      val oneBody = (left.raws ++ right.raws).map(_.size.toLong).max
      TestControl.executeEmbed(
        if !log then run(None, left, right)
        else
          Files
            .forAsync[IO]
            .tempDirectory
            .use(dir =>
              withLog(dir / "received", oneBody) { received =>
                for
                  _ <- run(Some(received), left, right)
                  residency <- received.residency
                yield
                  assert(residency.evictions > 0, s"bodies left the cache: $residency")
                  assert(residency.reads > 0, s"bodies were read back from the log: $residency")
              }
            )
      )
    }

  test("an adopted forged block a rollback left off our chain wins again without being delivered") {
    rolledBackForged(log = false)
  }

  test("so does a fetched block (unchanged)") {
    for
      (left, right) <- forks
      l = left.headers
      r = right.headers
      first <- delivered(right, 0, 1)
      ours <- delivered(left, 0, 2)
      onFirst <- delivered(right, 1, 3)
      _ <- TestControl.executeEmbed(node(None, k = k) { n =>
        for
          _ <- n.deliver(first) *> n.deliver(ours) *> n.deliver(onFirst)
          after <- n.tipAt(r(2).point) <* n.settled
          prepared <- n.prepared.get
        yield
          assertEquals(after.blocks.map(_._1.hash), hashes(r.take(3)))
          assertEquals(prepared, hashes(r.take(1) ++ l.take(2) ++ r.slice(1, 3)))
      })
    yield ()
  }

  test("with a received log whose cache holds one body, the kept block is read back from the log") {
    rolledBackForged(log = true)
  }

  test(
    "after the received log is closed and opened again, startup selection reads the kept block"
  ) {
    for
      (left, right) <- forks
      l = left.headers
      r = right.headers
      theirs <- delivered(right, 0, 2)
      onOurs <- delivered(left, 1, 3)
      _ <- TestControl.executeEmbed(Files.forAsync[IO].tempDirectory.use { dir =>
        for
          before <- withLog(dir / "received") { log =>
            node(None, k = k, received = Some(log)) { n =>
              n.offer(l(0), left.raws(0)) *> n.deliver(theirs) *> n.follower.committedChain.get
            }
          }
          // a restart: the log is opened again, `Main.seedReceived` seeds the pool from it, the
          // chain resumes as the selected log left it, and startup selection runs
          _ <- withLog(dir / "received") { log =>
            log.refs.flatMap { recovered =>
              node(None, k = k, received = Some(log), initial = Some(resumed(k, r.take(2)))) { n =>
                for
                  started <- n.follower.committedChain.get
                  _ <- n.deliver(onOurs)
                  selected <- n.tipAt(l(2).point) <* n.settled
                  prepared <- n.prepared.get
                yield
                  assert(recovered.exists(_.hash == l(0).hash), "the log holds the forged block")
                  assertEquals(started.blocks.map(_._1.hash), hashes(r.take(2)), "startup keeps")
                  assertEquals(selected.blocks.map(_._1.hash), hashes(l.take(3)), "it wins again")
                  assertEquals(prepared, hashes(l.slice(1, 3)), "without being delivered")
              }
            }
          }
        yield assertEquals(before.blocks.map(_._1.hash), hashes(r.take(2)), "rolled back before")
      })
    yield ()
  }

  test("a forged block no younger than the immutable tip is not kept") {
    for
      (left, right) <- forks
      l = left.headers
      r = right.headers
      _ <- TestControl.executeEmbed(node(None, k = 2) { n =>
        for
          ours <- l.zip(left.raws).take(3).traverse(n.offer(_, _))
          chain <- n.follower.committedChain.get
          old <- n.offer(r(0), right.raws(0))
          // our own first block again, now the immutable tip, which the forest still holds
          immutable <- n.offer(l(0), left.raws(0))
          forest <- n.pool.processedSnapshot
          stored <- n.stored.get
        yield
          val tooOld = Follower.Submission(
            Follower.Adoption.Lost(Follower.AdoptionLoss.TooOld(l(2).point)),
            Follower.Retention.TooOld
          )
          assertEquals(ours, l.take(3).map(adopted))
          assertEquals(chain.anchor.point, l(0).point, "the first block is immutable")
          assertEquals(old, tooOld, "of its height: not kept, not adopted")
          assertEquals(
            immutable,
            tooOld,
            "too old is checked first, as the reference does, though the forest has the block"
          )
          assert(forest.contains(l(0).hash), "the forest keeps the immutable tip's block")
          assertEquals(forest.get(r(0).hash), None)
          assertEquals(stored, hashes(l.take(3)), "nor written to the received log")
      })
    yield ()
  }

  test(
    "an epoch boundary block of the immutable tip's number is younger than it; nothing else is"
  ) {
    def byron(id: Int, parent: Option[Bytes], number: Int, boundary: Boolean): Header.Byron =
      Header
        .UnsizedByron(
          Bytes(Vector(id.toByte)),
          Bytes(Vector(id.toByte)),
          boundary,
          0,
          id,
          100000,
          number,
          parent
        )
        .withSizeHint(FetchTesting.sizeHint(1))
    val b0 = byron(1, None, 0, boundary = false)
    val b1 = byron(2, Some(b0.hash), 1, boundary = false)
    val chain = Vector(b0, b1).foldLeft(VolatileChain.genesis(1, BigInt(-1)))((c, h) =>
      c.extend(h, h.blockNo).fold(e => fail(e.message), _._1)
    )
    assertEquals(chain.anchor.point, b0.point, "b0 is immutable")
    val young = Follower.youngerThanImmutable(chain, _: Header)
    assert(young(byron(3, Some(b0.hash), 0, boundary = true)), "a boundary block of b0's number")
    assert(!young(byron(4, None, 0, boundary = false)), "a main block of b0's number")
    assert(young(byron(5, Some(b0.hash), 1, boundary = false)), "a higher number")
    assert(
      Follower.youngerThanImmutable(VolatileChain.genesis(1, BigInt(-1)), b0),
      "anything, while the immutable tip is Origin"
    )
  }

  test("a forged block offered again, or delivered by a peer, is kept once") {
    for
      (left, _) <- forks
      l = left.headers
      again <- delivered(left, 0, 1)
      _ <- TestControl.executeEmbed(Files.forAsync[IO].tempDirectory.use { dir =>
        withLog(dir / "received") { log =>
          node(None, k = k, received = Some(log)) { n =>
            for
              first <- n.offer(l(0), left.raws(0))
              second <- n.offer(l(0), left.raws(0))
              _ <- n.deliver(again)
              forest <- n.pool.processedSnapshot
              prepared <- n.prepared.get
              stored <- n.stored.get
              refs <- log.refs
            yield
              assertEquals(first, adopted(l(0)))
              assertEquals(
                second,
                adopted(l(0)).copy(retention = Follower.Retention.Present),
                "the same block again: stored already, not selected again, and our tip, which " +
                  "the reference's forging loop reads as adopted"
              )
              assertEquals(stored, Vector(l(0).hash), "written to the received log once")
              assertEquals(
                forest.get(l(0).hash).map(_.origin),
                Some(BlockPool.Source.Forged),
                "the forest has it as a forged block"
              )
              assertEquals(prepared, Vector.empty, "a peer's delivery of it is not processed")
              assertEquals(refs.count(_.hash == l(0).hash), 1, "the log holds it once")
          }
        }
      })
    yield ()
  }

  test("a forged block the rules refuse is kept invalid; offered again it is not stored again") {
    for
      (left, _) <- forks
      l = left.headers
      _ <- TestControl.executeEmbed(node(None, k = k, refused = Set(l(1).hash)) { n =>
        for
          first <- n.offer(l(0), left.raws(0))
          refused <- n.offer(l(1), left.raws(1))
          known <- n.pool.invalidBlocks.snapshot
          again <- n.offer(l(1), left.raws(1))
          forest <- n.pool.processedSnapshot
          stored <- n.stored.get
          _ <- n.settled
          after <- n.follower.committedChain.get
          unchanged <- n.pool.invalidBlocks.snapshot
          fatals <- n.fatals.get
        yield
          assertEquals(first, adopted(l(0)))
          assert(
            refused.adoption.isInstanceOf[Follower.Adoption.Invalid],
            s"refused: ${refused.adoption}"
          )
          assertEquals(refused.retention, Follower.Retention.Kept, "and kept")
          assertEquals(known.entries.keySet, Set(l(1).hash), "recorded invalid")
          assertEquals(
            forest.get(l(1).hash).map(_.origin),
            Some(BlockPool.Source.Forged),
            "in the forest"
          )
          assert(
            again.adoption.isInstanceOf[Follower.Adoption.Invalid],
            s"again: ${again.adoption}"
          )
          assertEquals(again.retention, Follower.Retention.Present, "stored already")
          assertEquals(stored, hashes(l.take(2)), "written to the received log once")
          assertEquals(after.tip, l(0).point, "never selected")
          assertEquals(unchanged.revision, known.revision, "nor judged again")
          assertEquals(fatals, Vector.empty)
      })
    yield ()
  }
