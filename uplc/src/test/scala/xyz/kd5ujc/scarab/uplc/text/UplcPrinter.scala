// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import cats.{Applicative, Eval, Traverse}
import cats.data.Chain
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
  * A `lam` at depth `d` (the number of `lam` s around it) binds the name `x-d`: text `x`, unique
  * `d`. A variable of index `i` at depth `d` is then `x-(d - i)`; a free index gets a negative
  * unique, written as the unsigned decimal that the parser wraps back to it.
  *
  * Printing is an unfold of the program into nested groups of text, folded into a [[Chain]] of
  * pieces ([[Schemes.hylo]]): a deep term or constant is printed in constant stack.
  */
object UplcPrinter:

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
    case OfTerm(term: Term, depth: Int)
    case OfType(t: DefaultUni)
    case OfValue(c: Con, parens: Boolean)
    case OfData(d: PlutusData)
    case OfEntry(key: PlutusData, value: PlutusData)
    case Verbatim(text: String)

  import Layout.{Atom, Group}
  import Seed.*

  private def name(level: Long): String = s"x-${java.lang.Long.toUnsignedString(level)}"

  private def unfold(seed: Seed): Layout[Seed] = seed match
    case Verbatim(text)    => Atom(text)
    case OfTerm(t, depth)  => termLayout(t, depth)
    case OfType(t)         => typeOf(t)
    case OfValue(c, paren) => value(c, paren)
    case OfData(d)         => data(d)
    case OfEntry(k, v)     => Group("(", Vector(OfData(k), OfData(v)), ", ", ")")

  private def termLayout(t: Term, depth: Int): Layout[Seed] = t.unfix match
    case TermF.Var(i) => Atom(name(depth.toLong - i.index.bits))
    case TermF.LamAbs(body) =>
      Group("(lam ", Vector(Verbatim(name(depth.toLong)), OfTerm(body, depth + 1)), " ", ")")
    case TermF.Apply(f, a) => Group("[", Vector(OfTerm(f, depth), OfTerm(a, depth)), " ", "]")
    case TermF.Force(x)    => Group("(force ", Vector(OfTerm(x, depth)), "", ")")
    case TermF.Delay(x)    => Group("(delay ", Vector(OfTerm(x, depth)), "", ")")
    case TermF.Constant(c) =>
      Group("(con ", Vector(OfType(c.typeOf), OfValue(c, parens = true)), " ", ")")
    case TermF.Builtin(f) => Atom(s"(builtin ${f.name})")
    case TermF.Error      => Atom("(error)")
    case TermF.Constr(tag, xs) =>
      Group("(constr ", Verbatim(tag.toBigInt.toString) +: xs.map(OfTerm(_, depth)), " ", ")")
    case TermF.Case(s, bs) =>
      Group("(case ", (s +: bs).map(OfTerm(_, depth)), " ", ")")

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

  private def render(seed: Seed): String =
    Schemes.hylo[Layout, Seed, Chain[String]](seed)(unfold, fold).iterator.mkString

  /** The program in the textual syntax. */
  def program(p: Program): String =
    s"(program ${p.version.show} ${render(Seed.OfTerm(p.term, 0))})"

  /** A closed term in the textual syntax. */
  def term(t: Term): String = render(Seed.OfTerm(t, 0))

  /** A constant as it is written after `con`: its type and its value. */
  def constant(c: Con): String =
    s"${render(Seed.OfType(c.typeOf))} ${render(Seed.OfValue(c, parens = true))}"
