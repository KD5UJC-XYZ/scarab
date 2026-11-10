// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

/** Runs [[WalletQueryCases.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.node.ntc.WalletQueryCasesMain"`.
  */
object WalletQueryCasesMain:
  def main(args: Array[String]): Unit = WalletQueryCases.write(args)
