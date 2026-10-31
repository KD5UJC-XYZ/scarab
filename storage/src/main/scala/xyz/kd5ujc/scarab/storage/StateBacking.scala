// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Applicative
import scala.util.Using
import cats.effect.Sync
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{StorageError, Utxo, UtxoKeyLayout}

/** The part of the node state that lives outside the snapshot file: the UTxO set. It follows the
  * immutable tip, and each ledger snapshot has a checkpoint of it taken at the same block, so a
  * restored snapshot and its store always agree. The snapshot records the checkpoint it was written
  * with ([[CheckpointBinding]]) and a restore admits only that one ([[admit]]).
  */
trait StateBacking[F[_], S]:
  /** The immutable tip moved to `state`: make the store reflect it. */
  def advance(state: S)(using Raise[F, StorageError]): F[Unit]

  /** `state` with whatever the store now holds dropped from it (what a snapshot should contain). */
  def settled(state: S)(using Raise[F, StorageError]): F[S]

  /** Durable copy of the store, which is at `anchor`, to go with the snapshot of that block: what
    * that snapshot records of it.
    */
  def checkpoint(anchor: BlockRef)(using Raise[F, StorageError]): F[CheckpointBinding]

  /** Whether the checkpoint of `anchor` is the one a snapshot recorded as `binding`: the reason it
    * is not, as a [[StorageError.UtxoCheckpoint]], otherwise. A [[CheckpointBinding.Legacy]]
    * snapshot is admitted when its checkpoint directory exists, as the V5 build admitted it.
    */
  def admit(anchor: Option[BlockRef], binding: CheckpointBinding)(using
      Raise[F, StorageError]
  ): F[Either[StorageError, Unit]]

  /** Make the store equal to the checkpoint of `anchor`. */
  def restore(anchor: Option[BlockRef])(using cats.mtl.Raise[F, StorageError]): F[Boolean]

  /** Start from an empty store (no snapshot was restored). */
  def reset(using cats.mtl.Raise[F, StorageError]): F[Unit]

  /** Delete the checkpoints that no listed snapshot (by file stem) refers to. Only directories
    * named as checkpoints ([[StateBacking.isStem]]) are deleted.
    */
  def retain(stems: Set[String])(using Raise[F, StorageError]): F[Unit]

object StateBacking:
  def none[F[_]: Applicative, S]: StateBacking[F, S] = new StateBacking[F, S]:
    private val unit = Applicative[F].unit
    def advance(state: S)(using Raise[F, StorageError]) = unit
    def settled(state: S)(using Raise[F, StorageError]) = Applicative[F].pure(state)
    def checkpoint(anchor: BlockRef)(using Raise[F, StorageError]) =
      Applicative[F].pure(CheckpointBinding.Unbacked)
    def admit(anchor: Option[BlockRef], binding: CheckpointBinding)(using
        Raise[F, StorageError]
    ) = Applicative[F].pure(Right(()))
    def restore(anchor: Option[BlockRef])(using cats.mtl.Raise[F, StorageError]) =
      Applicative[F].pure(true)
    def reset(using cats.mtl.Raise[F, StorageError]) = unit
    def retain(stems: Set[String])(using Raise[F, StorageError]) = unit

  /** The name shared by a snapshot file (`<stem>.bin`) and its checkpoint directory. */
  def stem(a: BlockRef): String = f"snapshot-${a.slot}%020d-${a.blockNo}"

  private val Stem = "snapshot-[0-9]{20}-[0-9]+".r

  /** Whether `name` is a name [[stem]] gives. */
  def isStem(name: String): Boolean = Stem.matches(name)

/** How a chain state holds the handle on its UTxO set. */
trait UtxoHandle[S]:
  /** The handle, none while it is only a placeholder: while the Byron ledger is current, the real
    * set is the Byron ledger's own, and the Shelley state the chain will start from holds the
    * genesis placeholder (the hand-over replaces it).
    */
  def get(s: S): Option[Utxo]

  /** The state with the handle changed by `f`. */
  def modify(s: S)(f: Utxo => Utxo): S

