// SPDX-License-Identifier: Apache-2.0
package lab

import ReferenceJson.{Json as J}
import PlutusResearchIO.{record, text, num, sha}
import lab.ledger.ConwayNativeLikelihood

/** Actual selected component identities, linked to original generation requests rather than
  * filenames.
  */
private[lab] object PlutusRepeatedPublication:
  def fields(state: CoherentSequence.State): J =
    val b = state.syntheticBoundary.getOrElse(
      throw new IllegalArgumentException("repeated component required")
    )
    require(b.repeated, "explicit repeated component required")
    require(
      b.frozenId.isDefined == b.checkedLikelihood.isDefined,
      "published repeated freeze must have checked evidence"
    )
    record(
      "epoch" -> num(state.ledger.environment.epoch),
      "componentId" -> text(b.id.hex),
      "transitions" -> num(b.transitions),
      "frozenId" -> b.frozenId.fold[J](J.Lit("null"))(x => text(x.hex)),
      "allocationId" -> b.allocationId.fold[J](J.Lit("null"))(x => text(x.hex)),
      "nonMyopicId" -> text(b.nonMyopic.id.hex),
      "checkedLikelihood" -> b.checkedLikelihood.fold[J](J.Lit("null")) { g =>
        require(
          g.mode == b.generationMode &&
            (g.mode == ConwayNativeLikelihood.Mode.CheckedJvm || g.mode == ConwayNativeLikelihood.Mode.PureJvm) && !g.nativeValuesAuthoritative &&
            g.jvmMismatchWords == 0 && b.frozenId.contains(g.request.frozen.id),
          "selected checked likelihood identity mismatch"
        )
        val f = state.syntheticRewards
          .flatMap(_.frozen)
          .getOrElse(throw new IllegalArgumentException("selected monetary freeze required"))
        require(
          g.forFrozen(f, f.id).isRight,
          "selected generation request has foreign frozen source"
        )
        record(
          "frozenId" -> text(f.id.hex),
          "applicationEpoch" -> num(f.epoch),
          "observedSlot" -> num(f.observedSlot),
          "preTickTupleId" -> text(f.preTickTupleId.hex),
          "requestSHA256" -> text(sha(g.request.original).hex),
          "evidenceSHA256" -> text(sha(g.evidence).hex),
          "nativeResponseSHA256" -> g.nativeResponse.fold[J](J.Lit("null"))(x => text(sha(x).hex)),
          "computedRaw32Words" -> num(g.computedRaw32Words),
          "computedRaw64Words" -> num(g.computedRaw64Words),
          "nativeValidated" -> J.Lit(g.nativeValidated.toString),
          "diagnosticNativeDependency" -> J.Lit(g.diagnosticNativeDependency.toString),
          "raw32Comparisons" -> num(g.raw32Comparisons),
          "raw64Comparisons" -> num(g.raw64Comparisons),
          "jvmMismatchWords" -> num(g.jvmMismatchWords),
          "mode" -> text(
            if g.mode == ConwayNativeLikelihood.Mode.PureJvm then "pure-jvm" else "checked-jvm"
          )
        )
      }
    )
