// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.state.ChainDepState
import xyz.kd5ujc.scarab.crypto.bls.{
  BlsError,
  Certificate,
  Committee,
  RbHash,
  Seat,
  VerificationError,
  VerificationKey,
  Weight
}
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{
  EraTag,
  LeiosCommittee,
  NewEpochState,
  ShelleyBasedEra,
  SnapShots,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

export xyz.kd5ujc.scarab.ledger.{LeiosCertificationDefect, LeiosExtValidationError}

/** The Leios committee of a ledger state's tip and the weight a certificate's signers must reach:
  * the result of `leiosCommitteeOfTip` (ouroboros-consensus 2416493b
  * `Ouroboros/Consensus/Ledger/SupportsProtocol.hs:51-59`), with each seat's key decoded once so
  * that a caller can keep it for the epoch.
  */
final case class TipCommittee(committee: Committee, threshold: Weight)

/** A certificate that passed the checks: the announced endorser block it certifies (its hash is the
  * closure to apply), the announcing block's hash it was verified over, and its signers' weight.
  */
final case class Certified(point: EbPoint, rbHash: RbHash, weight: Weight)

/** The checks `Forker.applyBlock` runs on a certifying ranking block before it resolves and applies
  * the certified endorser block's closure (ouroboros-consensus 2416493b
  * `Ouroboros/Consensus/Storage/LedgerDB/Forker.hs:631-666`), all against the unticked state of the
  * block's parent. The two checks the reference leaves as `FIXME` there, the minimum certification
  * gap and the endorser block's capacities, are not run, as there.
  */
object LeiosCertification:

  /** The four checks in the reference's order (ouroboros-consensus 2416493b), each failing with its
    * case of [[LeiosExtValidationError]]:
    *
    *   1. the parent's announcement (`protocolStateLeiosAnnouncement`, `Cardano/Block.hs:1650-1653`
    *      and `Shelley/Ledger/Leios.hs:327-339`): only a Praos2 state holds one, and its point is
    *      the announcing header's slot and the endorser block's hash;
    *   1. the announcing block's hash (`announcingRbHash`, `Shelley/Ledger/Leios.hs:345-350`): the
    *      block's previous hash, none at genesis;
    *   1. the committee of the parent's tip ([[leiosCommitteeOfTip]]), none in an era without
    *      Leios;
    *   1. `verifyLeiosCert` over the announcing block's hash ([[Certificate.verify]]).
    *
    * @param parent
    *   the chain-dependent state of the block's parent, unticked (`headerStateChainDep`)
    * @param previousHash
    *   the block's previous-block hash (`blockPrevHash`), `None` at genesis
    * @param committee
    *   the committee of the parent's ledger state ([[leiosCommitteeOfTip]])
    * @param cert
    *   the certificate the block's body carries
    */
  def check(
      parent: ChainDepState,
      previousHash: Option[Bytes],
      committee: Option[TipCommittee],
      cert: Certificate
  ): Either[LeiosCertificationDefect, Either[LeiosExtValidationError, Certified]] =
    announcement(parent) match
      case None => Right(Left(LeiosExtValidationError.LeiosCertificateWithoutAnnouncement(cert)))
      case Some(point) =>
        previousHash match
          case None =>
            Right(Left(LeiosExtValidationError.LeiosCertificateAfterGenesis(cert, point)))
          case Some(bytes) =>
            RbHash.fromBytes(bytes) match
              case None => Left(LeiosCertificationDefect.PreviousHashWidth(bytes.size))
              case Some(rbHash) =>
                committee match
                  case None =>
                    Right(Left(LeiosExtValidationError.LeiosMissingCommittee(point, cert)))
                  case Some(TipCommittee(members, threshold)) =>
                    Certificate.verify(members, threshold, rbHash, cert) match
                      case Right(weight) => Right(Right(Certified(point, rbHash, weight)))
                      case Left(native: BlsError.Backend) =>
                        Left(LeiosCertificationDefect.Native(native))
                      case Left(error: VerificationError) =>
                        Right(
                          Left(
                            LeiosExtValidationError
                              .LeiosInvalidCertificate(cert, point, rbHash, error)
                          )
                        )

  /** `announcementLeiosPoint <$> protocolStateLeiosAnnouncement` of a chain-dependent state
    * (`LeiosDemoTypes.hs:1768-1770`, `Shelley/Ledger/Leios.hs:327-339`, ouroboros-consensus
    * 2416493b): the point of the endorser block the last applied header announced, at that header's
    * slot (`fromWithOrigin (SlotNo 0) praosStateLastSlot`). The states of the protocols before
    * Praos2 hold no announcement.
    */
  def announcement(state: ChainDepState): Option[EbPoint] = state match
    case ChainDepState.Praos2(s) =>
      s.announcement.map { announced =>
        val slot = s.praos.lastSlot match
          case WithOrigin.At(at) => Word64.fromUnsigned(at.word.bits)
          case WithOrigin.Origin => Word64.fromUnsigned(0L)
        EbPoint(slot, announced.announcement.hash)
      }
    case ChainDepState.PBft(_) | ChainDepState.TPraos(_) | ChainDepState.Praos(_) => None

  /** `leiosCommitteeOfTip` (`Ledger/SupportsProtocol.hs:51-59`) of a ledger state: the ledger view
    * at the state's own tip, whose Dijkstra form (`getLeiosCommitteeFromView`,
    * `Shelley/Ledger/Ledger.hs:990-993`) holds the committee of the set snapshot and the quorum
    * parameter (`mkDijkstraForecast`, cardano-ledger 9df9f98c `Dijkstra/Forecast.hs:128-139`).
    * Every era before Dijkstra has none (`LeiosDemoTypes.hs:1494-1498`).
    */
  def leiosCommitteeOfTip[E <: EraTag](nes: NewEpochState[E])(using
      era: ShelleyBasedEra[E]
  ): Either[LeiosCertificationDefect, Option[TipCommittee]] =
    committeeBasis(nes).flatMap(_.traverse(tipCommittee.tupled))

  /** What [[leiosCommitteeOfTip]] makes its committee of: the seated committee of the set snapshot
    * and the quorum parameter, none before Dijkstra. Equal bases give equal committees, so a caller
    * may keep the committee of a basis and use it again for an equal one.
    */
  def committeeBasis[E <: EraTag](nes: NewEpochState[E])(using
      era: ShelleyBasedEra[E]
  ): Either[LeiosCertificationDefect, Option[(LeiosCommittee, Rational)]] =
    era match
      case ShelleyBasedEra.SDijkstra =>
        nes.es.snapshots.layout match
          case SnapShots.Layout.Leios(_, _, _, set) =>
            Right(Some((set, EraLedger.curPParams(nes.es.ls.utxo.gov).leiosQuorumStakeThreshold)))
          case SnapShots.Layout.Node11_1 => Left(LeiosCertificationDefect.CommitteeOfNode11_1)
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
        Right(None)

  /** The committee of a seated ledger committee and a quorum (`unboundRational`): each seat weighs
    * its pool's stake ratio, and its key, honoured when it was seated, is decoded without checking
    * its proof of possession again.
    */
  def tipCommittee(
      seated: LeiosCommittee,
      quorum: Rational
  ): Either[LeiosCertificationDefect, TipCommittee] =
    for
      seats <- seated.seats.zipWithIndex.traverse { (seat, index) =>
        seat.verificationKey
          .traverse(VerificationKey.fromBytes)
          .bimap(
            LeiosCertificationDefect.UndecodableSeatKey(index, _),
            Seat(Weight(seat.weight), _)
          )
      }
      committee <- Committee.honoured(seats).leftMap(LeiosCertificationDefect.Unaddressable(_))
    yield TipCommittee(committee, Weight(quorum))
