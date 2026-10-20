// SPDX-License-Identifier: Apache-2.0
import java.io.{BufferedOutputStream, File, FileInputStream, FileOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.{CRC32, ZipEntry, ZipOutputStream}
import sbt._

/** Packages the runtime classpath without merging dependency resources or changing precedence. */
object ReproducibleArtifacts {
  private val timestamp = LocalDateTime.of(1980, 1, 1, 0, 0)

  private def read[A](file: File)(use: FileInputStream => A): A = {
    val in = new FileInputStream(file)
    try use(in)
    finally in.close()
  }

  private def digest(file: File): String = {
    val hash = MessageDigest.getInstance("SHA-256")
    read(file)(in => {
      val buffer = new Array[Byte](65536)
      Iterator
        .continually(in.read(buffer))
        .takeWhile(_ != -1)
        .foreach(n => hash.update(buffer, 0, n))
    })
    hash.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  /** Stored entries avoid compression variability; timestamps, entry order and names are fixed. */
  private def archive(output: File, entries: Seq[(String, File)]): File = {
    require(entries.map(_._1).distinct.size == entries.size, "duplicate archive entry")
    IO.createDirectory(output.getParentFile)
    val zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))
    try
      entries.sortBy(_._1).foreach { case (name, source) =>
        val crc = new CRC32()
        read(source)(in => {
          val buffer = new Array[Byte](65536)
          Iterator
            .continually(in.read(buffer))
            .takeWhile(_ != -1)
            .foreach(n => crc.update(buffer, 0, n))
        })
        val entry = new ZipEntry(name)
        entry.setTimeLocal(timestamp)
        entry.setMethod(ZipEntry.STORED)
        entry.setSize(source.length())
        entry.setCompressedSize(source.length())
        entry.setCrc(crc.getValue)
        zip.putNextEntry(entry)
        read(source)(in => IO.transfer(in, zip))
        zip.closeEntry()
      }
    finally zip.close()
    output
  }

  def runtime(
      root: File,
      output: File,
      classpath: Seq[File],
      identity: Seq[(String, String)]
  ): File = {
    val staging = output.getParentFile / "release-staging"
    IO.delete(staging)
    IO.createDirectory(staging)
    val libraries = classpath.zipWithIndex.map { case (entry, index) =>
      val label = if (entry.isDirectory) {
        val relative = IO.relativize(root, entry).getOrElse(sys.error(s"external classes: $entry"))
        relative.takeWhile(_ != '/').replaceAll("[^A-Za-z0-9._-]", "_") + ".jar"
      } else entry.getName
      val name = f"lib/$index%04d-$label"
      val file = staging / name
      if (entry.isDirectory) {
        val mappings = (entry ** "*").get
          .filter(_.isFile)
          .map(f => IO.relativize(entry, f).getOrElse(sys.error(s"class path escaped: $f")) -> f)
        archive(file, mappings)
      } else IO.copyFile(entry, file, preserveLastModified = false)
      name -> file
    }
    val metadata = Seq(
      "classpath.txt" -> (libraries.map(_._1).mkString(":") + "\n"),
      "build-info.properties" -> identity.sortBy(_._1).map { case (k, v) => s"$k=$v\n" }.mkString,
      "scarab" -> """#!/bin/sh
set -eu
cd -- "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec java -cp "$(cat classpath.txt)" xyz.kd5ujc.scarab.node.Main "$@"
""",
      "SHA256SUMS" -> libraries.map { case (name, f) => s"${digest(f)}  $name\n" }.mkString
    ).map { case (name, content) =>
      val file = staging / name
      IO.write(file, content, UTF_8)
      name -> file
    }
    val notices = Seq("LICENSE", "NOTICE").map(name => name -> (root / name))
    archive(output, libraries ++ metadata ++ notices)
  }
}
