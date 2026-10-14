// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import xyz.kd5ujc.scarab.models.gen.Ast.{Assign, Occ}

/** Closed error type of the generator. Rendering to text happens at the boundary (`Main`). */
enum GenError:
  case Parse(file: String, failure: CddlParser.ParseFailure)
  case Io(path: String, cause: String)
  case UnknownModule(name: String, referencedFrom: String)
  case UnresolvedName(scope: String, name: String)
  case ArityMismatch(rule: String, expected: Int, actual: Int)
  case Unsupported(rule: String, feature: String)
  case NameClash(scope: String, scalaName: String, rules: Vector[String])
  case BadConfig(line: Int, detail: String)

  def message: String = this match
    case Parse(f, e)            => s"$f: ${e.getMessage}"
    case Io(p, c)               => s"$p: $c"
    case UnknownModule(m, from) => s"unknown module `$m` (referenced from $from)"
    case UnresolvedName(s, n)   => s"unresolved name `$n` in $s"
    case ArityMismatch(r, e, a) => s"rule `$r` takes $e type arguments, got $a"
    case Unsupported(r, f)      => s"unsupported CDDL feature in `$r`: $f"
    case NameClash(s, n, rs) =>
      s"in $s the rules ${rs.mkString(", ")} all map to the Scala name `$n`"
    case BadConfig(l, m) => s"config line $l: $m"

final case class GenException(error: GenError) extends RuntimeException(error.message)

/** A rule, qualified by the module it lives in (`conway:header_body`). */
final case class QName(module: String, name: String):
  override def toString: String = s"$module:$name"

enum PrimKind:
  case Uint, Nint, Int, Bytes, Text, Bool, Nil, Any, Float

/** A resolved type: names are qualified, generics are expanded, groups spliced into entries. */
enum RT:
  case Prim(kind: PrimKind)
  case LitInt(value: BigInt)
  case LitText(value: String)
  case LitBool(value: Boolean)
  case LitBytes(value: Vector[Byte])

  /** An integer in `lo..hi` (inclusive); from `uint .size n` or a `lo..hi` range. */
  case IntRange(lo: BigInt, hi: BigInt)
  case SizedBytes(lo: Int, hi: Int)
  case SizedText(lo: Int, hi: Int)
  case Ref(target: QName)
  case Choice(alts: Vector[RT])
  case Arr(entries: Vector[REntry])
  case MapT(entries: Vector[REntry])
  case Tagged(tag: BigInt, inner: RT)

  /** `bytes .cbor inner`: a byte string holding the encoding of `inner`. */
  case CborOf(inner: RT)

  /** A reference to a group rule, spliced into the enclosing array or map when encoded. */
  case GroupRef(target: QName)
  case Unsupported(why: String)

enum RKey:
  case IntKey(value: BigInt)
  case TextKey(value: String)
  case OfType(ty: RT, cut: Boolean)

/** An array or map entry. `label` is the CDDL member name (a comment in arrays, the key in maps).
  */
final case class REntry(occ: Occ, label: Option[String], key: Option[RKey], value: RT)

enum RuleBody:
  case Type(ty: RT)
  case GroupBody(entries: Vector[REntry])

final case class RuleDef(name: QName, body: RuleBody)

/** The rules of one module: the files configured for it, later files overriding earlier ones. */
final case class Module(name: String, rules: Map[String, (Vector[Ast.Rule], Scope)])

/** Name resolution scope: the module of the defining file and its `;# import … as alias` table. */
final case class Scope(module: String, aliases: Map[String, String])

