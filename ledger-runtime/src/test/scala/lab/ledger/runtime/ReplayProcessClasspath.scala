// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import java.io.File
import java.nio.file.{Files, Path}

/** Resolve the actually loaded test/module/dependency locations. sbt's own java.class.path is only
  * its launcher when tests are not forked; a prior app/runtimeClasspathFile is neither required nor
  * consulted. Loading these classes with initialize=false does not run crypto initializers.
  */
private[runtime] object ReplayProcessClasspath:
  def current: String =
    val required = Vector(
      "lab.ledger.runtime.ReplayInterruptionProbe$",
      "lab.ledger.runtime.NioReplayStore$",
      "lab.ledger.RestrictedReplay$",
      "lab.cbor.Bytes",
      "cats.effect.IO",
      "cats.effect.kernel.Async",
      "cats.effect.std.Semaphore",
      "cats.Monad",
      "cats.kernel.Monoid",
      "scala.Option",
      "scala.deriving.Mirror",
      "org.bouncycastle.crypto.digests.Blake2bDigest",
      "com.weavechain.curve25519.CompressedEdwardsY"
    )
    required
      .map { name =>
        val cls = Class.forName(name, false, getClass.getClassLoader)
        val source = Option(cls.getProtectionDomain)
          .flatMap(p => Option(p.getCodeSource))
          .getOrElse(throw new IllegalStateException(s"missing test classpath source: $name"))
        val location = source.getLocation
        require(location.getProtocol == "file", s"nonlocal test classpath source: $name")
        val path = Path.of(location.toURI).toAbsolutePath.normalize()
        require(Files.exists(path), s"missing test classpath path: $name")
        path.toString
      }
      .distinct
      .mkString(File.pathSeparator)
