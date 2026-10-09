// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import scala.util.control.NonFatal
import ConwayStake as S

/** Supplied synthetic empty-governance EPOCH projection. Opaque payloads are canonical CBOR, not
  * decoded native PParams/Globals. No admission, RATIFY implementation or publication.
  */
object ConwayEmptyGovernance:
  val Profile = "conway-pv9-supplied-empty-governance-boundary-v1"
  val MaxEntries = 4096
  val MaxPayloadBytes = 65536
  val MaxAggregateBytes = 1048576
  private val Max = (BigInt(1) << 64) - 1
  final class Payload private[ConwayEmptyGovernance] (val original: Bytes)
  final case class Anchor(url: String, hash: Bytes)
  final case class DRepState(
      expiry: BigInt,
      anchor: Option[Anchor],
      deposit: BigInt,
      delegators: Set[S.Credential]
  )
  enum Vote:
    case Credential(value: S.Credential)
    case AlwaysAbstain, AlwaysNoConfidence
  final case class Account(
      rewards: BigInt,
      deposit: BigInt,
      pool: Option[Bytes],
      vote: Option[Vote]
  )
  final case class Committee(members: Map[S.Credential, BigInt], threshold: S.Ratio)
  enum Authorization:
    case Hot(credential: S.Credential)
    case Resigned(anchor: Option[Anchor])
  final case class Constitution(anchor: Anchor, script: Option[Bytes])
  enum Purpose:
    case Parameters, HardFork, Committee, Constitution
  final case class ActionId(transaction: Bytes, index: Int)
  enum FutureParameters:
    case NoUpdate, PotentialNone
    case Pending(payload: Payload)
  final case class Parameters(current: Payload, previous: Payload, future: FutureParameters)
  final case class Globals(securityParameter: BigInt, original: Payload)
  final case class Pool(parameters: Payload, deposit: BigInt)
  final case class PoolShare(stake: BigInt, fraction: S.Ratio, vrf: Bytes)
  final case class PoolDistribution(total: BigInt, pools: Map[Bytes, PoolShare])
  final case class Enact(
      committee: Option[Committee],
      constitution: Constitution,
      current: Payload,
      previous: Payload,
      treasury: BigInt,
      withdrawals: Map[S.Credential, BigInt],
      roots: Map[Purpose, Option[ActionId]]
  )
  final case class Ratify(
      enact: Enact,
      enacted: Vector[ActionId],
      expired: Set[ActionId],
      delayed: Boolean
  )
  final case class CompletedSnapshot(
      proposals: Vector[ActionId],
      drepDistribution: Map[Vote, BigInt],
      dreps: Map[S.Credential, DRepState],
      poolDistribution: Map[Bytes, BigInt]
  )
  enum OldDRep:
    case Unknown, Pulsing
    case Complete(snapshot: CompletedSnapshot, ratify: Ratify)
  final case class Deposits(
      stake: Map[S.Credential, BigInt],
      pools: Map[Bytes, BigInt],
      dreps: Map[S.Credential, BigInt],
      proposals: Map[ActionId, BigInt],
      total: BigInt
  )
  final case class Input(
      epoch: BigInt,
      dormant: BigInt,
      dreps: Map[S.Credential, DRepState],
      committee: Option[Committee],
      committeeState: Map[S.Credential, Authorization],
      constitution: Constitution,
      parameters: Parameters,
      roots: Map[Purpose, Option[ActionId]],
      proposals: Map[ActionId, Payload],
      oldDRep: OldDRep,
      accounts: Map[S.Credential, Account],
      instantaneous: Map[S.Credential, BigInt],
      newMarkPoolDistribution: PoolDistribution,
      stakePools: Map[Bytes, Pool],
      poolUpdates: Map[Bytes, Pool],
      retirements: Map[Bytes, BigInt],
      proposalDeposits: Map[S.Credential, BigInt],
      donations: BigInt,
      treasury: BigInt,
      deposits: Deposits,
      globals: Globals
  )
  final case class FreshPulsing(
      pulseSize: Int,
      index: Int,
      accounts: Map[S.Credential, Account],
      instantaneous: Map[S.Credential, BigInt],
      stakePoolDistribution: PoolDistribution,
      drepDistribution: Map[Vote, BigInt],
      dreps: Map[S.Credential, DRepState],
      epoch: BigInt,
      committeeState: Map[S.Credential, Authorization],
      enact: Enact,
      proposals: Vector[ActionId],
      proposalDeposits: Map[S.Credential, BigInt],
      globals: Globals,
      stakePools: Map[Bytes, Pool]
  )
  final class Applied private[ConwayEmptyGovernance] (
      val before: Input,
      val epoch: BigInt,
      val dormant: BigInt,
      val committeeState: Map[S.Credential, Authorization],
      val parameters: Parameters,
      val fresh: FreshPulsing,
      val id: Bytes
  ):
    val dreps = before.dreps
    val committee = before.committee
    val constitution = before.constitution
    val roots = before.roots
    val deposits = before.deposits
    val treasury = before.treasury
    val donations = BigInt(0)
    val syntheticOnly = true
    val nativePayloadsValidated = false
    val nativeEquivalent = false
    val epochTransitionValidated = false
    val published = false

  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](value: Either[String, A]): A =
    value.fold(e => throw new IllegalArgumentException(e), identity)
  private def coin(n: BigInt): Boolean = n != null && n >= 0 && n <= Max
  private def width(b: Bytes, n: Int): Boolean = b != null && b.value != null && b.size == n
  private def cred(c: S.Credential): Boolean = c != null && width(c.hash, 28)
  private def ratio(r: S.Ratio): Boolean = r != null && coin(r.numerator) && coin(r.denominator) &&
    r.denominator > 0 && r.numerator <= r.denominator && r.numerator.gcd(r.denominator) == 1
  private def action(a: ActionId): Boolean =
    a != null && width(a.transaction, 32) && a.index >= 0 && a.index <= 65535
  private def anchor(a: Anchor): Boolean = a != null && a.url != null &&
    a.url.length <= 128 && a.url.getBytes("UTF-8").length <= 128 &&
    new String(a.url.getBytes("UTF-8"), "UTF-8") == a.url && width(a.hash, 32)
  private def node(v: V): Node = Node(v, Bytes.empty)
  private def canonical(v: V): V = v match
    case V.Arr(xs) => V.Arr(xs.map(x => node(canonical(x.value))))
    case V.Map(xs) =>
      val fields = xs.map((k, v) => (node(canonical(k.value)), node(canonical(v.value))))
      val keys = fields.map((k, _) => get(Cbor.encode(k.value)))
      require(keys.distinct.size == keys.size, "duplicate payload map key")
      V.Map(fields.zip(keys).sortBy((_, key) => (key.size, key.hex)).map(_._1))
    case V.Tag(n, value) => V.Tag(n, node(canonical(value.value)))
    case other           => other
  def payload(raw: Bytes): Either[String, Payload] = checked {
    require(raw != null && raw.size > 0 && raw.size <= MaxPayloadBytes, "payload byte bound")
    val decoded = get(Cbor.decode(raw, Cbor.Limits(MaxPayloadBytes, 16, 8192, MaxPayloadBytes)))
    require(get(Cbor.encode(canonical(decoded.value))) == raw, "canonical payload required")
    new Payload(raw)
  }
  private def same(a: Payload, b: Payload): Boolean =
    a != null && b != null && a.original == b.original

  // Bound the entire typed graph before validation/identity; collections are never accepted by omission.
  private def bound(value: Any): Unit =
    var items = 0; var bytes = 0L
    def visit(v: Any): Unit =
      items += 1; require(items <= 65536, "aggregate item bound")
      v match
        case null       => throw new IllegalArgumentException("null governance field")
        case p: Payload => bytes += p.original.size
        case b: Bytes   => bytes += b.size
        case s: String =>
          require(s.length <= MaxPayloadBytes, "string character bound")
          bytes += s.getBytes("UTF-8").length
        case m: Map[?, ?] =>
          require(m.size <= MaxEntries, "map bound"); m.foreach((k, v) => { visit(k); visit(v) })
        case s: Set[?]    => require(s.size <= MaxEntries, "set bound"); s.foreach(visit)
        case s: Vector[?] => require(s.size <= MaxEntries, "sequence bound"); s.foreach(visit)
        case n: BigInt    => require(n.bitLength <= 64, "integer width bound")
        case _: Int | _: Boolean => ()
        case p: Product          => p.productIterator.foreach(visit)
        case _ => throw new IllegalArgumentException("unsupported governance value")
      require(bytes <= MaxAggregateBytes, "aggregate payload bound")
    visit(value)
  private def identityText(v: Any): String =
    def frame(s: String) = s.length.toString + ":" + s
    v match
      case p: Payload => "payload" + frame(p.original.hex)
      case b: Bytes   => "bytes" + frame(b.hex)
      case m: Map[?, ?] =>
        "map" + m.toVector
          .map((k, v) => frame(identityText(k)) + frame(identityText(v)))
          .sorted
          .map(frame)
          .mkString
      case s: Set[?]    => "set" + s.toVector.map(identityText).sorted.map(frame).mkString
      case s: Vector[?] => "vector" + s.map(x => frame(identityText(x))).mkString
      case p: Product =>
        frame(p.productPrefix) + p.productIterator.map(x => frame(identityText(x))).mkString
      case x => frame(x.toString)
  private def checkDReps(ds: Map[S.Credential, DRepState]): Unit = ds.foreach { (c, d) =>
    require(
      cred(c) && coin(d.expiry) && coin(d.deposit) && d.anchor.forall(anchor) && d.delegators
        .forall(cred),
      "DRep fields"
    )
  }
  private def checkVote(v: Vote): Boolean = v match
    case Vote.Credential(c)                           => cred(c)
    case Vote.AlwaysAbstain | Vote.AlwaysNoConfidence => true
  private def checkCommittee(c: Option[Committee]): Unit = c.foreach { x =>
    require(
      ratio(x.threshold) && x.members.forall((c, e) => cred(c) && coin(e)),
      "committee fields"
    )
  }
  private def checkRoots(roots: Map[Purpose, Option[ActionId]]): Unit =
    require(
      roots.keySet == Purpose.values.toSet && roots.values.forall(_.forall(action)),
      "all four proposal roots required"
    )

  /** Caller supplies post-reward/SNAP inputs; this models only the declared empty EPOCH slice. */
  def applyBoundary(input: Input, successorEpoch: BigInt): Either[String, Applied] = checked {
    bound(input)
    require(
      coin(input.epoch) && input.epoch < Max && successorEpoch == input.epoch + 1,
      "exact successor epoch required"
    )
    require(coin(input.dormant) && input.dormant < Max, "dormant counter exhausted")
    require(coin(input.treasury) && input.donations == 0, "treasury bounds/zero donations required")
    require(
      coin(input.globals.securityParameter) && input.globals.securityParameter > 0,
      "positive security parameter required"
    )
    require(
      input.proposals.isEmpty && input.proposalDeposits.isEmpty && input.poolUpdates.isEmpty && input.retirements.isEmpty,
      "empty proposals/deposits and no pending pool changes required"
    )
    require(
      input.parameters.future == FutureParameters.NoUpdate || input.parameters.future == FutureParameters.PotentialNone,
      "no pending parameter update required"
    )
    checkDReps(input.dreps); checkCommittee(input.committee); checkRoots(input.roots)
    require(
      anchor(input.constitution.anchor) && input.constitution.script.forall(width(_, 28)),
      "constitution fields"
    )
    input.committeeState.foreach { (c, a) =>
      require(
        cred(c) && (a match
          case Authorization.Hot(h)      => cred(h)
          case Authorization.Resigned(a) => a.forall(anchor)),
        "committee authorization fields"
      )
    }
    input.accounts.foreach { (c, a) =>
      require(
        cred(c) && coin(a.rewards) && coin(a.deposit) &&
          a.pool.forall(p => width(p, 28) && input.stakePools.contains(p)) && a.vote.forall(
            checkVote
          ),
        "account fields"
      )
    }
    require(input.instantaneous.forall((c, n) => cred(c) && coin(n)), "instantaneous fields")
    require(input.stakePools.forall((p, s) => width(p, 28) && coin(s.deposit)), "stake pool fields")
    val pd = input.newMarkPoolDistribution
    require(
      coin(pd.total) && pd.total > 0 && pd.pools.forall { (p, s) =>
        width(p, 28) && input.stakePools.contains(p) && coin(s.stake) && ratio(s.fraction) && width(
          s.vrf,
          32
        ) &&
        s.fraction.numerator * pd.total == s.stake * s.fraction.denominator
      } && pd.pools.values.map(_.stake).sum == (if pd.pools.isEmpty then BigInt(0) else pd.total),
      "new mark pool distribution"
    )
    require(pd.pools.nonEmpty || pd.total == 1, "empty distribution denominator sentinel")
    val ds = input.deposits
    require(
      ds.stake == input.accounts.map((c, a) => c -> a.deposit) &&
        ds.pools == input.stakePools.map((p, s) => p -> s.deposit) &&
        ds.dreps == input.dreps.map((c, d) => c -> d.deposit) && ds.proposals.isEmpty && coin(
          ds.total
        ) &&
        ds.total == ds.stake.values.sum + ds.pools.values.sum + ds.dreps.values.sum,
      "deposit obligation mismatch"
    )
    input.oldDRep match
      case OldDRep.Complete(snapshot, ratify) =>
        require(
          snapshot.proposals.isEmpty && ratify.enacted.isEmpty && ratify.expired.isEmpty && !ratify.delayed,
          "old completed snapshot/ratification must have no pending actions"
        )
        checkDReps(snapshot.dreps)
        require(
          snapshot.drepDistribution.forall { (v, n) =>
            checkVote(v) && coin(n) && (v match
              case Vote.Credential(c) => snapshot.dreps.contains(c)
              case _                  => true)
          } &&
            snapshot.poolDistribution.forall((p, n) => width(p, 28) && coin(n)),
          "old completed distribution fields"
        )
        val e = ratify.enact
        require(
          e.committee == input.committee && e.constitution == input.constitution &&
            same(e.current, input.parameters.current) && same(
              e.previous,
              input.parameters.previous
            ) &&
            e.roots == input.roots && e.treasury == 0 && e.withdrawals.isEmpty,
          "old enactment differs or has treasury/withdrawal effects"
        )
      case _ =>
        throw new IllegalArgumentException(
          "completed old DRep state required; unknown/pulsing unsupported"
        )
    val committeeState =
      input.committeeState.filter((c, _) => input.committee.exists(_.members.contains(c)))
    val parameters =
      Parameters(input.parameters.current, input.parameters.current, FutureParameters.PotentialNone)
    val enact = Enact(
      input.committee,
      input.constitution,
      parameters.current,
      parameters.previous,
      input.treasury,
      Map.empty,
      input.roots
    )
    val chunk =
      (BigInt(input.accounts.size) / (4 * input.globals.securityParameter)).max(BigInt(1)).toInt
    val fresh = FreshPulsing(
      chunk,
      0,
      input.accounts,
      input.instantaneous,
      pd,
      Map.empty,
      input.dreps,
      successorEpoch,
      committeeState,
      enact,
      Vector.empty,
      Map.empty,
      input.globals,
      input.stakePools
    )
    val identity = Blake2b.hash256.hash(
      Bytes.fromArray(
        (Profile + identityText(input) +
          identityText((successorEpoch, input.dormant + 1, committeeState, parameters, fresh)))
          .getBytes("UTF-8")
      )
    )
    new Applied(
      input,
      successorEpoch,
      input.dormant + 1,
      committeeState,
      parameters,
      fresh,
      identity
    )
  }
