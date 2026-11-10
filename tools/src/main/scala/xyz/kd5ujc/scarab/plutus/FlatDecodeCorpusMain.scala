// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Runs [[FlatDecodeCorpus.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.plutus.FlatDecodeCorpusMain"`.
  */
object FlatDecodeCorpusMain:
  def main(args: Array[String]): Unit = FlatDecodeCorpus.write(args)
