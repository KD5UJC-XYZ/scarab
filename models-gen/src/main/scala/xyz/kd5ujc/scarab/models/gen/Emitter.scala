// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import cats.data.{Chain, StateT}
import cats.syntax.all.*

/** Resolves reachability before emitting source, with typed, stack-safe failures. */
final class Emitter(schema: Schema, cfg: Config):
  import Emitter.*
  import GenResult.*

  private type Discover[A] = StateT[GenResult, Reach, A]
  private def deferred[A](body: => Discover[A]): Discover[A] =
    StateT(s => GenResult.deferred(body.run(s)))

  private def walk(q: QName): Discover[Unit] = deferred {
    StateT.get[GenResult, Reach].flatMap { r =>
      if r.seen(q) then StateT.pure(())
      else
        val next = r.copy(order = r.order :+ q, seen = r.seen + q)
        StateT.set[GenResult, Reach](next).flatMap { _ =>
          if cfg.external(q) then StateT.pure(())
          else
            StateT.liftF(fromEither(schema.rule(q))).flatMap { rule =>
              StateT.modify[GenResult, Reach](s => s.copy(rules = s.rules.updated(q, rule))) *>
                (rule.body match
                  case RuleBody.Type(t)       => visit(t)
                  case RuleBody.GroupBody(es) => es.traverse_(visitEntry))
            }
        }
    }
  }

  private def visitEntry(e: REntry): Discover[Unit] =
    e.key.traverse_ {
      case RKey.OfType(t, _) => visit(t)
      case _                 => StateT.pure[GenResult, Reach, Unit](())
    } *> visit(e.value)

  private def visit(t: RT): Discover[Unit] = deferred {
    t match
      case RT.Ref(q) =>
        StateT.modify[GenResult, Reach](s =>
          s.copy(total = s.total.updated(q, s.total.getOrElse(q, 0) + 1))
        ) *> walk(q)
      case RT.GroupRef(q) => visit(RT.Ref(q))
      case RT.Choice(alts) =>
        alts.traverse_ { alt =>
          val count = alt match
            case RT.Ref(q) if isPlainChoice(alts) =>
              StateT.modify[GenResult, Reach](s =>
                s.copy(alternatives = s.alternatives.updated(q, s.alternatives.getOrElse(q, 0) + 1))
              )
            case _ => StateT.pure[GenResult, Reach, Unit](())
          count *> visit(alt)
        }
      case RT.Arr(es)      => es.traverse_(visitEntry)
      case RT.MapT(es)     => es.traverse_(visitEntry)
      case RT.Tagged(_, i) => visit(i)
      case RT.CborOf(i)    => visit(i)
      case _               => StateT.pure(())
  }

  def generate(): Either[GenError, Map[String, String]] =
    val discover = cfg.roots.traverse_ { q =>
      StateT.modify[GenResult, Reach](s =>
        s.copy(total = s.total.updated(q, s.total.getOrElse(q, 0) + 1))
      ) *> walk(q)
    }
    discover.runS(Reach()).value.value.flatMap(r => new PreparedEmitter(cfg, schema, r).generate())

