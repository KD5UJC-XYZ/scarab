// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayEmptyGovernance as G, ConwayStake as S}
import scala.util.control.NonFatal

/** Bounded normalized PV9 governance subset. Original-span equality is required explicitly;
  * arbitrary native re-encoding, live pulser reconstruction and ledger admission are unsupported.
  */
private[lab] object NativeGovernanceComponents:
  val MaxBytes = 1048576
  val MaxComponentBytes = 65536
  val MaxEntries = 4096
  val ComponentNames = Set(
    "certificateState",
    "votingState",
    "delegationState",
    "accounts",
    "governance",
    "proposals",
    "dormantEpochs",
    "dreps",
    "committeeState",
    "committee",
    "constitution",
    "drepPulsingState",
    "currentParameters",
    "previousParameters",
    "futureParameters"
  )
  final case class HistoricalEnact(
      committee: Option[G.Committee],
      constitution: G.Constitution,
      currentParameters: Bytes,
      previousParameters: Bytes,
      treasury: BigInt,
      withdrawals: Map[S.Credential, BigInt],
      roots: Map[G.Purpose, Option[G.ActionId]]
  )
  final case class HistoricalComplete(
      snapshot: G.CompletedSnapshot,
      enact: HistoricalEnact,
      enacted: Vector[G.ActionId],
      expired: Set[G.ActionId],
      delayed: Boolean
  )
  final class Checked private[NativeGovernanceComponents] (
      val originalSeed: Bytes,
      val originalEpoch: Bytes,
      val componentOriginals: Map[String, Bytes],
      val componentSHA256: Map[String, Bytes],
      val epoch: BigInt,
      val dormant: BigInt,
      val dreps: Map[S.Credential, G.DRepState],
      val accounts: Map[S.Credential, G.Account],
      val committee: Option[G.Committee],
      val committeeState: Map[S.Credential, G.Authorization],
      val constitution: G.Constitution,
      val roots: Map[G.Purpose, Option[G.ActionId]],
      val proposals: Map[G.ActionId, Bytes],
      val currentParameters: Bytes,
      val previousParameters: Bytes,
      val futureParameters: Bytes,
      val historical: HistoricalComplete,
      val sourceId: Bytes
  ):
    val componentDerivationChecked = true
    val voteReverseDelegationChecked = true
    val rewardSeedAdmission = false
    val runtimeImport = false
    val nativeConformance = false
    val authenticatedSnapshot = false
    val liveGovernanceCursorRecoverable = false
    val parameterSemanticsChecked = false
    val wholeSeedDerivationChecked = false

  private val Max = (BigInt(1) << 64) - 1
  private def get[A](e: Either[String, A]): A = e.fold(fail, identity)
  private def fail(s: String): Nothing = throw new IllegalArgumentException(s)
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def arr(n: Node, count: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == count => xs
    case _                             => fail("governance array shape")
  private def uint(n: Node, max: BigInt = Max): BigInt = n.value match
    case V.UInt(v) if v >= 0 && v <= max => v
    case _                               => fail("governance unsigned bound/shape")
  private def bytes(n: Node, count: Int): Bytes = n.value match
    case V.ByteString(b) if b.size == count => b
    case _                                  => fail("governance hash width/shape")
  private def credential(n: Node): S.Credential =
    val a = arr(n, 2)
    S.Credential(uint(a(0), 1) == 1, bytes(a(1), 28))
  private def entries(n: Node): Vector[(Node, Node)] = n.value match
    case V.Map(xs) if xs.size <= MaxEntries => xs
    case _                                  => fail("governance map shape/bound")
  private def mapping[K, A](n: Node)(key: Node => K, value: Node => A): Map[K, A] =
    val pairs = entries(n).map((k, v) => key(k) -> value(v))
    require(pairs.map(_._1).distinct.size == pairs.size, "governance duplicate map key")
    pairs.toMap
  private def emptyMap(n: Node): Unit =
    require(entries(n).isEmpty, "nonempty governance map unsupported")
  private def emptySeq(n: Node): Unit = { arr(n, 0); () }
  private def emptySet(n: Node): Unit = n.value match
    case V.Tag(t, inner) if t == 258 => emptySeq(inner)
    case _                           => fail("governance PV9 empty set required")
  private def delegators(n: Node): Set[S.Credential] = n.value match
    case V.Tag(t, inner) if t == 258 =>
      inner.value match
        case V.Arr(xs) if xs.size <= MaxEntries =>
          val cs = xs.map(credential)
          require(cs.distinct.size == cs.size, "duplicate DRep delegator")
          cs.toSet
        case _ => fail("DRep delegator set bound/shape")
    case _ => fail("DRep delegator PV9 set tag")
  private def drep(n: Node): G.DRepState =
    val a = arr(n, 4)
    emptySeq(a(1)) // Supported current registration has an explicitly absent anchor.
    G.DRepState(uint(a(0)), None, uint(a(2)), delegators(a(3)))
  private def vote(n: Node): G.Vote = n.value match
    case V.Arr(xs) if xs.size == 2 =>
      G.Vote.Credential(credential(n))
    case V.Arr(xs) if xs.size == 1 =>
      uint(xs.head, 3).toInt match
        case 2 => G.Vote.AlwaysAbstain
        case 3 => G.Vote.AlwaysNoConfidence
        case _ => fail("governance special vote tag")
    case _ => fail("governance vote shape")
  private def nullable[A](n: Node)(read: Node => A): Option[A] = n.value match
    case V.Null => None
    case _      => Some(read(n))
  private def account(n: Node): G.Account =
    val a = arr(n, 4)
    G.Account(uint(a(0)), uint(a(1)), nullable(a(2))(bytes(_, 28)), nullable(a(3))(vote))
  private def committee(n: Node): Option[G.Committee] =
    val a = arr(arr(n, 1).head, 2) // Present empty committee is distinct from absence.
    emptyMap(a(0))
    val ratio = a(1).value match
      case V.Tag(t, inner) if t == 30 =>
        val v = arr(inner, 2); val num = uint(v(0)); val den = uint(v(1))
        require(den > 0 && num <= den && num.gcd(den) == 1, "committee unit interval")
        S.Ratio(num, den)
      case _ => fail("committee ratio tag")
    Some(G.Committee(Map.empty, ratio))
  private def constitution(n: Node): G.Constitution =
    val a = arr(n, 2); val anchor = arr(a(0), 2)
    require(anchor(0).value == V.Text(""), "nonempty constitution URL unsupported")
    val hash = bytes(anchor(1), 32)
    require(hash.value.forall(_ == 0), "nonzero constitution hash unsupported")
    require(a(1).value == V.Null, "constitution script unsupported")
    G.Constitution(G.Anchor("", hash), None)
  private def roots(n: Node): Map[G.Purpose, Option[G.ActionId]] =
    val a = arr(n, 4); a.foreach(emptySeq)
    Vector(G.Purpose.Parameters, G.Purpose.HardFork, G.Purpose.Committee, G.Purpose.Constitution)
      .map(_ -> Option.empty[G.ActionId])
      .toMap
  private def parameter(n: Node): Bytes =
    arr(n, 31)
    require(n.original.size <= MaxComponentBytes, "historical parameter bound")
    n.original // Shape and provenance only; parameter semantics belong to the parameter decoder.
  private def parse(raw: Bytes, pin: Bytes): Node =
    require(
      raw != null && raw.value != null && raw.size > 0 && raw.size <= MaxBytes,
      "governance original byte bound"
    )
    require(
      pin != null && pin.value != null && pin.size == 32 && ClusterHeaderObservation.sha256(
        raw
      ) == pin,
      "governance original digest"
    )
    get(Cbor.decode(raw, Cbor.Limits(MaxBytes, 48, 200000, MaxBytes)))
  private def subtree(root: Node, path: Int*): Node = path.foldLeft(root) { (n, index) =>
    n.value match
      case V.Arr(xs) if index < xs.size => xs(index)
      case _                            => fail("governance source path")
  }
  private val Paths = Map(
    "certificateState" -> Vector(3, 1, 0),
    "votingState" -> Vector(3, 1, 0, 0),
    "delegationState" -> Vector(3, 1, 0, 2),
    "accounts" -> Vector(3, 1, 0, 2, 0),
    "dreps" -> Vector(3, 1, 0, 0, 0),
    "committeeState" -> Vector(3, 1, 0, 0, 1),
    "dormantEpochs" -> Vector(3, 1, 0, 0, 2),
    "governance" -> Vector(3, 1, 1, 3),
    "proposals" -> Vector(3, 1, 1, 3, 0),
    "committee" -> Vector(3, 1, 1, 3, 1),
    "constitution" -> Vector(3, 1, 1, 3, 2),
    "currentParameters" -> Vector(3, 1, 1, 3, 3),
    "previousParameters" -> Vector(3, 1, 1, 3, 4),
    "futureParameters" -> Vector(3, 1, 1, 3, 5),
    "drepPulsingState" -> Vector(3, 1, 1, 3, 6)
  )

  def decode(
      originalSeed: Bytes,
      seedSHA256: Bytes,
      originalEpoch: Bytes,
      epochSHA256: Bytes,
      components: Map[String, Bytes],
      componentSHA256: Map[String, Bytes]
  ): Either[String, Checked] = checked {
    require(
      components != null && componentSHA256 != null && components.keySet == ComponentNames && componentSHA256.keySet == ComponentNames,
      "exact governance component set required"
    )
    require(
      components.values.forall(b =>
        b != null && b.value != null && b.size > 0 && b.size <= MaxComponentBytes
      ),
      "governance component byte bound"
    )
    val seed = parse(originalSeed, seedSHA256); val original = parse(originalEpoch, epochSHA256)
    val seedFields = arr(seed, 7); val epochFields = arr(original, 7)
    val epoch = uint(seedFields(0));
    require(uint(epochFields(0)) == epoch, "governance epoch mismatch")
    for root <- Vector(seed, original) do
      arr(subtree(root, 3), 4); arr(subtree(root, 3, 1), 2)
      arr(subtree(root, 3, 1, 0), 3); arr(subtree(root, 3, 1, 0, 0), 3)
      arr(subtree(root, 3, 1, 0, 2), 4); arr(subtree(root, 3, 1, 1), 6)
      arr(subtree(root, 3, 1, 1, 3), 7)
    Paths.foreach { (name, path) =>
      val raw = components(name); val pin = componentSHA256(name)
      require(
        pin != null && pin.value != null && pin.size == 32 && ClusterHeaderObservation.sha256(
          raw
        ) == pin,
        "governance component digest: " + name
      )
      require(
        subtree(seed, path*).original == raw && subtree(original, path*).original == raw,
        "governance component original derivation: " + name
      )
    }
    val gov = arr(subtree(seed, 3, 1, 1, 3), 7)
    val voting = arr(subtree(seed, 3, 1, 0, 0), 3)
    val ds = mapping(voting(0))(credential, drep)
    val accounts = mapping(subtree(seed, 3, 1, 0, 2, 0))(credential, account)
    accounts.values.foreach(_.vote.foreach {
      case G.Vote.Credential(c) => require(ds.contains(c), "vote references unregistered DRep")
      case _                    => ()
    })
    ds.foreach { (c, d) =>
      require(
        d.delegators == accounts.collect {
          case (a, state) if state.vote.contains(G.Vote.Credential(c)) => a
        }.toSet,
        "DRep reverse delegation mismatch"
      )
    }
    emptyMap(voting(1)); val dormant = uint(voting(2))
    val proposals = arr(gov(0), 2); val currentRoots = roots(proposals(0)); emptySeq(proposals(1))
    val currentCommittee = committee(gov(1)); val currentConstitution = constitution(gov(2))
    val current = parameter(gov(3)); val previous = parameter(gov(4))
    val future = arr(gov(5), 1);
    require(uint(future.head, 0) == 0, "outer future update unsupported")
    val completed = arr(gov(6), 2)
    val snapshot = arr(completed(0), 4)
    emptySeq(snapshot(0)); snapshot.drop(1).foreach(emptyMap)
    val ratify = arr(completed(1), 4); val enact = arr(ratify(0), 7)
    val oldCommittee = committee(enact(0)); val oldConstitution = constitution(enact(1))
    val oldCurrent = parameter(enact(2)); val oldPrevious = parameter(enact(3))
    val oldTreasury = uint(enact(4)); emptyMap(enact(5)); val oldRoots = roots(enact(6))
    require(
      oldCommittee == currentCommittee && oldConstitution == currentConstitution && oldRoots == currentRoots,
      "unsupported historical governance difference"
    )
    emptySeq(ratify(1)); emptySet(ratify(2));
    require(ratify(3).value == V.Bool(false), "ratification delay unsupported")
    val history = HistoricalComplete(
      G.CompletedSnapshot(Vector.empty, Map.empty, Map.empty, Map.empty),
      HistoricalEnact(
        oldCommittee,
        oldConstitution,
        oldCurrent,
        oldPrevious,
        oldTreasury,
        Map.empty,
        oldRoots
      ),
      Vector.empty,
      Set.empty,
      false
    )
    val identityParts =
      Vector(seedSHA256, epochSHA256) ++ ComponentNames.toVector.sorted.map(componentSHA256)
    val identity = ClusterHeaderObservation.sha256(Bytes(identityParts.flatMap(_.value)))
    new Checked(
      originalSeed,
      originalEpoch,
      components,
      componentSHA256,
      epoch,
      dormant,
      ds,
      accounts,
      currentCommittee,
      Map.empty,
      currentConstitution,
      currentRoots,
      Map.empty,
      current,
      previous,
      gov(5).original,
      history,
      identity
    )
  }
