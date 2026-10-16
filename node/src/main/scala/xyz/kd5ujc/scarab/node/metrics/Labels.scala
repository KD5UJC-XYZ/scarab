// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import scala.compiletime.constValueTuple
import scala.compiletime.ops.any.==
import scala.compiletime.ops.string.Matches
import scala.deriving.Mirror

/** A metric name, `[a-zA-Z_:][a-zA-Z0-9_:]*` (the text exposition format, version 0.0.4). The
  * compiler checks a literal against the pattern, so a malformed name in the catalogue does not
  * compile.
  */
opaque type MetricName = String

object MetricName:
  type Valid[S <: String] = Matches[S, "[a-zA-Z_:][a-zA-Z0-9_:]*"] =:= true

  def apply[S <: String & Singleton](name: S)(using Valid[S]): MetricName = name

  extension (name: MetricName) def value: String = name

  given Ordering[MetricName] = Ordering.String

/** A label name, `[a-zA-Z_][a-zA-Z0-9_]*` without the reserved prefix `__`, checked like
  * [[MetricName]]. `le` is the histogram's own and cannot be used.
  */
opaque type LabelName = String

object LabelName:
  type Valid[S <: String] =
    Matches[S, "([a-zA-Z][a-zA-Z0-9_]*|_[a-zA-Z0-9][a-zA-Z0-9_]*)"] =:= true
  type NotLe[S <: String] = (S == "le") =:= false

  def apply[S <: String & Singleton](name: S)(using Valid[S], NotLe[S]): LabelName = name

  extension (name: LabelName) def value: String = name

/** How a value of type `A` is written as a label value. Label values are typed, so a series can
  * only be labelled with a member of a closed type, which keeps the number of series bounded;
  * `String` is allowed for the constants of an info series (a version, a commit).
  */
trait LabelValue[A]:
  def render(a: A): String

object LabelValue:
  def instance[A](f: A => String): LabelValue[A] = f(_)

  given LabelValue[String] = instance(identity)

  /** The name of the case, for an enum or a sealed family of objects: `derives LabelValue`. */
  inline def derived[A](using m: Mirror.SumOf[A]): LabelValue[A] =
    val names = constValueTuple[m.MirroredElemLabels].toList.asInstanceOf[List[String]].toVector
    instance(a => names(m.ordinal(a)))

/** The labels of a metric: their names, and how a value of the label type `L` becomes their values
  * (in the same order). `Unit` is the metric without labels.
  */
final class Labels[L] private (val names: List[LabelName], val values: L => List[String])

object Labels:
  val none: Labels[Unit] = new Labels(Nil, _ => Nil)

  def one[A](name: LabelName)(using A: LabelValue[A]): Labels[A] =
    new Labels(List(name), a => List(A.render(a)))

  def two[A, B](first: LabelName, second: LabelName)(using
      A: LabelValue[A],
      B: LabelValue[B]
  ): Labels[(A, B)] =
    new Labels(List(first, second), (a, b) => List(A.render(a), B.render(b)))

  def three[A, B, C](first: LabelName, second: LabelName, third: LabelName)(using
      A: LabelValue[A],
      B: LabelValue[B],
      C: LabelValue[C]
  ): Labels[(A, B, C)] =
    new Labels(List(first, second, third), (a, b, c) => List(A.render(a), B.render(b), C.render(c)))
