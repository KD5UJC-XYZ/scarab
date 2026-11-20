// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.network.ChainSyncServer.Update
import xyz.kd5ujc.scarab.network.{BlockFetchServer, Settled}
import xyz.kd5ujc.scarab.protocol.NetworkError
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef}
import xyz.kd5ujc.scarab.storage.{CountedReads, FileImmutableStore, ImmutableStore, StorageTestKit}
import munit.ScalaCheckSuite
import cats.mtl.Handle
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.node.Fatal
import org.scalacheck.Gen
import org.scalacheck.Prop.*

import StorageTestKit.*

object RelayKit:
  def ref(n: Int, salt: Int = 0): BlockRef =
    BlockRef(BigInt(n) * 3, hash(n + salt * 100000), BigInt(n))

  /** The "raw block" is the block number and the salt; its header is the same bytes. */
  def raw(n: Int, salt: Int = 0): Bytes = Bytes(Vector(n.toByte, salt.toByte))

  def served(n: Int, salt: Int = 0): Served =
    Served(ref(n, salt), NtNHeader(Era.Conway, raw(n, salt), None), raw(n, salt))

  val headerOf: ChainServer.HeaderOf = bytes => Right(NtNHeader(Era.Conway, bytes, None))

/** The served chain as a model: a reader that applies every instruction it is given reconstructs
  * exactly the chain the server holds, however the chain grew and forked in between.
  */
class ServedChainSuite extends ScalaCheckSuite:
  import RelayKit.*

  enum Op:
    case Extend
    case RollBack(depth: Int)
    case Read(follower: Int, steps: Int)

  private val ops: Gen[List[Op]] = Gen.listOf(
    Gen.frequency(
      4 -> Gen.const(Op.Extend),
      1 -> Gen.choose(0, 4).map(Op.RollBack(_)),
      3 -> Gen.zip(Gen.choose(0, 2), Gen.choose(1, 4)).map((f, s) => Op.Read(f, s))
    )
  )

  /** A follower's local chain, as hashes. */
  private type Local = Vector[Bytes]

  private def apply(local: Local, chain: ServedChain, id: Long, steps: Int): (ServedChain, Local) =
    (0 until steps).foldLeft((chain, local)) { case ((c, l), _) =>
      c.followers.get(id).map(c.read) match
        case Some(Read.Forward(b)) =>
          (c.move(id, Cursor.At(b.ref.point)), l :+ b.ref.hash)
        case Some(Read.Backward(p)) =>
          val kept = p match
            case Point.Origin         => 0
            case Point.Block(_, hash) => l.indexOf(hash) + 1
          (c.move(id, Cursor.At(p)), l.take(kept))
        case _ => (c, l)
    }

  property("every follower reconstructs the served chain, and rollbacks are to chain points") {
    forAll(ops) { script =>
      var chain = ServedChain.empty(None)
      var counter = 0
      var salt = 0
      val ids = (0 until 3).map { _ =>
        val (c, id) = chain.open()
        chain = c
        id
      }
      var locals: Map[Long, Local] = ids.map(_ -> Vector.empty[Bytes]).toMap
      script.foreach {
        case Op.Extend =>
          counter += 1
          chain = chain.extend(served(counter, salt), 0, None)
        case Op.RollBack(depth) =>
          val keep = (chain.blocks.size - depth).max(0)
          val to = if keep == 0 then Point.Origin else chain.blocks(keep - 1).ref.point
          chain.rollback(to).foreach { c =>
            // a fork: the next blocks differ from the ones just removed
            salt += 1
            counter -= (chain.blocks.size - keep)
            chain = c
          }
        case Op.Read(f, steps) =>
          val (c, l) = apply(locals(ids(f)), chain, ids(f), steps)
          chain = c
          locals = locals.updated(ids(f), l)
      }
      assert(ServedChain.isConsistent(chain))
      ids.foreach { id =>
        val (c, l) = apply(locals(id), chain, id, script.size * 2 + 20)
        chain = c
        assertEquals(l, chain.blocks.map(_.ref.hash), s"follower $id")
      }
    }
  }

  test("a follower that had read past a rollback is told to roll back to the new tip exactly") {
    var chain = ServedChain.empty(None)
    val (c0, id) = chain.open()
    chain = (1 to 5).foldLeft(c0)((c, n) => c.extend(served(n), 0, None))
    chain = chain.move(id, Cursor.At(ref(4).point))
    val rolled = chain.rollback(ref(2).point).get
    assertEquals(rolled.read(rolled.followers(id)), Read.Backward(ref(2).point))
    // a follower that had not read that far is not disturbed
    val behind = chain.move(id, Cursor.At(ref(1).point)).rollback(ref(2).point).get
    assertEquals(behind.read(behind.followers(id)), Read.Forward(served(2)))
    // rolling back to a block that is not served fails
    assertEquals(chain.rollback(ref(9).point), None)
  }

