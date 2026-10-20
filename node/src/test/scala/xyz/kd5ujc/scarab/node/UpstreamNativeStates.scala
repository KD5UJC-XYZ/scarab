// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.mempack.TxOutMemPack
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Decoded, Node, Writer}
import xyz.kd5ujc.scarab.storage.haskell.state.{EpochStateCodecs, Record, Wire}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given
import xyz.kd5ujc.scarab.storage.scls.{SclsKey, SclsRecord}

// Ordinary state-file codecs deliberately read an empty UTxO plus an external table. The
// upstream integration hook writes its actual map inline, using ordinary little-endian
// MemPack keys and Conway MemPack values. Reuse those production codecs without dropping it.
private[node] object UpstreamNativeStates extends EpochStateCodecs with munit.Assertions:
  private enum TraceError extends xyz.kd5ujc.scarab.codec.DomainError:
    case MemPack(cause: xyz.kd5ujc.scarab.ledger.mempack.MemPack.Failure)

  def inlineUtxoTables(using ProtVer): Codec[UtxoTables] = new Codec[UtxoTables]:
    def decode(n: Node): Decoded[UtxoTables] =
      Wire.sortedMapOf(Codec.bytes, Codec.bytes).decode(n).flatMap { entries =>
        entries.toVector
          .traverse { (key, value) =>
            for
              decoded <- SclsKey
                .decode(SclsRecord.Namespace.Utxo, key)
                .leftMap(e => DecodeError.at(n, DecodeError.Kind.Domain(e)))
              input <- decoded match
                case SclsKey.Key.Utxo(input) => Right(input)
                case other                   => Left(Codec.mismatch(s"UTxO key ($other)", n))
              output <- TxOutMemPack
                .decode[Conway](value)
                .leftMap(e => DecodeError.at(n, DecodeError.Kind.Domain(TraceError.MemPack(e))))
            yield input -> output
          }
          .map(entries => UtxoTables(xyz.kd5ujc.scarab.ledger.Utxo.fromMap(entries.toMap)))
      }
    def encode(a: UtxoTables, out: Writer): Unit =
      val entries = a.utxo.iterator.toVector.map { (input, output) =>
        val key = SclsKey.encode(SclsKey.Key.Utxo(input)).fold(e => fail(e.toString), identity)
        val value = TxOutMemPack.encode[Conway](output).fold(e => fail(e.toString), identity)
        key -> value
      }
      out.mapHead(entries.size)
      entries.foreach { (key, value) =>
        out.bytes(key); out.bytes(value)
      }

  def nativeUtxoState(using ProtVer): Codec[UTxOState[Conway]] =
    Record.of[UTxOState[Conway]](
      (inlineUtxoTables, coin, coin, conwayGovState, conwayInstantStake, coin)
    )

  def nativeLedgerState(using ProtVer): Codec[LedgerState[Conway]] =
    Record.of[LedgerState[Conway]]((conwayCertState, nativeUtxoState))

  def nativeNewEpochState(using ProtVer): Codec[NewEpochState[Conway]] =
    val epoch =
      Record.of[EpochState[Conway]]((chainAccountState, nativeLedgerState, snapShots, nonMyopic))
    Record.of[NewEpochState[Conway]](
      (
        epochNo,
        blocksMade,
        blocksMade,
        epoch,
        Wire.strictMaybe(pulsingRewUpdate),
        poolDistr,
        stashedAvvm[Conway]
      )
    )
