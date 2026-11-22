// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Monoid, Order, Show}
import cats.derived.*
import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  ByteStrings,
  DecoderVersion,
  LedgerDecodeError,
  PoolParams,
  Semantic
}
import xyz.kd5ujc.scarab.protocol.StateLayout

// A pool's registration parameters as the ledger state holds them: `State/StakePool.hs` and
// `Address.hs` of cardano-ledger-core, era-independent as the reference keeps them in its core
// library.

/** `newtype AccountId = AccountId (Credential Staking)` (`libs/cardano-ledger-core/src/Cardano/
  * Ledger/Address.hs:189-191`, cardano-ledger f649f975).
  */
final case class AccountId(credential: Credential[Staking]) derives Eq, Show

/** `data AccountAddress` (`Address.hs:183-187`, cardano-ledger f649f975): an account, a reward
  * address, on its network.
  */
final case class AccountAddress(networkId: Network, id: AccountId) derives Eq, Show

object AccountAddress:
  /** The derived `Ord AccountAddress` (`Address.hs:183-187`, cardano-ledger f649f975): the network,
    * `Testnet` before `Mainnet` (`BaseTypes.hs:878-881`), then the account's credential
    * (`Credential.hs:98-101`). One instance, so that every map of accounts sees the same order.
    */
  private object AccountAddressOrder extends SortOrder[AccountAddress]:
    private val credentials = Order[Credential[Staking]]
    def compare(x: AccountAddress, y: AccountAddress): Int =
      val networks = Integer.compare(x.networkId.ordinal, y.networkId.ordinal)
      if networks != 0 then networks else credentials.compare(x.id.credential, y.id.credential)

  given order: Order[AccountAddress] = AccountAddressOrder
  given ordering: Ordering[AccountAddress] = AccountAddressOrder.ordering

/** `newtype Withdrawals = Withdrawals {unWithdrawals :: Map AccountAddress Coin}`
  * (`libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:980-982`, cardano-ledger f649f975): the
  * amounts a body withdraws from its accounts, by account, in the order of `Ord AccountAddress`,
  * which a `Map` gives its keys.
  */
opaque type Withdrawals = SortedMap[AccountAddress, Coin]

object Withdrawals:
  /** `Withdrawals`, the accounts in their order. */
  def apply(withdrawals: Map[AccountAddress, Coin]): Withdrawals =
    SortedMap.from(withdrawals)(using AccountAddress.ordering)

  /** `mempty` (`instance Monoid Withdrawals`, `Address.hs:987-988`, cardano-ledger f649f975). */
  val empty: Withdrawals = SortedMap.empty(using AccountAddress.ordering)

  extension (w: Withdrawals) def unWithdrawals: SortedMap[AccountAddress, Coin] = w

  /** `instance Semigroup Withdrawals` (`Address.hs:984-985`, cardano-ledger f649f975): the amounts
    * of an account summed (`Map.unionWith (<>)`), and `mempty` the empty map.
    */
  given Monoid[Withdrawals] with
    def empty: Withdrawals = Withdrawals.empty
    def combine(x: Withdrawals, y: Withdrawals): Withdrawals =
      y.foldLeft(x)((m, entry) =>
        m.updatedWith(entry._1)(v => Some(v.fold(entry._2)(w => Coin(w.value + entry._2.value))))
      )

  given Eq[Withdrawals] = Eq.fromUniversalEquals
  given Show[Withdrawals] = Show.show(w =>
    w.toVector.map((a, c) => s"${a.show} -> ${c.show}").mkString("Withdrawals(", ", ", ")")
  )
  given CanEqual[Withdrawals, Withdrawals] = CanEqual.derived

/** `data PoolMetadata` (`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:293-297`,
  * cardano-ledger f649f975): a URL and the hash of the metadata it names. Scarab keeps the term as
  * the bytes it was read from, as the state's codecs and `StakePoolState` keep it, and the hash
  * (`pmHash`) is read with it, so that POOL reads it as the reference's field; only [[read]] and
  * [[held]] make one. Two are equal when the terms mean the same, as the derived `Eq` compares the
  * decoded fields.
  */
