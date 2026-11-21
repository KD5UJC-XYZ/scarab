// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Async
import cats.effect.std.AtomicCell
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.consensus.Praos2
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncementFields,
  AnnouncingHeader,
  BytesSize,
  LeiosAnnouncements,
  LeiosRollForward
}
import xyz.kd5ujc.scarab.consensus.state.ChainDepState
import xyz.kd5ujc.scarab.ledger.{Header, LeiosHeader, PeerError}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word32
import xyz.kd5ujc.scarab.storage.{LeiosStore, LeiosStoreError}

/** What a ChainSync client of the Leios build does with the headers it accepts, for one connection:
  * the roll-forward of a validated header and the headers of an accepted jump.
  */
trait LeiosChainSync[F[_]](using F: cats.Monad[F]):
  /** `leiosMsgRollForwardCallback` (`Ouroboros/Consensus/Network/NodeToNode.hs` 484-510 at
    * ouroboros-consensus 2416493b), with the validated `header` and the header state as of its
    * predecessor, `predecessor`, which carries the ledger view of the predecessor's slot. A peer
    * whose roll-forward claims a second endorser block certified for an election raises
    * [[PeerError.LeiosTwoCertificationClaims]].
    */
  def rolledForward(header: Header, predecessor: NodeHeaderState)(using
      Raise[F, PeerError]
  ): F[Unit] =
    LeiosChainSync.leiosHeader(header).traverse_(rolledForwardLeios(_, predecessor))

  /** [[rolledForward]] of a Dijkstra header, read as the Leios header it is. */
  def rolledForwardLeios(header: LeiosHeader, predecessor: NodeHeaderState)(using
      Raise[F, PeerError]
  ): F[Unit]

  /** `leiosJumpAcceptedCallback` (`NodeToNode.hs` 512-526 at 2416493b): the headers of the dynamo's
    * candidate an accepted jump carries, oldest first, each with the header state as of its
    * predecessor; those past the newest slot this peer accepted a jump through are counted as its
    * offers, as their roll-forwards would have been, and announce nothing.
    */
  def jumped(headers: Vector[(Header, NodeHeaderState)])(using Raise[F, PeerError]): F[Unit]

