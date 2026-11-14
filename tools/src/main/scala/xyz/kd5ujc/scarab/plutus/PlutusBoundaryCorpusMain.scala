// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Runs [[PlutusBoundaryCorpus.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.plutus.PlutusBoundaryCorpusMain"`.
  */
object PlutusBoundaryCorpusMain:
  def main(args: Array[String]): Unit = PlutusBoundaryCorpus.write(args)
