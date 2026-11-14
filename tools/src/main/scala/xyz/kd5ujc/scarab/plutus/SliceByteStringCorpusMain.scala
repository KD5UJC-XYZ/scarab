// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Runs [[SliceByteStringCorpus.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.plutus.SliceByteStringCorpusMain"`.
  */
object SliceByteStringCorpusMain:
  def main(args: Array[String]): Unit = SliceByteStringCorpus.write(args)
