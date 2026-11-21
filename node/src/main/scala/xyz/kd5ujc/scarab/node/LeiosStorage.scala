// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Resource}
import cats.mtl.Raise
import fs2.io.file.Path
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.{
  LeiosBackground,
  LeiosStore,
  LeiosStoreError,
  LeiosStoreLocks,
  RocksLeiosStore
}

/** The endorser-block store of a run. The node opens it, as cardano-node b004fe0c opens its LeiosDB
  * in `handleSimpleNode` once the database's marker is checked and closes it after the run
  * (`cardano-node/src/Cardano/Node/Run.hs:372-402`), and the run's consumers receive it, as
  * ouroboros-consensus 2416493b's `run` receives `rnLeiosDb` and hands it to the ChainDB and the
  * NodeKernel without owning it (`Ouroboros/Consensus/Node.hs:261-264`, 580, 644).
  */
private[node] object LeiosStorage:
  /** Where the store lives in the node's database directory: one RocksDB database in place of the
    * reference's `leios.vol.db` and `leios.imm.db` ([[RocksLeiosStore]]).
    */
  def directory(db: Path): Path = db / "leios"

  /** The store of `build` 's run, opened for the resource's life, or none:
    *
    *   - only the Leios testnet's build has a LeiosDB; a release build's node has none;
    *   - the reference's `LeiosDbConfig` backend `SQLite` (its default) keeps the store on disk, at
    *     [[directory]] of `db`; `InMemory` keeps it in memory (`newLeiosDBInMemory`), which this
    *     build has no store for, so it opens none, and nor does a run without a database directory.
    *
    * `locks` is the process's instance ([[LeiosStoreLocks]]); a failed open raises
    * [[StorageError.LeiosStore]] with the store's typed cause.
    */
  def resource[F[_]: Async](
      build: ReferenceBuild,
      backend: LeiosDbBackend,
      db: Option[Path],
      locks: LeiosStoreLocks[F]
  )(using Raise[F, StorageError]): Resource[F, Option[LeiosStore[F]]] =
    build match
      case ReferenceBuild.Release11_1 | ReferenceBuild.Release11_2 => Resource.pure(None)
      case ReferenceBuild.LeiosB004fe0c =>
        (backend, db) match
          case (LeiosDbBackend.SQLite, Some(dir)) =>
            RocksLeiosStore
              .resource[F](directory(dir), locks)(using Async[F], lifted[F])
              .map(Some(_))
          case (LeiosDbBackend.InMemory, _) | (LeiosDbBackend.SQLite, None) =>
            Resource.pure(None)

  /** The store's errors as the node's ([[StorageError.LeiosStore]], [[LeiosBackground.lifted]]). */
  def lifted[F[_]](using Raise[F, StorageError]): Raise[F, LeiosStoreError] =
    LeiosBackground.lifted[F]
