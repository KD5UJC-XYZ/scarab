// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Show}
import scala.collection.immutable.SortedMap
import scala.compiletime.{constValue, erasedValue, summonFrom, summonInline}
import scala.deriving.Mirror
import scala.reflect.ClassTag

/** One step of a [[Path]] into a state. */
enum Step:
  /** A field of a record, by its name. */
  case Field(name: String)

  /** An entry of a map, by its key as the key shows. */
  case Key(shown: String)

  /** An element of a sequence, by its position. */
  case Index(position: Int)

  /** An element of a set that is only on one side, by how it shows. */
  case Member(shown: String)

/** Where in a state two values differ: the steps from the root, rendered as
  * `nes.es.ls.cert.dState.accounts[<credential>].balance`.
  */
final case class Path(steps: Vector[Step]):
  def /(step: Step): Path = Path(steps :+ step)

  def render: String =
    steps
      .foldLeft(new StringBuilder) {
        case (b, Step.Field(n))  => (if b.isEmpty then b else b.append('.')).append(n)
        case (b, Step.Key(k))    => b.append('[').append(k).append(']')
        case (b, Step.Index(i))  => b.append('[').append(i).append(']')
        case (b, Step.Member(m)) => b.append('{').append(m).append('}')
      }
      .toString

object Path:
  val root: Path = Path(Vector.empty)
  given Eq[Path] = Eq.fromUniversalEquals
  given Show[Path] = Show.show(_.render)

/** The first difference of two values: where it is, and how each side shows there. */
final case class Difference(path: Path, left: String, right: String):
  /** The difference seen from a record that holds the value at `step`. */
  def under(step: Step): Difference = copy(path = Path(step +: path.steps))

object Difference:
  given Eq[Difference] = Eq.fromUniversalEquals
  given Show[Difference] =
    Show.show(d => s"${d.path.render}: ${d.left} /= ${d.right}")

  /** Values are rendered for a human, and a state is big: the rendering stops here. */
  val ShownLimit: Int = 300

  def clipped(s: String): String =
    if s.length <= ShownLimit then s else s.take(ShownLimit) + "..."

/** The first difference of two values of a state type, by the path to it. The type-class form of
  * `Eq` for a comparison that has to say *where*: the differential harness (design
  * `ledger-mirror-step2.md` §2.5) names the path of a divergence in its repro bundle, and a failing
  * property test shows it instead of two states of a hundred thousand accounts.
  *
  * The laws, and the property tests in `DiffSuite`: `diff(a, b)` is empty exactly when `a` and `b`
  * are equal (by the `Eq` of the type), and a difference is found by the same path from either
  * side.
  *
  * Records and enums derive it (`derives Diff`: the first field that differs, by name; for an enum
  * the case, then its fields); leaves take it from their `Eq` and `Show` ([[Diff.leaf]], the
  * default for every type that has them); sequences, options, sets and maps look inside. The
  * instances of an era-indexed type are written beside its `Eq`, by the witness, as they are.
  */
trait Diff[A]:
  /** The first difference, if the two are different. Records are walked in field order and sorted
    * maps in key order, so the answer is the same on every run for equal inputs.
    */
  def diff(a: A, b: A): Option[Difference]

  /** A value as it is rendered in a difference. */
  def show(a: A): String

