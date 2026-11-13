// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

/** A semantics variant of the builtins, the reference's `BuiltinSemanticsVariant DefaultFun`.
  * Plutus 1.70.0.0 (`507cab8c`) defines A to E (`PlutusCore/Default/Builtins.hs:1083-1088`);
  * 1.71.0.0 (`261766ad`) adds F and G for protocol 12 (`Builtins.hs:1084-1091` there). A variant
  * selects the denotation of a builtin where they differ, and the default cost model whose shape
  * the on-chain parameters fill: `builtinCostModel<V>.json` and `cekMachineCosts<V>.json`
  * (`PlutusCore/Evaluation/Machine/ExBudgetingDefaults.hs:66-157`,
  * `PlutusCore/DataFilePaths.hs:11-41`). Variants are not ledger languages: V1 and V2 share theirs.
  */
enum SemanticsVariant:
  case A, B, C, D, E, F, G

object SemanticsVariant:

  /** The variant the reference evaluates a script of `language` with at protocol `protocol`, at
    * plutus 1.70.0.0: the `toSemVar` argument of `mkDynEvaluationContext` in
    * `PlutusLedgerApi/V1/EvaluationContext.hs:53-57` (V2's file is the same, lines 53-57) and
    * `V3/EvaluationContext.hs:50-53`, tabulated in `Common/ProtocolVersions.hs:127-146`. A V4
    * script is evaluated as V3 ([[PlutusLanguage.evaluatedAs]]); plutus's own V4 context, which the
    * node of 1.70.0.0 does not call, would give E (`V4/EvaluationContext.hs:47`). It is total, as
    * the reference's must be (`Common/Eval.hs:204-205`): at protocol 12 and later 1.70.0.0 still
    * gives D and E; F and G are 1.71.0.0's and are not returned here.
    */
  def of(language: PlutusLanguage, protocol: ProtocolMajor): SemanticsVariant =
    val p = protocol.value
    language.evaluatedAs match
      case PlutusLanguage.V1 | PlutusLanguage.V2 =>
        if p < ProtocolMajor.Chang.value then A
        else if p < ProtocolMajor.VanRossem.value then B
        else D
      case PlutusLanguage.V3 => if p < ProtocolMajor.VanRossem.value then C else E
      case PlutusLanguage.V4 => E

  /** The variants an evaluation context of `language` holds machine parameters for: the `semVars`
    * list of `mkDynEvaluationContext` in the same files (V1 and V2 line 51, V3 line 48). The
    * context fills the default cost model of each of them with the same on-chain parameters
    * (`Common/Eval.hs:209-219`), so these are the cost-model shapes a language's parameters must
    * fit.
    */
  def ofContext(language: PlutusLanguage): Vector[SemanticsVariant] = language.evaluatedAs match
    case PlutusLanguage.V1 | PlutusLanguage.V2 => Vector(A, B, D)
    case PlutusLanguage.V3                     => Vector(C, E)
    case PlutusLanguage.V4                     => Vector(E)
