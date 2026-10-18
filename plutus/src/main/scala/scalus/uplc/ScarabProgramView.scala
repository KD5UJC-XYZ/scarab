// SPDX-License-Identifier: Apache-2.0
package scalus.uplc

/** Replaces only the VM view of a decoded program, preserving its version and de Bruijn indices.
  * Scalus 1.3.0 makes this constructor package-private (`Program.scala:247`); its public `Program`
  * conversion renames variables and therefore cannot preserve a deliberately open variable.
  * Original script bytes remain owned by the caller and are never re-encoded from this view.
  */
object ScarabProgramView:
  def withTerm(program: DeBruijnedProgram, term: Term): DeBruijnedProgram =
    program.copy(term = term)
