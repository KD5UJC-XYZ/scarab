// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.state.{EraTag, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.Decoded

/** Why a `NewEpochState` cannot be written in the Haskell format: it holds something the format
  * has and the mirror does not (the pulser of a running reward update), or a value outside the
  * range of the Haskell type it is written as.
  */
final case class StateNotWritable(constraint: String)

/** The ledger part of the Haskell node's `state` file, as bytes: the `NewEpochState` of an era
  * (`Types.hs:397-405`), written at the version the reference writes the era at ([[Versions]]).
  * The envelopes around it (the snapshot version, the hard-fork telescope, the header state) are
  * the next stage.
  */
object HaskellState:
  /** The limits for reading a state: no cap below the sizes a state has (tens of megabytes, and
    * hundreds of thousands of items), and floats allowed (the non-myopic likelihoods).
    */
  val limits: Cbor.Limits = Cbor.Limits(
    maxInputBytes = Int.MaxValue,
    maxDepth = Int.MaxValue,
    maxItems = Int.MaxValue,
    maxStringBytes = Int.MaxValue,
    allowFloats = true
  )

  /** The bytes of the state, or why it cannot be written. */
  def encode[E <: EraTag](
      state: NewEpochState[E]
  )(using ShelleyBasedEra[E]): Either[StateNotWritable, Bytes] =
    val codec = HaskellStateCodecs.newEpochState[E]
    codec.check(state) match
      case Some(constraint) => Left(StateNotWritable(constraint))
      case None             => Right(codec.encodeBytes(state))

  /** The state the bytes hold, or the first thing wrong with them and where it is. */
  def decode[E <: EraTag](bytes: Bytes)(using ShelleyBasedEra[E]): Decoded[NewEpochState[E]] =
    HaskellStateCodecs.newEpochState[E].decodeBytes(bytes, limits)
