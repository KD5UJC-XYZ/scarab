// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.ledger.{
  ConwayStake as S,
  ConwayEmptyGovernance as G,
  ConwayEpochBoundary as B,
  ConwayRewardPulser as P,
  ConwayRewardStart as R,
  ConwayNonMyopic as NM,
  ConwayNativeLikelihood as N,
  PlutusOutput
}
import lab.header.PraosCertificateState.Point
import lab.header.PraosNonceEvolution as Nonces
import lab.submission.{AdmissionProfile, StatePin}
import ReferenceJson.Json as J
import RepeatedTerminalCbor.{Node, Value as V}
import RepeatedTerminalSupport.*
import scala.util.boundary
import scala.util.control.NonFatal

/** Pure observational projection of the selected repeated tuple. It is neither a recovery format
  * nor admission authority. Native originals remain in the independently pinned acquisition.
  */
private[lab] object RepeatedPlutusTerminal:
  export RepeatedTerminalSupport.Failure
  val MaxBytes = 2 * 1024 * 1024
  val Schema = "plutus-repeated-service-terminal-observation-v1"
  final case class Snapshot(active: Map[S.Credential, S.Active], pools: Map[Bytes, S.PoolSnapshot])
  enum Reward:
    case Absent
    case Complete(deltas: B.Deltas, rewards: Map[S.Credential, Set[B.Reward]], nonMyopic: NM.State)
  private enum RewardPhase:
    case Absent, Complete
  private def generationMode(j: J)(using Scope): N.Mode = text(j) match
    case "pure-jvm"    => N.Mode.PureJvm
    case "checked-jvm" => N.Mode.CheckedJvm
    case _             => reject(Failure.Unsupported("generation mode"))
  private def rewardPhase(j: J)(using Scope): RewardPhase = text(j) match
    case "absent"   => RewardPhase.Absent
    case "complete" => RewardPhase.Complete
    case _          => reject(Failure.Unsupported("active or unknown monetary reward phase"))
  final case class Components(
      epoch: BigInt,
      pots: B.Pots,
      deposits: BigInt,
      donations: BigInt,
      previousBlocks: Map[Bytes, BigInt],
      currentBlocks: Map[Bytes, BigInt],
      instantaneous: Map[S.Credential, BigInt],
      accounts: Map[S.Credential, G.Account],
      pools: Map[Bytes, Bytes],
      mark: Snapshot,
      set: Snapshot,
      go: Snapshot,
      snapshotFees: BigInt,
      leadership: G.PoolDistribution,
      governance: RepeatedTerminalGovernance.Projection,
      nonMyopic: NM.State,
      reward: Reward
  )
  final class Observation private[RepeatedPlutusTerminal] (
      val original: Bytes,
      val json: J,
      val pin: StatePin,
      val source: Bytes,
      val manifest: Bytes,
      val epoch: BigInt,
      val outputMapSHA256: Bytes,
      val components: J
  )
  final case class Comparison(epoch: BigInt, fees: BigInt, entries: Int)

  private def snapshot(s: S.Snapshot): Snapshot = Snapshot(s.active, s.pools)
  private def distribution(s: S.Snapshot): G.PoolDistribution = G.PoolDistribution(
    if s.distribution.isEmpty then BigInt(1) else s.total,
    s.distribution.map((p, v) => p -> G.PoolShare(v.coin, v.ratio, v.vrf))
  )
  private def nonMyopic(n: Node)(using Scope): NM.State =
    val a = arr(n, 2)
    val likelihoods = mapping(a(0))(
      bytes(_, 28),
      x =>
        get(
          NM.likelihood(
            rows(x).map(v => cbor(RepeatedTerminalCbor.nativeFloat32(v), "native Float likelihood"))
          ),
          "100 finite raw32 weights"
        )
    )
    get(NM.state(likelihoods, uint(a(1))), "non-myopic state")
  private def decodeSnapshot(n: Node)(using Scope): Snapshot =
    val a = arr(n, 2)
    val active = mapping(a(0))(
      credential,
      x =>
        val v = arr(x, 2); S.Active(uint(v(0)), bytes(v(1), 28))
    )
    val pools = mapping(a(1))(
      bytes(_, 28),
      x =>
        val v = arr(x, 10); val count = uint(v(8)); valid(count <= Int.MaxValue, "delegator count")
        S.PoolSnapshot(
          uint(v(0)),
          ratio(v(1)),
          set(v(2))(bytes(_, 28)),
          uint(v(3)),
          bytes(v(4), 32),
          uint(v(5)),
          uint(v(6)),
          ratio(v(7)),
          count.toInt,
          credential(v(9))
        )
    )
    Snapshot(active, pools)
  private def decodeDistribution(n: Node)(using Scope): G.PoolDistribution =
    val a = arr(n, 2)
    G.PoolDistribution(
      uint(a(1)),
      mapping(a(0))(
        bytes(_, 28),
        x =>
          val v = arr(x, 3); G.PoolShare(uint(v(1)), ratio(v(0)), bytes(v(2), 32))
      )
    )
  private def reward(n: Node)(using Scope): B.Reward =
    val a = arr(n, 3); val kind = uint(a(0)); valid(kind <= 1, "reward kind")
    B.Reward(
      if kind == 0 then B.RewardKind.Member else B.RewardKind.Leader,
      bytes(a(1), 28),
      uint(a(2))
    )
  private[lab] def decodeReward(n: Node): Either[Failure, Reward] = boundary:
    val result = maybe(n) { inner =>
      val a = rows(inner)
      valid(a.nonEmpty, "reward sum")
      uint(a.head) match
        case tag if tag == 0 => reject(Failure.Unsupported("active monetary reward pulser"))
        case tag if tag == 1 =>
          equal(a.size, 2, "complete reward sum width")
          val r = arr(a(1), 5)
          val rewards = mapping(r(2))(
            credential,
            x =>
              val rs = set(x)(reward)
              // Native Ord Reward ignores amount. Reject semantic duplicates even if amounts differ.
              valid(rs.map(r => (r.kind, r.pool)).size == rs.size, "duplicate native reward key")
              rs
          )
          // Native wire stores negate(deltaR) and negate(deltaF), not the signed deltas.
          Reward.Complete(
            B.Deltas(signed(r(0)), -signed(r(1)), -signed(r(3))),
            rewards,
            nonMyopic(r(4))
          )
        case _ => reject(Failure.Unsupported("reward sum tag"))
    }
    Right(result.getOrElse(Reward.Absent))

  private def selectedReward(state: CoherentSequence.State)(using Scope): Reward =
    val r =
      state.syntheticRewards.getOrElse(reject(Failure.Invalid("rewards", "missing component")))
    val b =
      state.syntheticBoundary.getOrElse(reject(Failure.Invalid("boundary", "missing component")))
    valid(
      r.frozen.isDefined == r.pulser.isDefined && b.frozenId == r.frozen.map(_.id) &&
        b.checkedLikelihood.isDefined == r.frozen.isDefined,
      "frozen phase binding"
    )
    r.pulser match
      case None => Reward.Absent
      case Some(p) if p.phase == P.Phase.Pulsing =>
        reject(Failure.Unsupported("active monetary reward pulser"))
      case Some(p) =>
        val f = r.frozen.get
        val generated = b.checkedLikelihood.get
        valid(
          generated.mode == b.generationMode && !generated.nativeValuesAuthoritative &&
            (generated.mode == N.Mode.PureJvm || generated.mode == N.Mode.CheckedJvm) &&
            generated.jvmMismatchWords == 0,
          "likelihood generation mode"
        )
        valid(
          (generated.mode == N.Mode.PureJvm && generated.nativeResponse.isEmpty &&
            !generated.nativeValidated && !generated.diagnosticNativeDependency &&
            generated.raw32Comparisons == 0 && generated.raw64Comparisons == 0) ||
            (generated.mode == N.Mode.CheckedJvm && generated.nativeResponse
              .contains(generated.evidence) &&
              generated.nativeValidated && generated.diagnosticNativeDependency),
          "likelihood evidence mode"
        )
        val values = get(generated.forFrozen(f, f.id), "selected likelihood freeze")
        val allocation = get(R.calculate(f, f.id), "reward allocation")
        valid(
          p.frozenId == f.id && p.allocationId == allocation.id &&
            b.allocationId.contains(allocation.id),
          "reward allocation binding"
        )
        val c = p.completion.getOrElse(reject(Failure.Invalid("reward", "missing completion")))
        valid(c.frozenId == f.id && c.allocationId == allocation.id, "reward completion binding")
        val history =
          b.frozenHistory.getOrElse(reject(Failure.Invalid("reward", "missing frozen history")))
        val nm = get(
          NM.completeFrozen(history, history.id, f, f.id, allocation, values),
          "completed non-myopic state"
        ).after
        Reward.Complete(c.completed.deltas, c.completed.rewards, nm)

  def fromState(state: CoherentSequence.State): Either[Failure, Components] = boundary:
    valid(state != null, "terminal state")
    val stake = state.stake.getOrElse(reject(Failure.Invalid("stake", "missing component")))
    val r =
      state.syntheticRewards.getOrElse(reject(Failure.Invalid("rewards", "missing component")))
    val b =
      state.syntheticBoundary.getOrElse(reject(Failure.Invalid("boundary", "missing component")))
    valid(
      b.repeated && b.transitions >= 0 && b.transitions <= b.repeatedLimit,
      "bounded repeated component"
    )
    valid(
      stake.ledgerId == state.ledger.id && stake.slot == state.ledger.slot &&
        stake.epoch == state.ledger.environment.epoch && b.governanceInput.epoch == stake.epoch &&
        r.pots.fees == state.ledger.fees,
      "coherent component tuple"
    )
    val base = b.governanceInput
    equal(stake.context.accounts.keySet, base.accounts.keySet, "governance/stake account domain")
    val accounts = base.accounts.map { (c, a) =>
      val s = stake.context.accounts(c)
      valid(a.deposit == s.deposit && a.pool == s.delegation, "stable account registration")
      c -> a.copy(rewards = s.balance)
    }
    val pools = b.poolPayloads.map((id, p) => id -> p.original)
    equal(
      stake.context.pools,
      b.poolPayloads.map((id, p) => id -> p.pool),
      "stable pool registration"
    )
    val leadership = if b.transitions == 0 then stake.snapshots.mark else stake.snapshots.set
    Right(
      Components(
        stake.epoch,
        r.pots,
        base.deposits.total,
        base.donations,
        r.previousBlocks,
        r.currentBlocks,
        stake.instantaneous,
        accounts,
        pools,
        snapshot(stake.snapshots.mark),
        snapshot(stake.snapshots.set),
        snapshot(stake.snapshots.go),
        stake.snapshots.fees,
        distribution(leadership),
        unwrap(RepeatedTerminalGovernance.fromState(b)),
        b.nonMyopic,
        selectedReward(state)
      )
    )

  /** All fields of the native seven-field epoch record are either compared or explicitly rejected.
    * Historical VRF multiplicity/genesis delegation indexes are compared separately to the initial
    * original, because the runtime deliberately does not claim to reconstruct them.
    */
  private[lab] def decodeComponents(raw: Bytes, maxSupply: BigInt): Either[Failure, Components] =
    boundary:
      val fs = arr(parse(raw), 7); val es = arr(fs(3), 4); val ls = arr(es(1), 2)
      val cs = arr(ls(0), 3); val us = arr(ls(1), 6); val ps = arr(cs(1), 4); val ds = arr(cs(2), 4)
      equal(fs(6).value, V.Null, "Conway stashed AVVM unit")
      Vector(ps(2), ps(3), ds(1)).foreach(emptyMap(_, "pending pool/delegation effects"))
      val ir = arr(ds(3), 4); emptyMap(ir(0), "MIR reserves"); emptyMap(ir(1), "MIR treasury")
      equal(signed(ir(2)), BigInt(0), "MIR reserve delta");
      equal(signed(ir(3)), BigInt(0), "MIR treasury delta")
      val accounts = mapping(ds(0))(credential, RepeatedTerminalGovernance.account)
      val pools = mapping(ps(1))(
        bytes(_, 28),
        x =>
          get(GovernancePoolPayload.decode(x.original, sha(x.original)), "pool original").original
      )
      def counts(n: Node): Map[Bytes, BigInt] =
        val values = mapping(n)(bytes(_, 28), uint)
        valid(
          values.forall((p, c) => pools.contains(p) && c > 0),
          "positive registered block counts"
        )
        values
      val chain = arr(es(0), 2); val snaps = arr(es(2), 4)
      val instantaneous = mapping(us(4))(credential, uint)
      valid(instantaneous.values.forall(_ > 0), "positive instantaneous stake")
      val gov = unwrap(RepeatedTerminalGovernance.decode(cs(0), us(3)))
      val deposits = uint(us(1))
      equal(
        deposits,
        accounts.values.map(_.deposit).sum + pools.values
          .map(p => get(GovernancePoolPayload.decode(p, sha(p)), "pool deposits").pool.deposit)
          .sum +
          gov.dreps.values.map(_.deposit).sum,
        "deposit obligation"
      )
      equal(uint(us(5)), BigInt(0), "unsupported donations")
      Right(
        Components(
          uint(fs(0)),
          B.Pots(uint(chain(0)), uint(chain(1)), uint(us(2)), maxSupply),
          deposits,
          uint(us(5)),
          counts(fs(1)),
          counts(fs(2)),
          instantaneous,
          accounts,
          pools,
          decodeSnapshot(snaps(0)),
          decodeSnapshot(snaps(1)),
          decodeSnapshot(snaps(2)),
          uint(snaps(3)),
          decodeDistribution(fs(5)),
          gov,
          nonMyopic(es(3)),
          unwrap(decodeReward(fs(4)))
        )
      )

  private def snapshotJson(s: Snapshot): J = record(
    "active" -> mapJson(s.active)(cred, a => J.Arr(Vector(num(a.coin), str(a.pool.hex)))),
    "pools" -> mapJson(s.pools)(
      _.hex,
      p =>
        record(
          "coin" -> num(p.coin),
          "ratio" -> ratioJson(p.ratio),
          "owners" -> J.Arr(p.owners.toVector.map(_.hex).sorted.map(str)),
          "ownerCoin" -> num(p.ownerCoin),
          "vrf" -> str(p.vrf.hex),
          "pledge" -> num(p.pledge),
          "cost" -> num(p.cost),
          "margin" -> ratioJson(p.margin),
          "delegators" -> num(p.delegators),
          "rewardAccount" -> str(cred(p.rewardAccount))
        )
    )
  )
  private def nmJson(n: NM.State): J = record(
    "rewardPot" -> num(n.rewardPot),
    "likelihoods" -> mapJson(n.likelihoods)(_.hex, l => J.Arr(l.hex.map(str)))
  )
  private def rewardJson(r: Reward): J = r match
    case Reward.Absent => record("phase" -> str("absent"))
    case Reward.Complete(d, rewards, nm) =>
      record(
        "phase" -> str("complete"),
        "deltaT" -> num(d.treasury),
        "deltaR" -> num(d.reserves),
        "deltaF" -> num(d.fees),
        "rewards" -> mapJson(rewards)(
          cred,
          rs =>
            J.Arr(
              rs.toVector
                .sortBy(r => (r.kind.ordinal, r.pool.hex))
                .map(r => J.Arr(Vector(num(r.kind.ordinal), str(r.pool.hex), num(r.amount))))
            )
        ),
        "nonMyopic" -> nmJson(nm)
      )
  def componentsJson(c: Components): J = record(
    "epoch" -> num(c.epoch),
    "pots" -> record(
      "treasury" -> num(c.pots.treasury),
      "reserves" -> num(c.pots.reserves),
      "fees" -> num(c.pots.fees),
      "maxSupply" -> num(c.pots.maxSupply),
      "deposits" -> num(c.deposits),
      "donations" -> num(c.donations)
    ),
    "previousBlocks" -> poolCoins(c.previousBlocks),
    "currentBlocks" -> poolCoins(c.currentBlocks),
    "instantaneousStake" -> credentialCoins(c.instantaneous),
    "accounts" -> mapJson(c.accounts)(cred, RepeatedTerminalGovernance.accountJson),
    "pools" -> mapJson(c.pools)(_.hex, b => str(b.hex)),
    "snapshots" -> record(
      "mark" -> snapshotJson(c.mark),
      "set" -> snapshotJson(c.set),
      "go" -> snapshotJson(c.go),
      "fees" -> num(c.snapshotFees)
    ),
    "leadership" -> record(
      "total" -> num(c.leadership.total),
      "pools" -> mapJson(c.leadership.pools)(
        _.hex,
        p => J.Arr(Vector(ratioJson(p.fraction), num(p.stake), str(p.vrf.hex)))
      )
    ),
    "governance" -> RepeatedTerminalGovernance.json(c.governance),
    "nonMyopic" -> nmJson(c.nonMyopic),
    "reward" -> rewardJson(c.reward)
  )

  private def nonce(n: Nonces.Nonce): J = n match
    case Nonces.Nonce.Neutral => J.Lit("null")
    case Nonces.Nonce.Hash(b) => str(b.hex)
  private def protocol(lastSlot: BigInt, f: Nonces.Fields, counters: Map[Bytes, BigInt]): J =
    record(
      "lastSlot" -> num(lastSlot),
      "counters" -> J.Arr(
        counters.toVector
          .sortBy(_._1.hex)
          .map((h, n) => record("issuer" -> str(h.hex), "counter" -> num(n)))
      ),
      "evolving" -> nonce(f.evolving),
      "candidate" -> nonce(f.candidate),
      "epoch" -> nonce(f.epoch),
      "lab" -> nonce(f.lab),
      "lastEpochBlock" -> nonce(f.lastEpochBlock),
      "previousEpoch" -> record(
        "present" -> bool(f.previousEpoch.isDefined),
        "value" -> option(f.previousEpoch)(nonce)
      )
    )
  private def likelihood(state: CoherentSequence.State)(using Scope): J =
    val b = state.syntheticBoundary.get
    option(b.checkedLikelihood) { g =>
      val f = state.syntheticRewards.get.frozen.get
      record(
        "frozenId" -> str(f.id.hex),
        "applicationEpoch" -> num(f.epoch),
        "observedSlot" -> num(f.observedSlot),
        "preTickTupleId" -> str(f.preTickTupleId.hex),
        "requestSHA256" -> str(sha(g.request.original).hex),
        "requestOriginal" -> str(g.request.original.hex),
        "evidenceSHA256" -> str(sha(g.evidence).hex),
        "evidenceOriginal" -> str(g.evidence.hex),
        "nativeResponseSHA256" -> option(g.nativeResponse)(b => str(sha(b).hex)),
        "nativeResponseOriginal" -> option(g.nativeResponse)(b => str(b.hex)),
        "mode" -> str(if g.mode == N.Mode.PureJvm then "pure-jvm" else "checked-jvm"),
        "nativeValidated" -> bool(g.nativeValidated),
        "diagnosticNativeDependency" -> bool(g.diagnosticNativeDependency),
        "computedRaw32Words" -> num(g.computedRaw32Words),
        "computedRaw64Words" -> num(g.computedRaw64Words),
        "raw32Comparisons" -> num(g.raw32Comparisons),
        "raw64Comparisons" -> num(g.raw64Comparisons),
        "jvmMismatchWords" -> num(g.jvmMismatchWords)
      )
    }

  def encode(
      state: CoherentSequence.State,
      pin: StatePin,
      source: Bytes,
      manifest: String
  ): Either[Failure, J] = boundary:
    valid(
      state != null && pin != null && source != null && source.size == 32 &&
        manifest != null && manifest.matches("[0-9a-f]{64}"),
      "terminal export arguments"
    )
    valid(
      pin.coherentStateId == state.id && pin.ledgerStateId == state.ledger.id &&
        pin.environmentId == state.ledger.environment.id && pin.point == state.certificates.state.tip &&
        pin.validationSlot == state.ledger.slot && pin.validationSlot == pin.point.slot &&
        pin.profileId == AdmissionProfile.PlutusV3.id && state.ledger.environment.plutus.nonEmpty &&
        state.nonces.lastSlot == pin.point.slot &&
        state.nonces.certificateStateId == state.certificates.state.id,
      "terminal coherent pin"
    )
    val expectedContext = sha(
      Bytes.fromArray(
        ("native-sequence-diagnostic-context-v1\n" +
          source.hex + "\n").getBytes("UTF-8")
      )
    )
    equal(state.contextId, expectedContext, "terminal source join context")
    val c = unwrap(fromState(state))
    val b = state.syntheticBoundary.get
    val components = componentsJson(c)
    val result = record(
      "schema" -> str(Schema),
      "diagnosticOnly" -> bool(true),
      "restartSupported" -> bool(false),
      "fullLedgerValidated" -> bool(false),
      "pin" -> PlutusServiceRuntime.pin(pin),
      "sourceJoinId" -> str(source.hex),
      "initialManifestSHA256" -> str(manifest),
      "outputMapFile" -> str("terminal-output-map.cbor"),
      "outputMapSHA256" -> str(sha(state.ledger.outputMap).hex),
      "fees" -> num(c.pots.fees),
      "epoch" -> num(c.epoch),
      "validationSlot" -> num(pin.validationSlot),
      "instantaneousStake" -> credentialCoins(c.instantaneous),
      "representedProtocol" -> protocol(
        state.nonces.lastSlot,
        state.nonces.fields,
        state.certificates.state.counters
      ),
      "components" -> components,
      "componentsSHA256" -> str(sha(EvidenceJson.encode(components)).hex),
      "repeatedEpoch" -> record(
        "componentId" -> str(b.id.hex),
        "transitions" -> num(b.transitions),
        "generationMode" -> str(
          if b.generationMode == N.Mode.PureJvm then "pure-jvm" else "checked-jvm"
        ),
        "frozenId" -> option(b.frozenId)(x => str(x.hex)),
        "allocationId" -> option(b.allocationId)(x => str(x.hex)),
        "nonMyopicId" -> str(b.nonMyopic.id.hex),
        "checkedLikelihood" -> likelihood(state)
      )
    )
    valid(EvidenceJson.encode(result).size <= MaxBytes, "repeated terminal byte bound")
    Right(result)

  private def fullPoint(j: J)(using Scope): Point =
    equal(obj(j).keySet, Set("slot", "blockNo", "hash"), "point fields")
    Point(hash(field(j, "hash")), number(field(j, "slot")), number(field(j, "blockNo")))

  private def original(j: J, name: String)(using Scope): Bytes =
    val h = text(j)
    valid(
      h.nonEmpty && h.length <= N.MaxResponseBytes * 2 && h.length % 2 == 0 &&
        h.matches("[0-9a-f]+"),
      name + " original bound/spelling"
    )
    get(Bytes.fromHex(h), name)
  private def ascii(raw: Bytes)(using Scope): String =
    valid(raw.value.forall(b => b >= 0 && b < 127), "ASCII generation evidence")
    new String(raw.toArray, "US-ASCII")
  private def decimal(s: String)(using Scope): BigInt =
    number(J.Num(s))

  /** Exact original generation rows are checked without granting runtime/native authority to a
    * serialized hash. CheckedJvm's captured native response remains diagnostic external evidence.
    */
  private def validateRepeated(j: J, epoch: BigInt, pin: StatePin)(using Scope): Unit =
    val r = field(j, "repeatedEpoch")
    equal(
      obj(r).keySet,
      Set(
        "componentId",
        "transitions",
        "generationMode",
        "frozenId",
        "allocationId",
        "nonMyopicId",
        "checkedLikelihood"
      ),
      "repeated metadata fields"
    )
    hash(field(r, "componentId")); hash(field(r, "nonMyopicId"))
    val transitions = number(field(r, "transitions"))
    valid(transitions == epoch && transitions <= 8, "repeated epoch-zero transition count")
    val mode = generationMode(field(r, "generationMode"))
    val reward = field(field(j, "components"), "reward")
    val phase = rewardPhase(field(reward, "phase"))
    val nullValue = J.Lit("null")
    if phase == RewardPhase.Absent then
      equal(field(r, "frozenId"), nullValue, "absent frozen identity")
      equal(field(r, "allocationId"), nullValue, "absent allocation identity")
      equal(field(r, "checkedLikelihood"), nullValue, "absent generation evidence")
    else
      val frozen = hash(field(r, "frozenId")); hash(field(r, "allocationId"))
      val g = field(r, "checkedLikelihood")
      equal(
        obj(g).keySet,
        Set(
          "frozenId",
          "applicationEpoch",
          "observedSlot",
          "preTickTupleId",
          "requestSHA256",
          "requestOriginal",
          "evidenceSHA256",
          "evidenceOriginal",
          "nativeResponseSHA256",
          "nativeResponseOriginal",
          "mode",
          "nativeValidated",
          "diagnosticNativeDependency",
          "computedRaw32Words",
          "computedRaw64Words",
          "raw32Comparisons",
          "raw64Comparisons",
          "jvmMismatchWords"
        ),
        "likelihood fields"
      )
      equal(hash(field(g, "frozenId")), frozen, "generation frozen identity")
      equal(number(field(g, "applicationEpoch")), epoch, "generation application epoch")
      val observed = number(field(g, "observedSlot"))
      valid(observed <= pin.point.slot && observed / 1000 == epoch, "generation observed slot")
      hash(field(g, "preTickTupleId"))
      equal(generationMode(field(g, "mode")), mode, "configured generation mode")
      equal(number(field(g, "jvmMismatchWords")), BigInt(0), "generation mismatch count")
      val request = original(field(g, "requestOriginal"), "generation request")
      val evidence = original(field(g, "evidenceOriginal"), "generation evidence")
      equal(sha(request), hash(field(g, "requestSHA256")), "generation request digest")
      equal(sha(evidence), hash(field(g, "evidenceSHA256")), "generation evidence digest")
      val requestText = ascii(request); val evidenceText = ascii(evidence)
      val requestPrefix = N.Profile + "\n" + frozen.hex + "\n1000 1 20 0 1\n"
      valid(
        requestText.startsWith(requestPrefix) && requestText.endsWith("\n"),
        "generation request echo"
      )
      val requestRows =
        requestText.substring(requestPrefix.length).split("\n", -1).toVector.dropRight(1)
      valid(requestRows.size <= N.MaxPools, "generation pool bound")
      val inputs = requestRows.map { row =>
        valid(
          row.matches("[0-9a-f]{56} (0|[1-9][0-9]*) (0|[1-9][0-9]*) (0|[1-9][0-9]*)"),
          "request row"
        )
        val parts = row.split(" ")
        val stake = decimal(parts(1)); val circulation = decimal(parts(2));
        val blocks = decimal(parts(3))
        valid(circulation > 0 && stake <= circulation && blocks <= 1000, "request quantities")
        (parts(0), stake, circulation, blocks)
      }
      equal(inputs.map(_._1), inputs.map(_._1).distinct.sorted, "generation pool order/domain")
      val prefix =
        if mode == N.Mode.PureJvm then
          lab.ledger.ConwayLikelihoodGeneration.Profile + "\n" +
            requestText + "--jvm--\n"
        else requestText + "--native--\n"
      valid(
        evidenceText.startsWith(prefix) && evidenceText.endsWith("\n"),
        "generation evidence echo"
      )
      val outputRows = evidenceText.substring(prefix.length).split("\n", -1).toVector.dropRight(1)
      equal(outputRows.size, inputs.size, "generation output domain")
      inputs.zip(outputRows).foreach { case ((pool, stake, circulation, blocks), row) =>
        valid(row.matches("[0-9a-f]{56} [0-9a-f]{16} [0-9a-f]{800}"), "generation raw word row")
        val parts = row.split(" "); equal(parts(0), pool, "generation output pool")
        val probability =
          1.0 - java.lang.Math.pow(1.0 - 1.0 / 20.0, stake.toDouble / circulation.toDouble)
        val raw64 = java.lang.Double.doubleToRawLongBits(probability)
        equal(parts(1), f"$raw64%016x", "generation raw64 probability")
        val expectedWords = Vector
          .tabulate(100) { i =>
            val x = (i.toDouble + 0.5) / 100.0
            val value = (blocks.toDouble * java.lang.Math.log(x) +
              (1000 - blocks).toDouble * java.lang.Math.log(1.0 - probability * x)).toFloat
            valid(java.lang.Float.isFinite(value), "finite generated raw32")
            val word = java.lang.Float.floatToRawIntBits(value); f"$word%08x"
          }
          .mkString
        equal(parts(2), expectedWords, "generation raw32 output")
      }
      equal(
        number(field(g, "computedRaw32Words")),
        BigInt(inputs.size * 100),
        "computed raw32 count"
      )
      equal(number(field(g, "computedRaw64Words")), BigInt(inputs.size), "computed raw64 count")
      val native = mode == N.Mode.CheckedJvm
      equal(field(g, "nativeValidated"), bool(native), "native validated flag")
      equal(field(g, "diagnosticNativeDependency"), bool(native), "native dependency flag")
      equal(
        number(field(g, "raw32Comparisons")),
        BigInt(if native then inputs.size * 100 else 0),
        "raw32 comparisons"
      )
      equal(
        number(field(g, "raw64Comparisons")),
        BigInt(if native then inputs.size else 0),
        "raw64 comparisons"
      )
      if native then
        val response = original(field(g, "nativeResponseOriginal"), "native response")
        equal(response, evidence, "checked native response original")
        equal(sha(response), hash(field(g, "nativeResponseSHA256")), "native response digest")
      else
        equal(field(g, "nativeResponseOriginal"), nullValue, "pure JVM native response")
        equal(field(g, "nativeResponseSHA256"), nullValue, "pure JVM native response digest")
  def read(raw: Bytes): Either[Failure, Observation] = boundary:
    valid(raw != null && raw.size > 0 && raw.size <= MaxBytes, "repeated terminal byte bound")
    val parsed =
      try Right(ReferenceJson.parse(raw))
      catch
        case NonFatal(e) =>
          Left(Failure.Invalid("terminal JSON", Option(e.getMessage).getOrElse("parse failure")))
    val j = unwrap(parsed)
    equal(
      obj(j).keySet,
      Set(
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
        "representedProtocol",
        "components",
        "componentsSHA256",
        "repeatedEpoch"
      ),
      "terminal field set"
    )
    equal(field(j, "schema"), str(Schema), "terminal schema")
    equal(field(j, "diagnosticOnly"), bool(true), "diagnostic flag")
    equal(field(j, "restartSupported"), bool(false), "restart flag")
    equal(field(j, "fullLedgerValidated"), bool(false), "ledger flag")
    equal(field(j, "outputMapFile"), str("terminal-output-map.cbor"), "terminal output filename")
    val p = field(j, "pin")
    equal(
      obj(p).keySet,
      Set(
        "ownerId",
        "generation",
        "point",
        "coherentStateId",
        "ledgerStateId",
        "environmentId",
        "validationSlot",
        "profileId"
      ),
      "pin fields"
    )
    val pin = get(
      StatePin.checked(
        hash(field(p, "ownerId")),
        number(field(p, "generation")),
        fullPoint(field(p, "point")),
        hash(field(p, "coherentStateId")),
        hash(field(p, "ledgerStateId")),
        hash(field(p, "environmentId")),
        number(field(p, "validationSlot")),
        text(field(p, "profileId"))
      ),
      "terminal pin"
    )
    valid(
      pin.profileId == AdmissionProfile.PlutusV3.id && pin.validationSlot == pin.point.slot &&
        number(field(j, "validationSlot")) == pin.validationSlot,
      "terminal slot/profile binding"
    )
    val components = field(j, "components")
    equal(
      sha(EvidenceJson.encode(components)),
      hash(field(j, "componentsSHA256")),
      "terminal components digest"
    )
    equal(field(j, "epoch"), field(components, "epoch"), "terminal component epoch")
    equal(field(j, "fees"), field(field(components, "pots"), "fees"), "terminal component fees")
    equal(
      field(j, "instantaneousStake"),
      field(components, "instantaneousStake"),
      "terminal component stake"
    )
    validateRepeated(j, number(field(j, "epoch")), pin)
    Right(
      new Observation(
        raw,
        j,
        pin,
        hash(field(j, "sourceJoinId")),
        hash(field(j, "initialManifestSHA256")),
        number(field(j, "epoch")),
        hash(field(j, "outputMapSHA256")),
        components
      )
    )

  private def path(root: Node, indexes: Int*)(using Scope): Node = indexes.foldLeft(root)((n, i) =>
    val xs = rows(n); valid(i >= 0 && i < xs.size, "native component path"); xs(i)
  )
  private def replacement(seed: Node, debug: Node, whole: Bytes)(using Scope): Unit =
    def loop(left: Node, right: Node, remaining: List[Int]): Unit = remaining match
      case Nil =>
        emptyMap(right, "debug UTxO placeholder")
        val unpacked = get(NativeCoinUtxoMemPack.decode(left.original), "terminal MemPack UTxO")
        equal(
          get(EndpointLedgerChecks.utxoSemantics(unpacked), "MemPack output meaning"),
          get(EndpointLedgerChecks.utxoSemantics(whole), "whole output meaning"),
          "MemPack/whole UTxO"
        )
      case i :: rest =>
        val l = rows(left); val r = arr(right, l.size); valid(i < l.size, "replacement path")
        l.indices
          .filter(_ != i)
          .foreach(k => equal(l(k).original, r(k).original, "unchanged native non-UTxO original"))
        loop(l(i), r(i), rest)
    loop(seed, debug, List(3, 1, 1, 0))

  def compareComponents(
      observation: Observation,
      initialSeed: Bytes,
      endpointSeed: Bytes,
      endpointDebug: Bytes,
      whole: Bytes,
      terminal: Bytes,
      protocolOriginal: Bytes,
      maxSupply: BigInt
  ): Either[Failure, Comparison] = boundary:
    valid(observation != null, "terminal observation")
    val seed = parse(endpointSeed); val debug = parse(endpointDebug);
    val initial = parse(initialSeed)
    replacement(seed, debug, whole)
    // These immutable source indexes have no synthesized runtime representation.
    Vector(Vector(3, 1, 0, 1, 0), Vector(3, 1, 0, 2, 2)).foreach { p =>
      equal(path(seed, p*).original, path(initial, p*).original, "immutable native source index")
    }
    val actual = unwrap(decodeComponents(endpointSeed, maxSupply))
    equal(componentsJson(actual), observation.components, "complete terminal epoch components")
    equal(actual.epoch, observation.epoch, "endpoint epoch")
    equal(
      actual.nonMyopic.id,
      hash(field(field(observation.json, "repeatedEpoch"), "nonMyopicId")),
      "terminal non-myopic identity"
    )
    equal(sha(terminal), observation.outputMapSHA256, "terminal UTxO original digest")
    val outputs = get(PlutusOutput.snapshot(whole, 0), "endpoint UTxO").outputs
    val selected = get(PlutusOutput.snapshot(terminal, 0), "terminal UTxO").outputs
    def meaning(out: PlutusOutput.Output) = (out.address, out.coin, out.datum.map(_.original))
    equal(
      outputs.view.mapValues(meaning).toMap,
      selected.view.mapValues(meaning).toMap,
      "whole terminal UTxO"
    )
    valid(outputs.values.forall(_.datum.isEmpty), "datumless terminal outputs")
    equal(
      get(S.recomputePlutus(whole, 0), "recomputed instantaneous stake"),
      actual.instantaneous,
      "native/recomputed instantaneous stake"
    )
    equal(
      outputs.values.map(_.coin).sum + actual.accounts.values.map(_.rewards).sum +
        actual.pots.treasury + actual.pots.reserves + actual.pots.fees + actual.deposits + actual.donations,
      maxSupply,
      "complete terminal supply accounting"
    )
    val native = get(NativePraosProtocol.decode(protocolOriginal), "native protocol")
    equal(native.snapshot.lastSlot, observation.pin.point.slot, "native protocol terminal slot")
    equal(
      protocol(native.snapshot.lastSlot, native.snapshot.fields, native.counters),
      field(observation.json, "representedProtocol"),
      "represented protocol fields"
    )
    Right(Comparison(actual.epoch, actual.pots.fees, outputs.size))
