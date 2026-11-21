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

/** The Leios committee of a ledger state's tip and the weight a certificate's signers must reach:
  * the result of `leiosCommitteeOfTip` (ouroboros-consensus 2416493b
  * `Ouroboros/Consensus/Ledger/SupportsProtocol.hs:51-59`), with each seat's key decoded once so
  * that a caller can keep it for the epoch.
  */
final case class TipCommittee(committee: Committee, threshold: Weight)

/** Why the Leios checks of a certifying block reach no verdict: each a state the reference cannot
  * hold or a failure of the native BLS library. None is a [[LeiosExtValidationError]], and none may
  * be read as one.
  */
enum LeiosCertificationDefect:
  /** A Dijkstra state of the 11.1 layout, whose snapshots hold no committee; the Leios build holds
    * none such ([[xyz.kd5ujc.scarab.protocol.DijkstraNotYet.LedgerState]]).
    */
  case CommitteeOfNode11_1

  /** A seated key the ledger stored that does not decode; the selection decoded each key it seated
    * ([[xyz.kd5ujc.scarab.ledger.state.LeiosCommitteeSelection.select]]).
    */
  case UndecodableSeatKey(seat: Int, cause: BlsError)

  /** A committee of more seats than a `Word16` seat id names; the committee size is a `Word16`. */
  case Unaddressable(cause: BlsError)

  /** The previous-block hash is not the 32 bytes of a header hash. */
  case PreviousHashWidth(size: Int)

  /** The native library failed during the aggregate verification. */
  case Native(cause: BlsError.Backend)

/** `LeiosExtValidationError` (ouroboros-consensus 2416493b `LeiosDemoTypes.hs:1436-1447`): why a
  * certifying ranking block is invalid before its endorser block's closure is applied, the four
  * cases `Forker.applyBlock` raises. The fifth, `LeiosCertificateForecastRejected`, is the verdict
  * of chain selection's forecast check (`ChainDB/Impl/ChainSel.hs`), not of block application.
  */
enum LeiosExtValidationError:
  /** The parent's chain-dependent state holds no announcement: there is no endorser block to
    * certify.
    */
  case LeiosCertificateWithoutAnnouncement(cert: Certificate)

  /** The parent's ledger state is of an era without a Leios committee. */
  case LeiosMissingCommittee(point: EbPoint, cert: Certificate)

  /** The block has no parent, so it would certify at genesis. */
  case LeiosCertificateAfterGenesis(cert: Certificate, point: EbPoint)

  /** The certificate fails `verifyLeiosCert` over the announcing block's hash. */
  case LeiosInvalidCertificate(
      cert: Certificate,
      point: EbPoint,
      rbHash: RbHash,
      error: VerificationError
  )

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
    era match
      case ShelleyBasedEra.SDijkstra =>
        nes.es.snapshots.layout match
          case SnapShots.Layout.Leios(_, _, _, set) =>
            tipCommittee(set, EraLedger.curPParams(nes.es.ls.utxo.gov).leiosQuorumStakeThreshold)
              .map(Some(_))
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
