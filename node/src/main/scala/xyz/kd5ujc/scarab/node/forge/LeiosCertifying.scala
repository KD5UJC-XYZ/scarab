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

  /** How many reached applications a memo keeps: the tip's, and the one before it while a fork
    * switch or a forge on the previous tip still reads it. An application still running is never
    * dropped for this: its callers wait for it.
    */
  private val Kept: Int = 2

  /** One application in a memo: running, its state to come (`none` once it failed, was refused or
    * was cancelled), or `reached`.
    */
  private final case class Entry[F[_]](
      key: Applied,
      state: Deferred[F, Option[NewEpochState[Dijkstra]]],
      reached: Boolean
  )

  /** `entries` with the application whose state is `state` reached, as the newest reached one, and
    * the reached ones beyond the newest [[Kept]] dropped, the running ones all kept: an application
    * that ran long is not dropped as it is reached.
    */
  private def reached[F[_]](entries: Vector[Entry[F]], state: AnyRef): Vector[Entry[F]] =
    val (mine, others) = entries.partition(_.state eq state)
    val marked = others ++ mine.map(_.copy(reached = true))
    val surplus = marked.count(_.reached) - Kept
    if surplus <= 0 then marked
    else
      val dropped = marked.filter(_.reached).take(surplus).map(_.state).toSet
      marked.filterNot(e => dropped.exists(_ eq e.state))

  /** `underlying` with its [[LeiosCertifying.closed]] memoised, for the newest [[Kept]] tips and
    * endorser blocks, so that the certified closure applied off the forging path, as soon as the
    * tip announces an endorser block whose closure the node holds, is not applied again when the
    * forger certifies it: the reference keeps that state off the forging path too, in the mempool's
    * certifying view (`buildCertifyingView` over `applyAnnouncedClosure`,
    * `Mempool/CertifyingView.hs` and `Mempool/Impl/Common.hs:272-344` at ouroboros-consensus
    * d91b3557), and reads it when it forges (`getCertifyingSnapshotFor`,
    * `NodeKernel/Forge.hs:805-821`).
    *
    * One application runs at a time for each pair, and a call while it runs waits for it. Only a
    * state the application reached is given to the calls that waited and kept: a failure or refusal
    * is its own call's only, and a failure, refusal, raise or cancellation wakes the waiting calls,
    * which then ask again, each with its own arguments, one of them applying the closure. So no
    * call gets what `underlying` would not have given it, and a call never meets another's failure.
    * The entry is made and the step that completes it installed with no cancellation between them,
    * so a waiting call is always woken.
    */
  def memoized[F[_], S](underlying: LeiosCertifying[F, S])(using
      F: Concurrent[F]
  ): F[LeiosCertifying[F, S]] =
    type Result = Either[NodeError, NewEpochState[Dijkstra]]
    Ref.of[F, Vector[Entry[F]]](Vector.empty).map { memo =>
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
          // the entry of this application, dropped only if it is still the one this call made
          def forget(mine: AnyRef): F[Unit] =
            memo.update(_.filterNot(e => e.key == key && (e.state eq mine)))
          def ask: F[Result] =
            Deferred[F, Option[NewEpochState[Dijkstra]]].flatMap { mine =>
              F.uncancelable { poll =>
                memo
                  .modify { entries =>
                    entries.find(_.key == key) match
                      case Some(theirs) => (entries, Left(theirs.state))
                      case None         => (entries :+ Entry(key, mine, reached = false), Right(()))
                  }
                  .flatMap {
                    // woken with none, the call asks again: the entry is gone by then
                    case Left(theirs) =>
                      poll(theirs.get).flatMap(_.fold(poll(ask))(state => F.pure(Right(state))))
                    case Right(()) =>
                      poll(applied).guaranteeCase {
                        case Outcome.Succeeded(result) =>
                          result.flatMap {
                            case Right(state) =>
                              memo.update(reached(_, mine)) *> mine.complete(Some(state)).void
                            case Left(_) => forget(mine) *> mine.complete(None).void
                          }
                        case Outcome.Errored(_) | Outcome.Canceled() =>
                          forget(mine) *> mine.complete(None).void
                      }
                  }
              }
            }
          ask
    }
