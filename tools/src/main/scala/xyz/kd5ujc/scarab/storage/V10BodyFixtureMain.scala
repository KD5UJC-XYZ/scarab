// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files, Path}
import munit.Assertions

/** Writes the golden V10 bodies ([[V10BodyCases]]) into a directory that does not exist yet: one
  * file per case and the indefinite body. Argument: that directory. `V10BodyGoldenSuite` checks
  * every file against its case.
  */
object V10BodyFixtureMain extends Assertions:
  def main(args: Array[String]): Unit =
    val target = Path.of(args(0))
    assert(!Files.exists(target), s"$target exists")
    Files.createDirectories(target)
    V10BodyCases.Case.values.foreach { c =>
      val body = V10BodyCases.body(c)
      Files.write(target.resolve(c.file), body.toArray)
      if c == V10BodyCases.Case.Node11_1Conway then
        Files.write(
          target.resolve(V10BodyCases.IndefiniteFile),
          V10BodyCases.indefinite(body).toArray
        )
    }
