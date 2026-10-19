// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayLedgerState,
  ConwayNewEpochState,
  Credential,
  DRepRole,
  DRepState,
  EpochNo,
  NewEpochState,
  Sorted
}

/** The pinned exporter normalizes dormant DRep expiries before serialization. SCLS omits the
  * dormant counter, so importing against a complete same-point base must retain its known original
  * expiries. Arithmetic inversion is ambiguous at Word64 wraparound.
  */
object SclsDRepMapper:
  enum Error extends DomainError:
    case DuplicateCredential(credential: Credential[DRepRole])
    case UnexpectedCredential(credential: Credential[DRepRole])
    case ProjectionMismatch(credential: Credential[DRepRole])
    case MissingCredential(credential: Credential[DRepRole])

  /** cardano-ledger@f649f975, Conway/Rules/Certs.hs:308-328; canonical export invokes this at
    * CanonicalState/Conway/Export.hs:363-371. `binOpEpochNo (+)` wraps its Word64 result before
    * comparing it with the current epoch. This interchange operation does not change ledger rules.
    */
  private[scls] def normalizeExpiry(epoch: EpochNo, dormant: EpochNo, expiry: EpochNo): EpochNo =
    if dormant.word.isZero then expiry
    else
      val actual = EpochNo.fromUnsigned(((dormant.toBigInt + expiry.toBigInt) & Mask).toLong)
      if actual.toBigInt < epoch.toBigInt then expiry else actual

  private val Mask = (BigInt(1) << 64) - 1
  private val voting = ConwayNewEpochState.ls.andThen(ConwayLedgerState.vState)

  def project(base: NewEpochState[Conway]): Iterator[(SclsKey.Key, SclsValue.Entry)] =
    val state = voting.get(base)
    state.dreps.iterator.map { case (credential, value) =>
      SclsKey.Key.DReps(credential) -> SclsValue.Entry.DRep(
        DRepState.expiry.modify(normalizeExpiry(base.epoch, state.numDormantEpochs, _))(value)
      )
    }

  /** Reconstruct an exact canonical projection against its complete base. Each original expiry is a
    * checked preimage from that base, never a guessed subtraction. Every other DRep field comes
    * from the incoming typed record; the unrepresented dormant counter stays in the base.
    * Whole-state admission, including exact block hash and all namespace commitments, belongs to
    * the service. This helper alone never authorizes snapshot publication.
    */
  private[scls] def overlay(
      base: NewEpochState[Conway],
      entries: Iterator[(Credential[DRepRole], DRepState)]
  ): Either[Error, NewEpochState[Conway]] =
    val state = voting.get(base)
    val empty = Sorted.empty[Credential[DRepRole], DRepState]
    entries
      .foldLeft[Either[Error, SortedMap[Credential[DRepRole], DRepState]]](Right(empty)) {
        case (result, (credential, incoming)) =>
          result.flatMap { collected =>
            for
              _ <- Either.cond(
                !collected.contains(credential),
                (),
                Error.DuplicateCredential(credential)
              )
              original <- state.dreps
                .get(credential)
                .toRight(Error.UnexpectedCredential(credential))
              normalized = DRepState.expiry.modify(
                normalizeExpiry(base.epoch, state.numDormantEpochs, _)
              )(original)
              _ <- Either.cond(normalized == incoming, (), Error.ProjectionMismatch(credential))
            yield collected.updated(credential, incoming.copy(expiry = original.expiry))
          }
      }
      .flatMap { collected =>
        state.dreps.keysIterator.find(credential => !collected.contains(credential)) match
          case Some(missing) => Left(Error.MissingCredential(missing))
          case None          => Right(ConwayNewEpochState.dreps.replace(collected)(base))
      }
