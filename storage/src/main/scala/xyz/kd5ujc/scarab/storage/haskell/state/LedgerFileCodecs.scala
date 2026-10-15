// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.consensus.state.*
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.models.runtime.{
  Arr,
  Codec,
  ConstraintViolation,
  DecodeError,
  Decoded,
  Node,
  Writer,
  at
}
import xyz.kd5ujc.scarab.protocol.Era

import DecodeError.Kind
import EraLedgerState.toEra
import Envelope.*
import Wire.*

/** The header state and the whole `state` file: the tip of the headers, and the telescope of
  * chain-dependent states, around the codecs of [[ConsensusCodecs]].
  */
trait LedgerFileCodecs extends ConsensusCodecs:
  /** The witness of a Shelley-based era, from its index in the telescope. */
  private def shelleyBasedAt(era: Era): Option[ShelleyBasedEra[?]] = era match
    case Era.Byron   => None
    case Era.Shelley => Some(ShelleyBasedEra.SShelley)
    case Era.Allegra => Some(ShelleyBasedEra.SAllegra)
    case Era.Mary    => Some(ShelleyBasedEra.SMary)
    case Era.Alonzo  => Some(ShelleyBasedEra.SAlonzo)
    case Era.Babbage => Some(ShelleyBasedEra.SBabbage)
    case Era.Conway  => Some(ShelleyBasedEra.SConway)

  private val eraIndex: Codec[Era] =
    Codec
      .uintBig(BigInt(Era.values.length - 1))
      .imap(i => Era.values(i.toInt))(e => BigInt(e.index))

  private def byronTip(n: Node): Decoded[AnnTip] =
    Arr.exact(n, 4).flatMap { ys =>
      for
        slot <- slotNo.decode(ys(0)).at("slot")
        hash <- hash32.decode(ys(1)).at("hash")
        block <- blockNo.decode(ys(2)).at("blockNo")
        ebb <- Codec.bool.decode(ys(3)).at("isEBB")
      yield AnnTip(slot, block, TipInfo.ByronTip(hash, if ebb then IsEBB.EBB else IsEBB.NotEBB))
    }

  private def shelleyTipIn(era: Era, at: Node)(n: Node): Decoded[AnnTip] =
    Arr.exact(n, 3).flatMap { ys =>
      for
        slot <- slotNo.decode(ys(0)).at("slot")
        hash <- hash32.decode(ys(1)).at("hash")
        block <- blockNo.decode(ys(2)).at("blockNo")
        witness <- shelleyBasedAt(era).toRight(
          DecodeError.at(at, Kind.OutOfRange("a Shelley-based era", BigInt(era.index)))
        )
      yield AnnTip(slot, block, TipInfo.ShelleyBasedTip(witness, hash))
    }

  /** `AnnTip` of the Cardano chain (`HardFork/Combinator/Serialisation/SerialiseDisk.hs:100-108`,
    * an `NS`): `[era index, the era's tip]`. A Shelley-based tip is `[slot, hash, block number]`
    * (`HeaderValidation.hs:657-671`), Byron's has the boundary-block flag after them (`:673-697`).
    * Note the order of the hash and the block number, which is not the ledger tip's (`ShelleyTip`:
    * slot, block number, hash).
    */
  given annTip: Codec[AnnTip] = new Codec[AnnTip]:
    def decode(n: Node): Decoded[AnnTip] =
      Arr.exact(n, 2).flatMap { xs =>
        eraIndex.decode(xs(0)).at("era").flatMap { era =>
          (if era == Era.Byron then byronTip(xs(1)) else shelleyTipIn(era, xs(0))(xs(1))).at("tip")
        }
      }
    def encode(a: AnnTip, out: Writer): Unit =
      out.arrayHead(2)
      a.info match
        case TipInfo.ByronTip(hash, isEBB) =>
          out.uint(Era.Byron.index.toLong)
          out.arrayHead(4)
          slotNo.encode(a.slot, out)
          hash32.encode(hash, out)
          blockNo.encode(a.blockNo, out)
          out.bool(isEBB == IsEBB.EBB)
        case TipInfo.ShelleyBasedTip(era, hash) =>
          out.uint(era.toEra.index.toLong)
          out.arrayHead(3)
          slotNo.encode(a.slot, out)
          hash32.encode(hash, out)
          blockNo.encode(a.blockNo, out)

  /** `HeaderState` (`HeaderValidation.hs:715-727`): `[tip, chain-dependent state telescope]`. */
  given headerState: Codec[HeaderState] = new Codec[HeaderState]:
    private val tip = withOriginMaybe(annTip)
    private val chainDep = telescope(chainDepStateOf)
    def decode(n: Node): Decoded[HeaderState] =
      Arr.exact(n, 2).flatMap { xs =>
        for
          t <- tip.decode(xs(0)).at("tip")
          c <- chainDep.decode(xs(1)).at("chainDep")
        yield HeaderState(t, c)
      }
    def encode(a: HeaderState, out: Writer): Unit =
      out.arrayHead(2)
      tip.encode(a.tip, out)
      chainDep.encode(a.chainDep, out)
    override def check(a: HeaderState): Option[ConstraintViolation] =
      chainDep.check(a.chainDep).map(_.at("chainDep"))

  /** `ExtLedgerState` in the snapshot (`Ledger/Extended.hs:233-248`, `LedgerDB/Snapshots.hs`):
    * `[1, [ledger state telescope, header state]]`.
    */
  given haskellLedgerFile: Codec[HaskellLedgerFile] =
    val ledger = telescope(ledgerFileEra)
    versioned(
      1,
      new Codec[HaskellLedgerFile]:
        def decode(n: Node): Decoded[HaskellLedgerFile] =
          Arr.exact(n, 2).flatMap { xs =>
            for
              l <- ledger.decode(xs(0)).at("ledgerState")
              h <- headerState.decode(xs(1)).at("headerState")
            yield HaskellLedgerFile(l, h)
          }
        def encode(a: HaskellLedgerFile, out: Writer): Unit =
          out.arrayHead(2)
          ledger.encode(a.ledger, out)
          headerState.encode(a.header, out)
        override def check(a: HaskellLedgerFile): Option[ConstraintViolation] =
          ledger.check(a.ledger).map(_.at("ledgerState")).orElse(headerState.check(a.header))
    )