private final class PreparedEmitter(cfg: Config, schema: Schema, reached: Emitter.Reach):
  import Emitter.*
  import GenResult.*

  private type Owners = Map[Owner, OwnerData]
  private type Build[A] = StateT[GenResult, Owners, A]
  private def deferred[A](body: => Build[A]): Build[A] =
    StateT(s => GenResult.deferred(body.run(s)))
  private def pure[A](a: A): Build[A] = StateT.pure(a)
  private def fail[A](e: GenError): Build[A] = StateT.liftF(failure(e))
  private def unsupported[A](ctx: String, f: UnsupportedFeature): Build[A] = fail(
    GenError.Unsupported(ctx, f)
  )
  private def data(o: Owner): Build[OwnerData] = StateT.inspect(_.getOrElse(o, OwnerData()))
  private def update(o: Owner)(f: OwnerData => OwnerData): Build[Unit] =
    StateT.modify(s => s.updated(o, f(s.getOrElse(o, OwnerData()))))
  private val specs = cfg.modules.map(m => m.name -> m).toMap
  private def packageOf(module: String): String = s"${cfg.basePackage}.${specs(module).subpackage}"
  private def lastSegment(module: String): String = specs(module).subpackage.split('.').last

  private def isStructBody(q: QName): Boolean = reached.rules(q).body match
    case RuleBody.Type(RT.Arr(es))  => !isVector(es)
    case RuleBody.Type(RT.MapT(es)) => !isPairs(es)
    case _                          => false

  private def inlined(q: QName): Boolean =
    !cfg.roots.contains(q) && reached.total.getOrElse(q, 0) == 1 && reached.alternatives.getOrElse(
      q,
      0
    ) == 1 &&
      !cfg.preserve(q) && !cfg.rename.contains(q) && !cfg.external(q) && isStructBody(q)

  private val reservedTypes = Set(
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

  def scalaName(q: QName): String = cfg.rename.getOrElse(
    q,
    pascal(q.name) match
      case n if reservedTypes(n) => n + "Type"
      case n                     => n
  )

  private def refName(q: QName, o: Owner): Build[String] =
    val n = scalaName(q)
    if q.module == o.module then pure(n)
    else
      update(o)(d => d.copy(imports = (d.imports :+ q.module).distinct))
        .as(s"${lastSegment(q.module)}.$n")

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

  private def inlineCodec(tpe: String, expr: String, o: Owner): Build[Tc] =
    if expr.matches("[A-Za-z0-9_.]+") then pure(Tc(tpe, expr))
    else
      data(o).flatMap { d =>
        d.hoists.find(h => h._3 == expr && h._2 == tpe) match
          case Some(h) => pure(Tc(tpe, h._1))
          case None =>
            val n = s"c${d.hoists.size}"
            update(o)(d => d.copy(hoists = d.hoists :+ ((n, tpe, expr)))).as(Tc(tpe, n))
      }

  private def tc(t: RT, hint: String, o: Owner, ctx: String): Build[Tc] = deferred {
    def wrapped(inner: RT)(f: Tc => (String, String)): Build[Tc] =
      tc(inner, hint, o, ctx).flatMap(i => { val (tpe, expr) = f(i); inlineCodec(tpe, expr, o) })
    t match
      case RT.LitInt(n) if !n.isValidInt                  => tc(RT.IntRange(n, n), hint, o, ctx)
      case RT.Prim(PrimKind.Uint)                         => pure(Tc("BigInt", "rt.Codec.uint"))
      case RT.Prim(PrimKind.Int) | RT.Prim(PrimKind.Nint) => pure(Tc("BigInt", "rt.Codec.int"))
      case RT.Prim(PrimKind.Bytes)                        => pure(Tc("rt.Bytes", "rt.Codec.bytes"))
      case RT.Prim(PrimKind.Text)                         => pure(Tc("String", "rt.Codec.text"))
      case RT.Prim(PrimKind.Bool)                         => pure(Tc("Boolean", "rt.Codec.bool"))
      case RT.Prim(PrimKind.Any)                          => pure(Tc("rt.Node", "rt.Codec.any"))
      case RT.IntRange(lo, hi) => val (tpe, expr) = intRepr(lo, hi); inlineCodec(tpe, expr, o)
      case RT.LitInt(n) if n.isValidInt => inlineCodec("Int", s"rt.Codec.intIn($n)", o)
      case RT.SizedBytes(lo, hi) => inlineCodec("rt.Bytes", s"rt.Codec.bytesSized($lo, $hi)", o)
      case RT.SizedText(lo, hi)  => inlineCodec("String", s"rt.Codec.textSized($lo, $hi)", o)
      case RT.Ref(q) =>
        if cfg.external(q) then pure(Tc("rt.Node", "rt.Codec.any"))
        else refName(q, o).map(n => Tc(n, n))
      case RT.Tagged(n, inner) =>
        wrapped(inner)(i => (i.tpe, s"rt.Codec.tagged(BigInt($n), ${i.codec})"))
      case RT.CborOf(inner) => wrapped(inner)(i => (i.tpe, s"rt.Codec.embedded(${i.codec})"))
      case RT.Choice(alts) if maybeTagged(alts).isDefined =>
        val (n, inner) = maybeTagged(alts).get
        wrapped(inner)(i => (i.tpe, s"rt.Codec.maybeTagged(BigInt($n), ${i.codec})"))
      case RT.Choice(alts) if alts.size == 2 && alts.contains(nil) =>
        wrapped(alts.find(_ != nil).get)(i =>
          (s"Option[${i.tpe}]", s"rt.Codec.nullable(${i.codec})")
        )
      case RT.Choice(alts) if intLits(alts).isDefined =>
        inlineCodec("Int", s"rt.Codec.intIn(${intLits(alts).get.mkString(", ")})", o)
      case RT.Choice(alts) if mergeableVectors(alts) =>
        val vs = alts.collect { case RT.Arr(es) => es.head }
        val min = vs.map(_.occ.min).min
        val max =
          if vs.exists(_.occ.max.isEmpty) then "Int.MaxValue"
          else vs.flatMap(_.occ.max).max.toString
        wrapped(vs.head.value)(i => (s"Vector[${i.tpe}]", s"rt.Codec.vec(${i.codec}, $min, $max)"))
      case RT.Arr(es) if isVector(es) =>
        val e = es.head
        wrapped(e.value)(i =>
          (
            s"Vector[${i.tpe}]",
            s"rt.Codec.vec(${i.codec}, ${e.occ.min}, ${e.occ.max.fold("Int.MaxValue")(_.toString)})"
          )
        )
      case RT.MapT(es) if isPairs(es) =>
        val e = es.head
        e.key match
          case Some(RKey.OfType(k, _)) =>
            for
              kt <- tc(k, hint + "Key", o, ctx)
              vt <- tc(e.value, hint + "Value", o, ctx)
              r <- inlineCodec(
                s"Vector[(${kt.tpe}, ${vt.tpe})]",
                s"rt.Codec.pairs(${kt.codec}, ${vt.codec})",
                o
              )
            yield r
          case _ => unsupported(ctx, UnsupportedFeature.PairsWithoutTypeKey)
      case RT.Choice(_) | RT.Arr(_) | RT.MapT(_) =>
        val nested = Owner(o.module, hint, o.rule)
        for
          d <- classifyType(QName(o.module, hint), hint, t, nested)
          child <- data(nested)
          _ <- update(o)(p =>
            p.copy(synth = p.synth :+ d, imports = (p.imports ++ child.imports).distinct)
          )
        yield Tc(hint, hint)
      case RT.Unsupported(feature) => unsupported(ctx, feature)
      case other                   => unsupported(ctx, UnsupportedFeature.TypeInPosition(other))
  }

  private val reservedFieldNames =
    Set("xs", "node", "out", "a", "parts", "w", "spec", "all", "from", "fresh", "next")

  private def fieldBase(t: RT): Option[String] =
    @scala.annotation.tailrec
    def loop(current: RT, plural: Boolean): Option[String] = current match
      case RT.Ref(q) =>
        Some(if plural && !camel(q.name).endsWith("s") then camel(q.name) + "s" else camel(q.name))
      case RT.GroupRef(q) =>
        Some(if plural && !camel(q.name).endsWith("s") then camel(q.name) + "s" else camel(q.name))
      case RT.Tagged(_, i)                                => loop(i, plural)
      case RT.CborOf(i)                                   => loop(i, plural)
      case RT.Choice(alts) if maybeTagged(alts).isDefined => loop(maybeTagged(alts).get._2, plural)
      case RT.Choice(alts) if alts.size == 2 && alts.contains(nil) =>
        loop(alts.find(_ != nil).get, plural)
      case RT.Arr(es) if isVector(es) => loop(es.head.value, true)
      case _                          => None
    loop(t, false)

  private def uniq(base: String, used: Set[String]): (String, Set[String]) =
    val b0 = if reservedFieldNames(base) then base + "_" else base
    val n =
      if !used(b0) then b0 else Iterator.from(2).map(i => b0 + i).find(n => !used(n)).getOrElse(b0)
    (esc(n), used + n)

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
  ): Build[Vector[ArrItem]] =
    es.zipWithIndex
      .foldLeftM((Set.empty[String], Vector.empty[ArrItem])) { case ((used, items), (e, idx)) =>
        e.value match
          case v if literalCode(v).isDefined && e.occ.isOnce =>
            val (check, write) = literalCode(v).get
            pure((used, items :+ ArrItem.Literal(check, write)))
          case RT.GroupRef(g) =>
            if !e.occ.isOnce then unsupported(ctx, UnsupportedFeature.RepeatedGroupReference)
            else
              val (name, names) =
                uniq(e.label.map(camel).orElse(fieldBase(e.value)).getOrElse("group"), used)
              refName(g, o)
                .map(gn => (names, items :+ ArrItem.Group(FieldInfo(name, gn, gn, name), gn)))
          case v =>
            if !(e.occ.isOnce || e.occ.isOptional) then
              unsupported(ctx, UnsupportedFeature.FixedArrayOccurrence(e.occ))
            else
              val configured = cfg.fields.get((q, idx.toString))
              val (name, names) = uniq(
                configured.orElse(e.label.map(camel)).orElse(fieldBase(v)).getOrElse("value"),
                used
              )
              val path = e.label
                .orElse(v match
                  case RT.Ref(r) => Some(r.name)
                  case _         => None)
                .getOrElse(name)
              tc(v, hint + pascal(name), o, ctx).map { t =>
                val item = ArrItem.Field(
                  FieldInfo(
                    name,
                    if e.occ.isOptional then s"Option[${t.tpe}]" else t.tpe,
                    t.codec,
                    path
                  ),
                  e.occ.isOptional
                )
                (names, items :+ item)
              }
      }
      .map(_._2)

  private def mapItems(
      es: Vector[REntry],
      o: Owner,
      q: QName,
      ctx: String,
      hint: String
  ): Build[Vector[MapItem]] =
    es.foldLeftM((Set.empty[String], Vector.empty[MapItem])) { case ((used, items), e) =>
      e.key match
        case Some(k @ (RKey.IntKey(_) | RKey.TextKey(_))) =>
          val (keyText, keyCode) = k match
            case RKey.IntKey(n)  => (n.toString, s"rt.MapKey.IntKey(${n}L)")
            case RKey.TextKey(s) => (s, s"rt.MapKey.TextKey(${quote(s)})")
          if !(e.occ.isOnce || e.occ.isOptional) then
            unsupported(ctx, UnsupportedFeature.RepeatedLiteralMapKey)
          else
            val base = cfg.fields
              .get((q, keyText))
              .orElse(e.label.map(camel))
              .orElse(k match
                case RKey.TextKey(t) => Some(camel(t))
                case _               => None)
              .orElse(fieldBase(e.value))
              .getOrElse(s"key$keyText")
            val (name, names) = uniq(base, used)
            tc(e.value, hint + pascal(name), o, ctx).map { t =>
              (
                names,
                items :+ MapItem.Literal(
                  FieldInfo(
                    name,
                    if e.occ.isOptional then s"Option[${t.tpe}]" else t.tpe,
                    t.codec,
                    keyText
                  ),
                  keyCode,
                  e.occ.isOptional
                )
              )
            }
        case Some(RKey.OfType(kt, _)) =>
          if e.occ.min > 1 then unsupported(ctx, UnsupportedFeature.MapMinimumAboveOne)
          else
            for
              k <- tc(kt, hint + "Key", o, ctx)
              v <- tc(e.value, hint + "Value", o, ctx)
              named = uniq(fieldBase(kt).getOrElse("entries"), used)
            yield (
              named._2,
              items :+ MapItem.Typed(
                FieldInfo(named._1, s"Vector[(${k.tpe}, ${v.tpe})]", "", named._1),
                k,
                v
              )
            )
        case None => unsupported(ctx, UnsupportedFeature.MapWithoutKey)
    }.map(_._2)

  private def classify(q: QName): Build[Decl] =
    val name = scalaName(q)
    val o = Owner(q.module, name, q)
    reached.rules(q).body match
      case RuleBody.GroupBody(es) =>
        arrayItems(es, o, q, q.toString, name).map(Decl.GroupStruct(q, name, _, o))
      case RuleBody.Type(t) => classifyType(q, name, t, o)

  private def classifyType(q: QName, name: String, t: RT, o: Owner): Build[Decl] = deferred {
    val ctx = q.toString
    def opaque(repr: String, codec: String): Build[Decl] =
      pure(Decl.Opaque(q, name, repr, codec, o))
    val decl: Build[Decl] = t match
      case RT.LitInt(n) if !n.isValidInt => val (tpe, expr) = intRepr(n, n); opaque(tpe, expr)
      case RT.Arr(es) if !isVector(es) =>
        arrayItems(es, o, q, ctx, name).map(items =>
          Decl.Struct(q, name, Shape.Arr(items), cfg.preserve(q), o)
        )
      case RT.MapT(es) if !isPairs(es) =>
        mapItems(es, o, o.rule, ctx, name).map(items =>
          Decl.Struct(q, name, Shape.Map(items), cfg.preserve(q), o)
        )
      case RT.Choice(alts) if isPlainChoice(alts) =>
        enumCases(q, name, alts, o).map(Decl.Enum(q, name, _, o))
      case RT.Choice(alts) if intLits(alts).isDefined =>
        opaque("Int", s"rt.Codec.intIn(${intLits(alts).get.mkString(", ")})")
      case RT.Prim(PrimKind.Uint)                         => opaque("BigInt", "rt.Codec.uint")
      case RT.Prim(PrimKind.Int) | RT.Prim(PrimKind.Nint) => opaque("BigInt", "rt.Codec.int")
      case RT.Prim(PrimKind.Bytes) =>
        opaque(
          "rt.Bytes",
          cfg.chunks.get(q).fold("rt.Codec.bytes")(limit => s"rt.Codec.chunkedBytes($limit)")
        )
      case RT.Prim(PrimKind.Text)       => opaque("String", "rt.Codec.text")
      case RT.Prim(PrimKind.Bool)       => opaque("Boolean", "rt.Codec.bool")
      case RT.IntRange(lo, hi)          => val (tpe, expr) = intRepr(lo, hi); opaque(tpe, expr)
      case RT.LitInt(n) if n.isValidInt => opaque("Int", s"rt.Codec.intIn($n)")
      case RT.SizedBytes(lo, hi)        => opaque("rt.Bytes", s"rt.Codec.bytesSized($lo, $hi)")
      case RT.SizedText(lo, hi)         => opaque("String", s"rt.Codec.textSized($lo, $hi)")
      case RT.Unsupported(f)            => unsupported(ctx, f)
      case other =>
        for
          i <- tc(other, name + "Content", o, ctx)
          owner <- data(o)
        yield Decl.Alias(q, name, i.tpe, unhoist(i.codec, owner), o)
    decl.flatMap { d =>
      if cfg.preserve(q) && !d.isInstanceOf[Decl.Struct] then
        unsupported(ctx, UnsupportedFeature.PreserveNonStruct)
      else pure(d)
    }
  }

  private def unhoist(codec: String, o: OwnerData): String =
    Emitter.unhoist(codec, o.hoists)

  private lazy val moduleTypeNames = reached.order
    .filterNot(q => cfg.external(q) || inlined(q))
    .groupMap(_.module)(scalaName)
    .map((m, ns) => m -> ns.toSet)
    .withDefaultValue(Set.empty)

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

  private def enumCases(q: QName, name: String, alts: Vector[RT], o: Owner): Build[Vector[CaseD]] =
    val names = cfg.cases.getOrElse(q, Vector.empty)
    val strip = cfg.strip.getOrElse(q, Vector.empty)
    alts.zipWithIndex
      .foldLeftM((Set.empty[String], Vector.empty[CaseD])) { case ((used, cases), (alt, i)) =>
        def pick(derived: Option[String]): (String, Set[String]) =
          val n = names
            .lift(i)
            .orElse(
              derived.map(d => pascal(strip.find(d.startsWith).fold(d)(p => d.stripPrefix(p))))
            )
            .getOrElse(s"Alt$i")
          uniq(if moduleTypeNames(q.module)(n) || reservedTypes(n) then n + "Case" else n, used)
        val ctx = s"$q alternative $i"
        val derived = alt match
          case RT.Ref(r)                                       => Some(r.name)
          case RT.Arr(Vector(REntry(_, _, _, RT.GroupRef(g)))) => Some(g.name)
          case _                                               => kindName(alt)
        // Struct alternatives historically use no kind name; keep that output contract.
        val picked = alt match
          case RT.MapT(es) if !isPairs(es) => pick(None)
          case RT.Arr(es) if !isVector(es) =>
            pick(derived.filter(_ => es.size == 1 && es.head.value.isInstanceOf[RT.GroupRef]))
          case _ => pick(derived)
        val (cn, next) = picked
        val result: Build[CaseD] = alt match
          case RT.Ref(r) if inlined(r) =>
            reached.rules(r).body match
              case RuleBody.Type(RT.Arr(es)) =>
                arrayItems(es, o, r, ctx, name + cn)
                  .map(items => CaseD.Struct(cn, Shape.Arr(items)))
              case RuleBody.Type(RT.MapT(es)) =>
                mapItems(es, o, r, ctx, name + cn).map(items => CaseD.Struct(cn, Shape.Map(items)))
              case _ => unsupported(ctx, UnsupportedFeature.InlineNonStruct)
          case RT.Arr(es) if !isVector(es) =>
            arrayItems(es, o, q, ctx, name + cn).map(items => CaseD.Struct(cn, Shape.Arr(items)))
          case RT.MapT(es) if !isPairs(es) =>
            mapItems(es, o, q, ctx, name + cn).map(items => CaseD.Struct(cn, Shape.Map(items)))
          case RT.Prim(PrimKind.Nil) => pure(CaseD.Null(cn))
          case other =>
            tc(other, name + cn, o, ctx).map { t =>
              val field = uniq(fieldBase(other).getOrElse("value"), Set.empty)._1
              CaseD.Wrap(cn, FieldInfo(field, t.tpe, t.codec, "value"))
            }
        result.map(c => (next, cases :+ c))
      }
      .map(_._2)

  private def lines(parts: Vector[String], ind: String = ""): String =
    parts.map(ind + _ + "\n").mkString

  private def isNumber(s: String): Boolean = s.nonEmpty && s.forall(_.isDigit)

  private def renderArrayDecode(
      items: Vector[ArrItem],
      build: String,
      node: String,
      ind: String,
      withXs: Boolean = true,
      from: String = "0",
      finish: Option[String] = None
  ): String =
    def sum(terms: Vector[String]): String = if terms.isEmpty then "0" else terms.mkString(" + ")
    def bound(maximum: Boolean): String = sum(items.map {
      case ArrItem.Group(_, g)    => s"$g.${if maximum then "MaxArity" else "MinArity"}"
      case ArrItem.Field(_, true) => if maximum then "1" else "0"
      case _                      => "1"
    })
    val fieldNames = fieldsOf(Shape.Arr(items)).map(_.name).toSet
    def position(index: Int): String =
      Iterator
        .from(0)
        .map(n => s"position$index" + (if n == 0 then "" else "_" + n))
        .find(name => !fieldNames(name))
        .getOrElse(s"position${index}_")
    val (end, positioned) =
      items.zipWithIndex.foldLeft((from, Vector.empty[(ArrItem, String, String)])) {
        case ((p, acc), (item, index)) =>
          val after = item match
            case ArrItem.Field(_, true) | ArrItem.Group(_, _) => position(index)
            case _ => if isNumber(p) then (p.toInt + 1).toString else s"$p + 1"
          (after, acc :+ ((item, p, after)))
      }
    val completed = finish.fold(s"rt.Arr.end(xs, $end).map(_ => $build)")(f => s"$f($build, $end)")
    val expression = positioned.foldRight(completed) { case ((item, p, after), tail) =>
      val nested = tail.linesIterator.map("  " + _).mkString("\n")
      item match
        case ArrItem.Field(f, true) =>
          s"rt.Arr.optional(xs, $p, ${f.codec}, ${quote(f.path)}) { (${f.name}, $after) =>\n$nested\n}"
        case ArrItem.Field(f, false) =>
          s"rt.Arr.at(xs, $p)(${f.codec}.decode).at(${quote(f.path)}).flatMap { ${f.name} =>\n$nested\n}"
        case ArrItem.Literal(check, _) =>
          val checked = check.replace("xs(@)", "value").replace("@", p)
          s"rt.Arr.at(xs, $p)(value => $checked).flatMap { _ =>\n$nested\n}"
        case ArrItem.Group(f, g) =>
          s"$g.decodeWith(xs, $p) { (${f.name}, $after) =>\n$nested\n}"
    }
    if withXs then
      val prefix = s"rt.Arr.between($node, ${bound(false)}, ${bound(true)}).flatMap { xs =>"
      lines(Vector(prefix) ++ expression.linesIterator.map("  " + _).toVector :+ "}", ind)
    else lines(expression.linesIterator.toVector, ind)

  private def renderArrayEncode(
      items: Vector[ArrItem],
      acc: FieldInfo => String,
      ind: String,
      head: Boolean = true
  ): String =
    val start =
      if !head then Vector.empty
      else
        val fixed = items.count {
          case ArrItem.Literal(_, _) | ArrItem.Field(_, false) => true; case _ => false
        }
        val groups = items.collect { case ArrItem.Group(f, g) => s"$g.arity(${acc(f)})" }
        val opts = items.collect { case ArrItem.Field(f, true) =>
          s"(if ${acc(f)}.isDefined then 1 else 0)"
        }
        val terms = (if fixed > 0 || (groups.isEmpty && opts.isEmpty) then Vector(fixed.toString)
                     else Vector.empty) ++ groups ++ opts
        Vector(s"out.arrayHead(${terms.mkString(" + ")})")
    lines(
      start ++ items.map {
        case ArrItem.Literal(_, write) => write
        case ArrItem.Field(f, false)   => s"${f.codec}.encode(${acc(f)}, out)"
        case ArrItem.Field(f, true)    => s"${acc(f)}.foreach(${f.codec}.encode(_, out))"
        case ArrItem.Group(f, g)       => s"$g.encodeInto(${acc(f)}, out)"
      },
      ind
    )

  private def renderMapDecode(
      items: Vector[MapItem],
      build: String,
      node: String,
      spec: String,
      ind: String
  ): String =
    val (_, _, body) = items.foldLeft((0, 0, Vector.empty[String])) { case ((li, ti, out), item) =>
      item match
        case MapItem.Literal(f, _, opt) =>
          (
            li + 1,
            ti,
            out :+ s"  ${f.name} <- parts.${if opt then "optional" else "required"}($li, ${f.codec})"
          )
        case MapItem.Typed(f, k, v) =>
          (li, ti + 1, out :+ s"  ${f.name} <- parts.entries($ti, ${k.codec}, ${v.codec})")
    }
    lines(Vector("for", s"  parts <- rt.Maps.split($node, $spec)") ++ body :+ s"yield $build", ind)

  private def renderMapSpec(items: Vector[MapItem], name: String, ind: String): String =
    val lits = items.collect { case l: MapItem.Literal => l.keyCode }
    val typed = items.collect { case t: MapItem.Typed => s"k => ${t.key.codec}.decode(k).isRight" }
    s"${ind}private lazy val $name = new rt.MapSpec(\n$ind  Array[rt.MapKey](${lits.mkString(", ")}),\n$ind  Array[rt.Node => Boolean](${typed.mkString(", ")})\n$ind)\n"

  private def renderMapEncode(
      items: Vector[MapItem],
      acc: FieldInfo => String,
      ind: String
  ): String =
    val req = items.count { case MapItem.Literal(_, _, false) => true; case _ => false }
    val dyn = items.collect {
      case MapItem.Literal(f, _, true) => s"(if ${acc(f)}.isDefined then 1 else 0)"
      case MapItem.Typed(f, _, _)      => s"${acc(f)}.size"
    }
    val body = items.flatMap {
      case MapItem.Literal(f, key, opt) =>
        val writeKey =
          if key.startsWith("rt.MapKey.IntKey(") then
            s"out.uint(${key.stripPrefix("rt.MapKey.IntKey(").stripSuffix(")")})"
          else s"out.text(${key.stripPrefix("rt.MapKey.TextKey(").stripSuffix(")")})"
        if opt then Vector(s"${acc(f)}.foreach { v => $writeKey; ${f.codec}.encode(v, out) }")
        else Vector(writeKey, s"${f.codec}.encode(${acc(f)}, out)")
      case MapItem.Typed(f, k, v) =>
        Vector(
          s"${acc(f)}.foreach { (k, v) => ${k.codec}.encode(k, out); ${v.codec}.encode(v, out) }"
        )
    }
    lines(Vector(s"out.mapHead(${(req.toString +: dyn).mkString(" + ")})") ++ body, ind)

  private def params(items: Vector[FieldInfo]): String =
    items.map(f => s"${f.name}: ${f.tpe}").mkString(", ")
  private def args(items: Vector[FieldInfo]): String = items.map(_.name).mkString(", ")

  private def fieldsOf(s: Shape): Vector[FieldInfo] = s match
    case Shape.Arr(is) =>
      is.collect { case ArrItem.Field(f, _) => f; case ArrItem.Group(f, _) => f }
    case Shape.Map(is) =>
      is.collect { case MapItem.Literal(f, _, _) => f; case MapItem.Typed(f, _, _) => f }

  private def hoistLines(o: Owner, owners: Owners): String =
    owners
      .getOrElse(o, OwnerData())
      .hoists
      .map((n, tpe, expr) => s"  private lazy val $n: rt.Codec[$tpe] = $expr\n")
      .mkString

  private def encodeShape(shape: Shape, accessor: FieldInfo => String, ind: String): String =
    shape match
      case Shape.Arr(items) => renderArrayEncode(items, accessor, ind)
      case Shape.Map(items) => renderMapEncode(items, accessor, ind)

  private def decodeShape(shape: Shape, build: String, spec: String): String = shape match
    case Shape.Arr(items) => renderArrayDecode(items, build, "node", "    ")
    case Shape.Map(items) => renderMapDecode(items, build, "node", spec, "    ")

  private def specShape(shape: Shape, name: String): String = shape match
    case Shape.Map(items) => renderMapSpec(items, name, "  ")
    case _                => ""

  private def renderDecl(d: Decl, owners: Owners): String =
    val doc = s"/** CDDL rule `${d.q}`. */"
    d match
      case Decl.Opaque(_, name, repr, codec, _) =>
        lines(
          Vector(
            doc,
            s"opaque type $name = $repr",
            s"object $name extends rt.Codec[$name]:",
            s"  private val codec: rt.Codec[$repr] = $codec",
            s"  def from(value: $repr): Either[rt.ConstraintViolation, $name] =",
            "    codec.check(value) match",
            "      case None => Right(value)",
            s"      case Some(violation) => Left(violation.copy(typeName = ${quote(name)}, actual = String.valueOf(value)))",
            s"  def unsafe(value: $repr): $name = value",
            s"  extension (self: $name) def value: $repr = self",
            s"  def decode(node: rt.Node): rt.Decoded[$name] = codec.decode(node)",
            s"  def encode(a: $name, out: rt.Writer): Unit = codec.encode(a, out)",
            s"  override def check(a: $name): Option[rt.ConstraintViolation] = codec.check(a)"
          )
        )
      case Decl.Alias(_, name, tpe, codec, _) =>
        lines(Vector(doc, s"type $name = $tpe", s"lazy val $name: rt.Codec[$tpe] = $codec"))
      case Decl.Struct(_, name, shape, preserve, o) =>
        val fields = fieldsOf(shape)
        val build =
          if preserve then s"new $name(${args(fields)})(node.original)"
          else s"new $name(${args(fields)})"
        val header = lines(
          Vector(
            if preserve then
              s"/** CDDL rule `${d.q}`. `raw` is the exact encoding it was decoded from (hashed or signed by the spec). */"
            else doc,
            s"final case class $name(${params(fields)})${
                if preserve then "(val raw: rt.Bytes)" else ""
              }",
            s"object $name extends rt.Codec[$name]:"
          )
        )
        val encoder =
          if preserve then
            lines(
              Vector(
                s"  def encode(a: $name, out: rt.Writer): Unit = out.raw(a.raw)",
                "  /** Build a value from its members; `raw` is their canonical encoding. */",
                s"  def canonical(${params(fields)}): $name =",
                s"    val fresh = new $name(${args(fields)})(rt.EmptyBytes)",
                "    val w = new rt.Writer",
                "    writeFields(fresh, w)",
                s"    new $name(${args(fields)})(w.result)",
                s"  private def writeFields(a: $name, out: rt.Writer): Unit ="
              )
            )
          else lines(Vector(s"  def encode(a: $name, out: rt.Writer): Unit ="))
        header + hoistLines(o, owners) + specShape(shape, "spec") +
          lines(Vector(s"  def decode(node: rt.Node): rt.Decoded[$name] =")) +
          decodeShape(shape, build, "spec") + encoder + encodeShape(
            shape,
            f => s"a.${f.name}",
            "    "
          )
      case Decl.GroupStruct(_, name, items, o) =>
        val fields = fieldsOf(Shape.Arr(items))
        def arity(maximum: Boolean): String = items
          .map {
            case ArrItem.Group(_, g)    => s"$g.${if maximum then "MaxArity" else "MinArity"}"
            case ArrItem.Field(_, true) => if maximum then "1" else "0"
            case _                      => "1"
          }
          .mkString(" + ")
        val actual = items
          .map {
            case ArrItem.Group(f, g)    => s"$g.arity(a.${f.name})"
            case ArrItem.Field(f, true) => s"(if a.${f.name}.isDefined then 1 else 0)"
            case _                      => "1"
          }
          .mkString(" + ")
        lines(
          Vector(
            doc,
            "/** A group rule: its members are spliced into the enclosing array, not wrapped in an array of their own. */",
            s"final case class $name(${params(fields)})",
            s"object $name:"
          )
        ) + hoistLines(o, owners) +
          lines(
            Vector(
              s"  val MinArity: Int = ${arity(false)}",
              s"  val MaxArity: Int = ${arity(true)}",
              s"  def arity(a: $name): Int = $actual",
              s"  def decodeWith[A](xs: Vector[rt.Node], from: Int)(next: ($name, Int) => rt.Decoded[A]): rt.Decoded[A] ="
            )
          ) +
          renderArrayDecode(
            items,
            s"$name(${args(fields)})",
            "node",
            "    ",
            withXs = false,
            from = "from",
            finish = Some("next")
          ) +
          lines(Vector(s"  def encodeInto(a: $name, out: rt.Writer): Unit =")) +
          renderArrayEncode(items, f => s"a.${f.name}", "    ", head = false)
      case Decl.Enum(_, name, cases, o) =>
        val definitions = cases.map {
          case CaseD.Struct(cn, shape) =>
            val fs = fieldsOf(shape)
            if fs.isEmpty then s"  case $cn" else s"  case $cn(${params(fs)})"
          case CaseD.Null(cn)    => s"  case $cn"
          case CaseD.Wrap(cn, f) => s"  case $cn(${params(Vector(f))})"
        }
        val specs = cases.map {
          case CaseD.Struct(cn, shape) => specShape(shape, s"spec$cn"); case _ => ""
        }.mkString
        val decoders = cases.map {
          case CaseD.Struct(cn, shape) =>
            val fs = fieldsOf(shape)
            val build = if fs.isEmpty then cn else s"$cn(${args(fs)})"
            lines(
              Vector(s"  private def decode$cn(node: rt.Node): rt.Decoded[$name] =")
            ) + decodeShape(shape, build, s"spec$cn")
          case CaseD.Null(cn) =>
            lines(
              Vector(
                s"  private def decode$cn(node: rt.Node): rt.Decoded[$name] = rt.Lit.nul(node).map(_ => $cn)"
              )
            )
          case CaseD.Wrap(cn, f) =>
            lines(
              Vector(
                s"  private def decode$cn(node: rt.Node): rt.Decoded[$name] = ${f.codec}.decode(node).map($cn(_))"
              )
            )
        }.mkString
        val encoders = cases.map {
          case CaseD.Struct(cn, shape) =>
            val fs = fieldsOf(shape)
            lines(
              Vector(if fs.isEmpty then s"    case $cn =>" else s"    case $cn(${args(fs)}) =>")
            ) + encodeShape(shape, _.name, "      ")
          case CaseD.Null(cn) => lines(Vector(s"    case $cn => out.nul()"))
          case CaseD.Wrap(cn, f) =>
            lines(Vector(s"    case $cn(${f.name}) => ${f.codec}.encode(${f.name}, out)"))
        }.mkString
        lines(
          Vector(doc, s"enum $name:") ++ definitions :+ s"object $name extends rt.Codec[$name]:"
        ) +
          hoistLines(o, owners) + specs + lines(
            Vector(
              s"  private lazy val alternatives = new rt.Alternatives[$name](",
              s"    Array[rt.Node => rt.Decoded[$name]](${cases.map(c => s"decode${c.name}").mkString(", ")})",
              "  )",
              s"  def decode(node: rt.Node): rt.Decoded[$name] = alternatives(node)"
            )
          ) +
          decoders + lines(
            Vector(s"  def encode(a: $name, out: rt.Writer): Unit = a match")
          ) + encoders

  private def flatten(d: Decl, owners: Owners): Vector[Decl] =
    @scala.annotation.tailrec
    def loop(todo: List[Decl], out: Vector[Decl]): Vector[Decl] = todo match
      case Nil       => out
      case h :: tail => loop(owners.getOrElse(h.owner, OwnerData()).synth.toList ::: tail, out :+ h)
    loop(List(d), Vector.empty)

  def generate(): Either[GenError, Map[String, String]] =
    val unreachable = (cfg.preserve ++ cfg.cases.keySet ++ cfg.chunks.keySet).find(q =>
      !reached.seen(q) && q.module.nonEmpty && schema.lookup(q).isDefined
    )
    val build = unreachable match
      case Some(q) => fail[Vector[Decl]](GenError.BadConfig(0, ConfigProblem.UnreachableRule(q)))
      case None    => reached.order.filterNot(q => cfg.external(q) || inlined(q)).traverse(classify)
    build.run(Map.empty).value.value.flatMap { (owners, decls) =>
      val byModule = decls.flatMap(flatten(_, owners)).groupMap(_.q.module)(identity)
      val clash = byModule.toVector.flatMap { (m, ds) =>
        ds.groupBy(_.name).collect {
          case (n, same) if same.size > 1 =>
            GenError.NameClash(m, n, same.map(_.q.toString))
        }
      }.headOption
      clash match
        case Some(e) => Left(e)
        case None =>
          Right(byModule.map { (module, ds) =>
            val imports = ds
              .flatMap(d => owners.getOrElse(d.owner, OwnerData()).imports)
              .distinct
              .filterNot(_ == module)
            val text = "// SPDX-License-Identifier: Apache-2.0\n" +
              s"// Generated by scarab-models-gen from the CDDL module `$module` (${specs(module).files.mkString(", ")}). Do not edit.\n" +
              s"package ${packageOf(module)}\n\n" +
              s"import ${cfg.basePackage}.runtime as rt\n" + "import rt.at\n" +
              imports.map(m => s"import ${packageOf(m)}\n").mkString + "\n" +
              ds.map(d => renderDecl(d, owners) + "\n").mkString
            s"${packageOf(module).replace('.', '/')}/${pascal(module.replace('.', '_'))}.scala" -> text
          })
    }

