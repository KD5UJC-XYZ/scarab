// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  DefaultUni,
  PlutusData,
  Program,
  ProgramVersion,
  Term,
  TermF
}
import xyz.kd5ujc.scarab.uplc.builtin.{
  BuiltinFailure,
  ErrorClass,
  EvaluationFailure,
  Outcome,
  UnliftingCause,
  UnliftingFailure
}
import xyz.kd5ujc.scarab.uplc.cek.{CaseBuiltinError, CaserBuiltin}
import xyz.kd5ujc.scarab.uplc.cost.Measure
import xyz.kd5ujc.scarab.uplc.flat.{Checks, FlatDecoder, FlatEncoder, FlatGen}

import CoreTesting.*
import DefaultFun.*

/** Lists of `Data` and of pairs of `Data` keep the values of the `Data` node they come from, and
  * behave as lists of the constants that wrap those values did.
  *
  * The properties compare the conversion builtins, the list operations of `Constant.List`, the list
  * builtins (`headList`, `tailList`, `nullList`, `chooseList`, `dropList`, `mkCons`: value, error
  * and log lines), `case` on a list and the costing size, on both forms, with the earlier way of
  * computing them, kept here as [[Wrapped]]: every element wrapped in a constant, the element type
  * checked on every element. Equality, hashing, rendering and `toArray` are compared for lists of
  * `Data`, and the Flat encoding of both forms decodes to the same list. The verdicts and budgets
  * of whole programs are the conformance and oracle suites' (`CekConformance171Suite`,
  * `GeneratedOracle171Suite`).
  */
