// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Node, Value}
import xyz.kd5ujc.scarab.ledger.{Credential, DecodeField, LedgerDecodeError, RewardAccount}

import V12.{Result, given}

/** An account balance interval of a body (`AccountBalanceInterval`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:670-675`, cardano-ledger 9df9f98c):
  * an exact balance, or an inclusive lower bound, an exclusive upper bound, or both.
  */
enum DijkstraBalanceInterval:
  case Exact(coin: BigInt)
  case Lower(inclusive: BigInt)
  case Upper(exclusive: BigInt)
  case Both(inclusive: BigInt, exclusive: BigInt)

/** The level of a body: a top-level transaction's, or a sub-transaction's. */
enum DijkstraLevel:
  case Top
  case Sub

/** The readers of a Dijkstra body's fields that Dijkstra's era decoder
  * ([[xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxBody]]) and the stored state's codecs share
  * (`DijkstraTxBodyRaw`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxBody.hs:340-479`,
  * cardano-ledger 9df9f98c).
  */
object DijkstraBodyReaders:
  /** A non-empty map of account addresses, none twice (`decodeMap`, then `failOnNull`). */
  private[ledger] def accountMap[A](n: Node, site: DecodeField)(
      value: Node => Result[A]
  ): Result[Vector[(RewardAccount, A)]] =
    V12.nonEmptyMap(n, site)(Account.decode(_, site))((_, v) => value(v))

  /** `decodePositiveCoin` (`libs/cardano-ledger-core/src/Cardano/Ledger/Coin.hs:226-232`,
    * cardano-ledger 9df9f98c): a `Word64` other than zero.
    */
  private[ledger] def positive(n: Node): Result[BigInt] =
    V12
      .word64(n, DecodeField.Donation)
      .flatMap(c => Either.cond(c != 0, c, LedgerDecodeError.Zero(DecodeField.Donation)))

  /** `Network` (`libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:899-905`, cardano-ledger
    * 9df9f98c): a `Word8` that is 0 or 1.
    */
  private[ledger] def networkId(n: Node): Result[Int] =
    V12
      .word8(n, DecodeField.NetworkId)
      .flatMap(v =>
        Either.cond(v <= 1, v, LedgerDecodeError.NumberOutOfRange(DecodeField.NetworkId, v, 0, 1))
      )

  /** `decodeGuards` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxBody.hs:1662-1678`,
    * cardano-ledger 9df9f98c): an `OSet` of credentials whose first element decides the form of
    * all: a list makes every element a credential, anything else every element a key hash. No guard
    * twice.
    */
  private[ledger] def guards(n: Node): Result[Vector[Credential]] =
    V12.setItems(n, DecodeField.Guards).flatMap { xs =>
      val credentials = xs.headOption.exists(V12.isList)
      xs.traverse(x =>
        if credentials then Credential.decode(x, DecodeField.Guards)
        else V12.bytesN(x, 28, DecodeField.Guards).map(Credential.Key(_))
      ).flatMap(V12.unique(_, DecodeField.Guards)(identity))
    }

  /** `AccountBalanceInterval` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:684-696`,
    * cardano-ledger 9df9f98c): an unsigned integer is an exact balance; anything else is
    * `decodeRecordNamed` of a lower and an upper bound, each a coin or null, not both null.
    */
  def balanceInterval(n: Node): Result[DijkstraBalanceInterval] = n.value match
    case Value.UInt(_) =>
      V12.word64(n, DecodeField.AccountBalanceIntervals).map(DijkstraBalanceInterval.Exact(_))
    case _ =>
      V12
        .record(n, 2, DecodeField.AccountBalanceIntervals)
        .flatMap(xs =>
          (
            V12.nullable(xs(0))(V12.word64(_, DecodeField.AccountBalanceIntervals)),
            V12.nullable(xs(1))(V12.word64(_, DecodeField.AccountBalanceIntervals))
          ).tupled
        )
        .flatMap {
          case (None, None)    => Left(LedgerDecodeError.Empty(DecodeField.AccountBalanceIntervals))
          case (Some(l), None) => Right(DijkstraBalanceInterval.Lower(l))
          case (None, Some(u)) => Right(DijkstraBalanceInterval.Upper(u))
          case (Some(l), Some(u)) => Right(DijkstraBalanceInterval.Both(l, u))
        }

end DijkstraBodyReaders
