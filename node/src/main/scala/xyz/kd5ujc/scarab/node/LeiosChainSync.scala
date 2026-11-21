// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Async
import cats.effect.std.AtomicCell
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncementFields,
  AnnouncingHeader,
  BytesSize,
  LeiosAnnouncements,
  LeiosRollForward
}
import xyz.kd5ujc.scarab.consensus.state.ChainDepState
import xyz.kd5ujc.scarab.ledger.{Header, LeiosHeader, PeerError}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import xyz.kd5ujc.scarab.storage.{LeiosStore, LeiosStoreError}

/** What a ChainSync client of the Leios build does with the headers it accepts, for one connection:
  * the roll-forward of a validated header and the headers of an accepted jump.
  */
trait LeiosChainSync[F[_]](using F: cats.Monad[F]):
  /** `leiosMsgRollForwardCallback` (`Ouroboros/Consensus/Network/NodeToNode.hs` 484-510 at
    * ouroboros-consensus 2416493b), with the validated `header` and the header state as of its
    * predecessor, `predecessor`, which carries the closure bound of the ledger view of the
    * predecessor's slot. A peer whose roll-forward claims a second endorser block certified for an
    * election raises [[PeerError.LeiosTwoCertificationClaims]].
    */
  def rolledForward(header: Header, predecessor: NodeHeaderState)(using
      Raise[F, PeerError]
  ): F[Unit] =
    LeiosChainSync.leiosHeader(header).traverse_(rolledForwardLeios(_, predecessor))

  /** [[rolledForward]] of a Dijkstra header, read as the Leios header it is. */
  def rolledForwardLeios(header: LeiosHeader, predecessor: NodeHeaderState)(using
      Raise[F, PeerError]
  ): F[Unit]

  /** `leiosJumpAcceptedCallback` (`NodeToNode.hs` 510-526 at 2416493b): the dynamo's candidate
    * fragment an accepted jump offered, its `anchor` and its headers oldest first, each with the
    * header state as of its predecessor where it is known. The headers past the newest slot this
    * peer accepted a jump through ([[LeiosChainSync.pastAccepted]]) are counted as its offers, as
    * their roll-forwards would have been, and announce nothing; then that slot becomes the
    * fragment's head slot where it is newer.
    */
  def jumped(anchor: ChainSync.Point, headers: Vector[(Header, Option[NodeHeaderState])])(using
      Raise[F, PeerError]
  ): F[Unit]

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

    def jumped(anchor: ChainSync.Point, headers: Vector[(Header, Option[NodeHeaderState])])(using
        Raise[F, PeerError]
    ): F[Unit] =
      vars.maxAcceptedJumpSlot.get.flatMap { acceptedThrough =>
        pastAccepted(acceptedThrough, anchor, headers)
          .collect { case (h, Some(predecessor)) => h -> predecessor }
          .traverse_((h, predecessor) => leiosHeader(h).traverse_(offers(_, predecessor))) *>
          // `max (AF.headSlot (jTheirFragment jumpInfo))`, Origin below every slot
          headSlot(anchor, headers).traverse_(slot =>
            vars.maxAcceptedJumpSlot.update(old =>
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
        case NodeHeaderState.Shelley(protocol, _, _, _, leiosBound) =>
          (
            protocol.dep match
              case ChainDepState.Praos2(p) => Some(p)
              case ChainDepState.Praos(_) | ChainDepState.TPraos(_) | ChainDepState.PBft(_) =>
                None
            ,
            maxEbTxsSize(leiosBound)
          )
        case _: NodeHeaderState.Byron => (None, Some(BytesSize.zero))
      state.flatMap(LeiosRollForward.certifiedAnnouncement(header, _)).traverse_ { fields =>
        // a bound the node could not forecast is its own defect: the claim is counted, and no
        // offer is recorded against a bound that is not the reference's
        claim(fields) *> bound.traverse_(b =>
          vars.offerings.update(
            LeiosRollForward.recordOffer(_, fields.point, LeiosRollForward.certRbOffer(fields, b))
          ) *> shared.wake
        )
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

  /** `past` of `headersWithPredecessorStates` (`NodeToNode.hs` 2128-2144 at 2416493b): the headers
    * of a fragment from `anchor` after the slot `acceptedThrough` (none: Origin), split with
    * `splitAfterMeasure`. The fragment is split after its anchor where the anchor is at that slot,
    * which keeps every header, or after its newest header at that slot; where neither is, the whole
    * fragment is kept, as a jump onto another chain than the last one's needs.
    */
  def pastAccepted[A](
      acceptedThrough: Option[Word64],
      anchor: ChainSync.Point,
      headers: Vector[(Header, A)]
  ): Vector[(Header, A)] =
    val through = acceptedThrough.map(_.toBigInt)
    if anchorSlot(anchor) == through then headers
    else
      through.fold(headers) { slot =>
        val newest = headers.lastIndexWhere((h, _) => h.slot == slot)
        if newest >= 0 then headers.drop(newest + 1) else headers
      }

  /** `AF.headSlot` of a fragment from `anchor` with `headers`: its newest header's slot, or its
    * anchor's where it has none; none at Origin.
    */
  private def headSlot[A](anchor: ChainSync.Point, headers: Vector[(Header, A)]): Option[Word64] =
    headers.lastOption
      .map((h, _) => h.slot)
      .orElse(anchorSlot(anchor))
      .map(Word64.clamped)

  private def anchorSlot(anchor: ChainSync.Point): Option[BigInt] = anchor match
    case ChainSync.Point.Origin         => None
    case ChainSync.Point.Block(slot, _) => Some(slot.value)

  /** The Leios header of a Dijkstra header the gate validated, which decoded it as this does; none
    * for a header of another era, which neither offers nor announces.
    */
  def leiosHeader(header: Header): Option[LeiosHeader] = header match
    case h: Header.Shelley if h.era == Era.Dijkstra => LeiosHeader.decode(h.raw).toOption
    case _: Header.Shelley | _: Header.Byron        => None

  /** `getLeiosMaxEbTxsSizeFromView` (`Shelley/Ledger/Leios.hs` 161-163 at 2416493b): a Praos2
    * view's bound, and 0 for the view of an era without Leios (`Storage/LedgerDB/Forker.hs`
    * 975-987); none where the node could not make the view.
    */
  def maxEbTxsSize(bound: LeiosBound): Option[BytesSize] = bound match
    case LeiosBound.Of(b)        => Some(BytesSize.wrap(b.toLong))
    case LeiosBound.WithoutLeios => Some(BytesSize.zero)
    case LeiosBound.Unknown      => None
