// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes
import lab.submission.AdmissionProfile

/** The same closed dispatch is used for first admission and every pool rebuild. */
object AdmissionValidation:
  def prepare[P](
      profile: AdmissionProfile,
      pin: P,
      view: ClusterTransition.State,
      original: Bytes
  ): Either[ScopedAdmission.Failure, ScopedAdmission.Candidate[P]] = profile match
    case AdmissionProfile.AdaVkey      => AdaAdmission.prepare(pin, view, original)
    case AdmissionProfile.NativeScript => NativeAdmission.prepare(pin, view, original)
