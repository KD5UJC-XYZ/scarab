// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.plutus.PlutusExecution
import scala.util.control.NonFatal

/** Pure binding of checked parameter projections and externally acquired single-era geometry. The
  * application must derive time from the pinned genesis/config; this object does not parse genesis
  * or authenticate acquisition. Nothing in the HTTP API may supply these values.
  */
final class PlutusEnvironment private[PlutusEnvironment] (
    val id: Bytes,
    val baseEnvironmentId: Bytes,
    val parameters: PlutusParameters.Checked,
    val time: PlutusContextInput.SlotTime,
    val networkId: Int
)

object PlutusEnvironment:
  private val Max = (BigInt(1) << 64) - 1
  private def bounded(n: BigInt): Boolean = n != null && n >= 0 && n <= Max
  private def hash(b: Bytes): Boolean = b != null && b.value != null && b.size == 32

  def bind(
      base: ClusterTransition.Environment,
      parameters: PlutusParameters.Checked,
      time: PlutusContextInput.SlotTime,
      networkId: Int
  ): Either[String, PlutusEnvironment] =
    try
      require(
        base != null && parameters != null && time != null,
        "complete Plutus environment required"
      )
      require(networkId == 0, "isolated testnet network ID required")
      require(
        hash(base.id) && hash(time.genesisDigest) &&
          time.genesisDigest == base.genesisDigest && parameters.sourceSHA256 == base.parameterDigest,
        "mixed genesis/parameter environment sources"
      )
      require(
        bounded(time.systemStartMillis) && bounded(time.slotLengthNumeratorMillis) &&
          time.slotLengthNumeratorMillis > 0 && bounded(time.slotLengthDenominator) &&
          time.slotLengthDenominator > 0 &&
          time.slotLengthNumeratorMillis.gcd(time.slotLengthDenominator) == 1,
        "canonical positive single-era time geometry required"
      )
      val linear = parameters.linear; val expected = base.feeParameters
      require(
        linear.feePerByte == expected.feePerByte && linear.feeFixed == expected.feeFixed &&
          linear.maxTxSize == expected.maxTxSize && parameters.minimumOutput.coinsPerUTxOByte ==
          base.minimumOutputParameters.coinsPerUTxOByte,
        "mixed ledger parameter projections"
      )
      val domain = Vector(
        PlutusExecution.ProfileId,
        "environment-v1",
        base.id.hex,
        parameters.sourceSHA256.hex,
        time.genesisDigest.hex,
        time.systemStartMillis.toString,
        time.slotLengthNumeratorMillis.toString,
        time.slotLengthDenominator.toString,
        networkId.toString
      ).mkString("\n")
      val id = Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(domain.getBytes(UTF_8)))
      Right(new PlutusEnvironment(id, base.id, parameters, time, networkId))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
