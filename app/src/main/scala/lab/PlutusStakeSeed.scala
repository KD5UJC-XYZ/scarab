// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.ledger.{ConwayStake as Stake, PlutusOutput, TxIn}
import scala.util.control.NonFatal

/** Datum-aware source-bound UTxO/stake component adapter. No runtime attach or epoch authority.
  * Main must add an explicit opt-in ConwayStake/ClusterTransition hook; legacy paths stay closed.
  */
object PlutusStakeSeed:
  final class Prepared private[PlutusStakeSeed] (
      val snapshot: PlutusOutput.Snapshot,
      val sourceSHA256: Bytes,
      val stakeOutputs: Map[TxIn, Stake.Output],
      val instantaneous: Map[Stake.Credential, BigInt]
  ):
    val fullLedgerValidated = false
    def checkpointBytes: Bytes = snapshot.original

  def decode(
      utxo: Bytes,
      sourceSHA256: Bytes,
      networkId: Int,
      exported: Map[Stake.Credential, BigInt]
  ): Either[String, Prepared] =
    try
      require(
        utxo != null && utxo.value != null && utxo.size <= PlutusOutput.MaxSnapshotBytes,
        "bounded whole UTxO required"
      )
      require(
        sourceSHA256 != null && sourceSHA256.size == 32 &&
          ClusterHeaderObservation.sha256(utxo) == sourceSHA256,
        "whole UTxO source pin mismatch"
      )
      val decoded = PlutusOutput
        .snapshot(utxo, networkId)
        .fold(e => throw new IllegalArgumentException(e), identity)
      val outputs = decoded.outputs.map((ref, out) =>
        ref -> Stake.Output(out.stakeCredential, out.coin, out.original)
      )
      val maximum = (BigInt(1) << 64) - 1
      val totals = outputs.values.foldLeft(Map.empty[Stake.Credential, BigInt]) { (seen, output) =>
        output.credential.fold(seen) { credential =>
          val total = seen.getOrElse(credential, BigInt(0)) + output.coin
          require(total <= maximum, "stake aggregate overflow")
          if total == 0 then seen else seen.updated(credential, total)
        }
      }
      require(totals == exported, "whole UTxO/instantaneous component mismatch")
      Right(new Prepared(decoded, sourceSHA256, outputs, totals))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
