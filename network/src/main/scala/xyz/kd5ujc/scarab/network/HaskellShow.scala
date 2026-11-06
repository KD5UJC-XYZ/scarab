// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

/** The reference's local-address and file-descriptor trace presentation. */
object HaskellShow:
  /** `show s`; the common GHC string escaping also serves ledger diagnostics. */
  def string(s: String): String = xyz.kd5ujc.scarab.codec.HaskellShow.text(s)

  /** `showsPrec 11 n` of an `Int`: a negative number in parentheses. */
  def intArgument(n: Int): String = if n < 0 then s"($n)" else n.toString
