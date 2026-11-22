// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.crypto.bls.{Certificate, RbHash}
import xyz.kd5ujc.scarab.ledger.{ChainError, NodeError, StorageError}
import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraClosure
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, SlotNo, UtxoTables}
import xyz.kd5ujc.scarab.node.forge.LeiosCertifying
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.storage.{UtxoReadView, UtxoStore}

/** The node's [[LeiosCertifying]]: its endorser-block store (`lookupEbClosure`), its vote state
  * (`queryCert`), and the certified closure applied as block application applies it
  * ([[MirrorDijkstra.closedForForging]]), the stored outputs it reads fetched from `utxoStore`
  * first (`readTables` of the forker at the parent, ouroboros-consensus 2416493b
  * `NodeKernel/Forge.hs:761, 833`).
  */
object LeiosCertifier:
  def apply[F[_]: Sync](
      lookup: EbHash => Raise[F, StorageError] ?=> F[Option[Vector[(LeiosTxHash, Bytes)]]],
      certificates: RbHash => F[Option[Certificate]],
      utxoStore: Option[UtxoReadView[F]],
      env: (MirrorState, BigInt) => MirrorDijkstra.Env
  ): LeiosCertifying[F, MirrorState] = new LeiosCertifying[F, MirrorState]:
    def closureHeld(eb: EbHash)(using Raise[F, StorageError]): F[Boolean] =
      lookup(eb).map(_.isDefined)

    def certificate(rbHash: RbHash): F[Option[Certificate]] = certificates(rbHash)

    def closed(
        chain: MirrorState,
        tip: Option[BlockRef],
        parent: NewEpochState[Dijkstra],
        point: EbPoint,
        slot: BigInt
    )(using Raise[F, StorageError]): F[Either[NodeError, NewEpochState[Dijkstra]]] =
      lookup(point.hash).flatMap { stored =>
        val resolved = DijkstraClosure.resolveLeiosClosure(point.hash, stored)
        val reads = resolved.fold(_ => Set.empty, c => MirrorDijkstra.inputs(c.map(_._2)))
        val ls = parent.es.ls
        UtxoStore.complete(utxoStore, ls.utxo.utxo.utxo, reads).flatMap { utxo =>
          val read = parent.copy[Dijkstra](es =
            parent.es
              .copy[Dijkstra](ls = ls.copy[Dijkstra](utxo = ls.utxo.copy(utxo = UtxoTables(utxo))))
          )
          tip
            .traverse(t =>
              SlotNo.fromBigInt(t.slot).leftMap(e => ChainError.StateOutOfRange(slot, e))
            )
            .fold(
              e => Sync[F].pure(Left(e)),
              tipSlot =>
                Sync[F].blocking(
                  MirrorDijkstra.closedForForging(env(chain, slot), point, resolved, read, tipSlot)
                )
            )
        }
      }
