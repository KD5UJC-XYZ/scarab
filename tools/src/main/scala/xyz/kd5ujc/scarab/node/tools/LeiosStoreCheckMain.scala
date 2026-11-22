// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO, IOApp}
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.Json
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.EbHash
import xyz.kd5ujc.scarab.storage.{LeiosStore, LeiosStoreError, LeiosStoreLocks, RocksLeiosStore}

/** Development tool, not part of the node: `xyz.kd5ujc.scarab.node.tools.LeiosStoreCheckMain`.
  *
  * `--db <dir> --hashes <hex>[,<hex>...]`: opens the endorser-block store of a stopped node's
  * database directory (`<dir>/leios`, as `follow --db` keeps it under the Leios build) and prints,
  * for each endorser-block hash, one JSON line: whether the store holds its body (`lookupEbBody`),
  * and the number of transactions of its closure when the closure is complete (`lookupEbClosure`),
  * else null. `devnet.py leios-traffic --follow` asks it for the endorser blocks the reference
  * chain announces, to judge that the node fetched each certified one's closure into its own store
  * (#73).
  */
object LeiosStoreCheckMain extends IOApp:
  private val Usage = "usage: LeiosStoreCheckMain --db <dir> --hashes <hex>[,<hex>...]"

  private enum Problem:
    case Arguments
    case Hash(text: String, cause: Bytes.HexError)
    case Store(cause: LeiosStoreError)
    def message: String = this match
      case Arguments         => Usage
      case Hash(text, cause) => s"not an endorser-block hash: $text (${cause.message})"
      case Store(cause)      => cause.message

  private def parse(args: List[String]): Either[Problem, (Path, List[EbHash])] =
    args match
      case "--db" :: db :: "--hashes" :: hashes :: Nil =>
        hashes
          .split(',')
          .toList
          .filter(_.nonEmpty)
          .traverse(h => Bytes.fromHex(h).bimap(Problem.Hash(h, _), EbHash.fromBytes))
          .map(Path(db) -> _)
      case _ => Left(Problem.Arguments)

  private def line(store: LeiosStore[IO], hash: EbHash)(using
      cats.mtl.Raise[IO, LeiosStoreError]
  ): IO[Json] =
    (store.lookupEbBody(hash), store.lookupEbClosure(hash)).mapN((body, closure) =>
      Json.obj(
        "hash" -> Json.fromString(hash.bytes.hex),
        "body" -> Json.fromBoolean(body.isDefined),
        "closureTxs" -> closure.fold(Json.Null)(txs => Json.fromInt(txs.size))
      )
    )

  def run(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(problem) => IO.println(problem.message).as(ExitCode(2))
      case Right((db, hashes)) =>
        Handle
          .allow[LeiosStoreError](
            LeiosStoreLocks
              .make[IO]
              .flatMap(locks =>
                RocksLeiosStore
                  .resource[IO](db / "leios", locks)
                  .use(store =>
                    hashes.traverse_(h => line(store, h).flatMap(j => IO.println(j.noSpaces)))
                  )
              )
              .as(ExitCode.Success)
          )
          .rescue(e =>
            IO.println(Json.obj("error" -> Json.fromString(Problem.Store(e).message)).noSpaces)
              .as(ExitCode.Error)
          )