object Emitter:
  private val hoistReference = "\\bc\\d+\\b".r

  private def substituteHoists(
      source: String,
      expanded: Map[String, Chain[String]]
  ): Chain[String] =
    val (offset, fragments) = hoistReference
      .findAllMatchIn(source)
      .foldLeft(
        (0, Chain.empty[String])
      ) { case ((cursor, result), matched) =>
        val unchanged = Chain.one(source.substring(cursor, matched.start))
        val replacement = expanded.getOrElse(matched.matched, Chain.one(matched.matched))
        (matched.end, result ++ unchanged ++ replacement)
      }
    fragments ++ Chain.one(source.substring(offset))

  private[gen] def unhoist(codec: String, hoists: Vector[(String, String, String)]): String =
    // These entries are the substitutions the old reverse fold would still visit.
    val expanded = hoists.foldLeft(Map.empty[String, Chain[String]]) {
      case (known, (name, _, expression)) =>
        known.updated(name, substituteHoists(expression, known))
    }
    substituteHoists(codec, expanded).iterator.mkString

  private[gen] def isVector(es: Vector[REntry]): Boolean =
    es.size == 1 && es.head.key.isEmpty && !es.head.value.isInstanceOf[RT.GroupRef] &&
      (es.head.occ.max.forall(_ > 1) || es.head.occ.min > 1)

  private[gen] def isPairs(es: Vector[REntry]): Boolean =
    es.size == 1 && es.head.occ.max.isEmpty && (es.head.key match
      case Some(RKey.OfType(_, _)) => true
      case _                       => false)

  private[gen] val nil: RT = RT.Prim(PrimKind.Nil)

  private[gen] def intLits(alts: Vector[RT]): Option[Vector[BigInt]] =
    val xs = alts.collect { case RT.LitInt(n) if n.isValidInt => n }
    Option.when(xs.size == alts.size && alts.nonEmpty)(xs)

  /** `#6.n(x) / x`: the tag is optional on input (Conway sets), written on output. */
  private[gen] def maybeTagged(alts: Vector[RT]): Option[(BigInt, RT)] = alts match
    case Vector(RT.Tagged(n, a), b) if a == b => Some((n, a))
    case Vector(b, RT.Tagged(n, a)) if a == b => Some((n, a))
    case _                                    => None

  private[gen] def mergeableVectors(alts: Vector[RT]): Boolean =
    alts.size > 1 && alts.forall {
      case RT.Arr(es) => isVector(es)
      case _          => false
    } && alts.collect { case RT.Arr(es) => es.head.value }.distinct.size == 1

  /** A choice that needs an enum: it has alternatives that are not just a single wrapper type. */
  private[gen] def isPlainChoice(alts: Vector[RT]): Boolean =
    val nullable = alts.size == 2 && alts.contains(nil)
    !nullable && intLits(alts).isEmpty && !mergeableVectors(alts) && maybeTagged(alts).isEmpty

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
  final case class Owner(module: String, name: String, rule: QName)
  final case class OwnerData(
      hoists: Vector[(String, String, String)] = Vector.empty,
      imports: Vector[String] = Vector.empty,
      synth: Vector[Decl] = Vector.empty
  )
  private[gen] final case class Reach(
      order: Vector[QName] = Vector.empty,
      seen: Set[QName] = Set.empty,
      rules: Map[QName, RuleDef] = Map.empty,
      total: Map[QName, Int] = Map.empty,
      alternatives: Map[QName, Int] = Map.empty
  )

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
