// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.util.Base64
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PlutusFailure
import xyz.kd5ujc.scarab.plutus.{DataSerialiser, EvalRequest, PlutusLanguage, ScalusEvaluator}

/** Wire presentation only: ledger-core/Plutus/Evaluate.hs `ToCBOR PlutusWithContext` and
  * Alonzo/Rules/Utxos.hs `scriptFailureToFailureDescription`, pinned at f649f975.
  */
private[ntc] object PlutusFailureCbor:
  def context(request: EvalRequest): Bytes = CborOut.build { w =>
    w.array(5).uint(request.protocolMajor)
    w.array(2).uint(request.language.id).bytes(request.script)
    if request.language != PlutusLanguage.V3 then w.array(request.args.size)
    request.args.foreach(arg =>
      w.raw(Bytes.fromArray(DataSerialiser.serialise(ScalusEvaluator.toData(arg))))
    )
    w.array(2).uint(request.budget.mem).uint(request.budget.steps)
    w.array(request.costModel.size)
    request.costModel.foreach(value => w.int(BigInt(value)))
  }

  def reconstruction(failure: PlutusFailure): Bytes =
    failure.request.fold(failure.reconstructionDetail) { request =>
      Bytes.fromArray(Base64.getEncoder.encode(context(request).toArray))
    }
