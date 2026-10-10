// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.PraosCertificateState as Certificate
import lab.ledger.{ConwayEmptyGovernance as G, ConwayStake as S}
import scala.util.control.NonFatal

/** Test-only, source-bound endpoint equality for the finite one-boundary fixture. Governance
  * remains a separately reported pending domain, never silently accepted.
  */
private[lab] object NativeEndpointLedger:
  final class Report private[NativeEndpointLedger] (
      val domainsChecked: Set[String],
      val componentOriginals: Map[String, Bytes],
      val originalSeed: Bytes,
      val originalEpoch: Bytes,
      val sourcePins: Map[String, Bytes],
      val endpointId: Bytes,
      val replayStateId: Bytes,
      val unsupportedDomains: Set[String] = Set(
        "general-ledger-admission",
        "nonempty-governance-actions",
        "live-pulser-cursor"
      ),
      val pendingGovernance: Boolean = true,
      val fullLedgerEquality: Boolean = false
  )

  /** Endpoint roles are installed state; boundary.roles remains initial source provenance. */
  private[lab] def checkInstalledParameters(
      current: Bytes,
      previous: Bytes,
      installed: G.Parameters
  ): Either[String, Unit] = protect {
    require(
      current == installed.current.original && previous == installed.previous.original,
      "endpoint installed parameter role originals mismatch"
    )
  }

  private val Max = (BigInt(1) << 64) - 1
  private def get[A](v: Either[?, A]): A =
    v.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def parse(b: Bytes): Node =
    get(Cbor.decode(b, Cbor.Limits(524288, 48, 100000, 524288)))
  private def arr(n: Node, size: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == size => xs
    case _                            => throw new IllegalArgumentException("endpoint array shape")
  private def uint(n: Node): BigInt = n.value match
    case V.UInt(v) if v >= 0 && v <= Max => v
    case _                               => throw new IllegalArgumentException("endpoint uint64")
  private def bytes(n: Node, size: Int): Bytes = n.value match
    case V.ByteString(v) if v.size == size => v
    case _ => throw new IllegalArgumentException("endpoint hash width")
  private def canonical(n: Node): Bytes = get(Cbor.encode(n.value))
  private def map(n: Node): Vector[(Node, Node)] = n.value match
    case V.Map(xs) if xs.size <= 4096 =>
      require(xs.map((k, _) => canonical(k)).distinct.size == xs.size, "endpoint duplicate map key")
      xs
    case _ => throw new IllegalArgumentException("endpoint map bound/shape")
  private def credential(n: Node): S.Credential =
    val a = arr(n, 2); val tag = uint(a(0))
    require(tag <= 1, "endpoint credential tag")
    S.Credential(tag == 1, bytes(a(1), 28))
  private def ratio(n: Node): S.Ratio = n.value match
    case V.Tag(t, inner) if t == 30 =>
      val a = arr(inner, 2); val x = uint(a(0)); val y = uint(a(1))
      require(y > 0 && x <= y && x.gcd(y) == 1, "endpoint exact ratio")
      S.Ratio(x, y)
    case _ => throw new IllegalArgumentException("endpoint ratio encoding")
  private def set[A](n: Node)(f: Node => A): Set[A] = n.value match
    case V.Tag(t, inner) if t == 258 =>
      inner.value match
        case V.Arr(xs) if xs.size <= 4096 =>
          val values = xs.map(f)
          require(values.distinct.size == values.size, "endpoint duplicate set entry")
          values.toSet
        case _ => throw new IllegalArgumentException("endpoint set shape")
    case _ => throw new IllegalArgumentException("endpoint PV9 set")
  private def empty(n: Node, label: String): Unit =
    require(map(n).isEmpty, "endpoint unsupported nonempty " + label)
  private def seqEmpty(n: Node, label: String): Unit =
    require(
      n.value match {
        case V.Arr(xs) => xs.isEmpty
        case _         => false
      },
      "endpoint unsupported " + label
    )
  private def path(n: Node, indexes: Int*): Node = indexes.foldLeft(n) { (at, i) =>
    at.value match
      case V.Arr(xs) if i >= 0 && i < xs.size => xs(i)
      case _ => throw new IllegalArgumentException("endpoint source path")
  }

  export EndpointLedgerChecks.{checkReplacement, utxoSemantics, checkSnapshot}

  /** Historical PV9 multiplicity index is preserved, never reconstructed from pool registrations.
    */
  private[lab] def checkHistoricalVrfIndex(
      current: Bytes,
      initial: Bytes
  ): Either[String, Unit] = protect {
    Vector(current, initial).foreach { raw =>
      map(parse(raw)).foreach { (key, count) =>
        bytes(key, 32)
        require(uint(count) > 0, "endpoint historical VRF count must be positive")
      }
    }
    require(current == initial, "endpoint historical VRF index original changed")
  }

  /** Preserved legacy genesis delegation state, not recomputed by the scoped replay. */
  private[lab] def checkHistoricalGenesisDelegations(
      current: Bytes,
      initial: Bytes
  ): Either[String, Unit] = protect {
    Vector(current, initial).foreach { raw =>
      map(parse(raw)).foreach { (key, value) =>
        bytes(key, 28)
        val pair = arr(value, 2)
        bytes(pair(0), 28)
        bytes(pair(1), 32)
      }
    }
    require(current == initial, "endpoint historical genesis delegation original changed")
  }

  private def accounts(n: Node): Map[S.Credential, S.Account] = map(n).map { (k, v) =>
    val a = arr(v, 4)
    val pool = a(2).value match
      case V.Null => None
      case _      => Some(bytes(a(2), 28))
    // Voting remains in the separate governance component comparison.
    credential(k) -> S.Account(uint(a(0)), uint(a(1)), pool)
  }.toMap

  private def pools(n: Node): Map[Bytes, S.Pool] = map(n).map { (k, v) =>
    val a = arr(v, 10)
    seqEmpty(a(6), "pool relays"); seqEmpty(a(7), "pool metadata")
    bytes(k, 28) -> S.Pool(
      bytes(a(0), 32),
      uint(a(1)),
      uint(a(2)),
      ratio(a(3)),
      credential(a(4)),
      set(a(5))(bytes(_, 28)),
      set(a(9))(credential),
      uint(a(8))
    )
  }.toMap

  def compare(
      endpoint: NativeProtocolBootstrap.Acquisition,
      expectedPoint: Certificate.Point,
      expectedAcquisitionId: Bytes,
      replay: CoherentSequence.State,
      initial: NativeLedgerV2.Checked
  ): Either[String, Report] = protect {
    require(
      endpoint != null && replay != null && initial != null && expectedPoint != null &&
        expectedAcquisitionId != null && expectedAcquisitionId.size == 32 &&
        endpoint.id == expectedAcquisitionId && endpoint.anchor == expectedPoint,
      "endpoint expected acquisition/full point"
    )
    require(
      replay.certificates.state.tip == expectedPoint && replay.ledger.slot == expectedPoint.slot,
      "endpoint replay full point mismatch"
    )
    require(endpoint.networkMagic == initial.acquisition.networkMagic, "endpoint network splice")
    val expectedContextId = ClusterHeaderObservation.sha256(
      Bytes.fromArray(
        ("native-sequence-diagnostic-context-v1\n" + initial.id.hex + "\n").getBytes("UTF-8")
      )
    )
    require(replay.contextId == expectedContextId, "endpoint replay initial join identity mismatch")
    val original = endpoint.originals
    require(
      original.keySet == NativeProtocolBootstrap.InputNames &&
        original.forall((name, raw) =>
          ClusterHeaderObservation.sha256(raw) == endpoint.sourcePins(name)
        ),
      "endpoint exact original pins"
    )
    val seed = original("derived-full-epoch-seed.cbor")
    val debug = original("original-debug-epoch.cbor")
    val whole = original("original-whole-utxo.cbor")
    get(checkReplacement(seed, debug, whole))
    val fs = arr(parse(seed), 7)
    require(
      uint(fs(0)) == 1 && initial.ledger.globals.epoch == 0 &&
        expectedPoint.slot / initial.ledger.globals.geometry.epochLength == 1,
      "endpoint one-boundary epoch profile"
    )
    require(fs(6).value == V.Null, "endpoint stashed AVVM unsupported")
    val es = arr(fs(3), 4); val ls = arr(es(1), 2); val cs = arr(ls(0), 3)
    val us = arr(ls(1), 6); val vs = arr(cs(0), 3); val ps = arr(cs(1), 4)
    val delegation = arr(cs(2), 4); val gov = arr(us(3), 7)
    get(
      checkHistoricalVrfIndex(
        ps(0).original,
        path(
          parse(initial.ledger.epochComponents.originals("original-debug-epoch.cbor")),
          3,
          1,
          0,
          1,
          0
        ).original
      )
    )
    val snapshots = arr(es(2), 4); val nm = arr(es(3), 2)
    val stake =
      replay.stake.getOrElse(throw new IllegalArgumentException("endpoint replay stake absent"))
    val rewards = replay.syntheticRewards.getOrElse(
      throw new IllegalArgumentException("endpoint replay rewards absent")
    )
    val boundary = replay.syntheticBoundary.getOrElse(
      throw new IllegalArgumentException("endpoint replay boundary absent")
    )
    require(
      boundary.boundaryApplied && stake.epoch == 1 && replay.ledger.environment.epoch == 1,
      "endpoint applied epoch boundary absent"
    )
    require(
      get(utxoSemantics(whole)) == get(utxoSemantics(replay.ledger.outputMap)),
      "endpoint whole UTxO differs from replay"
    )
    val instant = map(us(4)).map((k, v) => credential(k) -> uint(v)).toMap
    require(
      instant == stake.instantaneous && get(S.recompute(whole)) == instant,
      "endpoint instantaneous stake mismatch"
    )
    require(accounts(delegation(0)) == stake.context.accounts, "endpoint stake accounts mismatch")
    require(pools(ps(1)) == stake.context.pools, "endpoint pools mismatch")
    empty(ps(2), "future pools")
    empty(ps(3), "retiring pools")
    empty(delegation(1), "future genesis delegations")
    get(
      checkHistoricalGenesisDelegations(
        delegation(2).original,
        path(
          parse(initial.ledger.epochComponents.originals("original-debug-epoch.cbor")),
          3,
          1,
          0,
          2,
          2
        ).original
      )
    )
    val ir = arr(delegation(3), 4)
    empty(ir(0), "instantaneous reserve rewards"); empty(ir(1), "instantaneous treasury rewards")
    require(uint(ir(2)) == 0 && uint(ir(3)) == 0, "endpoint instantaneous rewards unsupported")
    Vector(
      snapshots(0) -> stake.snapshots.mark,
      snapshots(1) -> stake.snapshots.set,
      snapshots(2) -> stake.snapshots.go
    ).foreach((n, expected) => get(checkSnapshot(n.original, expected)))
    require(uint(snapshots(3)) == stake.snapshots.fees, "endpoint snapshot fees mismatch")
    def counts(n: Node) = map(n).map { (k, v) =>
      val pool = bytes(k, 28); val count = uint(v)
      require(stake.context.pools.contains(pool) && count > 0, "endpoint issuer count domain")
      pool -> count
    }.toMap
    require(
      counts(fs(1)) == rewards.previousBlocks && counts(fs(2)) == rewards.currentBlocks,
      "endpoint issuer counts mismatch"
    )
    val pots = arr(es(0), 2)
    require(
      uint(pots(0)) == rewards.pots.treasury && uint(pots(1)) == rewards.pots.reserves &&
        uint(us(2)) == rewards.pots.fees && uint(us(2)) == replay.ledger.fees &&
        uint(us(1)) == boundary.governanceAfter.get.deposits.total &&
        uint(us(5)) == boundary.governanceAfter.get.donations,
      "endpoint treasury/reserves/fees/deposits/donations mismatch"
    )
    get(
      checkInstalledParameters(
        gov(3).original,
        gov(4).original,
        boundary.governanceAfter.get.parameters
      )
    )
    require(
      fs(4).original.hex == "80" && rewards.pulser.isEmpty && rewards.frozen.isEmpty,
      "endpoint reward phase is not exact Absent"
    )
    empty(nm(0), "nonmyopic likelihoods")
    require(
      boundary.nonMyopic.likelihoods.isEmpty && uint(nm(1)) == boundary.nonMyopic.rewardPot,
      "endpoint empty likelihoods/reward pot mismatch"
    )
    val leaders = arr(fs(5), 2)
    val expectedLeaders = initial.ledger.epochComponents.stake.snapshots.mark
    require(
      uint(leaders(1)) == expectedLeaders.total &&
        stake.snapshots.set.active == expectedLeaders.active &&
        stake.snapshots.set.pools == expectedLeaders.pools,
      "endpoint leadership preboundary snapshot"
    )
    val distribution = map(leaders(0)).map { (k, v) =>
      val a = arr(v, 3); bytes(k, 28) -> (ratio(a(0)), uint(a(1)), bytes(a(2), 32))
    }.toMap
    require(
      distribution == expectedLeaders.distribution.map((pool, p) =>
        pool -> (p.ratio, p.coin, p.vrf)
      ),
      "endpoint leadership distribution mismatch"
    )
    require(
      distribution.keySet == initial.protocol.eligibility.stakes.keySet &&
        distribution.forall { (pool, value) =>
          val fraction = initial.protocol.eligibility.stakes(pool)
          value._1.numerator == fraction.numerator && value._1.denominator == fraction.denominator &&
          initial.protocol.certificates.registrations.get(pool).contains(value._3)
        },
      "endpoint eligibility/VRF distribution mismatch"
    )
    val eligible = replay.eligibility.getOrElse(
      throw new IllegalArgumentException("endpoint replay eligibility absent")
    )
    val lastCertificate = replay.certificates.steps.lastOption.getOrElse(
      throw new IllegalArgumentException("endpoint final certificate observation not retained")
    )
    val leader = distribution.getOrElse(
      lastCertificate.issuer,
      throw new IllegalArgumentException("endpoint final issuer absent from leadership")
    )
    require(
      eligible.headers.size == 1 && eligible.headers.head.headerHash == expectedPoint.hash &&
        eligible.headers.head.stake.numerator == leader._1.numerator &&
        eligible.headers.head.stake.denominator == leader._1.denominator,
      "endpoint final verified eligibility fraction mismatch"
    )
    val componentOriginals = Map(
      "certificateState" -> ls(0),
      "votingState" -> cs(0),
      "delegationState" -> cs(2),
      "accounts" -> delegation(0),
      "dreps" -> vs(0),
      "committeeState" -> vs(1),
      "dormantEpochs" -> vs(2),
      "governance" -> us(3),
      "proposals" -> gov(0),
      "committee" -> gov(1),
      "constitution" -> gov(2),
      "currentParameters" -> gov(3),
      "previousParameters" -> gov(4),
      "futureParameters" -> gov(5),
      "drepPulsingState" -> gov(6)
    ).map((name, n) => name -> n.original)
    new Report(
      Set(
        "source-pins-full-point",
        "debug-seed-utxo-replacement",
        "epoch",
        "whole-utxo",
        "instantaneous-stake",
        "stake-accounts",
        "pools",
        "mark-set-go-snapshots",
        "snapshot-fees",
        "previous-current-issuer-counts",
        "treasury-reserves-fees-deposits-donations",
        "current-previous-parameter-originals",
        "reward-absent",
        "empty-nonmyopic",
        "preboundary-leadership-eligibility-vrfs",
        "unchanged-historical-vrf-index",
        "unchanged-historical-genesis-delegations"
      ),
      componentOriginals,
      seed,
      debug,
      endpoint.sourcePins,
      endpoint.id,
      replay.id
    )
  }
