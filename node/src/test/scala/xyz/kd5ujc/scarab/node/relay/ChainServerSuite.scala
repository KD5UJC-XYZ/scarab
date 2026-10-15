// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.relay

import cats.effect.IO
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Era, NtNHeader}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.network.ChainSyncServer.Update
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef}
import xyz.kd5ujc.scarab.ledger.ChainError
import xyz.kd5ujc.scarab.storage.{FileImmutableStore, StorageTestKit}
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import scala.concurrent.duration.*

import StorageTestKit.*

object RelayKit:
  def ref(n: Int, salt: Int = 0): BlockRef =
    BlockRef(BigInt(n) * 3, hash(n + salt * 100000), BigInt(n))

  /** The "raw block" is the block number and the salt; its header is the same bytes. */
  def raw(n: Int, salt: Int = 0): Bytes = Bytes(Vector(n.toByte, salt.toByte))

  def served(n: Int, salt: Int = 0): Served =
    Served(ref(n, salt), NtNHeader(Era.Conway, raw(n, salt), None), raw(n, salt))

  val headerOf: ChainServer.HeaderOf = bytes =>
    Right(
      ChainServer.Info(NtNHeader(Era.Conway, bytes, None), BigInt(bytes.value.head & 0xff))
    )

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
class ChainServerSuite extends CatsEffectSuite:
  import RelayKit.*

  given Raise[IO, ChainError] = new Raise[IO, ChainError]:
    def functor = cats.Functor[IO]
    def raise[E2 <: ChainError, A](e: E2): IO[A] = IO.raiseError(RuntimeException(e.message))

  private val k = 3

  private def readAll(
      source: xyz.kd5ujc.scarab.network.ChainSyncServer.Source[IO, NtNHeader]
  ): IO[List[Int]] =
    def loop(acc: List[Int]): IO[List[Int]] =
      source.poll.flatMap {
        case Some(Update.RollForward(h, _))  => loop(acc :+ (h.raw.value.head & 0xff))
        case Some(Update.RollBackward(_, _)) => loop(acc)
        case None                            => IO.pure(acc)
      }
    loop(Nil)

  test("a client from Origin reads the immutable blocks and then the volatile ones") {
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer.open[IO](Some(store), None, headerOf)
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
            server <- ChainServer.open[IO](Some(store), None, headerOf)
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
            server <- ChainServer.open[IO](Some(store), None, headerOf)
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

  test("a client waiting at the tip is woken by the next block and by a rollback") {
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer.open[IO](Some(store), None, headerOf)
            persistence = server.persistence[Unit]
            _ <- (1 to 2).toList.traverse_(i =>
              persistence.extended(ref(i), raw(i), Vector.empty, Anchor[Unit](None, ()))
            )
            outcome <- server.source.use { source =>
              for
                _ <- source.intersect(Vector(ref(2).point))
                _ <- source.poll // the roll-back to the intersection
                waiting <- source.await.start
                _ <- IO.sleep(20.millis)
                _ <- persistence.extended(ref(3), raw(3), Vector.empty, Anchor[Unit](None, ()))
                forward <- waiting.joinWithNever
                rollback <- source.await.start
                _ <- IO.sleep(20.millis)
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

  test("block ranges: inside the store, across into the volatile chain, and refusals") {
    tempDir.use { dir =>
      ok {
        FileImmutableStore.open[IO](dir, 30L).use { (store, _) =>
          for
            server <- ChainServer.open[IO](Some(store), None, headerOf)
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
            range = (a: Int, b: Int, max: Int) =>
              blocks.range(ref(a).point, ref(b).point, max).flatMap {
                case None    => IO.pure(None)
                case Some(s) => s.compile.toList.map(_.map(_.value.head.toInt).some)
              }
            inStore <- range(2, 5, 100)
            single <- range(4, 4, 100)
            across <- range(5, 9, 100)
            volatile <- range(8, 10, 100)
            backwards <- range(5, 2, 100)
            unknown <- range(2, 77, 100)
            tooLong <- range(1, 10, 5)
          yield
            assertEquals(inStore, Some(List(2, 3, 4, 5)))
            assertEquals(single, Some(List(4)))
            assertEquals(across, Some(List(5, 6, 7, 8, 9)))
            assertEquals(volatile, Some(List(8, 9, 10)))
            assertEquals(backwards, None)
            assertEquals(unknown, None)
            assertEquals(tooLong, None)
        }
      }
    }
  }
