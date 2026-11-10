// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** Runs [[BoundaryTransactionScripts.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.plutus.BoundaryTransactionScriptsMain"`.
  */
object BoundaryTransactionScriptsMain:
  def main(args: Array[String]): Unit = BoundaryTransactionScripts.write(args)
