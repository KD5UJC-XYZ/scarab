// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.ledger.MinimumOutput

object MinimumOutputCommand:
  def parameters(json: ReferenceJson.Json): MinimumOutput.Parameters =
    import ReferenceJson.{field, uint}
    MinimumOutput.Parameters
      .checked(
        "Conway",
        uint(field(json, "protocolVersion", "major")),
        uint(field(json, "protocolVersion", "minor")),
        uint(field(json, "utxoCostPerByte"))
      )
      .fold(e => throw new IllegalArgumentException(e), identity)

  def render(receipt: MinimumOutput.Receipt): String =
    val outputs = receipt.outputs
      .map { o =>
        s"""{"index":${o.index},"originalHex":"${o.original.hex}","serializedBytes":${o.original.size},"coin":${o.coin},"required":${o.required},"satisfied":${o.satisfied}}"""
      }
      .mkString("[", ",", "]")
    s"""{"scope":"minimum-output-predicate","profile":"${receipt.profileId}","satisfied":${receipt.satisfied},"outputs":$outputs,"fullLedgerValidated":false}"""

  def run(args: List[String]): IO[ExitCode] = args match
    case List(params, transaction) =>
      IO.blocking {
        def read(name: String): Bytes =
          val path = Path.of(name)
          require(Files.size(path) <= 4194304, "minimum-output input exceeds bound")
          Bytes.fromArray(Files.readAllBytes(path))
        val p = parameters(ReferenceJson.parse(read(params)))
        val tx = Bytes
          .fromHex(new String(read(transaction).toArray, "UTF-8").trim)
          .fold(e => throw new IllegalArgumentException(e), identity)
        val result =
          MinimumOutput.check(p, tx).fold(e => throw new IllegalArgumentException(e), identity)
        (render(result), if result.satisfied then ExitCode.Success else ExitCode(1))
      }.flatMap { case (text, status) => IO.println(text).as(status) }
        .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
    case _ =>
      IO.println("usage: minimum-output PARAMETERS_JSON TRANSACTION_CBOR_HEX").as(ExitCode(2))