object LeiosChainSync:
  /** The central state of the announcements the node has counted, shared by every connection
    * (`getLeiosCentralState`'s `selfPeer`, `LeiosDemoLogic/Announcements.hs` 238-262 at 2416493b).
    */
  type Central[F[_]] = AtomicCell[F, LeiosAnnouncements[AnnouncingHeader]]

  /** The handling of `vars` ' connection, over the fetch logic's `shared` state, the node's
    * `central` announcements and its store, whose failure goes to `fatal`; `onset` places a slot on
    * the wall clock since the system start, for the age of the endorser blocks it announces.
    */
  def apply[F[_], P](
      shared: LeiosFetchShared[F, P],
      vars: LeiosPeerVars[F],
      central: Central[F],
      store: LeiosStore[F],
      onset: BigInt => F[Option[FiniteDuration]],
      fatal: LeiosStoreError => F[Unit]
  )(using F: Async[F]): LeiosChainSync[F] = new LeiosChainSync[F]:
    def rolledForwardLeios(header: LeiosHeader, predecessor: NodeHeaderState)(using
        Raise[F, PeerError]
    ): F[Unit] =
      offers(header, predecessor) *> AnnouncingHeader.of(header).traverse_(announced(header, _))

    def jumped(headers: Vector[(Header, NodeHeaderState)])(using Raise[F, PeerError]): F[Unit] =
      vars.maxAcceptedJumpSlot.get.flatMap { acceptedThrough =>
        val past = headers.filter((h, _) => acceptedThrough.forall(h.slot > _.toBigInt))
        past.traverse_((h, predecessor) => leiosHeader(h).traverse_(offers(_, predecessor))) *>
          headers.lastOption.traverse_((newest, _) =>
            vars.maxAcceptedJumpSlot.update(old =>
              val slot = xyz.kd5ujc.scarab.protocol.LeiosWord.Word64.clamped(newest.slot)
              Some(old.fold(slot)(o => if o.toBigInt > slot.toBigInt then o else slot))
            )
          )
      }

    /** `checkMsgRollForwardForLeiosOffers` (`LeiosDemoLogic.hs` 1671-1696 at 2416493b): a
      * certifying header's claim is counted, then the endorser block its predecessor announced is
      * recorded as offered, at the bound of the predecessor's view, and the decision woken.
      */
    private def offers(header: LeiosHeader, predecessor: NodeHeaderState)(using
        Raise[F, PeerError]
    ): F[Unit] =
      val (state, bound) = predecessor match
        case NodeHeaderState.Shelley(protocol, _, _, _, view) =>
          (
            protocol.dep match
              case ChainDepState.Praos2(p) => Some(p)
              case ChainDepState.Praos(_) | ChainDepState.TPraos(_) | ChainDepState.PBft(_) =>
                None
            ,
            maxEbTxsSize(view)
          )
        case _: NodeHeaderState.Byron => (None, BytesSize.zero)
      state.flatMap(LeiosRollForward.certifiedAnnouncement(header, _)).traverse_ { fields =>
        claim(fields) *>
          vars.offerings.update(
            LeiosRollForward.recordOffer(
              _,
              fields.point,
              LeiosRollForward.certRbOffer(fields, bound)
            )
          ) *> shared.wake
      }

    /** `noteCertificationClaim` (`LeiosDemoLogic.hs` 1696-1731). */
    private def claim(fields: AnnouncementFields)(using Raise[F, PeerError]): F[Unit] =
      vars.certificationClaims.evalUpdate(claimed =>
        LeiosRollForward
          .noteCertificationClaim(claimed, fields.election, fields.ebHash)
          .fold(
            two =>
              PeerError
                .LeiosTwoCertificationClaims(
                  two.election.slot,
                  two.election.pool,
                  two.alreadyClaimed,
                  two.claimed
                )
                .raise,
            F.pure
          )
      )

    /** `processAnnouncementCentrally` for an announcement received over ChainSync
      * (`LeiosDemoLogic.hs` 1777-1853 at 2416493b): counted once per election, at most twice
      * (`onAnnouncementCentral`, `Announcements.hs` 338-376); a new one records its endorser block
      * as awaited (`recordAnnouncedEb`) and its point in the store (`writeEbPoint`). It is not
      * relayed: the LeiosNotify server is not run, nor the transaction cache kept (#54).
      */
    private def announced(header: LeiosHeader, anc: AnnouncingHeader): F[Unit] =
      onset(header.slot).flatMap { at =>
        central.evalUpdate(counted =>
          counted.extendLive(anc.fields.election, anc) match
            case Left(_) => F.pure(counted)
            case Right((_, next)) =>
              recordAnnounced(anc.fields, at) *> writePoint(anc.fields).as(next)
        )
      }

    /** `recordAnnouncedEb` (`LeiosDemoLogic.hs` 2097-2138): the decision is woken only when the
      * announcement gives it something new to do.
      */
    private def recordAnnounced(fields: AnnouncementFields, at: Option[FiniteDuration]): F[Unit] =
      shared.outstanding
        .modify(_.announced(fields, at))
        .flatMap(changed => shared.wake.whenA(changed))

    private def writePoint(fields: AnnouncementFields): F[Unit] =
      Handle
        .allow[LeiosStoreError](
          store
            .writeEbPoint(fields.point, Word32.wrap(fields.ebBodySize.toLong))
            .as(().asRight[LeiosStoreError])
        )
        .rescue(e => F.pure(e.asLeft[Unit]))
        .flatMap(_.fold(fatal, F.pure))

  /** The Leios header of a Dijkstra header the gate validated, which decoded it as this does; none
    * for a header of another era, which neither offers nor announces.
    */
  def leiosHeader(header: Header): Option[LeiosHeader] = header match
    case h: Header.Shelley if h.era == Era.Dijkstra => LeiosHeader.decode(h.raw).toOption
    case _: Header.Shelley | _: Header.Byron        => None

  /** `getLeiosMaxEbTxsSizeFromView` (`Shelley/Ledger/Leios.hs` 161-163 at 2416493b): a Praos2
    * view's bound, and 0 for the view of an era without Leios (`Storage/LedgerDB/Forker.hs`
    * 975-987).
    */
  def maxEbTxsSize(view: Option[Praos2.LedgerView]): BytesSize =
    view.fold(BytesSize.zero)(v => BytesSize.wrap(v.maxEbTxsSize.toLong))
