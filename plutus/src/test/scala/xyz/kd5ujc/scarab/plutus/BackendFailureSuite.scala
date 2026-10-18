// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import scalus.uplc.{DefaultFun, Term}
import scalus.uplc.eval.{BuiltinError, BuiltinException, InvalidReturnValue, MachineError}

class BackendFailureSuite extends FunSuite:
  private def builtin(fun: DefaultFun, cause: Throwable): BuiltinError =
    new BuiltinError(fun, Term.Error(), cause, Vector.empty)

  test("unexpected backend defects never become reference script failures") {
    for error <- Vector(
        new IllegalStateException("backend defect"),
        new NullPointerException("backend defect"),
        builtin(DefaultFun.AddInteger, new NullPointerException("backend defect")),
        builtin(DefaultFun.AddInteger, new ArithmeticException("backend defect")),
        builtin(DefaultFun.IndexByteString, new IllegalStateException("backend defect"))
      )
    do assert(!ScalusEvaluator.referenceFailure(error), error.getClass.getName)
  }

  test("documented typed CEK and builtin input errors remain genuine script failures") {
    for error <- Vector(
        new MachineError("CEK script failure"),
        new InvalidReturnValue(Term.Error()),
        builtin(DefaultFun.AddInteger, new BuiltinException("bad argument")),
        builtin(DefaultFun.DivideInteger, new ArithmeticException("division by zero")),
        builtin(DefaultFun.IndexByteString, new IndexOutOfBoundsException("index")),
        builtin(DefaultFun.DecodeUtf8, new IllegalArgumentException("invalid UTF8"))
      )
    do assert(ScalusEvaluator.referenceFailure(error), error.getClass.getName)
  }
