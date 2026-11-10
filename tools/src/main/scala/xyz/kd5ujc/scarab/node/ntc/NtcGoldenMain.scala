// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

/** Runs [[NtcGolden.write]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.node.ntc.NtcGoldenMain"`.
  */
object NtcGoldenMain:
  def main(args: Array[String]): Unit = NtcGolden.write()
