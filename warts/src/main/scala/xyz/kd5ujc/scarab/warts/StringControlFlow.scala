// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.warts

import org.wartremover.{WartTraverser, WartUniverse}

/** A `String` that decides control flow (STR-3): an equality of a `String` (`==`, `!=`, `equals`,
  * `equalsIgnoreCase`, the cats `===` and `=!=`) with a `String` or a supertype of it, a
  * `startsWith`, `endsWith`, `contains` or `matches` on a string, a regex match, a `match` on a
  * string with literal string patterns, and a lookup in a `Map[String, _]` or a `Set[String]` by a
  * key that is not a literal. Each message starts with the kind of site, so counts split by it.
  *
  * Not sites:
  *   - an equality of two values of an opaque type over `String` (`JsonKey`), and a lookup in a map
  *     keyed by one: typed values; an opaque value compared with a raw `String` is a site;
  *   - in the body of `equals`, and of a function of two values of one type, not `String`, to
  *     `Boolean` (`sameLabel(x: Value, y: Value)`), an equality of two Strings neither of which is
  *     a literal: the type's own equality; a comparison with a literal, and the other kinds, are
  *     sites there;
  *   - the condition of an `if`, and a `match`, whose value is a `String` and whose every branch is
  *     built from a String the condition reads (`if node.contains('.') then node else
  *     node.take(8)`): it chooses between forms of the same text;
  *   - the lookup of an upsert, `m.updated(k, … m.get(k) …)` with the same path `m` and key `k`,
  *     which is `updatedWith`;
  *   - a macro implementation (a definition that takes `Quotes`), which runs in the compiler;
  *   - code the compiler made (an enum's `valueOf`); a lambda's body is user code, although its
  *     owner is synthetic too.
  *
  * Not seen: an eta-expanded `s.equals`; in an equality definition, a comparison with a constant
  * that is not a literal; and a lookup by a raw `String` in a map keyed by an opaque type, in its
  * scope.
  *
  * A definition that carries the wart's full name in `@SuppressWarnings` is skipped with all it
  * holds; a call to it is checked as any other, its arguments too.
  */
