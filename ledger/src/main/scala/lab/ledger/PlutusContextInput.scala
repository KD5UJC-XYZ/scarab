// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.Bytes
import lab.submission.SignedTransaction

/** Source material for a bounded context derivation, not a phase-one or admission proof. Derivers
  * must validate all fields. The phase-one gate separately owns private checked facts. Collateral
  * is never included in txInfoInputs or inferred txInfoSignatories. Signatories come only from body
  * required-signers, which this first profile excludes.
  */
final case class PlutusContextInput(
    transaction: SignedTransaction,
    ordinaryInputs: Vector[PlutusContextInput.ResolvedInput],
    collateralInputs: Vector[PlutusContextInput.ResolvedInput],
    time: PlutusContextInput.SlotTime
)

object PlutusContextInput:
  final case class ResolvedInput(transactionId: Bytes, index: BigInt, originalOutput: Bytes)

  /** Exact single-era geometry: start + slot * numerator / denominator, in POSIX milliseconds. The
    * context deriver must reject unsupported fractional endpoint times, nonpositive geometry or
    * unbound source parameters. It must never substitute network defaults.
    */
  final case class SlotTime(
      systemStartMillis: BigInt,
      slotLengthNumeratorMillis: BigInt,
      slotLengthDenominator: BigInt,
      genesisDigest: Bytes
  )
