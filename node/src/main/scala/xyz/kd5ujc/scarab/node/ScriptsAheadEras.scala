// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.state.{MirrorClock, MirrorState, Placement}
import xyz.kd5ujc.scarab.ledger.{
  Header,
  PosixClock,
  Prepared,
  ProtocolParams,
  SystemStart,
  Tx as FlatTx,
  TxIn,
  TxOut as FlatTxOut
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayBlock, ConwayPParams, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, PreConway, ShelleyBbody}
import xyz.kd5ujc.scarab.ledger.state.{Conway, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era

/** The scripts of a queued block as [[ScriptsAhead]] speculates them on the selected chain at the
  * worker's tip, `state`: by the era of the tip's ledger state, which must be the block's (a block
  * that enters another era is not speculated), with the tip's parameters and its era history at the
  * block's slot, as the rules take them for a block applied on that state (`MirrorRules.shelley`).
  * A block of an era before Conway has its requests collected as the flat rules collect them
  * ([[ScriptsAhead.flat]]); a Conway block as Conway's era rules do
  * (`collectPlutusScriptsWithContext`). Byron and Dijkstra blocks, and a block that does not decode
  * or whose redeemers declare more units than the block may hold, are not speculated.
  */
private[node] object ScriptsAheadEras:
  def scripts(
      start: SystemStart,
      placement: Placement,
      state: MirrorState,
      header: Header.Shelley,
      raw: xyz.kd5ujc.scarab.codec.Bytes
  ): Option[ScriptsAhead.Scripts] =
    xyz.kd5ujc.scarab.consensus.state.MirrorStep.nesOf(state).flatMap {
      case i: EraNes.InEra[e] if EraNes.eraOf(i.era) == header.era =>
        Prepared.decode(raw, state.ext.layout.stateLayout).flatMap { prepared =>
          (i.era, prepared) match
            case (ShelleyBasedEra.SConway, Prepared.Conway(read)) =>
              conway(MirrorClock.conway(start, state, placement, header.slot), i.nes, read)
            case (era, Prepared.Flat(_, decoded)) =>
              PreConway.of(era).flatMap { pre =>
                given PreConway[e] = pre
                given ShelleyBasedEra[e] = era
                decoded.sequence.toOption.flatMap { txs =>
                  val pp = pre.curPParams.get(i.nes.es.ls.utxo)
                  Option.when(ShelleyBbody.exUnitsExceeded[e](pp, txs).isEmpty) {
                    val clock = MirrorClock.of(start, state, placement, header.era, header.slot)
                    val scripted = txs.filter(_.witnesses.redeemers.nonEmpty)
                    ScriptsAhead.Scripts(
                      scripted.size,
                      txs.foldLeft(Map.empty[TxIn, FlatTxOut])(_ ++ ScriptsAhead.created(_)),
                      reads(scripted),
                      outputs =>
                        ScriptsAhead.flat[e](scripted, pp, slot => clock.posix(slot), outputs)
                    )
                  }
                }
              }
            case _ => None
        }
      case _ => None
    }

  /** What transactions with redeemers read: their inputs and reference inputs. */
  private def reads(scripted: Vector[FlatTx]): Set[TxIn] =
    scripted.iterator.flatMap(t => t.body.inputs ++ t.body.referenceInputs).toSet

  /** Whether the units the transactions `txs` declare fit the block's limit of `pp`. */
  private def fits(pp: ProtocolParams, txs: Vector[FlatTx]): Boolean =
    txs
      .foldLeft(xyz.kd5ujc.scarab.plutus.ExUnits.zero)(_ + _.witnesses.totalExUnits)
      .fits(pp.maxBlockExUnits)

  /** A Conway block read into the era's types (`read`), speculated on the Conway state `nes` with
    * the clock `clock`: its transactions also read by the flat decoder for what they read and
    * create, each transaction's requests collected by the era's collection, from the outputs read
    * into the era (`ConwayUTxO.fromStored`); a valid transaction creates its outputs as the era's
    * rules store them.
    */
  private def conway(
      clock: PosixClock.Forecast,
      nes: NewEpochState[Conway],
      read: ConwayBlock.Read
  ): Option[ScriptsAhead.Scripts] =
    val stored = nes.es.ls.utxo.gov.curPParams
    val t = EraTx.conway
    val b = t.bodyOf
    given b.Level[TxLevel.Top] = b.top
    for
      block <- read.block.toOption
      flat <- read.envelope.transactions.traverse(tx => FlatTx.fromBlock(tx, Era.Conway).toOption)
      params <- ConwayPParams.fromStored(stored).toOption
      txs = EraBlockBody.conway.txSeqBlockBody(block.blockBody)
      if txs.sizeIs == flat.size && fits(stored, flat)
    yield
      val pairs = flat.zip(txs)
      val scripted = pairs.filter(_._1.witnesses.redeemers.nonEmpty)
      val created = pairs.foldLeft(Map.empty[TxIn, FlatTxOut]) { case (made, (f, e)) =>
        if f.isValid then
          made ++ UTxOs
            .txouts[Conway, TxLevel.Top](t.body[TxLevel.Top](e))
            .unUTxO
            .view
            .mapValues(StoredTxOut.conway.toStored)
        else made ++ ScriptsAhead.created(f)
      }
      val pp = PParams[Conway](params)
      ScriptsAhead.Scripts(
        scripted.size,
        created,
        reads(scripted.map(_._1)),
        outputs =>
          scripted.foldLeft(ScriptsAhead.Collected(Vector.empty, 0)) { case (acc, (f, e)) =>
            (f.body.inputs ++ f.body.referenceInputs)
              .traverse(in => outputs(in).map(in -> _))
              .flatMap(found => ConwayUTxO.fromStored(found.toMap).toOption)
              .flatMap(utxo =>
                AlonzoPlutusEvaluate
                  .collectPlutusScriptsWithContext[Conway](slot => clock.posix(slot), pp, e, utxo)
                  .toOption
              )
              .fold(acc.copy(skipped = acc.skipped + 1))(found =>
                acc.copy(requests = acc.requests ++ found.map(PlutusWithContext.evalRequest))
              )
          }
      )
