// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import io.circe.{Json, parser}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.consensus.HeaderCheckpoints
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.ledger.state.{VocabError, Word64}

/** Cardano/Node/Protocol/Checkpoints.hs:56–114 at the pinned cardano-node 11.1.3. */
object CheckpointsFile:
  enum Problem extends DomainError:
    case DuplicateBlockNumbers(numbers: Set[BigInt])
    def message: String = this match
      case DuplicateBlockNumbers(numbers) =>
        s"duplicate checkpoints for block numbers ${numbers.toVector.sorted.mkString(", ")}"

  def parse(
      file: String,
      bytes: Array[Byte],
      expectedHash: Option[Bytes] = None
  ): Either[ConfigError, HeaderCheckpoints] =
    def field[A: io.circe.Decoder](json: Json, name: String): Either[ConfigError, A] =
      json.hcursor.get[A](name).leftMap(e => ConfigError(ConfigProblem.JsonField(file, name, e)))
    for
      _ <- expectedHash.traverse_ { expected =>
        val actual = Blake2b.hash256.hash(Bytes.fromArray(bytes))
        Either.cond(
          actual == expected,
          (),
          ConfigError(
            ConfigProblem.GenesisHashMismatch("Checkpoints", file, expected.hex, actual.hex)
          )
        )
      }
      json <- parser
        .parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
        .leftMap(e => ConfigError(ConfigProblem.JsonSyntax(file, e)))
      rows <- field[Vector[Json]](json, "checkpoints")
      entries <- rows.traverse { row =>
        for
          number <- field[BigInt](row, "blockNo")
          _ <- Word64
            .fromBigInt(VocabError.Kind.BlockNo, number)
            .leftMap(e => ConfigError(ConfigProblem.Ancillary(e)))
          text <- field[String](row, "hash")
          hash <- Bytes.fromHex(text).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
          _ <- Either.cond(
            hash.size == 32,
            (),
            ConfigError(ConfigProblem.ByteLength("checkpoint hash", 32, hash.size))
          )
        yield number -> hash
      }
      duplicates = entries
        .groupMapReduce(_._1)(_ => 1)(_ + _)
        .collect {
          case (number, count) if count > 1 => number
        }
        .toSet
      _ <- Either.cond(
        duplicates.isEmpty,
        (),
        ConfigError(ConfigProblem.Ancillary(Problem.DuplicateBlockNumbers(duplicates)))
      )
    yield HeaderCheckpoints(entries.toMap)
