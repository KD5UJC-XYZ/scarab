// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.math.Ordering.Implicits.seqOrdering
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits

/** Orders of the values whose repeats an ordered set refuses: a certificate, a pool registration by
  * meaning ([[PoolIdentity]]) and a proposal procedure ([[ProposalIdentity]]). The reference finds
  * the repeats of an `OSet` with its elements' derived `Ord` (`decodeOSet`,
  * `libs/cardano-data/src/Data/OSet/Strict.hs:109-116`, cardano-ledger 29a7d656), so the check
  * costs a sort whatever the hash codes of the elements. Each order here compares the fields that
  * the value's equality compares, so two values compare equal exactly when they are equal; which of
  * two unequal values comes first has no meaning.
  */
private[ledger] object IdentityOrder:
  private def by[A](a: A, b: A)(using o: Ordering[A]): Int = o.compare(a, b)

  given rational: Ordering[Rational] = Ordering.by(r => (r.numerator, r.denominator))

  /** A set by its elements in ascending order: equal sets have one such sequence. */
  given set[A: Ordering]: Ordering[Set[A]] = Ordering.by(_.toVector.sorted)

  /** A map by its entries in ascending order of the key. */
  given map[K: Ordering, V: Ordering]: Ordering[Map[K, V]] =
    Ordering.by(_.toVector.sorted(using Ordering.Tuple2(using Ordering[K], Ordering[V])))

  given either[A: Ordering, B: Ordering]: Ordering[Either[A, B]] = (a, b) =>
    (a, b) match
      case (Left(x), Left(y))   => by(x, y)
      case (Right(x), Right(y)) => by(x, y)
      case (Left(_), Right(_))  => -1
      case (Right(_), Left(_))  => 1

  given anchor: Ordering[Anchor] = Ordering.by(a => (a.url, a.hash))
  given exUnits: Ordering[ExUnits] = Ordering.by(u => (u.mem, u.steps))

  given semantic: Ordering[Semantic] = new Ordering[Semantic]:
    // nested no deeper than `Semantic` reads (eight levels), so the recursion is bounded
    def compare(a: Semantic, b: Semantic): Int = (a, b) match
      case (Semantic.Num(x), Semantic.Num(y)) => x.compare(y)
      case (Semantic.Str(x), Semantic.Str(y)) => x.compareTo(y)
      case (Semantic.Txt(x), Semantic.Txt(y)) => x.compareTo(y)
      case (Semantic.Items(x), Semantic.Items(y)) =>
        seqOrdering[Vector, Semantic](using this).compare(x, y)
      case (Semantic.Entries(x, n), Semantic.Entries(y, m)) =>
        if n != m then Integer.compare(n, m)
        else set(using Ordering.Tuple2(using this, this)).compare(x, y)
      case (Semantic.Tagged(t, x), Semantic.Tagged(u, y)) =>
        val tags = t.compare(u)
        if tags != 0 then tags else compare(x, y)
      case (Semantic.Ratio(x), Semantic.Ratio(y))   => rational.compare(x, y)
      case (Semantic.Flag(x), Semantic.Flag(y))     => java.lang.Boolean.compare(x, y)
      case (Semantic.Opaque(x), Semantic.Opaque(y)) => x.compareTo(y)
      case _                                        => Integer.compare(a.ordinal, b.ordinal)

  given poolParams: Ordering[PoolParams] = Ordering.by(p =>
    (
      p.operator,
      p.vrfKeyHash,
      p.pledge,
      p.cost,
      p.margin,
      p.rewardAccount,
      p.owners,
      p.relays,
      p.metadata
    )
  )

  given poolIdentity: Ordering[PoolIdentity] =
    Ordering.by(p => (p.fixed, p.relays, p.metadata))

  given certificate: Ordering[Certificate] = (a, b) =>
    import Certificate.*
    (a, b) match
      case (Register(c, d), Register(e, f))                     => by((c, d), (e, f))
      case (Unregister(c, d), Unregister(e, f))                 => by((c, d), (e, f))
      case (Delegate(c, p, d, x), Delegate(e, q, f, y))         => by((c, p, d, x), (e, q, f, y))
      case (RegisterPool(p), RegisterPool(q))                   => by(p, q)
      case (RetirePool(p, e), RetirePool(q, f))                 => by((p, e), (q, f))
      case (GenesisDelegate(g, d, v), GenesisDelegate(h, e, w)) => by((g, d, v), (h, e, w))
      case (MoveRewards(p, t), MoveRewards(q, u)) =>
        by((p.ordinal, t), (q.ordinal, u))
      case (RegisterDRep(c, d, x), RegisterDRep(e, f, y))       => by((c, d, x), (e, f, y))
      case (UnregisterDRep(c, d), UnregisterDRep(e, f))         => by((c, d), (e, f))
      case (UpdateDRep(c, x), UpdateDRep(e, y))                 => by((c, x), (e, y))
      case (CommitteeAuthorize(c, h), CommitteeAuthorize(e, k)) => by((c, h), (e, k))
      case (CommitteeResign(c, x), CommitteeResign(e, y))       => by((c, x), (e, y))
      case _ => Integer.compare(a.ordinal, b.ordinal)

  /** A parameter by its key, then its value, read as the field's type. */
  given conwayParam: Ordering[ConwayParam] = (a, b) =>
    val keys = Integer.compare(a.key, b.key)
    if keys != 0 then keys else by(paramValue(a), paramValue(b))

  private enum ParamValue:
    case Whole(value: BigInt)
    case Ratio(value: Rational)
    case Ratios(values: Vector[Rational])
    case Units(value: ExUnits)
    case Costs(models: Map[Int, Vector[BigInt]])

  private given Ordering[ParamValue] = (a, b) =>
    (a, b) match
      case (ParamValue.Whole(x), ParamValue.Whole(y))   => x.compare(y)
      case (ParamValue.Ratio(x), ParamValue.Ratio(y))   => by(x, y)
      case (ParamValue.Ratios(x), ParamValue.Ratios(y)) => by(x, y)
      case (ParamValue.Units(x), ParamValue.Units(y))   => by(x, y)
      case (ParamValue.Costs(x), ParamValue.Costs(y))   => by(x, y)
      case _                                            => Integer.compare(a.ordinal, b.ordinal)

  private def paramValue(p: ConwayParam): ParamValue =
    import ConwayParam.*
    p match
      case TxFeePerByte(v)               => ParamValue.Whole(v.value)
      case TxFeeFixed(v)                 => ParamValue.Whole(v.value)
      case MaxBBSize(v)                  => ParamValue.Whole(v.value)
      case MaxTxSize(v)                  => ParamValue.Whole(v.value)
      case MaxBHSize(v)                  => ParamValue.Whole(v.value)
      case KeyDeposit(v)                 => ParamValue.Whole(v.value)
      case PoolDeposit(v)                => ParamValue.Whole(v.value)
      case EMax(v)                       => ParamValue.Whole(v.value)
      case NOpt(v)                       => ParamValue.Whole(v.value)
      case A0(v)                         => ParamValue.Ratio(v)
      case Rho(v)                        => ParamValue.Ratio(v)
      case Tau(v)                        => ParamValue.Ratio(v)
      case MinPoolCost(v)                => ParamValue.Whole(v.value)
      case CoinsPerUTxOByte(v)           => ParamValue.Whole(v.value)
      case CostModels(models)            => ParamValue.Costs(models)
      case Prices(memory, steps)         => ParamValue.Ratios(Vector(memory, steps))
      case MaxTxExUnits(v)               => ParamValue.Units(v)
      case MaxBlockExUnits(v)            => ParamValue.Units(v)
      case MaxValSize(v)                 => ParamValue.Whole(v.value)
      case CollateralPercentage(v)       => ParamValue.Whole(v.value)
      case MaxCollateralInputs(v)        => ParamValue.Whole(v.value)
      case PoolThresholds(v)             => ParamValue.Ratios(v.toVector)
      case DRepThresholds(v)             => ParamValue.Ratios(v.toVector)
      case CommitteeMinSize(v)           => ParamValue.Whole(v.value)
      case CommitteeMaxTermLength(v)     => ParamValue.Whole(v.value)
      case GovActionLifetime(v)          => ParamValue.Whole(v.value)
      case GovActionDeposit(v)           => ParamValue.Whole(v.value)
      case DRepDeposit(v)                => ParamValue.Whole(v.value)
      case DRepActivity(v)               => ParamValue.Whole(v.value)
      case MinFeeRefScriptCostPerByte(v) => ParamValue.Ratio(v)

  given updateEntry: Ordering[ConwayParamUpdate.Entry] = Ordering.by(e => (e.param, e.original))
  given conwayParamUpdate: Ordering[ConwayParamUpdate] = Ordering.by(_.entries.toVector)

  /** A parameter change's update ([[ParamChangeUpdate]]): Conway's before Dijkstra's, each by its
    * own order. A body's proposals, which this order compares, hold Conway's alone.
    */
  given paramChangeUpdate: Ordering[ParamChangeUpdate] = (a, b) =>
    (a, b) match
      case (x: ConwayParamUpdate, y: ConwayParamUpdate) => by(x, y)
      case (x: DijkstraKeysUpdate, y: DijkstraKeysUpdate) =>
        by(x.update, y.update)(using dijkstra.DijkstraOrder.paramUpdate)
      case (_: ConwayParamUpdate, _: DijkstraKeysUpdate) => -1
      case (_: DijkstraKeysUpdate, _: ConwayParamUpdate) => 1

  given govAction: Ordering[GovAction] = (a, b) =>
    import GovAction.*
    (a, b) match
      case (ParameterChange(p, u, s), ParameterChange(q, v, t)) =>
        val parents = by(p, q)
        if parents != 0 then parents
        else
          val updates = paramChangeUpdate.compare(u, v)
          if updates != 0 then updates else by(s, t)
      case (HardFork(p, m, n), HardFork(q, o, r))                 => by((p, m, n), (q, o, r))
      case (TreasuryWithdrawals(w, s), TreasuryWithdrawals(x, t)) => by((w, s), (x, t))
      case (NoConfidence(p), NoConfidence(q))                     => by(p, q)
      case (UpdateCommittee(p, r, d, u), UpdateCommittee(q, s, e, v)) =>
        by((p, r, d, u), (q, s, e, v))
      case (NewConstitution(p, s, x), NewConstitution(q, t, y)) => by((p, s, x), (q, t, y))
      case _ => Integer.compare(a.ordinal, b.ordinal)

  given actionIdentity: Ordering[ActionIdentity] = (a, b) =>
    (a, b) match
      case (ActionIdentity.Update(p, f, s), ActionIdentity.Update(q, g, t)) =>
        by((p, f, s), (q, g, t))
      case (ActionIdentity.Withdrawals(w, s), ActionIdentity.Withdrawals(x, t)) =>
        by((w, s), (x, t))
      case (ActionIdentity.Other(x), ActionIdentity.Other(y)) => by(x, y)
      case _ => Integer.compare(a.ordinal, b.ordinal)

  given proposalIdentity: Ordering[ProposalIdentity] =
    Ordering.by(p => (p.deposit, p.returnAccount, p.action, p.anchor))
