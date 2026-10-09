// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import java.nio.file.{Files, Path, LinkOption, StandardOpenOption as Open}
import lab.cbor.Bytes
import lab.ledger.TxIn
import ReferenceJson.{field, string}
import scala.concurrent.duration.*

/** Offline fixture proof helper. No signing keys, network access or runtime state import. */
object NativeScriptFixtureMain extends IOApp:
  import NativeLiveBoundaryMain.{get, obj, read, sha, encode, num, text, bool, record, point}

  private def boundedRead(path: Path, limit: Int): Bytes =
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "regular input file required")
    read(path, limit)

  private def decimal(value: String, maximum: BigInt): BigInt =
    require(value.matches("0|[1-9][0-9]{0,19}"), "canonical bounded decimal")
    val result = BigInt(value)
    require(result <= maximum, "numeric bound")
    result

  // A hard-link publication is atomic and fails if the target already exists. Unlike
  // ATOMIC_MOVE, it cannot replace an existing target on Unix. Unsupported filesystems fail.
  private[lab] def saveNew(path: Path, value: ReferenceJson.Json): IO[Unit] = IO.blocking {
    val target = path.toAbsolutePath
    val raw = encode(value)
    require(raw.size <= 16384, "proof output bound")
    val temporary = Files.createTempFile(target.getParent, ".native-script-proof-", ".part")
    try
      Files.write(temporary, raw.toArray, Open.WRITE, Open.TRUNCATE_EXISTING)
      Files.createLink(target, temporary)
      ()
    finally Files.deleteIfExists(temporary)
  }

  private[lab] def work(args: List[String]): IO[Unit] = args match
    case List("originals", input, output) =>
      IO.blocking {
        val proof = get(NativeScriptFixtureEvidence.originals(boundedRead(Path.of(input), 65536)))
        record(
          "transactionId" -> text(proof.transactionId.hex),
          "envelopeSHA256" -> text(proof.envelopeSHA256.hex),
          "bodySHA256" -> text(proof.bodySHA256.hex),
          "witnessesSHA256" -> text(proof.witnessesSHA256.hex),
          "bytes" -> num(proof.bytes)
        )
      }.flatMap(saveNew(Path.of(output), _))
    case List("bootstrap", directory, script, transactionId, index, coin, output) =>
      IO.blocking {
        require(transactionId.matches("[0-9a-f]{64}"), "canonical funded transaction id")
        val input = get(TxIn.create(get(Bytes.fromHex(transactionId)), decimal(index, 65535)))
        val amount = decimal(coin, (BigInt(1) << 64) - 1)
        val root = Path.of(directory)
        val manifest = boundedRead(root.resolve("adapter-inputs.json"), 16384)
        val json = ReferenceJson.parse(manifest)
        require(
          obj(json).keySet == Set("schema", "point", "inputs") &&
            string(field(json, "schema")) == "native-ledger-v2-reviewed-inputs-v1",
          "initial packet manifest contract"
        )
        val anchor = point(field(json, "point"))
        NativeLedgerV2.InputNames.foreach { name =>
          require(
            Files.isRegularFile(root.resolve(name), LinkOption.NOFOLLOW_LINKS),
            "regular packet input file required"
          )
        }
        val (originals, _) = NativeLiveBoundaryMain.inputs(
          root,
          obj(field(json, "inputs")),
          NativeLedgerV2.InputNames
        )
        val seed = originals("derived-full-epoch-seed.cbor")
        val scriptBytes = boundedRead(Path.of(script), 65536)
        val proof = get(
          NativeScriptFixtureEvidence.bootstrap(
            get(NativeEpochComponents.mempackUtxo(seed)),
            originals("original-whole-utxo.cbor"),
            seed,
            originals("original-debug-epoch.cbor"),
            scriptBytes,
            input,
            amount
          )
        )
        record(
          "schema" -> text("native-script-bootstrap-proof-v1"),
          "passed" -> bool(true),
          "scope" -> text(proof.scope),
          "fundedInput" -> text(s"${input.id.hex}#${input.index}"),
          "coin" -> num(amount),
          "scriptHash" -> text(proof.scriptHash.hex),
          "scriptSHA256" -> text(sha(scriptBytes).hex),
          "wholeUtxoSHA256" -> text(proof.wholeUtxoSHA256.hex),
          "mempackSHA256" -> text(proof.mempackSHA256.hex),
          "seedSHA256" -> text(proof.seedSHA256.hex),
          "debugSHA256" -> text(proof.debugSHA256.hex),
          "manifestSHA256" -> text(sha(manifest).hex),
          "point" -> point(anchor),
          "entries" -> num(proof.entries),
          "fullLedgerValidated" -> bool(proof.fullLedgerValidated),
          "runtimeImport" -> bool(proof.runtimeImport)
        )
      }.flatMap(saveNew(Path.of(output), _))
    case _ =>
      IO.raiseError(
        new IllegalArgumentException(
          "originals INPUT_CBOR OUTPUT_JSON | bootstrap INITIAL_PACKET_DIR ORIGINAL_SCRIPT_CBOR FUNDED_TXID OUTPUT_INDEX COIN OUTPUT_JSON"
        )
      )

  def run(args: List[String]): IO[ExitCode] =
    IO.defer(work(args)).timeout(30.seconds).as(ExitCode.Success).handleErrorWith { error =>
      IO.println(
        "NATIVE_SCRIPT_FIXTURE_FAILED: " +
          Option(error.getMessage).getOrElse(error.getClass.getName).take(1024)
      ).as(ExitCode(2))
    }
