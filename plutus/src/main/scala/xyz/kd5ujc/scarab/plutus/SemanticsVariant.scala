// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scalus.uplc.BuiltinSemanticsVariant

/** The builtin semantics variants of plutus-core 1.71.0.0 (`261766ad`,
  * `PlutusCore/Default/Builtins.hs:1083-1091`). Variants A to E are those of 1.70.0.0; F and G are
  * new for Dijkstra.
  *
  * F and G mean what D and E mean, with one difference: the integer arguments that D and E unlift
  * as `CInteger` (`ensurableInteger`, lines 2806-2811) are plain `Integer` s, so they have no
  * bounds. Byte strings stay bounded (`ensurableByteString`, lines 2813-2820), as do the `Word64`
  * index of `constrData` and the `Int` shift amounts. F's costs are D's and G's are E's
  * (`ExBudgetingDefaults.hs:158-159, 185-186`). Scalus 1.4.0 knows variants A to E only, so the
  * machine runs F and G under the Scalus variant of the same meaning ([[scalus]]), and this node
  * applies the bounds itself ([[UnliftingBounds]]).
  */
enum SemanticsVariant:
  case A, B, C, D, E, F, G

  /** The Scalus variant with the same builtin meanings and costing functions. */
  def scalus: BuiltinSemanticsVariant = this match
    case A     => BuiltinSemanticsVariant.A
    case B     => BuiltinSemanticsVariant.B
    case C     => BuiltinSemanticsVariant.C
    case D | F => BuiltinSemanticsVariant.D
    case E | G => BuiltinSemanticsVariant.E

  /** `ensurableInteger`: integer arguments are unlifted as bounded `CInteger`s. */
  def boundsIntegers: Boolean = this == D || this == E

  /** `ensurableByteString`: byte-string arguments are unlifted as bounded `CByteString`s. */
  def boundsByteStrings: Boolean = this == D || this == E || this == F || this == G

object SemanticsVariant:
  /** The variant of a script's language at a protocol version (`machineParametersFor`,
    * `PlutusLedgerApi/MachineParameters.hs:16-44`, and the `mkEvaluationContext` of
    * `V1/EvaluationContext.hs:46-66`, `V2/EvaluationContext.hs:46-66` and
    * `V3/EvaluationContext.hs:43-60`, 1.71.0.0). A PlutusV4 script is evaluated in the PlutusV3
    * evaluation context ([[PlutusLanguage.evaluatedAs]]); the language does not exist below
    * Dijkstra.
    */
  def of(language: PlutusLanguage, protocolMajor: Int): SemanticsVariant =
    import PlutusAvailability.{Chang, Dijkstra, VanRossem}
    language.evaluatedAs match
      case PlutusLanguage.V1 | PlutusLanguage.V2 =>
        if protocolMajor < Chang then A
        else if protocolMajor < VanRossem then B
        else if protocolMajor < Dijkstra then D
        else F
      case _ =>
        if protocolMajor < VanRossem then C
        else if protocolMajor < Dijkstra then E
        else G
