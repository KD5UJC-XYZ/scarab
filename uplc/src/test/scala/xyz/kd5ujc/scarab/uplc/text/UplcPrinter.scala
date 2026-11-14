// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import cats.{Applicative, Eval, Traverse}
import cats.data.{Chain, State}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.uplc.{
  Constant as Con,
  DefaultUni,
  PlutusData,
  Program,
  Schemes,
  Term,
  TermF,
  Value
}

/** Programs written in the textual syntax, in the forms of the reference's classic printer
  * (`UntypedPlutusCore/Core/Instance/Pretty/Classic.hs:22-93`, plutus 1.70.0.0 `507cab8c`) and its
  * constant printer (`PlutusCore/Pretty/PrettyConst.hs`), on one line. What it writes,
  * [[UplcParser]] reads back to the same program; layout and the text of names are not the
  * reference's, which the conformance runner does not compare (it parses the expected result,
  * `PlutusConformance/Common.hs:438-440`).
  *
  * The names of binders follow a [[Naming]]. By default ([[Naming.Depth]]) a `lam` at depth `d`
  * (the number of `lam` s around it) binds the name `x-d`: text `x`, unique `d`. A variable then
  * prints the name of the `lam` its index reaches; a free index gets a negative unique, written as
  * the unsigned decimal that the parser wraps back to it.
  *
  * Printing is an unfold of the program into nested groups of text, folded into a [[Chain]] of
  * pieces ([[Schemes.hyloM]], whose state numbers the binders): a deep term or constant is printed
  * in constant stack.
  */
