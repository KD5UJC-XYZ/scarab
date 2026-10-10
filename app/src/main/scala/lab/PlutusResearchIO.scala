// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode, Ref}
import cats.syntax.all.*
import java.nio.file.{Files, Path, StandardOpenOption as Open, StandardCopyOption as Copy}
import java.time.Instant
import lab.cbor.Bytes
import lab.submission.AdmissionProfile
import lab.header.PraosCertificateState.Point
import lab.network.{AsyncTcpTransport, TcpLimits}
import ReferenceJson.{Json as J, field, string, uint}
import scala.concurrent.duration.*

/** Pinned acquisition and private diagnostic evidence; no state import or submission. */
private[lab] object PlutusResearchIO:
  private[lab] def get[A](e: Either[?, A]): A =
    e.fold(x => throw new IllegalArgumentException(x.toString), identity)
  private[lab] def obj(j: J): Map[String, J] = j match
    case J.Obj(values) => values
    case _             => throw new IllegalArgumentException("JSON object required")
  private[lab] def sha(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private[lab] def hash(j: J): Bytes =
    val s = string(j)
    require(s.matches("[0-9a-f]{64}"), "SHA256/hash spelling")
    get(Bytes.fromHex(s))
  private[lab] def read(path: Path, limit: Int): Bytes =
    val stream = Files.newInputStream(path)
    val data =
      try stream.readNBytes(limit + 1)
      finally stream.close()
    require(data.length <= limit, "input byte bound")
    Bytes.fromArray(data)
  private[lab] def encode(j: J): Bytes = EvidenceJson.encode(j)
  private[lab] def num(n: BigInt): J = J.Num(n.toString)
  private[lab] def text(s: String): J = J.Str(s)
  private[lab] def bool(v: Boolean): J = J.Lit(v.toString)
  private[lab] def record(fields: (String, J)*): J = J.Obj(fields.toMap)
  private[lab] def point(p: Point): J =
    record("slot" -> num(p.slot), "blockNo" -> num(p.blockNo), "hash" -> text(p.hash.hex))
  private[lab] def point(j: J): Point =
    require(obj(j).keySet == Set("slot", "blockNo", "hash"), "exact fullpoint fields")
    Point(hash(field(j, "hash")), uint(field(j, "slot")), uint(field(j, "blockNo")))
  private[lab] def save(path: Path, value: J): IO[Unit] = IO.blocking {
    require(!Files.exists(path), "evidence already exists")
    val target = path.toAbsolutePath
    val temporary = Files.createTempFile(target.getParent, ".plutus-research-", ".part")
    try
      Files.write(temporary, encode(value).toArray, Open.WRITE, Open.TRUNCATE_EXISTING)
      Files.createLink(target, temporary)
      ()
    finally Files.deleteIfExists(temporary)
  }
  private[lab] def awaitFile(path: Path, limit: Int): IO[Bytes] =
    def loop: IO[Bytes] = IO.blocking(Files.exists(path)).flatMap {
      case true  => IO.blocking(read(path, limit))
      case false => IO.sleep(100.millis) *> IO.defer(loop)
    }
    loop.timeout(60.seconds)
  private[lab] def inputs(
      root: Path,
      rows: Map[String, J],
      names: Set[String]
  ): (Map[String, Bytes], Map[String, Bytes]) =
    require(rows.keySet == names, "exact original input names")
    val originals = rows.map { (name, row) =>
      require(obj(row).keySet == Set("sha256", "bytes"), "input descriptor fields")
      val b = read(root.resolve(name), 524288)
      require(
        BigInt(b.size) == uint(field(row, "bytes")) && sha(b) == hash(field(row, "sha256")),
        "original input pin: " + name
      )
      name -> b
    }
    (originals, rows.map((name, row) => name -> hash(field(row, "sha256"))))
  private[lab] def initial(
      root: Path,
      pin: String,
      admissionProfile: AdmissionProfile = AdmissionProfile.AdaVkey
  ): NativeLedgerV2.Checked =
    val raw = read(root.resolve("adapter-inputs.json"), 16384)
    require(pin.matches("[0-9a-f]{64}") && sha(raw).hex == pin, "independent initial manifest pin")
    val j = ReferenceJson.parse(raw)
    require(
      obj(j).keySet == Set("schema", "point", "inputs") && string(
        field(j, "schema")
      ) == "native-ledger-v2-reviewed-inputs-v1",
      "initial manifest contract"
    )
    val anchor = point(field(j, "point"))
    val (raws, pins) = inputs(root, obj(field(j, "inputs")), NativeLedgerV2.InputNames)
    val joined = get(NativeLedgerV2.decode(raws, pins, anchor, admissionProfile))
    require(
      anchor.slot < 300 && joined.ledger.globals.geometry.epochLength == 1000,
      "proven early epoch-zero profile"
    )
    joined
  private[lab] def endpoint(
      root: Path,
      ready: J,
      terminal: Point,
      joined: NativeLedgerV2.Checked
  ): NativeProtocolBootstrap.Acquisition =
    require(
      obj(ready).keySet == Set("schema", "manifestSHA256", "acquisitionResultSHA256") &&
        string(field(ready, "schema")) == "native-endpoint-ready-v1",
      "endpoint readiness contract"
    )
    val manifest = read(root.resolve("endpoint/endpoint-inputs.json"), 16384)
    require(
      sha(manifest) == hash(field(ready, "manifestSHA256")),
      "independent endpoint manifest pin"
    )
    val j = ReferenceJson.parse(manifest)
    require(
      obj(j).keySet == Set(
        "schema",
        "point",
        "genesisSHA256",
        "acquisitionResultSHA256",
        "inputs"
      ) &&
        string(field(j, "schema")) == "native-endpoint-reviewed-inputs-v1",
      "endpoint manifest contract"
    )
    require(
      point(field(j, "point")) == terminal && hash(
        field(j, "genesisSHA256")
      ) == joined.ledger.globals.genesisSHA256,
      "endpoint fullpoint/genesis binding"
    )
    val resultPin = hash(field(ready, "acquisitionResultSHA256"))
    require(
      hash(field(j, "acquisitionResultSHA256")) == resultPin &&
        sha(read(root.resolve("acquisition-result.json"), 65536)) == resultPin,
      "endpoint acquisition evidence pin"
    )
    val (raws, pins) =
      inputs(root.resolve("endpoint"), obj(field(j, "inputs")), NativeProtocolBootstrap.InputNames)
    get(NativeProtocolBootstrap.checkAcquisition(raws, pins, terminal))
  private[lab] def observation(
      root: Path,
      o: NetworkPublicationObservation,
      observedUnixNanos: Long
  ): IO[Unit] = IO.blocking {
    val prefix = f"block-${o.index}%04d"
    val envelope = root.resolve("originals").resolve(prefix + "-header.cbor")
    val block = root.resolve("originals").resolve(prefix + ".cbor")
    if o.applied.isEmpty then
      Files.write(envelope, o.original.envelope.toArray, Open.CREATE_NEW, Open.WRITE)
      Files.write(block, o.original.block.toArray, Open.CREATE_NEW, Open.WRITE)
    else
      require(
        sha(read(envelope, 65535)) == o.envelopeSHA256 && sha(
          read(block, 1048576)
        ) == o.blockSHA256,
        "stream originals changed"
      )
    val row = record(
      "index" -> num(o.index),
      "point" -> point(o.announced),
      "phase" -> text(if o.applied.isEmpty then "fetched" else "published-observed"),
      "headerSHA256" -> text(o.envelopeSHA256.hex),
      "blockSHA256" -> text(o.blockSHA256.hex),
      "observedUnixNanos" -> num(observedUnixNanos),
      "arrivedNanos" -> num(o.arrived.toNanos),
      "fetchedNanos" -> num(o.fetched.toNanos),
      "appliedObservedNanos" -> o.applied.fold[J](J.Lit("null"))(x => num(x.toNanos))
    )
    Files.write(
      root.resolve("observations.jsonl"),
      encode(row).toArray,
      Open.CREATE,
      Open.APPEND
    )
    ()
  }
