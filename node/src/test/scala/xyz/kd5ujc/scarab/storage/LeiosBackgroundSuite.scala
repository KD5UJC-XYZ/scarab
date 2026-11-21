// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, Outcome, Ref}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import java.nio.file.{Files as JFiles, Path as JPath}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRef, BlockRules, LeiosBlockInfo, LeiosCert}
import xyz.kd5ujc.scarab.ledger.{Block, NodeError, StorageError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.ReceivedBlockRecovery
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, HfcHeader}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import fs2.io.file.Path

/** The chain database drives the Leios store as ouroboros-consensus 2416493b's background drives
  * its LeiosDB (`Storage/ChainDB/Impl/Background.hs`): a copy to the immutable store promotes the
  * endorser block that a copied ranking block certifies, the one its predecessor announced
  * (`copyToImmutableDB` and `certifiedEb`, 230-310), and each collection of the received log
  * collects the store at its slot (`garbageCollectBlocks`, 547-559). The storage runs under
  * TestControl, as [[ReceivedCollectionSuite]] runs it; the store is read through its public
  * operations only.
  */
class LeiosBackgroundSuite extends FailsOnUncaught:
  import LeiosStoreSuite.{body, hash, ok as stored, tx, word32}

  private given SnapshotFormat[IO, Int] with
    def encode(snapshot: StateSnapshot[Int]) = IO.pure(Right(Array(snapshot.state.toByte)))
    def decode(content: Array[Byte]) =
      IO.pure(
        Right(StateSnapshot(None, content.headOption.fold(0)(_.toInt), CheckpointBinding.Unbacked))
      )

  private val rules: BlockRules[IO, Int] = BlockRules.from((_, _, _) => IO.pure(0))

  // no Byron block is read
  private val epochSlots = BigInt(21600)

  private val testnet = JPath.of("fixtures", "leios", "testnet")

  private def rows(file: String): Vector[Array[String]] =
    JFiles
      .readAllLines(testnet.resolve(file))
      .asScala
      .toVector
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map(_.split('\t'))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private def labelled(file: String, label: String): Bytes =
    rows(file).find(_(0) == label).fold(fail(s"no $label in $file"))(r => hex(r(5)))

  /** Block 64,850 of the Leios testnet, which certifies the endorser block block 64,849 announced
    * (`fixtures/leios/testnet`, provenance in `fixtures/leios/README.md`).
    */
  private val certifying: Bytes = labelled("blocks-rc-2026w44.tsv", "certifying")

  /** Block 64,849: its recorded header, which announces the endorser block, in a Dijkstra block
    * `[8, [header, [transactions, leios_cert, peras_cert]]]` with an empty body, no transaction and
    * no certificate. Only the header of that block is recorded; nothing checks the body against the
    * header's body hash here, as the rules accept any block.
    */
  private val announcing: Bytes =
    val header = HfcHeader.decode(labelled("headers-notable.tsv", "announcing")) match
      case Right(HfcHeader.Dijkstra(raw)) => raw
      case other                          => fail(s"not a Dijkstra header: $other")
    Bytes(Vector(0x82, 0x08, 0x82).map(_.toByte)) ++ header ++
      Bytes(Vector(0x83, 0x80, 0xf6, 0xf6).map(_.toByte))

  private def decoded(raw: Bytes): Block =
    Block.decode(raw, epochSlots).fold(e => fail(e.message), identity)

  private def refOf(block: Block): BlockRef =
    BlockRef(block.header.slot, block.header.hash, block.header.blockNo, block.header.isBoundary)

  private val announcer = decoded(announcing)
  private val certifier = decoded(certifying)
  private val announcerRef = refOf(announcer)
  private val certifierRef = refOf(certifier)

  private def word64(n: BigInt): Word64 = Word64.from(n).fold(e => fail(e.toString), identity)

  /** The endorser block block 64,850 certifies: block 64,849's announcement, at its slot. */
  private val certified: EbPoint =
    EbPoint(
      word64(announcerRef.slot),
      LeiosBlockInfo.of(announcer).announced.getOrElse(fail("block 64,849 announces nothing"))
    )

  /** An endorser block older than the collection's slot that nothing certifies. */
  private val unvetoed = EbPoint(word64(announcerRef.slot - 18), hash(1))

  /** An endorser block at a slot after the collection's. */
  private val later = EbPoint(word64(certifierRef.slot + 1), hash(2))

  private val config = ChainStorage.Config(
    SnapshotPolicy.EverySlots(1_000_000),
    byronEpochSlots = epochSlots
  )

  private def typed[A](run: Handle[IO, NodeError] ?=> IO[A]): IO[A] =
    Handle.allow[NodeError](run).rescue(error => IO(fail(error.message)))

  /** What the Leios store holds of the three endorser blocks, read through its operations. */
  private final case class Held(
      points: Vector[EbPoint],
      bodies: Vector[Boolean],
      closures: Vector[Boolean]
  )

  private def held(store: LeiosStore[IO]): IO[Held] =
    stored(
      (
        store.scanEbPoints,
        Vector(certified, unvetoed, later).traverse(p =>
          store.lookupEbBody(p.hash).map(_.isDefined)
        ),
        Vector(certified, unvetoed, later).traverse(p =>
          store.lookupEbClosure(p.hash).map(_.isDefined)
        )
      ).mapN(Held.apply)
    )

  /** A step of the program that holds the storage and the Leios store open. */
  private type Step = (ChainStorage.Recovered[IO, Int], LeiosStore[IO]) => IO[Unit]

  /** The storage and the Leios store in a temporary directory under TestControl, the store holding
    * the three endorser blocks, each with its body and its one transaction. `within` gets a way to
    * run a step at the virtual time now and one to move the clock.
    */
  private def node[A](
      within: (Step => IO[Unit], FiniteDuration => IO[Unit]) => IO[A]
  ): IO[A] =
    StorageTestKit.tempDir.use { dir =>
      for
        steps <- Queue.unbounded[IO, Option[Step]]
        session = LeiosStoreLocks
          .resource[IO]
          .flatMap(locks =>
            LeiosStoreSuite.allowing(RocksLeiosStore.resource[IO](dir / "leios", locks))
          )
        program = session.use { leios =>
          stored(Vector(certified, unvetoed, later).traverse_ { p =>
            leios.writeEbPoint(p, word32(1)) *> leios.writeEbBody(p, body(1), Vector.empty) *>
              leios.writeTxs(p, Vector(0 -> tx(0)))
          }) *> typed(
            ChainStorage
              .open[IO, Int](
                dir,
                config,
                rules,
                0,
                _ => IO.unit,
                StateBacking.none[IO, Int],
                _ => IO.unit,
                leios = Some(leios)
              )(ReceivedBlockRecovery.startUp(epochSlots))
              .use { recovered =>
                def run: IO[Unit] =
                  steps.take.flatMap(_.traverse_(step => step(recovered, leios) *> run))
                run
              }
          )
        }
        control <- TestControl.execute(program)
        _ <- control.tick
        act = (step: Step) => steps.offer(Some(step)) *> control.tick
        advance = (by: FiniteDuration) => control.tick *> control.advanceAndTick(by)
        result <- within(act, advance).guarantee(steps.offer(None) *> control.tickAll)
        ended <- control.results
        _ <- IO(assertEquals(ended, Some(Outcome.succeeded[cats.Id, Throwable, Unit](()))))
      yield result
    }

  /** The blocks, each applied and made immutable in the extension that applies it. */
  private def copied(blocks: Vector[(BlockRef, Bytes)]): Step = (recovered, _) =>
    typed(blocks.zipWithIndex.traverse_ { case ((ref, raw), i) =>
      recovered.persistence.extended(ref, raw, Vector(ref), Anchor(Some(ref), i))
    })

  /** Both blocks made immutable in the one extension of the certifying block. */
  private val together: Step = (recovered, _) =>
    typed(
      recovered.persistence.extended(announcerRef, announcing, Vector.empty, Anchor(None, 0)) *>
        recovered.persistence.extended(
          certifierRef,
          certifying,
          Vector(announcerRef, certifierRef),
          Anchor(Some(certifierRef), 1)
        )
    )

  private def read(into: Ref[IO, Option[Held]]): Step = (_, leios) =>
    held(leios).flatMap(h => into.set(Some(h)))

  test(
    "the recorded blocks: block 64,850 certifies what its predecessor, block 64,849, announced"
  ) {
    assertEquals(certifier.header.prevHash, Some(announcerRef.hash))
    assertEquals(LeiosBlockInfo.of(certifier).cert, LeiosCert.Carried)
    assertEquals(LeiosBlockInfo.of(announcer).cert, LeiosCert.Absent)
    assert(unvetoed.slot.toBigInt < announcerRef.slot && later.slot.toBigInt > certifierRef.slot)
  }

  Vector(
    "in an extension after its predecessor's" ->
      copied(Vector(announcerRef -> announcing, certifierRef -> certifying)),
    "in the extension that copies its predecessor too" -> together
  ).foreach { (how, copy) =>
    test(
      s"a certifying block copied $how promotes its endorser block, which the collection keeps"
    ) {
      for
        before <- Ref.of[IO, Option[Held]](None)
        early <- Ref.of[IO, Option[Held]](None)
        after <- Ref.of[IO, Option[Held]](None)
        _ <- node { (act, advance) =>
          act(copy) *> act(read(before)) *> advance(60.seconds - 1.nanosecond) *>
            act(read(early)) *> advance(1.nanosecond) *> act(read(after))
        }
        promoted <- before.get
        due <- early.get
        collected <- after.get
      yield
        val all =
          Held(Vector(unvetoed, certified, later), Vector.fill(3)(true), Vector.fill(3)(true))
        assertEquals(promoted, Some(all), "the promotion collects nothing")
        assertEquals(due, Some(all), "nothing is collected before the collection's 60 s")
        // the collection at the certifying block's slot: the endorser block nothing certifies goes,
        // the promoted one leaves the volatile partition and the immutable one keeps its body and
        // closure, and the one at a later slot stays
        assertEquals(
          collected,
          Some(Held(Vector(later), Vector(true, false, true), Vector(true, false, true)))
        )
    }
  }

  test("without a certifying block, the collection at its slot takes every older endorser block") {
    for
      after <- Ref.of[IO, Option[Held]](None)
      _ <- node { (act, advance) =>
        act(copied(Vector(announcerRef -> announcing))) *> advance(60.seconds) *> act(read(after))
      }
      collected <- after.get
    yield
      // the collection at block 64,849's slot: its own announcement is not older than the slot
      assertEquals(
        collected,
        Some(Held(Vector(certified, later), Vector(true, false, true), Vector(true, false, true)))
      )
  }

  /** A failure of the Leios store, as a write that fails. */
  private val boom = new java.io.IOException("the Leios store's write failed")

  /** `store`, its promotions of any endorser block failing with [[boom]]. */
  private def failing(store: LeiosStore[IO]): LeiosStore[IO] = new LeiosStore[IO]:
    def writeEbPoint(point: EbPoint, announced: Word32)(using Raise[IO, LeiosStoreError]) =
      store.writeEbPoint(point, announced)
    def writeEbBody(point: EbPoint, eb: LeiosEb, fills: Vector[LocalFill])(using
        Raise[IO, LeiosStoreError]
    ) = store.writeEbBody(point, eb, fills)
    def writeTxs(point: EbPoint, txs: Vector[(Int, Bytes)])(using Raise[IO, LeiosStoreError]) =
      store.writeTxs(point, txs)
    def lookupEbBody(hash: EbHash)(using Raise[IO, LeiosStoreError]) = store.lookupEbBody(hash)
    def lookupEbClosure(hash: EbHash)(using Raise[IO, LeiosStoreError]) =
      store.lookupEbClosure(hash)
    def batchRetrieveTxs(hash: EbHash, offsets: Vector[Int])(using Raise[IO, LeiosStoreError]) =
      store.batchRetrieveTxs(hash, offsets)
    def scanEbPoints(using Raise[IO, LeiosStoreError]) = store.scanEbPoints
    def scanCompleteEbClosuresNotOlderThanSlot(slot: Word64)(using Raise[IO, LeiosStoreError]) =
      store.scanCompleteEbClosuresNotOlderThanSlot(slot)
    def garbageCollect(slot: Word64)(using Raise[IO, LeiosStoreError]) = store.garbageCollect(slot)
    def promoteToImmutable(points: Vector[EbPoint])(using raise: Raise[IO, LeiosStoreError]) =
      if points.isEmpty then store.promoteToImmutable(points)
      else raise.raise(LeiosStoreError.Io(boom))

  /** The snapshot files in `dir` deleted: this suite's states are not in a format a start reads. */
  private def snapshotsDropped(dir: Path): IO[Unit] =
    IO.blocking(
      scala.util.Using
        .resource(JFiles.list((dir / "snapshots").toNioPath))(
          _.iterator.asScala.toVector
        )
        .foreach(JFiles.delete)
    )

  test(
    "an endorser block whose promotion failed after its certifier's copy is promoted at the next " +
      "open, so a collection past the certifier keeps it"
  ) {
    StorageTestKit.tempDir.use { dir =>
      val leiosStore = LeiosStoreLocks
        .resource[IO]
        .flatMap(locks =>
          LeiosStoreSuite.allowing(RocksLeiosStore.resource[IO](dir / "leios", locks))
        )
      def opened(leios: LeiosStore[IO])(using Handle[IO, NodeError]) =
        ChainStorage.open[IO, Int](
          dir,
          config,
          rules,
          0,
          _ => IO.unit,
          StateBacking.none[IO, Int],
          _ => IO.unit,
          leios = Some(leios)
        )(ReceivedBlockRecovery.startUp(epochSlots))
      for
        // the first run: both blocks are copied, and the certifier's promotion fails after its
        // append, which stops the storage
        failed <- leiosStore.use { leios =>
          stored(Vector(certified, unvetoed, later).traverse_ { p =>
            leios.writeEbPoint(p, word32(1)) *> leios.writeEbBody(p, body(1), Vector.empty) *>
              leios.writeTxs(p, Vector(0 -> tx(0)))
          }) *> Handle
            .allow[NodeError](
              opened(failing(leios))
                .use(recovered =>
                  recovered.persistence.extended(
                    announcerRef,
                    announcing,
                    Vector(announcerRef),
                    Anchor(Some(announcerRef), 0)
                  ) *> recovered.persistence.extended(
                    certifierRef,
                    certifying,
                    Vector(certifierRef),
                    Anchor(Some(certifierRef), 1)
                  )
                )
                .as(Option.empty[NodeError])
            )
            .rescue(e => IO.pure(Some(e)))
        }
        _ <- snapshotsDropped(dir)
        // the next run opens both stores again, then the store is collected past the certifier
        reopened <- leiosStore.use { leios =>
          typed(opened(leios).use(_.store.tip)).flatMap(tip =>
            stored(leios.garbageCollect(word64(certifierRef.slot + 1))) *> held(leios).map(tip -> _)
          )
        }
      yield
        assertEquals(failed, Some(StorageError.LeiosStore(LeiosStoreError.Io(boom))))
        assertEquals(reopened._1, Some(certifierRef), "the certifier was copied before it failed")
        // the certified endorser block's body and closure are in the immutable partition; the one
        // nothing certifies is collected, and the one at the collection's slot stays
        assertEquals(
          reopened._2,
          Held(Vector(later), Vector(true, false, true), Vector(true, false, true))
        )
    }
  }

  test("a block certifies nothing without a certificate, on genesis, or after no announcement") {
    import LeiosBackground.{Info, certifiedEb}
    val eb = hash(3)
    val announced = LeiosBlockInfo(LeiosCert.Absent, Some(eb))
    val carried = LeiosBlockInfo(LeiosCert.Carried, None)
    def h(i: Int) = Bytes(Vector.fill(32)(i.toByte))
    val blocks = Vector(
      Info(h(1), 10, None, announced),
      Info(h(2), 11, Some(h(1)), carried),
      Info(h(3), 12, Some(h(1)), LeiosBlockInfo(LeiosCert.Absent, None)),
      Info(h(4), 13, None, carried),
      Info(h(5), 14, Some(h(9)), carried),
      Info(h(6), 15, Some(h(3)), carried),
      Info(h(7), Word64.Max + 1, None, announced),
      Info(h(8), Word64.Max + 2, Some(h(7)), carried)
    ).map(i => i.hash -> i).toMap
    val certifies = certifiedEb(blocks.get)
    assertEquals(certifies(h(2)), Some(EbPoint(word64(10), eb)), "its predecessor's announcement")
    assertEquals(certifies(h(3)), None, "no certificate")
    assertEquals(certifies(h(4)), None, "a certifying block on genesis")
    assertEquals(certifies(h(5)), None, "a predecessor not held")
    assertEquals(certifies(h(6)), None, "a predecessor that announced nothing")
    assertEquals(certifies(h(8)), None, "a predecessor at a slot past a Word64")
    assertEquals(certifies(h(9)), None, "a block not held")
  }
