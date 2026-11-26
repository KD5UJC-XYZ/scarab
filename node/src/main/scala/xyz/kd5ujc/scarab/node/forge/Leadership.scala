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

/** The Praos slot-leader check from the forging side: evaluate the pool's VRF key on the slot and
  * compare the output with the threshold of the pool's relative stake, then prove a won slot's
  * output for its certificate. This is exactly the check [[xyz.kd5ujc.scarab.consensus.Praos]] runs
  * on a received header, so a slot that is won here is accepted there.
  */
object Leadership:
  /** The two evaluations of the pool's VRF key that [[decide]] makes: the output of a slot's input,
    * for every slot, and the proof with the output it certifies, for a won slot only.
    */
  private[forge] trait Evaluator:
    def output(alpha: Bytes): Either[Draft03Prover.Failure, Bytes]
    def prove(alpha: Bytes): Either[Draft03Prover.Failure, Draft03Prover.Proved]

  private[forge] object Evaluator:
    def of(key: Draft03Prover.SigningKey): Evaluator = new Evaluator:
      def output(alpha: Bytes): Either[Draft03Prover.Failure, Bytes] = key.output(alpha)
      def prove(alpha: Bytes): Either[Draft03Prover.Failure, Draft03Prover.Proved] =
        key.prove(alpha)

    /** The re-check of a slot the schedule leads (#1101, decision 5): `key` 's output evaluated
      * again, and the schedule's certificate `cert` for the proof, which the schedule built from
      * the same key and input.
      */
    def again(key: Evaluator, cert: VrfCert): Evaluator = new Evaluator:
      def output(alpha: Bytes): Either[Draft03Prover.Failure, Bytes] = key.output(alpha)
      def prove(alpha: Bytes): Either[Draft03Prover.Failure, Draft03Prover.Proved] =
        Right(Draft03Prover.Proved(cert.proof, cert.output))

  /** The pool's VRF key as the forger lends it (#1418): for one evaluation at a time, under a lease
    * on the credentials in force ([[HotKeys]]), so that a reload never erases the key while it is
    * evaluated, and the key is erased once the evaluations in flight under it end.
    */
  private[forge] trait Keys[F[_]]:
    /** `f` with the VRF key in force, its verification key and its evaluations, both of the one
      * generation of credentials leased for it; none while none is in force. `f` is a computation,
      * not an effect: the lease lasts as long as it runs, and so never across a wait.
      */
    def lent[A](f: (Bytes, Evaluator) => A): F[Option[A]]

    /** [[lent]], if the key in force is the key whose verification key is `vrf`; none while another
      * key or none is in force.
      */
    def evaluating[A](vrf: Bytes)(f: Evaluator => A): F[Option[A]]

  /** The VRF certificate of the header if the pool leads `slot`, else [[ForgeError.NotLeader]]. The
    * check is on the stake alone, as `checkIsLeader` of `Protocol/Praos.hs` (consensus 82ecba329
    * 402-428, unchanged at 808e4005 408-434) makes it: whether the VRF key is the one the pool
    * registered is for the block's validation to say. A pool absent from the distribution has a
    * relative stake of zero (`meetsLeaderThreshold`, 82ecba329 535-555), which leads no slot unless
    * the active-slot coefficient is one.
    *
    * The reference evaluates the certified VRF (`VRF.evalCertified`, the output with its proof) for
    * every slot; Scarab computes the output alone and builds the proof only for a won slot
    * ([[decide]]), with the same decision and the same certificate.
    */
  def check(
      slot: BigInt,
      epochNonce: Nonce,
      key: Draft03Prover.SigningKey,
      stake: BigInt,
      totalStake: BigInt,
      activeSlotsCoeff: Leader.Fraction
  ): Either[ForgeError, VrfCert] =
    decide(slot, epochNonce, Evaluator.of(key), stake, totalStake, activeSlotsCoeff)

  /** [[check]] of `slot` with the inputs of its epoch and the evaluations of the key they name. */
  private[forge] def check(
      slot: BigInt,
      inputs: EpochInputs,
      key: Evaluator
  ): Either[ForgeError, VrfCert] =
    decide(slot, inputs.nonce, key, inputs.stake, inputs.total, inputs.activeSlotsCoeff)

  /** [[check]] with the key's evaluations given: the output, its leader value and the threshold
    * decide the slot, and only a won slot is proved.
    */
  private[forge] def decide(
      slot: BigInt,
      epochNonce: Nonce,
      key: Evaluator,
      stake: BigInt,
      totalStake: BigInt,
      activeSlotsCoeff: Leader.Fraction
  ): Either[ForgeError, VrfCert] =
    for
      nonce <- epochNonce match
        case Nonce.Neutral => Right(Vrf.NeutralNonce)
        case Nonce.Hash(b) => Vrf.Hash32.fromBytes(b).leftMap(ForgeError.VrfInput(_))
      s <- Vrf.Slot.fromBigInt(slot).leftMap(ForgeError.VrfInput(_))
      input <- Vrf.Input.create(s, nonce).leftMap(ForgeError.VrfInput(_))
      alpha <- Vrf.alpha(input).leftMap(ForgeError.VrfInput(_))
      output <- key.output(alpha).leftMap(ForgeError.VrfProof(_))
      value <- Leader.leaderValue(output).leftMap(ForgeError.LeaderThreshold(_))
      fraction <- (if stake <= 0 then Leader.Fraction.checked(0, 1)
                   else Leader.Fraction.checked(stake, totalStake))
        .leftMap(ForgeError.LeaderThreshold(_))
      decision <- Leader
        .check(value, fraction, activeSlotsCoeff)
        .leftMap(ForgeError.LeaderThreshold(_))
      _ <- Either.cond(decision == Leader.Decision.Eligible, (), ForgeError.NotLeader(slot))
      proved <- key.prove(alpha).leftMap(ForgeError.VrfProof(_))
    yield VrfCert(proved.output, proved.proof)
