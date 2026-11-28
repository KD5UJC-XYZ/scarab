// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ConwayParamUpdate,
  DecodeField,
  DecoderVersion,
  GovAction as FlatGovAction,
  ProtocolParams,
  Tx as FlatTx
}
import xyz.kd5ujc.scarab.ledger.era.alonzo.AlonzoPParams
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbagePParams
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPParams
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraPParams
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Babbage, Conway, Dijkstra, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.*
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, HaskellStateCodecs, Params}

/** The era types' CBOR of the parameters and of an update against what the node reads and writes
  * now: every parameter change of the recorded Conway refusal corpora and Preview governance blocks
  * is read by `DecCBOR (PParamsUpdate ConwayEra)` as the flat decoder's typed value and written as
  * the flat writer writes it; and every parameter record of the recorded Conway and Dijkstra states
  * is the list the state's codec reads (`Params.codecIn`), positionally, read as `fromStored` of
  * the codec's value and written back to its bytes.
  */
class EraPParamsStoredSuite extends FunSuite:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def version(major: Int): DecoderVersion =
    DecoderVersion.ofMajor(BigInt(major)).getOrElse(fail(s"no version $major"))

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs)       => xs
    case Value.Tag(_, inner) => items(inner)
    case other               => fail(s"not an array: $other")

  /** The update of each parameter-change proposal of a body (key 20, `[deposit, account, action,
    * anchor]`, the action `[0, parent, update, policy]`), in order.
    */
  private def updatesOf(body: Node): Vector[Node] =
    body.value match
      case Value.Map(pairs) =>
        pairs
          .collectFirst { case (k, v) if k.value == Value.UInt(20) => items(v) }
          .getOrElse(Vector.empty)
          .map(p => items(p)(2))
          .flatMap(a =>
            items(a) match
              case Vector(tag, _, update, _) if tag.value == Value.UInt(0) => Some(update)
              case _                                                       => None
          )
      case other => fail(s"not a body: $other")

  /** The flat updates of a body's parameter changes, in order. */
  private def flatUpdates(tx: FlatTx): Vector[ConwayParamUpdate] =
    tx.body.proposals.map(_.action).collect { case FlatGovAction.ParameterChange(_, u, _) => u }

  private def compare(what: String, raw: Node, u: ConwayParamUpdate): Unit =
    val typed = right(s"$what typed")(ConwayPParams.fromUpdate(u))
    Vector(9, 10, 11).foreach { major =>
      given DecodeAt = DecodeAt(version(major), DecodeField.ParameterUpdate)
      val era = right(s"$what at $major")(PParamsUpdate.decodePParamsUpdate[Conway](raw))
      assertEquals(era.hkd, typed, s"$what at $major")
    }
    assertEquals(
      Right(PParamsUpdate.encodePParamsUpdate(PParamsUpdate[Conway](typed)).bytes),
      HaskellStateCodecs.conwayParamUpdate.write(u),
      what
    )

  test("every parameter change of the Conway refusal corpora reads and writes as the flat path's"):
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    val compared = loaded.corpora.filter(_.era == RefusalEra.Conway).flatMap { c =>
      c.cases.flatMap { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val flat = right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Conway))
        val raws = updatesOf(items(right(s"$what cbor")(Cbor.decode(bytes)))(0))
        val us = flatUpdates(flat)
        assertEquals(raws.size, us.size, what)
        raws.zip(us).zipWithIndex.map { case ((r, u), i) =>
          compare(s"$what #$i", r, u)
          what
        }
      }
    }
    assertEquals(compared.size, 14, compared)

  test(
    "every parameter change of the recorded Preview governance blocks reads and writes as the flat path's"
  ):
    val blocks = Files
      .readAllLines(Path.of("fixtures/preview/conway-governance.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => right("hex")(Bytes.fromHex(l)))
      .map(b => right("block")(Block.decodeShelley(b).left.map(_.message)))
    val compared = blocks.flatMap {
      case b: Block.Shelley =>
        b.transactions.flatMap { t =>
          val what = t.id.hex
          val flat = right(s"$what flat")(FlatTx.fromBlock(t, b.era))
          val raws = updatesOf(right(s"$what body")(Cbor.decode(t.body)))
          val us = flatUpdates(flat)
          assertEquals(raws.size, us.size, what)
          raws.zip(us).map((r, u) => { compare(what, r, u); what })
        }
      case other => fail(s"not a Shelley-based block: $other")
    }
    assertEquals(compared.size, 2, compared)

  /** Every list of `size` items of `root`, walked on the heap. */
  private def lists(root: Node, size: Int): Vector[Node] =
    @tailrec def go(pending: List[Node], found: Vector[Node]): Vector[Node] = pending match
      case Nil => found
      case n :: rest =>
        val children = n.value match
          case Value.Arr(xs)    => xs.toList
          case Value.Map(pairs) => pairs.toList.flatMap((k, v) => List(k, v))
          case Value.Tag(_, x)  => List(x)
          case _                => Nil
        val hit = n.value match
          case Value.Arr(xs) if xs.size == size => Vector(n)
          case _                                => Vector.empty
        go(children ::: rest, found ++ hit)
    go(List(root), Vector.empty)

  private def stateOf(path: String): Node =
    right(path)(Cbor.decode(Bytes.wrap(Files.readAllBytes(Path.of(path))), HaskellState.limits))

  /** The parameter records of a state: the lists of the era's size the state's codec reads. Each is
    * read by the era's decoder as `fromStored` of the codec's value, and written back to its bytes.
    */
  /** The governance state's parameters within a `NewEpochState` (cardano-ledger 9df9f98c): of
    * `[epoch, blocks, blocks, EpochState, ...]`, the `EpochState` 's `LedgerState`, its
    * `UTxOState`, its `ConwayGovState`; there the current and previous parameters (items 3 and 4)
    * and, in the DRep pulser's `DRComplete`, its `RatifyState` 's `EnactState` 's (items 2 and 3).
    */
  private def governance(nes: Node): Vector[Node] =
    val gov = Vector(3, 1, 1, 3).foldLeft(nes)((n, i) => items(n)(i))
    val enact = Vector(6, 1, 0).foldLeft(gov)((n, i) => items(n)(i))
    Vector(items(gov)(3), items(gov)(4), items(enact)(2), items(enact)(3))

  private def records[E <: xyz.kd5ujc.scarab.ledger.state.EraTag](
      path: String,
      telescope: Int,
      layout: StateLayout,
      era: ShelleyBasedEra[E],
      size: Int,
      at: DecoderVersion,
      govRecords: Node => Vector[Node] = governance,
      toStored: Option[PParams[E] => ProtocolParams] = None
  )(fromStored: ProtocolParams => PParams[E])(using EraPParams[E]): Int =
    val codec = Params.codecIn(layout, era)
    val root = stateOf(path)
    val found = lists(root, size).flatMap(n => codec.decode(n).toOption.map(n -> _))
    val nes = Vector(1, 0, telescope, 1, 1, 1).foldLeft(root)((n, i) => items(n)(i))
    assertEquals(
      found.map(_._1.original.hex).sorted,
      govRecords(nes).map(_.original.hex).sorted,
      s"$path: the records are the governance state's"
    )
    found.foreach { (n, stored) =>
      given DecodeAt = DecodeAt(at, DecodeField.ParameterUpdate)
      val expected = fromStored(stored)
      assertEquals(PParams.decodePParams[E](n), Right(expected), path)
      assertEquals(PParams.encodePParams(expected).bytes, n.original, path)
      assertEquals(codec.write(stored), Right(n.original), path)
      // the era's record written back over parameters that hold none of it
      toStored.foreach(back => assertEquals(codec.write(back(expected)), Right(n.original), path))
    }
    found.size

  /** The parameters within a Babbage `NewEpochState`: of its `ShelleyGovState` (`[proposals,
    * futureProposals, curPParams, prevPParams, futurePParams]`, cardano-ledger f649f975
    * `eras/shelley/impl/src/Cardano/Ledger/Shelley/Governance.hs:63-71`), the current and previous
    * parameters and those the future parameters predict, if any.
    */
  private def shelleyGovernance(size: Int)(nes: Node): Vector[Node] =
    val gov = Vector(3, 1, 1, 3).foldLeft(nes)((n, i) => items(n)(i))
    Vector(items(gov)(2), items(gov)(3)) ++ lists(items(gov)(4), size)

  /** Parameters whose every Babbage field is a value no recorded record holds, each within its
    * field's type.
    */
  private val babbageSentinel: ProtocolParams = ProtocolParams.empty.copy(
    minFeeA = 987654321,
    minFeeB = 987654323,
    maxBlockBodySize = 987659,
    maxTxSize = 98761,
    maxBlockHeaderSize = 9871,
    keyDeposit = 987654327,
    poolDeposit = 987654329,
    eMax = 9873,
    nOpt = 9877,
    a0 = Rational(7, 13),
    rho = Rational(5, 17),
    tau = Rational(3, 19),
    protocolMajor = 13,
    protocolMinor = 9879,
    minPoolCost = 987654331,
    coinsPerUTxOByte = 9883,
    costModels = Map(1 -> Vector(BigInt(-7))),
    priceMem = Rational(11, 23),
    priceSteps = Rational(13, 29),
    maxTxExUnits = ExUnits(9887, 9901),
    maxBlockExUnits = ExUnits(9907, 9923),
    maxValueSize = 9929,
    collateralPercentage = 9931,
    maxCollateralInputs = 9941
  )

  private def babbageParams(p: ProtocolParams): PParams[Babbage] =
    PParams[Babbage](right("Babbage's parameters")(BabbagePParams.fromStored(p)))

  test("the recorded Babbage state's parameter records are the era's, positionally"):
    val count = records(
      "fixtures/haskell-state/babbage-4000/state",
      5,
      StateLayout.Node11_1,
      ShelleyBasedEra.SBabbage,
      22,
      version(8),
      shelleyGovernance(22),
      Some(pp => {
        // the base differs from the record in every field `toStored` sets, so a field it
        // dropped keeps the base's value and the bytes differ
        val base = right("sentinel")(BabbagePParams.fromStored(babbageSentinel))
        pp.hkd.productIterator
          .zip(base.productIterator)
          .zipWithIndex
          .foreach { case ((field, sentinel), i) => assertNotEquals(field, sentinel, s"field $i") }
        BabbagePParams.toStored(pp.hkd, babbageSentinel)
      })
    )(babbageParams)
    // the current (version 7), the previous (6) and the future, voted for (8)
    assertEquals(count, 3)

  test("the recorded Babbage update proposal reads the typed way as the flat decoder stores it"):
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    val compared = loaded.corpora.filter(_.era == RefusalEra.Babbage).flatMap { c =>
      c.cases.flatMap { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val flat = right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Babbage))
        val body = items(right(s"$what cbor")(Cbor.decode(bytes)))(0)
        val key6 = body.value match
          case Value.Map(pairs) =>
            pairs.collectFirst { case (k, v) if k.value == Value.UInt(6) => v }
          case other => fail(s"$what: not a body: $other")
        key6.map { n =>
          val kept = right(s"$what typed")(KeptUpdate.decode[Babbage](n))
          assertEquals(Some(kept.stored), flat.body.update, what)
          assertEquals(
            KeptUpdate.fromStored[Babbage](kept.stored).map(_.update),
            Right(kept.update)
          )
          what
        }
      }
    }
    assertEquals(compared.size, 1, compared)

  /** Parameters whose every Alonzo field is a value no recorded record holds, each within its
    * field's type.
    */
  private val alonzoSentinel: ProtocolParams = babbageSentinel.copy(
    protocolMajor = 7,
    d = Rational(1, 7),
    extraEntropy = xyz.kd5ujc.scarab.ledger.Nonce.Hash(Bytes.fromArray(Array.fill(32)(7.toByte))),
    coinsPerUTxOWord = 9949
  )

  private def alonzoParams(p: ProtocolParams): PParams[Alonzo] =
    PParams[Alonzo](right("Alonzo's parameters")(AlonzoPParams.fromStored(p)))

  test("the recorded Alonzo state's parameter records are the era's, positionally"):
    val count = records(
      "fixtures/haskell-state/alonzo-3013/state",
      4,
      StateLayout.Node11_1,
      ShelleyBasedEra.SAlonzo,
      24,
      version(6),
      shelleyGovernance(24),
      Some(pp => {
        // the base differs from the record in every field `toStored` sets
        val base = right("sentinel")(AlonzoPParams.fromStored(alonzoSentinel))
        pp.hkd.productIterator
          .zip(base.productIterator)
          .zipWithIndex
          .foreach { case ((field, sentinel), i) => assertNotEquals(field, sentinel, s"field $i") }
        AlonzoPParams.toStored(pp.hkd, alonzoSentinel)
      })
    )(alonzoParams)
    // the current, the previous and the future parameters
    assertEquals(count, 3)

  test(
    "every Alonzo state of the era walk holds parameters the era reads, and writes as the state's codec"
  ):
    val codec = Params.codecIn(StateLayout.Node11_1, ShelleyBasedEra.SAlonzo)
    val states = xyz.kd5ujc.scarab.node.read.ErasStates.samples
      .filter(_.era == Era.Alonzo)
      .flatMap(s => xyz.kd5ujc.scarab.consensus.state.MirrorStep.nesOf(s.mirror))
      .map { case i: xyz.kd5ujc.scarab.ledger.rules.EraNes.InEra[e] =>
        xyz.kd5ujc.scarab.ledger.state.NewEpochState
          .retype(i.era, ShelleyBasedEra.SAlonzo)(i.nes)
          .getOrElse(fail("not Alonzo"))
      }
    val records = states.flatMap { nes =>
      val gov = nes.es.ls.utxo.gov
      Vector(gov.curPParams, gov.prevPParams) ++ gov.futurePParams.nextParams.toVector
    }
    val versions = records.map { p =>
      val pp = alonzoParams(p)
      assertEquals(AlonzoPParams.toStored(pp.hkd, p), p)
      // written over parameters that hold none of it, and read back
      assertEquals(alonzoParams(AlonzoPParams.toStored(pp.hkd, alonzoSentinel)), pp)
      assertEquals(Right(PParams.encodePParams(pp).bytes), codec.write(p))
      (p.protocolMajor, p.protocolMinor)
    }
    // the current, previous and future parameters of 192 states, at the versions of the walk from Mary's
    // last to the one Alonzo's last epoch voted for
    assertEquals(
      (states.size, records.size, versions.distinct.sorted),
      (192, 571, Vector[(BigInt, BigInt)]((4, 0), (5, 0), (6, 0), (7, 0)))
    )
    // the proposals each state holds, as the node stores them, read into the era with their
    // bytes kept
    val proposals = states.flatMap { nes =>
      val gov = nes.es.ls.utxo.gov
      Vector(gov.curProposals, gov.futureProposals).filter(_.nonEmpty).map { ps =>
        val stored =
          xyz.kd5ujc.scarab.ledger.Update(ps.map((k, u) => k.bytes -> u).toMap, BigInt(0))
        val kept = right("stored proposals")(KeptUpdate.fromStored[Alonzo](stored))
        assertEquals(kept.stored, stored)
        appliedAlike("era walk", gov.curPParams, ps, kept)
        given DecodeAt = DecodeAt(version(5), DecodeField.ParameterUpdate)
        kept.update.proposals.updates.values.foreach(u =>
          assertEquals(
            PParamsUpdate.decodePParamsUpdate[Alonzo](
              right("cbor")(Cbor.decode(PParamsUpdate.encodePParamsUpdate(u).bytes))
            ),
            Right(u)
          )
        )
        kept.update.proposals.updates.size
      }
    }
    // and the outputs of their UTxO sets, read as Alonzo's and written back
    val outputs = states.flatMap(_.es.ls.utxo.utxo.utxo.valuesIterator).distinct
    val written = outputs.count { out =>
      val era = right("output")(alonzo.AlonzoTxOut.fromStored(out))
      assertEquals(alonzo.AlonzoTxOut.toStored(era), out)
      EncCBOR[alonzo.AlonzoTxOut].encCBOR(era).bytes == out.raw
    }
    // 187 proposal maps, of 561 delegates' updates; the 5 distinct outputs the walk's UTxO sets hold
    assertEquals((proposals.size, proposals.sum, outputs.size, written), (187, 561, 5, 5))

  /** Each delegate's typed update applied to `base` as Alonzo applies it, against the flat rule's
    * application of the bytes the node stores it by: an update the stored form read wrongly, or
    * read as empty, gives another record unless each field it loses already held its value.
    */
  private def appliedAlike(
      what: String,
      base: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      ps: scala.collection.immutable.SortedMap[
        xyz.kd5ujc.scarab.ledger.state.KeyHash[xyz.kd5ujc.scarab.ledger.state.Genesis],
        xyz.kd5ujc.scarab.ledger.ParamUpdate
      ],
      kept: KeptUpdate[Alonzo]
  ): Unit =
    val pp = PParams[Alonzo](right(s"$what base")(AlonzoPParams.fromStored(base)))
    assertEquals(kept.update.proposals.updates.keySet, ps.keySet, what)
    ps.foreach { (k, stored) =>
      val typed = kept.update.proposals.updates.getOrElse(k, fail(s"$what: no update of $k"))
      assertEquals(
        Right(
          AlonzoPParams.toStored(EraPParams.alonzo.applyPPUpdates(pp, typed).hkd, base)
        ),
        xyz.kd5ujc.scarab.ledger.ProtocolParams.update(base, stored)(using ShelleyBasedEra.SAlonzo),
        what
      )
    }

  private def dijkstraParams(p: ProtocolParams): PParams[Dijkstra] =
    PParams[Dijkstra](right("Dijkstra's parameters")(DijkstraPParams.fromStored(p)))

  private def conwayParams(p: ProtocolParams): PParams[Conway] =
    PParams[Conway](right("Conway's parameters")(ConwayPParams.fromStored(p)))

  test("the recorded Dijkstra states' parameter records are the era's, positionally"):
    Vector("dijkstra-203" -> 4, "testnet-2168639" -> 4).foreach { (name, expected) =>
      val count = records(
        s"fixtures/leios/state/$name/state",
        7,
        StateLayout.Leios,
        ShelleyBasedEra.SDijkstra,
        54,
        DecoderVersion.Dijkstra
      )(dijkstraParams)
      assertEquals(count, expected, name)
    }

  test("the recorded Conway states' parameter records are the era's, positionally"):
    Vector(
      ("fixtures/leios/state/conway-195/state", StateLayout.Leios, 4),
      ("fixtures/haskell-state/conway-4593/state", StateLayout.Node11_1, 4)
    ).foreach { (path, layout, expected) =>
      val count =
        records(path, 6, layout, ShelleyBasedEra.SConway, 31, version(9))(conwayParams)
      assertEquals(count, expected, path)
    }
