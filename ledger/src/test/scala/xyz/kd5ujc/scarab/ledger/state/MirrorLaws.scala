// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.arrow.FunctionK
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Prop}
import org.scalacheck.Prop.{forAll, propBoolean}
import org.typelevel.discipline.Laws
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Ptr
import xyz.kd5ujc.scarab.ledger as node

/** A change to a mirror that must be refused: it breaks the piece `field` that `from` derives from
  * the rest, and `None` when the mirror has nothing to break it with (no pool, no account, ...).
  */
final case class Tamper[E <: EraTag](
    field: Derived,
    name: String,
    apply: LedgerImage[E] => Option[LedgerImage[E]]
)

/** The laws of [[LedgerMirror]] for the era `E`, over any effect `F` that has the capabilities
  * (`run` interprets it, with the environment given).
  *
  *   - retraction: `from(to(s))` is `s`, up to [[LedgerMirror.normalised]];
  *   - the mirror is stable: `to(from(to(s)))` is `to(s)`;
  *   - the gaps are exactly the ones the mirror itself shows (and no other);
  *   - a mirror whose derived piece was tampered with is refused, naming the piece.
  */
final class MirrorLaws[F[_], E <: EraTag](
    mirror: LedgerMirror[F],
    run: FunctionK[F, [A] =>> Either[MirrorError, A]],
    tampers: List[Tamper[E]]
)(using ShelleyBasedEra[E])
    extends Laws:

  private def to(at: WithOrigin[SlotNo], s: node.LedgerState) = run(mirror.to[E](at, s))
  private def from(at: WithOrigin[SlotNo], i: LedgerImage[E]) = run(mirror.from[E](at, i))

  /** The gaps the mirror shows: counted from the mirror's values, not from the state's. */
  private def shown(i: LedgerImage[E]): Set[Gap] =
    val snapshots = i.nes.es.snapshots
    def lowerBound(snap: SnapShot): Boolean =
      val counts =
        snap.activeStake.entries.valuesIterator.toVector.groupMapReduce(_.delegation)(_ => 1)(_ + _)
      snap.pools.exists((id, p) => p.numDelegators.toBigInt > BigInt(counts.getOrElse(id, 0)))
    val idle = i.extras.poolDistribution.fold(false)(_.idlePools.nonEmpty)
    Set(Gap.NonMyopic) ++
      Option.when(i.extras.rewardAmounts.nonEmpty)(Gap.RewardAttribution) ++
      Option.when(i.nes.ru.isEmpty && i.extras.rewardDraft.isDefined)(Gap.PendingPulsingUpdate) ++
      Option.when(
        idle || List(snapshots.set, snapshots.go).exists(lowerBound)
      )(Gap.NumDelegatorsLowerBound)

  def laws(using
      states: Arbitrary[node.LedgerState],
      tips: Arbitrary[WithOrigin[SlotNo]]
  ): RuleSet =
    new SimpleRuleSet(
      "ledger mirror",
      "retraction" -> forAll { (at: WithOrigin[SlotNo], s: node.LedgerState) =>
        val back = to(at, s).flatMap(m => from(at, m.value))
        (back == Right(LedgerMirror.normalised(s))) :| s"$back"
      },
      "the mirror of the retraction is the mirror" -> forAll {
        (at: WithOrigin[SlotNo], s: node.LedgerState) =>
          val again = for
            m <- to(at, s)
            back <- from(at, m.value)
            n <- to(at, back)
          yield (m, n)
          again.exists((m, n) => m == n) :| s"$again"
      },
      "the gaps are the ones the mirror shows" -> forAll {
        (at: WithOrigin[SlotNo], s: node.LedgerState) =>
          val result = to(at, s)
          result.exists(m => m.gaps == shown(m.value)) :| s"$result"
      },
      "a mirror with a derived piece changed is refused" -> forAll {
        (at: WithOrigin[SlotNo], s: node.LedgerState) =>
          to(at, s).fold(
            e => Prop.falsified :| s"$e",
            m =>
              tampers.foldLeft(Prop.passed) { (prop, t) =>
                t.apply(m.value).fold(prop) { bad =>
                  val verdict = from(at, bad)
                  prop && (verdict == Left(MirrorError.DerivedMismatch(t.field))) :|
                    s"${t.name}: $verdict"
                }
              }
          )
      }
    )

