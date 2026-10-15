// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import scala.annotation.unused

import scala.collection.mutable

/** Turns a resolved [[Schema]] into Scala 3 source: one file per CDDL module.
  *
  * Every named rule becomes a type with a companion that is an `rt.Codec` (the hand-written
  * runtime):
  *   - constrained leaves (`bytes .size 32`, `uint .size 8`, `0..12`, `14 / 15`) become opaque
  *     types;
  *   - arrays and maps become case classes (literal members are checked, not stored);
  *   - choices become enums, decoded by ordered trial and reported with all causes;
  *   - group rules become case classes spliced into their parent array;
  *   - wrappers (`#6.24`, `.cbor`, `[* x]`, `x / nil`) become aliases of the wrapped type.
  * Rules listed under `preserve` carry the exact bytes they were decoded from (`raw`).
  */
final class Emitter(schema: Schema, cfg: Config):
  import Emitter.*

  private def fail(e: GenError): Nothing = throw GenException(e)
  private val specs: Map[String, Config.ModuleSpec] = cfg.modules.map(m => m.name -> m).toMap
  private def packageOf(module: String): String = s"${cfg.basePackage}.${specs(module).subpackage}"
  private def lastSegment(module: String): String = specs(module).subpackage.split('.').last

  // Reachability and reference counts (decides which single-use rules are folded into their enum) -----
  private val reached = mutable.LinkedHashSet.empty[QName]
  private val totalRefs = mutable.Map.empty[QName, Int].withDefaultValue(0)
  private val altRefs = mutable.Map.empty[QName, Int].withDefaultValue(0)

  private def walk(q: QName): Unit =
    if reached.add(q) && !cfg.external(q) then
      schema.rule(q).body match
        case RuleBody.Type(t)       => visit(t)
        case RuleBody.GroupBody(es) => es.foreach(visitEntry)

  private def visitEntry(e: REntry): Unit =
    e.key.foreach {
      case RKey.OfType(t, _) => visit(t)
      case _                 => ()
    }
    visit(e.value)

  private def visit(t: RT): Unit = t match
    case RT.Ref(q)      => totalRefs(q) += 1; walk(q)
    case RT.GroupRef(q) => totalRefs(q) += 1; walk(q)
    case RT.Choice(alts) =>
      val enumLike = isPlainChoice(alts)
      alts.foreach {
        case RT.Ref(q) if enumLike => altRefs(q) += 1; visit(RT.Ref(q))
        case other                 => visit(other)
      }
    case RT.Arr(es)      => es.foreach(visitEntry)
    case RT.MapT(es)     => es.foreach(visitEntry)
    case RT.Tagged(_, i) => visit(i)
    case RT.CborOf(i)    => visit(i)
    case _               => ()

  private def isVector(es: Vector[REntry]): Boolean =
    es.size == 1 && es.head.key.isEmpty && !es.head.value.isInstanceOf[RT.GroupRef] &&
      (es.head.occ.max.forall(_ > 1) || es.head.occ.min > 1)

  private def isPairs(es: Vector[REntry]): Boolean =
    es.size == 1 && es.head.occ.max.isEmpty && (es.head.key match
      case Some(RKey.OfType(_, _)) => true
      case _                       => false)

  private def isStructBody(q: QName): Boolean = schema.rule(q).body match
    case RuleBody.Type(RT.Arr(es))  => !isVector(es)
    case RuleBody.Type(RT.MapT(es)) => !isPairs(es)
    case _                          => false

  private def inlined(q: QName): Boolean =
    !cfg.roots.contains(q) && totalRefs(q) == 1 && altRefs(q) == 1 && !cfg.preserve(q) &&
      !cfg.rename.contains(q) && !cfg.external(q) && isStructBody(q)

  // Codec and type expressions of inline types ----------------------------------------------------

  /** Scala names the generated code itself uses; a rule called `big_int` must not shadow `BigInt`.
    */
  private val reservedTypes =
    Set(
      "BigInt",
      "Vector",
      "Option",
      "Some",
      "None",
      "Int",
      "Long",
      "Boolean",
      "String",
      "Either",
      "Left",
      "Right",
      "Unit",
      "Array",
      "Map",
      "List",
      "Set",
      "Any",
      "Nothing"
    )

  def scalaName(q: QName): String =
    cfg.rename.getOrElse(
      q,
      pascal(q.name) match
        case n if reservedTypes(n) => n + "Type"
        case n                     => n
    )

  private def refName(q: QName, o: Owner): String =
    val n = scalaName(q)
    if q.module == o.module then n
    else
      o.imports += q.module
      s"${lastSegment(q.module)}.$n"

  private def intRepr(lo: BigInt, hi: BigInt): (String, String) =
    if lo.isValidInt && hi.isValidInt then ("Int", s"rt.Codec.intRange($lo, $hi)")
    else if lo.isValidLong && hi.isValidLong then
      (
        "Long",
        if lo.signum == 0 then s"rt.Codec.uintLong(${hi}L)"
        else s"rt.Codec.longRange(${lo}L, ${hi}L)"
      )
    else if lo.signum == 0 then ("BigInt", s"rt.Codec.uintBig(BigInt(\"$hi\"))")
    else ("BigInt", s"rt.Codec.bigRange(BigInt(\"$lo\"), BigInt(\"$hi\"))")

  private def simple(codec: String): Boolean = codec.matches("[A-Za-z0-9_.]+")

  private def inlineCodec(tpe: String, expr: String, o: Owner): Tc =
    Tc(tpe, if simple(expr) then expr else o.hoist(s"rt.Codec[$tpe]", expr))

  private val nil: RT = RT.Prim(PrimKind.Nil)

  private def intLits(alts: Vector[RT]): Option[Vector[BigInt]] =
    val xs = alts.collect { case RT.LitInt(n) if n.isValidInt => n }
    Option.when(xs.size == alts.size && alts.nonEmpty)(xs)

  /** `#6.n(x) / x`: the tag is optional on input (Conway sets), written on output. */
  private def maybeTagged(alts: Vector[RT]): Option[(BigInt, RT)] = alts match
    case Vector(RT.Tagged(n, a), b) if a == b => Some((n, a))
    case Vector(b, RT.Tagged(n, a)) if a == b => Some((n, a))
    case _                                    => None

  private def tc(t: RT, hint: String, o: Owner, ctx: String): Tc = t match
    case RT.LitInt(n) if !n.isValidInt                  => tc(RT.IntRange(n, n), hint, o, ctx)
    case RT.Prim(PrimKind.Uint)                         => Tc("BigInt", "rt.Codec.uint")
    case RT.Prim(PrimKind.Int) | RT.Prim(PrimKind.Nint) => Tc("BigInt", "rt.Codec.int")
    case RT.Prim(PrimKind.Bytes)                        => Tc("rt.Bytes", "rt.Codec.bytes")
    case RT.Prim(PrimKind.Text)                         => Tc("String", "rt.Codec.text")
    case RT.Prim(PrimKind.Bool)                         => Tc("Boolean", "rt.Codec.bool")
    case RT.Prim(PrimKind.Any)                          => Tc("rt.Node", "rt.Codec.any")
    case RT.IntRange(lo, hi) =>
      val (tpe, expr) = intRepr(lo, hi)
      inlineCodec(tpe, expr, o)
    case RT.LitInt(n) if n.isValidInt => inlineCodec("Int", s"rt.Codec.intIn($n)", o)
    case RT.SizedBytes(lo, hi) => inlineCodec("rt.Bytes", s"rt.Codec.bytesSized($lo, $hi)", o)
    case RT.SizedText(lo, hi)  => inlineCodec("String", s"rt.Codec.textSized($lo, $hi)", o)
    case RT.Ref(q) =>
      if cfg.external(q) then Tc("rt.Node", "rt.Codec.any")
      else
        val n = refName(q, o)
        Tc(n, n)
    case RT.Tagged(n, inner) =>
      val i = tc(inner, hint, o, ctx)
      inlineCodec(i.tpe, s"rt.Codec.tagged(BigInt($n), ${i.codec})", o)
    case RT.CborOf(inner) =>
      val i = tc(inner, hint, o, ctx)
      inlineCodec(i.tpe, s"rt.Codec.embedded(${i.codec})", o)
    case RT.Choice(alts) if maybeTagged(alts).isDefined =>
      val (n, inner) = maybeTagged(alts).get
      val i = tc(inner, hint, o, ctx)
      inlineCodec(i.tpe, s"rt.Codec.maybeTagged(BigInt($n), ${i.codec})", o)
    case RT.Choice(alts) if alts.size == 2 && alts.contains(nil) =>
      val i = tc(alts.find(_ != nil).get, hint, o, ctx)
      inlineCodec(s"Option[${i.tpe}]", s"rt.Codec.nullable(${i.codec})", o)
    case RT.Choice(alts) if intLits(alts).isDefined =>
      inlineCodec("Int", s"rt.Codec.intIn(${intLits(alts).get.mkString(", ")})", o)
    case RT.Choice(alts) if mergeableVectors(alts) =>
      val vs = alts.collect { case RT.Arr(es) => es.head }
      val i = tc(vs.head.value, hint, o, ctx)
      val min = vs.map(_.occ.min).min
      val max =
        if vs.exists(_.occ.max.isEmpty) then "Int.MaxValue" else vs.flatMap(_.occ.max).max.toString
      inlineCodec(s"Vector[${i.tpe}]", s"rt.Codec.vec(${i.codec}, $min, $max)", o)
    case RT.Arr(es) if isVector(es) =>
      val e = es.head
      val i = tc(e.value, hint, o, ctx)
      val max = e.occ.max.fold("Int.MaxValue")(_.toString)
      inlineCodec(s"Vector[${i.tpe}]", s"rt.Codec.vec(${i.codec}, ${e.occ.min}, $max)", o)
    case RT.MapT(es) if isPairs(es) =>
      val e = es.head
      val k = e.key match
        case Some(RKey.OfType(kt, _)) => kt
        case _                        => fail(GenError.Unsupported(ctx, "pairs without a type key"))
      val kt = tc(k, hint + "Key", o, ctx)
      val vt = tc(e.value, hint + "Value", o, ctx)
      inlineCodec(s"Vector[(${kt.tpe}, ${vt.tpe})]", s"rt.Codec.pairs(${kt.codec}, ${vt.codec})", o)
    case RT.Choice(_) | RT.Arr(_) | RT.MapT(_) =>
      // An anonymous composite becomes a declaration of its own, named after where it occurs.
      val q = QName(o.module, hint)
      val d = classifyType(q, hint, t, new Owner(o.module, hint, o.rule))
      o.synth += d
      d.owner.imports.foreach(o.imports += _)
      Tc(hint, hint)
    case RT.Unsupported(why) => fail(GenError.Unsupported(ctx, why))
    case other               => fail(GenError.Unsupported(ctx, s"type $other in this position"))

  private def mergeableVectors(alts: Vector[RT]): Boolean =
    alts.size > 1 && alts.forall {
      case RT.Arr(es) => isVector(es)
      case _          => false
    } && alts.collect { case RT.Arr(es) => es.head.value }.distinct.size == 1

  // Shapes ---------------------------------------------------------------------------------------

  private val reservedFieldNames =
    Set("xs", "node", "out", "a", "parts", "w", "spec", "all", "from", "fresh")

  private def fieldNameFor(t: RT): Option[String] = t match
    case RT.Ref(q)                                      => Some(camel(q.name))
    case RT.Choice(alts) if maybeTagged(alts).isDefined => fieldNameFor(maybeTagged(alts).get._2)
    case RT.GroupRef(q)                                 => Some(camel(q.name))
    case RT.Tagged(_, i)                                => fieldNameFor(i)
    case RT.CborOf(i)                                   => fieldNameFor(i)
    case RT.Choice(alts) if alts.size == 2 && alts.contains(nil) =>
      alts.find(_ != nil).flatMap(fieldNameFor)
    case RT.Arr(es) if isVector(es) =>
      fieldNameFor(es.head.value).map(n => if n.endsWith("s") then n else n + "s")
    case _ => None

  private def uniq(base: String, used: mutable.Set[String]): String =
    val b0 = if reservedFieldNames(base) then base + "_" else base
    var n = b0
    var i = 2
    while used(n) do
      n = b0 + i
      i += 1
    used += n
    esc(n)

  /** For a literal element: (decode check with `@` as the position, encode statement). */
  private def literalCode(t: RT): Option[(String, String)] = t match
    case RT.LitInt(n) if n.signum >= 0 && n.isValidLong =>
      Some((s"rt.Lit.uint(xs(@), ${n}L)", s"out.uint(${n}L)"))
    case RT.LitText(s) => Some((s"rt.Lit.text(xs(@), ${quote(s)})", s"out.text(${quote(s)})"))
    case RT.LitBool(b) => Some((s"rt.Lit.bool(xs(@), $b)", s"out.bool($b)"))
    case RT.Prim(PrimKind.Nil) => Some(("rt.Lit.nul(xs(@))", "out.nul()"))
    case _                     => None

  private def arrayItems(
      es: Vector[REntry],
      o: Owner,
      q: QName,
      ctx: String,
      hint: String
  ): Vector[ArrItem] =
    val used = mutable.Set.empty[String]
    var sawOptional = false
    es.zipWithIndex.map { (e, idx) =>
      e.value match
        case v if literalCode(v).isDefined && e.occ.isOnce =>
          val (check, write) = literalCode(v).get
          ArrItem.Literal(check, write)
        case RT.GroupRef(g) =>
          if !e.occ.isOnce then
            fail(GenError.Unsupported(ctx, "repeated or optional group reference"))
          val gname = refName(g, o)
          val name = uniq(e.label.map(camel).orElse(fieldNameFor(e.value)).getOrElse("group"), used)
          ArrItem.Group(FieldInfo(name, gname, gname, name), gname)
        case v =>
          if !(e.occ.isOnce || e.occ.isOptional) then
            fail(GenError.Unsupported(ctx, s"entry with occurrence ${e.occ} in a fixed array"))
          if e.occ.isOptional then sawOptional = true
          else if sawOptional then
            fail(GenError.Unsupported(ctx, "required entry after an optional one"))
          val configured = cfg.fields.get((q, idx.toString))
          val name =
            uniq(
              configured.orElse(e.label.map(camel)).orElse(fieldNameFor(v)).getOrElse("value"),
              used
            )
          val t = tc(v, hint + pascal(name), o, ctx)
          val path = e.label
            .orElse(v match
              case RT.Ref(r) => Some(r.name)
              case _         => None)
            .getOrElse(name)
          ArrItem.Field(
            FieldInfo(name, if e.occ.isOptional then s"Option[${t.tpe}]" else t.tpe, t.codec, path),
            e.occ.isOptional
          )
    }

  private def mapItems(
      es: Vector[REntry],
      o: Owner,
      q: QName,
      ctx: String,
      hint: String
  ): Vector[MapItem] =
    val used = mutable.Set.empty[String]
    es.map { e =>
      e.key match
        case Some(k @ (RKey.IntKey(_) | RKey.TextKey(_))) =>
          val (keyText, keyCode) = k match
            case RKey.IntKey(n)  => (n.toString, s"rt.MapKey.IntKey(${n}L)")
            case RKey.TextKey(s) => (s, s"rt.MapKey.TextKey(${quote(s)})")
          if !(e.occ.isOnce || e.occ.isOptional) then
            fail(GenError.Unsupported(ctx, "repeated literal map key"))
          val base = cfg.fields
            .get((q, keyText))
            .orElse(e.label.map(camel))
            .orElse(k match
              case RKey.TextKey(t) => Some(camel(t))
              case _               => None)
            .orElse(fieldNameFor(e.value))
            .getOrElse(s"key$keyText")
          val name = uniq(base, used)
          val t = tc(e.value, hint + pascal(name), o, ctx)
          MapItem.Literal(
            FieldInfo(
              name,
              if e.occ.isOptional then s"Option[${t.tpe}]" else t.tpe,
              t.codec,
              keyText
            ),
            keyCode,
            e.occ.isOptional
          )
        case Some(RKey.OfType(kt, _)) =>
          if e.occ.min > 1 then
            fail(GenError.Unsupported(ctx, "map entry with a minimum above one"))
          val k = tc(kt, hint + "Key", o, ctx)
          val v = tc(e.value, hint + "Value", o, ctx)
          val name = uniq(fieldNameFor(kt).getOrElse("entries"), used)
          MapItem.Typed(FieldInfo(name, s"Vector[(${k.tpe}, ${v.tpe})]", "", name), k, v)
        case None => fail(GenError.Unsupported(ctx, "map entry without a key"))
    }

  // Declarations -----------------------------------------------------------------------------------

  private def classify(q: QName): Decl =
    val name = scalaName(q)
    val o = new Owner(q.module, name, q)
    schema.rule(q).body match
      case RuleBody.GroupBody(es) =>
        Decl.GroupStruct(q, name, arrayItems(es, o, q, q.toString, name), o)
      case RuleBody.Type(t) => classifyType(q, name, t, o)

  private def classifyType(q: QName, name: String, t: RT, o: Owner): Decl =
    val ctx = q.toString
    def opaque(repr: String, codec: String): Decl = Decl.Opaque(q, name, repr, codec, o)
    val decl: Decl = t match
      case RT.LitInt(n) if !n.isValidInt =>
        val (tpe, expr) = intRepr(n, n)
        opaque(tpe, expr)
      case RT.Arr(es) if !isVector(es) =>
        Decl.Struct(q, name, Shape.Arr(arrayItems(es, o, q, ctx, name)), cfg.preserve(q), o)
      case RT.MapT(es) if !isPairs(es) =>
        Decl.Struct(q, name, Shape.Map(mapItems(es, o, o.rule, ctx, name)), cfg.preserve(q), o)
      case RT.Choice(alts) if isPlainChoice(alts) =>
        Decl.Enum(q, name, enumCases(q, name, alts, o), o)
      case RT.Choice(alts) if intLits(alts).isDefined =>
        opaque("Int", s"rt.Codec.intIn(${intLits(alts).get.mkString(", ")})")
      case RT.Prim(PrimKind.Uint)                         => opaque("BigInt", "rt.Codec.uint")
      case RT.Prim(PrimKind.Int) | RT.Prim(PrimKind.Nint) => opaque("BigInt", "rt.Codec.int")
      case RT.Prim(PrimKind.Bytes)                        => opaque("rt.Bytes", "rt.Codec.bytes")
      case RT.Prim(PrimKind.Text)                         => opaque("String", "rt.Codec.text")
      case RT.Prim(PrimKind.Bool)                         => opaque("Boolean", "rt.Codec.bool")
      case RT.IntRange(lo, hi) =>
        val (tpe, expr) = intRepr(lo, hi)
        opaque(tpe, expr)
      case RT.LitInt(n) if n.isValidInt => opaque("Int", s"rt.Codec.intIn($n)")
      case RT.SizedBytes(lo, hi)        => opaque("rt.Bytes", s"rt.Codec.bytesSized($lo, $hi)")
      case RT.SizedText(lo, hi)         => opaque("String", s"rt.Codec.textSized($lo, $hi)")
      case RT.Unsupported(why)          => fail(GenError.Unsupported(ctx, why))
      case other                        =>
        // Anything else is a transparent wrapper (Ref, Tagged, CborOf, Vector, Option, Any, mergeable choices).
        val i = tc(other, name + "Content", o, ctx)
        Decl.Alias(q, name, i.tpe, unhoist(i.codec, o), o)
    if cfg.preserve(q) && !decl.isInstanceOf[Decl.Struct] then
      fail(GenError.Unsupported(ctx, "`preserve` is supported on array and map rules only"))
    decl

  /** An alias has no object to hold hoisted codecs: expand them into the one expression. */
  private def unhoist(codec: String, o: Owner): String =
    o.hoists.reverseIterator.foldLeft(codec) { (acc, h) =>
      acc.replaceAll(s"\\b${h._1}\\b", java.util.regex.Matcher.quoteReplacement(h._3))
    }

  private lazy val moduleTypeNames: Map[String, Set[String]] =
    reached.toVector
      .filterNot(q => cfg.external(q) || inlined(q))
      .groupMap(_.module)(scalaName)
      .map((m, ns) => m -> ns.toSet)
      .withDefaultValue(Set.empty)

  /** A readable default case name for an anonymous alternative, from its kind. */
  private def kindName(t: RT): Option[String] = t match
    case RT.Prim(PrimKind.Int) | RT.Prim(PrimKind.Uint) | RT.Prim(PrimKind.Nint) |
        RT.IntRange(_, _) =>
      Some("Integer")
    case RT.Prim(PrimKind.Bytes) | RT.SizedBytes(_, _) => Some("Bytes")
    case RT.Prim(PrimKind.Text) | RT.SizedText(_, _)   => Some("Text")
    case RT.Prim(PrimKind.Bool)                        => Some("Bool")
    case RT.Arr(es) if isVector(es)                    => Some("Items")
    case RT.MapT(es) if isPairs(es)                    => Some("Entries")
    case RT.Tagged(n, _)                               => Some(s"Tag$n")
    case _                                             => None

  /** A choice that needs an enum: it has alternatives that are not just a single wrapper type. */
  private def isPlainChoice(alts: Vector[RT]): Boolean =
    val nullable = alts.size == 2 && alts.contains(nil)
    !nullable && intLits(alts).isEmpty && !mergeableVectors(alts) && maybeTagged(alts).isEmpty

  private def enumCases(q: QName, name: String, alts: Vector[RT], o: Owner): Vector[CaseD] =
    val names = cfg.cases.getOrElse(q, Vector.empty)
    val strip = cfg.strip.getOrElse(q, Vector.empty)
    val used = mutable.Set.empty[String]
    alts.zipWithIndex.map { (alt, i) =>
      def pick(derived: Option[String]): String =
        val n = names
          .lift(i)
          .orElse(derived.map(d => pascal(strip.find(d.startsWith).fold(d)(p => d.stripPrefix(p)))))
          .getOrElse(s"Alt$i")
        // A case must not shadow a type of its module that the enum's own codecs refer to.
        uniq(
          if moduleTypeNames(q.module).contains(n) || reservedTypes(n) then n + "Case" else n,
          used
        )
      val ctx = s"$q alternative $i"
      alt match
        case RT.Ref(r) if inlined(r) =>
          val cname = pick(Some(r.name))
          schema.rule(r).body match
            case RuleBody.Type(RT.Arr(es)) =>
              CaseD.Struct(cname, Shape.Arr(arrayItems(es, o, r, ctx, name + cname)))
            case RuleBody.Type(RT.MapT(es)) =>
              CaseD.Struct(cname, Shape.Map(mapItems(es, o, r, ctx, name + cname)))
            case _ => fail(GenError.Unsupported(ctx, "inlined rule is not a struct"))
        case RT.Arr(es) if !isVector(es) =>
          val cname = pick(es match
            case Vector(REntry(_, _, _, RT.GroupRef(g))) => Some(g.name)
            case _                                       => None)
          CaseD.Struct(cname, Shape.Arr(arrayItems(es, o, q, ctx, name + cname)))
        case RT.MapT(es) if !isPairs(es) =>
          val cname = pick(None)
          CaseD.Struct(cname, Shape.Map(mapItems(es, o, q, ctx, name + cname)))
        case RT.Prim(PrimKind.Nil) => CaseD.Null(pick(None))
        case other =>
          val cname = pick(other match
            case RT.Ref(r) => Some(r.name)
            case _         => kindName(other))
          val t = tc(other, name + cname, o, ctx)
          val field = uniq(fieldNameFor(other).getOrElse("value"), mutable.Set.empty)
          CaseD.Wrap(cname, FieldInfo(field, t.tpe, t.codec, "value"))
    }

  // Rendering ----------------------------------------------------------------------------------------

  private def isNumber(s: String): Boolean = s.nonEmpty && s.forall(_.isDigit)

  private def renderArrayDecode(
      items: Vector[ArrItem],
      build: String,
      node: String,
      ind: String,
      withXs: Boolean = true
  ): String =
    val fixed = items.count {
      case ArrItem.Literal(_, _)   => true
      case ArrItem.Field(_, false) => true
      case _                       => false
    }
    val groups = items.collect { case ArrItem.Group(_, g) => s"$g.Arity" }
    val optional = items.count {
      case ArrItem.Field(_, true) => true
      case _                      => false
    }
    val minExpr = (if fixed > 0 || groups.isEmpty then fixed.toString else "") + groups
      .map(g => (if fixed > 0 then " + " else "") + g)
      .mkString
    val sb = new StringBuilder
    def line(s: String) = sb.append(ind).append(s).append('\n')
    line("for")
    if withXs then
      line(
        if optional == 0 then s"  xs <- rt.Arr.exact($node, $minExpr)"
        else s"  xs <- rt.Arr.between($node, $minExpr, $minExpr + $optional)"
      )
    var pos = "0"
    def next(p: String, by: String): String =
      if isNumber(p) && isNumber(by) then (p.toInt + by.toInt).toString else s"$p + $by"
    def seg(f: FieldInfo, @unused p: String): String = quote(f.path)
    items.foreach { it =>
      val p = pos
      it match
        case ArrItem.Literal(check, _) =>
          val at = if isNumber(p) then quote(s"[$p]") else s"""s"[$${$p}]""""
          line(s"  _ <- ${check.replace("@", p)}.at($at)")
          pos = next(p, "1")
        case ArrItem.Field(f, false) =>
          line(s"  ${f.name} <- ${f.codec}.decode(xs($p)).at(${seg(f, p)})")
          pos = next(p, "1")
        case ArrItem.Field(f, true) =>
          line(
            s"  ${f.name} <- (if xs.size > $p then ${f.codec}.decode(xs($p)).map(Some(_)).at(${seg(f, p)}) else rt.noValue)"
          )
          pos = next(p, "1")
        case ArrItem.Group(f, g) =>
          line(s"  ${f.name} <- $g.decodeFrom(xs, $p)")
          pos = next(p, s"$g.Arity")
    }
    line(s"yield $build")
    sb.result()

  private def renderArrayEncode(
      items: Vector[ArrItem],
      acc: FieldInfo => String,
      ind: String,
      head: Boolean = true
  ): String =
    val sb = new StringBuilder
    def line(s: String) = sb.append(ind).append(s).append('\n')
    if head then
      val fixed = items.count {
        case ArrItem.Literal(_, _)   => true
        case ArrItem.Field(_, false) => true
        case _                       => false
      }
      val groups = items.collect { case ArrItem.Group(_, g) => s"$g.Arity" }
      val opts = items.collect { case ArrItem.Field(f, true) =>
        s"(if ${acc(f)}.isDefined then 1 else 0)"
      }
      val terms = (if fixed > 0 || (groups.isEmpty && opts.isEmpty) then Vector(fixed.toString)
                   else Vector.empty) ++ groups ++ opts
      line(s"out.arrayHead(${terms.mkString(" + ")})")
    items.foreach {
      case ArrItem.Literal(_, write) => line(write)
      case ArrItem.Field(f, false)   => line(s"${f.codec}.encode(${acc(f)}, out)")
      case ArrItem.Field(f, true)    => line(s"${acc(f)}.foreach(${f.codec}.encode(_, out))")
      case ArrItem.Group(f, g)       => line(s"$g.encodeInto(${acc(f)}, out)")
    }
    sb.result()

  private def renderMapDecode(
      items: Vector[MapItem],
      build: String,
      node: String,
      spec: String,
      ind: String
  ): String =
    val sb = new StringBuilder
    def line(s: String) = sb.append(ind).append(s).append('\n')
    line("for")
    line(s"  parts <- rt.Maps.split($node, $spec)")
    var li = 0
    var ti = 0
    items.foreach {
      case MapItem.Literal(f, _, opt) =>
        line(s"  ${f.name} <- parts.${if opt then "optional" else "required"}($li, ${f.codec})")
        li += 1
      case MapItem.Typed(f, k, v) =>
        line(s"  ${f.name} <- parts.entries($ti, ${k.codec}, ${v.codec})")
        ti += 1
    }
    line(s"yield $build")
    sb.result()

  private def renderMapSpec(items: Vector[MapItem], name: String, ind: String): String =
    val lits = items.collect { case l: MapItem.Literal => l.keyCode }
    val typed = items.collect { case t: MapItem.Typed => s"k => ${t.key.codec}.decode(k).isRight" }
    s"${ind}private lazy val $name = new rt.MapSpec(\n$ind  Array[rt.MapKey](${lits.mkString(", ")}),\n$ind  Array[rt.Node => Boolean](${typed.mkString(", ")})\n$ind)\n"

  private def renderMapEncode(
      items: Vector[MapItem],
      acc: FieldInfo => String,
      ind: String
  ): String =
    val sb = new StringBuilder
    def line(s: String) = sb.append(ind).append(s).append('\n')
    val req = items.count {
      case MapItem.Literal(_, _, false) => true
      case _                            => false
    }
    val dyn = items.collect {
      case MapItem.Literal(f, _, true) => s"(if ${acc(f)}.isDefined then 1 else 0)"
      case MapItem.Typed(f, _, _)      => s"${acc(f)}.size"
    }
    line(s"out.mapHead(${(req.toString +: dyn).mkString(" + ")})")
    items.foreach {
      case MapItem.Literal(f, key, opt) =>
        val writeKey =
          if key.startsWith("rt.MapKey.IntKey(") then
            s"out.uint(${key.stripPrefix("rt.MapKey.IntKey(").stripSuffix(")")})"
          else s"out.text(${key.stripPrefix("rt.MapKey.TextKey(").stripSuffix(")")})"
        if opt then line(s"${acc(f)}.foreach { v => $writeKey; ${f.codec}.encode(v, out) }")
        else
          line(writeKey)
          line(s"${f.codec}.encode(${acc(f)}, out)")
      case MapItem.Typed(f, k, v) =>
        line(
          s"${acc(f)}.foreach { (k, v) => ${k.codec}.encode(k, out); ${v.codec}.encode(v, out) }"
        )
    }
    sb.result()

  private def params(items: Vector[FieldInfo]): String =
    items.map(f => s"${f.name}: ${f.tpe}").mkString(", ")
  private def args(items: Vector[FieldInfo]): String = items.map(_.name).mkString(", ")

  private def fieldsOf(s: Shape): Vector[FieldInfo] = s match
    case Shape.Arr(is) =>
      is.collect {
        case ArrItem.Field(f, _) => f
        case ArrItem.Group(f, _) => f
      }
    case Shape.Map(is) =>
      is.collect {
        case MapItem.Literal(f, _, _) => f
        case MapItem.Typed(f, _, _)   => f
      }

  private def hoistLines(o: Owner, ind: String): String =
    o.hoists.map((n, tpe, expr) => s"${ind}private lazy val $n: $tpe = $expr\n").mkString

  private def renderDecl(d: Decl): String =
    val sb = new StringBuilder
    def w(s: String): Unit = sb.append(s).append('\n')
    val doc = s"/** CDDL rule `${d.q}`. */"
    d match
      case Decl.Opaque(_, name, repr, codec, _) =>
        w(doc)
        w(s"opaque type $name = $repr")
        w(s"object $name extends rt.Codec[$name]:")
        w(s"  private val codec: rt.Codec[$repr] = $codec")
        w(s"  def from(value: $repr): Either[rt.ConstraintViolation, $name] =")
        w(s"    codec.check(value) match")
        w(s"      case None             => Right(value)")
        w(
          s"      case Some(constraint) => Left(rt.ConstraintViolation(${quote(name)}, constraint, String.valueOf(value)))"
        )
        w(s"  def unsafe(value: $repr): $name = value")
        w(s"  extension (self: $name) def value: $repr = self")
        w(s"  def decode(node: rt.Node): rt.Decoded[$name] = codec.decode(node)")
        w(s"  def encode(a: $name, out: rt.Writer): Unit = codec.encode(a, out)")
        w(s"  override def check(a: $name): Option[String] = codec.check(a)")
      case Decl.Alias(_, name, tpe, codec, _) =>
        w(doc)
        w(s"type $name = $tpe")
        w(s"lazy val $name: rt.Codec[$tpe] = $codec")
      case Decl.Struct(_, name, shape, preserve, o) =>
        val fields = fieldsOf(shape)
        w(
          if preserve then
            s"/** CDDL rule `${d.q}`. `raw` is the exact encoding it was decoded from (hashed or signed by the spec). */"
          else doc
        )
        w(s"final case class $name(${params(fields)})${
            if preserve then "(val raw: rt.Bytes)" else ""
          }")
        w(s"object $name extends rt.Codec[$name]:")
        sb.append(hoistLines(o, "  "))
        val build =
          if preserve then s"new $name(${args(fields)})(node.original)"
          else s"new $name(${args(fields)})"
        def accessor(f: FieldInfo): String = s"a.${f.name}"
        val encodeBody = shape match
          case Shape.Arr(items) => renderArrayEncode(items, accessor, "    ")
          case Shape.Map(items) => renderMapEncode(items, accessor, "    ")
        shape match
          case Shape.Map(items) => sb.append(renderMapSpec(items, "spec", "  "))
          case _                => ()
        w(s"  def decode(node: rt.Node): rt.Decoded[$name] =")
        shape match
          case Shape.Arr(items) => sb.append(renderArrayDecode(items, build, "node", "    "))
          case Shape.Map(items) => sb.append(renderMapDecode(items, build, "node", "spec", "    "))
        if preserve then
          w(s"  def encode(a: $name, out: rt.Writer): Unit = out.raw(a.raw)")
          w("  /** Build a value from its members; `raw` is their canonical encoding. */")
          w(s"  def canonical(${params(fields)}): $name =")
          w(s"    val fresh = new $name(${args(fields)})(rt.EmptyBytes)")
          w("    val w = new rt.Writer")
          w("    writeFields(fresh, w)")
          w(s"    new $name(${args(fields)})(w.result)")
          w(s"  private def writeFields(a: $name, out: rt.Writer): Unit =")
        else w(s"  def encode(a: $name, out: rt.Writer): Unit =")
        sb.append(encodeBody)
      case Decl.GroupStruct(_, name, items, o) =>
        val fields = fieldsOf(Shape.Arr(items))
        val arity = items
          .map {
            case ArrItem.Group(_, g)    => s"$g.Arity"
            case ArrItem.Field(_, true) => "0"
            case _                      => "1"
          }
          .mkString(" + ")
        w(doc)
        w(
          "/** A group rule: its members are spliced into the enclosing array, not wrapped in an array of their own. */"
        )
        w(s"final case class $name(${params(fields)})")
        w(s"object $name:")
        sb.append(hoistLines(o, "  "))
        w(s"  val Arity: Int = $arity")
        w(s"  def decodeFrom(all: Vector[rt.Node], from: Int): rt.Decoded[$name] =")
        w(s"    val xs = all.slice(from, from + Arity)")
        sb.append(
          renderArrayDecode(items, s"$name(${args(fields)})", "node", "    ", withXs = false)
        )
        w(s"  def encodeInto(a: $name, out: rt.Writer): Unit =")
        sb.append(renderArrayEncode(items, f => s"a.${f.name}", "    ", head = false))
      case Decl.Enum(_, name, cases, o) =>
        w(doc)
        w(s"enum $name:")
        cases.foreach {
          case CaseD.Struct(cn, shape) =>
            val fs = fieldsOf(shape)
            w(if fs.isEmpty then s"  case $cn" else s"  case $cn(${params(fs)})")
          case CaseD.Null(cn)    => w(s"  case $cn")
          case CaseD.Wrap(cn, f) => w(s"  case $cn(${params(Vector(f))})")
        }
        w(s"object $name extends rt.Codec[$name]:")
        sb.append(hoistLines(o, "  "))
        cases.foreach {
          case CaseD.Struct(cn, Shape.Map(items)) =>
            sb.append(renderMapSpec(items, s"spec$cn", "  "))
          case _ => ()
        }
        w(s"  private lazy val alternatives = new rt.Alternatives[$name](")
        w(
          s"    Array[rt.Node => rt.Decoded[$name]](${cases.map(c => s"decode${c.name}").mkString(", ")})"
        )
        w("  )")
        w(s"  def decode(node: rt.Node): rt.Decoded[$name] = alternatives(node)")
        cases.foreach {
          case CaseD.Struct(cn, shape) =>
            val fs = fieldsOf(shape)
            val build = if fs.isEmpty then cn else s"$cn(${args(fs)})"
            w(s"  private def decode$cn(node: rt.Node): rt.Decoded[$name] =")
            shape match
              case Shape.Arr(items) => sb.append(renderArrayDecode(items, build, "node", "    "))
              case Shape.Map(items) =>
                sb.append(renderMapDecode(items, build, "node", s"spec$cn", "    "))
          case CaseD.Null(cn) =>
            w(
              s"  private def decode$cn(node: rt.Node): rt.Decoded[$name] = rt.Lit.nul(node).map(_ => $cn)"
            )
          case CaseD.Wrap(cn, f) =>
            w(
              s"  private def decode$cn(node: rt.Node): rt.Decoded[$name] = ${f.codec}.decode(node).map($cn(_))"
            )
        }
        w(s"  def encode(a: $name, out: rt.Writer): Unit = a match")
        cases.foreach {
          case CaseD.Struct(cn, shape) =>
            val fs = fieldsOf(shape)
            w(if fs.isEmpty then s"    case $cn =>" else s"    case $cn(${args(fs)}) =>")
            shape match
              case Shape.Arr(items) => sb.append(renderArrayEncode(items, f => f.name, "      "))
              case Shape.Map(items) => sb.append(renderMapEncode(items, f => f.name, "      "))
          case CaseD.Null(cn) => w(s"    case $cn => out.nul()")
          case CaseD.Wrap(cn, f) =>
            w(s"    case $cn(${f.name}) => ${f.codec}.encode(${f.name}, out)")
        }
    sb.result()

  // Driver --------------------------------------------------------------------------------------------

  private def flatten(d: Decl): Vector[Decl] = d +: d.owner.synth.toVector.flatMap(flatten)

  /** Generate the Scala sources: relative path -> content. */
  def generate(): Map[String, String] =
    cfg.roots.foreach { r =>
      totalRefs(r) += 1
      walk(r)
    }
    (cfg.preserve ++ cfg.cases.keySet)
      .filterNot(q => reached.contains(q) || q.module.isEmpty)
      .foreach { q =>
        // A synthetic rule (named after where it occurs) is allowed; a typo in a real rule name is not.
        if schema.lookup(q).isDefined then
          fail(GenError.BadConfig(0, s"$q is configured but not reachable from any root"))
      }
    val decls = reached.toVector.filterNot(q => cfg.external(q) || inlined(q)).map(classify)
    val byModule = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Decl]]
    decls
      .flatMap(flatten)
      .foreach(x => byModule.getOrElseUpdate(x.q.module, mutable.ArrayBuffer.empty) += x)
    byModule.foreach { (m, ds) =>
      ds.groupBy(_.name).foreach { (n, same) =>
        if same.size > 1 then fail(GenError.NameClash(m, n, same.map(_.q.toString).toVector))
      }
    }
    byModule.map { (module, ds) =>
      val imports = ds.flatMap(_.owner.imports).distinct.filterNot(_ == module)
      val text = new StringBuilder
      text.append("// SPDX-License-Identifier: Apache-2.0\n")
      text.append(
        s"// Generated by scarab-models-gen from the CDDL module `$module` (${specs(module).files.mkString(", ")}). Do not edit.\n"
      )
      text.append(s"package ${packageOf(module)}\n\n")
      text.append(s"import ${cfg.basePackage}.runtime as rt\n")
      text.append("import rt.at\n")
      imports.foreach(m => text.append(s"import ${packageOf(m)}\n"))
      text.append("\n")
      ds.foreach { d =>
        text.append(renderDecl(d))
        text.append("\n")
      }
      s"${packageOf(module).replace('.', '/')}/${pascal(module.replace('.', '_'))}.scala" -> text
        .result()
    }.toMap

