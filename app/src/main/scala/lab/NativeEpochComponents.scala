// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayStake as S, ConwayNonMyopic as N, ConwayEpochBoundary as B}
import lab.header.PraosCertificateState.Point
import ReferenceJson.{Json as J, field, string, uint as ju}
import scala.util.control.NonFatal

/** Early-epoch diagnostic derivation from original CBOR, not ledger/bootstrap admission. Native
  * caches omitted by encoding are recomputed, never claimed recovered from live memory.
  */
private[lab] object NativeEpochComponents:
  val Names = Set(
    "native-projection.json",
    "original-debug-epoch.cbor",
    "original-whole-utxo.cbor",
    "derived-full-epoch-seed.cbor",
    "capture.json",
    "request.json",
    "effective-shelley-genesis.json"
  )
  private val Max = (BigInt(1) << 64) - 1
  private val MaxBytes = 524288
  private val Limits = Cbor.Limits(MaxBytes, 48, 100000, MaxBytes)
  final class Absent private[NativeEpochComponents] (
      val original: Bytes,
      val componentSHA256: Bytes,
      val seedSHA256: Bytes,
      val point: Point,
      val bindingId: Bytes
  )
  final class Checked private[NativeEpochComponents] (
      val id: Bytes,
      val originals: Map[String, Bytes],
      val pins: Map[String, Bytes],
      val components: Map[String, Bytes],
      val point: Point,
      val stake: ConwayStakeSeed.Prepared,
      val parameters: NativeSeedParameters.Prepared,
      val pots: B.Pots,
      val utxoCoin: BigInt,
      val previousBlocks: Map[Bytes, BigInt],
      val currentBlocks: Map[Bytes, BigInt],
      val nonMyopic: N.State,
      val reward: Absent,
      val protocolAcquisitionId: Option[Bytes]
  ):
    val rewardSeedAdmission = false
    val runtimeImport = false
    val authenticatedSnapshot = false
    val fullEpochSemanticsChecked = false
    val protocolStateAvailable = false
    val historicalFreezeAvailable = false
    val serializedGovernanceOnly = true

  private def get[A](v: Either[String, A]): A =
    v.fold(s => throw new IllegalArgumentException(s), identity)
  private def checked[A](v: => A): Either[String, A] = try Right(v)
  catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def obj(j: J): Map[String, J] = j match
    case J.Obj(m) => m
    case _        => throw new IllegalArgumentException("component JSON object")
  private def hex(s: String, width: Int): Bytes =
    require(s.length == width * 2 && s.matches("[0-9a-f]*"), "canonical component hex")
    get(Bytes.fromHex(s))
  private def uint(n: Node): BigInt = n.value match
    case V.UInt(v) if v <= Max => v
    case _ => throw new IllegalArgumentException("component unsigned coin/count")
  private def arr(n: Node, size: Int): Vector[Node] = n.value match
    case V.Arr(v) if v.size == size => v
    case _                          => throw new IllegalArgumentException("component array width")
  private def arrAny(n: Node): Vector[Node] = n.value match
    case V.Arr(v) if v.size <= 4096 => v
    case _                          => throw new IllegalArgumentException("component bounded array")
  private def encoded(n: Node): Bytes = get(Cbor.encode(n.value))
  private def map(n: Node): Vector[(Node, Node)] = n.value match
    case V.Map(v) if v.size <= 4096 =>
      val keys = v.map((k, _) => encoded(k))
      require(keys.distinct.size == keys.size, "duplicate component map key")
      v
    case _ => throw new IllegalArgumentException("component bounded map")
  private def bytes(n: Node, width: Int): Bytes = n.value match
    case V.ByteString(b) if b.size == width => b
    case _ => throw new IllegalArgumentException("component hash width")
  private def credential(n: Node): S.Credential =
    val a = arr(n, 2); val kind = uint(a(0)); require(kind <= 1, "credential kind")
    S.Credential(kind == 1, bytes(a(1), 28))
  private def set[A](n: Node)(f: Node => A): Set[A] = n.value match
    case V.Tag(t, inner) if t == 258 =>
      val values = arrAny(inner).map(f);
      require(values.distinct.size == values.size, "duplicate component set entry"); values.toSet
    case _ => throw new IllegalArgumentException("PV9 set tag258")
  private def ratio(n: Node): S.Ratio = n.value match
    case V.Tag(t, inner) if t == 30 =>
      val a = arr(inner, 2); val x = uint(a(0)); val y = uint(a(1))
      require(y > 0 && x <= y && x.gcd(y) == 1, "component rational")
      S.Ratio(x, y)
    case _ => throw new IllegalArgumentException("PV9 ratio tag30")
  private def emptyMap(n: Node): Unit = require(map(n).isEmpty, "unsupported nonempty effect map")
  private def zero(n: Node): Unit =
    require(uint(n) == 0, "unsupported nonzero early-epoch pot/deposit")
  private def node(v: V) = Node(v, Bytes.empty)
  private def update(n: Node, i: Int, v: Node): Node = node(V.Arr(arrAny(n).updated(i, v)))
  private def same(a: Node, b: Node): Unit =
    require(encoded(a) == encoded(b), "native component/seed mismatch")
  private def point(j: J, p: Point): Unit =
    require(
      obj(j).keySet == Set("slot", "hash") && ju(field(j, "slot")) == p.slot &&
        hex(string(field(j, "hash")), 32) == p.hash,
      "component source point mismatch"
    )

  /** Field projection only, not admission: UTxOState[2] is fees; [5] is donations. The full decoder
    * separately requires both to be zero for the supported profile.
    */
  private[lab] def projectedPots(
      chainAccount: Node,
      utxoState: Node,
      supply: BigInt
  ): Either[String, B.Pots] = checked {
    val chain = arr(chainAccount, 2)
    val us = arr(utxoState, 6)
    B.Pots(uint(chain(0)), uint(chain(1)), uint(us(2)), supply)
  }

  def decode(
      originals: Map[String, Bytes],
      expectedPins: Map[String, Bytes],
      anchor: Point
  ): Either[String, Checked] = decodeBound(originals, expectedPins, anchor, None)

  /** The v2 records remain byte-for-byte originals. The separately checked acquisition binds
    * protocol, verifier and receipt sources; it supplies no runtime admission capability.
    */
  def decodeV2(
      originals: Map[String, Bytes],
      expectedPins: Map[String, Bytes],
      anchor: Point,
      acquisition: NativeProtocolBootstrap.Acquisition
  ): Either[String, Checked] = checked {
    require(
      acquisition != null && originals != null && expectedPins != null &&
        originals.keySet == Names && expectedPins.keySet == Names &&
        acquisition.anchor == anchor,
      "v2 component acquisition/source/point required"
    )
    (Names intersect NativeProtocolBootstrap.InputNames).foreach { name =>
      require(
        originals(name) == acquisition.originals(name) &&
          expectedPins(name) == acquisition.sourcePins(name),
        "v2 component original splice: " + name
      )
    }
    get(decodeBound(originals, expectedPins, anchor, Some(acquisition)))
  }

  private def decodeBound(
      originals: Map[String, Bytes],
      expectedPins: Map[String, Bytes],
      anchor: Point,
      acquisition: Option[NativeProtocolBootstrap.Acquisition]
  ): Either[String, Checked] = checked {
    require(
      originals != null && expectedPins != null && originals.keySet == Names && expectedPins.keySet == Names,
      "exact component source set"
    )
    require(
      anchor != null && anchor.slot >= 0 && anchor.slot <= Max && anchor.blockNo >= 0 && anchor.blockNo <= Max && anchor.hash.size == 32,
      "component point bounds"
    )
    Names.foreach { name =>
      val raw = originals(name); val pin = expectedPins(name)
      require(
        raw != null && raw.value != null && raw.size > 0 && raw.size <= MaxBytes && pin != null && pin.size == 32 && sha(
          raw
        ) == pin,
        "component external source pin/bound: " + name
      )
    }
    def json(name: String) = ReferenceJson.parse(originals(name))
    val capture = json("capture.json"); val request = json("request.json")
    val schema = if acquisition.isDefined then BigInt(2) else BigInt(1)
    val kind =
      if acquisition.isDefined then "single-acquire-native-protocol-payloads"
      else "single-acquire-native-payloads"
    require(
      ju(field(capture, "schema")) == schema && ju(field(request, "schema")) == schema &&
        string(field(capture, "kind")) == kind,
      "versioned diagnostic source contract"
    )
    point(field(request, "point"), anchor)
    Vector("requestedPoint", "acquiredPoint", "finalPoint").foreach(k =>
      point(field(capture, k), anchor)
    )
    require(
      ju(field(capture, "blockNo")) == anchor.blockNo && ju(
        field(capture, "finalBlockNo")
      ) == anchor.blockNo &&
        ju(field(capture, "acquireCount")) == 1 && ju(field(capture, "reacquireCount")) == 0 &&
        ju(field(capture, "ntcVersion")) == ju(field(request, "ntcVersion")),
      "single acquisition declaration"
    )
    require(
      string(field(capture, "epochHex")) == originals("original-debug-epoch.cbor").hex &&
        string(field(capture, "utxoHex")) == originals("original-whole-utxo.cbor").hex,
      "capture/original bytes mismatch"
    )
    val projection = json("native-projection.json")
    require(
      string(field(projection, "kind")) == "native-epoch-diagnostic-projection" && ju(
        field(projection, "schema")
      ) == 1 &&
        hex(string(field(projection, "seedSHA256")), 32) == expectedPins(
          "derived-full-epoch-seed.cbor"
        ),
      "projection seed identity"
    )
    val report = obj(field(projection, "components"))
    val components = report.map { (name, value) =>
      require(
        obj(value).keySet == Set("encoding", "cborHex", "sha256") && string(
          field(value, "encoding")
        ) == "native-encCBOR-pv9",
        "component descriptor"
      )
      val text = string(field(value, "cborHex"));
      require(
        text.length > 0 && text.length <= MaxBytes * 2 && text.length % 2 == 0,
        "component byte bound"
      )
      val raw = hex(text, text.length / 2);
      require(sha(raw) == hex(string(field(value, "sha256")), 32), "component digest")
      name -> raw
    }
    def decode(raw: Bytes) = get(Cbor.decode(raw, Limits))
    val full = decode(originals("derived-full-epoch-seed.cbor")); val fs = arr(full, 7)
    val debug = decode(originals("original-debug-epoch.cbor")); val ds = arr(debug, 7)
    val es = arr(fs(3), 4); val ls = arr(es(1), 2); val cs = arr(ls(0), 3); val us = arr(ls(1), 6)
    val vs = arr(cs(0), 3); val ps = arr(cs(1), 4); val delegation = arr(cs(2), 4)
    require(fs(6).value == V.Null, "Conway stashed AVVM unit")
    // PV9 does not enforce multiplicity equality; preserve and shape-check this historical index.
    map(ps(0)).foreach { (k, v) =>
      bytes(k, 32); require(uint(v) > 0, "VRF multiplicity entry")
    }
    val snapshots = arr(es(2), 4); val go = arr(snapshots(2), 2); val nm = arr(es(3), 2);
    val gov = arr(us(3), 7)
    val de = arr(ds(3), 4); val dl = arr(de(1), 2); val du = arr(dl(1), 6); emptyMap(du(0))
    val restored = update(full, 3, update(fs(3), 1, update(es(1), 1, update(ls(1), 0, du(0)))))
    same(restored, debug)
    val locations = Map(
      "accounts" -> delegation(0),
      "certificateState" -> ls(0),
      "chainAccountState" -> es(0),
      "committee" -> gov(1),
      "committeeState" -> vs(1),
      "constitution" -> gov(2),
      "currentBlocks" -> fs(2),
      "currentParameters" -> gov(3),
      "delegationState" -> cs(2),
      "deposits" -> us(1),
      "donations" -> us(5),
      "dormantEpochs" -> vs(2),
      "drepPulsingState" -> gov(6),
      "dreps" -> vs(0),
      "fees" -> us(2),
      "futureDelegations" -> delegation(1),
      "futureParameters" -> gov(5),
      "futurePools" -> ps(2),
      "goPools" -> go(1),
      "goSnapshot" -> snapshots(2),
      "governance" -> us(3),
      "instantaneousRewards" -> delegation(3),
      "instantaneousStake" -> us(4),
      "leadership" -> fs(5),
      "nonMyopic" -> es(3),
      "nonMyopicLikelihoods" -> nm(0),
      "nonMyopicRewardPot" -> nm(1),
      "pools" -> ps(1),
      "previousBlocks" -> fs(1),
      "previousParameters" -> gov(4),
      "proposals" -> gov(0),
      "retiringPools" -> ps(3),
      "rewardState" -> fs(4),
      "snapshots" -> es(2),
      "votingState" -> cs(0)
    )
    require(components.keySet == locations.keySet + "utxo", "exact component domain")
    locations.foreach((name, n) => same(n, decode(components(name))))
    val whole = decode(originals("original-whole-utxo.cbor"));
    same(whole, decode(components("utxo")))
    val unpacked = get(NativeCoinUtxoMemPack.decode(us(0).original)); same(whole, decode(unpacked))
    val epoch = uint(fs(0));
    require(epoch == 0 && ju(field(projection, "epoch")) == epoch, "early epoch-zero profile")
    val parameters = get(
      NativeSeedParameters.decode(
        components("previousParameters"),
        components("currentParameters"),
        originals("effective-shelley-genesis.json"),
        expectedPins("effective-shelley-genesis.json"),
        epoch,
        anchor.slot,
        ju(field(request, "networkMagic"))
      )
    )
    val recipe = expectedPins.toVector
      .sortBy(_._1)
      .map((k, v) => k + "=" + v.hex)
      .mkString(
        "native-components-v1\n",
        "\n",
        s"\n${anchor.slot}:${anchor.hash.hex}:${anchor.blockNo}\n"
      )
    val identityRecipe = acquisition.fold(recipe)(a =>
      "native-components-v2\n" + recipe + "acquisition=" + a.id.hex + "\n"
    )
    val id = sha(Bytes.fromArray(identityRecipe.getBytes("US-ASCII")))
    Vector(ps(2), ps(3), delegation(1)).foreach(emptyMap)
    val ir = arr(delegation(3), 4); emptyMap(ir(0)); emptyMap(ir(1)); zero(ir(2)); zero(ir(3))
    // Current genesis delegations are retained, not mislabeled as pending effects.
    map(delegation(2)).foreach { (k, v) =>
      bytes(k, 28); val a = arr(v, 2); bytes(a(0), 28); bytes(a(1), 32)
    }
    val accountRows = map(delegation(0)).map { (k, v) =>
      val a = arr(v, 4); val c = credential(k); zero(a(0)); zero(a(1))
      val pool = a(2).value match
        case V.Null => None
        case _      => Some(bytes(a(2), 28))
      (c, S.Account(uint(a(0)), uint(a(1)), pool), credential(a(3)))
    }
    val accounts = accountRows.map((c, a, _) => c -> a).toMap
    val pools = map(ps(1)).map { (k, v) =>
      val a = arr(v, 10);
      require(arrAny(a(6)).isEmpty && arrAny(a(7)).isEmpty, "pool relays/metadata unsupported");
      zero(a(8))
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
    val context = get(S.context(id, parameters.globals.epochLength, accounts, pools))
    val dreps = map(vs(0)).map { (k, v) =>
      val c = credential(k); val a = arr(v, 4); uint(a(0));
      require(arrAny(a(1)).isEmpty, "DRep anchor unsupported"); zero(a(2))
      val delegates = set(a(3))(credential)
      require(
        delegates == accountRows.collect { case (who, _, target) if target == c => who }.toSet,
        "DRep reverse delegation mismatch"
      )
      c -> delegates
    }.toMap
    require(
      accountRows.forall((_, _, target) => dreps.contains(target)),
      "unregistered DRep target"
    )
    emptyMap(vs(1)); zero(vs(2))
    val proposals = arr(gov(0), 2);
    arr(proposals(0), 4).foreach(n => require(arrAny(n).isEmpty, "proposal root unsupported"));
    require(arrAny(proposals(1)).isEmpty, "proposals unsupported")
    require(arr(gov(5), 1).head.value == V.UInt(0), "future parameter update unsupported")
    val instantaneous = map(us(4)).map((k, v) => credential(k) -> uint(v)).toMap
    require(instantaneous.values.forall(_ > 0), "instantaneous stake excludes zero entries")
    require(
      get(S.recompute(originals("original-whole-utxo.cbor"))) == instantaneous,
      "original UTxO/instantaneous mismatch"
    )
    def snapshot(n: Node): S.Snapshot =
      val a = arr(n, 2);
      val active = map(a(0)).map { (k, v) =>
        val x = arr(v, 2); credential(k) -> S.Active(uint(x(0)), bytes(x(1), 28))
      }.toMap
      val rawPools = map(a(1))
      if active.isEmpty && rawPools.isEmpty then S.emptySnapshot
      else
        val computed = get(S.fromActive(context, active))
        require(
          rawPools.map((k, _) => bytes(k, 28)).toSet == computed.pools.keySet,
          "snapshot pool domain"
        )
        rawPools.foreach { (k, v) =>
          val e = computed.pools(bytes(k, 28)); val x = arr(v, 10)
          require(
            uint(x(0)) == e.coin && ratio(x(1)) == e.ratio && set(x(2))(
              bytes(_, 28)
            ) == e.owners && uint(x(3)) == e.ownerCoin &&
              bytes(x(4), 32) == e.vrf && uint(x(5)) == e.pledge && uint(x(6)) == e.cost && ratio(
                x(7)
              ) == e.margin &&
              uint(x(8)) == e.delegators && credential(x(9)) == e.rewardAccount,
            "snapshot derived pool mismatch"
          )
        }
        computed
    val mark = snapshot(snapshots(0)); val setSnapshot = snapshot(snapshots(1));
    val goSnapshot = snapshot(snapshots(2)); zero(snapshots(3))
    require(
      setSnapshot.active.isEmpty && setSnapshot.pools.isEmpty && goSnapshot.active.isEmpty && goSnapshot.pools.isEmpty,
      "early set/go must be empty"
    )
    val reset = get(S.snapshot(context, instantaneous));
    require(
      mark.active == reset.active && mark.pools == reset.pools && mark.total == reset.total,
      "mark/current reset stake mismatch"
    )
    val leadership = arr(fs(5), 2); require(uint(leadership(1)) == mark.total, "leadership total")
    val leaders = map(leadership(0));
    require(
      leaders.map((k, _) => bytes(k, 28)).toSet == mark.distribution.keySet,
      "leadership pool domain"
    )
    leaders.foreach { (k, v) =>
      val a = arr(v, 3); val pool = mark.distribution(bytes(k, 28));
      require(
        ratio(a(0)) == pool.ratio && uint(a(1)) == pool.coin && bytes(a(2), 32) == pool.vrf,
        "leadership derived mismatch"
      )
    }
    def counts(n: Node): Map[Bytes, BigInt] = map(n).map { (k, v) =>
      val pool = bytes(k, 28); val count = uint(v);
      require(pools.contains(pool) && count > 0, "count pool/positive count"); pool -> count
    }.toMap
    val previous = counts(fs(1)); val current = counts(fs(2));
    require(previous.isEmpty, "early previous counts must be empty")
    val chain = arr(es(0), 2); zero(chain(0)); zero(us(1)); zero(us(2)); zero(us(5))
    val outputs = get(S.decodeUtxo(originals("original-whole-utxo.cbor")))
    val utxoCoin = outputs.values.map(_.coin).sum; val reserves = uint(chain(1));
    val supply = parameters.globals.maxSupply
    require(utxoCoin + reserves == supply, "independent coin supply mismatch")
    emptyMap(nm(0)); zero(nm(1)); val nonMyopic = get(N.state(Map.empty, uint(nm(1))))
    require(
      components("rewardState").hex == "80" && fs(4).original.hex == "80",
      "exact Absent80 required; no fabricated freeze"
    )
    require(string(field(projection, "rewardPhase")) == "absent", "phase label mismatch")
    val freeze = field(projection, "rewardFreeze")
    require(
      string(field(freeze, "status")) == "absent-no-frozen-capsule" && obj(
        field(freeze, "components")
      ).isEmpty &&
        field(freeze, "frozenGoSnapshotAvailable") == J.Lit("false") && field(
          freeze,
          "fullOriginalFreezeInputsAvailable"
        ) == J.Lit("false"),
      "absent phase cannot carry freeze"
    )
    val stake = get(
      ConwayStakeSeed.checkedComponents(
        context,
        originals("original-whole-utxo.cbor"),
        instantaneous,
        S.Snapshots(mark, setSnapshot, goSnapshot, uint(snapshots(3))),
        epoch,
        uint(us(2)),
        id
      )
    )
    val reward = new Absent(
      components("rewardState"),
      sha(components("rewardState")),
      expectedPins("derived-full-epoch-seed.cbor"),
      anchor,
      id
    )
    new Checked(
      id,
      originals,
      expectedPins,
      components,
      anchor,
      stake,
      parameters,
      get(projectedPots(es(0), ls(1), supply)),
      utxoCoin,
      previous,
      current,
      nonMyopic,
      reward,
      acquisition.map(_.id)
    )
  }
