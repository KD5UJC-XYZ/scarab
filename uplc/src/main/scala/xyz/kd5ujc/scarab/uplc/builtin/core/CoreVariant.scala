// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.uplc.SemanticsVariant

/** The semantics variants as the unlifting and the denotations here tell them apart: plutus
  * 1.71.0.0's A to G (`PlutusCore/Default/Builtins.hs:1084-1091`, `261766ad`). Where a builtin's
  * variants differ, they differ in the Haskell type an argument is read as, chosen by two
  * predicates (`Builtins.hs:2806-2820`): [[ensurableInteger]] and [[ensurableByteString]]. Plutus
  * 1.70.0.0 (`507cab8c`) had one, `ensurable`, true for D and E (`Builtins.hs:2788-2793` there),
  * which 1.71.0.0 splits so that F and G keep its bounds on byte strings and drop those on
  * integers.
  */
enum CoreVariant:
  case A, B, C, D, E, F, G

  /** Whether the variant reads the integers of the arithmetic builtins and of `consByteString` in D
    * as the on-chain bounded `CInteger`: `ensurableInteger` (`Builtins.hs:2806-2811`, plutus
    * 1.71.0.0), true for D and E. It is 1.70.0.0's `ensurable` for every variant 1.70.0.0 has.
    */
  def ensurableInteger: Boolean = this match
    case D | E             => true
    case A | B | C | F | G => false

  /** Whether the variant reads the byte strings it bounds as the on-chain bounded `CByteString`,
    * `Word64` for `constrData`, `Int` for the shifts and rotations, and the bounded scalar and
    * input of `bls12_381_G*_scalarMul` and `writeBits`: `ensurableByteString`
    * (`Builtins.hs:2813-2820`, plutus 1.71.0.0), true for D to G. It too is 1.70.0.0's `ensurable`
    * for the variants 1.70.0.0 has.
    */
  def ensurableByteString: Boolean = this match
    case D | E | F | G => true
    case A | B | C     => false

object CoreVariant:
  def of(variant: SemanticsVariant): CoreVariant = variant match
    case SemanticsVariant.A => A
    case SemanticsVariant.B => B
    case SemanticsVariant.C => C
    case SemanticsVariant.D => D
    case SemanticsVariant.E => E
    case SemanticsVariant.F => F
    case SemanticsVariant.G => G
