// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}

/** Pre-Conway protocol-parameter update proposals: what PPUP refuses and how many genesis delegates
  * must agree (the era-indexed rules, `ShelleyUtxo` and `Upec`, call these).
  */
object UpdateChecks:
  /** Why an update proposal is refused: by PPUP, or because its protocol version is not a pair of
    * numbers (which the reference's decoder would not admit).
    */
  enum Refusal:
    case Failed(failure: PpupFailure)
    case Unreadable

    /** The refusal of transaction `id` as the ledger's failure for it. */
    def inTx(id: Bytes): LedgerFailure = this match
      case Failed(f)  => LedgerFailure.Rejected(id, PredicateFailure.utxo(UtxoFailure.Update(f)))
      case Unreadable => LedgerFailure.Undecodable(id, TxDefect.UnreadableProtocolVersion)

  /** A proposed protocol version must follow the current one: `(major + 1, 0)` or `(major, minor +
    * 1)`.
    */
  def canFollow(params: ProtocolParams, u: ParamUpdate): Either[Refusal, Unit] =
    u.fields.get(14) match
      case None => Right(())
      case Some(raw) =>
        for
          n <- Cbor.decode(raw).leftMap(_ => Refusal.Unreadable)
          xs <- CborRead.arrayN(n, 2, "protocol version").leftMap(_ => Refusal.Unreadable)
          major <- CborRead.uint(xs(0), "major").leftMap(_ => Refusal.Unreadable)
          minor <- CborRead.uint(xs(1), "minor").leftMap(_ => Refusal.Unreadable)
          _ <- Either.cond(
            (major == params.protocolMajor + 1 && minor == 0) ||
              (major == params.protocolMajor && minor == params.protocolMinor + 1),
            (),
            Refusal.Failed(PpupFailure.PVCannotFollow(major, minor))
          )
        yield ()

  /** The parameters the proposals of the epoch vote for: the one update that at least `quorum`
    * genesis delegates proposed identically, applied to `params` (`votedFuturePParams`,
    * `Rules/Ppup.hs:205-225`).
    */
  def voted(
      params: ProtocolParams,
      current: Map[Bytes, ParamUpdate],
      quorum: Int
  ): Option[ProtocolParams] =
    val winners = current.values.toVector
      .groupBy(identity)
      .collect { case (u, vs) if vs.size >= quorum => u }
      .toVector
    winners match
      case Vector(u) =>
        ProtocolParams
          .update(params, u)
          .toOption
          .filter(p => p.maxTxSize + p.maxBlockHeaderSize < p.maxBlockBodySize)
      case _ => None