final class Schema(val modules: Map[String, Module]):
  private val cache = scala.collection.mutable.Map.empty[QName, RuleDef]

  def modulesNamed: Set[String] = modules.keySet

  def lookup(q: QName): Option[(Vector[Ast.Rule], Scope)] =
    modules.get(q.module).flatMap(_.rules.get(q.name))

  def rule(q: QName): RuleDef =
    cache.getOrElseUpdate(q, resolveRule(q))

  private def fail(e: GenError): Nothing = throw GenException(e)

  private def resolveRule(q: QName): RuleDef =
    val (defs, scope) = lookup(q).getOrElse(fail(GenError.UnresolvedName(q.module, q.name)))
    if defs.head.params.nonEmpty then
      fail(GenError.Unsupported(q.toString, "generic rule used without type arguments"))
    val isGroup =
      defs.exists(r => r.assign == Assign.GroupChoice || r.body.isInstanceOf[Ast.Ty.GroupT])
    if isGroup then
      val alts = defs.flatMap { r =>
        r.body match
          case Ast.Ty.GroupT(g) => g.choices
          case other            => Vector(Vector(Ast.Entry(Occ.Once, None, other)))
      }
      if alts.size != 1 then
        fail(GenError.Unsupported(q.toString, "group rule with `//` alternatives"))
      RuleDef(
        q,
        RuleBody.GroupBody(entries(alts.head, scope, Map.empty, q.toString, inMap = false))
      )
    else
      val tys = defs.map(r => ty(r.body, scope, Map.empty, q.toString))
      RuleDef(q, RuleBody.Type(if tys.size == 1 then tys.head else flattenChoice(tys)))

  private def flattenChoice(alts: Vector[RT]): RT =
    val flat = alts.flatMap {
      case RT.Choice(xs) => xs
      case other         => Vector(other)
    }
    if flat.size == 1 then flat.head else RT.Choice(flat)

  private val prelude: Map[String, RT] = Map(
    "uint" -> RT.Prim(PrimKind.Uint),
    "nint" -> RT.Prim(PrimKind.Nint),
    "int" -> RT.Prim(PrimKind.Int),
    "bytes" -> RT.Prim(PrimKind.Bytes),
    "bstr" -> RT.Prim(PrimKind.Bytes),
    "text" -> RT.Prim(PrimKind.Text),
    "tstr" -> RT.Prim(PrimKind.Text),
    "bool" -> RT.Prim(PrimKind.Bool),
    "true" -> RT.LitBool(true),
    "false" -> RT.LitBool(false),
    "nil" -> RT.Prim(PrimKind.Nil),
    "null" -> RT.Prim(PrimKind.Nil),
    "any" -> RT.Prim(PrimKind.Any),
    "float" -> RT.Prim(PrimKind.Float),
    "float16" -> RT.Prim(PrimKind.Float),
    "float32" -> RT.Prim(PrimKind.Float),
    "float64" -> RT.Prim(PrimKind.Float)
  )

  /** Find the rule an identifier names from `scope`: a local rule, or `alias.rule` through an
    * import.
    */
  private def locate(id: String, scope: Scope): Option[QName] =
    val local = QName(scope.module, id)
    if lookup(local).isDefined then Some(local)
    else
      val dot = id.indexOf('.')
      if dot < 0 then None
      else
        scope.aliases.get(id.substring(0, dot)).flatMap { m =>
          val q = QName(m, id.substring(dot + 1))
          if lookup(q).isDefined then Some(q)
          else if modules.contains(m) then fail(GenError.UnresolvedName(m, q.name))
          else fail(GenError.UnknownModule(m, scope.module))
        }

  /** An integer bound: a literal, or a name of a rule that is one (`max_word64 =
    * 18446744073709551615`).
    */
  private def intOf(t: Ast.Ty, scope: Scope, env: Map[String, RT], ctx: String): BigInt = t match
    case Ast.Ty.Literal(Ast.Lit.IntL(n)) => n
    case Ast.Ty.Name(id, Vector()) if !env.contains(id) =>
      locate(id, scope).flatMap(q => lookup(q).map(q -> _)) match
        case Some((_, (Vector(r), defScope))) if r.params.isEmpty =>
          intOf(r.body, defScope, Map.empty, ctx)
        case _ => fail(GenError.Unsupported(ctx, s"non-literal bound $id"))
    case _ => fail(GenError.Unsupported(ctx, s"non-literal bound $t"))

  def ty(t: Ast.Ty, scope: Scope, env: Map[String, RT], ctx: String): RT = t match
    case Ast.Ty.Literal(l) =>
      l match
        case Ast.Lit.IntL(n)    => RT.LitInt(n)
        case Ast.Lit.TextL(s)   => RT.LitText(s)
        case Ast.Lit.BytesL(bs) => RT.LitBytes(bs)
        case Ast.Lit.FloatL(s)  => RT.Unsupported(s"float literal $s")
    case Ast.Ty.Name(id, args) =>
      env.get(id) match
        case Some(bound) => bound
        case None =>
          locate(id, scope) match
            case Some(q) =>
              val (defs, defScope) = lookup(q).get
              val params = defs.head.params
              if params.isEmpty then
                if args.nonEmpty then fail(GenError.ArityMismatch(q.toString, 0, args.size))
                RT.Ref(q)
              else
                if params.size != args.size then
                  fail(GenError.ArityMismatch(q.toString, params.size, args.size))
                val bound = params.zip(args.map(ty(_, scope, env, ctx))).toMap
                val tys = defs.map(r => this.ty(r.body, defScope, bound, q.toString))
                flattenChoice(tys)
            case None =>
              prelude.getOrElse(id, fail(GenError.UnresolvedName(scope.module, id)))
    case Ast.Ty.Range(lo, hi, inclusive) =>
      val a = intOf(lo, scope, env, ctx)
      val b = intOf(hi, scope, env, ctx)
      RT.IntRange(a, if inclusive then b else b - 1)
    case Ast.Ty.Ctrl(base, op, arg) => control(base, op, arg, scope, env, ctx)
    case Ast.Ty.Choice(alts)        => flattenChoice(alts.map(ty(_, scope, env, ctx)))
    case Ast.Ty.Array(g)            => composite(g, scope, env, ctx, inMap = false)
    case Ast.Ty.MapT(g)             => composite(g, scope, env, ctx, inMap = true)
    case Ast.Ty.Tag(major, number, inner) =>
      (major, number, inner) match
        case (6, Some(n), Some(i)) => RT.Tagged(n, ty(i, scope, env, ctx))
        case (6, Some(n), None)    => RT.Tagged(n, RT.Prim(PrimKind.Any))
        case (-1, None, None)      => RT.Prim(PrimKind.Any)
        case _                     => RT.Unsupported(s"tag form #$major")
    case Ast.Ty.GroupT(_) => RT.Unsupported("group in type position")
    case Ast.Ty.Enumeration(of) =>
      of match
        case Ast.Ty.GroupT(g) if g.choices.size == 1 =>
          RT.Choice(g.choices.head.map(e => ty(e.value, scope, env, ctx)))
        case other => RT.Unsupported(s"enumeration of $other")
    case Ast.Ty.Unwrap(_) => RT.Unsupported("unwrap (~)")

  private def control(
      base: Ast.Ty,
      op: String,
      arg: Ast.Ty,
      scope: Scope,
      env: Map[String, RT],
      ctx: String
  ): RT =
    val b = ty(base, scope, env, ctx)
    (b, op) match
      case (RT.Prim(PrimKind.Uint), "size") =>
        arg match
          case Ast.Ty.Literal(Ast.Lit.IntL(n)) => RT.IntRange(0, (BigInt(1) << (8 * n.toInt)) - 1)
          case _                               => RT.Unsupported("uint .size with a range")
      case (RT.Prim(PrimKind.Bytes), "size") =>
        sizes(arg, ctx).fold(RT.Unsupported("bad .size"))(RT.SizedBytes(_, _))
      case (RT.Prim(PrimKind.Text), "size") =>
        sizes(arg, ctx).fold(RT.Unsupported("bad .size"))(RT.SizedText(_, _))
      case (RT.Prim(PrimKind.Bytes), "cbor") => RT.CborOf(ty(arg, scope, env, ctx))
      case (RT.Prim(PrimKind.Uint), "lt")    => RT.IntRange(0, intOf(arg, scope, env, ctx) - 1)
      case (RT.Prim(PrimKind.Uint), "le")    => RT.IntRange(0, intOf(arg, scope, env, ctx))
      case (_, "default")                    => b
      case _                                 => RT.Unsupported(s".$op")

  private def sizes(arg: Ast.Ty, ctx: String): Option[(Int, Int)] = arg match
    case Ast.Ty.Literal(Ast.Lit.IntL(n)) => Some((n.toInt, n.toInt))
    case Ast.Ty.Range(Ast.Ty.Literal(Ast.Lit.IntL(a)), Ast.Ty.Literal(Ast.Lit.IntL(b)), inc) =>
      Some((a.toInt, if inc then b.toInt else b.toInt - 1))
    case _ => None

  private def composite(
      g: Ast.Group,
      scope: Scope,
      env: Map[String, RT],
      ctx: String,
      inMap: Boolean
  ): RT =
    val alts = g.choices.map { es =>
      val xs = entries(es, scope, env, ctx, inMap)
      if inMap then RT.MapT(xs) else RT.Arr(xs)
    }
    if alts.size == 1 then alts.head else RT.Choice(alts)

  private def entries(
      es: Vector[Ast.Entry],
      scope: Scope,
      env: Map[String, RT],
      ctx: String,
      inMap: Boolean
  ): Vector[REntry] =
    es.flatMap { e =>
      e.value match
        case Ast.Ty.GroupT(g) if g.choices.size == 1 && e.key.isEmpty =>
          entries(g.choices.head, scope, env, ctx, inMap)
        case Ast.Ty.Name(id, Vector())
            if e.key.isEmpty && env.get(id).isEmpty && isGroupRule(id, scope) =>
          Vector(REntry(e.occ, None, None, RT.GroupRef(locate(id, scope).get)))
        case value =>
          val v = ty(value, scope, env, ctx)
          val (label, key) = e.key match
            case None => (None, None)
            case Some(Ast.Key.Bare(n)) =>
              if inMap then (Some(n), Some(RKey.TextKey(n))) else (Some(n), None)
            case Some(Ast.Key.Value(Ast.Lit.IntL(n))) =>
              (None, if inMap then Some(RKey.IntKey(n)) else None)
            case Some(Ast.Key.Value(Ast.Lit.TextL(s))) =>
              (None, if inMap then Some(RKey.TextKey(s)) else None)
            case Some(Ast.Key.Value(_)) =>
              fail(GenError.Unsupported(ctx, "non-integer, non-text literal key"))
            case Some(Ast.Key.OfType(kt, cut)) =>
              (None, if inMap then Some(RKey.OfType(ty(kt, scope, env, ctx), cut)) else None)
          Vector(REntry(e.occ, label, key, v))
    }

  private def isGroupRule(id: String, scope: Scope): Boolean =
    locate(id, scope).exists { q =>
      val (defs, _) = lookup(q).get
      defs.exists(r => r.assign == Assign.GroupChoice || r.body.isInstanceOf[Ast.Ty.GroupT])
    }

