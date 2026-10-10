// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.header.PraosCertificateState as Certificate
import lab.network.ChainSync
import lab.header.PraosEligibility
import lab.ledger.ClusterTransition
import scala.util.control.NonFatal

/** Pre-only original ownership. Construction is not ledger or consensus acceptance. */
object SequenceInput:
  enum Failure:
    case Unsupported(feature: String)
    case Rejected(stage: String, detail: String)
  private final case class Stop(failure: Failure) extends RuntimeException
  val sources: Map[String, String] = Map(
    "genesisSha256" -> "transfer-genesis.md",
    "preTipsSha256" -> "pre-tips.md",
    "preProtocolSha256" -> "pre-protocol-state.md",
    "preLedgerSha256" -> "pre-ledger-state.md",
    "preParametersSha256" -> "pre-parameters.md",
    "preUtxoSha256" -> "pre-utxo.md",
    "preUtxoCborSha256" -> "pre-utxo-cbor.md"
  )
  val ProfileId = "coherent-sequence-context-v1"
  final class Context private[SequenceInput] (
      val id: Bytes,
      val originals: Map[String, Bytes],
      val sourcePins: Map[String, Bytes],
      val certificates: Certificate.Context,
      val certificateSeed: Certificate.State,
      val nonces: PraosNonceSnapshot.Prepared,
      val eligibility: PraosEligibility.Context,
      val ledger: ClusterTransition.State,
      val epoch: BigInt,
      private[lab] val wholeUTxO: Bytes,
      private[lab] val stakeSourceId: Bytes,
      private[lab] val protocolAttributionDigest: Bytes,
      private[lab] val diagnosticOnly: Boolean
  ):
    val suppliedCheckpoint = true
    val validatedTip = false
    val authenticatedSnapshot = false
    val referenceSnapshotAtomic = false

  /** Structural input only. Memos are reconstructed ledger API adapters, not original submitted
    * transaction envelopes. Each ordinal retains the exact original body and witness spans.
    */
  final class Block private[SequenceInput] (
      val original: BoundedChainFollower.Original,
      val header: ReferenceCaptureCommand.Header,
      val transactionMemos: Vector[Bytes]
  ):
    val validatedTip = false

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

  def load(directory: Path): Either[Failure, Context] = protect {
    val manifest = read(directory.resolve("coherent-sequence-context.md"))
    val original = sources.values.map(n => n -> read(directory.resolve(n))).toMap
    getInput(bind(manifest, original))
  }
  private def getInput[A](r: Either[Failure, A]): A = r.fold(f => throw Stop(f), identity)

  private[lab] def bind(manifest: Bytes, original: Map[String, Bytes]): Either[Failure, Context] =
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
        ) == "coherent-sequence-context-v1",
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
      val point =
        ChainSync.Point.Block(get("anchor", ChainSync.UInt64.from(anchor.slot)), anchor.hash)
      val acquired = get("acquisition", BoundedChainFollower.checked(point, Vector.empty))
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
      val nonces = get(
        "nonce-context",
        PraosNonceSnapshot.bind(
          context,
          seed,
          original("transfer-genesis.md"),
          original("pre-protocol-state.md"),
          pins("preProtocolSha256")
        )
      )
      val recipe = sources.keys.toVector.sorted
        .map(k => k + "=" + pins(k).hex)
        .mkString(ProfileId + "\n", "\n", "\n")
      val id = ClusterHeaderObservation.sha256(raw(recipe))
      def ledgerGet[A](result: ClusterTransition.Checked[A]): A =
        result.fold(
          {
            case ClusterTransition.Failure.Unsupported(feature) => unsupported(feature)
            case other => reject("ledger-context", other.toString)
          },
          identity
        )
      val environment = ledgerGet(
        ClusterTransition.environment(
          pins("genesisSha256"),
          pins("preParametersSha256"),
          1082026L,
          epoch,
          9,
          0,
          uint(field(parameters, "txFeePerByte")),
          uint(field(parameters, "txFeeFixed")),
          uint(field(parameters, "maxTxSize")),
          uint(field(parameters, "utxoCostPerByte"))
        )
      )
      val checkpoint =
        ledgerGet(ClusterTransition.checkpoint(environment, preUtxo, fees, anchor.slot, id))
      val stakeSourceId = ClusterHeaderObservation.sha256(
        Bytes(pins("preLedgerSha256").value ++ ClusterHeaderObservation.sha256(preUtxo).value)
      )
      new Context(
        id,
        original,
        pins,
        context,
        seed,
        nonces,
        eligibility.context,
        checkpoint,
        epoch,
        preUtxo,
        stakeSourceId,
        pins("preProtocolSha256"),
        diagnosticOnly = false
      )
    }

  /** Internal supplied-state diagnostic only; this does not confer native admission. */
  private[lab] def fromNativeDiagnostic(
      joined: NativeLedgerV2.Checked,
      expectedJoinId: Bytes
  ): Either[Failure, Context] = protect {
    require(
      joined != null && expectedJoinId != null && expectedJoinId.value != null &&
        expectedJoinId.size == 32 && joined.id == expectedJoinId,
      "native diagnostic expected join identity"
    )
    get("native-geometry", NativeLedgerV2.checkCrossingGeometry(joined))
    val epoch = joined.ledger.epochComponents
    val globals = joined.ledger.globals
    val anchor = joined.point
    require(
      globals.epoch == 0 && epoch.parameters.epoch == 0 &&
        anchor.slot >= 0 && anchor.slot < globals.stabilityWindow &&
        joined.crossingBlockers.isEmpty &&
        epoch.reward.point == anchor && epoch.reward.bindingId == epoch.id &&
        epoch.reward.original.hex == "80" &&
        epoch.reward.componentSHA256 == ClusterHeaderObservation.sha256(epoch.reward.original),
      "native diagnostic early epoch-zero Absent anchor required"
    )
    val originals = epoch.originals ++ joined.protocol.originals
    val pins = epoch.pins ++ joined.protocol.sourcePins
    require(
      originals.keySet == NativeLedgerV2.InputNames && pins.keySet == NativeLedgerV2.InputNames &&
        originals.forall((name, bytes) => ClusterHeaderObservation.sha256(bytes) == pins(name)),
      "native diagnostic exact ten original/pin sources"
    )
    val utxo = originals("original-whole-utxo.cbor")
    require(
      epoch.stake.utxo == utxo && epoch.stake.sourceId == epoch.id,
      "native diagnostic stake source"
    )
    val id = ClusterHeaderObservation.sha256(
      raw("native-sequence-diagnostic-context-v1\n" + joined.id.hex + "\n")
    )
    def ledgerGet[A](result: ClusterTransition.Checked[A]): A =
      result.fold(
        {
          case ClusterTransition.Failure.Unsupported(feature) => unsupported(feature)
          case other => reject("native-ledger-context", other.toString)
        },
        identity
      )
    val parameters = epoch.parameters.current
    require(
      globals.networkMagic > 0 && globals.networkMagic <= BigInt("ffffffff", 16),
      "native diagnostic network magic"
    )
    val baseEnvironment = ledgerGet(
      ClusterTransition.environment(
        globals.genesisSHA256,
        parameters.sha256,
        globals.networkMagic.toLong,
        globals.epoch,
        9,
        0,
        parameters.feePerByte,
        parameters.feeFixed,
        parameters.maxTxSize,
        parameters.coinsPerUTxOByte
      )
    )
    val environment = epoch.admissionProfile match
      case lab.submission.AdmissionProfile.PlutusV3 =>
        val modelStream = Option(getClass.getResourceAsStream("/plutus-pv9/cost-model.json"))
          .getOrElse(throw new IllegalArgumentException("registered Plutus cost model unavailable"))
        val model =
          try Bytes.fromArray(modelStream.readNBytes(16385))
          finally modelStream.close()
        val checkedParameters = get(
          "plutus-parameters",
          lab.ledger.PlutusParameters.decode(parameters.original, parameters.sha256, model)
        )
        val genesis = epoch.parameters.genesisOriginal
        require(
          ClusterHeaderObservation.sha256(genesis) == globals.genesisSHA256,
          "Plutus timing genesis pin"
        )
        val start = java.time.Instant.parse(
          ReferenceJson.string(ReferenceJson.field(ReferenceJson.parse(genesis), "systemStart"))
        )
        require(start.getNano % 1000000 == 0, "Plutus system start requires exact milliseconds")
        val duration = epoch.parameters.slotLength
        val numerator = duration.numerator * 1000
        val divisor = numerator.gcd(duration.denominator)
        val time = lab.ledger.PlutusContextInput.SlotTime(
          BigInt(start.getEpochSecond) * 1000 + start.getNano / 1000000,
          numerator / divisor,
          duration.denominator / divisor,
          globals.genesisSHA256
        )
        val profile = get(
          "plutus-environment",
          lab.ledger.PlutusEnvironment.bind(baseEnvironment, checkedParameters, time, 0)
        )
        ledgerGet(ClusterTransition.withPlutus(baseEnvironment, profile))
      case _ => baseEnvironment
    val checkpoint = ledgerGet(
      ClusterTransition.checkpoint(environment, utxo, epoch.pots.fees, anchor.slot, id)
    )
    new Context(
      id,
      originals,
      pins,
      joined.protocol.certificates,
      joined.protocol.certificateSeed,
      joined.protocol.nonces,
      joined.protocol.eligibility,
      checkpoint,
      globals.epoch,
      utxo,
      epoch.stake.sourceId,
      pins("original-debug-protocol.cbor"),
      diagnosticOnly = true
    )
  }

  /** Rebind the exact seven original byte sources; no fabricated snapshots or downloaded tips. */
  def fromInput(input: BranchInput.Checked): Either[Failure, Context] = protect {
    require(input != null, "input required")
    val originals = sources.values.map(name => name -> input.originals(name)).toMap
    val manifest = raw(
      "format\t" + ProfileId + "\n" + sources.keys.toVector.sorted
        .map(k => k + "\t" + input.sourcePins(k).hex)
        .mkString("\n") + "\n"
    )
    getInput(bind(manifest, originals))
  }

  def block(original: BoundedChainFollower.Original): Either[Failure, Block] = protect {
    require(
      original != null && original.envelope != null && original.block != null,
      "original header and block required"
    )
    require(original.block.size > 0 && original.block.size <= 1048576, "one MiB block bound")
    val header = get("header", ReferenceCaptureCommand.header(original.envelope))
    if header.major != 11 || header.minor != 2 then
      unsupported("observed header version 11.2 required")
    val outer =
      arr(get("original", Cbor.decode(original.block, Cbor.Limits(1048576, 48, 200000, 1048576))))
    require(outer.size == 2, "block envelope")
    outer(0).value match
      case Value.UInt(n) if n == 7 => ()
      case _                       => unsupported("Conway block era index 7 required")
    val fields = arr(outer(1))
    require(fields.size == 5, "Conway block arity")
    require(fields(0).original == header.raw, "header/block original identity mismatch")
    val bodies = arr(fields(1)); val witnesses = arr(fields(2))
    require(bodies.size == witnesses.size, "body/witness count mismatch")
    if bodies.size > 16 then unsupported("sixteen transaction block bound")
    fields(3).value match
      case Value.Map(v) if v.isEmpty => ()
      case Value.Map(_)              => unsupported("auxiliary data")
      case _                         => reject("original", "auxiliary map required")
    if arr(fields(4)).nonEmpty then unsupported("invalid transaction indices")
    val memos = bodies.zip(witnesses).map { (body, witness) =>
      val memo = Bytes(
        Vector(0x84.toByte) ++ body.original.value ++ witness.original.value ++
          Vector(0xf5.toByte, 0xf6.toByte)
      )
      if memo.size > 65536 then unsupported("bounded transaction memo required")
      memo
    }
    require(memos.map(_.size.toLong).sum <= 1048576, "one MiB memo aggregate bound")
    new Block(original, header, memos)
  }
