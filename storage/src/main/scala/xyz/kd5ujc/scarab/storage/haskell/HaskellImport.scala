// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import cats.Functor
import cats.effect.{Async, Clock}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, NetworkIdentity, StorageError}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.storage.{
  DatabaseOwnership,
  FileImmutableStore,
  ImmutableStore,
  NetworkIdentityFile
}
import scala.concurrent.duration.FiniteDuration

/** Why a Haskell ImmutableDB was not imported. */
enum HaskellImportError:
  /** The source database cannot be read or fails its own validation. */
  case Source(error: HaskellImmutableError)

  /** Our store cannot take the blocks. */
  case Target(error: StorageError)

  case MagicFileMissing(path: String)
  case MagicFileMalformed(path: String, text: String)

  /** The database was written for another network (`protocolMagicId`). */
  case MagicMismatch(found: Long, expected: Long)

  /** A block that does not decode. */
  case Undecodable(slot: Long, reason: String)

  /** The block's own header hash differs from the hash the index lists. */
  case HeaderHashDisagrees(slot: Long, indexed: String, computed: String)

  case SlotDisagrees(indexed: Long, block: BigInt)

  /** A block that does not name its predecessor, or breaks the block numbering. */
  case BrokenLink(slot: Long, expected: String, found: String)
  case BlockNumberJump(slot: Long, expected: BigInt, found: BigInt)

  /** The first block does not follow the configured genesis: another network or genesis. */
  case ForeignGenesis(found: String, expected: String)

  /** The store holds a chain that the source does not contain. */
  case TargetNotInSource(slot: BigInt, hash: String)

  def message: String = this match
    case Source(e)                => e.message
    case Target(e)                => e.message
    case MagicFileMissing(p)      => s"$p does not exist: not a Haskell node database"
    case MagicFileMalformed(p, t) => s"$p does not hold a protocol magic: '$t'"
    case MagicMismatch(f, e) =>
      s"the Haskell database was written for protocol magic $f, this node is configured for $e"
    case Undecodable(s, r) => s"block at slot $s does not decode: $r"
    case HeaderHashDisagrees(s, i, c) =>
      s"block at slot $s: the index lists hash $i, the block's header hashes to $c"
    case SlotDisagrees(i, b) => s"the index places a block at slot $i, its header says $b"
    case BrokenLink(s, e, f) =>
      s"block at slot $s names $f as its predecessor, expected $e"
    case BlockNumberJump(s, e, f) => s"block at slot $s has number $f, expected $e"
    case ForeignGenesis(f, e) =>
      s"the first block names $f as its predecessor, the configured genesis requires $e: another network or genesis"
    case TargetNotInSource(s, h) =>
      s"the target database ends at slot $s (${h.take(16)}), which the Haskell database does not contain"

