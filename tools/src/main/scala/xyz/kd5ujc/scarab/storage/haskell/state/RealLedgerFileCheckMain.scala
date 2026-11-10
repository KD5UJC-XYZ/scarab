// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

/** Runs [[RealLedgerFileCheck.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.storage.haskell.state.RealLedgerFileCheckMain"`.
  */
object RealLedgerFileCheckMain:
  def main(args: Array[String]): Unit = RealLedgerFileCheck.write(args)