object MirrorLaws:
  private def fakeStaking: Credential[Staking] =
    KeyHash
      .fromBytes[Staking](Bytes.fromArray(Array.fill(28)(0x5a.toByte)))
      .fold(e => sys.error(e.show), Credential.KeyHashObj[Staking](_))

  private def pStateOf[E <: EraTag](using ShelleyBasedEra[E]) =
    NewEpochState.ledger[E].andThen(LedgerState.pState[E])

  private def one[E <: EraTag](
      f: LedgerImage[E] => Option[NewEpochState[E]]
  ): LedgerImage[E] => Option[LedgerImage[E]] =
    i => f(i).map(n => i.copy(nes = n))

  private def withFuture[E <: EraTag](g: GovState[E], f: FuturePParams)(using
      era: ShelleyBasedEra[E]
  ): GovState[E] = era match
    case ShelleyBasedEra.SConway => g.copy(futurePParams = f)
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      g.copy(futurePParams = f)

  private def futureOf[E <: EraTag](g: GovState[E])(using era: ShelleyBasedEra[E]): FuturePParams =
    era match
      case ShelleyBasedEra.SConway => g.futurePParams
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        g.futurePParams

  private def withInstant[E <: EraTag](i: InstantStake[E])(using
      era: ShelleyBasedEra[E]
  ): InstantStake[E] = era match
    case ShelleyBasedEra.SConway =>
      i.copy(credentialStake = i.credentialStake.updated(fakeStaking, CompactCoin.fromUnsigned(7L)))
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      i.copy(credentialStake = i.credentialStake.updated(fakeStaking, CompactCoin.fromUnsigned(7L)))

  /** Another pointer for the first account, before Conway (a Conway account has none). */
  private def withPointer[E <: EraTag](c: CertState[E])(using
      era: ShelleyBasedEra[E]
  ): Option[CertState[E]] = era match
    case ShelleyBasedEra.SConway => None
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      c.dState.accounts.states.headOption.map { (credential, account) =>
        val moved = account.copy(ptr = Ptr(account.ptr.slot + 1, 0, 0))
        c.copy(dState =
          c.dState.copy(accounts =
            c.dState.accounts.copy(states = c.dState.accounts.states.updated(credential, moved))
          )
        )
      }

  /** The changes of each derived piece, for any era. */
  def tampers[E <: EraTag](using era: ShelleyBasedEra[E]): List[Tamper[E]] =
    val ls = NewEpochState.ledger[E]
    val gov = ls.andThen(LedgerState.utxo[E]).andThen(UTxOState.gov[E])
    val instant = ls.andThen(LedgerState.utxo[E]).andThen(UTxOState.instantStake[E])
    val snapshots = NewEpochState.es[E].andThen(EpochState.snapshots[E])
    List(
      Tamper[E](
        Derived.VrfKeyHashes,
        "a VRF key hash nobody registered",
        one[E](i =>
          Some(
            pStateOf[E].modify(p =>
              p.copy(vrfKeyHashes =
                p.vrfKeyHashes.updated(
                  VRFVerKeyHash
                    .fromBytes(Bytes.fromArray(Array.fill(32)(0x77.toByte)))
                    .toOption
                    .get,
                  NonZero.oneWord
                )
              )
            )(i.nes)
          )
        )
      ),
      Tamper[E](
        Derived.PoolDelegators,
        "a delegator that is not delegated",
        one[E](i =>
          Option.when(pStateOf[E].get(i.nes).stakePools.nonEmpty)(
            pStateOf[E].modify(p =>
              val (id, pool) = p.stakePools.head
              p.copy(stakePools =
                p.stakePools.updated(id, pool.copy(delegators = pool.delegators + fakeStaking))
              )
            )(i.nes)
          )
        )
      ),
      Tamper[E](
        Derived.AccountPointer,
        "a pointer that is not the account's registration",
        one[E](i =>
          withPointer[E](i.nes.es.ls.cert).map(c =>
            ls.andThen(LedgerState.cert[E]).replace(c)(i.nes)
          )
        )
      ),
      Tamper[E](
        Derived.InstantStake,
        "stake in the UTxO that is not there",
        one[E](i => Some(instant.modify(withInstant[E](_))(i.nes)))
      ),
      Tamper[E](
        Derived.FuturePParams,
        "a future update that is not coming",
        one[E](i =>
          val now = futureOf[E](gov.get(i.nes))
          val other =
            if now == FuturePParams.NoPParamsUpdate then FuturePParams.PotentialPParamsUpdate(None)
            else FuturePParams.NoPParamsUpdate
          Some(gov.modify(withFuture[E](_, other))(i.nes))
        )
      ),
      Tamper[E](
        Derived.PoolDistr,
        "a pool distribution that is not the set snapshot's",
        one[E](i =>
          Some(
            NewEpochState
              .pd[E]
              .modify(d =>
                d.copy(totalActiveStake =
                  Coin
                    .fromBigInt(d.totalActiveStake.value.value + 1)
                    .flatMap(NonZero.from(_))
                    .toOption
                    .get
                )
              )(
                i.nes
              )
          )
        )
      ),
      Tamper[E](
        Derived.Snapshot(Derived.Which.Mark),
        "a stake of a pool of the mark snapshot",
        one[E](i =>
          Option.when(i.nes.es.snapshots.mark.pools.nonEmpty)(
            snapshots
              .andThen(SnapShots.mark)
              .modify(m =>
                val (id, p) = m.pools.head
                m.copy(pools =
                  m.pools
                    .updated(id, p.copy(stake = CompactCoin.fromUnsigned(p.stake.word.bits + 1)))
                )
              )(i.nes)
          )
        )
      )
    ) ++ List(
      Tamper[E](
        Derived.Extras,
        "residue of a pool that no snapshot has",
        i =>
          Some(
            i.copy(extras =
              i.extras.copy(snapshots =
                i.extras.snapshots.copy(mark =
                  i.extras.snapshots.mark.updated(
                    Bytes.fromArray(Array.fill(28)(0x11.toByte)),
                    PoolResidue(Set.empty, Bytes.empty, None)
                  )
                )
              )
            )
          )
      )
    )
