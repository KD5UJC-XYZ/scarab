// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.state.{EpochState, EraTag, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.{ConstraintViolation, Decoded}

/** Why a `NewEpochState` cannot be written in the Haskell format: it holds a value outside the
  * range of the Haskell type it is written as.
  */
final case class StateNotWritable(violation: ConstraintViolation)

/** The ledger part of the Haskell node's `state` file, as bytes: the `NewEpochState` of an era
  * (`Types.hs:397-405`), written at the version the reference writes the era at ([[Versions]]). The
  * enclosing snapshot version, hard-fork telescope and header state are handled by
  * [[HaskellLedgerFile]].
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
    HaskellStateCodecs.newEpochState[E].write(state).left.map(StateNotWritable(_))

  /** The bytes of the epoch state alone (`Types.hs:136-142`), at the version of its era: what the
    * node-to-client query `DebugEpochState` answers.
    */
  def encodeEpochState[E <: EraTag](
      state: EpochState[E]
  )(using era: ShelleyBasedEra[E]): Either[StateNotWritable, Bytes] =
    HaskellStateCodecs
      .epochState[E](using era, Versions.eraLow(era))
      .write(state)
      .left
      .map(StateNotWritable(_))

  /** The state the bytes hold, or the first thing wrong with them and where it is. */
  def decode[E <: EraTag](bytes: Bytes)(using ShelleyBasedEra[E]): Decoded[NewEpochState[E]] =
    HaskellStateCodecs.newEpochState[E].decodeBytes(bytes, limits)