object StringControlFlow extends WartTraverser:

  private val equalities = Set("==", "!=", "equals", "equalsIgnoreCase")
  private val catsEqualities = Set("===", "=!=")
  private val predicates = Set("startsWith", "endsWith", "contains", "matches")
  private val lookups = Set("get", "apply", "contains", "getOrElse")
  private val regexOwners = Set(
    "scala.util.matching.Regex",
    "scala.util.matching.UnanchoredRegex",
    "java.util.regex.Pattern",
    "java.util.regex.Matcher"
  )
  private val regexMatches = Set(
    "findFirstIn",
    "findFirstMatchIn",
    "findAllIn",
    "findAllMatchIn",
    "findPrefixOf",
    "findPrefixMatchOf",
    "matches",
    "unapplySeq",
    "matcher",
    "find",
    "lookingAt"
  )

  def apply(u: WartUniverse): u.Traverser =
    new u.Traverser(this):
      import q.reflect.*

      private val string = TypeRepr.of[String]
      private val stringOps = TypeRepr.of[scala.collection.StringOps]
      private val mapClass = TypeRepr.of[scala.collection.Map[Any, Any]].typeSymbol
      private val setClass = TypeRepr.of[scala.collection.Set[Any]].typeSymbol

      private def opaque(t: TypeRepr): Boolean =
        t.widen.dealiasKeepOpaques match
          case r: TypeRef => r.typeSymbol.flags.is(Flags.Opaque)
          case _          => false

      private def stringType(t: TypeRepr): Boolean =
        val w = t.widen
        !opaque(w) && w <:< string && !(w <:< TypeRepr.of[Null]) && !(w =:= TypeRepr.of[Nothing])

      private def isString(t: Term): Boolean = stringType(t.tpe)

      /** A `String`, a value of an opaque type, or a supertype of `String` such as `Any`: what a
        * `String` can be compared with.
        */
      private def comparable(t: Term): Boolean =
        val w = t.tpe.widen
        !(w <:< TypeRepr.of[Null]) && !(w =:= TypeRepr.of[Nothing]) &&
        (w <:< string || string <:< w || opaque(w))

      /** The value a syntax conversion wraps (`catsSyntaxEq(s)(eq)` is `s`). */
      @scala.annotation.tailrec
      private def wrapped(t: Term): Option[Term] = t match
        case Apply(_, List(a)) if comparable(a) => Some(a)
        case Apply(f, _)                        => wrapped(f)
        case TypeApply(f, _)                    => wrapped(f)
        case _                                  => None

      /** An equality of a `String` that is not an opaque value with something it can equal. */
      private def textEquality(lhs: Term, rhs: Term): Boolean =
        (isString(lhs) && comparable(rhs)) || (isString(rhs) && comparable(lhs))

      private def stringKeyed(t: Term): Boolean =
        val w = t.tpe.widen
        def keyOf(base: Symbol): Option[TypeRepr] =
          w.baseType(base) match
            case AppliedType(_, key :: _) => Some(key)
            case _                        => None
        !opaque(w) && !(w =:= TypeRepr.of[Nothing]) && !(w <:< TypeRepr.of[Null]) &&
        keyOf(mapClass).orElse(keyOf(setClass)).exists(stringType)

      private def isLiteral(t: Term): Boolean =
        Trees.strip(t) match
          case Literal(_) => true
          case _          => false

      private def stringPattern(p: Tree): Boolean = p match
        case Literal(StringConstant(_)) => true
        case Alternatives(ps)           => ps.exists(stringPattern)
        case Bind(_, b)                 => stringPattern(b)
        case _                          => false

      /** The body of `equals`, or of a function of two values of one type other than `String` to
        * `Boolean`: there, an equality of two Strings neither of which is a literal implements the
        * type's equality.
        */
      private def equalityDefinition(d: DefDef): Boolean =
        val explicit = d.termParamss.filterNot(_.isImplicit).flatMap(_.params)
        d.name == "equals" || (d.returnTpt.tpe =:= TypeRepr.of[Boolean] && (explicit match
          case List(a, b) =>
            a.tpt.tpe =:= b.tpt.tpe && !stringType(a.tpt.tpe) && !opaque(a.tpt.tpe) && !opaque(
              b.tpt.tpe
            )
          case _ => false))

      private def macroImplementation(d: DefDef): Boolean =
        d.termParamss.exists(_.params.exists(_.tpt.tpe <:< TypeRepr.of[scala.quoted.Quotes]))

      /** A stable path (`a`, `a.table`, `this.table`) as the symbols along it, outermost last. */
      private def stable(t: Term): Option[List[Symbol]] =
        Trees.strip(t) match
          case i: Ident                 => Some(List(i.symbol))
          case s @ Select(qualifier, _) => stable(qualifier).map(s.symbol :: _)
          case th: This                 => Some(List(th.symbol))
          case _                        => None

      /** The `String` values a tree reads by name: its references of type `String`. */
      private object stringsRead extends TreeAccumulator[Set[Symbol]]:
        def foldTree(found: Set[Symbol], tree: Tree)(owner: Symbol): Set[Symbol] = tree match
          case r: Ref if stringType(r.tpe) => foldOverTree(found + r.symbol, tree)(owner)
          case _                           => foldOverTree(found, tree)(owner)

      /** An `if` whose value is a `String` and whose branches are each built from a String its
        * condition reads (`if node.contains('.') then node else node.take(8)`): it chooses between
        * forms of the same text.
        */
      private def textForm(cond: Term, branches: List[Term]): Boolean =
        val read = stringsRead.foldTree(Set.empty, cond)(Symbol.noSymbol)
        read.nonEmpty && branches.forall(b =>
          stringsRead.foldTree(Set.empty, b)(Symbol.noSymbol).exists(read)
        )

      /** Where a site is: in the body of an equality definition, and in the value of an upsert of a
        * map by a key (both as stable paths).
        */
      private final case class Context(
          equality: Boolean,
          upserts: Set[(List[Symbol], List[Symbol])]
      )

      private def kind(context: Context)(tree: Tree): Option[String] = tree match
        case Apply(Select(lhs, op), List(rhs)) if equalities(op) && textEquality(lhs, rhs) =>
          Option.unless(context.equality && !isLiteral(lhs) && !isLiteral(rhs))("string-equality")
        // the cats syntax `a === b`: `a` is the argument of the conversion to `EqOps`
        case Apply(Select(syntax, op), List(rhs))
            if catsEqualities(op) && wrapped(syntax).exists(textEquality(_, rhs)) =>
          Some("string-equality")
        case Select(recv, m) if predicates(m) && (isString(recv) || recv.tpe.widen <:< stringOps) =>
          Some("string-predicate")
        case s: Select if regexMatches(s.name) && regexOwners(Trees.plainName(s.symbol.owner)) =>
          Some("regex-match")
        case m @ Match(scrutinee, cases)
            if isString(scrutinee) && cases.exists(c => stringPattern(c.pattern)) &&
              !(isString(m) && textForm(scrutinee, cases.map(_.rhs))) =>
          Some("string-match")
        case Apply(Select(recv, m), key :: _)
            if lookups(m) && stringKeyed(recv) && !isLiteral(key) =>
          Option.unless(upsert(context)(recv, key))("string-keyed-lookup")
        case Apply(TypeApply(Select(recv, m), _), key :: _)
            if lookups(m) && stringKeyed(recv) && !isLiteral(key) =>
          Option.unless(upsert(context)(recv, key))("string-keyed-lookup")
        case _ => None

      private def upsert(context: Context)(map: Term, key: Term): Boolean =
        stable(map).zip(stable(key)).exists(context.upserts)

      /** `m.updated(k, v)`, with its type argument or without. */
      private object Updated:
        def unapply(t: Tree): Option[(Term, Term, Term)] = t match
          case Apply(Select(map, "updated"), List(key, value)) => Some((map, key, value))
          case Apply(TypeApply(Select(map, "updated"), _), List(key, value)) =>
            Some((map, key, value))
          case _ => None

      private def walk(context: Context): u.Traverser =
        new u.Traverser(StringControlFlow.this):
          override def traverseTree(tree: Tree)(owner: Symbol): Unit =
            tree match
              case d: Definition if hasWartAnnotation(d) => ()
              case d: DefDef if macroImplementation(d)   => ()
              case d: DefDef if !context.equality && equalityDefinition(d) =>
                walk(context.copy(equality = true)).traverseTree(d)(owner)
              case i @ If(cond, thenp, elsep)
                  if isString(i) && textForm(cond, List(thenp, elsep)) =>
                traverseTree(thenp)(owner)
                traverseTree(elsep)(owner)
              case Updated(map, key, value) if stringKeyed(map) =>
                traverseTree(map)(owner)
                traverseTree(key)(owner)
                stable(map).zip(stable(key)) match
                  case Some(pair) =>
                    walk(context.copy(upserts = context.upserts + pair)).traverseTree(value)(owner)
                  case None => traverseTree(value)(owner)
              case _ if owner.flags.is(Flags.Synthetic) && !owner.isAnonymousFunction =>
                super.traverseTree(tree)(owner)
              case _ =>
                kind(context)(tree).foreach(k => error(tree.pos, s"$k: ${Rules.Str3}"))
                super.traverseTree(tree)(owner)

      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        walk(Context(equality = false, upserts = Set.empty)).traverseTree(tree)(owner)
