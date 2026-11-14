// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.ledger.{BbodyFailure, BlockFailure}

/** A complete BBODY judgment. A raised defect retains its original identity and wins over an
  * observed predicate prefix; only a completed judgment publishes a native refusal list.
  */
object BlockJudgment:
  def apply[Env, S, Sig](rule: JudgedSts[Env, S, Sig, BbodyFailure, BlockFailure])(
      globals: Globals,
      env: Env,
      state: S,
      signal: Sig
  ): Either[BlockFailure, S] =
    rule
      .transition[RuleT.Judged[BbodyFailure, BlockFailure, S]](env, signal)
      .judge(RuleT.Policy.Accumulate, globals, state) match
      case RuleT.Outcome.Raised(defect, _) => Left(defect)
      case RuleT.Outcome.Refused(failures) => Left(BlockFailure.Refused(failures))
      case RuleT.Outcome.Passed(after, _)  => Right(after)
