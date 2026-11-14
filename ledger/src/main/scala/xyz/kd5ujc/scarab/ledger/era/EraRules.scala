// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}

/** Rules written once for every era that has the capability they ask for, as the reference writes a
  * rule once over its class constraints and assigns it to eras (`EraRule`).
  */
object SharedRules:
  /** The inline datums of a body's outputs, at any level the era has: every era whose bodies have
    * Babbage's fields (Babbage on), whose outputs the body class names.
    */
  def inlineDatums[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: BabbageEraTxBody[E],
      l: b.Level[L]
  ): Vector[Bytes] =
    b.outputs(body).flatMap(b.outputsOf.data)

  /** The lovelace of a set of outputs, whatever the era's value is (`coin . sumVal`). */
  def coinOf[E <: EraTag](outs: Vector[TxOut[E]])(using o: EraTxOut[E]): BigInt =
    o.valueOps.coin(outs.map(o.value).foldLeft(o.valueOps.zero)(o.valueOps.plus))

/** The complete rule set of era `E`: every rule named, none defaulted, so an era that does not say
  * which rule it runs does not compile. Spike: one rule.
  */
trait EraRules[E <: EraTag]:
  def inlineDatums(body: TxBody[TxLevel.Top, E]): Vector[Bytes]

object EraRules:
  given conway: EraRules[Conway] with
    def inlineDatums(body: TxBody[TxLevel.Top, Conway]): Vector[Bytes] =
      val b = EraTxBody.conway
      SharedRules.inlineDatums[Conway, TxLevel.Top](body)(using b, b.top)
