// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scala.quoted.*

/** The compile-time check of [[LibraryDigest.pinned]]: its argument is a literal of 64 lowercase
  * hex digits, as [[LibraryDigest.of]] writes a sha-256, or the build fails.
  */
object LibraryDigestLiteral:
  def checked(hex: Expr[String])(using Quotes): Expr[String] =
    val text = hex.valueOrAbort
    if text.matches("[0-9a-f]{64}") then Expr(text)
    else
      quotes.reflect.report.errorAndAbort(
        s"a pinned library digest is 64 lowercase hex digits, not \"$text\"",
        hex
      )
