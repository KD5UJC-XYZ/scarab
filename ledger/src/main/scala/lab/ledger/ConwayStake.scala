// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import scala.util.control.NonFatal

/** Checked stake projection only. No reward progression, epoch tick, governance or publication. */
object ConwayStake:
  val Profile = "conway-fixed-pool-stake-v1"
  private val Max = (BigInt(1) << 64) - 1
  final case class Credential(script: Boolean, hash: Bytes):
    def key: String = (if script then "scriptHash-" else "keyHash-") + hash.hex
  final case class Account(balance: BigInt, deposit: BigInt, delegation: Option[Bytes])
  final case class Ratio(numerator: BigInt, denominator: BigInt)
  final case class Pool(
      vrf: Bytes,
      pledge: BigInt,
      cost: BigInt,
      margin: Ratio,
      rewardAccount: Credential,
      owners: Set[Bytes],
      delegators: Set[Credential],
      deposit: BigInt
  )
  final case class Active(coin: BigInt, pool: Bytes)
  final case class PoolSnapshot(
      coin: BigInt,
      ratio: Ratio,
      owners: Set[Bytes],
      ownerCoin: BigInt,
      vrf: Bytes,
      pledge: BigInt,
      cost: BigInt,
      margin: Ratio,
      delegators: Int,
      rewardAccount: Credential
  )
  final class Snapshot private[ConwayStake] (
      val active: Map[Credential, Active],
      val total: BigInt,
      val pools: Map[Bytes, PoolSnapshot]
  ):
    def distribution: Map[Bytes, PoolSnapshot] = pools.filter(_._2.delegators > 0)
  final case class Snapshots(mark: Snapshot, set: Snapshot, go: Snapshot, fees: BigInt)
  final case class Output(credential: Option[Credential], coin: BigInt, original: Bytes)
  final class Context private[ConwayStake] (
      val id: Bytes,
      val epochLength: BigInt,
      val accounts: Map[Credential, Account],
      val pools: Map[Bytes, Pool]
  )
  final class Owner private[ConwayStake] ()
  def owner(): Owner = new Owner()
  final class State private[ConwayStake] (
      private[ConwayStake] val owner: Owner,
      val context: Context,
      val id: Bytes,
      val ledgerId: Bytes,
      val revision: BigInt,
      val slot: BigInt,
      val epoch: BigInt,
      val utxo: Map[TxIn, Output],
      val instantaneous: Map[Credential, BigInt],
      val snapshots: Snapshots
  )
  final class Candidate private[ConwayStake] (
      private[ConwayStake] val owner: Owner,
      val beforeId: Bytes,
      val beforeRevision: BigInt,
      val headerHash: Bytes,
      val headerSlot: BigInt,
      private[ConwayStake] val after: State
  ):
    val profileId = Profile
    def instantaneous: Map[Credential, BigInt] = after.instantaneous
  final class Rotation private[ConwayStake] (
      private[ConwayStake] val owner: Owner,
      val beforeId: Bytes,
      val beforeRevision: BigInt,
      val headerHash: Bytes,
      val headerSlot: BigInt,
      val snapshots: Snapshots,
      val leadership: Snapshot
  ):
    val profileId = Profile
    val epochTransitionValidated = false

  private def protect[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A, E](e: Either[E, A]): A =
    e.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def size(b: Bytes, n: Int): Boolean = b != null && b.value != null && b.size == n
  private def coin(n: BigInt): Boolean = n != null && n >= 0 && n <= Max
  private def credential(c: Credential): Boolean = c != null && size(c.hash, 28)
  private def ratio(r: Ratio): Boolean = r != null && coin(r.numerator) && coin(r.denominator) &&
    r.denominator > 0 && r.numerator <= r.denominator && r.numerator.gcd(r.denominator) == 1
  def fraction(n: BigInt, d: BigInt): Either[String, Ratio] = protect {
    require(coin(n) && coin(d) && d > 0 && n <= d, "stake fraction bounds")
    val g = n.gcd(d); Ratio(n / g, d / g)
  }
  private def hash(s: String): Bytes = Blake2b.hash256.hash(Bytes.fromArray(s.getBytes("UTF-8")))
  private def contextText(accounts: Map[Credential, Account], pools: Map[Bytes, Pool]): String =
    accounts.toVector
      .sortBy(_._1.key)
      .map { (c, a) => s"${c.key}:${a.balance}:${a.deposit}:${a.delegation.map(_.hex)}" }
      .mkString("|") + "\n" +
      pools.toVector
        .sortBy(_._1.hex)
        .map { (id, p) =>
          s"${id.hex}:${p.vrf.hex}:${p.pledge}:${p.cost}:${p.margin}:${p.rewardAccount.key}:${p.deposit}:" +
            p.owners.toVector.map(_.hex).sorted.mkString(",") + ":" + p.delegators.toVector
              .map(_.key)
              .sorted
              .mkString(",")
        }
        .mkString("|")
  def context(
      sourceDigest: Bytes,
      epochLength: BigInt,
      accounts: Map[Credential, Account],
      pools: Map[Bytes, Pool]
  ): Either[String, Context] = protect {
    require(
      size(sourceDigest, 32) && coin(epochLength) && epochLength > 0,
      "stake context source/epoch"
    )
    require(
      accounts != null && pools != null && accounts.size <= 4096 && pools.size <= 4096,
      "stake context bound"
    )
    require(
      accounts.forall { (c, a) =>
        credential(c) && a != null && coin(a.balance) && coin(a.deposit) &&
        a.delegation != null && a.delegation.forall(p => size(p, 28) && pools.contains(p))
      },
      "stake accounts/delegations"
    )
    require(
      pools.forall { (id, p) =>
        size(id, 28) && p != null && size(p.vrf, 32) && coin(p.pledge) && coin(p.cost) &&
        coin(p.deposit) && ratio(p.margin) && credential(
          p.rewardAccount
        ) && p.owners != null && p.owners.size <= 4096 &&
        p.owners.forall(
          size(_, 28)
        ) && p.delegators != null && p.delegators.size <= 4096 && p.delegators.forall(credential) &&
        p.delegators == accounts.collect { case (c, a) if a.delegation.contains(id) => c }.toSet
      },
      "pool parameters/delegator index mismatch"
    )
    new Context(
      hash(Profile + sourceDigest.hex + epochLength + contextText(accounts, pools)),
      epochLength,
      accounts,
      pools
    )
  }
  private def uint(n: Node): BigInt = n.value match
    case V.UInt(v) if coin(v) => v
    case _                    => throw new IllegalArgumentException("bounded stake coin required")
  private def amount(n: Node): BigInt = n.value match
    case V.UInt(_) => uint(n)
    case V.Arr(Vector(ada, assets)) =>
      assets.value match
        case V.Map(policies) =>
          require(policies.map(_._1.value).distinct.size == policies.size, "duplicate policy")
          policies.foreach { (p, names) =>
            require(
              p.value match { case V.ByteString(b) => size(b, 28); case _ => false },
              "policy width"
            )
            names.value match
              case V.Map(entries) =>
                require(entries.map(_._1.value).distinct.size == entries.size, "duplicate asset")
                entries.foreach { (name, quantity) =>
                  require(
                    name.value match { case V.ByteString(b) => b.size <= 32; case _ => false },
                    "asset name"
                  )
                  require(uint(quantity) > 0, "positive asset quantity")
                }
              case _ => throw new IllegalArgumentException("asset map")
          }
        case _ => throw new IllegalArgumentException("multiasset map")
      uint(ada)
    case _ => throw new IllegalArgumentException("stake value shape")

  /** Whole supplied UTxO; supports closed base/enterprise address forms, including script stake. */
  def decodeUtxo(raw: Bytes): Either[String, Map[TxIn, Output]] = protect {
    val nodes = get(NativeSpending.snapshot(raw))
    nodes.map { (ref, n) =>
      val (a, v) = n.value match
        case V.Arr(Vector(a, v)) => (a, v)
        case V.Map(fields)
            if fields.size == 2 && fields.map(_._1.value).toSet == Set(V.UInt(0), V.UInt(1)) =>
          (fields.find(_._1.value == V.UInt(0)).get._2, fields.find(_._1.value == V.UInt(1)).get._2)
        case _ => throw new IllegalArgumentException("stake output supports address/value only")
      val address = a.value match
        case V.ByteString(b) if b.size > 0 => b
        case _ => throw new IllegalArgumentException("stake address bytes")
      val kind = (address.value.head & 255) >>> 4
      require(
        (address.value.head & 15) == 0 &&
          ((kind <= 3 && address.size == 57) || ((kind == 6 || kind == 7) && address.size == 29)),
        "unsupported stake address kind/network"
      )
      val cred =
        if kind <= 3 then Some(Credential(kind >= 2, Bytes(address.value.drop(29)))) else None
      ref -> Output(cred, amount(v), n.original)
    }
  }

  /** Opt-in bounded datum projection; every output retains its original bytes. */
  private[lab] def decodePlutusUtxo(
      raw: Bytes,
      networkId: Int
  ): Either[String, Map[TxIn, Output]] =
    PlutusOutput
      .snapshot(raw, networkId)
      .map(_.outputs.map { (ref, out) =>
        ref -> Output(out.stakeCredential, out.coin, out.original)
      })

  private[lab] def recomputePlutus(
      raw: Bytes,
      networkId: Int
  ): Either[String, Map[Credential, BigInt]] =
    decodePlutusUtxo(raw, networkId).flatMap(m => protect(totals(m.values)))

  // Selection comes only from the checked ledger being projected, never a caller flag.
  private def decodeLedgerUtxo(ledger: ClusterTransition.State): Either[String, Map[TxIn, Output]] =
    ledger.environment.plutus.map(_.networkId) match
      case Some(networkId) => decodePlutusUtxo(ledger.outputMap, networkId)
      case None            => decodeUtxo(ledger.outputMap)

  private def totals(outputs: Iterable[Output]): Map[Credential, BigInt] =
    outputs.foldLeft(Map.empty[Credential, BigInt]) { (m, o) =>
      o.credential.fold(m) { c =>
        val total = m.getOrElse(c, BigInt(0)) + o.coin
        require(coin(total), "stake aggregate overflow")
        if total == 0 then m else m.updated(c, total)
      }
    }
  def recompute(raw: Bytes): Either[String, Map[Credential, BigInt]] =
    decodeUtxo(raw).flatMap(m => protect(totals(m.values)))
  def snapshot(context: Context, instantaneous: Map[Credential, BigInt]): Either[String, Snapshot] =
    protect {
      require(
        context != null && instantaneous != null && instantaneous.size <= 4096 && instantaneous
          .forall((c, n) => credential(c) && coin(n) && n > 0),
        "instant stake shape"
      )
      val active = context.accounts.flatMap { (c, a) =>
        val total = a.balance + instantaneous.getOrElse(c, BigInt(0))
        require(coin(total), "active account overflow")
        a.delegation.filter(_ => total > 0).map(p => c -> Active(total, p))
      }
      get(fromActive(context, active))
    }
  def fromActive(context: Context, active: Map[Credential, Active]): Either[String, Snapshot] =
    protect {
      require(
        context != null && active != null && active.size <= 4096 && active.forall { (c, a) =>
          credential(c) && a != null &&
          coin(a.coin) && a.coin > 0 && context.accounts
            .get(c)
            .exists(_.delegation.contains(a.pool))
        },
        "snapshot active stake/delegation mismatch"
      )
      val total = active.values.map(_.coin).sum.max(BigInt(1))
      require(coin(total), "active total overflow")
      val pools = context.pools.map { (id, p) =>
        val stake = p.delegators.toVector.flatMap(active.get).map(_.coin).sum
        val owners = p.owners.filter(k => p.delegators.contains(Credential(false, k)))
        val ownerCoin =
          owners.toVector.flatMap(k => active.get(Credential(false, k))).map(_.coin).sum
        id -> PoolSnapshot(
          stake,
          get(fraction(stake, total)),
          owners,
          ownerCoin,
          p.vrf,
          p.pledge,
          p.cost,
          p.margin,
          p.delegators.size,
          p.rewardAccount
        )
      }
      new Snapshot(active, total, pools)
    }
  def emptySnapshot: Snapshot = new Snapshot(Map.empty, 1, Map.empty)
  private def snapshotText(s: Snapshot): String = s.active.toVector
    .sortBy(_._1.key)
    .map((c, a) => s"${c.key}:${a.coin}:${a.pool.hex}")
    .mkString("|") +
    s.pools.toVector
      .sortBy(_._1.hex)
      .map { (p, s) =>
        p.hex + ":" + s
          .copy(owners = Set.empty)
          .toString + ":" + s.owners.toVector.map(_.hex).sorted.mkString(",")
      }
      .mkString("|")
  def seed(
      owner: Owner,
      context: Context,
      ledger: ClusterTransition.State,
      sourceDigest: Bytes,
      exported: Map[Credential, BigInt],
      snapshots: Snapshots
  ): Either[String, State] = protect {
    require(
      owner != null && context != null && ledger != null && size(
        sourceDigest,
        32
      ) && snapshots != null &&
        snapshots.mark != null && snapshots.set != null && snapshots.go != null && coin(
          snapshots.fees
        ),
      "stake seed shape"
    )
    require(
      ledger.slot / context.epochLength == ledger.environment.epoch,
      "stake seed epoch geometry"
    )
    Vector(snapshots.mark, snapshots.set, snapshots.go).foreach { s =>
      require(
        (s.active.isEmpty && s.pools.isEmpty && s.total == 1) || {
          val expected = get(fromActive(context, s.active));
          s.total == expected.total && s.pools == expected.pools
        },
        "snapshot/context mismatch"
      )
    }
    val utxo = get(decodeLedgerUtxo(ledger)); val instantaneous = totals(utxo.values)
    require(instantaneous == exported, "whole UTxO/instantaneous export mismatch")
    val id = hash(
      Profile + context.id.hex + ledger.id.hex + sourceDigest.hex + snapshotText(
        snapshots.mark
      ) + "\n" + snapshotText(snapshots.set) + "\n" + snapshotText(snapshots.go) + snapshots.fees
    )
    new State(
      owner,
      context,
      id,
      ledger.id,
      ledger.revision,
      ledger.slot,
      ledger.environment.epoch,
      utxo,
      instantaneous,
      snapshots
    )
  }

  /** Evaluates an existing checked ledger candidate without publishing it. No caller-supplied
    * deltas.
    */
  def prepare(
      owner: Owner,
      current: State,
      ledger: ClusterTransition.State,
      block: ClusterTransition.BlockCandidate
  ): Either[String, Candidate] = protect {
    require(
      owner != null && current != null && (owner eq current.owner) && ledger != null && block != null &&
        current.ledgerId == ledger.id && current.revision == ledger.revision,
      "stake owner/before/revision mismatch"
    )
    require(
      block.slot > current.slot && block.slot / current.context.epochLength == current.epoch,
      "stake same-epoch slot guard"
    )
    val accepted = get(ClusterTransition.commitBlock(ledger, block))
    val next = get(decodeLedgerUtxo(accepted.state))
    val shared = current.utxo.keySet intersect next.keySet
    require(shared.forall(k => current.utxo(k) == next(k)), "existing output changed")
    val spent = totals((current.utxo.keySet -- next.keySet).toVector.map(current.utxo))
    val created = totals((next.keySet -- current.utxo.keySet).toVector.map(next))
    val incremental = (current.instantaneous.keySet ++ spent.keySet ++ created.keySet)
      .map { c =>
        val n = current.instantaneous
          .getOrElse(c, BigInt(0)) - spent.getOrElse(c, BigInt(0)) + created.getOrElse(c, BigInt(0))
        require(coin(n), "stake delta bounds"); c -> n
      }
      .filter(_._2 > 0)
      .toMap
    require(incremental == totals(next.values), "incremental/full stake mismatch")
    val after = new State(
      owner,
      current.context,
      hash(current.id.hex + accepted.state.id.hex + block.headerHash.hex),
      accepted.state.id,
      accepted.state.revision,
      block.slot,
      current.epoch,
      next,
      incremental,
      current.snapshots
    )
    new Candidate(owner, current.id, current.revision, block.headerHash, block.slot, after)
  }

  /** Internal synthetic successor projection. The enclosing coordinator owns ancestry and
    * omitted-effect assumptions. Only the existing ledger candidate may supply body changes.
    */
  private[lab] def prepareSyntheticSuccessor(
      owner: Owner,
      current: State,
      ledger: ClusterTransition.State,
      block: ClusterTransition.BlockCandidate,
      preview: ConwayEpochBoundary.Preview
  ): Either[String, Candidate] = protect {
    require(
      owner != null && current != null && (owner eq current.owner) &&
        ledger != null && block != null && preview != null &&
        (preview.before.stake eq current) && current.ledgerId == ledger.id &&
        current.revision == ledger.revision && current.slot == ledger.slot &&
        current.epoch == ledger.environment.epoch,
      "synthetic stake owner/before/revision"
    )
    require(ledger.environment.plutus.isEmpty, "Plutus synthetic stake successor unsupported")
    val rotation = preview.rotation
    require(
      (rotation.owner eq owner) && rotation.beforeId == current.id &&
        rotation.beforeRevision == current.revision &&
        preview.signal.contextId == preview.before.id &&
        preview.signal.beforeRevision == current.revision &&
        block.headerHash == preview.signal.headerHash && block.slot == preview.signal.slot &&
        rotation.headerHash == block.headerHash && rotation.headerSlot == block.slot &&
        block.syntheticBoundary.contains((preview.id, preview.pots.fees)) &&
        block.slot > current.slot && block.slot / current.context.epochLength == current.epoch + 1,
      "synthetic stake boundary/block binding"
    )
    val application = preview.before.application
    require(
      application.epochLength == current.context.epochLength &&
        preview.balances.keySet == application.accounts.keySet && preview.balances.values
          .forall(coin),
      "synthetic post-reward account domain"
    )
    val post = get(
      context(
        application.id,
        application.epochLength,
        application.accounts.map((c, a) => c -> a.copy(balance = preview.balances(c))),
        application.pools
      )
    )
    val accepted = get(ClusterTransition.commitBlock(ledger, block)).state
    require(
      accepted.environment.epoch == current.epoch + 1 &&
        accepted.revision == current.revision + 1,
      "synthetic successor ledger geometry"
    )
    val next = get(decodeLedgerUtxo(accepted))
    val shared = current.utxo.keySet intersect next.keySet
    require(shared.forall(k => current.utxo(k) == next(k)), "existing output changed")
    val spent = totals((current.utxo.keySet -- next.keySet).toVector.map(current.utxo))
    val created = totals((next.keySet -- current.utxo.keySet).toVector.map(next))
    val incremental = (current.instantaneous.keySet ++ spent.keySet ++ created.keySet)
      .map { c =>
        val n = current.instantaneous.getOrElse(c, BigInt(0)) - spent.getOrElse(c, BigInt(0)) +
          created.getOrElse(c, BigInt(0))
        require(coin(n), "stake delta bounds"); c -> n
      }
      .filter(_._2 > 0)
      .toMap
    require(incremental == totals(next.values), "incremental/full stake mismatch")
    val after = new State(
      owner,
      post,
      hash(current.id.hex + accepted.id.hex + block.headerHash.hex + preview.id.hex + post.id.hex),
      accepted.id,
      accepted.revision,
      block.slot,
      current.epoch + 1,
      next,
      incremental,
      rotation.snapshots
    )
    new Candidate(owner, current.id, current.revision, block.headerHash, block.slot, after)
  }

  /** Pure candidate selection for an enclosing atomic coordinator. No runtime state is mutated. */
  def select(owner: Owner, current: State, candidate: Candidate): Either[String, State] = protect {
    require(
      owner != null && current != null && candidate != null && (owner eq current.owner) && (owner eq candidate.owner) &&
        current.id == candidate.beforeId && current.revision == candidate.beforeRevision,
      "stale/foreign stake candidate"
    )
    candidate.after
  }

  /** Coordinator-owned undo: preserve semantic identity while rebinding the increasing ledger
    * revision. The coordinator must first verify its whole-tuple receipt and apply ledger undo.
    */
  private[lab] def rebindAfterUndo(
      owner: Owner,
      before: State,
      ledger: ClusterTransition.State
  ): Either[String, State] = protect {
    require(
      owner != null && before != null && (owner eq before.owner) && ledger != null &&
        ledger.id == before.ledgerId && ledger.slot == before.slot &&
        ledger.environment.epoch == before.epoch && ledger.revision > before.revision,
      "stake undo ledger/owner/revision mismatch"
    )
    val utxo = get(decodeLedgerUtxo(ledger))
    require(
      utxo == before.utxo && totals(utxo.values) == before.instantaneous,
      "stake undo content mismatch"
    )
    new State(
      owner,
      before.context,
      before.id,
      ledger.id,
      ledger.revision,
      ledger.slot,
      before.epoch,
      utxo,
      before.instantaneous,
      before.snapshots
    )
  }

  /** Opaque-source recovery only: enclosing controller authenticates the retained capsule. There is
    * deliberately no caller-supplied identity or revision.
    */
  private[lab] def reownForRecovery(newOwner: Owner, original: State): Either[String, State] =
    protect {
      require(
        newOwner != null && original != null && original.owner != null,
        "recovery stake owner/source"
      )
      require(
        size(original.id, 32) && size(original.ledgerId, 32) && coin(original.revision) &&
          coin(original.slot) && original.slot / original.context.epochLength == original.epoch &&
          original.utxo.size <= 4096 && original.instantaneous == totals(original.utxo.values),
        "recovery stake geometry/content"
      )
      require(coin(original.snapshots.fees), "recovery snapshot fees")
      Vector(original.snapshots.mark, original.snapshots.set, original.snapshots.go).foreach {
        snap =>
          require(
            snap.active.size <= 4096 && snap.pools.size <= 4096 && coin(snap.total) &&
              snap.total == snap.active.values.map(_.coin).sum.max(BigInt(1)),
            "recovery snapshot content"
          )
      }
      new State(
        newOwner,
        original.context,
        original.id,
        original.ledgerId,
        original.revision,
        original.slot,
        original.epoch,
        original.utxo,
        original.instantaneous,
        original.snapshots
      )
    }

  private[ledger] def recoveryOwnerMatches(owner: Owner, state: State): Boolean =
    owner != null && state != null && (owner eq state.owner)

  private[ledger] def reownRotationForRecovery(
      newOwner: Owner,
      original: Rotation,
      selectedBefore: State
  ): Either[String, Rotation] = protect {
    require(
      newOwner != null && original != null && selectedBefore != null &&
        (newOwner eq selectedBefore.owner) && original.beforeId == selectedBefore.id &&
        original.beforeRevision == selectedBefore.revision &&
        (original.leadership eq selectedBefore.snapshots.mark) &&
        original.headerSlot > selectedBefore.slot &&
        original.headerSlot / selectedBefore.context.epochLength == selectedBefore.epoch + 1,
      "recovery rotation source/before"
    )
    new Rotation(
      newOwner,
      original.beforeId,
      original.beforeRevision,
      original.headerHash,
      original.headerSlot,
      original.snapshots,
      original.leadership
    )
  }

  private[ledger] def snapshotIdentity(s: Snapshot): Bytes =
    hash(Profile + ":snapshot:" + snapshotText(s) + ":" + s.total)

  /** Only the boundary preview may supply post-reward balances. No altered State escapes. */
  private[ledger] def previewRotationAfterRewards(
      owner: Owner,
      state: State,
      headerHash: Bytes,
      slot: BigInt,
      fees: BigInt,
      balances: Map[Credential, BigInt],
      application: Context
  ): Either[String, Rotation] = protect {
    require(
      owner != null && state != null && (owner eq state.owner) && balances != null &&
        application != null && application.epochLength == state.context.epochLength &&
        balances.keySet == application.accounts.keySet && balances.values.forall(coin),
      "post-reward application account domain/bounds"
    )
    val accounts = application.accounts.map((c, a) => c -> a.copy(balance = balances(c)))
    val post =
      get(context(application.id, application.epochLength, accounts, application.pools))
    val unpublished = new State(
      owner,
      post,
      state.id,
      state.ledgerId,
      state.revision,
      state.slot,
      state.epoch,
      state.utxo,
      state.instantaneous,
      state.snapshots
    )
    get(previewRotation(owner, unpublished, headerHash, slot, fees))
  }

  /** SNAP algebra only, not NEWEPOCH: reward/governance/pool effects have not been applied. */
  def previewRotation(
      owner: Owner,
      state: State,
      headerHash: Bytes,
      slot: BigInt,
      fees: BigInt
  ): Either[String, Rotation] = protect {
    require(
      owner != null && state != null && (owner eq state.owner) && size(headerHash, 32) && coin(
        slot
      ) && coin(fees) &&
        slot > state.slot && slot / state.context.epochLength == state.epoch + 1,
      "rotation owner/slot/profile mismatch"
    )
    val fresh = get(snapshot(state.context, state.instantaneous))
    new Rotation(
      owner,
      state.id,
      state.revision,
      headerHash,
      slot,
      Snapshots(fresh, state.snapshots.mark, state.snapshots.set, fees),
      state.snapshots.mark
    )
  }
