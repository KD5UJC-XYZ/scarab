// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.warts

import org.wartremover.{WartTraverser, WartUniverse}

/** The system clock (EFF-6), `unsafeRun*` (EFF-5), and the exceptions ERR-4 refuses for domain
  * errors.
  *
  *   - `wall-clock`: `System.currentTimeMillis` and `nanoTime`, every `now` of a `java.time` type,
  *     `Clock.system*`, `InstantSource.system`, `Calendar.getInstance`, `Deadline.now` and
  *     `new java.util.Date()`.
  *   - `unsafe-run`: a method whose name starts with `unsafeRun` or `unsafeTo` on an `IO`, a
  *     `SyncIO` or a `Dispatcher`.
  *   - `sys-error`, `predef-require`, `predef-assume`, `predef-assert`.
  *   - `raise-constructed`: `raiseError`, `raiseWhen` or `raiseUnless` (on any receiver, through
  *     the cats syntax, or unqualified), or the `fromOption` of `ApplicativeError`, `IO` or
  *     `SyncIO`, of a `Throwable` made for it: by `new`, by a factory (any method call, or a
  *     reference to a method), or held by a `val` bound to one; inside a block, an `if` or a
  *     `match` too.
  *   - `raise-domain-error`: the same methods, and `fromEither`, of a value whose type is one of
  *     ours (defined under `xyz.kd5ujc.scarab`), or of an `Either` whose left side is: a domain
  *     error is raised through cats-mtl `Raise`, never as an exception.
  *
  * A `Throwable` that is passed on as it came (a parameter, a pattern's binder, a field such as a
  * defect's `cause`) may be raised again: that is how a defect reaches the runtime.
  *
  * Not seen: a `Throwable` made in one method and raised in another through a parameter; the
  * raising forms not listed (`liftTo`, `ensure`, `ensureOr`, `rethrow`, `adaptError`); and an
  * assertion the typer folds to a constant `true`. `Predef.assert` is a transparent inline method,
  * so the typer has replaced the call with its body before this wart runs: the site is an `Inlined`
  * from `Predef` whose body calls `Scala3RunTime.assertFailed`.
  */
