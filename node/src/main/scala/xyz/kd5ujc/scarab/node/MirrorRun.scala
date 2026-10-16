// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, StateMirror, StateMirrorError}
import xyz.kd5ujc.scarab.ledger.state.{LedgerMirror, MirrorEnv, MirrorError}

/** The ledger-state mirror ([[StateMirror]]) run purely: the environment is read from a `Kleisli`
  * and a refusal ends the computation as a `Left`. The node-to-client answers of the debug queries
  * and the `--mirror-check` of the follower share it.
  */
object MirrorRun:
  type Result[A] = Either[StateMirrorError, A]
  type M[A] = Kleisli[Result, ConsensusMirrorEnv, A]

  val mirror: StateMirror[M] = StateMirror[M]

  /** The result of `program` in the environment `env`. */
  def run[A](env: ConsensusMirrorEnv)(program: StateMirror[M] => M[A]): Result[A] =
    program(mirror).run(env)

/** The ledger side of the mirror ([[LedgerMirror]]) run purely, for the harness' tick stage, which
  * compares the ledger state alone: the consensus side (the header state, the telescope) is not
  * what a tick changes.
  */
object LedgerRun:
  type Result[A] = Either[MirrorError, A]
  type M[A] = Kleisli[Result, MirrorEnv, A]

  val mirror: LedgerMirror[M] = LedgerMirror[M]

  /** The result of `program` in the environment `env`. */
  def run[A](env: MirrorEnv)(program: LedgerMirror[M] => M[A]): Result[A] =
    program(mirror).run(env)