object UplcPrinter:

  /** How the binders of a de Bruijn term are named. */
  enum Naming(val text: String):
    /** `x-d`, the unique of a binder its depth. */
    case Depth extends Naming("x")

    /** The names the reference gives a de Bruijn term it makes named: text `i` (`fakeName`,
      * `PlutusCore/DeBruijn/Internal.hs:134`, plutus 1.70.0.0 `507cab8c`) and a fresh unique for
      * each binder (`declareBinder`, `Internal.hs:240-243`), counted from 0 (`emptyFreshState`,
      * `PlutusCore/Quote.hs:51-52`) in the order `unDeBruijnTermWithM` visits them
      * (`UntypedPlutusCore/DeBruijn.hs:105-125`): a `lam` before its body, the function of an
      * application before its argument, fields and branches left to right. Two sibling `lam` s
      * therefore have different names, where [[Depth]] gives them the same.
      */
    case Fresh extends Naming("i")

  /** A piece of the output: an atom, or a group of children between an opening and a closing text,
    * with a separator between them.
    */
  private enum Layout[+R]:
    case Atom(text: String)
    case Group(open: String, children: Vector[R], separator: String, close: String)

  private object Layout:
    given Traverse[Layout] with
      def traverse[G[_], A, B](fa: Layout[A])(f: A => G[B])(using
          G: Applicative[G]
      ): G[Layout[B]] = fa match
        case Atom(t)                   => G.pure(Atom(t))
        case Group(open, xs, sep, end) => xs.traverse(f).map(Group(open, _, sep, end))
      def foldLeft[A, B](fa: Layout[A], b: B)(f: (B, A) => B): B = fa match
        case Atom(_)            => b
        case Group(_, xs, _, _) => xs.foldLeft(b)(f)
      def foldRight[A, B](fa: Layout[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
        fa match
          case Atom(_)            => lb
          case Group(_, xs, _, _) => xs.foldr(lb)(f)

  /** What is still to print: a term at its depth, a type, a constant's value, a `data` value or a
    * text.
    */
  private enum Seed:
    /** A term under its binders: the uniques of the `lam` s around it, the innermost last. */
    case OfTerm(term: Term, scope: Vector[Long])
    case OfType(t: DefaultUni)
    case OfValue(c: Con, parens: Boolean)
    case OfData(d: PlutusData)
    case OfEntry(key: PlutusData, value: PlutusData)
    case Verbatim(text: String)

  import Layout.{Atom, Group}
  import Seed.*

  private def name(naming: Naming, unique: Long): String =
    s"${naming.text}-${java.lang.Long.toUnsignedString(unique)}"

  /** The state of an unfold: the next fresh unique ([[Naming.Fresh]]). */
  private type Fresh[A] = State[Long, A]

  private def unfold(naming: Naming)(seed: Seed): Fresh[Layout[Seed]] = seed match
    case OfTerm(t, scope)  => termLayout(naming, t, scope)
    case Verbatim(text)    => State.pure(Atom(text))
    case OfType(t)         => State.pure(typeOf(t))
    case OfValue(c, paren) => State.pure(value(c, paren))
    case OfData(d)         => State.pure(data(d))
    case OfEntry(k, v)     => State.pure(Group("(", Vector(OfData(k), OfData(v)), ", ", ")"))

  /** The unique a variable of `index` names under `scope`; a free index gets the negative one
    * `depth - index`.
    */
  private def unique(scope: Vector[Long], index: Long): Long =
    if index >= 1 && index <= scope.size then scope(scope.size - index.toInt)
    else scope.size.toLong - index

  private def termLayout(naming: Naming, t: Term, scope: Vector[Long]): Fresh[Layout[Seed]] =
    def group(open: String, children: Vector[Seed], separator: String, close: String) =
      State.pure[Long, Layout[Seed]](Group(open, children, separator, close))
    t.unfix match
      case TermF.LamAbs(body) =>
        val binder: Fresh[Long] = naming match
          case Naming.Depth => State.pure(scope.size.toLong)
          case Naming.Fresh => State(next => (next + 1, next))
        binder.map(u =>
          Group("(lam ", Vector(Verbatim(name(naming, u)), OfTerm(body, scope :+ u)), " ", ")")
        )
      case TermF.Var(i)      => State.pure(Atom(name(naming, unique(scope, i.index.bits))))
      case TermF.Apply(f, a) => group("[", Vector(OfTerm(f, scope), OfTerm(a, scope)), " ", "]")
      case TermF.Force(x)    => group("(force ", Vector(OfTerm(x, scope)), "", ")")
      case TermF.Delay(x)    => group("(delay ", Vector(OfTerm(x, scope)), "", ")")
      case TermF.Constant(c) =>
        group("(con ", Vector(OfType(c.typeOf), OfValue(c, parens = true)), " ", ")")
      case TermF.Builtin(f) => State.pure(Atom(s"(builtin ${f.name})"))
      case TermF.Error      => State.pure(Atom("(error)"))
      case TermF.Constr(tag, xs) =>
        group("(constr ", Verbatim(tag.toBigInt.toString) +: xs.map(OfTerm(_, scope)), " ", ")")
      case TermF.Case(s, bs) => group("(case ", (s +: bs).map(OfTerm(_, scope)), " ", ")")

  // `Pretty (SomeTypeIn DefaultUni)`: an applied operator in parentheses.
  private def typeOf(t: DefaultUni): Layout[Seed] = t match
    case DefaultUni.Integer              => Atom("integer")
    case DefaultUni.ByteString           => Atom("bytestring")
    case DefaultUni.String               => Atom("string")
    case DefaultUni.Unit                 => Atom("unit")
    case DefaultUni.Bool                 => Atom("bool")
    case DefaultUni.Data                 => Atom("data")
    case DefaultUni.Bls12_381_G1_Element => Atom("bls12_381_G1_element")
    case DefaultUni.Bls12_381_G2_Element => Atom("bls12_381_G2_element")
    case DefaultUni.Bls12_381_MlResult   => Atom("bls12_381_mlresult")
    case DefaultUni.Value                => Atom("value")
    case DefaultUni.List(e)              => Group("(list ", Vector(OfType(e)), "", ")")
    case DefaultUni.Array(e)             => Group("(array ", Vector(OfType(e)), "", ")")
    case DefaultUni.Pair(a, b)           => Group("(pair ", Vector(OfType(a), OfType(b)), " ", ")")

  // `PrettyConst`: lists and arrays in brackets, pairs in parentheses, their elements without the
  // parentheses that `data` takes alone (`NoParens`, `PrettyConst.hs:127-141`).
  private def value(c: Con, parens: Boolean): Layout[Seed] = c match
    case Con.Integer(n)    => Atom(n.toString)
    case Con.ByteString(b) => Atom(s"#${b.hex}")
    case Con.String(s) =>
      Atom("\"" + HaskellChar.escaped(s.string.codePoints().toArray.toIndexedSeq) + "\"")
    case Con.Unit                    => Atom("()")
    case Con.Bool(b)                 => Atom(if b then "True" else "False")
    case Con.Data(d) if parens       => Group("(", Vector(OfData(d)), "", ")")
    case Con.Data(d)                 => data(d)
    case Con.Bls12_381_G1_Element(e) => Atom(s"0x${e.compressed.hex}")
    case Con.Bls12_381_G2_Element(e) => Atom(s"0x${e.compressed.hex}")
    // No literal reads it back (`Builtin.hs:169-170`); the reference prints the field element.
    case Con.Bls12_381_MlResult(m) => Atom(s"0x${m.raw.hex}")
    case Con.Value(v)              => Atom(valueText(v))
    case l: Con.List               => Group("[", l.items.map(OfValue(_, parens = false)), ", ", "]")
    case a: Con.Array              => Group("[", a.items.map(OfValue(_, parens = false)), ", ", "]")
    case Con.Pair(x, y) =>
      Group("(", Vector(OfValue(x, parens = false), OfValue(y, parens = false)), ", ", ")")

  // `PrettyBy ConstConfig Data` (`PrettyConst.hs:155-161`).
  private def data(d: PlutusData): Layout[Seed] = d match
    case PlutusData.Constr(i, fs) => Group(s"Constr $i [", fs.map(OfData(_)), ", ", "]")
    case PlutusData.Map(kvs)      => Group("Map [", kvs.map(OfEntry(_, _)), ", ", "]")
    case PlutusData.List(xs)      => Group("List [", xs.map(OfData(_)), ", ", "]")
    case PlutusData.I(n)          => Atom(s"I $n")
    case PlutusData.B(b)          => Atom(s"B #${b.hex}")

  // `PrettyBy ConstConfig Value` (`PrettyConst.hs:163-170`): the list of currencies and tokens. Its
  // depth is fixed.
  private def valueText(v: Value): String =
    v.toList
      .map { (currency, tokens) =>
        val inner = tokens.map((t, q) => s"(#${t.bytes.hex}, ${q.amount})").mkString("[", ", ", "]")
        s"(#${currency.bytes.hex}, $inner)"
      }
      .mkString("[", ", ", "]")

  private def fold(layout: Layout[Chain[String]]): Chain[String] = layout match
    case Atom(text) => Chain.one(text)
    case Group(open, children, separator, close) =>
      val inner = children.zipWithIndex.foldLeft(Chain.empty[String]) { case (acc, (c, i)) =>
        if i == 0 then acc ++ c else acc.append(separator) ++ c
      }
      Chain.one(open) ++ inner :+ close

  private def render(seed: Seed, naming: Naming): String =
    Schemes
      .hyloM[Layout, Fresh, Seed, Chain[String]](seed)(unfold(naming), l => State.pure(fold(l)))
      .runA(0L)
      .value
      .iterator
      .mkString

  /** The program in the textual syntax. */
  def program(p: Program): String = program(p, Naming.Depth)

  /** The program in the textual syntax, its binders named by `naming`. */
  def program(p: Program, naming: Naming): String =
    s"(program ${p.version.show} ${render(Seed.OfTerm(p.term, Vector.empty), naming)})"

  /** A closed term in the textual syntax. */
  def term(t: Term): String = term(t, Naming.Depth)

  /** A term in the textual syntax, its binders named by `naming`. */
  def term(t: Term, naming: Naming): String = render(Seed.OfTerm(t, Vector.empty), naming)

  /** A constant as it is written after `con`: its type and its value. */
  def constant(c: Con): String =
    val types = render(Seed.OfType(c.typeOf), Naming.Depth)
    s"$types ${render(Seed.OfValue(c, parens = true), Naming.Depth)}"