object Schema:
  /** Build a schema from parsed files: module name -> (files in override order, with their
    * pragmas).
    */
  def build(modules: Vector[(String, Vector[Ast.File])]): Schema =
    val known = modules.map(_._1).toSet
    val built = modules.map { (name, files) =>
      val rules = scala.collection.mutable.LinkedHashMap.empty[String, (Vector[Ast.Rule], Scope)]
      files.foreach { f =>
        val aliases = f.pragmas.map(p => p.alias -> p.module).toMap
        val scope = Scope(name, aliases)
        // Within one file, `=` starts a definition and `/=`, `//=` extend it; a later file replaces it.
        val perFile = scala.collection.mutable.LinkedHashMap.empty[String, Vector[Ast.Rule]]
        f.rules.foreach { r =>
          val prior = perFile.getOrElse(r.name, Vector.empty)
          perFile(r.name) = if r.assign == Assign.Eq then Vector(r) else prior :+ r
        }
        perFile.foreach { (n, rs) =>
          val extending = rs.forall(_.assign != Assign.Eq)
          val merged =
            if extending then rules.get(n).map(_._1).getOrElse(Vector.empty) ++ rs else rs
          rules(n) = (merged, scope)
        }
      }
      name -> Module(name, rules.toMap)
    }
    val _ = known
    new Schema(built.toMap)
