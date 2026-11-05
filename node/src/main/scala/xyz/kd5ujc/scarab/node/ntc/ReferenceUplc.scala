// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Eval
import cats.syntax.all.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{HaskellShow, HaskellUnicode}
import xyz.kd5ujc.scarab.plutus.{PlutusData, ReferenceDiagnostic}

/** Plutus 507cab8c's classic and readable UPLC instances and PrettyConst (1.70.0.0). The readable
  * fixities follow prettyprinter-configurable 1.36.0.0 Text/Fixity.hs and Text/PrettyBy/Fixity.hs;
  * rendering never delegates to the provider's own pretty-printer.
  */
private[ntc] object ReferenceUplc:
  import ReferenceDiagnostic.{Constant, Term, Universe}
  import ReferenceDocument.{Doc, *}

  private enum Associativity:
    case Left, Right, None
  private enum Side:
    case Left, Right
  private final case class Context(precedence: Int, associativity: Associativity, side: Side)
  private val bottom = Context(-20, Associativity.None, Side.Right)
  private val function = Context(100, Associativity.Left, Side.Left)
  private val argument = Context(100, Associativity.Left, Side.Right)
  private val binderBody = Context(1, Associativity.Right, Side.Right)

  private def enclose(context: Context, precedence: Int, associativity: Associativity)(
      doc: Doc
  ): Doc =
    val sameAssociativity = context.associativity == associativity
    val adjacent = (associativity, context.side) match
      case (Associativity.Left, Side.Left) | (Associativity.Right, Side.Right) => true
      case _                                                                   => false
    if context.precedence > precedence ||
      (context.precedence == precedence && !(sameAssociativity && adjacent))
    then parens(doc)
    else doc

  private def application(context: Context, head: Doc, arguments: Vector[Doc]): Doc =
    val body =
      if arguments.isEmpty then head
      else sep(Vector(head, vsep(arguments))).hang(2)
    enclose(context, 100, Associativity.Left)(body)

  private def section(open: Doc, close: Doc, body: Doc): Doc =
    ((open ++ body).nest(2) ++ close).group

  private def sexp(keyword: String, arguments: Vector[Doc]): Doc =
    section(
      text("(" + keyword) ++ (if arguments.isEmpty then lineBreak else line),
      lineBreak ++ text(")"),
      sep(arguments)
    )

  private def named(name: String, index: BigInt): Doc =
    def initial(c: Int): Boolean =
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_'
    val points = name.codePoints.toArray.toVector
    val valid = points.headOption.exists(initial) &&
      points.drop(1).forall(c => initial(c) || (c >= '0' && c <= '9') || c == '\'')
    text((if valid then name else "`" + name + "`") + "!" + index)

  private def builtin(function: scalus.uplc.DefaultFun): Doc =
    val name = function.toString
    text(name.headOption.fold(name)(first => first.toLower.toString + name.drop(1)))

  def universe(value: Universe): Doc = universeAt(value, bottom).value

  def universeArgument(value: Universe): Doc = universeAt(value, argument).value

  private def universeAt(value: Universe, context: Context): Eval[Doc] = Eval.defer {
    value match
      case Universe.Integer     => Eval.now(text("integer"))
      case Universe.ByteString  => Eval.now(text("bytestring"))
      case Universe.Text        => Eval.now(text("string"))
      case Universe.Unit        => Eval.now(text("unit"))
      case Universe.Bool        => Eval.now(text("bool"))
      case Universe.ProtoArray  => Eval.now(text("array"))
      case Universe.ProtoList   => Eval.now(text("list"))
      case Universe.ProtoPair   => Eval.now(text("pair"))
      case Universe.Data        => Eval.now(text("data"))
      case Universe.BlsG1       => Eval.now(text("bls12_381_G1_element"))
      case Universe.BlsG2       => Eval.now(text("bls12_381_G2_element"))
      case Universe.BlsMlResult => Eval.now(text("bls12_381_mlresult"))
      case Universe.Value       => Eval.now(text("value"))
      case Universe.Apply(fun, arg) =>
        universeAt(fun, function).flatMap { left =>
          universeAt(arg, argument).map { right =>
            enclose(context, 100, Associativity.Left)(left.space(right))
          }
        }
  }

  private def typeOf(value: Constant): Eval[Universe] = Eval.defer {
    value match
      case Constant.Integer(_)        => Eval.now(Universe.Integer)
      case Constant.ByteString(_)     => Eval.now(Universe.ByteString)
      case Constant.Text(_)           => Eval.now(Universe.Text)
      case Constant.UnitValue         => Eval.now(Universe.Unit)
      case Constant.Bool(_)           => Eval.now(Universe.Bool)
      case Constant.Data(_)           => Eval.now(Universe.Data)
      case Constant.List(element, _)  => Eval.now(Universe.Apply(Universe.ProtoList, element))
      case Constant.Array(element, _) => Eval.now(Universe.Apply(Universe.ProtoArray, element))
      case Constant.Pair(left, right) =>
        typeOf(left).flatMap(a =>
          typeOf(right).map(b => Universe.Apply(Universe.Apply(Universe.ProtoPair, a), b))
        )
      case Constant.BlsG1(_)    => Eval.now(Universe.BlsG1)
      case Constant.BlsG2(_)    => Eval.now(Universe.BlsG2)
      case Constant.BlsMlResult => Eval.now(Universe.BlsMlResult)
      case Constant.Value(_)    => Eval.now(Universe.Value)
  }

  /** PrettyConst preserves printable Unicode, while the shared GHC literal primitive supplies
    * nonprintable escapes and their decimal/SO terminators. PrettyConst.hs:123-137.
    */
  private def quoted(value: String): Doc =
    val points = value.codePoints.toArray.toVector
    val escaped = points.indices.map { index =>
      val point = points(index)
      if point == '"' then "\\\""
      else if point == '\\' then "\\\\"
      else if HaskellUnicode.isPrint(point) then new String(Character.toChars(point))
      else HaskellShow.literalChar(point, points.lift(index + 1).getOrElse(-1))
    }.mkString
    text("\"" + escaped + "\"")

  def constant(value: Constant): Doc = constantAt(value, bottom).value

  def constantArgument(value: Constant): Doc = constantAt(value, argument).value

  private def constantAt(value: Constant, context: Context): Eval[Doc] = Eval.defer {
    value match
      case Constant.Integer(number)   => Eval.now(text(number.toString))
      case Constant.ByteString(bytes) => Eval.now(text("#" + bytes.hex))
      case Constant.Text(value)       => Eval.now(quoted(value))
      case Constant.UnitValue         => Eval.now(text("()"))
      case Constant.Bool(value)       => Eval.now(text(if value then "True" else "False"))
      case Constant.Data(data)        => dataAt(data, context)
      case Constant.List(element, values) =>
        values.traverse(constantAt(_, bottom)).map(constantList(element, _))
      case Constant.Array(element, values) =>
        values.traverse(constantAt(_, bottom)).map(constantList(element, _))
      case Constant.Pair(left, right) =>
        constantAt(left, bottom).flatMap(a =>
          constantAt(right, bottom).map(b => tuple(Vector(a, b)))
        )
      case Constant.BlsG1(bytes) => Eval.now(text("0x" + bytes.hex))
      case Constant.BlsG2(bytes) => Eval.now(text("0x" + bytes.hex))
      case Constant.BlsMlResult  => Eval.now(text("<opaque>"))
      case Constant.Value(entries) =>
        entries
          .traverse { (policy, tokens) =>
            tokens
              .traverse { (name, amount) =>
                Eval.now(tuple(Vector(text("#" + name.hex), text(amount.toString))))
              }
              .map(tokens => tuple(Vector(text("#" + policy.hex), list(tokens))))
          }
          .map(list)
  }

  /** PrettyAny's list override renders the entire list through Show, without layout choices.
    * PrettyConst.hs:99-105,123-125,132-139; compound element types retain Prettyprinter.list.
    */
  private def constantList(element: Universe, values: Vector[Doc]): Doc = element match
    case Universe.Integer | Universe.Unit | Universe.Bool =>
      text("[") ++ join(text(","), values) ++ text("]")
    case Universe.ByteString | Universe.Text | Universe.ProtoArray | Universe.ProtoList |
        Universe.ProtoPair | Universe.Apply(_, _) | Universe.Data | Universe.BlsG1 |
        Universe.BlsG2 | Universe.BlsMlResult | Universe.Value =>
      list(values)

  private def dataAt(value: PlutusData, context: Context): Eval[Doc] = Eval.defer {
    value match
      case PlutusData.I(number) =>
        Eval.now(application(context, text("I").space(text(number.toString)), Vector.empty))
      case PlutusData.B(bytes) =>
        Eval.now(application(context, text("B").space(text("#" + bytes.hex)), Vector.empty))
      case PlutusData.Constr(index, fields) =>
        fields.traverse(dataAt(_, bottom)).map { docs =>
          application(context, text("Constr").space(text(index.toString)), Vector(list(docs)))
        }
      case PlutusData.List(values) =>
        values
          .traverse(dataAt(_, bottom))
          .map(docs => application(context, text("List"), Vector(list(docs))))
      case PlutusData.Map(values) =>
        values
          .traverse { (key, value) =>
            dataAt(key, bottom).flatMap(a => dataAt(value, bottom).map(b => tuple(Vector(a, b))))
          }
          .map(docs => application(context, text("Map"), Vector(list(docs))))
  }

  /** UPLC Core/Instance/Pretty/Classic.hs:23-80, annotations disabled as in the reference. */
  def classic(value: Term): Doc = classicTerm(value).value

  private def classicTerm(value: Term): Eval[Doc] = Eval.defer {
    value match
      case Term.Var(name, index) => Eval.now(named(name, index))
      case Term.Lam(name, index, body) =>
        classicTerm(body).map(doc => sexp("lam", Vector(named(name, index), doc)))
      case Term.Apply(fun, arg) =>
        classicTerm(fun).flatMap { left =>
          classicTerm(arg).map(right =>
            section(text("[") ++ line, line ++ text("]"), sep(Vector(left, right)))
          )
        }
      case Term.Delay(body) => classicTerm(body).map(doc => sexp("delay", Vector(doc)))
      case Term.Force(body) => classicTerm(body).map(doc => sexp("force", Vector(doc)))
      case Term.Constant(value) =>
        typeOf(value).flatMap(universeAt(_, argument)).flatMap { kind =>
          constantAt(value, argument).map(doc => sexp("con", Vector(kind, doc)))
        }
      case Term.Builtin(function) => Eval.now(sexp("builtin", Vector(builtin(function))))
      case Term.Error             => Eval.now(sexp("error", Vector.empty))
      case Term.Constr(index, fields) =>
        fields.traverse(classicTerm).map(docs => sexp("constr", text(index.toString) +: docs))
      case Term.Case(scrutinee, branches) =>
        classicTerm(scrutinee).flatMap { argument =>
          branches.traverse(classicTerm).map(docs => sexp("case", argument +: docs))
        }
  }

  /** UPLC Core/Instance/Pretty/Readable.hs:40-69 and Pretty/Readable.hs:155-235. */
  def readable(value: Term): Doc = readableTerm(value, bottom).value

  @tailrec private def lambdas(value: Term, names: Vector[Doc]): (Vector[Doc], Term) = value match
    case Term.Lam(name, index, body) => lambdas(body, names :+ named(name, index))
    case other                       => (names, other)

  @tailrec private def applications(value: Term, arguments: List[Term]): (Term, Vector[Term]) =
    value match
      case Term.Apply(fun, arg) => applications(fun, arg :: arguments)
      case other                => (other, arguments.toVector)

  private def readableTerm(value: Term, context: Context): Eval[Doc] = Eval.defer {
    value match
      case Term.Var(name, index) => Eval.now(named(name, index))
      case Term.Constant(value)  => constantAt(value, context)
      case Term.Builtin(fun)     => Eval.now(builtin(fun))
      case Term.Error            => Eval.now(text("error"))
      case _: Term.Lam =>
        val (names, body) = lambdas(value, Vector.empty)
        readableTerm(body, binderBody).map { rendered =>
          val binds = sep(names).align
          val binder = (text("\\") ++ binds).space(text("->"))
          enclose(context, 1, Associativity.Right)(sep(Vector(binder, rendered)).hang(2))
        }
      case _: Term.Apply =>
        val (fun, args) = applications(value, Nil)
        readableTerm(fun, function).flatMap { head =>
          args.traverse(readableTerm(_, argument)).map(docs => application(context, head, docs))
        }
      case Term.Delay(body) =>
        readableTerm(body, argument).map(doc => application(context, text("delay"), Vector(doc)))
      case Term.Force(body) =>
        readableTerm(body, argument).map(doc => application(context, text("force"), Vector(doc)))
      case Term.Constr(index, fields) =>
        fields.traverse(readableTerm(_, argument)).map { docs =>
          application(context, text("constr").space(text(index.toString)), Vector(list(docs)))
        }
      case Term.Case(scrutinee, branches) =>
        readableTerm(scrutinee, argument).flatMap { scrut =>
          branches.traverse(readableTerm(_, argument)).map { docs =>
            application(context, text("case"), Vector(scrut, list(docs)))
          }
        }
  }