object Emitter:
  private val keywords = Set(
    "type",
    "val",
    "var",
    "def",
    "class",
    "object",
    "trait",
    "enum",
    "given",
    "match",
    "case",
    "if",
    "else",
    "for",
    "while",
    "do",
    "new",
    "this",
    "super",
    "with",
    "yield",
    "end",
    "then",
    "extends",
    "final",
    "lazy",
    "private",
    "protected",
    "override",
    "package",
    "import",
    "return",
    "throw",
    "try",
    "catch",
    "finally",
    "true",
    "false",
    "null",
    "implicit",
    "sealed",
    "abstract",
    "forSome",
    "macro",
    "using"
  )
  def esc(name: String): String = if keywords(name) then s"`$name`" else name

  def quote(s: String): String =
    "\"" + s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case c    => c.toString
    } + "\""

  def pascal(s: String): String =
    val parts = s.split("[_\\-.$@]+").filter(_.nonEmpty)
    val joined = parts.map(p => p.head.toUpper.toString + p.tail).mkString
    if joined.isEmpty then "X" else if joined.head.isDigit then "N" + joined else joined

  def camel(s: String): String =
    val p = pascal(s)
    p.head.toLower.toString + p.tail

  final case class Tc(tpe: String, codec: String)

  /** A member of a generated class: Scala name (already escaped) and type, codec expression and
    * CDDL path segment.
    */
  final case class FieldInfo(name: String, tpe: String, codec: String, path: String)

  /** Per-declaration state: module it lives in, hoisted codec expressions, foreign imports,
    * synthesized types.
    */
  final class Owner(val module: String, val name: String, val rule: QName):
    val hoists = mutable.ArrayBuffer.empty[(String, String, String)]
    val imports = mutable.LinkedHashSet.empty[String]
    val synth = mutable.ArrayBuffer.empty[Decl]
    def hoist(tpe: String, expr: String): String =
      hoists.find(h => h._3 == expr && h._2 == tpe) match
        case Some(h) => h._1
        case None =>
          val n = s"c${hoists.size}"
          hoists += ((n, tpe, expr))
          n

  enum ArrItem:
    /** A literal element: the decode check (`@` stands for the position) and the encode statement.
      */
    case Literal(check: String, write: String)
    case Field(f: FieldInfo, optional: Boolean)
    case Group(f: FieldInfo, groupType: String)

  enum MapItem:
    case Literal(f: FieldInfo, keyCode: String, optional: Boolean)
    case Typed(f: FieldInfo, key: Tc, value: Tc)

  enum Shape:
    case Arr(items: Vector[ArrItem])
    case Map(items: Vector[MapItem])

  enum CaseD:
    case Struct(cname: String, shape: Shape)
    case Null(cname: String)
    case Wrap(cname: String, f: FieldInfo)

    def name: String = this match
      case Struct(n, _) => n
      case Null(n)      => n
      case Wrap(n, _)   => n

  sealed trait Decl:
    def q: QName
    def name: String
    def owner: Owner

  object Decl:
    final case class Opaque(q: QName, name: String, repr: String, codec: String, owner: Owner)
        extends Decl
    final case class Alias(q: QName, name: String, tpe: String, codec: String, owner: Owner)
        extends Decl
    final case class Struct(q: QName, name: String, shape: Shape, preserve: Boolean, owner: Owner)
        extends Decl
    final case class GroupStruct(q: QName, name: String, items: Vector[ArrItem], owner: Owner)
        extends Decl
    final case class Enum(q: QName, name: String, cases: Vector[CaseD], owner: Owner) extends Decl
