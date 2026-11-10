// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Runs [[UnliftingBoundsCorpus.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.plutus.UnliftingBoundsCorpusMain"`.
  */
object UnliftingBoundsCorpusMain:
  def main(args: Array[String]): Unit = UnliftingBoundsCorpus.write(args)
