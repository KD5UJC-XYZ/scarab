// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import cats.{Applicative, Eval, Traverse}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.uplc.{
  Constant as Con,
  DeBruijn,
  DefaultFun,
  Fix,
  Program,
  ProgramVersion,
  Schemes,
  Term,
  TermF,
  Word64
}

/** A name of the textual syntax, the reference's `Name` (`PlutusCore/Name/Unique.hs:64-71`, plutus
  * 1.70.0.0 `507cab8c`): its text, kept for messages, and its unique. A variable is identified by
  * the unique alone (`Eq Name`, `Unique.hs:120-121`), so `x-0` and `y-0` name the same variable,
  * and two binders of the text `x` are one variable shadowed.
  */
final case class Name(text: String, unique: Long)

/** One layer of a term with names, as the parser reads it (`UntypedPlutusCore/Parser.hs:41`): the
  * reference's `Term Name DefaultUni DefaultFun`, which [[NamedTerm.deBruijn]] turns into a
  * [[Term]] with de Bruijn indices. Every case but the variable and the binder is that of
  * [[TermF]].
  */
enum NamedTermF[+R]:
  case Var(name: Name)
  case LamAbs(binder: Name, body: R)
  case Apply(function: R, argument: R)
  case Force(term: R)
  case Delay(term: R)
  case Constant(value: Con)
  case Builtin(function: DefaultFun)
  case Error
  case Constr(tag: Word64, fields: Vector[R])
  case Case(scrutinee: R, branches: Vector[R])

object NamedTermF:
  given Traverse[NamedTermF] with
    def traverse[G[_], A, B](fa: NamedTermF[A])(f: A => G[B])(using
        G: Applicative[G]
    ): G[NamedTermF[B]] =
      fa match
        case Var(n)            => G.pure(Var(n))
        case LamAbs(n, body)   => f(body).map(LamAbs(n, _))
        case Apply(fn, arg)    => G.map2(f(fn), f(arg))(Apply(_, _))
        case Force(t)          => f(t).map(Force(_))
        case Delay(t)          => f(t).map(Delay(_))
        case Constant(c)       => G.pure(Constant(c))
        case Builtin(b)        => G.pure(Builtin(b))
        case Error             => G.pure(Error)
        case Constr(tag, xs)   => xs.traverse(f).map(Constr(tag, _))
        case Case(scrut, alts) => G.map2(f(scrut), alts.traverse(f))(Case(_, _))

    def foldLeft[A, B](fa: NamedTermF[A], b: B)(f: (B, A) => B): B = fa match
      case LamAbs(_, body)   => f(b, body)
      case Apply(fn, arg)    => f(f(b, fn), arg)
      case Force(t)          => f(b, t)
      case Delay(t)          => f(b, t)
      case Constr(_, xs)     => xs.foldLeft(b)(f)
      case Case(scrut, alts) => alts.foldLeft(f(b, scrut))(f)
      case _                 => b

    def foldRight[A, B](fa: NamedTermF[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa match
        case LamAbs(_, body)   => f(body, lb)
        case Apply(fn, arg)    => f(fn, Eval.defer(f(arg, lb)))
        case Force(t)          => f(t, lb)
        case Delay(t)          => f(t, lb)
        case Constr(_, xs)     => xs.foldr(lb)(f)
        case Case(scrut, alts) => f(scrut, Eval.defer(alts.foldr(lb)(f)))
        case _                 => lb

/** A term with names: the fixed point of [[NamedTermF]]. */
type NamedTerm = Fix[NamedTermF]

/** A program as the parser reads it, before its names are resolved. */
final case class NamedProgram(version: ProgramVersion, term: NamedTerm):
  /** The program with de Bruijn indices (`deBruijnTerm`), refused at its first free variable. */
  def deBruijn: Either[FreeVariable, Program] = NamedTerm.deBruijn(term).map(Program(version, _))

/** A variable that no enclosing `lam` binds: `FreeUnique`
  * (`PlutusCore/DeBruijn/Internal.hs:340-341`). The conformance runner reports a program with one
  * as a decode error (`PlutusConformance/CekEvaluator.hs:54-57`).
  */
final case class FreeVariable(name: Name)

object NamedTerm:
  /** The binders in scope: the reference's `LevelInfo` (`DeBruijn/Internal.hs:226-248`), the
    * current level and the level at which each unique was bound. A `lam` binds its unique at the
    * current level and its body is one level deeper; a variable bound at level `l` and used at
    * level `c` has index `c - l` (`getIndex`, `Internal.hs:265-279`). Binding a unique again
    * shadows it.
    */
  private final case class Scope(level: Int, levels: Map[Long, Int]):
    def bind(name: Name): Scope = Scope(level + 1, levels.updated(name.unique, level))
    def index(name: Name): Option[DeBruijn] =
      levels.get(name.unique).flatMap(bound => DeBruijn.fromInt(level - bound))

  private val top: Scope = Scope(0, Map.empty)

  /** `deBruijnTerm` (`UntypedPlutusCore/DeBruijn.hs:46-97`) as an unfold of the term and its scope:
    * the variables are resolved in the order the reference visits them, so the free variable
    * reported is its first.
    */
  def deBruijn(term: NamedTerm): Either[FreeVariable, Term] =
    Schemes.anaM[TermF, [x] =>> Either[FreeVariable, x], (NamedTerm, Scope)]((term, top)) {
      (t, scope) =>
        t.unfix match
          case NamedTermF.Var(n) => scope.index(n).map(TermF.Var(_)).toRight(FreeVariable(n))
          case NamedTermF.LamAbs(n, body) => Right(TermF.LamAbs((body, scope.bind(n))))
          case NamedTermF.Apply(f, a)     => Right(TermF.Apply((f, scope), (a, scope)))
          case NamedTermF.Force(x)        => Right(TermF.Force((x, scope)))
          case NamedTermF.Delay(x)        => Right(TermF.Delay((x, scope)))
          case NamedTermF.Constant(c)     => Right(TermF.Constant(c))
          case NamedTermF.Builtin(b)      => Right(TermF.Builtin(b))
          case NamedTermF.Error           => Right(TermF.Error)
          case NamedTermF.Constr(tag, xs) => Right(TermF.Constr(tag, xs.map((_, scope))))
          case NamedTermF.Case(s, alts) =>
            Right(TermF.Case((s, scope), alts.map((_, scope))))
    }

  def variable(name: Name): NamedTerm = Fix(NamedTermF.Var(name))
  def lam(binder: Name, body: NamedTerm): NamedTerm = Fix(NamedTermF.LamAbs(binder, body))
  def app(function: NamedTerm, argument: NamedTerm): NamedTerm =
    Fix(NamedTermF.Apply(function, argument))
  def force(term: NamedTerm): NamedTerm = Fix(NamedTermF.Force(term))
  def delay(term: NamedTerm): NamedTerm = Fix(NamedTermF.Delay(term))
  def constant(value: Con): NamedTerm = Fix(NamedTermF.Constant(value))
  def builtin(function: DefaultFun): NamedTerm = Fix(NamedTermF.Builtin(function))
  val error: NamedTerm = Fix(NamedTermF.Error)
  def constr(tag: Word64, fields: Vector[NamedTerm]): NamedTerm =
    Fix(NamedTermF.Constr(tag, fields))
  def caseOf(scrutinee: NamedTerm, branches: Vector[NamedTerm]): NamedTerm =
    Fix(NamedTermF.Case(scrutinee, branches))