final class DataListSuite extends munit.ScalaCheckSuite:
  private val E = CoreVariant.E
  private val DataPair = DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data)

  /** A list as it was kept before: its element type and its elements as constants. */
  private final case class Wrapped(elementType: DefaultUni, items: Vector[Constant]):
    def headOption: Option[Constant] = items.headOption
    def tail: Option[Wrapped] = Option.when(items.nonEmpty)(Wrapped(elementType, items.drop(1)))
    def drop(n: Int): Wrapped = Wrapped(elementType, items.drop(n))
    def cons(head: Constant): Either[Constant.ElementTypeMismatch, Wrapped] =
      Either.cond(
        head.typeOf == elementType,
        Wrapped(elementType, head +: items),
        Constant.ElementTypeMismatch(elementType, head.typeOf, 0)
      )

  private object Wrapped:
    def of(elementType: DefaultUni, items: Vector[Constant]) =
      items.zipWithIndex
        .collectFirst {
          case (c, i) if c.typeOf != elementType =>
            Constant.ElementTypeMismatch(elementType, c.typeOf, i)
        }
        .toLeft(Wrapped(elementType, items))
    def ofData(ds: Vector[PlutusData]): Wrapped = Wrapped(DefaultUni.Data, ds.map(Constant.Data(_)))
    def ofPairs(es: Vector[(PlutusData, PlutusData)]): Wrapped =
      Wrapped(DataPair, es.map((k, v) => Constant.Pair(Constant.Data(k), Constant.Data(v))))
    def data(w: Wrapped): Vector[PlutusData] = w.items.collect { case Constant.Data(d) => d }
    def pairs(w: Wrapped): Vector[(PlutusData, PlutusData)] =
      w.items.collect { case Constant.Pair(Constant.Data(k), Constant.Data(v)) => (k, v) }

  private def agree(list: Constant.List, wrapped: Wrapped, what: String): Prop =
    Prop.all(
      Prop(list.elementType == wrapped.elementType) :| s"$what: the element type",
      Prop(list.typeOf == DefaultUni.List(wrapped.elementType)) :| s"$what: the type",
      Prop(list.items.toVector == wrapped.items) :| s"$what: the elements",
      Prop(list.size == wrapped.items.size) :| s"$what: the size",
      Prop(list.isEmpty == wrapped.items.isEmpty) :| s"$what: emptiness",
      Prop(list.headOption == wrapped.headOption) :| s"$what: the head",
      Prop(list.items.iterator.toVector == wrapped.items) :| s"$what: the elements in order"
    )

  private def asList(c: Constant, what: String): Constant.List = c match
    case l: Constant.List => l
    case other            => fail(s"$what is not a list: $other")

  private def constant(outcome: Outcome[Probe]): Constant = result(outcome) match
    case Right(c)      => c
    case Left(failure) => fail(s"the application failed: $failure")

  private val plutusData: Gen[PlutusData] =
    FlatGen.constantOf(DefaultUni.Data).map {
      case Constant.Data(d) => d
      case other            => fail(s"not data: $other")
    }
  private val items: Gen[Vector[PlutusData]] =
    Gen.choose(0, 6).flatMap(Gen.listOfN(_, plutusData)).map(_.toVector)
  private val entries: Gen[Vector[(PlutusData, PlutusData)]] =
    Gen.choose(0, 6).flatMap(Gen.listOfN(_, Gen.zip(plutusData, plutusData))).map(_.toVector)

  test("the conversions out of Data keep the node's own vector") {
    val xs = Vector(PlutusData.I(1), PlutusData.List(Vector(PlutusData.I(2))))
    val es = Vector(PlutusData.I(1) -> PlutusData.I(2), PlutusData.I(1) -> PlutusData.I(3))
    constant(CoreTesting(E, UnListData, data(PlutusData.List(xs)))) match
      case Constant.List.OfData(ds) => assert(ds eq xs, "unListData copied the items")
      case other                    => fail(s"unListData: $other")
    constant(CoreTesting(E, UnMapData, data(PlutusData.Map(es)))) match
      case Constant.List.OfDataPairs(ps) => assert(ps eq es, "unMapData copied the entries")
      case other                         => fail(s"unMapData: $other")
    constant(CoreTesting(E, UnConstrData, data(PlutusData.Constr(7, xs)))) match
      case Constant.Pair(Constant.Integer(i), Constant.List.OfData(ds)) =>
        assertEquals(i, BigInt(7))
        assert(ds eq xs, "unConstrData copied the fields")
      case other => fail(s"unConstrData: $other")
    val branches = Vector.fill(8)(Term.constant(Constant.Unit))
    CaserBuiltin.WithData.select(Constant.Data(PlutusData.Constr(7, xs)), branches) match
      case CaserBuiltin.Selection.Applied(_, Constant.List.OfData(ds) :: Nil) =>
        assert(ds eq xs, "case copied the fields")
      case other => fail(s"case: $other")
  }

  test("the conversions into Data keep the list's own vector") {
    val xs = Vector(PlutusData.I(1), PlutusData.B(hexBytes("ab")))
    val es = Vector(PlutusData.I(1) -> PlutusData.I(2))
    constant(CoreTesting(E, ListData, con(Constant.List.OfData(xs)))) match
      case Constant.Data(PlutusData.List(ds)) => assert(ds eq xs, "listData copied the items")
      case other                              => fail(s"listData: $other")
    constant(CoreTesting(E, MapData, con(Constant.List.OfDataPairs(es)))) match
      case Constant.Data(PlutusData.Map(ps)) => assert(ps eq es, "mapData copied the entries")
      case other                             => fail(s"mapData: $other")
    constant(CoreTesting(E, ConstrData, int(3), con(Constant.List.OfData(xs)))) match
      case Constant.Data(PlutusData.Constr(i, ds)) =>
        assertEquals(i, BigInt(3))
        assert(ds eq xs, "constrData copied the fields")
      case other => fail(s"constrData: $other")
  }

  test("a list's form follows from its element type alone") {
    val zero = Constant.Data(PlutusData.I(0))
    val pair = Constant.Pair(zero, zero)
    def form(l: Constant.List): String = l match
      case Constant.List.OfData(_)         => "data"
      case Constant.List.OfDataPairs(_)    => "pairs"
      case Constant.List.OfConstants(_, _) => "constants"
    def made(e: Either[Constant.ElementTypeMismatch, Constant.List]) =
      e.fold(m => fail(m.toString), identity)
    assertEquals(form(Constant.List.empty(DefaultUni.Data)), "data")
    assertEquals(form(Constant.List.empty(DataPair)), "pairs")
    assertEquals(form(made(Constant.List.of(DefaultUni.Data, Vector(zero)))), "data")
    assertEquals(form(made(Constant.List.of(DataPair, Vector(pair)))), "pairs")
    assertEquals(form(made(Constant.List.empty(DefaultUni.Data).cons(zero))), "data")
    assertEquals(form(made(Constant.List.empty(DataPair).cons(pair))), "pairs")
    val others = Vector(
      DefaultUni.Integer,
      DefaultUni.List(DefaultUni.Data),
      DefaultUni.Pair(DefaultUni.Data, DefaultUni.Integer),
      DefaultUni.Pair(DefaultUni.Integer, DefaultUni.Data),
      DefaultUni.Array(DefaultUni.Data)
    )
    for t <- others do assertEquals(form(Constant.List.empty(t)), "constants", s"$t")
  }

  property("unListData, unConstrData and case give the list of the fields, as before") {
    Prop.forAll(items, Gen.choose(0L, 5L)) { (xs, tag) =>
      val branches = Vector.fill(6)(Term.constant(Constant.Unit))
      val cased = CaserBuiltin.WithData.select(Constant.Data(PlutusData.Constr(tag, xs)), branches)
      val fields = cased match
        case CaserBuiltin.Selection.Applied(_, (l: Constant.List) :: Nil) => l
        case other                                                        => fail(s"case: $other")
      val unConstr = constant(CoreTesting(E, UnConstrData, data(PlutusData.Constr(tag, xs))))
      agree(
        asList(constant(CoreTesting(E, UnListData, data(PlutusData.List(xs)))), "unListData"),
        Wrapped.ofData(xs),
        "unListData"
      ) && agree(fields, Wrapped.ofData(xs), "case") && (unConstr match
        case Constant.Pair(Constant.Integer(i), l: Constant.List) =>
          Prop(i == BigInt(tag)) :| "unConstrData: the tag" &&
          agree(l, Wrapped.ofData(xs), "unConstrData")
        case other => Prop(false) :| s"unConstrData: $other")
    }
  }

  property("unMapData gives the list of the entries, as before") {
    Prop.forAll(entries) { es =>
      agree(
        asList(constant(CoreTesting(E, UnMapData, data(PlutusData.Map(es)))), "unMapData"),
        Wrapped.ofPairs(es),
        "unMapData"
      )
    }
  }

  property("listData, mapData and constrData read the elements, as before") {
    Prop.forAll(items, entries) { (xs, es) =>
      val datas = Wrapped.ofData(xs)
      val pairs = Wrapped.ofPairs(es)
      val dataList =
        con(asList(constant(CoreTesting(E, UnListData, data(PlutusData.List(xs)))), ""))
      val pairList = con(asList(constant(CoreTesting(E, UnMapData, data(PlutusData.Map(es)))), ""))
      Prop.all(
        Prop(
          constant(CoreTesting(E, ListData, dataList)) ==
            Constant.Data(PlutusData.List(Wrapped.data(datas)))
        ) :| "listData",
        Prop(
          constant(CoreTesting(E, MapData, pairList)) ==
            Constant.Data(PlutusData.Map(Wrapped.pairs(pairs)))
        ) :| "mapData",
        Prop(
          constant(CoreTesting(E, ConstrData, int(2), dataList)) ==
            Constant.Data(PlutusData.Constr(2, Wrapped.data(datas)))
        ) :| "constrData"
      )
    }
  }

  // Elements of another type than the list's for `cons`: all three for a list of pairs, the first
  // two for a list of `Data`.
  private val strangers: Gen[Constant] =
    Gen.oneOf(Constant.Integer(1), Constant.Unit, Constant.Data(PlutusData.I(1)))

  // A run of list operations on a list of `Data` or of pairs of `Data`, and on its wrapped form.
  private enum Step:
    case Tail
    case Drop(n: Int)
    case Cons(head: Constant)

  // `cons` of an element of the list's type, or of a stranger, which it refuses.
  private def steps(element: Gen[Constant]): Gen[Vector[Step]] =
    val step = Gen.oneOf(
      Gen.const(Step.Tail),
      Gen.choose(-1, 4).map(Step.Drop(_)),
      element.map(Step.Cons(_)),
      strangers.map(Step.Cons(_))
    )
    Gen.choose(0, 8).flatMap(Gen.listOfN(_, step)).map(_.toVector)

  private def run(list: Constant.List, wrapped: Wrapped, todo: Vector[Step]): Prop =
    todo
      .foldLeft[(Prop, Option[(Constant.List, Wrapped)])](
        (agree(list, wrapped, "start"), Some((list, wrapped)))
      ) {
        case ((p, Some((l, w))), step) =>
          step match
            case Step.Tail =>
              (l.tail, w.tail) match
                case (Some(lt), Some(wt)) => (p && agree(lt, wt, "tail"), Some((lt, wt)))
                case (None, None)         => (p, Some((l, w)))
                case (a, b)               => (p && (Prop(false) :| s"tail: $a against $b"), None)
            case Step.Drop(n) =>
              val (ld, wd) = (l.drop(n), w.drop(n))
              (p && agree(ld, wd, s"drop $n"), Some((ld, wd)))
            case Step.Cons(head) =>
              (l.cons(head), w.cons(head)) match
                case (Right(lc), Right(wc)) => (p && agree(lc, wc, s"cons $head"), Some((lc, wc)))
                case (Left(a), Left(b)) =>
                  (p && (Prop(a == b) :| s"cons $head: the refusal"), Some((l, w)))
                case (a, b) => (p && (Prop(false) :| s"cons $head: $a against $b"), None)
        case ((p, None), _) => (p, None)
      }
      ._1

  property("the list operations on a list of Data give what they gave on the wrapped elements") {
    Prop.forAll(items, steps(plutusData.map(Constant.Data(_)))) { (xs, todo) =>
      run(Constant.List.OfData(xs), Wrapped.ofData(xs), todo)
    }
  }

  property("the list operations on a list of pairs of Data give what they gave before") {
    val pair = Gen
      .zip(plutusData, plutusData)
      .map((k, v) => Constant.Pair(Constant.Data(k), Constant.Data(v)))
    Prop.forAll(entries, steps(pair)) { (es, todo) =>
      run(Constant.List.OfDataPairs(es), Wrapped.ofPairs(es), todo)
    }
  }

  // Element types that reach each form, and elements of them or of a neighbouring type.
  private val elementTypes: Gen[DefaultUni] = Gen.oneOf(
    DefaultUni.Data,
    DataPair,
    DefaultUni.Integer,
    DefaultUni.List(DefaultUni.Data),
    DefaultUni.Pair(DefaultUni.Data, DefaultUni.Integer),
    DefaultUni.Pair(DefaultUni.Integer, DefaultUni.Data)
  )

  property("List.of keeps or refuses the elements as the check of every element did") {
    val cases = for
      t <- elementTypes
      other <- elementTypes
      ok <- Gen.choose(0, 5).flatMap(Gen.listOfN(_, FlatGen.constantOf(t)))
      wrong <- Gen.choose(0, 2).flatMap(Gen.listOfN(_, FlatGen.constantOf(other)))
      at <- Gen.choose(0, ok.size)
    yield (t, (ok.take(at) ++ wrong ++ ok.drop(at)).toVector)
    Prop.forAll(cases) { (t, cs) =>
      (Constant.List.of(t, cs), Wrapped.of(t, cs)) match
        case (Right(l), Right(w)) => agree(l, w, "of")
        case (Left(a), Left(b))   => Prop(a == b) :| s"the refusal: $a against $b"
        case (a, b)               => Prop(false) :| s"of: $a against $b"
    }
  }

  // `Tree.hash` and `Tree.render` over the wrapped elements: a list's label, then each element
  // in order, a `Data` constant being a leaf.
  private def wrappedHash(xs: Vector[PlutusData]): Int =
    xs.foldLeft(31 * (31 * 17 + 12) + xs.size)((h, d) => 31 * (31 * h + 7 + d.hashCode))
  private def wrappedRendering(xs: Vector[PlutusData]): String =
    val whole = "List(Data)" + (if xs.isEmpty then ""
                                else xs.map(d => s"Data($d)").mkString("[", ", ", "]"))
    if whole.length <= 1024 then whole else whole.substring(0, 1024) + "..."

  property("a list of Data equals, hashes and renders as the list of its wrapped elements did") {
    Prop.forAll(items) { xs =>
      val shared: Constant = Constant.List.OfData(xs)
      val checked = Constant.List.of(DefaultUni.Data, xs.map(Constant.Data(_)))
      val array = Constant.Array.of(DefaultUni.Data, xs.map(Constant.Data(_)))
      Prop.all(
        Prop(checked == Right(shared)) :| "equality",
        Prop(shared.hashCode == wrappedHash(xs)) :| "hash",
        Prop(shared.toString == wrappedRendering(xs)) :| "rendering",
        Prop(Right(asList(shared, "").toArray) == array) :| "toArray"
      )
    }
  }

  private def listOf(w: Wrapped): Constant =
    Constant.List.of(w.elementType, w.items).fold(m => fail(m.toString), identity)

  // The list builtins, `case` and the costing size on `list` against what they gave on `w`.
  private def builtinsAgree(list: Constant.List, w: Wrapped, head: Constant, n: BigInt): Prop =
    val xs = con(list)
    val (ifEmpty, ifNot) = (Probe.Lambda("empty"), Probe.Lambda("not empty"))
    val empty: Outcome[Probe] = Outcome.failed(EvaluationFailure.EmptyList)
    def ok(c: Constant): Outcome[Probe] = Outcome.Success(con(c), Vector.empty)
    val dropped = if n.signum <= 0 then w.items else w.items.drop(n.min(w.items.size).toInt)
    val consed = w.cons(head) match
      case Right(c) => ok(listOf(c))
      case Left(m) =>
        val cause = UnliftingCause.ElementMismatch(m.expected, m.found)
        Outcome.Failure(
          BuiltinFailure.Unlifting(UnliftingFailure(ErrorClass.Structural, cause)),
          Vector.empty
        )
    val one = Vector(Term.constant(Constant.Integer(1)))
    val two = one :+ Term.constant(Constant.Integer(2))
    def cased(branches: Vector[Term]): CaserBuiltin.Selection = w.headOption match
      case Some(h) =>
        val rest = listOf(Wrapped(w.elementType, w.items.drop(1)))
        CaserBuiltin.Selection.Applied(branches(0), h :: rest :: Nil)
      case None if branches.size == 1 => CaserBuiltin.Selection.Refused(CaseBuiltinError.EmptyList)
      case None                       => CaserBuiltin.Selection.Branch(branches(1))
    Prop.all(
      Prop(CoreTesting(E, HeadList, xs) == w.headOption.fold(empty)(ok)) :| "headList",
      Prop(CoreTesting(E, TailList, xs) == w.tail.fold(empty)(t => ok(listOf(t)))) :| "tailList",
      Prop(CoreTesting(E, NullList, xs) == ok(Constant.Bool(w.items.isEmpty))) :| "nullList",
      Prop(
        CoreTesting(E, ChooseList, xs, ifEmpty, ifNot) ==
          Outcome.Success(if w.items.isEmpty then ifEmpty else ifNot, Vector.empty)
      ) :| "chooseList",
      Prop(CoreTesting(E, DropList, int(n), xs) == ok(listOf(Wrapped(w.elementType, dropped)))) :|
        s"dropList $n",
      Prop(CoreTesting(E, MkCons, con(head), xs) == consed) :| s"mkCons $head",
      Prop(CaserBuiltin.WithData.select(list, one) == cased(one)) :| "case with one branch",
      Prop(CaserBuiltin.WithData.select(list, two) == cased(two)) :| "case with two branches",
      Prop(Measure.constant(list).toVector == Measure.list(w.items.size).toVector) :|
        "the costing size"
    )

  private val counts: Gen[BigInt] =
    Gen.oneOf(Gen.choose(-2, 8).map(BigInt(_)), Gen.const(BigInt(2).pow(70)))

  property("the list builtins and case on a list of Data give what they gave on the wrapped one") {
    val heads = Gen.oneOf(plutusData.map(Constant.Data(_)), strangers)
    Prop.forAll(items, heads, counts) { (xs, head, n) =>
      builtinsAgree(Constant.List.OfData(xs), Wrapped.ofData(xs), head, n)
    }
  }

  property("the list builtins and case on a list of pairs of Data give what they gave before") {
    val pairs =
      Gen
        .zip(plutusData, plutusData)
        .map((k, v) => Constant.Pair(Constant.Data(k), Constant.Data(v)))
    Prop.forAll(entries, Gen.oneOf(pairs, strangers), counts) { (es, head, n) =>
      builtinsAgree(Constant.List.OfDataPairs(es), Wrapped.ofPairs(es), head, n)
    }
  }

  property("the Flat encoding of a list of Data or of pairs decodes to the same list") {
    def roundTrip(list: Constant.List): Prop =
      val program = Program(ProgramVersion.V1_1_0, Term.constant(list))
      val decoded = FlatEncoder
        .program(program)
        .left
        .map(_.toString)
        .flatMap(FlatDecoder.program(_, Checks.unrestricted).left.map(_.toString))
      decoded.map(_.term.unfix) match
        case Right(TermF.Constant(c: Constant.List)) =>
          Prop(c == list) :| "the same list" &&
          Prop(c.getClass == list.getClass) :| "the same form"
        case other => Prop(false) :| s"decoded $other"
    Prop.forAll(items, entries) { (xs, es) =>
      roundTrip(Constant.List.OfData(xs)) && roundTrip(Constant.List.OfDataPairs(es))
    }
  }
