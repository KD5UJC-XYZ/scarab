// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

/** Execution units (Alonzo): memory and CPU steps. */
final case class ExUnits(mem: BigInt, steps: BigInt):
  def +(o: ExUnits): ExUnits = ExUnits(mem + o.mem, steps + o.steps)
  def fits(limit: ExUnits): Boolean = mem <= limit.mem && steps <= limit.steps

object ExUnits:
  val zero: ExUnits = ExUnits(0, 0)
