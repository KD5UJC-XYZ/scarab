// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.PoolParams
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.node.read.{EraRead, EraViews}
import xyz.kd5ujc.scarab.storage.haskell.state.{LeiosStateCodecs, Record, Versions, Wire}

/** `QueryResultStakePoolState` (cardano-ledger 9df9f98c `Api/State/Query.hs:551-566`): what
  * `GetStakePoolState` reports of one registered pool.
  */
private[ntc] final case class StakePoolStateResult(
    vrf: VRFVerKeyHash,
    blsKey: Option[BlsKeyState],
    pledge: Coin,
    cost: Coin,
    margin: Rational,
    accountId: Credential[Staking],
    owners: SortedSet[KeyHash[Staking]],
    relays: Bytes,
    metadata: Option[Bytes],
    deposit: Coin,
    numDelegators: BigInt,
    delegators: Option[SortedSet[Credential[Staking]]],
    retiring: Option[EpochNo],
    future: Option[PoolParams]
)

/** `QueryResultLeiosCommittee` (cardano-ledger 9df9f98c `Api/State/Query.hs:989-997`): the Leios
  * voting committees of the current epoch and of the next.
  */
private[ntc] final case class LeiosCommitteeResult(
    current: LeiosCommittee,
    future: Option[LeiosCommittee]
)

/** The answers of the two queries the Leios build adds, `GetStakePoolState` (40) and
  * `GetLeiosCommittee` (41) ([[QueryTable.Leios]]). The reference answers both in every
  * Shelley-based era (`answerPureBlockQuery`, `Shelley/Ledger/Query.hs:467-470`,
  * ouroboros-consensus 2416493b) and writes them at the era's lowest version (`LC.toEraCBOR @era`,
  * `:969-970`), with the ledger's encoders of the Leios build's state (cardano-ledger 9df9f98c), so
  * with [[LeiosStateCodecs]]. Only a session of the Leios build reads them, so the state is of that
  * build's layout; a part of another layout is refused by the codecs' checks.
  */
object LeiosQueryAnswers:
  /** `queryStakePoolState` (`Api/State/Query.hs:630-660`, cardano-ledger 9df9f98c): the registered
    * pools, or those of `pools` among them; each with its parameters, voting key and deposit from
    * its state, the number of its delegators, the delegators themselves only when asked for, its
    * retirement and its future parameters.
    */
  def stakePoolState[E <: EraTag](
      l: EraRead[E],
      pools: Option[Set[Bytes]],
      delegators: PoolDelegators
  ): Either[Unanswered, Bytes] =
    given ProtVer = Versions.eraLow(l.era)
    l.cert.leftMap(Unanswered.Refused(_)).flatMap { cert =>
      val p = EraViews.pState(cert)(using l.era)
      val asked = pools.fold(p.stakePools)(keep => p.stakePools.filter((id, _) => keep(id.bytes)))
      asked.toVector
        .traverse((id, s) =>
          p.future
            .get(id)
            .traverse(CertStateAnswers.futureParams)
            .flatMap(result(s, p.retiring.get(id), _, delegators))
            .map(id -> _)
        )
        .flatMap(rs => stakePoolStates.write(Sorted.map(rs)).leftMap(_ => Unanswered.Unwritable))
    }

  /** `queryLeiosCommittee` (`Api/State/Query.hs:1019-1026`, cardano-ledger 9df9f98c): the committee
    * of the set snapshot, which votes this epoch, and the one the mark snapshot selects for the
    * next ([[LeiosCommitteeSelection.ofMark]]; `mkMarkSnapShot`, `State/SnapShots.hs:444-467`).
    */
  def leiosCommittee[E <: EraTag](l: EraRead[E]): Either[Unanswered, Bytes] =
    given ProtVer = Versions.eraLow(l.era)
    l.snapshots.leftMap(Unanswered.Refused(_)).flatMap { s =>
      s.layout match
        case leios @ SnapShots.Layout.Leios(_, _, _, current) =>
          LeiosCommitteeSelection
            .ofMark(s.mark, leios)
            .leftMap(Unanswered.UnselectableCommittee(_))
            .flatMap(next =>
              committees
                .write(LeiosCommitteeResult(current, Some(next)))
                .leftMap(_ => Unanswered.Unwritable)
            )
        case SnapShots.Layout.Node11_1 => Left(Unanswered.Unsupported)
    }

  /** `toQueryResultStakePoolState` (`Api/State/Query.hs:642-660`, cardano-ledger 9df9f98c): the
    * voting key of the Leios layout's state; a pool's state of the 11.1 layout has none to report.
    */
  private def result(
      s: StakePoolState,
      retiring: Option[EpochNo],
      future: Option[PoolParams],
      delegators: PoolDelegators
  ): Either[Unanswered, StakePoolStateResult] =
    s.blsKey match
      case PoolBlsKey.Node11_1 => Left(Unanswered.Unsupported)
      case PoolBlsKey.Leios(key) =>
        Right(
          StakePoolStateResult(
            s.vrf,
            key,
            s.pledge,
            s.cost,
            s.margin,
            s.accountId,
            s.owners,
            s.relays,
            s.metadata,
            s.deposit.toCoin,
            BigInt(s.delegators.size),
            delegators match
              case PoolDelegators.Included => Some(s.delegators)
              case PoolDelegators.Omitted  => None
            ,
            retiring,
            future
          )
        )

  /** The map of the pools' results (`Map (KeyHash StakePool) (QueryResultStakePoolState era)`),
    * each result as its `EncCBOR` writes it (`Api/State/Query.hs:570-587`, cardano-ledger
    * 9df9f98c): fourteen items, the optional ones with `encodeNullStrictMaybe`, `null` for none.
    */
  private def stakePoolStates(using
      ProtVer
  ): Codec[SortedMap[KeyHash[StakePool], StakePoolStateResult]] =
    import LeiosStateCodecs.given
    import LeiosStateCodecs.{poolMetadata, relays}
    Wire.mapOf(
      summon[Codec[KeyHash[StakePool]]],
      Record.of[StakePoolStateResult](
        (
          summon[Codec[VRFVerKeyHash]],
          Wire.nullable(summon[Codec[BlsKeyState]]),
          summon[Codec[Coin]],
          summon[Codec[Coin]],
          Wire.unitIntervalAt,
          summon[Codec[Credential[Staking]]],
          Wire.setOf(summon[Codec[KeyHash[Staking]]]),
          relays,
          Wire.nullable(poolMetadata),
          summon[Codec[Coin]],
          Wire.word(63),
          Wire.nullable(Wire.setOf(summon[Codec[Credential[Staking]]])),
          Wire.nullable(summon[Codec[EpochNo]]),
          Wire.nullable(summon[Codec[PoolParams]])
        )
      )
    )

  /** `EncCBOR QueryResultLeiosCommittee` (`Api/State/Query.hs:1008-1012`, cardano-ledger 9df9f98c):
    * the two committees, the next one as a `StrictMaybe` with its own `encCBOR`, a list of none or
    * one.
    */
  private def committees(using ProtVer): Codec[LeiosCommitteeResult] =
    import LeiosStateCodecs.given
    Record.of[LeiosCommitteeResult](
      (summon[Codec[LeiosCommittee]], Wire.strictMaybe(summon[Codec[LeiosCommittee]]))
    )
