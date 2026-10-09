// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayEmptyGovernance as G, ConwayStake as S}
import scala.util.control.NonFatal

/** Test-only normalized serialization comparison, never live pulser cursor equality. Source: Conway
  * 1.23.0.0 DRepPulser.hs finishDRepPulser/computeDRepDistr; core 1.21.0.0 State/Governance.hs
  * FuturePParams; binary 1.9.1.0 encodeMaybe.
  */
private[lab] object NativeEndpointGovernance:
  val DRepPulserSHA256 = "c0e5c984d28c69ff024e9f3d950284c7fd122ff22703279b744141d9720fcf97"
  val FuturePParamsSHA256 = "e836e80dabae53177e4f4aa8e7f5fb51b3e5b3a6276d0ba98d11fadc327ebcae"
  val EncoderSHA256 = "3c6f222efddd43995afa425a18a748d35ca8f629799d5d2a362629db4bd50b47"
  val Names = Set(
    "accounts",
    "dreps",
    "committeeState",
    "dormantEpochs",
    "proposals",
    "committee",
    "constitution",
    "currentParameters",
    "previousParameters",
    "futureParameters",
    "drepPulsingState"
  )
  final class Checked private[NativeEndpointGovernance] (
      val endpointId: Bytes,
      val componentOriginals: Map[String, Bytes]
  ):
    val domainsChecked = Names ++ Set(
      "normalized-complete-snapshot",
      "empty-ratify-enact",
      "exact-four-parameter-originals"
    )
    val normalizedSerializationEqual = true
    val liveCursorEqual = false
    val authenticatedSnapshot = false
    val fullLedgerEquality = false
    val runtimeImport = false
    val rewardSeedAdmission = false
  private def get[A](v: Either[?, A]): A =
    v.fold(e => throw new IllegalArgumentException(e.toString), value => value)
  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def node(v: V) = Node(v, Bytes.empty)
  private def a(v: V*) = V.Arr(v.toVector.map(node))
  private def enc(v: V) = get(Cbor.encode(v))
  private def parse(raw: Bytes): Node =
    require(
      raw != null && raw.value != null && raw.size > 0 && raw.size <= 65536,
      "governance endpoint component bound"
    )
    get(Cbor.decode(raw, Cbor.Limits(65536, 48, 20000, 65536)))
  private def arr(n: Node, count: Int): Vector[Node] = n.value match
    case V.Arr(xs) if xs.size == count => xs
    case _ => throw new IllegalArgumentException("governance endpoint record shape")
  private def cred(c: S.Credential): V = a(V.UInt(if c.script then 1 else 0), V.ByteString(c.hash))
  private def vote(v: G.Vote): V = v match
    case G.Vote.Credential(c)      => cred(c)
    case G.Vote.AlwaysAbstain      => a(V.UInt(2))
    case G.Vote.AlwaysNoConfidence => a(V.UInt(3))
  private def mapping[K, A](m: Map[K, A])(key: K => V, value: A => V): V =
    V.Map(m.toVector.map((k, v) => node(key(k)) -> node(value(v))))
  private def set(values: Vector[V]): V = V.Tag(258, node(a(values*)))
  private def option[A](v: Option[A])(f: A => V): V = a(v.toVector.map(f)*)
  private def nullable[A](v: Option[A])(f: A => V): V = v.fold[V](V.Null)(f)
  private def anchor(v: G.Anchor): V = a(V.Text(v.url), V.ByteString(v.hash))
  private def drep(v: G.DRepState): V = a(
    V.UInt(v.expiry),
    option(v.anchor)(anchor),
    V.UInt(v.deposit),
    set(v.delegators.toVector.map(cred))
  )
  private def account(v: G.Account): V = a(
    V.UInt(v.rewards),
    V.UInt(v.deposit),
    nullable(v.pool)(V.ByteString(_)),
    nullable(v.vote)(vote)
  )
  private def ratio(r: S.Ratio): V = V.Tag(30, node(a(V.UInt(r.numerator), V.UInt(r.denominator))))
  private def committee(v: Option[G.Committee]): V =
    option(v)(c => a(mapping(c.members)(cred, V.UInt(_)), ratio(c.threshold)))
  private def constitution(v: G.Constitution): V =
    a(anchor(v.anchor), nullable(v.script)(V.ByteString(_)))
  private def roots(v: Map[G.Purpose, Option[G.ActionId]]): V =
    val order =
      Vector(G.Purpose.Parameters, G.Purpose.HardFork, G.Purpose.Committee, G.Purpose.Constitution)
    require(
      v.keySet == order.toSet && v.values.forall(_.isEmpty),
      "nonempty governance roots unsupported"
    )
    a(order.map(_ => a())*)
  private def payload(v: G.Payload): V = parse(v.original).value

  /** Structural semantic normalization rejects duplicates, including alternate key encodings. */
  private def normalized(v: V): V = v match
    case V.Map(rows) =>
      require(rows.size <= 4096, "governance map bound")
      val values = rows.map((k, value) => normalized(k.value) -> normalized(value.value))
      val keys = values.map((k, _) => enc(k).hex)
      require(keys.distinct.size == keys.size, "governance duplicate semantic map key")
      V.Map(values.sortBy((k, _) => enc(k).hex).map((k, value) => node(k) -> node(value)))
    case V.Tag(t, inner) if t == 258 =>
      val xs = inner.value match
        case V.Arr(xs) if xs.size <= 4096 => xs.map(x => normalized(x.value))
        case _ => throw new IllegalArgumentException("governance set shape/bound")
      val keys = xs.map(x => enc(x).hex)
      require(keys.distinct.size == keys.size, "governance duplicate semantic set element")
      set(xs.sortBy(x => enc(x).hex))
    case V.Arr(xs)       => V.Arr(xs.map(x => node(normalized(x.value))))
    case V.Tag(t, inner) => V.Tag(t, node(normalized(inner.value)))
    case other           => other

  def completedDistribution(fresh: G.FreshPulsing): Either[String, Map[G.Vote, BigInt]] = protect {
    require(
      fresh != null && fresh.index == 0 && fresh.drepDistribution.isEmpty &&
        fresh.proposals.isEmpty && fresh.proposalDeposits.isEmpty,
      "fresh empty-proposals index-zero governance profile required"
    )
    fresh.accounts.foldLeft(Map.empty[G.Vote, BigInt]) { case (out, (c, account)) =>
      account.vote match
        case Some(v) if (v match
              case G.Vote.Credential(d) => fresh.dreps.contains(d)
              case _                    => true
            ) =>
          val stake = fresh.instantaneous.getOrElse(c, BigInt(0)) +
            fresh.proposalDeposits.getOrElse(c, BigInt(0)) + account.rewards
          val total = out.getOrElse(v, BigInt(0)) + stake
          require(
            stake >= 0 && total <= ((BigInt(1) << 64) - 1),
            "governance distribution coin bound"
          )
          out.updated(v, total)
        case _ => out
    }
  }

  /** Generates expected values for this finite diagnostic, not native source bytes. */
  def expectedComponents(applied: G.Applied): Either[String, Map[String, Bytes]] = protect {
    require(
      applied != null && applied.before.proposals.isEmpty &&
        applied.committeeState.isEmpty,
      "empty proposals/committee-state governance profile required"
    )
    val f = applied.fresh
    val distribution = get(completedDistribution(f))
    val e = f.enact
    require(e.withdrawals.isEmpty, "governance withdrawals unsupported")
    val enact = a(
      committee(e.committee),
      constitution(e.constitution),
      payload(e.current),
      payload(e.previous),
      V.UInt(e.treasury),
      mapping(e.withdrawals)(cred, V.UInt(_)),
      roots(e.roots)
    )
    val snapshot = a(
      a(),
      mapping(distribution)(vote, V.UInt(_)),
      mapping(f.dreps)(cred, drep),
      mapping(f.stakePoolDistribution.pools)(V.ByteString(_), p => V.UInt(p.stake))
    )
    val future = applied.parameters.future match
      case G.FutureParameters.NoUpdate      => a(V.UInt(0))
      case G.FutureParameters.PotentialNone => a(V.UInt(2), a())
      case _ => throw new IllegalArgumentException("pending parameter update unsupported")
    Map(
      "accounts" -> mapping(f.accounts)(cred, account),
      "dreps" -> mapping(applied.dreps)(cred, drep),
      "committeeState" -> V.Map(Vector.empty),
      "dormantEpochs" -> V.UInt(applied.dormant),
      "proposals" -> a(roots(applied.roots), a()),
      "committee" -> committee(applied.committee),
      "constitution" -> constitution(applied.constitution),
      "currentParameters" -> payload(applied.parameters.current),
      "previousParameters" -> payload(applied.parameters.previous),
      "futureParameters" -> future,
      "drepPulsingState" -> a(snapshot, a(enact, a(), set(Vector.empty), V.Bool(false)))
    ).map((name, value) => name -> enc(value))
  }

  def compareComponents(components: Map[String, Bytes], applied: G.Applied): Either[String, Unit] =
    protect {
      require(
        components != null && Names.subsetOf(components.keySet) &&
          components.keySet.subsetOf(NativeGovernanceComponents.ComponentNames),
        "governance endpoint components missing or unknown"
      )
      require(
        components.size <= 15 && components.values.forall(b =>
          b != null && b.value != null && b.size > 0 && b.size <= 65536
        ) &&
          components.values.map(_.size.toLong).sum <= 1048576,
        "governance endpoint aggregate bound"
      )
      val expected = get(expectedComponents(applied))
      Names.toVector.sorted.foreach { name =>
        require(
          normalized(parse(components(name)).value) == normalized(parse(expected(name)).value),
          "governance endpoint " + name + " mismatch"
        )
      }
      require(
        components("currentParameters") == applied.parameters.current.original &&
          components("previousParameters") == applied.parameters.previous.original,
        "governance endpoint outer parameter original mismatch"
      )
      val complete = arr(parse(components("drepPulsingState")), 2)
      val ratify = arr(complete(1), 4)
      val enact = arr(ratify.head, 7)
      require(
        enact(2).original == applied.fresh.enact.current.original &&
          enact(3).original == applied.fresh.enact.previous.original,
        "governance endpoint enact parameter original mismatch"
      )
    }

  def compare(
      report: NativeEndpointLedger.Report,
      replay: CoherentSequence.State
  ): Either[String, Checked] = protect {
    require(report != null && replay != null, "endpoint report and replay required")
    require(report.replayStateId == replay.id, "endpoint governance replay state identity mismatch")
    val boundary = replay.syntheticBoundary.getOrElse(
      throw new IllegalArgumentException("endpoint checked boundary missing")
    )
    val applied = boundary.governanceAfter.getOrElse(
      throw new IllegalArgumentException("endpoint applied governance missing")
    )
    get(compareComponents(report.componentOriginals, applied))
    new Checked(report.endpointId, report.componentOriginals)
  }
