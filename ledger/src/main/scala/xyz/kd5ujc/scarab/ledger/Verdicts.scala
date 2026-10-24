// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{OCertError, OCertRules}
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
import xyz.kd5ujc.scarab.crypto.witness.StrictEd25519

/** The pure read side of signature verification: what the rules ask for. Every method is a function
  * of its arguments; an implementation may remember verdicts ([[VerificationCache]]) but must
  * return exactly what [[Verdicts.Direct]] returns. That is the contract the property test checks.
  */
trait Verdicts:
  /** Whether `signature` is a valid Ed25519 witness of `message` under `vkey`. */
  def witness(vkey: Bytes, signature: Bytes, message: Bytes): Boolean

  /** The OCERT signature checks of a header (see [[OCertRules.verifySignatures]]). */
  def opCert(
      header: Header.Shelley,
      slotsPerKesPeriod: BigInt,
      maxKesEvolutions: BigInt
  ): Either[OCertError, Unit]

  /** Praos (Babbage and later) VRF verdict, see
    * [[xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate.verify]].
    */
  def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result

  /** TPraos VRF verdict over an already derived alpha. */
  def tpraosVrf(alpha: Bytes, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result

  /** The Blake2b-224 hash of a verification key. */
  def keyHash(vkey: Bytes): Bytes

  /** A block decoded ahead of time, if any (taken once). */
  def takePrepared(hash: Bytes): Option[Prepared]

object Verdicts:
  /** No memory: every verdict is computed in place. The default for rules run outside the node
    * (tests, tools, the mempool), and the specification of every other implementation.
    */
  object Direct extends Verdicts:
    def witness(vkey: Bytes, signature: Bytes, message: Bytes): Boolean =
      ProviderDefect.witness(StrictEd25519.verify(vkey, signature, message))

    def opCert(
        header: Header.Shelley,
        slotsPerKesPeriod: BigInt,
        maxKesEvolutions: BigInt
    ): Either[OCertError, Unit] =
      OCertRules.verifySignatures(header, slotsPerKesPeriod, maxKesEvolutions)

    def praosVrf(input: Vrf.Input, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
      Vrf.verify(input, vkey, proof, output)

    def tpraosVrf(alpha: Bytes, vkey: Bytes, proof: Bytes, output: Bytes): Vrf.Result =
      Vrf.verifyAlpha(alpha, vkey, proof, output)

    def keyHash(vkey: Bytes): Bytes = xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(vkey)

    def takePrepared(hash: Bytes): Option[Prepared] = None
