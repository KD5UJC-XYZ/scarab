// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Applicative
import cats.effect.{Concurrent, Deferred, Outcome, Ref}
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
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

  /** Which application of a certified closure a memoised result is: the endorser block's, on the
    * state of the chain at the tip `tip`. The state at a tip is the same state wherever it is read,
    * and the application reads the environment of the tip's slot only, never the forged block's
    * (`mkMempoolEnv nes (getTipSlot lst)`, `Shelley/Ledger/Leios.hs:204-249` at ouroboros-consensus
    * d91b3557), so the pair names the result.
    */
  private final case class Applied(tip: Option[Bytes], eb: EbHash)

  /** How many applications a memo keeps: the tip's, and the one before it while a fork switch or a
    * forge on the previous tip still reads it.
    */
  private val Kept: Int = 2

  /** `underlying` with its [[LeiosCertifying.closed]] memoised, for the newest [[Kept]] tips and
    * endorser blocks, so that the certified closure applied off the forging path, as soon as the
    * tip announces an endorser block whose closure the node holds, is not applied again when the
    * forger certifies it: the reference keeps that state off the forging path too, in the mempool's
    * certifying view (`buildCertifyingView` over `applyAnnouncedClosure`,
    * `Mempool/CertifyingView.hs` and `Mempool/Impl/Common.hs:272-344` at ouroboros-consensus
    * d91b3557), and reads it when it forges (`getCertifyingSnapshotFor`,
    * `NodeKernel/Forge.hs:805-821`).
    *
    * One application runs at a time for each pair: a call while it runs waits for its result. Only
    * a result the application reached is kept: a failure, a raise or a cancellation is not, and a
    * call that waited for it applies the closure itself, as without the memo, so the memo never
    * gives what `underlying` would not.
    */
  def memoized[F[_], S](underlying: LeiosCertifying[F, S])(using
      F: Concurrent[F]
  ): F[LeiosCertifying[F, S]] =
    type Result = Either[NodeError, NewEpochState[Dijkstra]]
    Ref.of[F, Vector[(Applied, Deferred[F, Option[Result]])]](Vector.empty).map { memo =>
      new LeiosCertifying[F, S]:
        def closureHeld(eb: EbHash)(using Raise[F, StorageError]): F[Boolean] =
          underlying.closureHeld(eb)

        def certificate(rbHash: RbHash): F[Option[Certificate]] = underlying.certificate(rbHash)

        def closed(
            chain: S,
            tip: Option[BlockRef],
            parent: NewEpochState[Dijkstra],
            point: EbPoint,
            slot: BigInt
        )(using Raise[F, StorageError]): F[Result] =
          val key = Applied(tip.map(_.hash), point.hash)
          val applied = underlying.closed(chain, tip, parent, point, slot)
          // the entry of this application, left only if it is still the one this call made
          def forget(mine: Deferred[F, Option[Result]]): F[Unit] =
            memo.update(_.filterNot((k, d) => k == key && (d eq mine)))
          Deferred[F, Option[Result]].flatMap { mine =>
            memo
              .modify { entries =>
                entries.collectFirst { case (k, d) if k == key => d } match
                  case Some(theirs) => (entries, Left(theirs))
                  case None         => ((entries :+ (key -> mine)).takeRight(Kept), Right(mine))
              }
              .flatMap {
                case Left(theirs) => theirs.get.flatMap(_.fold(applied)(F.pure))
                case Right(own) =>
                  applied.guaranteeCase {
                    case Outcome.Succeeded(result) =>
                      result.flatMap { r =>
                        forget(own).whenA(r.isLeft) *> own.complete(Some(r)).void
                      }
                    case Outcome.Errored(_) | Outcome.Canceled() =>
                      forget(own) *> own.complete(None).void
                  }
              }
          }
    }
