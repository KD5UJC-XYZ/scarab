// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Runs [[SerialiseDataCorpus.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.plutus.SerialiseDataCorpusMain"`.
  */
object SerialiseDataCorpusMain:
  def main(args: Array[String]): Unit = SerialiseDataCorpus.write(args)
