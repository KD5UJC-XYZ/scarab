// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.syntax.all.*
import java.nio.charset.StandardCharsets.US_ASCII
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.{GovActionId, TxIn, state as S}

import SclsRecord.Namespace

/** Keys of `ledger-f649-conway-v0`. Reference: cardano-ledger@f649f975,
  * libs/cardano-ledger-canonical-state/src/Cardano/Ledger/CanonicalState/Namespace:
  * UTxO/V0.hs:42-45 uses the ordinary MemPack TxIn (little-endian index), whereas
  * GovProposals/V0.hs:61-80 explicitly uses a big-endian governance index. Their equal widths do
  * not imply equal encodings. Blocks/V0.hs:48-56 defines the epoch suffix; GovPParams/V0.hs:59-83
  * and GovProposals/Roots/V0.hs:53-73 define the remaining selectors.
  */
object SclsKey:
  enum Parameters(val text: String):
    case Previous extends Parameters("prev")
    case Current extends Parameters("curr")
    case PossibleFuture extends Parameters("pfut")
    case DefiniteFuture extends Parameters("dfut")

  enum Purpose:
    case Parameters, HardFork, Committee, Constitution

  enum Key(val namespace: Namespace):
    case Blocks(pool: S.KeyHash[S.StakePool], epoch: S.EpochNo) extends Key(Namespace.Blocks)
    case Accounts(credential: S.Credential[S.Staking]) extends Key(Namespace.Accounts)
    case CommitteeEntities extends Key(Namespace.CommitteeEntities)
    case DReps(credential: S.Credential[S.DRepRole]) extends Key(Namespace.DReps)
    case StakePools(pool: S.KeyHash[S.StakePool]) extends Key(Namespace.StakePools)
    case StakePoolVrfHashes(hash: S.VRFVerKeyHash) extends Key(Namespace.StakePoolVrfHashes)
    case Committee extends Key(Namespace.Committee)
    case Constitution extends Key(Namespace.Constitution)
    case PParams(which: Parameters) extends Key(Namespace.PParams)
    case ProposalRoots(purpose: Purpose) extends Key(Namespace.ProposalRoots)
    case Proposals(id: GovActionId) extends Key(Namespace.Proposals)
    case Utxo(input: TxIn) extends Key(Namespace.Utxo)

  enum Error extends DomainError:
    case Length(namespace: Namespace, found: Int)
    case HashLength(namespace: Namespace, found: Int)
    case IndexRange(namespace: Namespace, found: BigInt)
    case CredentialTag(namespace: Namespace, found: Int)
    case Singleton(namespace: Namespace, found: Int)
    case ParameterSelector(found: Bytes)
    case PurposeSelector(found: Int)
    case Vocabulary(cause: S.VocabError)

  private def byte(n: Int): Bytes = Bytes(Vector(n.toByte))
  private val zero = byte(0)

  private def unsigned(n: BigInt, size: Int, little: Boolean = false): Bytes =
    Bytes(Vector.tabulate(size) { i =>
      val place = if little then i else size - 1 - i
      ((n >> (8 * place)) & 255).toByte
    })

  private def number(bytes: Bytes, little: Boolean = false): BigInt =
    val ordered = if little then bytes.value.reverse else bytes.value
    ordered.foldLeft(BigInt(0))((n, b) => (n << 8) | (b & 255))

  private def credential[R <: S.KeyRole](value: S.Credential[R]): Bytes = value match
    case S.Credential.ScriptHashObj(hash) => zero ++ hash.bytes
    case S.Credential.KeyHashObj(hash)    => byte(1) ++ hash.bytes

  private def credential[R <: S.KeyRole](
      namespace: Namespace,
      bytes: Bytes
  ): Either[Error, S.Credential[R]] =
    val hash = bytes.drop(1).compact
    (bytes(0) & 255) match
      case 0 =>
        S.ScriptHash
          .fromBytes(hash)
          .leftMap(Error.Vocabulary(_))
          .map(S.Credential.ScriptHashObj[R](_))
      case 1 =>
        S.KeyHash.fromBytes[R](hash).leftMap(Error.Vocabulary(_)).map(S.Credential.KeyHashObj[R](_))
      case n => Left(Error.CredentialTag(namespace, n))

  private def input(
      namespace: Namespace,
      id: Bytes,
      index: BigInt,
      little: Boolean
  ): Either[Error, Bytes] =
    for
      _ <- Either.cond(id.size == 32, (), Error.HashLength(namespace, id.size))
      _ <- Either.cond(index >= 0 && index <= 65535, (), Error.IndexRange(namespace, index))
    yield id ++ unsigned(index, 2, little)

  def encode(key: Key): Either[Error, Bytes] = key match
    case Key.Blocks(pool, epoch)      => Right(pool.bytes ++ unsigned(epoch.toBigInt, 8))
    case Key.Accounts(value)          => Right(credential(value))
    case Key.DReps(value)             => Right(credential(value))
    case Key.StakePools(pool)         => Right(pool.bytes)
    case Key.StakePoolVrfHashes(hash) => Right(hash.bytes)
    case Key.CommitteeEntities | Key.Committee | Key.Constitution => Right(zero)
    case Key.PParams(which)         => Right(Bytes.wrap(which.text.getBytes(US_ASCII)))
    case Key.ProposalRoots(purpose) => Right(byte(purpose.ordinal))
    case Key.Proposals(id)          => input(key.namespace, id.txId, id.index, little = false)
    case Key.Utxo(in)               => input(key.namespace, in.id, in.index, little = true)

  def decode(namespace: Namespace, bytes: Bytes): Either[Error, Key] =
    if bytes.size != namespace.keySize then Left(Error.Length(namespace, bytes.size))
    else
      namespace match
        case Namespace.Blocks =>
          (
            S.KeyHash.fromBytes[S.StakePool](bytes.take(28).compact),
            S.EpochNo.fromBigInt(number(bytes.drop(28)))
          ).mapN(Key.Blocks.apply).leftMap(Error.Vocabulary(_))
        case Namespace.Accounts => credential[S.Staking](namespace, bytes).map(Key.Accounts(_))
        case Namespace.DReps    => credential[S.DRepRole](namespace, bytes).map(Key.DReps(_))
        case Namespace.StakePools =>
          S.KeyHash
            .fromBytes[S.StakePool](bytes.compact)
            .leftMap(Error.Vocabulary(_))
            .map(Key.StakePools(_))
        case Namespace.StakePoolVrfHashes =>
          S.VRFVerKeyHash
            .fromBytes(bytes.compact)
            .leftMap(Error.Vocabulary(_))
            .map(Key.StakePoolVrfHashes(_))
        case Namespace.PParams =>
          Parameters.values
            .find(p => Bytes.wrap(p.text.getBytes(US_ASCII)) == bytes)
            .map(Key.PParams(_))
            .toRight(Error.ParameterSelector(bytes.compact))
        case Namespace.ProposalRoots =>
          Purpose.values
            .lift(bytes(0) & 255)
            .map(Key.ProposalRoots(_))
            .toRight(Error.PurposeSelector(bytes(0) & 255))
        case Namespace.Proposals =>
          Right(Key.Proposals(GovActionId(bytes.take(32).compact, number(bytes.drop(32)))))
        case Namespace.Utxo =>
          Right(Key.Utxo(TxIn(bytes.take(32).compact, number(bytes.drop(32), little = true))))
        case Namespace.CommitteeEntities | Namespace.Committee | Namespace.Constitution =>
          if bytes(0) != 0 then Left(Error.Singleton(namespace, bytes(0) & 255))
          else
            Right(namespace match
              case Namespace.CommitteeEntities => Key.CommitteeEntities
              case Namespace.Committee         => Key.Committee
              case _                           => Key.Constitution)
