// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import cats.data.NonEmptyList
import cats.parse.Parser
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{DefaultUni, ProgramVersion, Text, Value}
import xyz.kd5ujc.scarab.uplc.Constant.ElementTypeMismatch

/** A type of the textual syntax with its kind: a type of kind `*`, or one of the type operators of
  * the universe, bare or applied to a first argument. The parser checks the kinds of a type
  * application as it reads it (`defaultUniApplication`, `tryUniApply`,
  * `PlutusCore/Parser/Type.hs:93-101`, `Universe/Core.hs:619-625`, plutus 1.70.0.0 `507cab8c`).
  */
enum Kinded:
  case Star(t: DefaultUni)
  case ListOf
  case ArrayOf
  case PairOf
  case PairWith(first: DefaultUni)

  /** This operator applied to `argument`, none when the kinds do not match (`withApplicable`). */
  def apply(argument: Kinded): Option[Kinded] = (this, argument) match
    case (ListOf, Star(a))      => Some(Star(DefaultUni.List(a)))
    case (ArrayOf, Star(a))     => Some(Star(DefaultUni.Array(a)))
    case (PairOf, Star(a))      => Some(PairWith(a))
    case (PairWith(a), Star(b)) => Some(Star(DefaultUni.Pair(a, b)))
    case _                      => None

/** Why a BLS12-381 literal is no point of its group: the checks of `uncompress`
  * (`PlutusCore/Crypto/BLS12_381/G1.hs:148-149`, `G2.hs` likewise), which calls cardano-base's
  * `blsUncompress` (not vendored): the length of a compressed point, blst's decoding, and the
  * subgroup.
  */
enum PointProblem:
  case WrongLength(expected: Int, actual: Int)
  case NotCompressed
  case Malformed
  case NotInGroup

/** Why a text is not a program of the textual syntax. Each case is a refusal of the reference's
  * parser (`UntypedPlutusCore/Parser.hs` and the modules of `PlutusCore/Parser`); any of them is a
  * `parse/decode error` to the conformance runner (`PlutusConformance/Common.hs:374-382`).
  */
enum TextError:
  /** The text does not follow the grammar; `expected` is what cats-parse looked for at `offset`. */
  case Syntax(offset: Int, expected: NonEmptyList[Parser.Expectation])

  /** A character of a string literal that `readLitChar` does not read (`Builtin.hs:86-87`), or the
    * end of the text before the closing quote.
    */
  case CharacterLiteral(offset: Int)

  /** `(builtin f)` where `f` names no builtin (`UnknownBuiltinFunction`, `Builtin.hs:55-63`). */
  case UnknownBuiltin(name: String)

  /** `constr` in a program before version 1.1.0 (`UntypedPlutusCore/Parser.hs:79`). */
  case ConstrBeforeV110(version: ProgramVersion)

  /** `case` in a program before version 1.1.0 (`UntypedPlutusCore/Parser.hs:92`). */
  case CaseBeforeV110(version: ProgramVersion)

  /** A `constr` tag above `maxBound :: Word64` (`UntypedPlutusCore/Parser.hs:80-84`). */
  case ConstrTagTooLarge(tag: BigInt)

  /** A type operator applied to an argument of the wrong kind (`Type.hs:101`). */
  case KindMismatch(function: Kinded, argument: Kinded)

  /** A constant whose type is an operator, not of kind `*` (`checkStar`, `Builtin.hs:44-46`). */
  case NotOfKindStar(kinded: Kinded)

  /** A `bls12_381_mlresult` constant, which has no literal (`Builtin.hs:169-170`). */
  case MlResultLiteral

  /** A `bls12_381_G1_element` literal that is no point of G1 (`Builtin.hs:138-143`). */
  case InvalidG1(bytes: Bytes, problem: PointProblem)

  /** A `bls12_381_G2_element` literal that is no point of G2 (`Builtin.hs:145-150`). */
  case InvalidG2(bytes: Bytes, problem: PointProblem)

  /** A `value` literal the parser refuses (`conValue`, `Builtin.hs:177-220`): a key longer than 32
    * bytes, an empty token list, a zero or out-of-bounds quantity, or keys out of order.
    */
  case InvalidValue(problem: Value.Invalid)

  /** A string literal whose characters are not text (cannot happen: `readLitChar` reads scalar
    * values and surrogates only).
    */
  case InvalidText(problem: Text.Invalid)

  /** A list or array element of another type than its list's (cannot happen: each element is read
    * at the element type).
    */
  case IllTyped(mismatch: ElementTypeMismatch)

/** Why a text is not a closed program: it does not parse, or it parses with a free variable. The
  * conformance runner reports either as `parse/decode error`
  * (`PlutusConformance/Common.hs:374-382`, `CekEvaluator.hs:54-57`).
  */
enum ProgramError:
  case Unparsable(error: TextError)
  case Open(free: FreeVariable)
