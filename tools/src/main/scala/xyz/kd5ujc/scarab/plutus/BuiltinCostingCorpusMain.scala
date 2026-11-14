// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Runs [[BuiltinCostingCorpus.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.plutus.BuiltinCostingCorpusMain"`.
  */
object BuiltinCostingCorpusMain:
  def main(args: Array[String]): Unit = BuiltinCostingCorpus.write(args)
