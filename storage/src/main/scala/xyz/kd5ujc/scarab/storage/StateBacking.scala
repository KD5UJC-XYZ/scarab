// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Applicative
import cats.effect.Sync
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.io.file.Path
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{StorageError, Utxo}

/** The part of the node state that lives outside the snapshot file: the UTxO set. It follows the
  * immutable tip, and each ledger snapshot has a checkpoint of it taken at the same block, so a
  * restored snapshot and its store always agree.
  */
trait StateBacking[F[_], S]:
  /** The immutable tip moved to `state`: make the store reflect it. */
  def advance(state: S): F[Unit]

  /** `state` with whatever the store now holds dropped from it (what a snapshot should contain). */
  def settled(state: S): F[S]

  /** Durable copy of the store, which is at `anchor`, to go with the snapshot of that block. */
  def checkpoint(anchor: BlockRef): F[Unit]

  /** Whether the checkpoint of `anchor` exists. */
  def available(anchor: Option[BlockRef]): F[Boolean]

  /** Make the store equal to the checkpoint of `anchor`. */
  def restore(anchor: Option[BlockRef]): F[Boolean]

  /** Start from an empty store (no snapshot was restored). */
  def reset: F[Unit]

  /** Delete the checkpoints that no listed snapshot (by file stem) refers to. */
  def retain(stems: Set[String]): F[Unit]

object StateBacking:
  def none[F[_]: Applicative, S]: StateBacking[F, S] = new StateBacking[F, S]:
    private val unit = Applicative[F].unit
    def advance(state: S) = unit
    def settled(state: S) = Applicative[F].pure(state)
    def checkpoint(anchor: BlockRef) = unit
    def available(anchor: Option[BlockRef]) = Applicative[F].pure(true)
    def restore(anchor: Option[BlockRef]) = Applicative[F].pure(true)
    def reset = unit
    def retain(stems: Set[String]) = unit

  /** The name shared by a snapshot file (`<stem>.bin`) and its checkpoint directory. */
  def stem(a: BlockRef): String = f"snapshot-${a.slot}%020d-${a.blockNo}"

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
    raise: Raise[F, StorageError],
    handle: UtxoHandle[S]
) extends StateBacking[F, S]:
  private val F = Sync[F]

  private def dirOf(a: BlockRef): Path = root / StateBacking.stem(a)

  private def guard[A](what: String)(body: => A): F[A] =
    F.blocking(body).attempt.flatMap {
      case Right(a) => F.pure(a)
      case Left(e)  => StorageError.Io(what, String.valueOf(e)).raise[F, A]
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
  def advance(state: S): F[Unit] =
    handle.get(state).fold(F.unit)(UtxoStore.advance(store, _))

  def settled(state: S): F[S] =
    if handle.get(state).isEmpty then F.pure(state)
    else store.flushed.map(at => handle.modify(state)(_.settled(at, force = true)))

  def checkpoint(anchor: BlockRef): F[Unit] =
    val dir = dirOf(anchor)
    guard(dir.toString)(RocksUtxoStore.deleteTree(dir.toNioPath)) *>
      guard(root.toString)(java.nio.file.Files.createDirectories(root.toNioPath)) *>
      store.checkpoint(dir)

  def available(anchor: Option[BlockRef]): F[Boolean] = anchor match
    case None    => F.pure(false)
    case Some(a) => guard(root.toString)(java.nio.file.Files.isDirectory(dirOf(a).toNioPath))

  def restore(anchor: Option[BlockRef]): F[Boolean] = anchor match
    case None    => F.pure(false)
    case Some(a) => store.restore(dirOf(a))

  def reset: F[Unit] = store.reset

  def retain(stems: Set[String]): F[Unit] = guard(root.toString) {
    val nio = root.toNioPath
    if java.nio.file.Files.isDirectory(nio) then
      val dirs = java.nio.file.Files.list(nio)
      try
        dirs.forEach { d =>
          if !stems.contains(d.getFileName.toString) then RocksUtxoStore.deleteTree(d)
        }
      finally dirs.close()
  }