/** The server over the immutable store and the volatile chain, read through its algebras. */
class ChainServerSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  import RelayKit.*

  private def ok[A](body: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](body).rescue(error => IO(fail(error.message)))

  private val fatal: Fatal => IO[Unit] = failure => IO(fail(failure.message))

  private val k = 3

  /** What an answer for a range gives, its blocks read whole. */
  private enum Got:
    case NoBlocks
    case Blocks(raws: Vector[Bytes])

    /** Refused at once, or in the batch after the blocks `sent`. */
    case Refused(refusal: NetworkError.RangeRefusal, sent: Vector[Bytes])

  private def got(answer: BlockFetchServer.Answer[IO]): IO[Got] = answer match
    case BlockFetchServer.Answer.NoBlocks()       => IO.pure(Got.NoBlocks)
    case BlockFetchServer.Answer.Refused(refusal) => IO.pure(Got.Refused(refusal, Vector.empty))
    case BlockFetchServer.Answer.Batch(blocks) =>
      blocks.compile.toVector.map { items =>
        val sent = items.flatMap(_.toOption)
        items
          .collectFirst { case Left(refusal) => refusal }
          .fold(Got.Blocks(sent))(
            Got.Refused(_, sent)
          )
      }

  private def raws(ns: Range): Vector[Bytes] = ns.toVector.map(raw(_))

  /** `blocks`, with `act` run once a batch has read its first `after` blocks and before it reads
    * the next: a batch reads each held block when it is pulled.
    */
  private def tapped(
      blocks: BlockFetchServer.Blocks[IO],
      after: Int,
      act: IO[Unit]
  ): BlockFetchServer.Blocks[IO] = (from, to) =>
    blocks.range(from, to).map {
      case BlockFetchServer.Answer.Batch(sent) =>
        BlockFetchServer.Answer.Batch(
          sent.zipWithIndex.evalTap((_, n) => act.whenA(n == after - 1)).map(_._1)
        )
      case other => other
    }

  private def readAll(
      source: xyz.kd5ujc.scarab.network.ChainSyncServer.Source[IO, NtNHeader]
  ): IO[List[Int]] =
    def loop(acc: List[Int]): IO[List[Int]] =
      source.poll.flatMap {
        case Some(Update.RollForward(h, _))  => loop(acc :+ (h.raw.value.head & 0xff))
        case Some(Update.RollBackward(_, _)) => loop(acc)
        case Some(Update.NotKept(after))     => IO(fail(s"not kept after $after"))
        case None                            => IO.pure(acc)
      }
    loop(Nil)

  test("a client from Origin reads the immutable blocks and then the volatile ones") {
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer
              .open[IO](Some(store), None, headerOf, fatal)
            persistence = server.persistence[Unit]
            _ <- (1 to 10).toList.traverse_ { i =>
              val immutable = if i > k then Vector(ref(i - k)) else Vector.empty
              immutable.traverse_(r => store.append(r, raw(r.blockNo.toInt))) *>
                persistence.extended(
                  ref(i),
                  raw(i),
                  immutable,
                  Anchor[Unit](immutable.headOption, ())
                )
            }
            tip <- server.tip
            read <- server.source.use { source =>
              source.intersect(Vector(ref(77).point, Point.Origin)) *> readAll(source)
            }
            fromMiddle <- server.source.use { source =>
              source.intersect(Vector(ref(5).point, Point.Origin)) *> readAll(source)
            }
            fromVolatile <- server.source.use { source =>
              source.intersect(Vector(ref(9).point)) *> readAll(source)
            }
            unknown <- server.source.use(_.intersect(Vector(ref(77).point)))
          yield
            assertEquals(read, (1 to 10).toList)
            assertEquals(fromMiddle, (6 to 10).toList)
            assertEquals(fromVolatile, List(10))
            assertEquals(unknown.isLeft, true)
            assertEquals(tip.blockNo.value, BigInt(10))
        }
      }
    }
  }

  test("the node-to-client cursor serves the stored block bytes, immutable and volatile") {
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer
              .open[IO](Some(store), None, headerOf, fatal)
            persistence = server.persistence[Unit]
            _ <- (1 to 8).toList.traverse_ { i =>
              val immutable = if i > k then Vector(ref(i - k)) else Vector.empty
              immutable.traverse_(r => store.append(r, raw(r.blockNo.toInt))) *>
                persistence.extended(
                  ref(i),
                  raw(i),
                  immutable,
                  Anchor[Unit](immutable.headOption, ())
                )
            }
            blocks <- server.blockSource.use { source =>
              def loop(acc: List[Bytes]): IO[List[Bytes]] =
                source.poll.flatMap {
                  case Some(Update.RollForward(b, _))  => loop(acc :+ b.bytes)
                  case Some(Update.RollBackward(_, _)) => loop(acc)
                  case Some(Update.NotKept(after))     => IO(fail(s"not kept after $after"))
                  case None                            => IO.pure(acc)
                }
              source.intersect(Vector(Point.Origin)) *> loop(Nil)
            }
          yield assertEquals(blocks, (1 to 8).toList.map(i => raw(i)))
        }
      }
    }
  }

  test("the first instruction after an intersection is a roll-back to the point found") {
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer
              .open[IO](Some(store), None, headerOf, fatal)
            persistence = server.persistence[Unit]
            _ <- (1 to 4).toList.traverse_(i =>
              persistence.extended(ref(i), raw(i), Vector.empty, Anchor[Unit](None, ()))
            )
            first <- server.source.use { source =>
              for
                _ <- source.intersect(Vector(ref(2).point))
                a <- source.poll
                b <- source.poll
              yield (a, b)
            }
            origin <- server.blockSource.use { source =>
              source.intersect(Vector(Point.Origin)) *> source.poll
            }
          yield
            first match
              case (Some(Update.RollBackward(p, _)), Some(Update.RollForward(h, _))) =>
                assertEquals(p, ref(2).point)
                assertEquals(h.raw, raw(3))
              case other => fail(other.toString)
            origin match
              case Some(Update.RollBackward(Point.Origin, _)) => ()
              case other                                      => fail(other.toString)
        }
      }
    }
  }

  /** A server with no store whose chain of `blocks` blocks has its first `blocks - k` immutable. */
  private def withoutStore(blocks: Int): IO[ChainServer[IO]] =
    ok {
      for
        server <- ChainServer.open[IO](None, None, headerOf, fatal)
        persistence = server.persistence[Unit]
        _ <- (1 to blocks).toList.traverse_ { i =>
          val immutable = if i > k then Vector(ref(i - k)) else Vector.empty
          persistence.extended(ref(i), raw(i), immutable, Anchor[Unit](immutable.headOption, ()))
        }
      yield server
    }

  test("a new follower's first instruction is a roll-back to Origin, with or without a store") {
    // the reference's follower starts in `FollowerInit`, whose instruction is `RollBack
    // GenesisPoint` (`ChainDB/Impl/Follower.hs` 108, 245-246, 282-286, ouroboros-consensus
    // 82ecba32): `cardano-cli`'s `getLocalChainTip` takes the tip from that first reply
    for
      server <- withoutStore(8)
      tip <- server.tip
      blocks <- server.blockSource.use(_.poll)
      headers <- server.source.use(_.poll)
    yield
      assertEquals(blocks, Some(Update.RollBackward(Point.Origin, tip)))
      assertEquals(headers, Some(Update.RollBackward(Point.Origin, tip)))
      assertEquals(tip.blockNo.value, BigInt(8))
  }

  test("without a store, a follower whose next block is no longer kept is not left waiting") {
    for
      server <- withoutStore(8)
      // from Origin, past the first instruction: block 1 is immutable and not kept
      fromOrigin <- server.blockSource.use(source =>
        source.poll *> (source.poll, source.await).tupled
      )
      // at a volatile point that becomes immutable before the follower asks again
      persistence = server.persistence[Unit]
      overtaken <- server.blockSource.use { source =>
        source.intersect(Vector(ref(6).point)) *> source.poll *>
          ok(
            (9 to 12).toList.traverse_ { i =>
              val immutable = Vector(ref(i - k))
              persistence.extended(
                ref(i),
                raw(i),
                immutable,
                Anchor[Unit](immutable.headOption, ())
              )
            }
          ) *> source.poll
      }
    yield
      assertEquals(fromOrigin, (Some(Update.NotKept(Point.Origin)), Update.NotKept(Point.Origin)))
      assertEquals(overtaken, Some(Update.NotKept(ref(6).point)))
  }

  test("a client waiting at the tip is woken by the next block and by a rollback") {
    Settled { settle =>
      tempDir.use { dir =>
        ok {
          FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
            for
              server <- ChainServer
                .open[IO](Some(store), None, headerOf, fatal)
              persistence = server.persistence[Unit]
              _ <- (1 to 2).toList.traverse_(i =>
                persistence.extended(ref(i), raw(i), Vector.empty, Anchor[Unit](None, ()))
              )
              outcome <- server.source.use { source =>
                for
                  _ <- source.intersect(Vector(ref(2).point))
                  _ <- source.poll // the roll-back to the intersection
                  waiting <- source.await.start
                  // the client waits at the tip
                  _ <- settle
                  _ <- persistence.extended(ref(3), raw(3), Vector.empty, Anchor[Unit](None, ()))
                  forward <- waiting.joinWithNever
                  rollback <- source.await.start
                  _ <- settle
                  _ <- persistence.rolledBack(ref(1).point)
                  back <- rollback.joinWithNever
                yield (forward, back)
              }
            yield outcome match
              case (Update.RollForward(h, tip), Update.RollBackward(p, tip2)) =>
                assertEquals(h.raw, raw(3))
                assertEquals(tip.blockNo.value, BigInt(3))
                assertEquals(p, ref(1).point)
                assertEquals(tip2.blockNo.value, BigInt(1))
              case other => fail(other.toString)
          }
        }
      }
    }
  }

  test("block ranges: inside the store, across into the volatile chain, whole, and refusals") {
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer
              .open[IO](Some(store), None, headerOf, fatal)
            persistence = server.persistence[Unit]
            _ <- (1 to 10).toList.traverse_ { i =>
              val immutable = if i > k then Vector(ref(i - k)) else Vector.empty
              immutable.traverse_(r => store.append(r, raw(r.blockNo.toInt))) *>
                persistence.extended(
                  ref(i),
                  raw(i),
                  immutable,
                  Anchor[Unit](immutable.headOption, ())
                )
            }
            blocks = server.blocks
            range = (a: Int, b: Int) => blocks.range(ref(a).point, ref(b).point).flatMap(got)
            inStore <- range(2, 5)
            single <- range(4, 4)
            across <- range(5, 9)
            volatile <- range(8, 10)
            backwards <- range(5, 2)
            unknown <- range(2, 77)
            whole <- range(1, 10)
          yield
            assertEquals(inStore, Got.Blocks(raws(2 to 5)))
            assertEquals(single, Got.Blocks(raws(4 to 4)))
            assertEquals(across, Got.Blocks(raws(5 to 9)))
            assertEquals(volatile, Got.Blocks(raws(8 to 10)))
            assertEquals(backwards, Got.NoBlocks)
            assertEquals(unknown, Got.NoBlocks)
            assertEquals(whole, Got.Blocks(raws(1 to 10)))
        }
      }
    }
  }

  test("a range across the boundary is exact while volatile blocks become immutable") {
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer
              .open[IO](Some(store), None, headerOf, fatal)
            persistence = server.persistence[Unit]
            extend = (i: Int) =>
              val immutable = if i > k then Vector(ref(i - k)) else Vector.empty
              immutable.traverse_(r => store.append(r, raw(r.blockNo.toInt))) *>
                persistence.extended(
                  ref(i),
                  raw(i),
                  immutable,
                  Anchor[Unit](immutable.headOption, ())
                )
            _ <- (1 to 10).toList.traverse_(extend)
            blocks = server.blocks
            // After the first block is sent, blocks 8 and 9 of the captured volatile chain move
            // to the store before the stored part is read.
            promoting <- tapped(blocks, 1, extend(11) *> extend(12))
              .range(ref(5).point, ref(9).point)
              .flatMap(got)
            // Block 10 is already in the store but still in the volatile chain when the request
            // arrives (the store is written first).
            _ <- store.append(ref(10), raw(10))
            written <- blocks.range(ref(8).point, ref(11).point).flatMap(got)
            fromBoundary <- blocks.range(ref(9).point, ref(12).point).flatMap(got)
          yield
            assertEquals(promoting, Got.Blocks(raws(5 to 9)))
            assertEquals(written, Got.Blocks(raws(8 to 11)))
            assertEquals(fromBoundary, Got.Blocks(raws(9 to 12)))
        }
      }
    }
  }

  /** Blocks 1 to `n`, with a boundary block before each block of `boundaries`: in that block's
    * slot, with the number of the block before it, as a Byron epoch boundary block.
    */
  private def withBoundaries(n: Int, boundaries: Set[Int]): Vector[(BlockRef, Bytes)] =
    (1 to n).toVector.flatMap { i =>
      val main = (ref(i), raw(i))
      if !boundaries.contains(i) then Vector(main)
      else
        val boundary = BlockRef(ref(i).slot, hash(i + 500000), BigInt(i - 1), boundary = true)
        Vector((boundary, raw(i - 1, 9)), main)
    }

  /** The server's chain extended by `chain`, each block storing the one `k` before it. */
  private def grow(
      store: ImmutableStore[IO],
      server: ChainServer[IO],
      chain: Vector[(BlockRef, Bytes)]
  )(using Handle[IO, NodeError]): IO[Unit] =
    val persistence = server.persistence[Unit]
    chain.indices.toList.traverse_ { i =>
      val immutable = if i >= k then Vector(chain(i - k)) else Vector.empty
      immutable.traverse_((r, b) => store.append(r, b)) *>
        persistence.extended(
          chain(i)._1,
          chain(i)._2,
          immutable.map(_._1),
          Anchor[Unit](immutable.headOption.map(_._1), ())
        )
    }

  private def headers(
      source: xyz.kd5ujc.scarab.network.ChainSyncServer.Source[IO, NtNHeader]
  ): IO[Vector[Bytes]] =
    def loop(acc: Vector[Bytes]): IO[Vector[Bytes]] =
      source.poll.flatMap {
        case Some(Update.RollForward(h, _))  => loop(acc :+ h.raw)
        case Some(Update.RollBackward(_, _)) => loop(acc)
        case Some(Update.NotKept(after))     => IO(fail(s"not kept after $after"))
        case None                            => IO.pure(acc)
      }
    loop(Vector.empty)

  test("stored chunks, boundary blocks and the volatile chain are served in chain order") {
    // ten blocks per chunk: block 20's boundary block starts chunk 2, block 25's is inside it
    val chain = withBoundaries(40, Set(20, 25))
    val raws = chain.map(_._2)
    def at(point: Point) = chain.indexWhere(_._1.point == point)
    val boundary20 = chain(at(ref(20).point) - 1)._1
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer
              .open[IO](Some(store), None, headerOf, fatal)
            _ <- grow(store, server, chain)
            starts = Vector(
              Point.Origin,
              ref(9).point,
              boundary20.point,
              ref(19).point,
              chain(chain.size - k - 1)._1.point
            )
            followed <- starts.traverse(p =>
              server.source.use(source => source.intersect(Vector(p)) *> headers(source))
            )
            whole <- server.blockSource.use { source =>
              def loop(acc: Vector[Bytes]): IO[Vector[Bytes]] =
                source.poll.flatMap {
                  case Some(Update.RollForward(b, _))  => loop(acc :+ b.bytes)
                  case Some(Update.RollBackward(_, _)) => loop(acc)
                  case Some(Update.NotKept(after))     => IO(fail(s"not kept after $after"))
                  case None                            => IO.pure(acc)
                }
              source.intersect(Vector(Point.Origin)) *> loop(Vector.empty)
            }
            ranges = Vector(
              ref(8).point -> ref(23).point,
              boundary20.point -> ref(39).point,
              ref(1).point -> ref(40).point
            )
            fetched <- ranges.traverse((from, to) => server.blocks.range(from, to).flatMap(got))
          yield
            starts.zip(followed).foreach { (start, got) =>
              val from = start match
                case Point.Origin => 0
                case p            => at(p) + 1
              assertEquals(got, raws.drop(from), s"after $start")
            }
            assertEquals(whole, raws)
            ranges.zip(fetched).foreach { case ((from, to), got) =>
              assertEquals(got, Got.Blocks(raws.slice(at(from), at(to) + 1)), s"$from to $to")
            }
        }
      }
    }
  }

  /** The read of block `n` of the counted chain [[counted]] grows: ten blocks to a chunk from block
    * 10, two bytes each.
    */
  private def blockAt(dir: fs2.io.file.Path)(n: Int): CountedReads.Read =
    val chunk = n / 10
    CountedReads.Read(dir / s"0000$chunk.chunk", 2L * (n - 10 * chunk), 2L * (n - 10 * chunk + 1))

  /** A server over a counted store and blocks 1 to 40: 1 to 37 stored, 38 to 40 volatile. */
  private def counted[A](dir: fs2.io.file.Path)(
      body: (ChainServer[IO], Ref[IO, Vector[CountedReads.Read]]) => IO[A]
  ): IO[A] =
    ok {
      CountedReads.open(dir, 30L, 1000).use { (store, log) =>
        for
          server <- ChainServer.open[IO](Some(store), None, headerOf, fatal)
          _ <- grow(store, server, (1 to 40).toVector.map(i => (ref(i), raw(i))))
          _ <- log.set(Vector.empty)
          result <- body(server, log)
        yield result
      }
    }

  test("a client behind the volatile chain is sent each header from one read of its block") {
    tempDir.use { dir =>
      counted(dir) { (server, log) =>
        for
          sent <- server.source.use { source =>
            for
              _ <- source.intersect(Vector(ref(12).point))
              _ <- log.set(Vector.empty)
              updates <- (1 to 6).toList.traverse(_ => source.poll)
            yield updates.collect { case Some(Update.RollForward(h, _)) => h.raw }
          }
          following <- log.get
        yield
          assertEquals(sent, (13 to 17).toList.map(raw(_)))
          assertEquals(following, (13 to 17).toVector.map(blockAt(dir)))
      }
    }
  }

  test("an intersection finds a stored point in the index, reading no block") {
    tempDir.use { dir =>
      counted(dir) { (server, log) =>
        server.source.use { source =>
          for
            tip <- server.tip
            // a stored slot whose block has another hash, a slot past the store, and a stored block
            found <- source.intersect(
              Vector(ref(14, salt = 1).point, ref(77).point, ref(12).point)
            )
            intersecting <- log.getAndSet(Vector.empty)
            back <- source.poll
            forward <- source.poll
            following <- log.get
          yield
            assertEquals(found, Right(ref(12).point -> tip))
            assertEquals(intersecting, Vector.empty[CountedReads.Read])
            assertEquals(back, Some(Update.RollBackward(ref(12).point, tip)))
            assertEquals(forward.collect { case Update.RollForward(h, _) => h.raw }, Some(raw(13)))
            assertEquals(following, Vector(blockAt(dir)(13)))
        }
      }
    }
  }

  test("a BlockFetch range finds its ends in the index and reads each of its blocks once") {
    tempDir.use { dir =>
      counted(dir) { (server, log) =>
        /** The range's blocks, the reads made before it is pulled, the reads made by the time each
          * block is sent, and all the reads it made.
          */
        def fetch(from: Int, to: Int) =
          for
            _ <- log.set(Vector.empty)
            range <- server.blocks.range(ref(from).point, ref(to).point)
            checking <- log.get
            sent <- range match
              case BlockFetchServer.Answer.Batch(blocks) =>
                blocks
                  .evalMap(b => log.get.map(reads => (b, reads.size)))
                  .compile
                  .toVector
                  .map(sent => Got.Blocks(sent.flatMap(_._1.toOption)) -> sent.map(_._2))
              case other => got(other).map(_ -> Vector.empty[Int])
            reads <- log.get
          yield (sent._1, checking, sent._2, reads)
        for
          inChunk <- fetch(12, 16)
          across <- fetch(17, 23)
          single <- fetch(14, 14)
          intoVolatile <- fetch(35, 39)
          reversed <- fetch(16, 12)
          unknown <- fetch(12, 77)
        yield
          def blocks(ns: Range) = ns.toVector.map(blockAt(dir))
          val answers = Vector(inChunk, across, single, intoVolatile, reversed, unknown)
          answers.foreach((_, checking, _, _) =>
            assertEquals(checking, Vector.empty[CountedReads.Read], "read to check a range")
          )
          assertEquals(
            answers.map(_._1),
            Vector(12 to 16, 17 to 23, 14 to 14, 35 to 39).map(ns => Got.Blocks(raws(ns))) ++
              Vector(Got.NoBlocks, Got.NoBlocks)
          )
          // each stored block of the range once, in order, and nothing after it
          assertEquals(
            answers.map(_._4),
            Vector(
              blocks(12 to 16),
              blocks(17 to 23),
              blocks(14 to 14),
              blocks(35 to 37),
              Vector.empty,
              Vector.empty
            )
          )
          // The stored part streams from a producer that keeps the store's scope, and reads at most
          // two blocks ahead of what is sent (`StorageBoundary.stream`, a queue of one).
          answers
            .take(3)
            .foreach((_, _, made, _) =>
              made.zipWithIndex.foreach { (reads, sent) =>
                assert(
                  reads >= sent + 1 && reads <= sent + 3,
                  s"$reads reads with block $sent sent"
                )
              }
            )
      }
    }
  }

  test(
    "a range is in order by slot: from a boundary block back to the block before it is refused"
  ) {
    // ten blocks to a chunk; block 20's boundary block starts chunk 2, and block 40's is volatile, as
    // are block 39 before it and block 40 after it
    val chain = withBoundaries(40, Set(20, 40))
    def boundary(n: Int) = chain(chain.indexWhere(_._1.point == ref(n).point) - 1)._1
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer
              .open[IO](Some(store), None, headerOf, fatal)
            _ <- grow(store, server, chain)
            range = (from: BlockRef, to: BlockRef) =>
              server.blocks.range(from.point, to.point).flatMap(got)
            storedBack <- range(boundary(20), ref(19))
            volatileBack <- range(boundary(40), ref(39))
            boundaryFirst <- range(boundary(20), ref(20))
            boundaryLast <- range(ref(20), boundary(20))
            toBoundary <- range(ref(19), boundary(20))
            volatileFirst <- range(boundary(40), ref(40))
          yield
            assertEquals(storedBack, Got.NoBlocks, "stored, from a boundary block back")
            assertEquals(volatileBack, Got.NoBlocks, "volatile, from a boundary block back")
            assertEquals(boundaryFirst, Got.Blocks(Vector(raw(19, 9), raw(20))))
            assertEquals(
              boundaryLast,
              Got.NoBlocks,
              "from the main block of a slot to its boundary block"
            )
            assertEquals(toBoundary, Got.Blocks(Vector(raw(19), raw(19, 9))))
            assertEquals(volatileFirst, Got.Blocks(Vector(raw(39, 9), raw(40))))
        }
      }
    }
  }
