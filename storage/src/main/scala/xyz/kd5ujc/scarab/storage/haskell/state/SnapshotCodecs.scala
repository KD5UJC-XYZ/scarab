// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Arr, Codec, ConstraintViolation, Decoded, Node, Writer, at}
import xyz.kd5ujc.scarab.protocol.StateLayout

import Wire.*

/** `StakePoolSnapShot` without the voting key: the ten fields of the 11.1 layout, in their order.
  */
private[state] final case class StakePoolSnapShot11(
    stake: CompactCoin,
    stakeRatio: Rational,
    selfDelegatedOwners: SortedSet[KeyHash[Staking]],
    selfDelegatedOwnersStake: Coin,
    vrf: VRFVerKeyHash,
    pledge: Coin,
    cost: Coin,
    margin: Rational,
    numDelegators: Word64,
    accountId: Credential[Staking]
)

/** `SnapShots` without the Leios fields: the four of the 11.1 layout. */
private[state] final case class SnapShots11(mark: SnapShot, set: SnapShot, go: SnapShot, fee: Coin)

/** The stake snapshots and the pool distribution (`State/SnapShots.hs`, `State/Stake.hs` and
  * `State/PoolDistr.hs` of `cardano-ledger-core`, the pinned shape of 11.1.3).
  */
