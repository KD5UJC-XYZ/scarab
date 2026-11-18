// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.effect.{Clock, IO, IOApp}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.protocol.Era

/** Development tool: what a fetched block costs whose one transaction registers a pool with a tag
  * 30 margin of two long bignums. It forges the body under the first Conway header of
  * `fixtures/eras/blocks.hex` and times [[BlockTransport.check]], which decodes the transactions
  * before it compares the body hash, and the rendering of its refusal (`message`). Each size is run
  * in three forms: two random components of that many bytes (`random`), refused as out of bounds;
  * the same numerator over 1 (`over-one`), refused too; `g` over `2g` for a random `g` of that many
  * bytes (`half`), whose margin is one half, so the block is refused for its body hash only; and
  * `g F(92)` over `g F(93)` (`lame`), the accepted margin whose reduction divides most often.
  *
  * `tools/runMain xyz.kd5ujc.scarab.ledger.RationalDecodeBench [largest component in bytes] [runs]`
  *
  * It prints a `HOST` line and one `RESULT` line per size and form, with the verdict, the median
  * and longest time of the check, the length of the refusal's message and the time to render it.
  */
object RationalDecodeBench extends IOApp:
  private val sizes = Vector(19000, 38000, 76000, 152000, 304000, 608000, 1216000)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => sys.error(e.message), identity)

  private def head(major: Int, n: Long): String =
    val m = major << 5
    if n < 24 then f"${m | n.toInt}%02x"
    else if n < 0x100 then f"${m | 24}%02x$n%02x"
    else if n < 0x10000 then f"${m | 25}%02x$n%04x"
    else if n < 0x100000000L then f"${m | 26}%02x$n%08x"
    else f"${m | 27}%02x$n%016x"

  private def bignum(magnitude: BigInt): String =
    val raw = magnitude.toByteArray.dropWhile(_ == 0)
    "c2" + head(2, raw.length.toLong) + Bytes.fromArray(raw).hex

  private def random(bytes: Int, seed: Long): BigInt =
    BigInt(8 * bytes - 1, new scala.util.Random(seed)).setBit(8 * bytes - 1)

  private val fibonacci: LazyList[BigInt] =
    BigInt(0) #:: BigInt(1) #:: fibonacci.zip(fibonacci.tail).map(_ + _)

  private def items(node: Node): Vector[Node] = node.value match
    case Value.Arr(values) => values
    case other             => sys.error(s"expected an array, got $other")

  /** The first Conway header of the fixture and its block's bytes. */
  private val conway: IO[(Header, Bytes)] =
    IO.blocking(Files.readString(Path.of("fixtures/eras/blocks.hex")))
      .flatMap(text =>
        IO(
          text.linesIterator
            .filter(_.nonEmpty)
            .map(hex)
            .map(raw =>
              Block.decode(raw, BigInt(50)).fold(e => sys.error(e.message), b => b.header -> raw)
            )
            .find(_._1.era == Era.Conway)
            .getOrElse(sys.error("no Conway block in the fixture"))
        )
      )

  private def forged(raw: Bytes, numerator: BigInt, denominator: BigInt): Bytes =
    val block = items(items(Cbor.decode(raw).fold(e => sys.error(e.message), identity))(1))
    val margin = "d81e82" + bignum(numerator) + bignum(denominator)
    val certificate = "8a03" + "581c" + "02" * 28 + "5820" + "03" * 32 + "0101" + margin +
      "581de1" + "04" * 28 + "8080f6"
    val body = "a4" + "00818258" + "20" + "01" * 32 + "00" + "0181825" + "81d61" + "07" * 28 +
      "1a000f4240" + "021a00030d40" + "0481" + certificate
    raw.take(block(1).offset) ++ hex("81" + body + "81a0a080")

  private def verdict(result: Either[BlockTransportError, Block]): String = result match
    case Left(BlockTransportError.TransactionDecode(_, cause)) =>
      s"TransactionDecode(${cause.productPrefix})"
    case Left(other) => other.productPrefix
    case Right(_)    => "Right"

  private def millis(d: FiniteDuration): Double = d.toNanos / 1e6

  private def row(form: String, bytes: Int, header: Header, raw: Bytes, runs: Int): IO[Unit] =
    for
      timed <- (1 to runs).toVector.traverse(_ =>
        Clock[IO].timed(IO(BlockTransport.check(header, raw)))
      )
      result = timed.headOption.fold[Either[BlockTransportError, Block]](sys.error("no run"))(_._2)
      rendered <- Clock[IO].timed(IO(result.left.map(_.message).left.toOption.fold(0)(_.length)))
      times = timed.map(t => millis(t._1)).sorted
      _ <- IO.println(
        f"RESULT form=$form%-8s component_bytes=$bytes%8d body_bytes=${raw.size}%8d verdict=${verdict(result)}%-45s " +
          f"check_ms_median=${times(times.size / 2)}%10.1f check_ms_max=${times.last}%10.1f " +
          f"message_chars=${rendered._2}%8d message_ms=${millis(rendered._1)}%8.1f"
      )
    yield ()

  def run(args: List[String]): IO[cats.effect.ExitCode] =
    val largest = args.headOption.flatMap(_.toIntOption).getOrElse(sizes.last)
    val runs = args.drop(1).headOption.flatMap(_.toIntOption).getOrElse(3).max(1)
    val chosen = sizes.filter(_ <= largest)
    IO.println(
      s"HOST jvm=${System.getProperty("java.vm.version")} processors=${Runtime.getRuntime.availableProcessors} runs=$runs"
    ) *> conway
      .flatMap { (header, raw) =>
        chosen.traverse_ { bytes =>
          IO.defer {
            val g = random(bytes, 3)
            def at(form: String, numerator: BigInt, denominator: BigInt) =
              IO(forged(raw, numerator, denominator)).flatMap(row(form, bytes, header, _, runs))
            at("random", random(bytes, 1), random(bytes, 2)) *>
              at("over-one", random(bytes, 1), BigInt(1)) *>
              at("half", g, g * 2) *>
              at("lame", g * fibonacci(92), g * fibonacci(93))
          }
        }
      }
      .as(cats.effect.ExitCode.Success)
