// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Runs [[EqualsDataCorpus.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.plutus.EqualsDataCorpusMain"`.
  */
object EqualsDataCorpusMain:
  def main(args: Array[String]): Unit = EqualsDataCorpus.write(args)
