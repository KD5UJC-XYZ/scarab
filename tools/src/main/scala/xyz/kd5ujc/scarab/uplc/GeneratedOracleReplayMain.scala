// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import xyz.kd5ujc.scarab.uplc.oracle.GeneratedReplay

/** Replays a directory of generated requests and the pinned evaluator's verdicts through the own
  * evaluator by hand, and prints every disagreement with its script:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.uplc.GeneratedOracleReplayMain <directory>"`.
  */
object GeneratedOracleReplayMain:
  def main(args: Array[String]): Unit = GeneratedReplay.run(args)
