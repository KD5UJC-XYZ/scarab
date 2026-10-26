// SPDX-License-Identifier: Apache-2.0
import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

/** The patched library has a distinct content identity; scripts/sbtw prepares its repository. */
object ScalusPatch {
  private val root = Paths.get("").toAbsolutePath
  private val inputs = Vector(
    "patches/scalus/manifest.json",
    "patches/scalus/cek-hooks.patch",
    "scripts/prepare_scalus.py"
  )
  private val digest = MessageDigest.getInstance("SHA-256")
  inputs.foreach { name =>
    val bytes = Files.readAllBytes(root.resolve(name))
    digest.update(ByteBuffer.allocate(8).putLong(bytes.length.toLong).array())
    digest.update(bytes)
  }
  val inputDigest: String = digest.digest().map(b => f"${b & 0xff}%02x").mkString
  val version: String = "1.4.0-scarab-" + inputDigest

  private val cache: Path = sys.env
    .get("SCARAB_CACHE_DIR")
    .orElse(sys.env.get("LAB_CACHE_DIR"))
    .map(Paths.get(_))
    .getOrElse(root.resolve(".cache"))
  val repository: Path =
    cache.resolve("scarab/patched-scalus").resolve(inputDigest).resolve("repository")
  def requirePrepared(expectedVersion: String): Unit = {
    require(
      expectedVersion == version,
      "Scalus dependency version does not match the pinned build inputs"
    )
    if (!Files.isDirectory(repository))
      sys.error(
        "The pinned Scalus hooks are not prepared; run bash scripts/sbtw inside the JDK image"
      )
  }
}
