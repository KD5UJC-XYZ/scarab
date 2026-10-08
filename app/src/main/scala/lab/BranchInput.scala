// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.header.PraosCertificateState as Certificate
import lab.network.ChainSync
import scala.util.control.NonFatal

/** Pre-only original ownership. Construction is not ledger or consensus acceptance. */
object BranchInput:
  enum Failure:
    case Unsupported(feature: String)
    case Rejected(stage: String, reason: String)
  private final case class Stop(failure: Failure) extends RuntimeException
  val sources: Map[String, String] = Map(
    "genesisSha256" -> "transfer-genesis.md",
    "preTipsSha256" -> "pre-tips.md",
    "preProtocolSha256" -> "pre-protocol-state.md",
    "preLedgerSha256" -> "pre-ledger-state.md",
    "preParametersSha256" -> "pre-parameters.md",
    "preUtxoSha256" -> "pre-utxo.md",
    "preUtxoCborSha256" -> "pre-utxo-cbor.md",
    "transactionSha256" -> "signed-transaction-cbor.md",
    "captureSha256" -> "scala-transfer.md"
  )
  final class Checked private[BranchInput] (
      val id: Bytes,
      val preStateAttribution: Bytes,
      val originals: Map[String, Bytes],
      val sourcePins: Map[String, Bytes],
      val transaction: Bytes,
      val body: Node,
      val witnesses: Node,
      val preUtxoCbor: Bytes,
      val feesBefore: BigInt,
      val parameters: ReferenceJson.Json,
      val certificates: CertificateBranch.Prepared,
      val eligibility: PraosEligibilityContext.Prepared,
      val header: ReferenceCaptureCommand.Header
  ):
    val referenceSnapshotAtomic = false
    val authenticatedSnapshot = false
    val ledgerValidated = false
    val consensusValidated = false
    def anchor: ChainSync.Point = certificates.acquisition.anchor
    def original: BoundedChainFollower.Original = certificates.acquisition.originals.head
  private def reject(stage: String, why: String): Nothing = throw Stop(Failure.Rejected(stage, why))
  private def unsupported(why: String): Nothing = throw Stop(Failure.Unsupported(why))
  private def get[A](stage: String, value: Either[String, A]): A =
    value.fold(reject(stage, _), identity)
  private def protect[A](body: => A): Either[Failure, A] =
    try Right(body)
    catch
      case Stop(f) => Left(f)
      case NonFatal(e) =>
        Left(Failure.Rejected("input", Option(e.getMessage).getOrElse(e.getClass.getName)))
  private def text(raw: Bytes): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw.toArray)).toString
  private def raw(text: String): Bytes = Bytes.fromArray(text.getBytes(StandardCharsets.UTF_8))
  private def hash(value: String, width: Int): Bytes =
    val b = get("source", Bytes.fromHex(value))
    require(b.size == width && b.hex == value, "canonical hash required")
    b
  private def obj(j: ReferenceJson.Json): Map[String, ReferenceJson.Json] = j match
    case ReferenceJson.Json.Obj(v) => v
    case _                         => reject("source", "object required")
  private def arr(n: Node): Vector[Node] = n.value match
    case Value.Arr(v) => v
    case _            => reject("original", "array required")
  private def decode(b: Bytes): Node =
    get("original", Cbor.decode(b, Cbor.Limits(4194304, 48, 200000, 4194304)))
  private def hex(raw: Bytes): Bytes = get("original", Bytes.fromHex(text(raw).trim))
  private def read(path: Path): Bytes =
    val in = Files.newInputStream(path)
    val b =
      try in.readNBytes(4194305)
      finally in.close()
    require(b.nonEmpty && b.length <= 4194304, "bounded nonempty original required")
    Bytes.fromArray(b)

  def load(directory: Path): Either[Failure, Checked] = protect {
    val manifest = read(directory.resolve("coherent-branch-input.md"))
    val original = sources.values.map(n => n -> read(directory.resolve(n))).toMap
    getInput(bind(manifest, original))
  }
  private def getInput[A](r: Either[Failure, A]): A = r.fold(f => throw Stop(f), identity)

  private[lab] def bind(manifest: Bytes, original: Map[String, Bytes]): Either[Failure, Checked] =
    protect {
      require(manifest.size <= 8192, "manifest bound")
      val entries = text(manifest).linesIterator.map { line =>
        val fields = line.split("\t", -1)
        require(fields.length == 2 && fields.forall(_.nonEmpty), "strict manifest TSV required")
        fields(0) -> fields(1)
      }.toVector
      require(entries.map(_._1).distinct.size == entries.size, "duplicate manifest key")
      val fields = entries.toMap
      require(
        fields.keySet == sources.keySet + "format" && fields(
          "format"
        ) == "coherent-branch-input-v1",
        "exact input manifest required"
      )
      require(original.keySet == sources.values.toSet, "exact original inputs required")
      val pins = sources.map { (key, file) =>
        val b = original(file)
        require(
          b != null && b.value != null && b.size > 0 && b.size <= 4194304,
          "bounded original required"
        )
        val pin = hash(fields(key), 32)
        if ClusterHeaderObservation.sha256(b) != pin then
          reject("source", "digest mismatch: " + file)
        key -> pin
      }
      def json(name: String) = ReferenceJson.parse(original(name))
      import ReferenceJson.{field, uint, string, array}
      val genesis = json("transfer-genesis.md")
      if string(field(genesis, "networkId")) != "Testnet" || uint(
          field(genesis, "networkMagic")
        ) != 1082026
      then unsupported("isolated testnet context required")
      val epochLength = uint(field(genesis, "epochLength"));
      require(epochLength > 0, "epoch length")
      val tips = array(json("pre-tips.md"));
      require(tips.size >= 2 && tips.size <= 32, "stable tip brackets required")
      val points = tips.map { t =>
        if string(field(t, "era")) != "Conway" then unsupported("Conway required")
        (
          Certificate.Point(
            hash(string(field(t, "hash")), 32),
            uint(field(t, "slot")),
            uint(field(t, "block"))
          ),
          uint(field(t, "epoch"))
        )
      }
      require(points.distinct.size == 1, "unstable anchor bracket")
      val (anchor, epoch) = points.head
      if anchor.slot / epochLength != epoch then unsupported("fixed-length epoch profile")
      val ledger = json("pre-ledger-state.md"); val protocol = json("pre-protocol-state.md")
      require(
        uint(field(ledger, "lastEpoch")) == epoch && uint(
          field(protocol, "lastSlot")
        ) == anchor.slot,
        "source anchor slot/epoch mismatch"
      )
      val parameters = json("pre-parameters.md")
      if uint(field(parameters, "protocolVersion", "major")) != 9 ||
        uint(field(parameters, "protocolVersion", "minor")) != 0
      then unsupported("ledger PV9.0 required")
      val registrations = obj(field(ledger, "stakeDistrib", "unPoolDistr")).map { (id, p) =>
        hash(id, 28) -> hash(string(field(p, "individualPoolStakeVrf")), 32)
      }
      val lifetime = uint(field(genesis, "maxKESEvolutions"));
      require(lifetime > 0 && lifetime <= 64, "Sum6 lifetime")
      val context = get(
        "certificate-context",
        Certificate.Context.checked(
          pins("genesisSha256"),
          pins("preLedgerSha256"),
          epoch * epochLength,
          (epoch + 1) * epochLength - 1,
          uint(field(genesis, "slotsPerKESPeriod")),
          lifetime.toInt,
          registrations
        )
      )
      val counters = obj(field(protocol, "oCertCounters")).map((id, n) => hash(id, 28) -> uint(n))
      val seed = get(
        "certificate-context",
        Certificate.seed(context, anchor, counters, pins("preProtocolSha256"))
      )
      val rows = text(original("scala-transfer.md")).linesIterator
        .filter(_.nonEmpty)
        .flatMap { line =>
          val j = ReferenceJson.parse(raw(line))
          j match
            case ReferenceJson.Json.Obj(f)
                if f.get("record").contains(ReferenceJson.Json.Str("transfer-range-block")) =>
              Some(
                BoundedChainFollower.Original(
                  get("capture", Bytes.fromHex(string(field(j, "headerEnvelopeHex")))),
                  get("capture", Bytes.fromHex(string(field(j, "rawBlockHex"))))
                )
              )
            case _ => None
        }
        .toVector
      if rows.size != 1 then unsupported("exactly one captured successor required")
      val header = get("acquisition", ReferenceCaptureCommand.header(rows.head.envelope))
      if header.major != 11 || header.minor != 2 then
        unsupported("observed header version 11.2 required")
      if header.slot / epochLength != epoch then unsupported("one supplied epoch only")
      require(
        header.parent == anchor.hash && header.slot > anchor.slot && header.blockNo == anchor.blockNo + 1,
        "successor does not extend anchor"
      )
      val point =
        ChainSync.Point.Block(get("anchor", ChainSync.UInt64.from(anchor.slot)), anchor.hash)
      val acquired = get("acquisition", BoundedChainFollower.checked(point, rows))
      val transaction = hex(original("signed-transaction-cbor.md"))
      if transaction.size > 65536 then unsupported("bounded transaction memo required")
      val tx = arr(decode(transaction));
      require(tx.size == 4, "four-field original transaction required")
      tx(2).value match
        case Value.Bool(true)  => ()
        case Value.Bool(false) => unsupported("phase-2 transaction")
        case _                 => reject("original", "boolean transaction validity required")
      tx(3).value match
        case Value.Null => ()
        case Value.Map(_) | Value.Arr(_) | Value.Tag(_, _) =>
          unsupported("auxiliary transaction data")
        case _ => reject("original", "malformed auxiliary transaction data")
      val outer = arr(decode(rows.head.block)); require(outer.size == 2, "block envelope")
      val block = arr(outer(1)); require(block.size == 5, "Conway block arity")
      if arr(block(4)).nonEmpty then unsupported("invalid transaction indices")
      block(3).value match
        case Value.Map(v) if v.isEmpty => ()
        case Value.Map(_)              => unsupported("auxiliary data")
        case _                         => reject("original", "auxiliary map required")
      val bodies = arr(block(1)); val witnesses = arr(block(2))
      if bodies.size != 1 || witnesses.size != 1 then
        unsupported("exactly one transaction required")
      require(
        bodies.head.original == tx(0).original && witnesses.head.original == tx(1).original,
        "original transaction body/witness inclusion mismatch"
      )
      val preUtxo = hex(original("pre-utxo-cbor.md"))
      decode(preUtxo).value match
        case Value.Map(_) => ()
        case _            => reject("pre-state", "UTxO CBOR map required")
      obj(json("pre-utxo.md")) // Retained attribution only; the ledger projection uses pinned CBOR.
      val fees = uint(field(ledger, "stateBefore", "esLState", "utxoState", "fees"))
      require(fees <= ChainSync.UInt64.Max, "fee pot Word64 bound")
      val prepared = CertificateBranch.Prepared(context, seed, acquired)
      val eligibility = get(
        "eligibility-context",
        PraosEligibilityContext.fromBoundSources(
          prepared,
          original("transfer-genesis.md"),
          original("pre-ledger-state.md"),
          original("pre-protocol-state.md"),
          pins("preProtocolSha256")
        )
      )
      val recipe = sources.keys.toVector.sorted
        .map(k => k + "=" + pins(k).hex)
        .mkString("coherent-branch-input-v1\n", "\n", "\n")
      val preRecipe = sources.keys.toVector.sorted
        .filterNot(k => k == "transactionSha256" || k == "captureSha256")
        .map(k => k + "=" + pins(k).hex)
        .mkString("coherent-pre-state-v1\n", "\n", "\n")
      new Checked(
        ClusterHeaderObservation.sha256(raw(recipe)),
        ClusterHeaderObservation.sha256(raw(preRecipe)),
        original,
        pins,
        transaction,
        tx(0),
        tx(1),
        preUtxo,
        fees,
        parameters,
        prepared,
        eligibility,
        header
      )
    }
