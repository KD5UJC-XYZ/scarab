// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import fs2.{Pull, Stream}
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{BlockFetch, CardanoBlockFetch, Era, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.network.ChainSyncServer.Update
import xyz.kd5ujc.scarab.network.{
  Bearer,
  BlockFetchServer,
  Mux,
  NodeToNodeServer,
  Peers,
  Protocols,
  ServerHandlers,
  Settled,
  TxSubmissionServer,
  recvPrefix
}
import xyz.kd5ujc.scarab.protocol.NetworkError
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef}
import xyz.kd5ujc.scarab.storage.{
  CountedReads,
  FileImmutableStore,
  ImmutableStore,
  ReceivedBlocks,
  StorageTestKit
}
import munit.ScalaCheckSuite
import cats.mtl.Handle
import cats.mtl.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{NodeError, StorageError}
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

  private type Answer =
    Either[BlockFetchServer.UnknownRange, Stream[IO, BlockFetchServer.IteratorResult]]

  /** What a range gives, read whole as the server sends it: `NoBlocks` for an unknown range, the
    * blocks to `Exhausted`, or the refusal raised, or `BlockGCed`, after the blocks sent.
    */
  private def got(range: cats.mtl.Raise[IO, NetworkError] ?=> IO[Answer]): IO[Got] =
    Ref.of[IO, Vector[Bytes]](Vector.empty).flatMap { sent =>
      Peers
        .typed {
          range.flatMap {
            case Left(_) => IO.pure(Got.NoBlocks)
            case Right(results) =>
              results
                .evalMap {
                  case BlockFetchServer.IteratorResult.Exhausted  => IO.pure(false)
                  case BlockFetchServer.IteratorResult.Block(raw) => sent.update(_ :+ raw).as(true)
                  case BlockFetchServer.IteratorResult.BlockGCed(point) =>
                    NetworkError
                      .RangeRefused(NetworkError.RangeRefusal.BlockGCed(point))
                      .raise[IO, Boolean]
                }
                .takeWhile(identity)
                .compile
                .drain *> sent.get.map(Got.Blocks(_))
          }
        }
        .flatMap {
          case Right(answer)                            => IO.pure(answer)
          case Left(NetworkError.RangeRefused(refusal)) => sent.get.map(Got.Refused(refusal, _))
          case Left(other)                              => IO(fail(other.message))
        }
    }

  private def raws(ns: Range): Vector[Bytes] = ns.toVector.map(raw(_))

  /** The ChainDB's `InvalidIteratorRange`. */
  private def invalid(from: Point, to: Point): NetworkError.RangeRefusal =
    NetworkError.RangeRefusal.InvalidIteratorRange(from, to)

  /** The ImmutableDB's `InvalidIteratorRangeError`. */
  private def invalidImmutable(from: Point, to: Point): NetworkError.RangeRefusal =
    NetworkError.RangeRefusal.InvalidIteratorRangeError(from, to)

  /** `blocks`, with `act` run once a batch has read its first `after` blocks and before it reads
    * the next: a batch reads each held block when it is pulled.
    */
  private def tapped(
      blocks: BlockFetchServer.Blocks[IO],
      after: Int,
      act: IO[Unit]
  ): BlockFetchServer.Blocks[IO] = new BlockFetchServer.Blocks[IO]:
    def range(from: Point, to: Point)(using cats.mtl.Raise[IO, NetworkError]) =
      blocks
        .range(from, to)
        .map(
          _.map(sent => sent.zipWithIndex.evalTap((_, n) => act.whenA(n == after - 1)).map(_._1))
        )

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
              .open[IO](Some(store), None, headerOf, fatal, ChainServer.Held.none)
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
              .open[IO](Some(store), None, headerOf, fatal, ChainServer.Held.none)
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
              .open[IO](Some(store), None, headerOf, fatal, ChainServer.Held.none)
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
        server <- ChainServer.open[IO](None, None, headerOf, fatal, ChainServer.Held.none)
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
                .open[IO](Some(store), None, headerOf, fatal, ChainServer.Held.none)
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
              .open[IO](Some(store), None, headerOf, fatal, ChainServer.Held.none)
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
            range = (a: Int, b: Int) => got(blocks.range(ref(a).point, ref(b).point))
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
            // a lower bound in a later slot than the upper: the reference's ChainDB iterator raises
            // `InvalidIteratorRange` (`Iterator.hs` 254-257), which ends the connection
            assertEquals(backwards, Got.Refused(invalid(ref(5).point, ref(2).point), Vector.empty))
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
              .open[IO](Some(store), None, headerOf, fatal, ChainServer.Held.none)
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
            promoting <- got(
              tapped(blocks, 1, extend(11) *> extend(12)).range(ref(5).point, ref(9).point)
            )
            // Block 10 is already in the store but still in the volatile chain when the request
            // arrives (the store is written first).
            _ <- store.append(ref(10), raw(10))
            written <- got(blocks.range(ref(8).point, ref(11).point))
            fromBoundary <- got(blocks.range(ref(9).point, ref(12).point))
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
              .open[IO](Some(store), None, headerOf, fatal, ChainServer.Held.none)
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
            fetched <- ranges.traverse((from, to) => got(server.blocks.range(from, to)))
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
          server <- ChainServer.open[IO](Some(store), None, headerOf, fatal, ChainServer.Held.none)
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
            checking <- Ref.of[IO, Vector[CountedReads.Read]](Vector.empty)
            made <- Ref.of[IO, Vector[Int]](Vector.empty)
            // the reads made before the range is pulled, and by the time each block is sent
            sent <- got(
              server.blocks
                .range(ref(from).point, ref(to).point)
                .flatTap { _ =>
                  log.get.flatMap(checking.set)
                }
                .map(_.map(_.evalTap {
                  case BlockFetchServer.IteratorResult.Block(_) =>
                    log.get.flatMap(reads => made.update(_ :+ reads.size))
                  case _ => IO.unit
                }))
            )
            reads <- log.get
            before <- checking.get
            each <- made.get
          yield (sent, before, each, reads)
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
              Vector(
                Got.Refused(invalid(ref(16).point, ref(12).point), Vector.empty),
                Got.NoBlocks
              )
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
              .open[IO](Some(store), None, headerOf, fatal, ChainServer.Held.none)
            _ <- grow(store, server, chain)
            range = (from: BlockRef, to: BlockRef) => got(server.blocks.range(from.point, to.point))
            storedBack <- range(boundary(20), ref(19))
            volatileBack <- range(boundary(40), ref(39))
            boundaryFirst <- range(boundary(20), ref(20))
            boundaryLast <- range(ref(20), boundary(20))
            toBoundary <- range(ref(19), boundary(20))
            volatileFirst <- range(boundary(40), ref(40))
          yield
            // a lower bound in a later slot (`InvalidIteratorRange`, `Iterator.hs` 254-257), and
            // the main block of a slot before its boundary block in the immutable chain (the
            // ImmutableDB's `InvalidIteratorRangeError`, `ImmutableDB/Impl/Iterator.hs` 151-159)
            // end the connection
            def refused(from: BlockRef, to: BlockRef) =
              Got.Refused(invalid(from.point, to.point), Vector.empty)
            assertEquals(storedBack, refused(boundary(20), ref(19)), "stored, from a boundary back")
            assertEquals(volatileBack, refused(boundary(40), ref(39)), "volatile, boundary back")
            assertEquals(boundaryFirst, Got.Blocks(Vector(raw(19, 9), raw(20))))
            assertEquals(
              boundaryLast,
              Got.Refused(invalidImmutable(ref(20).point, boundary(20).point), Vector.empty),
              "main block, then boundary"
            )
            assertEquals(toBoundary, Got.Blocks(Vector(raw(19), raw(19, 9))))
            assertEquals(volatileFirst, Got.Blocks(Vector(raw(39, 9), raw(40))))
        }
      }
    }
  }

  // BlockFetch serves the forks the node holds, as the reference's ChainDB iterator does (#1237):
  // ouroboros-consensus 82ecba32 (the same at 808e40054), `Storage/ChainDB/Impl/Iterator.hs`,
  // `Paths.hs` and `MiniProtocol/BlockFetch/Server.hs` 87-146

  /** The predecessor the header of `raw(n, salt)` names: block `n - 1` of its salt, and for the
    * first block of a fork (salt `s`) the block `forks(s)` it follows.
    */
  private def predecessors(forks: Map[Int, BlockRef])(
      raw: Bytes
  ): Option[ReceivedBlocks.Predecessor] =
    raw.value.map(_ & 0xff).toList match
      case List(1, 0) => Some(ReceivedBlocks.Predecessor.Genesis)
      case List(n, 0) => Some(ReceivedBlocks.Predecessor.Block(ref(n - 1).hash))
      case List(n, salt) =>
        forks.get(salt).map { base =>
          ReceivedBlocks.Predecessor.Block(
            if base.blockNo == n - 1 then base.hash else ref(n - 1, salt).hash
          )
        }
      case _ => None

  /** Fork 1 follows main block 8, fork 6 main block 10; fork 3 follows a block the node does not
    * hold.
    */
  private val forks =
    Map(1 -> ref(8), 3 -> BlockRef(BigInt(24), hash(777777), BigInt(8)), 6 -> ref(10))

  /** `received`, whose next read of its links runs the action `hook` holds first. */
  private final class Hooked(received: ReceivedBlocks[IO], hook: Ref[IO, IO[Unit]])
      extends ReceivedBlocks[IO]:
    def put(ref: BlockRef, raw: Bytes)(using cats.mtl.Raise[IO, StorageError]) =
      received.put(ref, raw)
    def restoreSelected(ref: BlockRef, raw: Bytes)(using cats.mtl.Raise[IO, StorageError]) =
      received.restoreSelected(ref, raw)
    def discardRecovered(hash: Bytes) = received.discardRecovered(hash)
    def get(hash: Bytes)(using cats.mtl.Raise[IO, StorageError]) = received.get(hash)
    def original(hash: Bytes)(using cats.mtl.Raise[IO, StorageError]) = received.original(hash)
    def links = hook.getAndSet(IO.unit).flatten *> received.links
    def refs = received.refs
    def hashes = received.hashes
    def holds = received.holds
    def invalidate(hash: Bytes) = received.invalidate(hash)
    def collect(durableSlot: BigInt)(using cats.mtl.Raise[IO, StorageError]) =
      received.collect(durableSlot)
    def residency = received.residency

  /** A node over a store and a received log: main blocks are received and then adopted as the node
    * adopts a fetched block, each storing the block `k` before it; other blocks only received.
    */
  private final class Node(
      val server: ChainServer[IO],
      val store: ImmutableStore[IO],
      val received: ReceivedBlocks[IO]
  ):
    def adopt(n: Int, salt: Int = 0)(using Handle[IO, NodeError]): IO[Unit] =
      val immutable = if salt == 0 && n > k then Vector(ref(n - k)) else Vector.empty
      received.put(ref(n, salt), raw(n, salt)) *>
        immutable.traverse_(r => store.append(r, raw(r.blockNo.toInt))) *>
        server
          .persistence[Unit]
          .extended(ref(n, salt), raw(n, salt), immutable, Anchor[Unit](immutable.headOption, ()))

    def receive(n: Int, salt: Int)(using Handle[IO, NodeError]): IO[Unit] =
      received.put(ref(n, salt), raw(n, salt))

  /** A node over the store and received log in `dir`, its log `blocksPerFile` blocks to a file,
    * that adopted main blocks 1 to 10 (1 to 7 stored) and received fork 1's blocks 9 and 10.
    */
  private def node[A](
      dir: fs2.io.file.Path,
      blocksPerFile: Int = 512,
      linked: Map[Bytes, ReceivedBlocks.Predecessor] = Map.empty,
      hook: Option[Ref[IO, IO[Unit]]] = None
  )(
      body: Handle[IO, NodeError] ?=> Node => IO[A]
  ): IO[A] =
    val predecessorOf = (raw: Bytes) => linked.get(raw).orElse(predecessors(forks)(raw))
    ok {
      FileImmutableStore.open[IO](dir / "immutable", 30L).use { (store, _) =>
        ReceivedBlocks.open[IO](dir / "received", blocksPerFile)(predecessorOf).use { log =>
          val received = hook.fold(log)(Hooked(log, _))
          for
            server <- ChainServer.open[IO](
              Some(store),
              None,
              headerOf,
              fatal,
              ChainServer.Held(Some(received), None)
            )
            node = Node(server, store, received)
            _ <- (1 to 10).toList.traverse_(node.adopt(_))
            _ <- node.receive(9, 1) *> node.receive(10, 1)
            result <- body(node)
          yield result
        }
      }
    }

  /** A BlockFetch message as the client decodes it. */
  private enum Wire:
    case StartBatch, NoBlocks, BatchDone
    case Block(raw: Bytes)

  /** What a client is sent for one range: the messages, and the reason the server's connection
    * ended with, none when the range ended in `NoBlocks` or `BatchDone` and the connection stayed.
    * `seen` is what the client's channel raised at that end.
    */
  private final case class Exchange(
      sent: Vector[Wire],
      ended: Option[NetworkError],
      seen: Option[NetworkError]
  )

  private val codec = CardanoBlockFetch.payloadCodec()

  private def encoded(message: BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]): Bytes =
    BlockFetch
      .encode(BlockFetch.State.Idle, BlockFetch.Role.Client, message, codec)
      .fold(e => fail(e.message), identity)

  private val noTransactions: TxSubmissionServer.Sink[IO] = new TxSubmissionServer.Sink[IO]:
    def reader = xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
    def known(id: Bytes) = IO.pure(false)
    def submit(originals: Vector[xyz.kd5ujc.scarab.protocol.TxSubmission2.EraTx])(using
        cats.mtl.Raise[IO, NetworkError]
    ) = IO.unit

  /** `blocks`, with `act` run once a batch has sent its first `after` blocks, before it reads the
    * next.
    */
  private def afterSent(
      blocks: BlockFetchServer.Blocks[IO],
      after: Int,
      act: IO[Unit]
  ): BlockFetchServer.Blocks[IO] = new BlockFetchServer.Blocks[IO]:
    def range(from: Point, to: Point)(using cats.mtl.Raise[IO, NetworkError]) =
      blocks
        .range(from, to)
        .map(
          _.map(
            _.pull
              .take(after.toLong)
              .flatMap(_.fold(Pull.done)(rest => Pull.eval(act) >> rest.pull.echo))
              .stream
          )
        )

  /** The range `from` to `to` asked of `server` over a connection served as the node serves one
    * (`NodeToNodeServer.start`, whose failed server ends the connection, after which its owner
    * closes the bearer). With `during`, once the client has `n` blocks the server runs `act` before
    * it reads the next.
    */
  private def overWire(
      server: ChainServer[IO],
      from: Point,
      to: Point,
      during: Option[(Int, IO[Unit])] = None
  ): IO[Exchange] =
    Peers
      .typed {
        for
          bearers <- Bearer.inMemory[IO](64)
          (local, remote) = bearers
          blocksSeen <- SignallingRef[IO, Int](0)
          blocks = during.fold(server.blocks)((n, act) =>
            afterSent(server.blocks, n, blocksSeen.waitUntil(_ >= n) *> act)
          )
          handlers = ServerHandlers[IO, NtNHeader](
            NtNHeader.codec,
            server.source,
            blocks,
            noTransactions
          )
          ended <- Deferred[IO, NetworkError]
          // the connection's owner: it closes the bearer once the server's mux has ended, and is
          // cancelled when the exchange ends however it ends
          serverSide = Peers
            .typed(
              Mux
                .resource(remote, NodeToNodeServer.Protocol, Mux.Config(role = Mux.Role.Responder))
                .use(mux => NodeToNodeServer.start(mux, handlers).use(_ => mux.closed))
            )
            .flatMap(reason => ended.complete(reason.merge).void)
            .guarantee(remote.close)
          exchange <- (Mux.resource(local, NodeToNodeServer.Protocol), serverSide.background).tupled
            .use { (client, serving) =>
              for
                c <- client.channel(Protocols.BlockFetch)
                _ <- c.send(encoded(BlockFetch.Message.RequestRange(from, to)))
                read <- {
                  def next(
                      state: BlockFetch.State,
                      sent: Vector[Wire]
                  ): IO[(Vector[Wire], Option[NetworkError])] =
                    Peers
                      .typed(
                        c.recvPrefix(2500000)(
                          BlockFetch.decodePrefix(state, BlockFetch.Role.Server, _, codec)
                        )
                      )
                      .flatMap {
                        case Left(closed) => IO.pure(sent -> Some(closed))
                        case Right(BlockFetch.Message.StartBatch) =>
                          next(BlockFetch.State.Streaming, sent :+ Wire.StartBatch)
                        case Right(BlockFetch.Message.Block(block)) =>
                          blocksSeen.update(_ + 1) *>
                            next(BlockFetch.State.Streaming, sent :+ Wire.Block(block.bytes))
                        case Right(BlockFetch.Message.NoBlocks) =>
                          IO.pure((sent :+ Wire.NoBlocks) -> None)
                        case Right(BlockFetch.Message.BatchDone) =>
                          IO.pure((sent :+ Wire.BatchDone) -> None)
                        case Right(other) => IO(fail(s"the server sent $other"))
                      }
                  next(BlockFetch.State.Busy, Vector.empty)
                }
                (sent, seen) = read
                // the connection ended: the server's side has finished, and said why
                _ <- seen.fold(c.send(encoded(BlockFetch.Message.ClientDone)))(_ => serving.void)
                reason <- ended.tryGet
              yield Exchange(sent, reason, seen)
            }
        yield exchange
      }
      .map(_.fold(e => fail(e.message), identity))

  private def served(raws: Vector[Bytes]): Exchange =
    Exchange(
      (Wire.StartBatch +: raws.map(Wire.Block(_))) :+ Wire.BatchDone,
      None,
      None
    )

  private val noBlocks: Exchange = Exchange(Vector(Wire.NoBlocks), None, None)

  /** The connection ended by the server for `refusal`, after `raws` were sent in a batch. */
  private def refused(refusal: NetworkError.RangeRefusal, raws: Vector[Bytes] = Vector.empty) =
    Exchange(
      if raws.isEmpty then Vector.empty else Wire.StartBatch +: raws.map(Wire.Block(_)),
      Some(NetworkError.RangeRefused(refusal)),
      Some(NetworkError.BearerClosed(NetworkError.Closure.PeerClosed))
    )

  private val fork1 = Vector(raw(9, 1), raw(10, 1))

  /** Blocks 11 to 14 adopted, making block 11 immutable, and the received log collected to its
    * slot: with one block to a file every file but block 14's goes.
    */
  private def collected(node: Node): IO[Unit] =
    ok((11 to 14).toList.traverse_(node.adopt(_)) *> node.received.collect(ref(11).slot))

  test("a fork the node holds and never selected is served, alone or joined to its chain") {
    tempDir.use { dir =>
      node(dir) { node =>
        for
          alone <- overWire(node.server, ref(9, 1).point, ref(10, 1).point)
          fromSelected <- overWire(node.server, ref(8).point, ref(10, 1).point)
          fromStored <- overWire(node.server, ref(5).point, ref(10, 1).point)
          single <- overWire(node.server, ref(10, 1).point, ref(10, 1).point)
          // ChainSync follows the selected chain alone, as the reference's follower does
          intersection <- node.server.source.use(_.intersect(Vector(ref(10, 1).point)))
        yield
          assertEquals(alone, served(fork1))
          assertEquals(fromSelected, served(raw(8) +: fork1))
          assertEquals(fromStored, served(raws(5 to 8) ++ fork1))
          assertEquals(single, served(Vector(raw(10, 1))))
          assert(intersection.isLeft, s"a fork's point is not on the chain followed: $intersection")
      }
    }
  }

  test("a block rolled back off the selected chain is still served, as is one held invalid") {
    tempDir.use { dir =>
      node(dir) { node =>
        for
          _ <- node.server.persistence[Unit].rolledBack(ref(8).point)
          _ <- node.adopt(9, 2) *> node.adopt(10, 2)
          rolledBack <- overWire(node.server, ref(9).point, ref(10).point)
          selected <- overWire(node.server, ref(8).point, ref(10, 2).point)
          // a validation refusal leaves the original held, as the reference's VolatileDB keeps an
          // invalid block beside its invalid-block set
          _ <- node.received.invalidate(ref(10, 1).hash)
          invalid <- overWire(node.server, ref(9, 1).point, ref(10, 1).point)
        yield
          assertEquals(rolledBack, served(raws(9 to 10)))
          assertEquals(selected, served(Vector(raw(8), raw(9, 2), raw(10, 2))))
          assertEquals(invalid, served(fork1))
      }
    }
  }

  test("a range the node cannot connect, or whose upper end it does not hold, gets NoBlocks") {
    tempDir.use { dir =>
      node(dir) { node =>
        for
          _ <- node.receive(9, 3) *> node.receive(10, 3)
          // fork 3's blocks are held, but its ancestry is not, so it cannot reach the immutable
          // tip (`ForkTooOld`)
          detached <- overWire(node.server, ref(7).point, ref(10, 3).point)
          // a path held whole is served however it began (`CompletelyInVolatileDB`)
          whole <- overWire(node.server, ref(9, 3).point, ref(10, 3).point)
          unknown <- overWire(node.server, ref(5).point, ref(77).point)
          tooOld <- overWire(node.server, ref(2).point, ref(6, 1).point)
        yield
          assertEquals(detached, noBlocks)
          assertEquals(whole, served(Vector(raw(9, 3), raw(10, 3))))
          assertEquals(unknown, noBlocks)
          assertEquals(tooOld, noBlocks)
      }
    }
  }

  test("bounds that are not a range end the connection, before anything is sent") {
    tempDir.use { dir =>
      node(dir) { node =>
        for
          origin <- overWire(node.server, Point.Origin, ref(5).point)
          reversed <- overWire(node.server, ref(5).point, ref(2).point)
          // the walk back from block 10 meets the start of the chain, never fork 1's block 9
          offPath <- overWire(node.server, ref(9, 1).point, ref(10).point)
        yield
          assertEquals(origin, refused(NetworkError.RangeRefusal.NoGenesisBlock))
          assertEquals(reversed, refused(invalid(ref(5).point, ref(2).point)))
          assertEquals(offPath, refused(invalid(ref(9, 1).point, ref(10).point)))
      }
    }
  }

  test("off the path, a walk that joins the immutable tip asks the store for a range it refuses") {
    // one block to a file: collection takes blocks 1 to 4 from the received log, so the walk back
    // from block 10 stops at block 5, and the range from fork 1's block 9 to the immutable tip is
    // refused by the immutable chain (`InvalidIteratorRangeError`)
    tempDir.use { dir =>
      node(dir, blocksPerFile = 1) { node =>
        for
          _ <- node.received.collect(ref(5).slot)
          offPath <- overWire(node.server, ref(9, 1).point, ref(10).point)
          // the walk stops at block 5, and its path joins the immutable chain at its tip, block 7
          joined <- overWire(node.server, ref(3).point, ref(10).point)
        yield
          assertEquals(offPath, refused(invalidImmutable(ref(9, 1).point, ref(7).point)))
          assertEquals(joined, served(raws(3 to 10)))
      }
    }
  }

  test("a held block collected during its batch ends the connection without BatchDone") {
    // one block to a file; once block 9 of fork 1 is sent, blocks 11 to 14 make block 11
    // immutable and the log is collected to its slot: block 10 of fork 1 is gone, and the
    // immutable chain does not hold it (`IteratorBlockGCed`, raised as `BlockGCed`)
    tempDir.use { dir =>
      node(dir, blocksPerFile = 1) { node =>
        overWire(node.server, ref(9, 1).point, ref(10, 1).point, Some(1 -> collected(node))).map(
          got =>
            assertEquals(
              got,
              refused(NetworkError.RangeRefusal.BlockGCed(ref(10, 1).point), Vector(raw(9, 1)))
            )
        )
      }
    }
  }

  test("held blocks collected during a batch come from the immutable chain, their points checked") {
    // as above, once the first block is sent: the selected chain's blocks 6 to 10 reach the
    // immutable chain and leave the log, and are read from there; fork 1's block 9 is not the
    // immutable chain's block of its slot, so it is collected for good
    for
      recovered <- tempDir.use(dir =>
        node(dir, blocksPerFile = 1)(node =>
          overWire(node.server, ref(5).point, ref(10).point, Some(1 -> collected(node)))
        )
      )
      lost <- tempDir.use(dir =>
        node(dir, blocksPerFile = 1)(node =>
          overWire(node.server, ref(8).point, ref(10, 1).point, Some(1 -> collected(node)))
        )
      )
    yield
      assertEquals(recovered, served(raws(5 to 10)))
      assertEquals(
        lost,
        refused(NetworkError.RangeRefusal.BlockGCed(ref(9, 1).point), Vector(raw(8)))
      )
  }

  test("a fork held in the received log is served after a restart") {
    tempDir.use { dir =>
      for
        _ <- node(dir)(_ => IO.unit)
        restarted <- ok {
          FileImmutableStore.open[IO](dir / "immutable", 30L).use { (store, _) =>
            ReceivedBlocks.open[IO](dir / "received")(predecessors(forks)).use { received =>
              for
                anchor <- store.tip
                server <- ChainServer.open[IO](
                  Some(store),
                  anchor,
                  headerOf,
                  fatal,
                  ChainServer.Held(Some(received), None)
                )
                alone <- overWire(server, ref(9, 1).point, ref(10, 1).point)
                fromStored <- overWire(server, ref(5).point, ref(10, 1).point)
              yield (alone, fromStored)
            }
          }
        }
      yield
        assertEquals(restarted._1, served(fork1))
        assertEquals(restarted._2, served(raws(5 to 8) ++ fork1))
    }
  }

  test("the walk back through the held blocks is stack safe on a 256 KiB thread") {
    // a held chain of 30,000 blocks, the first after the start of the chain: the walk from the
    // last meets the first, and a lower bound off the chain walks back to the start
    val chain = Vector.tabulate(30000)(i => BlockRef(BigInt(i + 1), hash(i + 1), BigInt(i + 1)))
    val held = chain.zipWithIndex.map { (ref, i) =>
      ref.hash -> HeldRange.Held(ref, chain.lift(i - 1).map(_.hash))
    }.toMap
    def point(ref: BlockRef): Point.Block = Point.Block(UInt64.saturating(ref.slot), ref.hash)
    xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack {
      val whole = HeldRange.path(held.get, point(chain.head), point(chain.last))
      val offChain =
        HeldRange.path(
          held.get,
          point(BlockRef(BigInt(7), hash(777777), BigInt(7))),
          point(chain.last)
        )
      assertEquals(
        whole.collect { case HeldRange.Path.Complete(points) => points.toVector },
        Some(chain)
      )
      assertEquals(offChain, None)
    }
  }

  test("a held path is followed by its predecessors whatever their slots") {
    // held blocks at slots 40, 46 and 43, each after the one before: a block is held before
    // selection judges it, and the reference's walk follows the hashes in its index, so the range
    // from the first to the last is served whole
    val first = BlockRef(BigInt(40), hash(920001), BigInt(11))
    val second = BlockRef(BigInt(46), hash(920002), BigInt(12))
    val third = BlockRef(BigInt(43), hash(920003), BigInt(13))
    val raws = Vector(201, 202, 203).map(n => Bytes(Vector(n.toByte, 7.toByte)))
    val linked = Map(
      raws(0) -> ReceivedBlocks.Predecessor.Block(ref(10).hash),
      raws(1) -> ReceivedBlocks.Predecessor.Block(first.hash),
      raws(2) -> ReceivedBlocks.Predecessor.Block(second.hash)
    )
    tempDir.use { dir =>
      node(dir, linked = linked) { node =>
        Vector(first, second, third).zip(raws).traverse_((r, b) => node.received.put(r, b)) *>
          overWire(node.server, first.point, third.point).map(got =>
            assertEquals(got, served(raws))
          )
      }
    }
  }

  test("a range is walked in one snapshot of the held blocks, as the reference walks its index") {
    // between the read of the selected chain and the read of the received log, block 10 is rolled
    // back and collected and fork 6's block 11, after it, is received: the two reads are taken
    // again until the chain did not change between them, so block 11 is seen without block 10,
    // as the reference's one snapshot would show it, and the range cannot be connected
    tempDir.use { dir =>
      for
        hook <- Ref.of[IO, IO[Unit]](IO.unit)
        got <- node(dir, blocksPerFile = 1, hook = Some(hook)) { node =>
          val meanwhile = ok(
            node.server.persistence[Unit].rolledBack(ref(9).point) *> node.receive(11, 6) *>
              node.received.collect(ref(11).slot)
          )
          hook.set(meanwhile) *> overWire(node.server, ref(10).point, ref(11, 6).point)
        }
      yield assertEquals(got, noBlocks)
    }
  }

  /** Blocks 1 to `n` of a chain for [[HeldRange]] alone: slot `i`, number `i`, the body its number.
    */
  private def chainOf(n: Int): Vector[ImmutableStore.Stored] =
    Vector.tabulate(n)(i =>
      ImmutableStore.Stored(
        BlockRef(BigInt(i + 1), hash(930000 + i + 1), BigInt(i + 1)),
        Bytes.fromArray(BigInt(i + 1).toByteArray)
      )
    )

  private def pointOf(ref: BlockRef): Point.Block =
    Point.Block(UInt64.saturating(ref.slot), ref.hash)

  /** A source for [[HeldRange]] over `chain`: the immutable chain is its first `immutable` blocks,
    * the held bodies those `held` says, each body read recorded in `read`.
    */
  private def sourceOf(
      chain: Vector[ImmutableStore.Stored],
      immutable: Ref[IO, Int],
      held: Int => Boolean,
      read: Ref[IO, Vector[Int]]
  ): HeldRange.Source[IO] = new HeldRange.Source[IO]:
    private def index(hash: Bytes) = chain.indexWhere(_.ref.hash == hash)
    def tip = immutable.get.map(n => chain.lift(n - 1).map(_.ref))
    def refOf(point: Point.Block) =
      immutable.get.map(n => chain.take(n).map(_.ref).find(r => pointOf(r) == point))
    def stored(from: HeldRange.Lower, first: BlockRef, last: BlockRef) =
      val start = index(first.hash) + (from match
        case HeldRange.Lower.Inclusive(_) => 0
        case HeldRange.Lower.Exclusive(_) => 1)
      Stream.emits(chain.slice(start, index(last.hash) + 1)).covary[IO]
    def body(hash: Bytes) =
      val i = index(hash)
      read.update(_ :+ (i + 1)) *> IO.pure(chain.lift(i).filter(_ => held(i + 1)).map(_.raw))

  /** The blocks of `chain` from its `firstHeld` -th (from 0) held, each after the one before it in
    * the chain, the first block of the chain after its start.
    */
  private def heldOf(
      chain: Vector[ImmutableStore.Stored],
      firstHeld: Int = 0
  ): Bytes => Option[HeldRange.Held] =
    chain.zipWithIndex
      .drop(firstHeld)
      .map((s, i) => s.ref.hash -> HeldRange.Held(s.ref, chain.lift(i - 1).map(_.ref.hash)))
      .toMap
      .get

  test("the immutable tip moving between the walk and the join moves where the path joins it") {
    // blocks 5 to 10 held, block 4 the immutable tip when the range is asked: the walk stops at
    // block 5, whose predecessor is the tip; before the join reads the tip again it moves to block
    // 7 (then to block 10): the part from the store runs to the new tip, and the held part goes on
    // after it (`streamFromBoth`, `Iterator.hs` 390-433)
    val chain = chainOf(10)
    val held = heldOf(chain, firstHeld = 4)
    def range(moved: Int) =
      for
        immutable <- Ref.of[IO, Int](4)
        read <- Ref.of[IO, Vector[Int]](Vector.empty)
        source = sourceOf(chain, immutable, _ > 4, read)
        sent <- got(
          HeldRange.answer(
            source,
            immutable.set(moved).as(held),
            pointOf(chain(2).ref),
            pointOf(chain(9).ref)
          )
        )
        bodies <- read.get
      yield (sent, bodies)
    for
      toSeven <- range(7)
      toEnd <- range(10)
    yield
      assertEquals(toSeven, (Got.Blocks(chain.drop(2).map(_.raw)), Vector(8, 9, 10)))
      assertEquals(toEnd, (Got.Blocks(chain.drop(2).map(_.raw)), Vector.empty[Int]))
  }

  test("a cycle of held predecessors ends the walk, and the range is not one the node holds") {
    // two held blocks each naming the other as its predecessor: the reference's walk would not
    // end (`Paths.hs` 188-218 and 336-342); this one ends, an
    // Improvement of class (a) (#474), unless it meets the lower bound first
    val a = BlockRef(BigInt(50), hash(940001), BigInt(20))
    val b = BlockRef(BigInt(51), hash(940002), BigInt(21))
    val held = Map(
      a.hash -> HeldRange.Held(a, Some(b.hash)),
      b.hash -> HeldRange.Held(b, Some(a.hash))
    ).get
    val other = pointOf(BlockRef(BigInt(7), hash(940003), BigInt(7)))
    assertEquals(
      HeldRange.path(held, pointOf(a), pointOf(b)).collect { case HeldRange.Path.Complete(p) =>
        p.toVector
      },
      Some(Vector(a, b))
    )
    assertEquals(HeldRange.path(held, other, pointOf(b)), Some(HeldRange.Path.Cycle))
  }

  test("a cycle through the immutable tip gets NoBlocks, never the tip twice") {
    // block 4 is the immutable tip, held too, after a block that is after it: the walk back from
    // block x comes to block 4, to the block before it, and to block 4 again. Taken as a
    // predecessor not held, that repeated hash would be the tip, and the join would send block 4
    // from the store and again from the path
    val chain = chainOf(6)
    val tip = chain(3).ref
    val loop = BlockRef(BigInt(9), hash(950001), BigInt(9))
    val x = BlockRef(BigInt(10), hash(950002), BigInt(10))
    val held = Map(
      x.hash -> HeldRange.Held(x, Some(tip.hash)),
      tip.hash -> HeldRange.Held(tip, Some(loop.hash)),
      loop.hash -> HeldRange.Held(loop, Some(tip.hash))
    ).get
    for
      immutable <- Ref.of[IO, Int](4)
      read <- Ref.of[IO, Vector[Int]](Vector.empty)
      sent <- got(
        HeldRange.answer(
          sourceOf(chain, immutable, _ => true, read),
          IO.pure(held),
          pointOf(chain(1).ref),
          pointOf(x)
        )
      )
    yield assertEquals(sent, Got.NoBlocks)
  }

  test("a long range of the immutable chain streams on a 256 KiB thread") {
    // 20,000 stored blocks asked for whole, the upper bound the immutable tip: the immutable
    // chain's own loop (`InImmutableDB`) for every block
    val chain = chainOf(20000)
    Resource
      .make(
        IO.delay(
          java.util.concurrent.Executors.newSingleThreadExecutor((run: Runnable) =>
            new Thread(null, run, "held-range-immutable-small-stack", 256L * 1024)
          )
        )
      )(executor => IO.delay(executor.shutdown()))
      .use { executor =>
        (for
          immutable <- Ref.of[IO, Int](20000)
          read <- Ref.of[IO, Vector[Int]](Vector.empty)
          sent <- got(
            HeldRange.answer(
              sourceOf(chain, immutable, _ => false, read),
              IO.pure(heldOf(chain, firstHeld = 20000)),
              pointOf(chain.head.ref),
              pointOf(chain.last.ref)
            )
          )
          bodies <- read.get
        yield (sent, bodies))
          .evalOn(scala.concurrent.ExecutionContext.fromExecutorService(executor))
      }
      .map(answered => assertEquals(answered, (Got.Blocks(chain.map(_.raw)), Vector.empty[Int])))
  }

  test("a request is answered from one snapshot however often the chain changes") {
    // the chain changes between the two reads of the selected chain on every attempt: after
    // `SnapshotAttempts` such reads the request reads both under the lock changes are published
    // under, and the change it meets then waits for the lock, so the request is answered
    tempDir.use { dir =>
      for
        hook <- Ref.of[IO, IO[Unit]](IO.unit)
        reads <- Ref.of[IO, Int](0)
        pending <- Ref.of[IO, Option[cats.effect.FiberIO[Unit]]](None)
        next <- Ref.of[IO, Int](11)
        answered <- node(dir, hook = Some(hook)) { node =>
          // the change started while a read holds the lock is the scope's, and is joined while
          // the stores are open
          cats.effect.std.Supervisor[IO](await = false).use { writers =>
            val change = ok(next.getAndUpdate(_ + 1).flatMap(n => node.adopt(n)))
            // every read of the log changes the chain: at once, while a change can be published,
            // and otherwise by a change that starts and lands once the read is done. The changes
            // stop after 50 reads only so that a loop that waits for a quiet chain ends too
            lazy val every: IO[Unit] = reads.updateAndGet(_ + 1).flatMap { n =>
              val now = node.server.publishable.flatMap {
                case _ if n > 50 => IO.unit
                case true        => change
                case false => writers.supervise(change).flatMap(fiber => pending.set(Some(fiber)))
              }
              now *> hook.set(every)
            }
            for
              exchange <- hook
                .set(every) *> overWire(node.server, ref(9, 1).point, ref(10, 1).point)
              landed <- pending.get.flatMap(_.traverse(_.joinWithNever))
            yield (exchange, landed)
          }
        }
        (got, landed) = answered
        count <- reads.get
      yield
        assertEquals(got, served(fork1))
        assertEquals(count, ChainServer.SnapshotAttempts + 1, "reads of the received log")
        assertEquals(landed, Some(()), "the change met under the lock lands after the read")
    }
  }

  test("a long batch streams, and recovers from the immutable chain, on a 256 KiB thread") {
    // 20,000 held blocks asked for whole; the first 10,000 have left the held blocks and are read
    // from the immutable chain, each checked against the path, and the rest from the held blocks
    val chain = chainOf(20000)
    Resource
      .make(
        IO.delay(
          java.util.concurrent.Executors.newSingleThreadExecutor((run: Runnable) =>
            new Thread(null, run, "held-range-small-stack", 256L * 1024)
          )
        )
      )(executor => IO.delay(executor.shutdown()))
      .use { executor =>
        (for
          immutable <- Ref.of[IO, Int](10000)
          read <- Ref.of[IO, Vector[Int]](Vector.empty)
          source = sourceOf(chain, immutable, _ > 10000, read)
          sent <- got(
            HeldRange.answer(
              source,
              IO.pure(heldOf(chain)),
              pointOf(chain.head.ref),
              pointOf(chain.last.ref)
            )
          )
        yield sent).evalOn(scala.concurrent.ExecutionContext.fromExecutorService(executor))
      }
      .map(sent => assertEquals(sent, Got.Blocks(chain.map(_.raw))))
  }