object SystemTimeAndUnsafeRun extends WartTraverser:

  private val clocks: Set[String] = Set(
    "java.lang.System.currentTimeMillis",
    "java.lang.System.nanoTime",
    "java.time.Clock.systemUTC",
    "java.time.Clock.systemDefaultZone",
    "java.time.Clock.system",
    "java.time.InstantSource.system",
    "java.util.Calendar.getInstance",
    "scala.concurrent.duration.Deadline.now"
  )

  private val throwing: Map[String, String] = Map(
    "scala.sys.package.error" -> "sys-error",
    "scala.Predef.require" -> "predef-require",
    "scala.Predef.assume" -> "predef-assume"
  )

  private val runners: Set[String] =
    Set("cats.effect.IO", "cats.effect.SyncIO", "cats.effect.std.Dispatcher")

  /** The methods that raise what they are given, on any receiver. */
  private val raising: Set[String] = Set("raiseError", "raiseWhen", "raiseUnless")

  /** The methods of `ApplicativeError`, and of the `IO` and `SyncIO` companions, that raise an
    * error they are given.
    */
  private val lifting: Set[String] = Set("fromOption", "fromEither")

  private val errorClasses: Set[String] =
    Set("cats.ApplicativeError", "cats.MonadError", "cats.effect.IO", "cats.effect.SyncIO")

  /** How a raised value came to be, from least to most wrong. */
  private enum Raised:
    case PassedOn, Constructed, Domain

  private def worst(a: Raised, b: Raised): Raised = if a.ordinal >= b.ordinal then a else b

  def apply(u: WartUniverse): u.Traverser =
    new u.Traverser(this):
      import q.reflect.*

      private val throwable = TypeRepr.of[Throwable]

      private def eitherLeft(t: TypeRepr): Option[TypeRepr] =
        t.widen.dealias.baseType(TypeRepr.of[Either[Any, Any]].typeSymbol) match
          case AppliedType(_, List(left, _)) => Some(left)
          case _                             => None

      /** How the value of `t` came to be, given the `val`s known to hold a made `Throwable`. */
      private def raised(made: Set[Symbol])(t: Term): Raised =
        Trees.strip(t) match
          case If(_, a, b) => worst(raised(made)(a), raised(made)(b))
          case Match(_, cases) =>
            cases.map(c => raised(made)(c.rhs)).foldLeft(Raised.PassedOn)(worst)
          case s if Trees.isOurs(s.tpe)                      => Raised.Domain
          case _: Apply | _: TypeApply | _: New              => Raised.Constructed
          case r: Ref if r.symbol.isDefDef || made(r.symbol) => Raised.Constructed
          case _                                             => Raised.PassedOn

      /** Whether `t` is a value a raising method could raise: a `Throwable`, one of ours, or an
        * `Either`.
        */
      private def candidate(t: Term): Boolean =
        t.tpe <:< throwable || Trees.isOurs(t.tpe) || eitherLeft(t.tpe).isDefined

      /** The `val`s of a tree that hold a `Throwable`, or one of ours, made for it. */
      private def madeVals(root: Tree, owner: Symbol): Set[Symbol] =
        val accumulator = new TreeAccumulator[Set[Symbol]]:
          def foldTree(found: Set[Symbol], tree: Tree)(o: Symbol): Set[Symbol] =
            tree match
              case v @ ValDef(_, tpt, Some(rhs))
                  if (tpt.tpe <:< throwable || Trees
                    .isOurs(tpt.tpe)) && raised(found)(rhs) != Raised.PassedOn =>
                foldOverTree(found + v.symbol, tree)(o)
              case _ => foldOverTree(found, tree)(o)
        accumulator.foldTree(Set.empty, root)(owner)

      private def isClock(s: Symbol): Boolean =
        val name = Trees.plainName(s)
        clocks(name) || (s.name == "now" && Trees.plainName(s.maybeOwner).startsWith("java.time."))

      /** The type of the receiver of a reference, written (`d.unsafeRunSync`) or imported
        * (`unsafeRunSync`).
        */
      private def receiver(r: Ref): Option[TypeRepr] = r match
        case Select(qualifier, _) => Some(qualifier.tpe)
        case _ =>
          r.tpe match
            case TermRef(prefix, _) => Some(prefix)
            case _                  => None

      private def unsafeRunner(r: Ref): Boolean =
        val name = r.symbol.name
        (name.startsWith("unsafeRun") || name.startsWith("unsafeTo")) &&
        receiver(r).exists(_.widen.baseClasses.exists(c => runners(Trees.plainName(c))))

      private def raisingCall(f: Term): Boolean =
        Trees.callee(f).exists { r =>
          val s = r.symbol
          raising(s.name) || (lifting(s.name) && errorClasses(Trees.plainName(s.maybeOwner)))
        }

      private def implicitClause(f: Term): Boolean =
        f.tpe.widen match
          case m: MethodType => m.isImplicit
          case _             => false

      private object callsAssertFailed extends TreeAccumulator[Boolean]:
        def foldTree(found: Boolean, tree: Tree)(owner: Symbol): Boolean =
          found || (tree match
            case r: Ref
                if Trees.plainName(r.symbol) == "scala.runtime.Scala3RunTime.assertFailed" =>
              true
            case _ => foldOverTree(found, tree)(owner))

      /** An `Either` is raised for its left side, which is a domain error when it is one of ours;
        * how the `Either` was made does not matter.
        */
      private def raisedValue(made: Set[Symbol])(t: Term): Raised =
        eitherLeft(t.tpe) match
          case Some(left) => if Trees.isOurs(left) then Raised.Domain else Raised.PassedOn
          case None       => raised(made)(t)

      private def report(made: Set[Symbol])(values: List[Term]): Option[(String, String)] =
        values.filter(candidate).map(raisedValue(made)).foldLeft(Raised.PassedOn)(worst) match
          case Raised.Domain      => Some("raise-domain-error" -> Rules.Err4)
          case Raised.Constructed => Some("raise-constructed" -> Rules.Err4)
          case Raised.PassedOn    => None

      private def kind(made: Set[Symbol])(tree: Tree): Option[(String, String)] = tree match
        case Inlined(Some(call), _, body)
            if Trees.plainName(call.symbol) == "scala.Predef" &&
              callsAssertFailed.foldTree(false, body)(Symbol.noSymbol) =>
          Some("predef-assert" -> Rules.Err4)
        case Apply(Select(New(tpt), "<init>"), Nil) if tpt.tpe =:= TypeRepr.of[java.util.Date] =>
          Some("wall-clock" -> Rules.Eff6)
        case r: Ref if unsafeRunner(r)   => Some("unsafe-run" -> Rules.Eff5)
        case r: Ref if isClock(r.symbol) => Some("wall-clock" -> Rules.Eff6)
        case r: Ref if throwing.contains(Trees.plainName(r.symbol)) =>
          throwing.get(Trees.plainName(r.symbol)).map(_ -> Rules.Err4)
        case Apply(f, args) if raisingCall(f) && !implicitClause(f) => report(made)(args)
        // the cats syntax `e.raiseError[F, A]`: the error is the argument of the conversion
        case s @ Select(Apply(_, List(error)), name)
            if raising(name) && Trees.plainName(s.symbol.maybeOwner).startsWith("cats.syntax.") =>
          report(made)(List(error))
        case _ => None

      private def sites(made: Set[Symbol]): u.Traverser =
        new u.Traverser(SystemTimeAndUnsafeRun.this):
          override def traverseTree(tree: Tree)(owner: Symbol): Unit =
            tree match
              case t if hasWartAnnotation(t) => ()
              case _ =>
                kind(made)(tree).foreach((k, rule) => error(tree.pos, s"$k: $rule"))
                super.traverseTree(tree)(owner)

      // The tree of a compilation unit, once: the vals that hold a made Throwable are found first, so that a
      // raise of one is seen wherever the val is defined.
      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        sites(madeVals(tree, owner)).traverseTree(tree)(owner)
