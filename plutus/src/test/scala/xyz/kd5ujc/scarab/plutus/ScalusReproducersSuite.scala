// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import scalus.cardano.ledger.{CardanoInfo, CostModels, Language, MajorProtocolVersion}
import scalus.cardano.onchain.plutus.prelude.{List as PList, Option as POption}
import scalus.cardano.onchain.plutus.v3.{Constitution, GovernanceAction}
import scalus.uplc.builtin.{ByteString, Data}
import scalus.uplc.builtin.Data.toData
import scalus.uplc.eval.{MachineParams, ThreeArguments}

/** Minimal reproducers of the two places where Scalus (1.2.0 and 1.3.0) deviates from the
  * reference. They assert the deviation as it is today, so an upgrade that fixes it fails here and
  * says that the workaround ([[ScalusEvaluator]]'s costing correction, `ScalusDeviations`) can go.
  * Upstream reports: scalus3/scalus#387 (costing) and #388 (constitution encoding).
  */
class ScalusReproducersSuite extends FunSuite:
  test(
    "verifyEd25519Signature is priced by the message (Y) in semantics variant A, not the signature (Z)"
  ) {
    // plutus-core/cost-model/data/builtinCostModelA.json: "type": "linear_in_z". Variant A is
    // PlutusV1/V2 below protocol 9.
    val v2 = CardanoInfo.mainnet.protocolParams.costModels.models(1).toVector
    val params = MachineParams.fromCostModels(
      CostModels(Map(1 -> v2.toIndexedSeq)),
      Language.PlutusV2,
      MajorProtocolVersion(8)
    )
    params.builtinCostModel.verifyEd25519Signature.cpu match
      case ThreeArguments.LinearInY(_) => () // deviation: the reference has LinearInZ
      case other                       => fail(s"fixed upstream? now $other")
  }

  test("a V3 NewConstitution is encoded with the Constitution constructor (fixed in Scalus 1.4.0)") {
    // plutus-ledger-api PlutusLedgerApi.V3.Contexts: `newtype Constitution = Constitution
    // {constitutionScript :: Maybe ScriptHash}` with `makeIsDataSchemaIndexed ''Constitution
    // [('Constitution, 0)]`, so `NewConstitution prev c` is `Constr 5 [prev, Constr 0 [maybe]]`.
    val hash = ByteString.fromArray(Array.fill[Byte](28)(7))
    val action: GovernanceAction =
      GovernanceAction.NewConstitution(POption.None, Constitution(POption.Some(hash)))
    val data = action.toData
    val none = Data.Constr(1, PList.Nil)
    def just(d: Data) = Data.Constr(0, PList(d))
    val expected = Data.Constr(5, PList(none, just(just(Data.B(hash)))))
    assertEquals(data, expected)
  }
