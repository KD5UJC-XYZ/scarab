// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import java.lang.management.ManagementFactory
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, PlutusData, Term}
import xyz.kd5ujc.scarab.uplc.builtin.Outcome
import xyz.kd5ujc.scarab.uplc.cek.CaserBuiltin

import CoreTesting.*
import DefaultFun.*

/** The conversions between `Data` and lists take constant time and memory, whatever the length of
  * the list, as their constant costs say (`builtinCostModelC.json`, plutus 1.71.0.0 `261766ad`):
  * the reference shares the list inside the `Data` node (`Builtins.hs:1763-1838` there, and
  * `caseBuiltinData`, `Universe.hs:1035-1050`). So do the list builtins and `case` on a list of
  * `Data`.
  *
  * Each check counts the bytes the test's thread allocates over a run of applications, once with a
  * one-element argument and once with one of `Large` elements, and requires the large one to cost
  * less than `PerCall` bytes more per application. Copying the list once per application costs at
  * least a reference per element, over 32 KB at this length (the control below copies it and is
  * seen to); a list operation that shares the vector allocates a few of its nodes, a few hundred
  * bytes at most. Each result is handed to `System.identityHashCode`, so that the JIT cannot drop
  * its allocation. The checks here are of cost only; `DataListSuite` checks the results.
  */
final class DataConversionAllocationSuite extends munit.FunSuite:
  private val Large = 8000
  private val PerCall = 4096L
  private val Runs = 200

  // The JVM's count of the bytes a thread allocates, on; a JVM without one fails the suite.
  private val threads = ManagementFactory.getThreadMXBean match
    case t: com.sun.management.ThreadMXBean if t.isThreadAllocatedMemorySupported =>
      t.setThreadAllocatedMemoryEnabled(true)
      t
    case other => fail(s"no count of allocated bytes on $other")

  private def count(): Long =
    val bytes = threads.getCurrentThreadAllocatedBytes
    assert(bytes >= 0, "the count of allocated bytes is off")
    bytes

  // `Runs` runs of `body`, each result kept from the JIT's escape analysis.
  private def runs(body: () => Any): Int =
    (1 to Runs).foldLeft(0)((h, _) => h ^ System.identityHashCode(body()))

  // The bytes this thread allocates over `Runs` runs of `body`, after as many to warm up.
  private def allocated(body: () => Any): Long =
    runs(body)
    val before = count()
    runs(body)
    val after = count()
    assert(after > before, s"no allocation counted over $Runs runs")
    after - before

  /** The bytes that `applied(Large)` allocates per application beyond what `applied(1)` does. */
  private def extraPerCall(applied: Int => () => Any): Long =
    applied(1)() // the shape is checked once before it is measured
    val small = allocated(applied(1))
    val large = allocated(applied(Large))
    (large - small) / Runs

  private def constant(outcome: Outcome[Probe]): Constant = result(outcome) match
    case Right(c)      => c
    case Left(failure) => fail(s"the application failed: $failure")

  private def zero = PlutusData.I(0)
  private def items(n: Int) = Vector.fill(n)(zero)
  private def entries(n: Int) = Vector.fill(n)(zero -> zero)

  /** `applied(n)` makes an argument of `n` elements and gives one application to it. */
  private def constantInLength(name: String)(applied: Int => () => Any): Unit =
    test(s"$name allocates as much for a long list as for a short one") {
      val extra = extraPerCall(applied)
      assert(
        extra < PerCall,
        s"$name allocates $extra bytes more per application on $Large elements than on 1"
      )
    }

  test("the count sees an application that copies the list") {
    val extra = extraPerCall(n =>
      val xs = items(n)
      () => xs.map(Constant.Data(_))
    )
    assert(extra > 4 * PerCall, s"wrapping $Large elements counted $extra bytes per application")
  }

  private val E = CoreVariant.E
  private def dataOfList(n: Int): Probe = data(PlutusData.List(items(n)))
  private def dataOfMap(n: Int): Probe = data(PlutusData.Map(entries(n)))
  private def dataListOf(n: Int): Probe = con(constant(CoreTesting(E, UnListData, dataOfList(n))))
  private def pairListOf(n: Int): Probe = con(constant(CoreTesting(E, UnMapData, dataOfMap(n))))

  // `unListData`, `unMapData` and `unConstrData` share the list of the `Data` node.
  constantInLength("unListData")(n =>
    val d = dataOfList(n)
    () => constant(CoreTesting(E, UnListData, d))
  )
  constantInLength("unMapData")(n =>
    val d = dataOfMap(n)
    () => constant(CoreTesting(E, UnMapData, d))
  )
  constantInLength("unConstrData")(n =>
    val d = data(PlutusData.Constr(0, items(n)))
    () => constant(CoreTesting(E, UnConstrData, d))
  )

  // `listData`, `mapData` and `constrData` share the list they are given.
  constantInLength("listData")(n =>
    val xs = dataListOf(n)
    () => constant(CoreTesting(E, ListData, xs))
  )
  constantInLength("mapData")(n =>
    val es = pairListOf(n)
    () => constant(CoreTesting(E, MapData, es))
  )
  constantInLength("constrData")(n =>
    val xs = dataListOf(n)
    () => constant(CoreTesting(E, ConstrData, int(0), xs))
  )

  // `case` on a `Constr` shares its fields.
  constantInLength("case on a Data constructor")(n =>
    val d = Constant.Data(PlutusData.Constr(0, items(n)))
    val branches = Vector(Term.constant(Constant.Unit))
    () => CaserBuiltin.WithData.select(d, branches)
  )

  // The list builtins and `case` on a list, on a list of `Data` or of pairs of `Data`.
  constantInLength("headList")(n =>
    val xs = dataListOf(n)
    () => constant(CoreTesting(E, HeadList, xs))
  )
  constantInLength("tailList")(n =>
    val xs = dataListOf(n)
    () => constant(CoreTesting(E, TailList, xs))
  )
  constantInLength("nullList")(n =>
    val xs = pairListOf(n)
    () => constant(CoreTesting(E, NullList, xs))
  )
  constantInLength("chooseList")(n =>
    val xs = dataListOf(n)
    () => CoreTesting(E, ChooseList, xs, unit, unit)
  )
  constantInLength("mkCons")(n =>
    val xs = dataListOf(n)
    () => constant(CoreTesting(E, MkCons, data(zero), xs))
  )
  constantInLength("mkCons of a pair")(n =>
    val xs = pairListOf(n)
    val pair = con(Constant.Pair(Constant.Data(zero), Constant.Data(zero)))
    () => constant(CoreTesting(E, MkCons, pair, xs))
  )
  constantInLength("dropList")(n =>
    val xs = dataListOf(n)
    () => constant(CoreTesting(E, DropList, int(1), xs))
  )
  constantInLength("case on a list")(n =>
    val xs = constant(CoreTesting(E, UnListData, dataOfList(n)))
    val branches = Vector(Term.constant(Constant.Unit), Term.constant(Constant.Unit))
    () => CaserBuiltin.WithData.select(xs, branches)
  )

  test("the arguments are what they claim") {
    assertEquals(
      dataListOf(3),
      con(CoreTesting.list(DefaultUni.Data, Vector.fill(3)(Constant.Data(zero))*))
    )
    assertEquals(
      constant(CoreTesting(E, ListData, dataListOf(Large))),
      Constant.Data(PlutusData.List(items(Large)))
    )
    assertEquals(
      constant(CoreTesting(E, MapData, pairListOf(Large))),
      Constant.Data(PlutusData.Map(entries(Large)))
    )
  }
