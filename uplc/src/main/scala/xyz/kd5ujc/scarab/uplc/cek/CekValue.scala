// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, Schemes, Term, TermF, Word64}
import xyz.kd5ujc.scarab.uplc.builtin.MachineValue

/** A value of the CEK machine, the reference's `CekValue` (`Cek/Internal.hs:289-316`, plutus
  * 1.70.0.0 `507cab8c`). Values are compared by identity: they hold environments and terms as deep
  * as the script, and the machine never compares them. [[CekValue.discharge]] turns one into a
  * term.
  */
sealed abstract class CekValue:
  override def equals(that: Any): Boolean = that match
    case v: AnyRef => this eq v
    case _         => false
  override def hashCode(): Int = System.identityHashCode(this)

/** The values (`Cek/Internal.hs:289-316`). */
object CekValue:
  /** `VCon`: a constant. */
  final case class VCon(constant: Constant) extends CekValue:
    override def toString: String = s"VCon($constant)"

  /** `VDelay`: a delayed term and its environment. */
  final case class VDelay(body: Term, env: Env[CekValue]) extends CekValue:
    override def toString: String = "VDelay"

  /** `VLamAbs`: the body of a lambda and its environment. */
  final case class VLamAbs(body: Term, env: Env[CekValue]) extends CekValue:
    override def toString: String = "VLamAbs"

  /** `VBuiltin`: a partial application of a builtin. The reference keeps the application's term
    * lazily and a `BuiltinRuntime` that says what the builtin expects next
    * (`Cek/Internal.hs:298-312`); this keeps where the application is in the builtin's
    * [[xyz.kd5ujc.scarab.uplc.Signature]] (every `force` comes before the first argument) and the
    * arguments, and builds the term only when it is discharged.
    *
    * @param forces
    *   the `force` s applied so far
    * @param arguments
    *   the arguments applied so far, the last one first
    * @param count
    *   the number of `arguments`
    */
  final case class VBuiltin(
      function: DefaultFun,
      forces: Int,
      arguments: List[CekValue],
      count: Int
  ) extends CekValue:
    override def toString: String = s"VBuiltin($function, $forces forces, $count arguments)"

    /** `BuiltinExpectForce`: a type argument comes next. */
    def expectsForce: Boolean = forces < function.signature.forces

    /** `BuiltinExpectArgument`: every type argument is in and a term argument comes next. */
    def expectsArgument: Boolean =
      forces == function.signature.forces && count < function.signature.arity

    /** `BuiltinCostedResult`: the application is saturated. */
    def saturated: Boolean =
      forces == function.signature.forces && count == function.signature.arity

    /** Whether one more `force` saturates the application. */
    def saturatedByForce: Boolean =
      forces + 1 == function.signature.forces && count == function.signature.arity

    /** Whether one more argument saturates the application. */
    def saturatedByArgument: Boolean =
      forces == function.signature.forces && count + 1 == function.signature.arity

    def forced: VBuiltin = VBuiltin(function, forces + 1, arguments, count)

    def applied(argument: CekValue): VBuiltin =
      VBuiltin(function, forces, argument :: arguments, count + 1)

    /** The arguments in the order they were applied. */
    def inOrder: Vector[CekValue] = arguments.reverseIterator.toVector

  object VBuiltin:
    private val unapplied: Vector[VBuiltin] =
      DefaultFun.values.toVector.map(f => VBuiltin(f, 0, Nil, 0))

    /** The value of the term `builtin f` (`Cek/Internal.hs:858-862`), one per builtin. */
    def of(function: DefaultFun): VBuiltin = unapplied(function.ordinal)

  /** `VConstr`: a constructor's tag and its fields, in order. */
  final case class VConstr(tag: Word64, fields: List[CekValue]) extends CekValue:
    override def toString: String = s"VConstr(${tag.show}, ${fields.size} fields)"

  given MachineValue[CekValue] with
    def constantOf(value: CekValue): Option[Constant] = value match
      case VCon(c) => Some(c)
      case _       => None
    def ofConstant(constant: Constant): CekValue = VCon(constant)

  /** `dischargeCekValue` (`Cek/Internal.hs:657-704`): the value as a term, every variable that its
    * environments bind replaced by the term of its value. An unfold over [[Seed]], so it runs in
    * constant stack ([[Schemes.ana]]) however deep the value and its environments are.
    */
  def discharge(value: CekValue): Term = Schemes.ana[TermF, Seed](Seed.Value(value))(layer)

  /** The term of a partial builtin application with `argument` applied after the others: the cause
    * the reference gives an application to a builtin that expects none
    * (`Cek/Internal.hs:996-1006`).
    */
  def dischargeApplied(function: VBuiltin, argument: CekValue): Term =
    discharge(function.applied(argument))

  /** What is still to be discharged: a value, a term under an environment, or the spine of a
    * partial builtin application.
    */
  private enum Seed:
    case Value(value: CekValue)

    /** `goValEnv env shift term` (`Cek/Internal.hs:676-704`): `shift` counts the lambdas entered
      * since the environment was captured.
      */
    case Body(env: Env[CekValue], shift: Long, term: Term)

    /** `f`, `forces` times forced, then applied to `arguments` (the last one first). */
    case Spine(function: DefaultFun, forces: Int, arguments: List[CekValue])

  private def layer(seed: Seed): TermF[Seed] = seed match
    case Seed.Value(v) => valueLayer(v)
    case Seed.Spine(f, forces, last :: rest) =>
      TermF.Apply(Seed.Spine(f, forces, rest), Seed.Value(last))
    case Seed.Spine(f, forces, Nil) if forces > 0 => TermF.Force(Seed.Spine(f, forces - 1, Nil))
    case Seed.Spine(f, _, Nil)                    => TermF.Builtin(f)
    case Seed.Body(env, shift, term)              => bodyLayer(env, shift, term)

  /** `goValue` (`Cek/Internal.hs:661-672`). The body of a `lam` is discharged under one more
    * binder; a builtin application is its spine.
    */
  private def valueLayer(value: CekValue): TermF[Seed] = value match
    case VCon(c)                      => TermF.Constant(c)
    case VDelay(body, env)            => TermF.Delay(Seed.Body(env, 0L, body))
    case VLamAbs(body, env)           => TermF.LamAbs(Seed.Body(env, 1L, body))
    case VBuiltin(f, forces, args, _) => layer(Seed.Spine(f, forces, args))
    case VConstr(tag, fields) => TermF.Constr(tag, fields.iterator.map(Seed.Value(_)).toVector)

  /** `go` of `goValEnv` (`Cek/Internal.hs:681-704`): a variable bound inside the term stays; one
    * bound by the environment is replaced by its value; one bound by neither stays free.
    */
  private def bodyLayer(env: Env[CekValue], shift: Long, term: Term): TermF[Seed] =
    term.unfix match
      case TermF.LamAbs(body) => TermF.LamAbs(Seed.Body(env, shift + 1L, body))
      case TermF.Var(index) =>
        val idx = index.index.bits
        // `shift >= idx` with both read as unsigned words.
        if java.lang.Long.compareUnsigned(shift, idx) >= 0 then TermF.Var(index)
        else
          env.indexOne(idx - shift) match
            case found: Env.Tree[CekValue] => valueLayer(found.value)
            case Env.Absent                => TermF.Var(index)
      case other => summon[cats.Functor[TermF]].map(other)(Seed.Body(env, shift, _))