object HaskellImport:
  final case class Config(
      /** The Haskell node's database directory (holding `immutable/` and `protocolMagicId`). */
      haskellDb: Path,
      /** Our database directory (`--db`). */
      db: Path,
      identity: NetworkIdentity,
      byronEpochSlots: BigInt,
      chunkSlots: ChunkSlots,
      /** Stop after the last block at or before this slot. */
      stopSlot: Option[Long] = None,
      /** Blocks between two forced syncs and two progress reports. */
      batch: Int = 10000
  )

  final case class Progress(appended: Long, skipped: Long, chunks: Int, slot: Long)

  final case class Report(
      appended: Long,
      skipped: Long,
      chunks: Int,
      tip: Option[BlockRef],
      /** The source went on past `stopSlot`. */
      stoppedAtSlot: Boolean,
      /** What was ignored at the end of the source's most recent chunk, if anything. */
      truncation: Option[(ChunkNo, Truncation)],
      elapsed: FiniteDuration
  )

  private enum Phase:
    /** The target already holds blocks: wait for its tip in the source. */
    case Skipping(tip: BlockRef)
    case Linking(previous: Option[BlockRef])

  private final case class State(
      phase: Phase,
      appended: Long,
      skipped: Long,
      identityKnown: Boolean
  )

  def run[F[_]: Async](config: Config, progress: Progress => F[Unit])(using
      outer: Raise[F, HaskellImportError]
  ): F[Report] =
    val F = Async[F]
    given Raise[F, StorageError] = new Raise[F, StorageError]:
      def functor: Functor[F] = F
      def raise[E2 <: StorageError, A](e: E2): F[A] = outer.raise(HaskellImportError.Target(e))
    given Raise[F, HaskellImmutableError] = new Raise[F, HaskellImmutableError]:
      def functor: Functor[F] = F
      def raise[E2 <: HaskellImmutableError, A](e: E2): F[A] =
        outer.raise(HaskellImportError.Source(e))

    def fail[A](e: HaskellImportError): F[A] = e.raise[F, A]
    val files = Files.forAsync[F]
    val expectedPrevious = config.identity.byronGenesis

    def show(h: Option[Bytes]): String = h.fold("no predecessor")(_.hex)

    val readMagic: F[Long] =
      val file = config.haskellDb / "protocolMagicId"
      files.exists(file).flatMap {
        case false => fail(HaskellImportError.MagicFileMissing(file.toString))
        case true =>
          files.readUtf8(file).compile.string.map(_.trim).flatMap { text =>
            text.toLongOption.fold(
              fail[Long](HaskellImportError.MagicFileMalformed(file.toString, text.take(40)))
            )(F.pure)
          }
      }

    def decode(b: HaskellBlock): F[(Block, BlockRef)] =
      Block.decode(b.bytes, config.byronEpochSlots) match
        case Left(e) => fail(HaskellImportError.Undecodable(b.slot, e.message))
        case Right(block) =>
          val h = block.header
          for
            _ <- F.unlessA(h.hash == b.hash)(
              fail[Unit](HaskellImportError.HeaderHashDisagrees(b.slot, b.hash.hex, h.hash.hex))
            )
            _ <- F.unlessA(h.slot == BigInt(b.slot))(
              fail[Unit](HaskellImportError.SlotDisagrees(b.slot, h.slot))
            )
          yield (block, BlockRef(h.slot, h.hash, h.blockNo, h.isBoundary))

    /** What the first block already stored names as its predecessor, for a store that predates the
      * identity file.
      */
    def firstPredecessor(store: ImmutableStore[F]): F[Option[Option[Bytes]]] =
      store.stream(xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin).take(1).compile.last.flatMap {
        case None => F.pure(None)
        case Some(stored) =>
          Block.decode(stored.raw, config.byronEpochSlots) match
            case Right(b) => F.pure(Some(b.header.prevHash))
            case Left(e) =>
              StorageError
                .Corrupt("first immutable block", e.message)
                .raise[F, Option[Option[Bytes]]]
      }

    DatabaseOwnership.resource[F](config.db).use { _ =>
      for
        began <- Clock[F].monotonic
        magic <- readMagic
        _ <- F.unlessA(magic == config.identity.magic)(
          fail[Unit](HaskellImportError.MagicMismatch(magic, config.identity.magic))
        )
        recorded <- NetworkIdentityFile.verify[F](config.db, config.identity)
        reader = HaskellImmutableReader[F](config.haskellDb / "immutable", config.chunkSlots)
        truncation <- cats.effect.Ref.of[F, Option[(ChunkNo, Truncation)]](None)
        chunkCount <- cats.effect.Ref.of[F, Int](0)
        beyond <- cats.effect.Ref.of[F, Boolean](false)
        result <- FileImmutableStore.open[F](config.db / "immutable").use { (store, _) =>
          def step(st: State, b: HaskellBlock): F[State] =
            decode(b).flatMap { (block, ref) =>
              val h = block.header
              st.phase match
                case Phase.Skipping(tip) =>
                  if ref.hash == tip.hash then
                    F.pure(st.copy(phase = Phase.Linking(Some(tip)), skipped = st.skipped + 1))
                  else if ref.slot > tip.slot then
                    fail(HaskellImportError.TargetNotInSource(tip.slot, tip.hash.hex))
                  else F.pure(st.copy(skipped = st.skipped + 1))
                case Phase.Linking(previous) =>
                  val link: F[Unit] = previous match
                    case None =>
                      F.unlessA(h.prevHash == expectedPrevious)(
                        fail[Unit](
                          HaskellImportError
                            .ForeignGenesis(show(h.prevHash), show(expectedPrevious))
                        )
                      )
                    case Some(p) =>
                      val number = if h.isBoundary then p.blockNo else p.blockNo + 1
                      F.unlessA(h.prevHash.contains(p.hash))(
                        fail[Unit](
                          HaskellImportError.BrokenLink(b.slot, p.hash.hex, show(h.prevHash))
                        )
                      ) *> F.unlessA(h.blockNo == number)(
                        fail[Unit](HaskellImportError.BlockNumberJump(b.slot, number, h.blockNo))
                      )
                  for
                    _ <- link
                    // The identity is recorded once the first block is known to fit the genesis.
                    _ <- F.unlessA(st.identityKnown || recorded)(
                      NetworkIdentityFile.write[F](config.db, config.identity)
                    )
                    _ <- store.append(ref, b.bytes)
                    n = st.appended + 1
                    _ <- F.whenA(n % config.batch == 0)(
                      store.sync *> chunkCount.get
                        .flatMap(c => progress(Progress(n, st.skipped, c, b.slot)))
                    )
                  yield st.copy(
                    phase = Phase.Linking(Some(ref)),
                    appended = n,
                    identityKnown = true
                  )
            }

          for
            tip <- store.tip
            // A store that holds blocks but no identity file is adopted if it fits the genesis.
            _ <- F.whenA(!recorded && tip.isDefined)(
              NetworkIdentityFile.adopt[F](config.db, config.identity, firstPredecessor(store))
            )
            initial = State(
              tip.fold[Phase](Phase.Linking(None))(Phase.Skipping(_)),
              0L,
              0L,
              recorded || tip.isDefined
            )
            source = reader
              .contents()
              .evalTap { c =>
                chunkCount.update(_ + 1) *>
                  F.whenA(c.mostRecent)(truncation.set(c.dropped.map(c.chunk -> _)))
              }
              .flatMap(c => Stream.emits(c.blocks))
              .evalTap(b => F.whenA(config.stopSlot.exists(b.slot > _))(beyond.set(true)))
              .takeWhile(b => config.stopSlot.forall(b.slot <= _))
            last <- source.evalScan(initial)(step).compile.last.map(_.getOrElse(initial))
            _ <- last.phase match
              case Phase.Skipping(t) =>
                fail[Unit](HaskellImportError.TargetNotInSource(t.slot, t.hash.hex))
              case _ => F.unit
            _ <- store.sync
            newTip <- store.tip
          yield (last, newTip)
        }
        (state, newTip) = result
        finished <- Clock[F].monotonic
        dropped <- truncation.get
        chunks <- chunkCount.get
        stopped <- beyond.get
      yield Report(
        state.appended,
        state.skipped,
        chunks,
        newTip,
        stopped,
        dropped,
        finished - began
      )
    }
