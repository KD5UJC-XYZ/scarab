// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{Term, Word64}

/** The machine's stack of frames, the reference's `Context` (`Cek/Internal.hs:724-745`, plutus
  * 1.70.0.0 `507cab8c`): an intrusive list on the heap, as deep as the evaluation needs.
  */
private[cek] sealed abstract class Context

private[cek] object Context:
  /** `FrameAwaitArg`: `[V _]`, a function value waiting for its argument. */
  final case class AwaitArg(function: CekValue, next: Context) extends Context

  /** `FrameAwaitFunTerm`: `[_ N]`, an argument term waiting for the function to be a value. */
  final case class AwaitFunTerm(env: Env[CekValue], argument: Term, next: Context) extends Context

  /** `FrameAwaitFunValueN` and `FrameAwaitFunConN`: `[_ V1 .. Vn]`, arguments waiting for the
    * function (the fields of a `constr` for its branch, or the constants the caser gives). The
    * reference keeps the caser's constants unwrapped; they become `VCon` when applied
    * (`Cek/Internal.hs:906-921`), and here when the frame is made.
    */
  final case class AwaitFunValues(first: CekValue, rest: List[CekValue], next: Context)
      extends Context

  /** `FrameForce`: `(force _)`. */
  final case class Force(next: Context) extends Context

  /** `FrameConstr`: `(constr i V0 .. Vj-1 _ Nj .. Nn)`, the fields from `todo` on still terms.
    *
    * @param todo
    *   the index of the next field to compute
    * @param done
    *   the values of the fields before the hole, the last one first
    */
  final case class Constr(
      env: Env[CekValue],
      tag: Word64,
      fields: Vector[Term],
      todo: Int,
      done: List[CekValue],
      next: Context
  ) extends Context

  /** `FrameCases`: `(case _ C0 .. Cn)`. */
  final case class Cases(env: Env[CekValue], branches: Vector[Term], next: Context) extends Context

  /** `NoFrame`. */
  case object NoFrame extends Context