final class PoolMetadata private (val bytes: Bytes, val hash: Bytes):
  private lazy val meaning: Semantic = Semantic.ofBytes(bytes)

  override def equals(that: Any): Boolean = that match
    case o: PoolMetadata => meaning == o.meaning
    case _               => false
  override def hashCode(): Int = meaning.hashCode
  override def toString: String = s"PoolMetadata(${bytes.hex})"

object PoolMetadata:
  given CanEqual[PoolMetadata, PoolMetadata] = CanEqual.derived

  /** `DecCBOR PoolMetadata` at `version` in the build of `layout` (`State/StakePool.hs:522-524` at
    * f649f975, `:683-685` at 9df9f98c): a record of a `Url`, its size bounded by the version and
    * its text chunked only in the Leios build from version 12, and a byte string, the hash, chunked
    * from version 12 ([[PoolParams.metadataAt]]). How a stored record and a decoder that has the
    * term's bytes read it.
    */
  def read(
      bytes: Bytes,
      version: DecoderVersion,
      layout: StateLayout
  ): Either[LedgerDecodeError, PoolMetadata] =
    PoolParams.metadataAt(bytes, version, layout).map(new PoolMetadata(bytes, _))

  /** The metadata of a term a decoder has already read with its version's bounds, as POOL reads it
    * again for its hash ([[PoolParams.metadataHash]]: `[text, bytes]`, the text unbounded and
    * definite, the hash definite or chunked).
    */
  def held(bytes: Bytes): Either[LedgerDecodeError, PoolMetadata] =
    PoolParams.metadataHash(bytes)(using ByteStrings.Chunked).map(new PoolMetadata(bytes, _))

/** `data StakePoolParams` (`State/StakePool.hs:424-435` at cardano-ledger f649f975, `:476-488` at
  * 9df9f98c): a pool's parameters as a certificate registers them and as the pool state holds them
  * until they take effect (`psFutureStakePoolParams`).
  *
  * The Leios build's `sppBlsKey`, its third field, is [[PoolParamsBlsKey]], by the state's layout:
  * the 11.1 build has no such field, so a value of its layout cannot hold a key. Its phantom `era`
  * (`StakePoolParams era`, used only by `Era era => DecCBOR`) is not kept: the parameters are the
  * same type in every era.
  *
  * The relays are kept as the bytes they were read from (no relays at all is `Bytes.empty`), and
  * the metadata as [[PoolMetadata]], as `StakePoolState` and the state's codecs keep them. Two
  * parameters are equal when their relays mean the same, as the reference's derived `Eq` compares
  * the decoded `StrictSeq StakePoolRelay`: an empty relay list read as `80` and as `9800` is one
  * list.
  */
final case class StakePoolParams(
    id: KeyHash[StakePool],
    vrf: VRFVerKeyHash,
    blsKey: PoolParamsBlsKey,
    pledge: Coin,
    cost: Coin,
    margin: UnitInterval,
    accountAddress: AccountAddress,
    owners: SortedSet[KeyHash[Staking]],
    relays: Bytes,
    metadata: Option[PoolMetadata]
):
  private lazy val identity: Any = (
    id,
    vrf,
    blsKey,
    pledge,
    cost,
    margin.rational,
    accountAddress,
    owners,
    Semantic.ofBytes(relays),
    metadata
  )

  override def equals(that: Any): Boolean = that match
    case o: StakePoolParams => identity == o.identity
    case _                  => false
  override def hashCode(): Int = identity.hashCode

object StakePoolParams:
  given CanEqual[StakePoolParams, StakePoolParams] = CanEqual.derived
  given Eq[StakePoolParams] = Eq.fromUniversalEquals
  given Show[StakePoolParams] = Show.fromToString
