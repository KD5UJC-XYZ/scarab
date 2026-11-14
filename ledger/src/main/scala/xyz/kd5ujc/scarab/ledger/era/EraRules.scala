// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Datum, Update}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, Conway, EraTag}

/** Rules written once for every era that has the capability they ask for, as the reference writes a
  * rule once over its class constraints and assigns it to eras (`EraRule`).
  */
object SharedRules:
  /** The inline datums of a body's outputs, at any level the era has: every era whose outputs have
    * datums (Babbage on).
    */
  def inlineDatums[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: EraTxBody[E],
      o: BabbageEraTxOut[E],
      l: b.Level[L]
  ): Vector[Bytes] =
    b.outputs(body)
      .flatMap(out =>
        o.datum(out) match
          case Datum.Inline(data) => Vector(data)
          case _                  => Vector.empty
      )

  /** A body's protocol-parameter update: only eras up to Babbage have one ([[ShelleyEraTxBody]]).
    */
  def proposedUpdate[E <: EraTag](body: TxBody[TxLevel.Top, E])(using
      s: ShelleyEraTxBody[E]
  ): Option[Update] =
    s.update(body)

/** The complete rule set of era `E`: every rule named, none defaulted, so an era that does not say
  * which rule it runs does not compile.
  */
trait EraRules[E <: EraTag]:
  def inlineDatums(body: TxBody[TxLevel.Top, E]): Vector[Bytes]

object EraRules:
  given babbage: EraRules[Babbage] with
    def inlineDatums(body: TxBody[TxLevel.Top, Babbage]): Vector[Bytes] =
      val b = EraTxBody.babbage
      SharedRules.inlineDatums[Babbage, TxLevel.Top](body)(using b, EraTxOut.babbage, b.top)

  given conway: EraRules[Conway] with
    def inlineDatums(body: TxBody[TxLevel.Top, Conway]): Vector[Bytes] =
      val b = EraTxBody.conway
      SharedRules.inlineDatums[Conway, TxLevel.Top](body)(using b, EraTxOut.conway, b.top)
