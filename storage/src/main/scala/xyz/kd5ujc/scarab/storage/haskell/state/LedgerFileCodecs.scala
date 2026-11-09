// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.consensus.state.*
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
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, Era, StateLayout}

import DecodeError.Kind
import EraLedgerState.toEra
import Envelope.*
import Wire.*

/** The header state and the whole `state` file: the tip of the headers, and the telescope of
  * chain-dependent states, around the codecs of [[ConsensusCodecs]].
  */
trait LedgerFileCodecs extends ConsensusCodecs:
  private val eraIndex: Codec[Era] =
    Codec
      .uintBig(BigInt(Era.values.length - 1))
      .imap(i => Era.values(i.toInt))(e => BigInt(e.index))

  private def byronTip(n: Node): Decoded[AnnTip] =
    arrayNode(stateLayout, n).flatMap(Arr.exact(_, 4)).flatMap { ys =>
      for
        slot <- slotNo.decode(ys(0)).at("slot")
        hash <- hash32.decode(ys(1)).at("hash")
        block <- blockNo.decode(ys(2)).at("blockNo")
        ebb <- Codec.bool.decode(ys(3)).at("isEBB")
      yield AnnTip(slot, block, TipInfo.ByronTip(hash, if ebb then IsEBB.EBB else IsEBB.NotEBB))
    }

  private def shelleyTipIn(era: Era, at: Node)(n: Node): Decoded[AnnTip] =
    arrayNode(stateLayout, n).flatMap(Arr.exact(_, 3)).flatMap { ys =>
      for
        slot <- slotNo.decode(ys(0)).at("slot")
        hash <- hash32.decode(ys(1)).at("hash")
        block <- blockNo.decode(ys(2)).at("blockNo")
        witness <- Versions
          .shelleyBased(era)
          .toRight(
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
      arrayNode(stateLayout, n).flatMap(Arr.exact(_, 2)).flatMap { xs =>
        eraIndex.decode(xs(0)).at("era").flatMap { era =>
          // the tip of a Dijkstra chain: its state is not read by this build
          if era == Era.Dijkstra then
            Left(DecodeError.at(xs(0), Kind.Domain(DijkstraNotYet.LedgerState)))
          else if era == Era.Byron then byronTip(xs(1)).at("tip")
          else shelleyTipIn(era, xs(0))(xs(1)).at("tip")
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
    private val tip = withOriginMaybe(annTip, stateLayout)
    private val chainDep = telescope(stateLayout, chainDepStateOf)
    def decode(n: Node): Decoded[HeaderState] =
      arrayNode(stateLayout, n).flatMap(Arr.exact(_, 2)).flatMap { xs =>
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

  // ---- the Peras state of the Leios layout (ouroboros-consensus 2416493b)

  /** A `PerasEnabled (BoundedPerasEpochContext blk)` of the resolver's window (`Peras/Params.hs:
    * 247-250`, a `Maybe`, `[]` or `[x]`): only `NoPerasEnabled` is read. A bounded epoch context
    * (`Peras/Context.hs:170-203`, `[start round, end round, [committee, parameters]]`) holds a
    * voting committee indexed by era (`decodeNS`, `HardFork/Combinator/AcrossEras.hs`,
    * `[era, committee]`): the eras before Dijkstra have none (`VoidPerasVotingCommitteeScheme`,
    * whose decoder fails, `Peras/Void.hs:168-170`), so the reference refuses one of them as this
    * does; Dijkstra's is the Dijkstra part of the state, not read by this build.
    */
  private val noEpochContext: Codec[Unit] = new Codec[Unit]:
    def decode(n: Node): Decoded[Unit] =
      arrayNode(StateLayout.Leios, n).flatMap(Arr.between(_, 0, 1)).flatMap { xs =>
        xs.headOption.fold[Decoded[Unit]](Right(())) { context =>
          (for
            bounded <- Arr.exact(context, 3)
            epochContext <- Arr.exact(bounded(2), 2).at("epochContext")
            committee <- Arr.exact(epochContext(0), 2).at("epochContext").at("pecCommittee")
            era <- Codec.uintInt(255).decode(committee(0)).at("pecCommittee")
            refusal = Era.values.lift(era) match
              case Some(Era.Dijkstra) => Kind.Domain(DijkstraNotYet.LedgerState)
              case Some(e)            => Kind.Unsupported(s"a Peras voting committee of $e (Void)")
              case None               => Kind.OutOfRange("an era index", BigInt(era))
            r <- Left(DecodeError.at(committee(0), refusal)): Decoded[Unit]
          yield r).at("PerasEnabled")
        }
      }
    def encode(a: Unit, out: Writer): Unit = out.arrayHead(0)

  /** `PerasEpochContextResolver` (`Peras/Context.hs:264-301`): `[0, reason]` or `[1, current,
    * previous]`, any other length and tag refused.
    */
  given perasResolver: Codec[PerasResolver] = new Codec[PerasResolver]:
    private val reason = Codec.bytesSized(0, Int.MaxValue)
    def decode(n: Node): Decoded[PerasResolver] =
      arrayNode(StateLayout.Leios, n).flatMap(variant).flatMap {
        case (t, Vector(r)) if t == 0 =>
          (if r.isIndefinite then Left(Codec.mismatch("definite byte string", r))
           else reason.decode(r)).map(PerasResolver.Failed(_)).at("reason")
        case (t, Vector(current, previous)) if t == 1 =>
          for
            _ <- noEpochContext.decode(current).at("current")
            _ <- noEpochContext.decode(previous).at("previous")
          yield PerasResolver.Disabled
        case (t, xs) => unknownVariant(n, "PerasEpochContextResolver", t, xs.size)
      }
    def encode(a: PerasResolver, out: Writer): Unit = a match
      case PerasResolver.Failed(r) =>
        out.arrayHead(2)
        out.uint(0L)
        reason.encode(r, out)
      case PerasResolver.Disabled =>
        out.arrayHead(3)
        out.uint(1L)
        noEpochContext.encode((), out)
        noEpochContext.encode((), out)

  /** `PerasState` (`Ledger/Peras.hs:76-101`): `[resolver, latest certificate round]`, the round a
    * `StrictMaybe` (`encodeStrictMaybe`, `Util/CBOR.hs:358-362`).
    */
  given perasState: Codec[PerasState] =
    arrayIn(
      StateLayout.Leios,
      Record.of[PerasState]((perasResolver, arrayIn(StateLayout.Leios, strictMaybe(perasRound))))
    )

  /** `ExtLedgerState` in the snapshot (`LedgerDB/Snapshots.hs`): `[1, ext]`. In the 11.1 layout
    * `ext` is `[ledger state telescope, header state]` (ouroboros-consensus 82ecba32
    * `Ledger/Extended.hs:233-248`, read by `decodeListLenOf 2`, `:268-280`). In the Leios layout it
    * is `[ledger state telescope, header state, Peras state]`, always written so (2416493b
    * `Extended.hs:420-442`); two parts are read with the default Peras state and three with theirs,
    * any other length refused after the ledger and header states (`:464-505`).
    */
  given haskellLedgerFile: Codec[HaskellLedgerFile] =
    val ledger = telescope(stateLayout, ledgerFileEra)
    def leiosPeras(a: HaskellLedgerFile): Option[PerasState] = a.layout match
      case ExtLedgerState.Layout.Leios(peras) => Some(peras)
      case ExtLedgerState.Layout.Node11_1     => None
    versionedIn(stateLayout)(
      1,
      new Codec[HaskellLedgerFile]:
        def decode(n: Node): Decoded[HaskellLedgerFile] = stateLayout match
          case StateLayout.Node11_1 =>
            arrayNode(stateLayout, n).flatMap(Arr.exact(_, 2)).flatMap { xs =>
              for
                l <- ledger.decode(xs(0)).at("ledgerState")
                h <- headerState.decode(xs(1)).at("headerState")
              yield HaskellLedgerFile(l, h, ExtLedgerState.Layout.Node11_1)
            }
          case StateLayout.Leios =>
            arrayNode(stateLayout, n).flatMap(Arr.between(_, 2, 3)).flatMap { xs =>
              for
                l <- ledger.decode(xs(0)).at("ledgerState")
                h <- headerState.decode(xs(1)).at("headerState")
                p <- xs
                  .lift(2)
                  .fold(Right(PerasState.default): Decoded[PerasState])(
                    perasState.decode(_).at("perasState")
                  )
              yield HaskellLedgerFile(l, h, ExtLedgerState.Layout.Leios(p))
            }
        def encode(a: HaskellLedgerFile, out: Writer): Unit =
          val peras = leiosPeras(a)
          out.arrayHead(if peras.isDefined then 3 else 2)
          ledger.encode(a.ledger, out)
          headerState.encode(a.header, out)
          peras.foreach(perasState.encode(_, out))
        override def check(a: HaskellLedgerFile): Option[ConstraintViolation] =
          ofThisLayout("ExtLedgerState", a.layout)
            .map(_.at("layout"))
            .orElse(ledger.check(a.ledger).map(_.at("ledgerState")))
            .orElse(headerState.check(a.header).map(_.at("headerState")))
    )
