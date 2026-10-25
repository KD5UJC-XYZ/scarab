// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import scalus.uplc.{DefaultFun, Term}
import scalus.uplc.eval.{
  BuiltinError,
  BuiltinException,
  InvalidReturnValue,
  MachineError,
  UnknownBuiltin
}

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

  /** Throws `fault` when called: a fault placed at the decoder or the machine. */
  private def thrower(fault: () => Throwable): Any => Nothing = _ => throw fault()

  private val script = FlatProgram.script((1, 0, 0), FlatProgram.unit)
  private val request = EvalRequest(
    PlutusLanguage.V3,
    10,
    script,
    Vector.empty,
    PlutusBoundaryCorpus.costModel(PlutusLanguage.V3, 10),
    PlutusBoundaryCorpus.budget
  )

  /** What Scalus, its libraries or the runtime throw that is no verdict on a script. */
  private val inputFaults: Vector[(String, () => Throwable)] = Vector(
    "NullPointerException" -> (() => new NullPointerException("injected")),
    "ClassCastException" -> (() => new ClassCastException("injected")),
    "MatchError" -> (() => new MatchError("injected")),
    "borer Overflow" -> (() =>
      scala.util
        .Try(
          scalus.uplc.builtin.Data.fromCbor(
            scalus.uplc.builtin.ByteString.fromArray(FlatDecodeCorpus.nestedList(2000))
          )
        )
        .failed
        .getOrElse(new AssertionError("borer decoded 2000 levels"))
    ),
    "StackOverflowError" -> (() => new StackOverflowError("injected")),
    "RuntimeException" -> (() => new RuntimeException("injected"))
  )

  /** What says the implementation itself is missing or broken. */
  private val implementationFaults: Vector[(String, () => Throwable)] = Vector(
    "UnsatisfiedLinkError" -> (() => new UnsatisfiedLinkError("injected")),
    "NoClassDefFoundError" -> (() => new NoClassDefFoundError("injected")),
    "ExceptionInInitializerError" -> (() => new ExceptionInInitializerError("injected")),
    "OutOfMemoryError" -> (() => new OutOfMemoryError("injected")),
    "UnknownBuiltin" -> (() => new UnknownBuiltin(DefaultFun.AddInteger, Vector.empty)),
    "BuiltinError over UnsatisfiedLinkError" -> (() =>
      builtin(DefaultFun.VerifyEcdsaSecp256k1Signature, new UnsatisfiedLinkError("injected"))
    )
  )

  private def decodeSite(fault: () => Throwable) =
    ScalusEvaluator.injected(
      thrower(fault),
      (vm, p, s, l) => { val _ = vm.evaluateScript(p, s, l) }
    )

  private def machineSite(fault: () => Throwable) =
    ScalusEvaluator.injected(FlatScan.decode, (_, _, _, _) => thrower(fault)(()))

  private def reaches(fault: () => Throwable): Vector[(String, Option[BackendFailure.Reach])] =
    def evaluated(result: EvalResult) = result match
      case EvalResult.Failure(EvalFailure.Unsupported(why), _, _) => Some(why.reach)
      case _                                                      => None
    val decoder = decodeSite(fault)
    Vector(
      "validate" -> (decoder.validate(PlutusLanguage.V3, 10, script) match
        case Left(ScriptDecodeFailure.Unavailable(why)) => Some(why.reach)
        case _                                          => None),
      "evaluate after decode" -> evaluated(decoder.evaluate(request)),
      "evaluate on the machine" -> evaluated(machineSite(fault).evaluate(request))
    )

  test("a defect that Scalus throws on an input is that input's, at both sites") {
    for (name, fault) <- inputFaults do
      for (site, reach) <- reaches(fault) do
        assertEquals(reach, Some(BackendFailure.Reach.Input), s"$name at $site")
  }

  test("a missing native, missing class or broken runtime stays the implementation's") {
    for (name, fault) <- implementationFaults do
      for (site, reach) <- reaches(fault) do
        assertEquals(reach, Some(BackendFailure.Reach.Implementation), s"$name at $site")
  }

  test("unavailable builtins and natives stay the implementation's") {
    Vector(
      BackendFailure.NoResult,
      BackendFailure.BinaryDstUnavailable,
      BackendFailure.NativeHashToGroupUnavailable,
      BackendFailure.BuiltinUnavailable(DefaultFun.VerifyEcdsaSecp256k1Signature)
    ).foreach(f => assertEquals(f.reach, BackendFailure.Reach.Implementation, f.toString))
    Vector(
      BackendFailure.StackExhausted,
      BackendFailure.BudgetOutOfRange(ExUnits.zero)
    ).foreach(f => assertEquals(f.reach, BackendFailure.Reach.Input, f.toString))
  }

  test("every MachineError of the pinned Scalus is classified, none by default") {
    val jar = java.nio.file.Paths
      .get(classOf[MachineError].getProtectionDomain.getCodeSource.getLocation.toURI)
    val zip = java.util.zip.ZipFile(jar.toFile)
    val loader = classOf[MachineError].getClassLoader
    val found =
      try
        import scala.jdk.CollectionConverters.*
        zip
          .entries()
          .asScala
          .map(_.getName)
          .filter(n => n.endsWith(".class") && !n.contains("module-info"))
          .flatMap(n =>
            scala.util
              .Try(Class.forName(n.stripSuffix(".class").replace('/', '.'), false, loader))
              .toOption
          )
          .filter(c => classOf[MachineError].isAssignableFrom(c))
          .map(_.getName.stripPrefix("scalus.uplc.eval."))
          .toSet
      finally zip.close()
    // A Scalus upgrade that adds a MachineError fails here: decide whether the reference reports it
    // as a script failure before adding it (ScalusEvaluator.referenceFailure).
    assertEquals(
      found,
      Set(
        "MachineError",
        "StackTraceMachineError",
        "NonPolymorphicInstantiationMachineError",
        "NonFunctionalApplicationMachineError",
        "OpenTermEvaluatedMachineError",
        "BuiltinTermArgumentExpectedMachineError",
        "UnexpectedBuiltinTermArgumentMachineError",
        "UnknownBuiltin",
        "EvaluationFailure",
        "MissingCaseBranch",
        "NonConstrScrutinized",
        "CaseOnBuiltinError",
        "CaseIndexOutOfBounds",
        "CaseBoolBranchMissing",
        "CaseUnitBranchMissing",
        "CasePairBranchError",
        "CaseListBranchError",
        "CaseDataBranchError",
        "CaseDataNonConstrError",
        "CaseDataNotSupportedError",
        "BuiltinException",
        "DeserializationError",
        "KnownTypeUnliftingError",
        "BuiltinError",
        "OutOfExBudgetError"
      )
    )
  }
