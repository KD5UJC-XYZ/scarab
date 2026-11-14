// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

/** Runs [[RewardOracleCases.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.storage.haskell.state.RewardOracleCasesMain"`.
  */
object RewardOracleCasesMain:
  def main(args: Array[String]): Unit = RewardOracleCases.write(args)
