// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Runs [[ConstrIndexCorpus.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.plutus.ConstrIndexCorpusMain"`.
  */
object ConstrIndexCorpusMain:
  def main(args: Array[String]): Unit = ConstrIndexCorpus.write(args)
