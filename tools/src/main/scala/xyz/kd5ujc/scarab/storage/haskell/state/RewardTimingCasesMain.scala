// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

/** Runs [[RewardTimingCases.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.storage.haskell.state.RewardTimingCasesMain"`.
  */
object RewardTimingCasesMain:
  def main(args: Array[String]): Unit = RewardTimingCases.write(args)
