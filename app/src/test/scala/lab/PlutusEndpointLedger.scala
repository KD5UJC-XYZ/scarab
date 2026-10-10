// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState.Point
import lab.ledger.{ConwayStake as S, PlutusOutput, TxIn}
import lab.submission.SignedTransaction
import scala.util.control.NonFatal

/** Test-only same-epoch, one successful Plutus spend comparison. No reward/governance parity. */
private[lab] object PlutusEndpointLedger:
  final case class Report(
      acquisitionId: Bytes,
      wholeUtxoSHA256: Bytes,
      feesBefore: BigInt,
      feesAfter: BigInt,
      spent: TxIn,
      collateral: TxIn,
      entries: Int
  ):
    val fullLedgerValidated = false
    val wholeUtxoEqual = true
    val instantaneousStakeEqual = true
    val collateralPreserved = true

  private def get[A](v: Either[?, A]): A =
    v.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def parse(raw: Bytes): Node = get(
    Cbor.decode(raw, Cbor.Limits(1048576, 48, 100000, 1048576))
  )
  private def arr(n: Node, count: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == count => xs
    case _                             => throw new IllegalArgumentException("endpoint array width")
  private def uint(n: Node): BigInt = n.value match
    case V.UInt(x) if x >= 0 && x <= (BigInt(1) << 64) - 1 => x
    case _ => throw new IllegalArgumentException("endpoint uint64")
  private def fields(n: Node): Map[BigInt, Node] = n.value match
    case V.Map(xs) =>
      val rows = xs.map((k, v) => uint(k) -> v)
      require(rows.map(_._1).distinct.size == rows.size, "duplicate body key")
      rows.toMap
    case _ => throw new IllegalArgumentException("body map required")
  private def oneRef(n: Node): TxIn =
    val plain = n.value match
      case V.Tag(tag, inner) if tag == 258 => inner
      case _                               => n
    val parts = arr(arr(plain, 1).head, 2)
    val hash = parts.head.value match
      case V.ByteString(b) => b
      case _               => throw new IllegalArgumentException("input hash bytes required")
    get(TxIn.create(hash, uint(parts(1))))
  def input(text: String): Either[String, TxIn] = protect {
    require(text.matches("[0-9a-f]{64}#(0|[1-9][0-9]{0,4})"), "canonical input reference")
    val pair = text.split("#", -1)
    get(TxIn.create(get(Bytes.fromHex(pair(0))), BigInt(pair(1))))
  }
  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def meaning(out: PlutusOutput.Output) =
    (out.address, out.coin, out.datum.map(_.original))

  def compare(
      endpoint: NativeProtocolBootstrap.Acquisition,
      terminal: Point,
      expectedAcquisitionId: Bytes,
      replay: CoherentSequence.State,
      initial: NativeLedgerV2.Checked,
      submitted: SignedTransaction,
      spent: TxIn,
      collateral: TxIn,
      fee: BigInt
  ): Either[String, Report] = protect {
    require(
      endpoint != null && replay != null && initial != null && submitted != null,
      "checked endpoint, initial, replay and submitted original required"
    )
    require(
      endpoint.id == expectedAcquisitionId && endpoint.anchor == terminal &&
        endpoint.networkMagic == initial.acquisition.networkMagic,
      "endpoint acquisition/fullpoint/network binding"
    )
    require(
      replay.certificates.state.tip == terminal && replay.ledger.slot == terminal.slot &&
        replay.ledger.environment.epoch == initial.ledger.globals.epoch &&
        terminal.slot / initial.ledger.globals.geometry.epochLength == initial.ledger.globals.epoch,
      "same-epoch terminal state required"
    )
    require(
      replay.syntheticBoundary.isEmpty && replay.syntheticRewards.isEmpty,
      "same-epoch proof excludes synthetic boundary/rewards"
    )
    val expectedContextId = ClusterHeaderObservation.sha256(
      Bytes.fromArray(
        ("native-sequence-diagnostic-context-v1\n" + initial.id.hex + "\n").getBytes("UTF-8")
      )
    )
    require(replay.contextId == expectedContextId, "initial source join mismatch")
    val network = replay.ledger.environment.plutus
      .map(_.networkId)
      .getOrElse(throw new IllegalArgumentException("checked Plutus ledger required"))
    val sources = endpoint.originals
    require(
      sources.keySet == NativeProtocolBootstrap.InputNames && sources.forall { (name, raw) =>
        ClusterHeaderObservation.sha256(raw) == endpoint.sourcePins(name)
      },
      "endpoint original source pins"
    )
    val whole = sources("original-whole-utxo.cbor")
    val actual = get(PlutusOutput.snapshot(whole, network)).outputs
    val expected = get(PlutusOutput.snapshot(replay.ledger.outputMap, network)).outputs
    val before =
      get(PlutusOutput.snapshot(initial.ledger.epochComponents.stake.utxo, network)).outputs
    require(
      actual.view.mapValues(meaning).toMap == expected.view.mapValues(meaning).toMap,
      "complete endpoint UTxO differs from follower"
    )
    // The only datum-bearing bootstrap output is consumed. Endpoint outputs are therefore in
    // the existing audited coin-only MemPack comparison domain; no datum decoding is bypassed.
    require(
      before.collect { case (ref, out) if out.datum.nonEmpty => ref }.toSet == Set(spent) &&
        actual.values.forall(_.datum.isEmpty),
      "one consumed inline datum and datumless endpoint required"
    )
    get(
      NativeEndpointLedger.checkReplacement(
        sources("derived-full-epoch-seed.cbor"),
        sources("original-debug-epoch.cbor"),
        whole
      )
    )
    val body = fields(parse(submitted.originalBody))
    require(
      submitted.isValid && oneRef(body(0)) == spent && oneRef(body(13)) == collateral &&
        spent != collateral && uint(body(2)) == fee,
      "descriptor/body spending collateral fee mismatch"
    )
    val outputs = body(1).value match
      case V.Arr(xs) if xs.nonEmpty && xs.size <= 128 => xs
      case _ => throw new IllegalArgumentException("bounded outputs required")
    val created = outputs.zipWithIndex.map { (out, index) =>
      get(TxIn.create(submitted.transactionId, BigInt(index))) -> get(
        PlutusOutput.decode(out.original, network)
      )
    }.toMap
    require(
      before.contains(spent) && before.contains(collateral) &&
        !actual.contains(spent) && actual.keySet == (before.keySet - spent) ++ created.keySet,
      "exact successful-spend input/output domain"
    )
    require(
      (before.keySet - spent).forall(ref => meaning(actual(ref)) == meaning(before(ref))) &&
        created.forall((ref, out) => meaning(actual(ref)) == meaning(out)),
      "unrelated or created output differs"
    )
    require(
      meaning(actual(collateral)) == meaning(before(collateral)) &&
        expected(collateral).original == before(collateral).original,
      "collateral must remain present and unchanged"
    )
    require(
      before(spent).coin == created.values.map(_.coin).sum + fee,
      "successful-spend ADA conservation"
    )
    val fs = arr(parse(sources("derived-full-epoch-seed.cbor")), 7)
    val es = arr(fs(3), 4)
    val us = arr(arr(es(1), 2)(1), 6)
    require(uint(fs(0)) == replay.ledger.environment.epoch, "acquired endpoint epoch mismatch")
    val feesBefore = initial.ledger.epochComponents.pots.fees
    val feesAfter = uint(us(2))
    require(
      feesAfter == replay.ledger.fees && feesAfter == feesBefore + fee,
      "endpoint fee pot must equal follower and exact submitted fee delta"
    )
    require(uint(us(1)) == 0 && uint(us(5)) == 0, "deposits/donations remain unsupported")
    val stake = replay.stake.getOrElse(throw new IllegalArgumentException("follower stake missing"))
    val exported = us(4).value match
      case V.Map(xs) if xs.size <= 4096 =>
        val rows = xs.map { (key, value) =>
          val c = arr(key, 2)
          val kind = uint(c(0)); require(kind <= 1, "stake credential kind")
          val hash = c(1).value match
            case V.ByteString(b) if b.size == 28 => b
            case _ => throw new IllegalArgumentException("stake credential hash")
          S.Credential(kind == 1, hash) -> uint(value)
        }
        require(rows.map(_._1).distinct.size == rows.size, "duplicate stake credential")
        rows.toMap
      case _ => throw new IllegalArgumentException("instantaneous stake map")
    require(
      exported == stake.instantaneous && get(S.recomputePlutus(whole, network)) == exported,
      "endpoint/follower/recomputed instantaneous stake mismatch"
    )
    val snaps = arr(es(2), 4)
    get(NativeEndpointLedger.checkSnapshot(snaps(0).original, stake.snapshots.mark))
    get(NativeEndpointLedger.checkSnapshot(snaps(1).original, stake.snapshots.set))
    get(NativeEndpointLedger.checkSnapshot(snaps(2).original, stake.snapshots.go))
    require(uint(snaps(3)) == stake.snapshots.fees, "endpoint snapshot fees mismatch")
    Report(
      endpoint.id,
      ClusterHeaderObservation.sha256(whole),
      feesBefore,
      feesAfter,
      spent,
      collateral,
      actual.size
    )
  }
