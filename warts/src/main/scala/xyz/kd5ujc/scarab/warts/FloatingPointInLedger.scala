// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.warts

import org.wartremover.{WartTraverser, WartUniverse}

/** A floating-point value in the ledger and consensus packages (ARI-1): a type written or inferred
  * as `Double` or `Float` (boxed or not), and the outermost floating-point term of an expression. A
  * site is reported once: the terms inside a floating-point term are not reported again. A pattern
  * only takes a value apart, so its binders are not sites (`case Value.Float(_, _) =>`); what a
  * guard or a case body computes with them is.
  *
  * A source outside those packages is skipped at its package clause, so the wart costs little
  * elsewhere.
  */
object FloatingPointInLedger extends WartTraverser:

  private val scopes = List("xyz.kd5ujc.scarab.ledger", "xyz.kd5ujc.scarab.consensus")

  private def inScope(pkg: String): Boolean =
    scopes.exists(s => pkg == s || pkg.startsWith(s + "."))

  private def holdsScope(pkg: String): Boolean =
    scopes.exists(s => s.startsWith(pkg + "."))

  def apply(u: WartUniverse): u.Traverser =
    new u.Traverser(this):
      import q.reflect.*

      private val floating =
        List(
          TypeRepr.of[Double],
          TypeRepr.of[Float],
          TypeRepr.of[java.lang.Double],
          TypeRepr.of[java.lang.Float]
        )

      private def isFloating(t: TypeRepr): Boolean =
        val w = t.widen.dealias
        floating.exists(w =:= _)

      private val inside: u.Traverser =
        new u.Traverser(FloatingPointInLedger.this):
          override def traverseTree(tree: Tree)(owner: Symbol): Unit =
            tree match
              case t if hasWartAnnotation(t) => ()
              case CaseDef(_, guard, body) =>
                guard.foreach(traverseTree(_)(owner))
                traverseTree(body)(owner)
              case t: TypeTree if isFloating(t.tpe) =>
                error(t.pos, s"floating-type: ${Rules.Ari1}")
              case t: Term if isFloating(t.tpe) =>
                error(t.pos, s"floating-term: ${Rules.Ari1}")
              case _ => super.traverseTree(tree)(owner)

      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        tree match
          case t if hasWartAnnotation(t) => ()
          case PackageClause(pid, _) if inScope(pid.symbol.fullName) =>
            inside.traverseTree(tree)(owner)
          case PackageClause(pid, _) if holdsScope(pid.symbol.fullName) =>
            super.traverseTree(tree)(owner)
          case _: PackageClause => ()
          case _ if inScope(Trees.enclosingPackage(owner).fullName) =>
            inside.traverseTree(tree)(owner)
          case _ => super.traverseTree(tree)(owner)
