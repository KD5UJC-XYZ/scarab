// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{Constant, DeBruijn, DefaultFun, PlutusData, Term, Word64}

/** Terms written by hand for the machine's tests and benchmarks: de Bruijn indices from 1. */
object Terms:
  def v(index: Long): Term = Term.variable(DeBruijn(Word64.fromBits(index)))
  def lam(body: Term): Term = Term.lam(body)
  def app(function: Term, arguments: Term*): Term = arguments.foldLeft(function)(Term.app)
  def force(term: Term): Term = Term.force(term)
  def delay(term: Term): Term = Term.delay(term)
  def builtin(function: DefaultFun): Term = Term.builtin(function)
  def con(constant: Constant): Term = Term.constant(constant)
  def int(n: BigInt): Term = con(Constant.Integer(n))
  def bool(b: Boolean): Term = con(Constant.Bool(b))
  val unit: Term = con(Constant.Unit)
  def data(d: PlutusData): Term = con(Constant.Data(d))
  def constr(tag: Long, fields: Term*): Term = Term.constr(Word64.fromBits(tag), fields.toVector)
  def caseOf(scrutinee: Term, branches: Term*): Term = Term.caseOf(scrutinee, branches.toVector)
  val error: Term = Term.error

  /** `term` under `n` layers of `f`, built from the inside out without recursion. */
  def nest(n: Int, term: Term)(f: Term => Term): Term = (1 to n).foldLeft(term)((t, _) => f(t))

  /** `(force (delay ... term))`, `n` times: two steps per layer, a `Force` and a `Delay`. */
  def forceDelay(n: Int, term: Term): Term = nest(n, term)(t => force(delay(t)))

  /** `ifThenElse` applied to a condition and two branches, the chosen one forced. */
  def ifThenElse(condition: Term, whenTrue: Term, whenFalse: Term): Term =
    force(app(force(builtin(DefaultFun.IfThenElse)), condition, delay(whenTrue), delay(whenFalse)))

  /** The strict fixed-point combinator `\f -> (\x -> f (\v -> x x v)) (\x -> f (\v -> x x v))`. */
  val fix: Term =
    val half = lam(app(v(2), lam(app(v(2), v(2), v(1)))))
    lam(app(half, half))

  /** A countdown from `n` to 0 through [[fix]]: each round compares with `lessThanEqualsInteger`,
    * chooses with `ifThenElse` and subtracts with `addInteger`; the result is `0`.
    */
  def countdown(n: BigInt): Term =
    val body = lam(
      lam(
        ifThenElse(
          app(builtin(DefaultFun.LessThanEqualsInteger), v(1), int(0)),
          int(0),
          app(v(2), app(builtin(DefaultFun.AddInteger), v(1), int(-1)))
        )
      )
    )
    app(fix, body, int(n))

  /** A loop over constructors: builds the list `n, n-1, .., 1` as `constr` (1 is cons, 0 nil) and
    * sums it with `case`, both through [[fix]]; the result is `n(n+1)/2`.
    */
  def constrSum(n: BigInt): Term =
    val build = lam(
      lam(
        ifThenElse(
          app(builtin(DefaultFun.LessThanEqualsInteger), v(1), int(0)),
          constr(0),
          constr(1, v(1), app(v(2), app(builtin(DefaultFun.AddInteger), v(1), int(-1))))
        )
      )
    )
    // sum = fix (\self xs -> case xs [0, \h t -> h + self t])
    val sum = lam(
      lam(
        caseOf(
          v(1),
          int(0),
          lam(lam(app(builtin(DefaultFun.AddInteger), v(2), app(v(4), v(1)))))
        )
      )
    )
    app(app(fix, sum), app(fix, build, int(n)))
