// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import cats.{Applicative, Eval, Traverse}
import cats.syntax.all.*

private type ConstantValue = Constant

/** One layer of an untyped Plutus Core term with de Bruijn indices, the reference's `Term`
  * (`UntypedPlutusCore/Core/Type.hs:88-104`, plutus 1.70.0.0 `507cab8c`), with its subterms
  * abstracted as `R`: [[Term]] is its fixed point, and [[Schemes]] fold and unfold it. A `lam` has
  * no name; the variables under it count binders outward ([[DeBruijn]]).
  *
  * The [[cats.Traverse]] instance visits the subterms in the order of the Flat encoding
  * (`UntypedPlutusCore/Core/Instance/Flat.hs:120-130`): function before argument, the scrutinee
  * before the branches. The fields of a `constr` and the branches of a `case` are a Flat list, a
  * continuation bit before each element (`PlutusCore/Flat/Decoder/Strict.hs:60-88`), so their
  * number is known only once they are read: a decoder unfolds a functor of its own, with a layer
  * per list cell, and folds it into a [[Term]] ([[Schemes.hyloM]] is generic in the functor).
  */
enum TermF[+R]:
  case Var(index: DeBruijn)
  case LamAbs(body: R)
  case Apply(function: R, argument: R)
  case Force(term: R)
  case Delay(term: R)
  case Constant(value: ConstantValue)
  case Builtin(function: DefaultFun)
  case Error

  /** `constr`, from program version 1.1.0 ([[ProgramVersion.allowsConstrAndCase]]). */
  case Constr(tag: Word64, fields: Vector[R])

  /** `case`, from program version 1.1.0. */
  case Case(scrutinee: R, branches: Vector[R])

object TermF:
  given Traverse[TermF] with
    def traverse[G[_], A, B](fa: TermF[A])(f: A => G[B])(using G: Applicative[G]): G[TermF[B]] =
      fa match
        case Var(i)            => G.pure(Var(i))
        case LamAbs(body)      => f(body).map(LamAbs(_))
        case Apply(fn, arg)    => G.map2(f(fn), f(arg))(Apply(_, _))
        case Force(t)          => f(t).map(Force(_))
        case Delay(t)          => f(t).map(Delay(_))
        case Constant(c)       => G.pure(Constant(c))
        case Builtin(b)        => G.pure(Builtin(b))
        case Error             => G.pure(Error)
        case Constr(tag, xs)   => xs.traverse(f).map(Constr(tag, _))
        case Case(scrut, alts) => G.map2(f(scrut), alts.traverse(f))(Case(_, _))

    override def map[A, B](fa: TermF[A])(f: A => B): TermF[B] = fa match
      case LamAbs(body)      => LamAbs(f(body))
      case Apply(fn, arg)    => Apply(f(fn), f(arg))
      case Force(t)          => Force(f(t))
      case Delay(t)          => Delay(f(t))
      case Constr(tag, xs)   => Constr(tag, xs.map(f))
      case Case(scrut, alts) => Case(f(scrut), alts.map(f))
      case Var(i)            => Var(i)
      case Constant(c)       => Constant(c)
      case Builtin(b)        => Builtin(b)
      case Error             => Error

    def foldLeft[A, B](fa: TermF[A], b: B)(f: (B, A) => B): B = fa match
      case LamAbs(body)      => f(b, body)
      case Apply(fn, arg)    => f(f(b, fn), arg)
      case Force(t)          => f(b, t)
      case Delay(t)          => f(b, t)
      case Constr(_, xs)     => xs.foldLeft(b)(f)
      case Case(scrut, alts) => alts.foldLeft(f(b, scrut))(f)
      case _                 => b

    def foldRight[A, B](fa: TermF[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = fa match
      case LamAbs(body)      => f(body, lb)
      case Apply(fn, arg)    => f(fn, Eval.defer(f(arg, lb)))
      case Force(t)          => f(t, lb)
      case Delay(t)          => f(t, lb)
      case Constr(_, xs)     => xs.foldr(lb)(f)
      case Case(scrut, alts) => f(scrut, Eval.defer(alts.foldr(lb)(f)))
      case _                 => lb

    override def toList[A](fa: TermF[A]): List[A] = fa match
      case LamAbs(body)      => body :: Nil
      case Apply(fn, arg)    => fn :: arg :: Nil
      case Force(t)          => t :: Nil
      case Delay(t)          => t :: Nil
      case Constr(_, xs)     => xs.toList
      case Case(scrut, alts) => scrut :: alts.toList
      case _                 => Nil

/** A term: the fixed point of [[TermF]]. */
type Term = Fix[TermF]

/** The constructors of [[Term]], one per case of [[TermF]]. */
object Term:
  def variable(index: DeBruijn): Term = Fix(TermF.Var(index))
  def lam(body: Term): Term = Fix(TermF.LamAbs(body))
  def app(function: Term, argument: Term): Term = Fix(TermF.Apply(function, argument))
  def force(term: Term): Term = Fix(TermF.Force(term))
  def delay(term: Term): Term = Fix(TermF.Delay(term))
  def constant(value: Constant): Term = Fix(TermF.Constant(value))
  def builtin(function: DefaultFun): Term = Fix(TermF.Builtin(function))
  val error: Term = Fix(TermF.Error)
  def constr(tag: Word64, fields: Vector[Term]): Term = Fix(TermF.Constr(tag, fields))
  def caseOf(scrutinee: Term, branches: Vector[Term]): Term = Fix(TermF.Case(scrutinee, branches))

/** A program: a term and the version of Plutus Core it declares
  * (`UntypedPlutusCore/Core/Type.hs:119-125`; the annotation is `()` on the chain and not kept).
  */
final case class Program(version: ProgramVersion, term: Term)
