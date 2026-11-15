// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.warts

import scala.annotation.tailrec
import scala.quoted.Quotes

/** The design rule each message of these warts names: one constant per rule of
  * `docs/design-rules.md`, by its ID. A message is `<kind>: <rule>`, so that a count of the sites
  * can split them by kind.
  */
object Rules:
  val Eff5: String =
    "EFF-5, no unsafeRun* outside the Dispatcher bridge of a third-party callback (docs/design-rules.md#eff-5)"
  val Eff6: String =
    "EFF-6, time comes from Clock[F], never from the system clock (docs/design-rules.md#eff-6)"
  val Err4: String =
    "ERR-4, no exception for a domain error: a typed case raised through cats-mtl Raise (docs/design-rules.md#err-4)"
  val Str3: String =
    "STR-3, no decision depends on a String's contents outside a parser (docs/design-rules.md#str-3)"
  val Ari1: String =
    "ARI-1, exact arithmetic in ledger and consensus; floating point only where the reference computes in it, " +
      "cited at the site (docs/design-rules.md#ari-1)"

/** Tree helpers shared by the warts. */
object Trees:

  /** The root package of the code these warts check: a type defined under it is one of ours. */
  val Ours: String = "xyz.kd5ujc.scarab."

  /** A symbol's full name with the `$` of module classes removed: `scala.Predef$.assert` is
    * `scala.Predef.assert`, a Java static `java.lang.System.nanoTime` keeps its name.
    */
  def plainName(using q: Quotes)(s: q.reflect.Symbol): String =
    s.fullName.replace("$", "")

  /** The package that encloses a symbol, or `NoSymbol`. */
  @tailrec
  def enclosingPackage(using q: Quotes)(s: q.reflect.Symbol): q.reflect.Symbol =
    if s.isNoSymbol || s.isPackageDef then s else enclosingPackage(s.maybeOwner)

  /** The term without the wrappers the typer leaves around a value: `Inlined`, `Typed` and the
    * statements of a `Block`, whose value is its last expression.
    */
  @tailrec
  def strip(using q: Quotes)(t: q.reflect.Term): q.reflect.Term =
    import q.reflect.*
    t match
      case Inlined(_, _, e) => strip(e)
      case Typed(e, _)      => strip(e)
      case Block(_, e)      => strip(e)
      case other            => other

  /** The method a call `f.m`, `m`, `f.m[T]` or `f.m(a)(b)` refers to: its `Select` or `Ident`. */
  @tailrec
  def callee(using q: Quotes)(t: q.reflect.Term): Option[q.reflect.Ref] =
    import q.reflect.*
    t match
      case r: Ref             => Some(r)
      case TypeApply(f, _)    => callee(f)
      case Apply(f, _)        => callee(f)
      case Inlined(_, Nil, e) => callee(e)
      case _                  => None

  /** Whether a type is one of ours: its class, or a member of a union or intersection, is defined
    * under [[Ours]].
    */
  def isOurs(using q: Quotes)(t: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    t.widen.dealias match
      case OrType(a, b)  => isOurs(a) || isOurs(b)
      case AndType(a, b) => isOurs(a) || isOurs(b)
      case other         => other.classSymbol.exists(c => plainName(c).startsWith(Ours))