object Diff extends LowPriorityDiff:
  def apply[A](using d: Diff[A]): Diff[A] = d

  /** A leaf: equal by `Eq`, rendered by `Show`. */
  def leaf[A](equal: Eq[A], shown: A => String): Diff[A] = new Diff[A]:
    def diff(a: A, b: A): Option[Difference] =
      if equal.eqv(a, b) then None
      else Some(Difference(Path.root, Difference.clipped(shown(a)), Difference.clipped(shown(b))))
    def show(a: A): String = Difference.clipped(shown(a))

  /** The step of a record's [[record]] instance: the difference in the field `name`, by the
    * instance of the field's type.
    */
  def at[A, B](name: String)(get: A => B)(using d: Diff[B]): (A, A) => Option[Difference] =
    (a, b) => d.diff(get(a), get(b)).map(_.under(Step.Field(name)))

  /** The instance of a record that `derives` cannot make (a type with an era parameter: its fields
    * are match types, which implicit search cannot see into; see `EraInstances`): the steps in
    * field order, the first that finds a difference wins.
    */
  def record[A](steps: ((A, A) => Option[Difference])*): Diff[A] = new Diff[A]:
    def diff(a: A, b: A): Option[Difference] =
      if same(a, b) then None
      else steps.iterator.flatMap(step => step(a, b)).nextOption()
    def show(a: A): String = Difference.clipped(a.toString)

  /** Reference identity without a cast: a boxed value type is never `eq` to another box, so a value
    * type falls through to the real comparison.
    */
  private def same(a: Any, b: Any): Boolean = (a, b) match
    case (x: AnyRef, y: AnyRef) => x eq y
    case _                      => false

  // ---- containers

  given option[A](using d: Diff[A]): Diff[Option[A]] = new Diff[Option[A]]:
    def diff(a: Option[A], b: Option[A]): Option[Difference] = (a, b) match
      case (Some(x), Some(y)) => d.diff(x, y)
      case (None, None)       => None
      case _                  => Some(Difference(Path.root, show(a), show(b)))
    def show(a: Option[A]): String = a.fold("None")(x => s"Some(${d.show(x)})")

  given sequence[A](using d: Diff[A]): Diff[Vector[A]] = new Diff[Vector[A]]:
    def diff(a: Vector[A], b: Vector[A]): Option[Difference] =
      if a eq b then None
      else
        val common = math.min(a.size, b.size)
        var i = 0
        var found: Option[Difference] = None
        while found.isEmpty && i < common do
          found = d.diff(a(i), b(i)).map(_.under(Step.Index(i)))
          i += 1
        found.orElse {
          if a.size == b.size then None
          else
            val shown = (v: Vector[A]) => if v.size > common then d.show(v(common)) else "<absent>"
            Some(Difference(Path(Vector(Step.Index(common))), shown(a), shown(b)))
        }
    def show(a: Vector[A]): String =
      Difference.clipped(a.map(d.show).mkString("Vector(", ", ", ")"))

  given list[A](using d: Diff[Vector[A]]): Diff[List[A]] = new Diff[List[A]]:
    def diff(a: List[A], b: List[A]): Option[Difference] = d.diff(a.toVector, b.toVector)
    def show(a: List[A]): String = d.show(a.toVector)

  given set[A](using shownA: Show[A]): Diff[Set[A]] = new Diff[Set[A]]:
    def diff(a: Set[A], b: Set[A]): Option[Difference] =
      if a == b then None
      else
        def few(s: Set[A]) = s.toList.map(shownA.show).sorted.take(3).mkString(", ")
        // the first member (by rendering) that is on one side only
        val onlyA = a.diff(b)
        val onlyB = b.diff(a)
        val first = (onlyA.toList.map(shownA.show) ++ onlyB.toList.map(shownA.show)).min
        Some(
          Difference(
            Path(Vector(Step.Member(Difference.clipped(first)))),
            s"only here: {${few(onlyA)}}",
            s"only there: {${few(onlyB)}}"
          )
        )
    def show(a: Set[A]): String =
      Difference.clipped(a.toList.map(shownA.show).sorted.mkString("Set(", ", ", ")"))

  given sortedMap[K, V](using shownK: Show[K], d: Diff[V]): Diff[SortedMap[K, V]] =
    new Diff[SortedMap[K, V]]:
      def diff(a: SortedMap[K, V], b: SortedMap[K, V]): Option[Difference] =
        if a eq b then None
        else mapDiff(a, b, shownK, d)
      def show(a: SortedMap[K, V]): String = s"SortedMap(${a.size} entries)"

  given map[K, V](using shownK: Show[K], d: Diff[V]): Diff[Map[K, V]] = new Diff[Map[K, V]]:
    def diff(a: Map[K, V], b: Map[K, V]): Option[Difference] =
      if a eq b then None
      else mapDiff(a, b, shownK, d)
    def show(a: Map[K, V]): String = s"Map(${a.size} entries)"

  /** The first entry (in the iteration order of the left map) that differs or is missing on the
    * right, else the first entry only the right has.
    */
  private def mapDiff[K, V](
      a: Map[K, V],
      b: Map[K, V],
      shownK: Show[K],
      d: Diff[V]
  ): Option[Difference] =
    def key(k: K) = Step.Key(Difference.clipped(shownK.show(k)))
    val it = a.iterator
    var found: Option[Difference] = None
    while found.isEmpty && it.hasNext do
      val (k, v) = it.next()
      found = b.get(k) match
        case Some(w) => d.diff(v, w).map(_.under(key(k)))
        case None    => Some(Difference(Path(Vector(key(k))), d.show(v), "<absent>"))
    found.orElse {
      b.keysIterator.find(k => !a.contains(k)).map { k =>
        Difference(Path(Vector(key(k))), "<absent>", d.show(b(k)))
      }
    }

  given tuple2[A, B](using da: Diff[A], db: Diff[B]): Diff[(A, B)] = new Diff[(A, B)]:
    def diff(a: (A, B), b: (A, B)): Option[Difference] =
      da.diff(a._1, b._1)
        .map(_.under(Step.Field("_1")))
        .orElse(db.diff(a._2, b._2).map(_.under(Step.Field("_2"))))
    def show(a: (A, B)): String = s"(${da.show(a._1)}, ${db.show(a._2)})"

  // ---- derivation

  /** The instance of a record or an enum: fields in order, by name; for an enum, the case and then
    * its fields. Needs an instance for each field (a leaf instance is available for every type with
    * `Eq`).
    */
  inline def derived[A](using m: Mirror.Of[A]): Diff[A] =
    inline m match
      case p: Mirror.ProductOf[A] => product[A](using p)
      case s: Mirror.SumOf[A]     => sum[A](using s)

  private inline def labels[L <: Tuple]: List[String] = inline erasedValue[L] match
    case _: EmptyTuple => Nil
    // the mirror's labels are string literals; `toString` is the identity on them and keeps the
    // widening checked (no cast) for the general tuple case
    case _: (l *: ls) => constValue[l].toString :: labels[ls]

  /** The instance of each case of an enum: its own `Diff` if it has one, else derived. */
  private inline def caseInstance[T]: Diff[T] = summonFrom {
    case d: Diff[T]      => d
    case m: Mirror.Of[T] => derived[T](using m)
  }

  /** Compare the fields of two products in declaration order: the first field that differs, by
    * name. Each field's value is narrowed to its declared type by a checked type test elaborated
    * per concrete field type at inline expansion, and compared by the instance of that same type —
    * no cast, no `Diff[Any]`, no `@unchecked` projection.
    */
  private inline def productDiff[Ts <: Tuple](
      p: Product,
      q: Product,
      names: List[String],
      i: Int
  ): Option[Difference] =
    inline erasedValue[Ts] match
      case _: EmptyTuple => None
      case _: (t *: ts)  =>
        // the ClassTag extractor narrows each field value to its declared type; the check and
        // the cast live in the stdlib's unapply, not in this source
        val ct = summonInline[ClassTag[t]]
        (p.productElement(i), q.productElement(i)) match
          case (ct(x), ct(y)) =>
            summonInline[Diff[t]]
              .diff(x, y)
              .map(_.under(Step.Field(names.head)))
              .orElse(productDiff[ts](p, q, names.tail, i + 1))
          // unreachable for a well-formed mirror; reported loudly rather than silently skipped
          case _ =>
            Some(
              Difference(
                Path(Vector(Step.Field(names.head))),
                "field value is not of the declared field type",
                "field value is not of the declared field type"
              )
            )

  /** A [[Diff]] from a difference function: the function is built at the derivation site (inline,
    * where the field and case types are concrete); the instance itself is a plain class, so
    * inlining does not duplicate an anonymous class.
    */
  def from[A](diffFn: (A, A) => Option[Difference]): Diff[A] = new Diff[A]:
    def diff(a: A, b: A): Option[Difference] = diffFn(a, b)
    def show(a: A): String = Difference.clipped(a.toString)

  private inline def product[A](using m: Mirror.ProductOf[A]): Diff[A] =
    val names = labels[m.MirroredElemLabels]
    from[A]((a, b) =>
      if same(a, b) then None
      else
        // a type with a product mirror is a Product at runtime: the narrowing is a checked type
        // test, and each field value is re-checked against its declared type in `productDiff`
        (a: Any, b: Any) match
          case (p: Product, q: Product) => productDiff[m.MirroredElemTypes](p, q, names, 0)
          case _                        => None // unreachable: the mirror proves the shape
    )

  private inline def sum[A](using m: Mirror.SumOf[A]): Diff[A] =
    val names = labels[m.MirroredElemLabels].toArray
    // the case instances are made once per derivation (on the first same-case comparison), not on
    // every comparison
    val cases = caseSteps[m.MirroredElemTypes, A].toArray
    from[A]((a, b) =>
      if same(a, b) then None
      else if m.ordinal(a) != m.ordinal(b) then
        Some(
          Difference(
            Path.root,
            Difference.clipped(s"${names(m.ordinal(a))}: ${a.toString}"),
            Difference.clipped(s"${names(m.ordinal(b))}: ${b.toString}")
          )
        )
      else
        // walk the cases by index: no iterator per comparison
        var found: Option[Difference] = None
        var i = 0
        while found.isEmpty && i < cases.length do
          found = cases(i)(a, b)
          i += 1
        found
    )

  /** One step per case, in declaration order: the case's instance (made on first use and kept) and
    * the `ClassTag` check that narrows the values to the case type. A step that does not recognise
    * the values answers none; the first that does is the case both inhabit.
    */
  private inline def caseSteps[Ts <: Tuple, A]: List[(A, A) => Option[Difference]] =
    inline erasedValue[Ts] match
      case _: EmptyTuple => Nil
      case _: (t *: ts) =>
        val ct = summonInline[ClassTag[t]]
        val held = new Memo(() => caseInstance[t])
        val step: (A, A) => Option[Difference] = (a, b) =>
          (ct.unapply(a), ct.unapply(b)) match
            case (Some(x), Some(y)) => held.value.diff(x, y)
            case _                  => None
        step :: caseSteps[ts, A]

  /** A value made on first use and kept: the instance of a case must not be built on every
    * comparison, and must not be built while the enum's own instance is being made (a case may hold
    * the enum).
    */
  private[scarab] final class Memo[A](init: () => A):
    lazy val value: A = init()

extension [A](a: A)
  /** The first difference from `b`, by the instance of the type. */
  def firstDifference(b: A)(using d: Diff[A]): Option[Difference] = d.diff(a, b)

/** The default instances: a type with `Eq` and `Show` is a leaf that renders by `Show`; a type with
  * `Eq` alone, by `toString`. They rank below every instance written for a type, in its companion
  * or in [[Diff]].
  */
trait LowPriorityDiff extends LowestPriorityDiff:
  given leafShown[A](using equal: Eq[A], shown: Show[A]): Diff[A] = Diff.leaf(equal, shown.show)

trait LowestPriorityDiff:
  given leafPlain[A](using equal: Eq[A]): Diff[A] = Diff.leaf(equal, _.toString)
