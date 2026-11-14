// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import xyz.kd5ujc.scarab.uplc.oracle.GeneratedCorpus

/** Writes the requests of the generated-term oracle by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.uplc.GeneratedOracleCorpusMain <directory> [seed] [per profile]"`.
  */
object GeneratedOracleCorpusMain:
  def main(args: Array[String]): Unit = GeneratedCorpus.write(args)
