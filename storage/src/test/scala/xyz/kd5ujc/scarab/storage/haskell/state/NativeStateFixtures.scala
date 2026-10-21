// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Immutable native captures: adding a fixture requires a reviewed provenance and inventory change.
  */
object NativeStateFixtures:
  enum Error:
    case Read(path: Path, cause: Throwable)
    case Inventory(expected: Set[String], actual: Set[String])
    case Digest(file: String, expected: String, actual: String)

  val hashes: Map[String, String] = Map(
    "allegra-1800/meta" -> "873bf2b0f85d57308ba5b5dda3402e62d04d386864629dd4f64165ca9d118dc6",
    "allegra-1800/state" -> "8599c0dfd6fed8ba1580a1a7bf308c5cd3745bf3dbe329297f7103c21916797a",
    "allegra-1800/tables" -> "a9dccdaafa323b570f6957047a8e85c13879f34a2369d47049fd3e9c6ada326d",
    "alonzo-3013/meta" -> "60826140324581b3353964ca719882353b4452b829882a2db2fadc1305b95b97",
    "alonzo-3013/state" -> "b3262e14d49f18a58735b9fa82fb60928cc10787aab2f23ebb6f3594c70c982d",
    "alonzo-3013/tables" -> "3129ee527f6053a4d91ece5f2a7533021d7fc5ce440e91b2efcdb80b6e3c8679",
    "babbage-4000/meta" -> "32729c9bce4e4c71dbe8f382d33e17f9eb11dd264cb21fcb270601d66d98e6a7",
    "babbage-4000/state" -> "b1ec21fb65ba9727e30088afa999a8d532b627bdf39b24341953b610de2758b1",
    "babbage-4000/tables" -> "e6f2775b2e40b8ea30f7a394ed35a4a1f607061adc2da7e7615ca30be82dacd1",
    "byron-25/meta" -> "3348b2ca4e8dfb81d53d9a8651524cc9e85fbbb83794d68a9a1c7e1f675ca8f1",
    "byron-25/state" -> "fbd1488ef48268d9f162b4dc5c4cd04897bd4c89b25634052e3450ec4f58b2a6",
    "byron-25/tables" -> "ddec13dd990caddf89799689f63091c7e15f8a4064efc3934991c556cb2b5199",
    "conway-4593/meta" -> "8f05c2f5e260b8c3aeaee1f6bad2e245a9176c66e7fbbbf1661f00c3193f876d",
    "conway-4593/state" -> "a18120e5c89808bbbcf64313866206200e4a5fc70b2f2a759acf75fc05e94bc9",
    "conway-4593/tables" -> "94851809f39841784369fe9458385a0f8c141d9bea9d8c55d291693a03186dc9",
    "mary-2308/meta" -> "0b168c2c7b79af1c09970e6f9e36080eb2cb1ce8b6bf05bc187caac9cf78c404",
    "mary-2308/state" -> "68ae9a5c6d5997bdba254671e38911dea62450c9a4d411b323163d05ed54eff3",
    "mary-2308/tables" -> "3c44034ea3a18855c1504fb289d6b90803c85111f3cc633b26a149cd9a4c07f8",
    "shelley-1000/meta" -> "07cd1ed9b747b77929f5afe195b3f2bb38d23d8e8ddd205ca98f6f80b012b7f0",
    "shelley-1000/state" -> "1d453e817e015aa48816fa4e920772049cd06581ae47741c68d924c0bfcaf844",
    "shelley-1000/tables" -> "6377b10c1b5f3072d529f15947772fb545386fad104e8bd83f89a2f3835b23c5"
  )
  val names: Vector[String] =
    hashes.keysIterator.map(_.takeWhile(_ != '/')).toVector.distinct.sorted

  def verify(root: Path): Either[Error, Vector[Path]] =
    val found = Either
      .catchNonFatal {
        Using.resource(Files.walk(root)) { walk =>
          walk
            .iterator()
            .asScala
            .filter(Files.isRegularFile(_))
            .map(root.relativize(_).toString.replace('\\', '/'))
            .filterNot(_.split('/').last == "README.md")
            .toSet
        }
      }
      .leftMap(Error.Read(root, _))
    for
      directories <- Either
        .catchNonFatal {
          Using.resource(Files.list(root)) { children =>
            children
              .iterator()
              .asScala
              .filter(Files.isDirectory(_))
              .map(_.getFileName.toString)
              .toSet
          }
        }
        .leftMap(Error.Read(root, _))
      _ <- Either.cond(directories == names.toSet, (), Error.Inventory(names.toSet, directories))
      actual <- found
      _ <- Either.cond(actual == hashes.keySet, (), Error.Inventory(hashes.keySet, actual))
      _ <- hashes.toVector.sortBy(_._1).traverse_ { (name, expected) =>
        val path = root.resolve(name)
        Either
          .catchNonFatal {
            MessageDigest
              .getInstance("SHA-256")
              .digest(Files.readAllBytes(path))
              .map(b => f"${b & 0xff}%02x")
              .mkString
          }
          .leftMap(Error.Read(path, _))
          .flatMap { actual =>
            Either.cond(actual == expected, (), Error.Digest(name, expected, actual))
          }
      }
    yield names.map(root.resolve)
