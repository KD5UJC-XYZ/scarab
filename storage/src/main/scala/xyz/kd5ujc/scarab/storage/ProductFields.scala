// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import scala.quoted.*

/** Compile-time checked case-class accessors. The selected field must have the codec's type; no
  * productElement, runtime type test, tuple allocation or unchecked projection is emitted.
  */
private[scarab] object ProductFields:
  inline def named[A <: Product, T](inline name: String): A => T = ${ accessor[A, T]('name) }

  private def accessor[A <: Product: Type, T: Type](name: Expr[String])(using
      Quotes
  ): Expr[A => T] =
    import quotes.reflect.*
    val label = name.valueOrAbort
    val field = TypeRepr
      .of[A]
      .dealias
      .typeSymbol
      .caseFields
      .find(_.name == label)
      .getOrElse(report.errorAndAbort(s"No case-class field $label in ${Type.show[A]}"))
    '{ (value: A) =>
      ${
        val selected = Select('value.asTerm, field)
        if !(selected.tpe <:< TypeRepr.of[T]) then
          report.errorAndAbort(s"Field $label has ${selected.tpe.show}, not ${Type.show[T]}")
        selected.asExprOf[T]
      }
    }
