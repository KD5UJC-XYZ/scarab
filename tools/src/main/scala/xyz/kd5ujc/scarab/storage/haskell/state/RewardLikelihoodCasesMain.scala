// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

/** Runs [[RewardLikelihoodCases.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.storage.haskell.state.RewardLikelihoodCasesMain"`.
  */
object RewardLikelihoodCasesMain:
  def main(args: Array[String]): Unit = RewardLikelihoodCases.write(args)
