// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode}
import java.nio.file.Path
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayStake as S, PlutusOutput, TxIn}
import lab.header.PraosNonceEvolution as N
import lab.submission.{AdmissionProfile, SignedTransaction, StatePin}
import ReferenceJson.{Json as J, field, string, uint}
import PlutusResearchIO.{get, obj, hash, read, sha, record, text, num, bool, point}

/** Independent, Test-only observational comparison. Never constructs or imports runtime state. */
object PlutusServiceCompareMain extends IOApp:
  private def parse(b: Bytes): Node = get(Cbor.decode(b, Cbor.Limits(1048576, 48, 100000, 1048576)))
  private def arr(n: Node, count: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == count => xs
    case _                             => throw new IllegalArgumentException("array width")
  private def number(n: Node): BigInt = n.value match
    case V.UInt(x) if x >= 0 && x <= StatePin.MaxUInt64 => x
    case _ => throw new IllegalArgumentException("uint64 required")
  private def fields(n: Node): Map[BigInt, Node] = n.value match
    case V.Map(xs) =>
      val rows = xs.map((k, v) => number(k) -> v)
      require(rows.map(_._1).distinct.size == rows.size, "duplicate map key")
      rows.toMap
    case _ => throw new IllegalArgumentException("map required")
  private def oneRef(n: Node): TxIn =
    val plain = n.value match
      case V.Tag(t, inner) if t == 258 => inner
      case _                           => n
    val xs = arr(arr(plain, 1).head, 2)
    val h = xs.head.value match
      case V.ByteString(b) => b
      case _               => throw new IllegalArgumentException("input hash")
    get(TxIn.create(h, number(xs(1))))
  private def meaning(out: PlutusOutput.Output) = (out.address, out.coin, out.datum.map(_.original))
  private def ref(in: TxIn): String = in.id.hex + "#" + in.index
  private def rows(j: J): Vector[J] = j match
    case J.Arr(xs) if xs.size <= 4096 => xs
    case _                            => throw new IllegalArgumentException("bounded JSON rows")
  private def nonce(j: J): N.Nonce = j match
    case J.Lit("null") => N.Nonce.Neutral
    case _             => N.Nonce.Hash(hash(j))

  private[lab] def compareEffects(
      beforeRaw: Bytes,
      actualRaw: Bytes,
      terminalRaw: Bytes,
      txs: Vector[SignedTransaction]
  ): Vector[J] =
    require(
      txs.size == 2 && txs.map(_.transactionId).distinct.size == 2,
      "two independent transactions"
    )
    val before = get(PlutusOutput.snapshot(beforeRaw, 0)).outputs
    val actual = get(PlutusOutput.snapshot(actualRaw, 0)).outputs
    val terminal = get(PlutusOutput.snapshot(terminalRaw, 0)).outputs
    require(
      actual.view.mapValues(meaning).toMap == terminal.view.mapValues(meaning).toMap,
      "complete endpoint/terminal UTxO mismatch"
    )
    val parsed = txs.map { tx =>
      val b = fields(parse(tx.originalBody))
      require(
        tx.isValid && tx.originalAuxiliary == Bytes.fromArray(Array(0xf6.toByte)),
        "valid/no auxiliary required"
      )
      require(
        Set(BigInt(0), BigInt(1), BigInt(2), BigInt(11), BigInt(13)).subsetOf(b.keySet) && b.keySet
          .subsetOf(
            Set(BigInt(0), BigInt(1), BigInt(2), BigInt(3), BigInt(8), BigInt(11), BigInt(13))
          ),
        "restricted body fields"
      )
      val spent = oneRef(b(0)); val collateral = oneRef(b(13))
      require(number(b(2)) == 300000, "exact fee")
      val witnesses = fields(parse(tx.originalWitnesses))
      require(witnesses.keySet == Set(BigInt(0), BigInt(5), BigInt(7)), "restricted witnesses")
      val scripts = witnesses(7).value match
        case V.Tag(t, n) if t == 258 => arr(n, 1)
        case _                       => arr(witnesses(7), 1)
      val script = scripts.head.value match
        case V.ByteString(raw) => raw
        case _                 => throw new IllegalArgumentException("script bytes required")
      require(
        sha(script).hex == "57fb50f08ffc1222cbe2b652db3dcfed0f714da98f8170cb104aee2bde4070f6",
        "registered script original"
      )
      val red = witnesses(5).value match
        case V.Map(xs) if xs.size == 1 => xs.head
        case _ => throw new IllegalArgumentException("one redeemer required")
      val pointer = arr(red._1, 2); val redeemer = arr(red._2, 2); val units = arr(redeemer(1), 2)
      require(
        number(pointer(0)) == 0 && number(pointer(1)) == 0 && number(redeemer(0)) == 7 &&
          number(units(0)) == 100000 && number(units(1)) == 30000000,
        "registered redeemer/budget"
      )
      val outs = arr(b(1), 1).zipWithIndex.map { (out, i) =>
        get(TxIn.create(tx.transactionId, BigInt(i))) -> get(PlutusOutput.decode(out.original, 0))
      }.toMap
      require(before.contains(spent) && before.contains(collateral), "funded inputs required")
      require(
        before(spent).paymentCredential == lab.Blake2b.hash224
          .hash(Bytes(Vector(3.toByte) ++ script.value)),
        "script input credential binding"
      )
      require(
        before(spent).coin == 20000000 && before(collateral).coin == 5000000 &&
          before(spent).coin == outs.values.map(_.coin).sum + 300000,
        "exact spend conservation"
      )
      (tx, spent, collateral, outs)
    }
    val spends = parsed.map(_._2).toSet
    val collaterals = parsed.map(_._3).toSet
    require(
      (spends ++ collaterals).size == 4 &&
        (spends ++ collaterals).map(_.id).size == 1 &&
        spends.map(_.index) == Set(BigInt(0), BigInt(2)) && collaterals
          .map(_.index) == Set(BigInt(1), BigInt(3)),
      "funding pair domain"
    )
    parsed.foreach { (_, s, c, _) => require(c.index == s.index + 1, "matched collateral pair") }
    val created = parsed.flatMap(_._4).toMap
    require(
      before.collect { case (in, out) if out.datum.nonEmpty => in }.toSet == spends &&
        actual.values.forall(_.datum.isEmpty),
      "exact consumed datums/datumless endpoint"
    )
    require(
      actual.keySet == (before.keySet -- spends) ++ created.keySet,
      "exact endpoint input/output domain"
    )
    require(
      (before.keySet -- spends).forall(in => meaning(actual(in)) == meaning(before(in))) &&
        created.forall((in, out) => meaning(actual(in)) == meaning(out)),
      "unrelated/collateral/created output mismatch"
    )
    require(
      collaterals.forall(in => terminal(in).original == before(in).original),
      "terminal collateral original changed"
    )
    parsed.map { (tx, s, c, _) =>
      record(
        "transactionId" -> text(tx.transactionId.hex),
        "envelopeSHA256" -> text(tx.envelopeSHA256.hex),
        "bodySHA256" -> text(sha(tx.originalBody).hex),
        "witnessesSHA256" -> text(sha(tx.originalWitnesses).hex),
        "spent" -> text(ref(s)),
        "collateral" -> text(ref(c))
      )
    }

  private[lab] def compareMaps(
      beforeRaw: Bytes,
      actualRaw: Bytes,
      terminalRaw: Bytes,
      txs: Vector[SignedTransaction],
      feesBefore: BigInt,
      feesAfter: BigInt
  ): Vector[J] =
    val result = compareEffects(beforeRaw, actualRaw, terminalRaw, txs)
    require(feesAfter == feesBefore + 600000, "exact fee pot delta")
    result

  private[lab] def compare(args: List[String]): J =
    require(args.size == 7, "INITIAL MANIFESTPIN OUTPUT ENDPOINT_EXCHANGE TX1 TX2 RESULT")
    val initial = PlutusResearchIO.initial(Path.of(args(0)), args(1), AdmissionProfile.PlutusV3)
    val output = Path.of(args(2)); val exchange = Path.of(args(3))
    val raw = read(output.resolve("terminal-observation.json"), 65536)
    val t = ReferenceJson.parse(raw)
    require(
      obj(t).keySet == Set(
        "schema",
        "diagnosticOnly",
        "restartSupported",
        "fullLedgerValidated",
        "pin",
        "sourceJoinId",
        "initialManifestSHA256",
        "outputMapFile",
        "outputMapSHA256",
        "fees",
        "epoch",
        "validationSlot",
        "instantaneousStake",
        "representedProtocol"
      ),
      "terminal exact field set"
    )
    require(
      string(field(t, "schema")) == "plutus-service-terminal-observation-v1" &&
        field(t, "diagnosticOnly") == bool(true) && field(t, "restartSupported") == bool(false) &&
        field(t, "fullLedgerValidated") == bool(false),
      "terminal diagnostic contract"
    )
    require(
      hash(field(t, "sourceJoinId")) == initial.id && string(
        field(t, "initialManifestSHA256")
      ) == args(1),
      "terminal source pins"
    )
    val p = field(t, "pin")
    require(
      obj(p).keySet == Set(
        "ownerId",
        "generation",
        "point",
        "coherentStateId",
        "ledgerStateId",
        "environmentId",
        "validationSlot",
        "profileId"
      ),
      "terminal pin fields"
    )
    val pin = get(
      StatePin.checked(
        hash(field(p, "ownerId")),
        uint(field(p, "generation")),
        point(field(p, "point")),
        hash(field(p, "coherentStateId")),
        hash(field(p, "ledgerStateId")),
        hash(field(p, "environmentId")),
        uint(field(p, "validationSlot")),
        string(field(p, "profileId"))
      )
    )
    require(
      pin.profileId == AdmissionProfile.PlutusV3.id && pin.validationSlot == pin.point.slot &&
        pin.point.slot >= initial.acquisition.anchor.slot && pin.point.slot < 1000 &&
        uint(field(t, "validationSlot")) == pin.validationSlot && uint(field(t, "epoch")) == 0 &&
        initial.ledger.globals.epoch == 0,
      "same epoch-zero terminal"
    )
    require(
      string(field(t, "outputMapFile")) == "terminal-output-map.cbor",
      "fixed terminal filename"
    )
    val terminal = read(output.resolve("terminal-output-map.cbor"), 1048576)
    require(sha(terminal) == hash(field(t, "outputMapSHA256")), "terminal output map pin")
    val ready = ReferenceJson.parse(read(exchange.resolve("endpoint-ready.json"), 16384))
    val endpoint = PlutusResearchIO.endpoint(exchange, ready, pin.point, initial)
    require(endpoint.networkMagic == initial.acquisition.networkMagic, "endpoint network")
    val sources = endpoint.originals
    val whole = sources("original-whole-utxo.cbor")
    get(
      EndpointLedgerChecks.checkReplacement(
        sources("derived-full-epoch-seed.cbor"),
        sources("original-debug-epoch.cbor"),
        whole
      )
    )
    val fs = arr(parse(sources("derived-full-epoch-seed.cbor")), 7)
    val es = arr(fs(3), 4); val us = arr(arr(es(1), 2)(1), 6)
    require(
      number(fs(0)) == 0 && number(us(1)) == 0 && number(us(5)) == 0,
      "same epoch/deposits/donations"
    )
    val feesBefore = initial.ledger.epochComponents.pots.fees; val feesAfter = number(us(2))
    require(feesAfter == uint(field(t, "fees")), "terminal fee pot")
    val txs = args
      .slice(4, 6)
      .map(path => get(SignedTransaction.checked(read(Path.of(path), 65536))))
      .toVector
    val identities = compareMaps(
      initial.ledger.epochComponents.stake.utxo,
      whole,
      terminal,
      txs,
      feesBefore,
      feesAfter
    )
    val exported = rows(field(t, "instantaneousStake")).map { row =>
      require(obj(row).keySet == Set("script", "hash", "coin"), "stake row fields")
      val script = field(row, "script") match
        case J.Lit("true")  => true
        case J.Lit("false") => false
        case _              => throw new IllegalArgumentException("stake credential flag")
      val h = get(Bytes.fromHex(string(field(row, "hash"))))
      require(h.size == 28, "stake credential hash")
      S.Credential(script, h) -> uint(field(row, "coin"))
    }
    require(exported.map(_._1).distinct.size == exported.size, "duplicate stake credentials")
    val nativeStake = us(4).value match
      case V.Map(xs) =>
        xs.map { (k, v) =>
          val c = arr(k, 2); val kind = number(c(0)); require(kind <= 1, "credential kind")
          val h = c(1).value match
            case V.ByteString(b) if b.size == 28 => b
            case _ => throw new IllegalArgumentException("native credential hash")
          S.Credential(kind == 1, h) -> number(v)
        }
      case _ => throw new IllegalArgumentException("native stake map")
    require(
      nativeStake
        .map(_._1)
        .distinct
        .size == nativeStake.size && nativeStake.toMap == exported.toMap &&
        get(S.recomputePlutus(whole, 0)) == exported.toMap,
      "instantaneous stake semantics"
    )
    val snapshots = initial.ledger.epochComponents.stake.snapshots
    val snaps = arr(es(2), 4)
    get(EndpointLedgerChecks.checkSnapshot(snaps(0).original, snapshots.mark))
    get(EndpointLedgerChecks.checkSnapshot(snaps(1).original, snapshots.set))
    get(EndpointLedgerChecks.checkSnapshot(snaps(2).original, snapshots.go))
    require(number(snaps(3)) == snapshots.fees, "endpoint snapshot fees unchanged")
    val pr = field(t, "representedProtocol")
    require(
      obj(pr).keySet == Set(
        "lastSlot",
        "counters",
        "evolving",
        "candidate",
        "epoch",
        "lab",
        "lastEpochBlock",
        "previousEpoch"
      ),
      "protocol fields"
    )
    val previous = field(pr, "previousEpoch")
    require(
      obj(previous).keySet == Set("present", "value") && field(previous, "present") == bool(true),
      "previous epoch presence"
    )
    val counters = rows(field(pr, "counters")).map { row =>
      require(obj(row).keySet == Set("issuer", "counter"), "counter fields")
      val h = get(Bytes.fromHex(string(field(row, "issuer"))));
      require(h.size == 28, "issuer width")
      h -> uint(field(row, "counter"))
    }
    require(
      counters.map(_._1).distinct.size == counters.size && uint(
        field(pr, "lastSlot")
      ) == pin.point.slot,
      "counter uniqueness/protocol slot"
    )
    get(
      NativeEndpointProtocol.compareValues(
        get(NativePraosProtocol.decode(sources("original-debug-protocol.cbor"))),
        pin.point.slot,
        N.Fields(
          nonce(field(pr, "evolving")),
          nonce(field(pr, "candidate")),
          nonce(field(pr, "epoch")),
          Some(nonce(field(previous, "value"))),
          nonce(field(pr, "lab")),
          nonce(field(pr, "lastEpochBlock"))
        ),
        counters.toMap
      )
    )
    record(
      "schema" -> text("plutus-service-endpoint-comparison-v1"),
      "terminalPoint" -> point(pin.point),
      "sourceJoinId" -> text(initial.id.hex),
      "initialManifestSHA256" -> text(args(1)),
      "terminalObservationSHA256" -> text(sha(raw).hex),
      "outputMapSHA256" -> text(sha(terminal).hex),
      "endpointAcquisitionId" -> text(endpoint.id.hex),
      "endpointWholeUtxoSHA256" -> text(sha(whole).hex),
      "feesBefore" -> num(feesBefore),
      "feesAfter" -> num(feesAfter),
      "entries" -> num(get(PlutusOutput.snapshot(whole, 0)).outputs.size),
      "transactions" -> J.Arr(identities),
      "completeUtxoEqual" -> bool(true),
      "collateralPreserved" -> bool(true),
      "instantaneousStakeEqual" -> bool(true),
      "endpointSnapshotsUnchanged" -> bool(true),
      "representedProtocolEqual" -> bool(true),
      "fullLedgerValidated" -> bool(false),
      "restartSupported" -> bool(false),
      "diagnosticOnly" -> bool(true)
    )

  def run(args: List[String]): IO[ExitCode] =
    IO.blocking(compare(args))
      .flatMap(result => PlutusResearchIO.save(Path.of(args(6)), result))
      .as(ExitCode.Success)
      .handleErrorWith(e =>
        IO.println("PLUTUS_SERVICE_COMPARISON_FAILED: " + e.getMessage).as(ExitCode.Error)
      )
