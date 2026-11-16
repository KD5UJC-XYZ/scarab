// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

/** A semantics variant of the builtins, the reference's `BuiltinSemanticsVariant DefaultFun`.
  * Plutus 1.71.0.0 (`261766ad`) defines A to G (`PlutusCore/Default/Builtins.hs:1084-1091`); F and
  * G, for protocol 12, are new since 1.70.0.0 (`507cab8c`), which defines A to E
  * (`Builtins.hs:1083-1088` there). A variant selects the denotation of a builtin where they
  * differ, and the default cost model whose shape the on-chain parameters fill:
  * `builtinCostModel<V>.json` and `cekMachineCosts<V>.json`, D's for F and E's for G
  * (`PlutusCore/Evaluation/Machine/ExBudgetingDefaults.hs:66-159`,
  * `PlutusCore/DataFilePaths.hs:11-41`). Variants are not ledger languages: V1 and V2 share theirs.
  */
enum SemanticsVariant:
  case A, B, C, D, E, F, G

object SemanticsVariant:

  /** The variant the reference evaluates a script of `language` with at protocol `protocol`, at
    * plutus 1.71.0.0: the `toSemVar` argument of `mkDynEvaluationContext` in
    * `PlutusLedgerApi/V1/EvaluationContext.hs:57-64` (V2's file is the same, lines 57-64) and
    * `V3/EvaluationContext.hs:51-58`, tabulated in `Common/ProtocolVersions.hs:127-148`. Below
    * protocol 12 it is 1.70.0.0's (`V1/EvaluationContext.hs:53-57`, `V3/EvaluationContext.hs:50-53`
    * there). A V4 script is evaluated as V3 ([[PlutusLanguage.evaluatedAs]]); plutus's own V4
    * context gives G at every protocol (`V4/EvaluationContext.hs:37-46`), which is V3's from
    * protocol 12, the first at which a V4 script decodes. It is total, as the reference's must be
    * (`Common/Eval.hs:202-204`).
    */
  def of(language: PlutusLanguage, protocol: ProtocolMajor): SemanticsVariant =
    val p = protocol.value
    language.evaluatedAs match
      case PlutusLanguage.V1 | PlutusLanguage.V2 =>
        if p < ProtocolMajor.Chang.value then A
        else if p < ProtocolMajor.VanRossem.value then B
        else if p < ProtocolMajor.Dijkstra.value then D
        else F
      case PlutusLanguage.V3 =>
        if p < ProtocolMajor.VanRossem.value then C
        else if p < ProtocolMajor.Dijkstra.value then E
        else G
      case PlutusLanguage.V4 => G

  /** The variants an evaluation context of `language` holds machine parameters for: the `semVars`
    * list of `mkDynEvaluationContext` in the same files (V1 and V2 lines 52-56, V3 line 49, plutus
    * 1.71.0.0). The context fills the default cost model of each of them with the same on-chain
    * parameters (`Common/Eval.hs:209-219`), so these are the cost-model shapes a language's
    * parameters must fit.
    */
  def ofContext(language: PlutusLanguage): Vector[SemanticsVariant] = language.evaluatedAs match
    case PlutusLanguage.V1 | PlutusLanguage.V2 => Vector(A, B, D, F)
    case PlutusLanguage.V3                     => Vector(C, E, G)
    case PlutusLanguage.V4                     => Vector(G)
