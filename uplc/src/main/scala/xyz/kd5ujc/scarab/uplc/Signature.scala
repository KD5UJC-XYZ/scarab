// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

/** The type scheme of a builtin at the level of the universe: what `makeBuiltinMeaning` derives
  * from the type of its Haskell denotation (`PlutusCore/Builtin/Meaning.hs:160-187,340-369`, plutus
  * 1.70.0.0 `507cab8c`). Every type variable becomes a `TypeSchemeAll` and a `BuiltinExpectForce`,
  * all of them before the first argument, in the order the variables first appear; every argument
  * becomes a `TypeSchemeArrow` and a `BuiltinExpectArgument`.
  *
  * The Haskell types that refine a universe type for one semantics variant (`Int`, `Word8`,
  * `Word64`, `CInteger`, `NumBytesCostedAsNumWords` for an integer; `CByteString` for a byte
  * string; `TextCostedByByteLength` for a string; `ValueTotalSize` and its kin for a value) bound
  * the unlifting or choose the costing measure. They differ by variant, so they are the builtins'
  * and the costing's, not part of the scheme here; no variant of 1.70.0.0 changes a scheme.
  *
  * @param forces
  *   the number of type variables, which is the number of `force` s the builtin takes first
  */
final case class Signature(
    forces: Int,
    parameters: Vector[Signature.Parameter],
    result: Signature.Type
):
  def arity: Int = parameters.size

object Signature:
  /** A type of the scheme: a universe type that may contain type variables. A scheme is from the
    * table of [[DefaultFun]], at most a few levels deep, so the methods here recurse plainly.
    */
  enum Type:
    /** The type variable numbered `index` (0 is `a`, 1 is `b`), by first appearance. */
    case Variable(index: Int)
    case Integer
    case ByteString
    case String
    case Unit
    case Bool
    case Data
    case Bls12_381_G1_Element
    case Bls12_381_G2_Element
    case Bls12_381_MlResult
    case Value
    case List(element: Type)
    case Pair(first: Type, second: Type)
    case Array(element: Type)

    /** The variables in order of first appearance, each once. */
    def variables: Vector[Int] = this match
      case Variable(i) => Vector(i)
      case List(e)     => e.variables
      case Pair(a, b)  => (a.variables ++ b.variables).distinct
      case Array(e)    => e.variables
      case _           => Vector.empty

    /** The universe type when the scheme has no variable. */
    def ground: Option[DefaultUni] = this match
      case Variable(_)          => None
      case Integer              => Some(DefaultUni.Integer)
      case ByteString           => Some(DefaultUni.ByteString)
      case String               => Some(DefaultUni.String)
      case Unit                 => Some(DefaultUni.Unit)
      case Bool                 => Some(DefaultUni.Bool)
      case Data                 => Some(DefaultUni.Data)
      case Bls12_381_G1_Element => Some(DefaultUni.Bls12_381_G1_Element)
      case Bls12_381_G2_Element => Some(DefaultUni.Bls12_381_G2_Element)
      case Bls12_381_MlResult   => Some(DefaultUni.Bls12_381_MlResult)
      case Value                => Some(DefaultUni.Value)
      case List(e)              => e.ground.map(DefaultUni.List(_))
      case Pair(a, b)           => a.ground.zip(b.ground).map(DefaultUni.Pair(_, _))
      case Array(e)             => e.ground.map(DefaultUni.Array(_))

  /** An argument of a builtin. */
  enum Parameter:
    /** Any value of the machine, passed to the denotation as it is: a bare type variable of the
      * denotation, `Opaque val a` (`ifThenElse`'s branches, `trace` 's second argument).
      */
    case Opaque(variable: Int)

    /** A constant of the given type: monomorphic (`Integer`), or `SomeConstant uni τ` when the type
      * has variables, where the constant may be of any type and the denotation checks its shape
      * (`mkCons`, `fstPair`).
      */
    case Constant(of: Type)

    def variables: Vector[Int] = this match
      case Opaque(v)   => Vector(v)
      case Constant(t) => t.variables

  /** The type variables of the whole scheme, in order of first appearance (`ToBinds`). */
  extension (s: Signature)
    def variables: Vector[Int] = (s.parameters.flatMap(_.variables) ++ s.result.variables).distinct

  /** `Signature(forces)(parameters*)(result)`, the form of the table of [[DefaultFun]]: a type
    * stands for a constant parameter of that type.
    */
  def apply(forces: Int)(parameters: (Type | Parameter)*)(result: Type): Signature =
    Signature(
      forces,
      parameters.toVector.map {
        case p: Parameter => p
        case t: Type      => Parameter.Constant(t)
      },
      result
    )

  /** The names the table of [[DefaultFun]] is written in. */
  object Notation:
    val integer: Type = Type.Integer
    val bytestring: Type = Type.ByteString
    val string: Type = Type.String
    val unit: Type = Type.Unit
    val bool: Type = Type.Bool
    val data: Type = Type.Data
    val g1: Type = Type.Bls12_381_G1_Element
    val g2: Type = Type.Bls12_381_G2_Element
    val mlResult: Type = Type.Bls12_381_MlResult
    val value: Type = Type.Value
    val a: Type = Type.Variable(0)
    val b: Type = Type.Variable(1)
    def list(element: Type): Type = Type.List(element)
    def pair(first: Type, second: Type): Type = Type.Pair(first, second)
    def array(element: Type): Type = Type.Array(element)

    /** The opaque parameter of type variable `v`. */
    def opaque(v: Type): Parameter = v match
      case Type.Variable(i) => Parameter.Opaque(i)
      case other            => Parameter.Constant(other)
