// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Sync
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** base's `getProgName` (`System/Environment.hs`): the name the program was started under, the last
  * `/` -separated segment of `argv[0]` (`basename`). The JVM keeps no `argv[0]` of its own, so it
  * is read from the process's command line, `/proc/self/cmdline` (NUL-separated, as the kernel
  * keeps it); where there is none, there is no name.
  */
object ProgramName:
  def current[F[_]: Sync]: F[Option[String]] =
    Sync[F].blocking(
      Either.catchNonFatal(Files.readAllBytes(Path.of("/proc/self/cmdline"))).toOption.flatMap(of)
    )

  /** The name in a command line: the bytes of `argv[0]` decoded as UTF-8, after its last `/`. */
  def of(cmdline: Array[Byte]): Option[String] =
    Option
      .when(cmdline.nonEmpty)(new String(cmdline.takeWhile(_ != 0), StandardCharsets.UTF_8))
      .map(argv0 => argv0.drop(argv0.lastIndexOf('/') + 1))