object UtxoHandle:
  /** The mirror: the set is that of the new-epoch state, none in Byron. */
  given UtxoHandle[MirrorState] with
    def get(s: MirrorState): Option[Utxo] = MirrorStep.utxoOf(s)
    def modify(s: MirrorState)(f: Utxo => Utxo): MirrorState = MirrorStep.mapUtxo(s)(f)

/** [[StateBacking]] for a chain state over a [[UtxoStore]]; checkpoints live under `root`. */
final class UtxoBacking[F[_]: Sync, S](store: UtxoStore[F], root: Path)(using
    handle: UtxoHandle[S]
) extends StateBacking[F, S]:
  private val F = Sync[F]

  private def dirOf(a: BlockRef): Path = root / StateBacking.stem(a)

  private def guard[A](what: String)(body: => A)(using Raise[F, StorageError]): F[A] =
    F.blocking(body).attempt.flatMap {
      case Right(a) => F.pure(a)
      case Left(e)  => StorageError.Io(what, e).raise[F, A]
    }

  /** While the Byron ledger is current the UTxO set of the state is only the genesis placeholder
    * the Shelley state starts from ([[UtxoHandle]]): the real set is the Byron ledger's own, and
    * the handover replaces the placeholder by it. The placeholder must not reach the store: it
    * would make the store's stamp 0 and the handover's entries (stamp 0, built from scratch) would
    * count as already stored, never be written and then be trimmed from the diff (mainnet stop 4).
    * The reference emits the translated UTxO as table diffs
    * (`translateLedgerStateByronToShelleyWrapper`, Ouroboros/Consensus/Cardano/CanHardFork.hs:
    * 343-358, `valuesAsDiffs . unstowLedgerTables`), so every entry is written.
    */
  def advance(state: S)(using Raise[F, StorageError]): F[Unit] =
    handle.get(state).fold(F.unit)(UtxoStore.advance(store, _))

  def settled(state: S)(using Raise[F, StorageError]): F[S] =
    if handle.get(state).isEmpty then F.pure(state)
    else store.flushed.map(at => handle.modify(state)(_.settled(at, force = true)))

  def checkpoint(anchor: BlockRef)(using Raise[F, StorageError]): F[CheckpointBinding] =
    val dir = dirOf(anchor)
    guard(dir.toString)(RocksUtxoStore.deleteTree(dir.toNioPath)) *>
      guard(root.toString)(java.nio.file.Files.createDirectories(root.toNioPath)) *>
      store.checkpoint(dir) *>
      guard(dir.toString)(CheckpointDigest.of(dir.toNioPath))
        .map(CheckpointBinding.Bound(UtxoKeyLayout.current, _))

  def admit(anchor: Option[BlockRef], binding: CheckpointBinding)(using
      Raise[F, StorageError]
  ): F[Either[StorageError, Unit]] =
    anchor match
      case None => F.pure(Left(StorageError.Missing("snapshot anchor")))
      case Some(a) =>
        val dir = dirOf(a)
        guard(dir.toString)(CheckpointDigest.admit(dir.toNioPath, binding))

  def restore(anchor: Option[BlockRef])(using cats.mtl.Raise[F, StorageError]): F[Boolean] =
    anchor match
      case None    => F.pure(false)
      case Some(a) => store.restore(dirOf(a))

  def reset(using cats.mtl.Raise[F, StorageError]): F[Unit] = store.reset

  def retain(stems: Set[String])(using Raise[F, StorageError]): F[Unit] = guard(root.toString) {
    val nio = root.toNioPath
    if java.nio.file.Files.isDirectory(nio) then
      Using.resource(java.nio.file.Files.list(nio)) { dirs =>
        dirs.forEach { d =>
          val name = d.getFileName.toString
          if StateBacking.isStem(name) && !stems.contains(name) then RocksUtxoStore.deleteTree(d)
        }
      }
  }
