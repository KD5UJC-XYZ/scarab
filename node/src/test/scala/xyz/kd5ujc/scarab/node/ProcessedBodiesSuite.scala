// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Applicative
import cats.effect.{IO, Ref, Resource}
import cats.mtl.{Handle, Raise, Tell}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node as CborNode, Value}
import xyz.kd5ujc.scarab.consensus.{BlockRef, ChainSelection, LeiosBlockInfo}
import xyz.kd5ujc.scarab.consensus.SelectView
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockFailure,
  BlockPoolProblem,
  BlockTransportError,
  Header,
  HeaderVrf,
  NodeError,
  PeerError,
  StorageError,
  ValidationError
}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.forge.{BlockBuilder, ForgedChain}
import xyz.kd5ujc.scarab.node.log.{EventKind, Line, Log}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.storage.{ChainStorage, ReceivedBlocks}

/** The processed forest keeps what the reference's VolatileDB keeps in memory of a block, its index
  * entry, and reads the body back from the received log (#416). Without a log the body stays in
  * memory. With a log the pool's cache of received bodies holds a body only until its admission is
  * processed (#684).
  */
class ProcessedBodiesSuite extends FailsOnUncaught:
  private val local =
    ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)

  private def blocks(n: Int): IO[Vector[BlockPool.Delivered]] =
    ForgedChain
      .forge(5000 until 5100)
      .flatMap(chain =>
        chain.headers
          .zip(chain.raws)
          .take(n)
          .traverse((h, raw) =>
            BlockPool.Delivered.checked(h, raw).fold(e => IO(fail(e.message)), IO.pure)
          )
      )
      .flatTap(found => IO(assertEquals(found.size, n, "forged blocks")))

  /** A received log over `stored`: `get` hides a refused block, `original` does not. */
  private def journal(
      stored: Ref[IO, Map[Bytes, Bytes]],
      refused: Ref[IO, Set[Bytes]]
  ): BlockPool.Recovered[IO] =
    BlockPool.Recovered(
      stored.get.map(held => hash => held.contains(hash)),
      h => (stored.get, refused.get).mapN((s, r) => s.get(h).filterNot(_ => r(h))),
      h => stored.get.map(_.get(h)),
      h => refused.update(_ + h)
    )

  /** `delivered` admitted through `pool`'s admission queue and processed, as selection does. */
  private def processAll(pool: BlockPool[IO], delivered: Vector[BlockPool.Delivered]): IO[Unit] =
    pool.admissions.resource(_ => IO.unit).use { _ =>
      pool.session.use { sender =>
        sender.claim(delivered.map(_.header.hash)) *> sender.fillDelivered(delivered) *>
          delivered.traverse_(_ =>
            pool.admissions.next.flatMap {
              case BlockAdmissions.Work.Add(admission) =>
                pool.processAdmission(admission) *>
                  pool.completeAdmission(admission.delivered.header.hash)
              case other => IO(fail(s"not an admission: $other"))
            }
          )
      }
    }

  private def cbor(major: Int, n: Long): Array[Byte] =
    if n < 24 then Array((major << 5 | n.toInt).toByte)
    else if n < 0x100 then Array((major << 5 | 24).toByte, n.toByte)
    else if n < 0x10000 then Array((major << 5 | 25).toByte, (n >> 8).toByte, n.toByte)
    else
      Array((major << 5 | 26).toByte, (n >> 24).toByte, (n >> 16).toByte, (n >> 8).toByte, n.toByte)

  /** A Conway block `i` of one transaction whose metadata makes the body about `size` bytes, on the
    * header fields of `base`: its body hash and size are its own, so the fetched-block checks pass.
    */
  private def large(base: Header.Shelley, i: Int, size: Int): IO[BlockPool.Delivered] =
    val chunks = size / 66
    val metadata = cbor(5, 1) ++ cbor(0, 1) ++ cbor(4, chunks.toLong) ++
      (0 until chunks).toArray.flatMap(c => cbor(2, 64) ++ Array.fill(64)((i + c).toByte))
    val input = cbor(4, 2) ++ cbor(2, 32) ++ Array.fill(32)(i.toByte) ++ cbor(0, 0)
    val body = cbor(5, 3) ++ cbor(0, 0) ++ cbor(4, 1) ++ input ++ cbor(0, 1) ++ cbor(4, 0) ++
      cbor(0, 2) ++ cbor(0, 0)
    val piece = BlockBuilder.Piece(
      Bytes(Vector.fill(32)(i.toByte)),
      Bytes.fromArray(body),
      Bytes.fromArray(cbor(5, 0)),
      Some(Bytes.fromArray(metadata)),
      true
    )
    val content = BlockBuilder.body(Vector(piece))
    val vrf = base.vrf match
      case HeaderVrf.Praos(result)     => result
      case HeaderVrf.TPraos(_, leader) => leader
    val raw = BlockBuilder.block(
      BlockBuilder.headerBody(
        BigInt(i + 1),
        BigInt(200000 + i),
        None,
        base.issuerVkey,
        base.vrfVkey,
        vrf,
        content.size,
        content.hash,
        base.opCert,
        base.protocolVersion
      ),
      base.kesSignature,
      content
    )
    Block.decodeShelley(raw) match
      case Right(block: Block.Shelley) =>
        BlockPool.Delivered.checked(block.header, raw).fold(e => IO(fail(e.message)), IO.pure)
      case other => IO(fail(s"not a Shelley-based block: $other"))

  /** `count` large blocks, each about mainnet's maximum body size, 90,112 bytes, with its header.
    */
  private def largeBlocks(count: Int): IO[Vector[BlockPool.Delivered]] =
    blocks(1).map(_.head.header).flatMap {
      case base: Header.Shelley => (0 until count).toVector.traverse(large(base, _, 89500))
      case other                => IO(fail(s"not a Shelley-based header: $other"))
    }

  /** `delivered` admitted through `pool` 's queue by one sender while the worker processes them one
    * by one, as selection does, `prepare` running before each is processed; the pool's residency is
    * read before each admission is taken and once all are done.
    */
  private def admitAll(
      pool: BlockPool[IO],
      delivered: Vector[BlockPool.Delivered],
      prepare: BlockPool.Delivered => IO[Unit]
  ): IO[(Vector[BlockPool.Residency], BlockPool.Residency)] =
    pool.admissions.resource(_ => IO.unit).use { _ =>
      pool.session.use { sender =>
        sender.claim(delivered.map(_.header.hash)) *>
          Resource.make(sender.fillDelivered(delivered).start)(_.cancel).use { filling =>
            for
              seen <- delivered.traverse { d =>
                for
                  held <- pool.residency
                  work <- pool.admissions.next
                  _ <- work match
                    case BlockAdmissions.Work.Add(admission) =>
                      prepare(d) *> pool.processAdmission(admission) *>
                        pool.completeAdmission(admission.delivered.header.hash)
                    case other => IO(fail(s"not an admission: $other"))
                yield held
              }
              _ <- filling.joinWithNever
              after <- pool.residency
            yield seen -> after
          }
      }
    }

  test(
    "with a received log the pool holds only the admission queue's bodies of many large blocks"
  ) {
    for
      delivered <- largeBlocks(64)
      sizes = delivered.map(_.block.size.toLong)
      _ <- IO(
        assert(sizes.min > 89000L && sizes.max < 91000L, s"block sizes ${sizes.min}..${sizes.max}")
      )
      stored <- Ref.of[IO, Map[Bytes, Bytes]](Map.empty)
      refused <- Ref.of[IO, Set[Bytes]](Set.empty)
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), IO.pure(local), journal(stored, refused))
      // the block is in the received log before it is processed (`BlockSelection.Before.prepare`)
      admitted <- admitAll(pool, delivered, d => stored.update(_.updated(d.header.hash, d.block)))
      (seen, after) = admitted
      // the bodies queued and the one the worker has taken
      bound = BlockAdmissions.Capacity + 1
      _ <- IO {
        assert(seen.forall(_.bodies <= bound), seen.map(_.bodies).toString)
        assert(seen.forall(_.bytes <= bound * sizes.max), seen.map(_.bytes).toString)
        assertEquals(after, BlockPool.Residency(0, 0L))
      }
      processed <- pool.processedSnapshot
      taken <- delivered.traverse(d => pool.takeFilled(d.header.hash))
    yield
      assertEquals(processed.keySet, delivered.map(_.header.hash).toSet)
      assertEquals(
        taken.map(_.map(_.block)),
        delivered.map(d => Some(d.block)),
        "read from the log"
      )
  }

  test("without a received log the pool holds every processed body, each counted once") {
    for
      delivered <- largeBlocks(16)
      pool <- BlockPool.create[IO]
      admitted <- admitAll(pool, delivered, _ => IO.unit)
      (_, after) = admitted
    yield assertEquals(
      after,
      BlockPool.Residency(delivered.size, delivered.map(_.block.size.toLong).sum)
    )
  }

  test("with a received log a refused processed block is not pinned in memory and stays readable") {
    for
      delivered <- blocks(1)
      block = delivered.head
      stored <- Ref.of[IO, Map[Bytes, Bytes]](Map(block.header.hash -> block.block))
      refused <- Ref.of[IO, Set[Bytes]](Set.empty)
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), IO.pure(local), journal(stored, refused))
      _ <- processAll(pool, delivered)
      processed <- pool.processedSnapshot
      p = processed(block.header.hash)
      refusal = ValidationError(block.header.point, BlockFailure.EpochPrecedesLedger(0))
      result <- pool.invalidated(
        BlockPool.Taken(block.block, false, p.origin, Some(p.delivered)),
        block.header,
        refusal
      )
      held <- pool.residency
      invalid <- pool.invalidBlocks.snapshot
      read <- pool.take(block.header.hash)
    yield
      assertEquals(result, Right(()))
      assertEquals(held, BlockPool.Residency(0, 0L))
      assertEquals(invalid.entries, Map(block.header.hash -> refusal))
      assertEquals(read, Some(block.block), "read back from the log through the forest")
  }

  test("with a received log a processed block keeps no body and reads it back, refused or not") {
    for
      delivered <- blocks(2)
      stored <- Ref.of[IO, Map[Bytes, Bytes]](delivered.map(d => d.header.hash -> d.block).toMap)
      refused <- Ref.of[IO, Set[Bytes]](Set.empty)
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), IO.pure(local), journal(stored, refused))
      _ <- processAll(pool, delivered)
      processed <- pool.processedSnapshot
      _ <- IO(assertEquals(processed.keySet, delivered.map(_.header.hash).toSet))
      _ <- IO(assert(processed.values.forall(_.held.isEmpty), "the forest holds no body"))
      first = delivered.head
      read <- pool.body(first.header.hash, processed(first.header.hash))
      _ <- IO(assertEquals(read, Some(first.block)))
      _ <- refused.update(_ + first.header.hash)
      afterRefusal <- pool.body(first.header.hash, processed(first.header.hash))
      _ <- IO(assertEquals(afterRefusal, Some(first.block), "a refused original stays readable"))
      taken <- pool.takeFilled(delivered(1).header.hash)
    yield
      assertEquals(taken.map(_.block), Some(delivered(1).block), "a claim is filled from the log")
      assertEquals(taken.flatMap(_.metadata).map(_.header.hash), Some(delivered(1).header.hash))
  }

  test("without a received log a processed block keeps its body in memory") {
    for
      delivered <- blocks(2)
      pool <- BlockPool.create[IO]
      _ <- processAll(pool, delivered)
      processed <- pool.processedSnapshot
      bodies <- delivered.traverse(d => pool.body(d.header.hash, processed(d.header.hash)))
    yield
      assertEquals(processed.values.map(_.held.isDefined).toSet, Set(true))
      assertEquals(bodies, delivered.map(d => Some(d.block)))
  }

  test("blocks seeded from the received log are kept as metadata and read back from it") {
    for
      delivered <- blocks(3)
      stored <- Ref.of[IO, Map[Bytes, Bytes]](delivered.map(d => d.header.hash -> d.block).toMap)
      refused <- Ref.of[IO, Set[Bytes]](Set.empty)
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), IO.pure(local), journal(stored, refused))
      _ <- pool.seedRecoveredMetadata(delivered.map(_.metadata))
      processed <- pool.processedSnapshot
      bodies <- delivered.traverse(d => pool.body(d.header.hash, processed(d.header.hash)))
    yield
      assert(processed.values.forall(p => p.held.isEmpty && p.origin == BlockPool.Source.Recovered))
      assertEquals(bodies, delivered.map(d => Some(d.block)))
  }

  /** `raw` with its empty witness container written the other way (a definite empty array for an
    * indefinite one, or the reverse): the block decodes and its header is unchanged, but its body
    * no longer hashes to what its header commits to (`BlockTransportError.WrongBodyHash`).
    */
  private def bodyNotItsHeaders(raw: Bytes): Bytes =
    def array(node: CborNode): Vector[CborNode] = node.value match
      case Value.Arr(items) => items
      case other            => fail(s"expected an array, got $other")
    val witnesses = array(array(Cbor.decode(raw).fold(e => fail(e.message), identity))(1))(2)
    assertEquals(array(witnesses), Vector.empty, "a forged block without transactions")
    val other =
      if witnesses.original == Bytes(Vector(0x80.toByte)) then
        Bytes(Vector(0x9f.toByte, 0xff.toByte))
      else Bytes(Vector(0x80.toByte))
    raw.take(witnesses.offset) ++ other ++ raw.drop(witnesses.offset + witnesses.original.size)

  private def ref(header: Header): BlockRef =
    BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)

  /** A `Log` that keeps every line. */
  private def collecting(lines: Ref[IO, Vector[Line]]): Log[IO] = new Tell[IO, Line]:
    def functor = Applicative[IO]
    def tell(line: Line) = lines.update(_ :+ line)

  private val epochSlots = ForgedChain.geometry.byron.epochSlots

  /** A check that accepts every record: it stands in for the start-up check
    * ([[ReceivedBlockRecovery.startUp]]), so that the log can hold what that check refuses.
    */
  private val acceptAll: ReceivedBlocks.Check = (_, _) => Right(())

  private def opened(dir: Path, check: ReceivedBlocks.Check)(using
      Raise[IO, StorageError]
  ): Resource[IO, ReceivedBlocks[IO]] =
    ReceivedBlocks.open[IO](dir)(ChainStorage.predecessorOf(epochSlots), check)

  private def recovered(log: ReceivedBlocks[IO])(using Raise[IO, StorageError]) =
    BlockPool.Recovered[IO](
      log.holds,
      hash => log.get(hash).map(_.map(_._2)),
      hash => log.original(hash).map(_.map(_._2)),
      log.invalidate
    )

  test(
    "seeding from the received log reads each block's header and does not check its body again"
  ) {
    // the log holds what the start-up check refuses (acceptAll): a block whose body is not its
    // header's, and bytes that are no block. Seeding checks neither body again.
    Files.forAsync[IO].tempDirectory.use { dir =>
      Handle
        .allow[NodeError] {
          for
            delivered <- blocks(2)
            (sound, other) = (delivered(0), delivered(1))
            wrong = bodyNotItsHeaders(other.block)
            noBlock = BlockRef(
              other.header.slot + 1,
              Bytes(Vector.fill(32)(7.toByte)),
              other.header.blockNo + 1,
              false
            )
            _ <- IO(BlockPool.Delivered.checked(other.header, wrong) match
              case Left(
                    PeerError.ProtocolProblem.FetchedBlockIntegrity(
                      BlockTransportError.WrongBodyHash(_, _)
                    )
                  ) =>
                ()
              case result => fail(s"the integrity check takes the changed body: $result"))
            _ <- opened(dir, acceptAll).use(log =>
              log.put(ref(sound.header), sound.block) *> log.put(ref(other.header), wrong) *>
                log.put(noBlock, Bytes(Vector(0xff.toByte)))
            )
            lines <- Ref.of[IO, Vector[Line]](Vector.empty)
            seeded <- opened(dir, acceptAll).use { log =>
              given Log[IO] = collecting(lines)
              for
                pool <- BlockPool
                  .create[IO](BlockPool.FetchPolicy(), IO.pure(local), recovered(log))
                _ <- Main.seedReceived(pool, Some(log), epochSlots)
                processed <- pool.processedSnapshot
              yield processed
            }
            logged <- lines.get
          yield
            assertEquals(
              seeded.keySet,
              Set(sound.header.hash, other.header.hash),
              "the blocks the open accepted are seeded, the one whose body is not its header's too"
            )
            assertEquals(
              seeded.get(other.header.hash).map(_.delivered.view),
              Some(other.metadata.view),
              "from its header"
            )
            assertEquals(
              logged.map(line => line.kind -> line.fields.toMap.get("hash").flatMap(_.asString)),
              Vector(EventKind.ReceivedBlockDiscarded -> Some(noBlock.hash.hex)),
              "only the record whose header does not decode is discarded, and logged"
            )
        }
        .rescue(error => IO(fail(error.message)))
    }
  }

  test(
    "the start adds a block of the selected chain to the received log only once it passes the check"
  ) {
    // a resume does not check all of a block (a Byron boundary block's body is not decoded): a
    // resumed block whose body is not its header's stands in for one, so that seeding, which does
    // not check a body again, never meets a block the log's start-up check did not pass
    Files.forAsync[IO].tempDirectory.use { dir =>
      Handle
        .allow[NodeError] {
          for
            delivered <- blocks(3)
            (held, sound, other) = (delivered(0), delivered(1), delivered(2))
            wrong = bodyNotItsHeaders(other.block)
            _ <- opened(dir, acceptAll).use(_.put(ref(held.header), held.block))
            lines <- Ref.of[IO, Vector[Line]](Vector.empty)
            found <- opened(dir, ReceivedBlockRecovery.startUp(epochSlots)).use { log =>
              given Log[IO] = collecting(lines)
              val resumed = Vector(
                ref(held.header) -> held.block,
                ref(sound.header) -> sound.block,
                ref(other.header) -> wrong
              )
              for
                _ <- Main.restoreReceived(log, resumed, epochSlots)
                pool <- BlockPool
                  .create[IO](BlockPool.FetchPolicy(), IO.pure(local), recovered(log))
                _ <- Main.seedReceived(pool, Some(log), epochSlots)
                kept <- log.refs
                processed <- pool.processedSnapshot
              yield (kept.map(_.hash).toSet, processed.keySet)
            }
            (kept, seeded) = found
            logged <- lines.get
          yield
            val accepted = Set(held.header.hash, sound.header.hash)
            assertEquals(kept, accepted, "the block whose body is not its header's is not added")
            assertEquals(seeded, accepted, "and the pool is not seeded with it")
            assertEquals(
              logged.map(line => line.kind -> line.fields.toMap.get("hash").flatMap(_.asString)),
              Vector(EventKind.ReceivedBlockDiscarded -> Some(other.header.hash.hex)),
              "its refusal is logged"
            )
        }
        .rescue(error => IO(fail(error.message)))
    }
  }

  test("a processed block the log can no longer read leaves the forest and can be fetched again") {
    // a block seeded from the log at start: the pool's own cache never held its body
    for
      delivered <- blocks(2)
      stored <- Ref.of[IO, Map[Bytes, Bytes]](delivered.map(d => d.header.hash -> d.block).toMap)
      refused <- Ref.of[IO, Set[Bytes]](Set.empty)
      pool <- BlockPool
        .create[IO](BlockPool.FetchPolicy(), IO.pure(local), journal(stored, refused))
      _ <- pool.seedRecoveredMetadata(delivered.map(_.metadata))
      lost = delivered.head.header.hash
      before <- pool.processedSnapshot
      // the log dropped the record (a failed checksum): it no longer returns it
      _ <- stored.update(_ - lost)
      read <- pool.body(lost, before(lost))
      after <- pool.processedSnapshot
      claimed <- pool.session.use(_.claim(Vector(lost)))
    yield
      assertEquals(read, None)
      assert(!after.contains(lost), "the block left the forest")
      assert(after.contains(delivered(1).header.hash), "the others stay")
      assertEquals(claimed, Vector(true), "the block can be claimed and fetched again")
  }

  test("a recovered block whose predecessors lead back to it is refused, the forest unchanged") {
    def hash(id: Int) =
      Bytes.fromArray(Array.tabulate[Byte](32)(i => if i == 31 then id.toByte else 0))
    def metadata(id: Int, parent: Int, slot: Int) =
      BlockPool.DeliveredHeader
        .checkedAtOpen(
          Header
            .UnsizedByron(hash(id), hash(id), false, 0, slot, 21600, slot, Some(hash(parent)))
            .withSizeHint(FetchTesting.sizeHint(1)),
          LeiosBlockInfo.Absent
        )
        .fold(e => fail(e.toString), identity)
    for
      pool <- BlockPool.create[IO]
      first <- pool.seedRecoveredMetadata(Vector(metadata(1, 2, 1)))
      before <- pool.processedSnapshot
      closing <- pool.seedRecoveredMetadata(Vector(metadata(3, 9, 3), metadata(2, 1, 2)))
      after <- pool.processedSnapshot
    yield
      assertEquals(first, Right(()))
      assertEquals(closing, Left(BlockPoolProblem.AvailableCycle(hash(2))))
      assertEquals(after, before, "a refused seeding adds nothing")
  }
