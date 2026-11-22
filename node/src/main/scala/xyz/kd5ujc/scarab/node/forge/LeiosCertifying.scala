// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Applicative
import cats.mtl.Raise
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.crypto.bls.{Certificate, RbHash}
import xyz.kd5ujc.scarab.ledger.{NodeError, StorageError}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}

/** What the Dijkstra forger reads of the node to certify an endorser block, `partitionMempool` 's
  * arguments beyond the mempool (ouroboros-consensus 2416493b
  * `ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/NodeKernel/Forge.hs:734-869`):
  * whether the store holds an endorser block's closure (`lookupEbClosure`, `:354`), the certificate
  * the vote state assembled for an announcing block (`queryCert`, `:371`), and the state the parent
  * comes to once the certified closure is applied to it (`resolveAndApplyLeiosClosure`,
  * `:827-835`), which the forger then ticks to the slot, as `tick-ledger-state-after-eb` does
  * (`:847-856`), and takes the pool's snapshot on.
  *
  * @tparam S
  *   the node's chain state, which the closure's rules read their clock and horizon from
  */
trait LeiosCertifying[F[_], S]:
  /** `isJust <$> lookupEbClosure` : whether the store holds the whole closure of `eb`. */
  def closureHeld(eb: EbHash)(using Raise[F, StorageError]): F[Boolean]

  /** `queryCert` (`LeiosVoteState.hs:241-243` at 2416493b): the certificate assembled for the
    * announcing block `rbHash`, if its tally reached the quorum.
    */
  def certificate(rbHash: RbHash): F[Option[Certificate]]

  /** `resolveAndApplyLeiosClosure` of the endorser block at `point` on `parent`, the unticked
    * ledger state of the chain `chain` at its tip `tip`, the parent of the block forged in `slot`:
    * the closure read from the store and applied to `parent` with the environment of its tip's slot
    * (`Shelley/Ledger/Leios.hs:210-252` at 2416493b). Its failure is the node's stop, the
    * reference's `error` (`NodeKernel/Forge.hs:837-841`), never a verdict.
    */
  def closed(
      chain: S,
      tip: Option[BlockRef],
      parent: NewEpochState[Dijkstra],
      point: EbPoint,
      slot: BigInt
  )(using Raise[F, StorageError]): F[Either[NodeError, NewEpochState[Dijkstra]]]

object LeiosCertifying:
  /** A node that holds no closure and assembles no certificate: its forger never certifies, as the
    * reference's does not with an empty LeiosDB, for forgers of tests and nodes without an
    * endorser-block store.
    */
  def none[F[_]: Applicative, S]: LeiosCertifying[F, S] = new LeiosCertifying[F, S]:
    def closureHeld(eb: EbHash)(using Raise[F, StorageError]): F[Boolean] =
      Applicative[F].pure(false)
    def certificate(rbHash: RbHash): F[Option[Certificate]] = Applicative[F].pure(None)
    def closed(
        chain: S,
        tip: Option[BlockRef],
        parent: NewEpochState[Dijkstra],
        point: EbPoint,
        slot: BigInt
    )(using Raise[F, StorageError]): F[Either[NodeError, NewEpochState[Dijkstra]]] =
      // no closure is held, so the forger never decides to certify and never asks
      Applicative[F].pure(Right(parent))
