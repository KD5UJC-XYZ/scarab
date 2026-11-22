// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.crypto.bls.{BlsError, Certificate, RbHash, VerificationError}
import xyz.kd5ujc.scarab.protocol.EbPoint

// The two outcomes of the Leios checks of a certifying ranking block that are no certificate
// (`LeiosCertification.check`, consensus), here so that a block's refusal (`BlockFailure`) and the
// node's stop (`ChainError`) hold them typed.

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

  def message: String = this match
    case CommitteeOfNode11_1 => "a Dijkstra state of the 11.1 layout holds no Leios committee"
    case UndecodableSeatKey(seat, cause) =>
      s"the key of committee seat $seat does not decode: $cause"
    case Unaddressable(cause) => s"the committee has more seats than a Word16 names: $cause"
    case PreviousHashWidth(size) =>
      s"the previous-block hash is $size bytes, not a header hash's 32"
    case Native(cause) => s"the BLS library failed verifying the certificate: $cause"

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

  def message: String = this match
    case LeiosCertificateWithoutAnnouncement(_) =>
      "a Leios certificate whose parent announced no endorser block"
    case LeiosMissingCommittee(point, _) =>
      s"a Leios certificate of ${point.hash.bytes.hex} in an era without a Leios committee"
    case LeiosCertificateAfterGenesis(_, point) =>
      s"a Leios certificate of ${point.hash.bytes.hex} at genesis"
    case LeiosInvalidCertificate(_, point, _, error) =>
      s"an invalid Leios certificate of ${point.hash.bytes.hex}: $error"
