// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import scala.util.control.NonFatal

/** Pure successor execution binding. The coordinator must authenticate governance parameter roles,
  * all automatic effects and header eligibility before publishing the resulting whole candidate.
  * This capability neither authorizes an epoch crossing nor imports an observed successor state.
  */
private[lab] object PlutusSuccessorBinding:
  final class Checked private[PlutusSuccessorBinding] (
      private[PlutusSuccessorBinding] val source: ClusterTransition.State,
      val preview: ConwayEpochBoundary.Preview,
      val environment: ClusterTransition.Environment
  )

  private def get[E, A](value: Either[E, A]): A =
    value.fold(e => throw new IllegalArgumentException(e.toString), identity)

  def prepare(
      before: ClusterTransition.State,
      preview: ConwayEpochBoundary.Preview,
      parameters: PlutusParameters.Checked
  ): Either[String, Checked] =
    try
      require(
        before != null && preview != null && parameters != null,
        "complete successor inputs required"
      )
      val old = before.environment
      val execution =
        old.plutus.getOrElse(throw new IllegalArgumentException("attached Plutus source required"))
      val stake = preview.before.stake
      require(
        stake.ledgerId == before.id && stake.revision == before.revision &&
          stake.slot == before.slot && stake.epoch == old.epoch && preview.epoch == old.epoch + 1,
        "successor preview ledger identity/revision/epoch mismatch"
      )
      require(
        preview.signal.slot > before.slot &&
          preview.signal.slot / stake.context.epochLength == preview.epoch,
        "successor preview slot mismatch"
      )
      val base = get(
        ClusterTransition.environment(
          old.genesisDigest,
          parameters.sourceSHA256,
          old.networkMagic,
          preview.epoch,
          9,
          0,
          parameters.linear.feePerByte,
          parameters.linear.feeFixed,
          parameters.linear.maxTxSize,
          parameters.minimumOutput.coinsPerUTxOByte
        )
      )
      val bound = get(PlutusEnvironment.bind(base, parameters, execution.time, execution.networkId))
      val next = get(ClusterTransition.withPlutus(base, bound))
      require(next.id != old.id, "successor execution identity must change")
      Right(new Checked(before, preview, next))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))

  def forSource(
      binding: Checked,
      before: ClusterTransition.State,
      preview: ConwayEpochBoundary.Preview
  ): Either[String, ClusterTransition.Environment] =
    if binding == null || before == null || preview == null ||
      !(binding.source eq before) || !(binding.preview eq preview)
    then Left("stale/foreign Plutus successor binding")
    else Right(binding.environment)