trait SnapshotCodecs extends GovStateCodecs:
  /** `StakeWithDelegation` (`Stake.hs:116-117`): `[stake, pool]`, the stake not zero. */
  given stakeWithDelegation: Codec[StakeWithDelegation] = Record.auto[StakeWithDelegation]

  /** `ActiveStake` (`Stake.hs:146-148`, a newtype over the map). */
  given activeStake(using ProtVer): Codec[ActiveStake] =
    mapOf(credential[Staking], stakeWithDelegation).imap(ActiveStake(_))(_.entries)

  /** The `Int` count of delegators (`SnapShots.hs:175`): a non-negative number below 2^63, as the
    * mirror keeps it in a word.
    */
  private val delegatorCount: Codec[Word64] =
    Codec.uintBig(BigInt(Long.MaxValue)).imap(n => Word64.fromUnsigned(n.toLong))(w => w.toBigInt)

  /** `StakePoolSnapShot` (`SnapShots.hs:239-252`): ten fields, the ratio a plain `Rational`. The
    * Leios layout writes eleven, the voting key after the VRF key hash (cardano-ledger 9df9f98c
    * `State/SnapShots.hs:280-315`).
    */
  given stakePoolSnapShot(using ProtVer): Codec[StakePoolSnapShot] = stateLayout match
    case StateLayout.Node11_1 =>
      val record = Record.of[StakePoolSnapShot11](
        (
          compactCoin,
          ratio,
          setOf(keyHash[Staking]),
          coin,
          vrfVerKeyHash,
          coin,
          coin,
          unitInterval,
          delegatorCount,
          credential[Staking]
        )
      )
      new Codec[StakePoolSnapShot]:
        def decode(n: Node): Decoded[StakePoolSnapShot] = record.decode(n).map { p =>
          StakePoolSnapShot(
            p.stake,
            p.stakeRatio,
            p.selfDelegatedOwners,
            p.selfDelegatedOwnersStake,
            p.vrf,
            PoolBlsKey.Node11_1,
            p.pledge,
            p.cost,
            p.margin,
            p.numDelegators,
            p.accountId
          )
        }
        private def view(p: StakePoolSnapShot): StakePoolSnapShot11 =
          StakePoolSnapShot11(
            p.stake,
            p.stakeRatio,
            p.selfDelegatedOwners,
            p.selfDelegatedOwnersStake,
            p.vrf,
            p.pledge,
            p.cost,
            p.margin,
            p.numDelegators,
            p.accountId
          )
        def encode(a: StakePoolSnapShot, out: Writer): Unit = record.encode(view(a), out)
        override def check(a: StakePoolSnapShot): Option[ConstraintViolation] =
          ofThisLayout("StakePoolSnapShot", a.blsKey)
            .map(_.at("blsKey"))
            .orElse(record.check(view(a)))
    case StateLayout.Leios =>
      Record.of[StakePoolSnapShot](
        (
          compactCoin,
          ratio,
          setOf(keyHash[Staking]),
          coin,
          vrfVerKeyHash,
          leiosPoolBlsKey,
          coin,
          coin,
          unitInterval,
          delegatorCount,
          credential[Staking]
        )
      )

  /** `SnapShot` (`SnapShots.hs:290-296`): `[active stake, pools]`. The older three-field shape
    * (`SnapShots.hs:310-325`) is not read.
    */
  given snapShot(using ProtVer): Codec[SnapShot] =
    Record.of[SnapShot]((activeStake, mapOf(keyHash[StakePool], stakePoolSnapShot)))

  /** `LeiosSeat` (cardano-ledger 9df9f98c `Binary/Encoding/EncCBOR.hs:487-491`,
    * `Binary/Decoding/DecCBOR.hs:739-740`): `[weight, verification key]`, the weight a `Rational`,
    * the key a `StrictMaybe`.
    */
  given leiosSeat(using ProtVer): Codec[LeiosSeat] =
    Record.of[LeiosSeat]((ratio, strictMaybe(blsVerificationKey)))

  /** `LeiosCommittee` (9df9f98c `EncCBOR.hs:493-496`, `DecCBOR.hs:742-745`): the list of the seats,
    * in order (`encodeList`: definite up to 23, indefinite past; either read).
    */
  given leiosCommittee(using ProtVer): Codec[LeiosCommittee] =
    seqOf(leiosSeat).imap(LeiosCommittee(_))(_.seats)

  /** `SnapShots` (`SnapShots.hs:355-362`): `[mark, set, go, fee]`; the pool distribution of the
    * mark snapshot is not written, the mirror derives it. The Leios layout wraps the snapshots
    * (cardano-ledger 9df9f98c `State/SnapShots.hs:414-432, 485-505, 525-536, 576-594`): mark as
    * `[snapshot, epoch, committee size, maximum key age]`, set as `[snapshot, committee]`, go as
    * `[snapshot]`; the fee is fourth in both.
    */
  given snapShots(using ProtVer): Codec[SnapShots] = stateLayout match
    case StateLayout.Node11_1 =>
      val record = Record.auto[SnapShots11]
      new Codec[SnapShots]:
        def decode(n: Node): Decoded[SnapShots] =
          record
            .decode(n)
            .map(s => SnapShots(s.mark, s.set, s.go, s.fee, SnapShots.Layout.Node11_1))
        private def view(s: SnapShots): SnapShots11 = SnapShots11(s.mark, s.set, s.go, s.fee)
        def encode(a: SnapShots, out: Writer): Unit = record.encode(view(a), out)
        override def check(a: SnapShots): Option[ConstraintViolation] =
          ofThisLayout("SnapShots", a.layout).map(_.at("layout")).orElse(record.check(view(a)))
    case StateLayout.Leios => leiosSnapShots

  private def leiosSnapShots(using ProtVer): Codec[SnapShots] = new Codec[SnapShots]:
    private val snap = snapShot
    private val committee = leiosCommittee

    def decode(n: Node): Decoded[SnapShots] =
      Arr.exact(n, 4).flatMap { xs =>
        for
          mark <- Arr
            .exact(xs(0), 4)
            .flatMap(ms =>
              for
                s <- snap.decode(ms(0)).at("snapShot")
                e <- epochNo.decode(ms(1)).at("epochNo")
                size <- word16.decode(ms(2)).at("leiosCommitteeSize")
                age <- word32.decode(ms(3)).at("leiosMaxKeyAge")
              yield (s, e, size, age)
            )
            .at("mark")
          set <- Arr
            .exact(xs(1), 2)
            .flatMap(ss =>
              for
                s <- snap.decode(ss(0)).at("snapShot")
                c <- committee.decode(ss(1)).at("leiosCommittee")
              yield (s, c)
            )
            .at("set")
          go <- Arr.exact(xs(2), 1).flatMap(gs => snap.decode(gs(0)).at("snapShot")).at("go")
          fee <- coin.decode(xs(3)).at("fee")
        yield SnapShots(
          mark._1,
          set._1,
          go,
          fee,
          SnapShots.Layout.Leios(mark._2, mark._3, mark._4, set._2)
        )
      }

    def encode(a: SnapShots, out: Writer): Unit = a.layout match
      case SnapShots.Layout.Leios(epoch, size, age, setCommittee) =>
        out.arrayHead(4)
        out.arrayHead(4)
        snap.encode(a.mark, out)
        epochNo.encode(epoch, out)
        word16.encode(size, out)
        word32.encode(age, out)
        out.arrayHead(2)
        snap.encode(a.set, out)
        committee.encode(setCommittee, out)
        out.arrayHead(1)
        snap.encode(a.go, out)
        coin.encode(a.fee, out)
      case SnapShots.Layout.Node11_1 => () // refused by `check`

    override def check(a: SnapShots): Option[ConstraintViolation] = a.layout match
      case SnapShots.Layout.Leios(epoch, size, age, setCommittee) =>
        snap
          .check(a.mark)
          .map(_.at("mark"))
          .orElse(epochNo.check(epoch).map(_.at("mark")))
          .orElse(word16.check(size).map(_.at("leiosCommitteeSize")))
          .orElse(word32.check(age).map(_.at("leiosMaxKeyAge")))
          .orElse(snap.check(a.set).map(_.at("set")))
          .orElse(committee.check(setCommittee).map(_.at("leiosCommittee")))
          .orElse(snap.check(a.go).map(_.at("go")))
          .orElse(coin.check(a.fee).map(_.at("fee")))
      case SnapShots.Layout.Node11_1 => ofThisLayout("SnapShots", a.layout).map(_.at("layout"))

  /** `IndividualPoolStake` (`PoolDistr.hs:70-77`): `[ratio of the total, stake, VRF key hash]`. */
  given individualPoolStake(using ProtVer): Codec[IndividualPoolStake] =
    Record.of[IndividualPoolStake]((ratio, compactCoin, vrfVerKeyHash))

  /** `PoolDistr` (`PoolDistr.hs:117-122`): `[distribution, total active stake]`. */
  given poolDistr(using ProtVer): Codec[PoolDistr] =
    Record.of[PoolDistr](
      (mapOf(keyHash[StakePool], individualPoolStake), nonZero[Coin])
    )
