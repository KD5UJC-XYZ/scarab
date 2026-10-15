// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{ForgeError, VrfCert}
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.crypto.vrf.{
  Draft03Prover,
  PraosLeaderThreshold as Leader,
  PraosVrfCertificate as Vrf
}

/** The Praos slot-leader check from the forging side: prove the slot with the pool's VRF key and
  * compare the certified output with the threshold of the pool's relative stake. This is exactly
  * the check [[xyz.kd5ujc.scarab.consensus.Praos]] runs on a received header, so a slot that is won
  * here is accepted there.
  */
object Leadership:
  /** The VRF certificate of the header if the pool leads `slot`, else [[ForgeError.NotLeader]]. */
  def check(
      slot: BigInt,
      epochNonce: Nonce,
      key: Draft03Prover.SigningKey,
      stake: BigInt,
      totalStake: BigInt,
      activeSlotsCoeff: Leader.Fraction
  ): Either[ForgeError, VrfCert] =
    if stake <= 0 || totalStake <= 0 then Left(ForgeError.NotLeader(slot))
    else
      def vrfFailure(reason: String): ForgeError = ForgeError.VrfFailure(reason)
      for
        nonce <- epochNonce match
          case Nonce.Neutral => Right(Vrf.NeutralNonce)
          case Nonce.Hash(b) => Vrf.Hash32.fromBytes(b).leftMap(e => vrfFailure(e.toString))
        s <- Vrf.Slot.fromBigInt(slot).leftMap(e => vrfFailure(e.toString))
        input <- Vrf.Input.create(s, nonce).leftMap(e => vrfFailure(e.toString))
        alpha <- Vrf.alpha(input).leftMap(e => vrfFailure(e.toString))
        proved <- key.prove(alpha).leftMap(e => vrfFailure(e.toString))
        value <- Leader.leaderValue(proved.output).leftMap(vrfFailure)
        fraction <- Leader.Fraction.checked(stake, totalStake).leftMap(vrfFailure)
        decision <- Leader.check(value, fraction, activeSlotsCoeff).leftMap(vrfFailure)
        cert <- Either.cond(
          decision == Leader.Decision.Eligible,
          VrfCert(proved.output, proved.proof),
          ForgeError.NotLeader(slot)
        )
      yield cert
