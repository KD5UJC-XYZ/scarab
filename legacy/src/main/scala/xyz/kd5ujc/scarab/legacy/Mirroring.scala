// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorState, StateMirrorError}
import xyz.kd5ujc.scarab.ledger.state.Mirrored

/** The conversion of the flat node state ([[StateMirror]]) run purely: the environment is read from
  * a `Kleisli` and a refusal ends the computation as a `Left`.
  */
object Mirroring:
  type Result[A] = Either[StateMirrorError, A]
  type M[A] = Kleisli[Result, ConsensusMirrorEnv, A]

  private val mirror: StateMirror[M] = StateMirror[M]

  /** The mirror of `s` at the tip `anchor`, in the environment `env`. */
  def toMirror(
      env: ConsensusMirrorEnv,
      anchor: Option[BlockRef],
      s: FlatNodeState
  ): Result[Mirrored[MirrorState]] =
    mirror.toMirror(anchor, s).run(env)
