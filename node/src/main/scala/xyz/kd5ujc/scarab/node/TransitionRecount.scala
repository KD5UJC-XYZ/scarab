// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Monad
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, StorageError}
import xyz.kd5ujc.scarab.ledger.state.{Gap, WithOrigin}
import xyz.kd5ujc.scarab.storage.{ChainStorage, ImmutableStore}
import xyz.kd5ujc.scarab.node.read.ChainRead.given

/** The after-voting counter of a restored snapshot that does not know it (`Gap.ShelleyTransition`),
  * recounted from the immutable store before the stored blocks are replayed.
  *
  * The reference's state always holds the counter (`shelleyLedgerTransition`, serialised with the
  * ledger state, `Ledger.hs:855-864,882`, ouroboros-consensus 82ecba32): the blocks of the tip's
  * epoch at or after the voting deadline, reset by the first tick into a new epoch
  * (`Ledger.hs:569-575`) and raised by each block at or after the deadline (`Ledger.hs:711-717`).
  * Eras change at epoch boundaries only, so the counter is the number of the chain's blocks from
  * the deadline to the tip, which the immutable store holds whenever it holds a block before the
  * deadline: its block numbers are consecutive and the snapshot's anchor is one of them. A store
  * that starts at or after the deadline cannot tell how many blocks it lacks, and the gap stays
  * open.
  */
object TransitionRecount:
  def reopen[F[_]: Monad](geometry: EpochGeometry): ChainStorage.Reopen[F, MirrorState] =
    new ChainStorage.Reopen[F, MirrorState]:
      def apply(store: ImmutableStore[F], anchor: Option[BlockRef], state: MirrorState)(using
          Raise[F, StorageError]
      ): F[MirrorState] =
        (anchor, tipSlot(state)) match
          case (Some(tip), Some(slot))
              if state.provenance.gaps.contains(Gap.ShelleyTransition) && tip.slot == slot =>
            // the era history the live counter is kept with: a chain whose Byron span is derived
            // learned its first Shelley epoch when it entered Shelley (`LedgerRules.entry`)
            val resolved = state.geometry(geometry)
            val deadline = MirrorStep.votingDeadline(resolved, resolved.epochOf(slot))
            store.pointAtOrAfterSlot(0).flatMap {
              case Some(first) if first.slot < deadline =>
                store
                  .pointAtOrAfterSlot(deadline)
                  .map(counted(tip, _))
                  .flatMap(n =>
                    MirrorStep
                      .recounted(state, n)
                      .fold(
                        e =>
                          StorageError
                            .Reconstruction("after-voting counter", e)
                            .raise[F, MirrorState],
                        _.pure[F]
                      )
                  )
              case _ => state.pure[F]
            }
          case _ => state.pure[F]

  /** The blocks from the first at or after the deadline to `tip`, both included. */
  private[node] def counted(tip: BlockRef, fromDeadline: Option[BlockRef]): Long =
    fromDeadline.filter(_.slot <= tip.slot).fold(0L)(f => (tip.blockNo - f.blockNo + 1).toLong)

  private def tipSlot(m: MirrorState): Option[BigInt] =
    m.ext.ledgerState.state match
      case EraLedgerState.ByronCurrent(_) => None
      case i: EraLedgerState.InShelleyEra[e] =>
        i.state.tip match
          case WithOrigin.At(t)  => Some(t.slot.toBigInt)
          case WithOrigin.Origin => None
