// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab

import org.wartremover.WartTraverser
import org.wartremover.test.WartTestTraverser
import org.wartremover.warts

/** The parent the `LeakingSealed` probe leaks. */
private sealed trait ProbeShape

/** Each wart that `build.sbt` refuses in main sources (`disallowedWarts`, which the build writes
  * into `DisallowedWarts.names`) fires on a probe. WartRemover takes a wart it cannot load, or one
  * that finds nothing on this Scala version, without a message, so a wart that is on is shown to
  * work here. Each probe is typed when this suite compiles and never runs.
  */
class DisallowedWartsSuite extends munit.FunSuite:

  private final case class Probe(wart: WartTraverser, result: WartTestTraverser.Result):
    def name: String = wart.getClass.getName.stripSuffix("$")
    def tag: String = s"[wartremover:${name.stripPrefix("org.wartremover.warts.")}]"

  /** `ThreadSleep` has no probe: a call to `Thread.sleep`, even one that never runs, is a sleep to
    * `scripts/check_test_sleeps.py`, which refuses one in a test source.
    */
  private val unprobed: Set[String] = Set("org.wartremover.warts.ThreadSleep")

  private val probes: List[Probe] = List(
    Probe(warts.Var, WartTestTraverser(warts.Var)((n: Int) => { var m = n; m += 1; m })),
    Probe(
      warts.While,
      WartTestTraverser(warts.While)((n: Int) => { var m = n; while m > 0 do m -= 1; m })
    ),
    Probe(
      warts.Throw,
      WartTestTraverser(warts.Throw)((b: Boolean) =>
        if b then throw new IllegalStateException else 0
      )
    ),
    Probe(
      warts.Return,
      WartTestTraverser(warts.Return)((b: Boolean) => {
        def first: Int = { if b then return 1; 0 }
        first
      })
    ),
    Probe(warts.Null, WartTestTraverser(warts.Null)(() => { val s: String = null; s })),
    Probe(
      warts.AsInstanceOf,
      WartTestTraverser(warts.AsInstanceOf)((a: Any) => a.asInstanceOf[String])
    ),
    Probe(
      warts.IsInstanceOf,
      WartTestTraverser(warts.IsInstanceOf)((a: Any) => a.isInstanceOf[String])
    ),
    Probe(
      warts.MutableDataStructures,
      WartTestTraverser(warts.MutableDataStructures)(() =>
        scala.collection.mutable.ListBuffer.empty[Int]
      )
    ),
    Probe(warts.OptionPartial, WartTestTraverser(warts.OptionPartial)((o: Option[Int]) => o.get)),
    Probe(warts.TryPartial, WartTestTraverser(warts.TryPartial)((t: scala.util.Try[Int]) => t.get)),
    Probe(
      warts.EitherProjectionPartial,
      WartTestTraverser(warts.EitherProjectionPartial)((e: Either[Int, Int]) => e.left.get)
    ),
    Probe(warts.IterableOps, WartTestTraverser(warts.IterableOps)((xs: List[Int]) => xs.head)),
    Probe(
      warts.TripleQuestionMark,
      WartTestTraverser(warts.TripleQuestionMark)((b: Boolean) => if b then ??? else 0)
    ),
    Probe(
      warts.GlobalExecutionContext,
      WartTestTraverser(warts.GlobalExecutionContext)(() =>
        scala.concurrent.ExecutionContext.global
      )
    ),
    Probe(
      warts.ArrayEquals,
      WartTestTraverser(warts.ArrayEquals)((a: Array[Int], b: Array[Int]) => a == b)
    ),
    Probe(
      warts.Enumeration,
      WartTestTraverser(warts.Enumeration)(() => {
        object Colour extends Enumeration:
          val Red = Value
        Colour.Red
      })
    ),
    Probe(
      warts.LeakingSealed,
      WartTestTraverser(warts.LeakingSealed)(() => {
        class Circle extends ProbeShape
        new Circle
      })
    ),
    Probe(
      warts.SynchronizedValueBasedClasses,
      WartTestTraverser(warts.SynchronizedValueBasedClasses)((n: java.lang.Long) =>
        n.synchronized(n)
      )
    ),
    Probe(warts.StringPlusAny, WartTestTraverser(warts.StringPlusAny)((n: Int) => "n = " + n)),
    Probe(
      warts.Product,
      WartTestTraverser(warts.Product)(() => {
        val arity: Product => Int = p => p.productArity
        arity
      })
    ),
    Probe(
      warts.RedundantConversions,
      WartTestTraverser(warts.RedundantConversions)((n: Int) => n.toInt)
    )
  )

  test("every wart build.sbt refuses has a probe here"):
    assertEquals(
      probes.map(_.name).sorted,
      DisallowedWarts.names.filterNot(unprobed).sorted
    )

  probes.foreach { probe =>
    test(s"${probe.tag} fires on its probe"):
      assert(probe.result.errors.nonEmpty, s"${probe.name} reported nothing")
      probe.result.errors.foreach(error => assert(error.startsWith(probe.tag), error))
  }
