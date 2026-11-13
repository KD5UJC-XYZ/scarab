// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.uplc.SemanticsVariant

/** The semantics variants of plutus 1.70.0.0 (`507cab8c`), A to E
  * (`PlutusCore/Default/Builtins.hs:1083-1088`): the ones the unlifting and the denotations here
  * know. F and G are 1.71.0.0's, which splits `ensurable` in two and is out of scope (ADR 0027), so
  * they are refused rather than taken as D and E.
  */
enum CoreVariant:
  case A, B, C, D, E

  /** Whether the variant reads the on-chain bounded types `CInteger` and `CByteString` (and
    * `Word64` for `constrData`): `ensurable` (`Builtins.hs:2788-2793`), true for D and E.
    */
  def ensurable: Boolean = this match
    case D | E     => true
    case A | B | C => false

/** A semantics variant that plutus 1.70.0.0 does not have. */
final case class UnsupportedVariant(variant: SemanticsVariant)

object CoreVariant:
  def of(variant: SemanticsVariant): Either[UnsupportedVariant, CoreVariant] = variant match
    case SemanticsVariant.A                      => Right(A)
    case SemanticsVariant.B                      => Right(B)
    case SemanticsVariant.C                      => Right(C)
    case SemanticsVariant.D                      => Right(D)
    case SemanticsVariant.E                      => Right(E)
    case SemanticsVariant.F | SemanticsVariant.G => Left(UnsupportedVariant(variant))
