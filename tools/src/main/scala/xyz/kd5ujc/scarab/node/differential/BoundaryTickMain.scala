// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

/** Runs [[BoundaryTick.run]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.node.differential.BoundaryTickMain"`.
  */
object BoundaryTickMain:
  def main(args: Array[String]): Unit = BoundaryTick.run(args)
