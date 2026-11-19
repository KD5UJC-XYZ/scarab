// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.{Header, StorageError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.ForgedRetention.{adopted, delivered, lost, node, resumed}
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.storage.ReceivedBlocks

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
      .allow[StorageError](ReceivedBlocks.open[IO](dir, cacheBytes = cacheBytes).use(use))
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
          forest <- n.pool.processedSnapshot
          stored <- n.stored.get
        yield
          assertEquals(ours, l.take(3).map(adopted))
          assertEquals(chain.anchor.point, l(0).point, "the first block is immutable")
          assertEquals(old.retention, Follower.Retention.TooOld, "of its height: not kept")
          assert(
            old.adoption.isInstanceOf[Follower.Adoption.Lost],
            s"and not adopted: ${old.adoption}"
          )
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
              refs <- log.refs
            yield
              assertEquals(first, adopted(l(0)))
              assertEquals(
                second,
                lost(Follower.AdoptionLoss.SelectionPrefersTip(l(0).point)),
                "the same block again: our tip already, and kept as it was"
              )
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

  test("a forged block the rules refuse is kept invalid; offered again it is known invalid") {
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
          assertEquals(again.retention, Follower.Retention.KnownInvalid, "offered again")
          assertEquals(stored, hashes(l.take(2)), "written to the received log once")
          assertEquals(after.tip, l(0).point, "never selected")
          assertEquals(unchanged.revision, known.revision, "nor judged again")
          assertEquals(fatals, Vector.empty)
      })
    yield ()
  }
