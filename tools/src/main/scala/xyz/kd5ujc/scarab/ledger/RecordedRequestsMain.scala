// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** Writes the script requests of the recorded Preview and preprod transactions for the pinned
  * evaluator by hand ([[RecordedRequests.write]]):
  * `sbt "tools/runMain xyz.kd5ujc.scarab.ledger.RecordedRequestsMain [directory]"`.
  */
object RecordedRequestsMain:
  def main(args: Array[String]): Unit = RecordedRequests.write(args)
