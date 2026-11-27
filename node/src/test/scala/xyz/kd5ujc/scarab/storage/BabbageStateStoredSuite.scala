// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Monoid
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.consensus.state.MirrorStep
import xyz.kd5ujc.scarab.ledger.{ParamUpdate, ProtocolParams}
import xyz.kd5ujc.scarab.ledger.era.{
  EraAccounts,
  EraCertState,
  EraGov,
  EraPParams,
  KeptPParamsUpdate,
  Obligations,
  PParamsUpdate,
  ShelleyEraAccounts,
  ShelleyGovStateRecord
}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbagePParams
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, EraNes, ShelleyCertOps}
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  CompactCoin,
  FuturePParams as StoredFuturePParams,
  NewEpochState,
  ShelleyBasedEra,
  ShelleyGovState as StoredShelleyGovState,
  Sorted
}
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.node.read.ErasStates
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellState,
  HaskellStateCodecs,
  NativeStateCodecs,
  ReferenceLayout
}

/** Babbage's state classes over every recorded Babbage state the repository holds, against the flat
  * state the node keeps: the Haskell state `babbage-4000`, the native V10 snapshots `babbage-541`
  * and `babbage-727`, and every state of the era-walking capture after a Babbage block
  * ([[ErasStates]]).
  *
  *   - The governance state is read into Babbage's record (`ShelleyGovStateRecord.fromStored`) and
  *     written back identical (`toStored`): the flat record, which holds every era's parameter
  *     fields, and the state's bytes the codec writes. Written back over parameters that hold none
  *     of Babbage's values (a sentinel), each record has every Babbage field of the flat one, so a
  *     field the glue dropped would show. Each proposed update keeps its stored bytes, which a
  *     proposal in a non-canonical form shows, and applied to the current parameters it sets what
  *     the flat path's update sets.
  *   - The accounts read through `EraAccounts[Babbage]` and `ShelleyEraAccounts[Babbage]` are the
  *     flat ones, and unregistering and registering each account as the reference's functions do
  *     gives the accounts and pools the flat DELEG's functions give.
  *
  * Each corpus's counts are asserted, so that a state holding nothing is seen as such.
  */
class BabbageStateStoredSuite extends FunSuite:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def at(n: Node, path: Int*): Node = path.foldLeft(n)((m, i) => items(m)(i))

  private def decode(bytes: Bytes): Node = right("CBOR")(Cbor.decode(bytes, HaskellState.limits))

  /** The current era's index in the telescope (`[1, [telescope, ...]]`) and its `NewEpochState`. */
  private def current(root: Node): (Int, Node) =
    val telescope = items(at(root, 1, 0))
    val index = telescope.size - 1
    (index, at(root, 1, 0, index, 1, 1, 1))

  /** Parameters whose every Babbage field is a value no recorded record holds, each within its
    * field's type, as `EraPParamsStoredSuite` 's.
    */
  private val sentinel: ProtocolParams = ProtocolParams.empty.copy(
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

  /** Babbage's fields of the node's parameters (`Babbage/PParams.hs:89-136`, cardano-ledger
    * f649f975), the protocol version as its two fields.
    */
  private def babbageFields(p: ProtocolParams): Vector[(String, String)] = Vector(
    "minFeeA" -> p.minFeeA.toString,
    "minFeeB" -> p.minFeeB.toString,
    "maxBlockBodySize" -> p.maxBlockBodySize.toString,
    "maxTxSize" -> p.maxTxSize.toString,
    "maxBlockHeaderSize" -> p.maxBlockHeaderSize.toString,
    "keyDeposit" -> p.keyDeposit.toString,
    "poolDeposit" -> p.poolDeposit.toString,
    "eMax" -> p.eMax.toString,
    "nOpt" -> p.nOpt.toString,
    "a0" -> p.a0.toString,
    "rho" -> p.rho.toString,
    "tau" -> p.tau.toString,
    "protocolMajor" -> p.protocolMajor.toString,
    "protocolMinor" -> p.protocolMinor.toString,
    "minPoolCost" -> p.minPoolCost.toString,
    "coinsPerUTxOByte" -> p.coinsPerUTxOByte.toString,
    "costModels" -> p.costModels.toString,
    "priceMem" -> p.priceMem.toString,
    "priceSteps" -> p.priceSteps.toString,
    "maxTxExUnits" -> p.maxTxExUnits.toString,
    "maxBlockExUnits" -> p.maxBlockExUnits.toString,
    "maxValueSize" -> p.maxValueSize.toString,
    "collateralPercentage" -> p.collateralPercentage.toString,
    "maxCollateralInputs" -> p.maxCollateralInputs.toString
  )

  test("the sentinel differs from every recorded record in every Babbage field") {
    // checked per record below too; here the sentinel against Babbage's empty parameters
    val empty = BabbagePParams.toStored(BabbagePParams.empty, ProtocolParams.empty)
    babbageFields(sentinel).zip(babbageFields(empty)).foreach((s, e) => assertNotEquals(s, e))
  }

  /** What a state held: its proposed updates (this epoch's, the next's), whether its future
    * parameters are a prediction (`DefinitePParamsUpdate` or `PotentialPParamsUpdate`, of
    * parameters or of none), its accounts and their pointers.
    */
  final case class Counts(
      cur: Int,
      future: Int,
      futureParams: Int,
      accounts: Int,
      pointers: Int
  )

  private val noUpdate = Counts(0, 0, 0, 0, 0)

  private def plus(a: Counts, b: Counts): Counts =
    Counts(
      a.cur + b.cur,
      a.future + b.future,
      a.futureParams + b.futureParams,
      a.accounts + b.accounts,
      a.pointers + b.pointers
    )

  /** The governance state of `nes` read into Babbage's record and back, field by field against the
    * flat record; its counts.
    */
  private def governance(what: String, nes: NewEpochState[Babbage]): Counts =
    val g: StoredShelleyGovState = nes.es.ls.utxo.gov
    val era = right(s"$what read")(ShelleyGovStateRecord.fromStored[Babbage](g))
    // the inverse of the read, every era's fields of each record kept
    assertEquals(ShelleyGovStateRecord.toStored[Babbage](era, g), g, what)
    // over a base that holds none of Babbage's values, each record has the flat one's Babbage
    // fields, read through the class's lenses
    val sentinelBase = StoredShelleyGovState(
      Sorted.empty,
      Sorted.empty,
      sentinel,
      sentinel,
      StoredFuturePParams.map(g.futurePParams)(_ => sentinel)
    )
    val over = ShelleyGovStateRecord.toStored[Babbage](era, sentinelBase)
    val gov = EraGov.babbage
    val r = ShelleyGovStateRecord.babbage
    Vector(
      (r.storedPParams(gov.curPParamsGovStateL.get(era), sentinel), over.curPParams, g.curPParams),
      (
        r.storedPParams(gov.prevPParamsGovStateL.get(era), sentinel),
        over.prevPParams,
        g.prevPParams
      )
    ).foreach { (viaLens, viaRecord, flat) =>
      babbageFields(flat)
        .zip(babbageFields(sentinel))
        .foreach((f, s) => assertNotEquals(f, s, s"$what: the sentinel holds a recorded value"))
      assertEquals(babbageFields(viaLens), babbageFields(flat), what)
      assertEquals(babbageFields(viaRecord), babbageFields(flat), what)
    }
    assertEquals(over.futurePParams.ordinal, g.futurePParams.ordinal, what)
    assertEquals(
      over.futurePParams.nextParams.map(babbageFields),
      g.futurePParams.nextParams.map(babbageFields),
      what
    )
    // `nextEpochPParams`: the known future parameters, else the current ones
    assertEquals(
      babbageFields(r.storedPParams(EraGov.nextEpochPParams[Babbage](era), sentinel)),
      babbageFields(g.futurePParams.definite.getOrElse(g.curPParams)),
      what
    )
    assertEquals(gov.obligationGovState(era), Monoid[Obligations].empty, what)
    // each proposed update keeps its stored bytes, and applied to the current parameters sets what
    // the flat path's update sets
    Vector(era.sgsCurProposals -> g.curProposals, era.sgsFutureProposals -> g.futureProposals)
      .foreach { (kept, stored) =>
        assertEquals(kept.updates.keySet, stored.keySet, what)
        assertEquals(kept.proposed.updates.keySet, stored.keySet, what)
        kept.updates.foreach { (genesis, u) =>
          assertEquals(u.stored, stored(genesis), what)
          // a recorded state's updates were written by the reference, canonically
          assertEquals(
            PParamsUpdate.encodePParamsUpdate(u.update)(using EraPParams.babbage).bytes,
            right(what)(HaskellStateCodecs.paramUpdate.write(stored(genesis))),
            what
          )
          val flat = right(s"$what flat update")(
            ProtocolParams.update[Babbage](g.curPParams, stored(genesis))(using
              ShelleyBasedEra.SBabbage
            )
          )
          val typed = r.storedPParams(
            EraPParams.babbage.applyPPUpdates(era.sgsCurPParams, u.update),
            g.curPParams
          )
          assertEquals(typed, flat, what)
        }
      }
    Counts(
      g.curProposals.size,
      g.futureProposals.size,
      g.futurePParams match
        case StoredFuturePParams.NoPParamsUpdate => 0
        case StoredFuturePParams.DefinitePParamsUpdate(_) |
            StoredFuturePParams.PotentialPParamsUpdate(_) =>
          1
      ,
      0,
      0
    )

  /** The accounts of `nes` read through Babbage's accounts classes, and each account unregistered
    * and registered again by the reference's functions, against the flat DELEG's; their counts.
    */
  private def accounts(what: String, nes: NewEpochState[Babbage]): Counts =
    val ls = nes.es.ls
    val cs = ls.cert
    val acc = cs.dState.accounts
    val a = EraAccounts.babbage
    assertEquals(a.accountsMapL.get(acc), acc.states, what)
    assertEquals(a.accountsPtrsMapG.get(acc), acc.ptrs, what)
    assertEquals(
      EraAccounts.sumDepositsAccounts[Babbage](acc).value,
      EraLedger.keyDeposits(ls)(using ShelleyBasedEra.SBabbage),
      what
    )
    assertEquals(
      EraAccounts.sumBalancesAccounts[Babbage](acc).value,
      CompactCoin.sum(acc.states.valuesIterator.map(_.balance)).toBigInt,
      what
    )
    acc.states.foreach { (c, s) =>
      assertEquals(acc.ptrs.get(a.ptrAccountStateG.get(s)), Some(c), what)
      assertEquals(EraAccounts.lookupStakePoolDelegation[Babbage](c, acc), s.pool, what)
      val (found, without) = ShelleyEraAccounts.unregisterShelleyAccount[Babbage](c, acc)
      assertEquals(found, Some(s), what)
      assertEquals(a.unregisterAccount(c, acc), (found, without), what)
      val flat = ShelleyCertOps.unregister(cs, c, s)
      assertEquals(without, flat.dState.accounts, what)
      // the account's pointer goes with it
      assertEquals(without.ptrs.size, acc.ptrs.size - 1, what)
      assertEquals(
        EraCertState.unDelegReDelegStakePool[Babbage](c, s, None, cs.pState),
        flat.pState,
        what
      )
      val again =
        ShelleyEraAccounts.registerShelleyAccount[Babbage](c, s.ptr, s.deposit, None, without)
      assertEquals(again, ShelleyCertOps.register(flat, c, s.ptr, s.deposit).dState.accounts, what)
    }
    // an account not registered is not found, and nothing changes
    acc.states.headOption.foreach { (c, _) =>
      val (_, without) = ShelleyEraAccounts.unregisterShelleyAccount[Babbage](c, acc)
      assertEquals(
        ShelleyEraAccounts.unregisterShelleyAccount[Babbage](c, without),
        (None, without),
        what
      )
    }
    Counts(0, 0, 0, acc.states.size, acc.ptrs.size)

  private def withGov(
      nes: NewEpochState[Babbage],
      g: StoredShelleyGovState
  ): NewEpochState[Babbage] =
    nes.copy(es = nes.es.copy(ls = nes.es.ls.copy(utxo = nes.es.ls.utxo.copy[Babbage](gov = g))))

  /** A state whose bytes the codec read: read through the classes, and its governance state written
    * back over itself and over the sentinel's records (whose Babbage fields the codec writes are
    * those of the era's record), each written by the codec as the state's bytes.
    */
  private def stored(what: String, codec: Codec[NewEpochState[Babbage]], node: Node): Counts =
    val nes = right(s"$what decode")(codec.decode(node))
    val g = nes.es.ls.utxo.gov
    val era = right(s"$what read")(ShelleyGovStateRecord.fromStored[Babbage](g))
    val sentinelBase = g.copy(
      curPParams = sentinel,
      prevPParams = sentinel,
      futurePParams = StoredFuturePParams.map(g.futurePParams)(_ => sentinel)
    )
    Vector(g, sentinelBase).foreach(base =>
      assertEquals(
        codec.write(withGov(nes, ShelleyGovStateRecord.toStored[Babbage](era, base))),
        Right(node.original),
        what
      )
    )
    plus(governance(what, nes), accounts(what, nes))

  test("the recorded Haskell Babbage state reads through the classes and is written back") {
    val path = "fixtures/haskell-state/babbage-4000/state"
    val (index, nes) =
      current(decode(Bytes.wrap(Files.readAllBytes(Path.of(path)))))
    assertEquals(index, 5, path)
    assertEquals(
      stored(path, HaskellStateCodecs.newEpochState[Babbage], nes),
      Counts(3, 0, 1, 3, 3)
    )
  }

  test("the native V10 Babbage snapshots read through the classes and are written back") {
    val read = Vector("babbage-541", "babbage-727").map { name =>
      val path = s"fixtures/snapshot-v10/eras/$name.bin"
      val content =
        right(path)(FileLedgerSnapshots.verified(Files.readAllBytes(Path.of(path))))
      val (_, _, _, haskell) =
        right(path)(SnapshotV10.sections(content, ReferenceLayout.Release11_1))
      val (index, nes) = current(decode(haskell))
      assertEquals(index, 5, path)
      name -> stored(path, NativeStateCodecs.newEpochState[Babbage], nes)
    }.toMap
    assertEquals(
      read,
      Map("babbage-541" -> Counts(0, 0, 1, 3, 3), "babbage-727" -> Counts(3, 0, 1, 3, 3))
    )
  }

  test("every Babbage state of the era-walking capture reads through the classes") {
    val states = ErasStates.samples
      .filter(_.era == Era.Babbage)
      .flatMap(s => MirrorStep.nesOf(s.mirror))
      .map { case i: EraNes.InEra[e] =>
        NewEpochState.retype(i.era, ShelleyBasedEra.SBabbage)(i.nes).getOrElse(fail("not Babbage"))
      }
    val counts = states.zipWithIndex
      .map((nes, i) => plus(governance(s"state $i", nes), accounts(s"state $i", nes)))
    assertEquals(states.size, 188)
    // 185 states hold the three delegates' proposal and the parameters it makes, the other three a
    // potential update of no parameters; none holds a proposal for the next epoch
    assertEquals(counts.foldLeft(noUpdate)(plus), Counts(555, 0, 188, 564, 564))
  }

  /** The node's parameter records each with other eras' fields of their own: the recorded states
    * hold the same in every place, so this is what shows each record written back over its own
    * place's record and not another's.
    */
  test("each parameter record is written back over the record of its own place") {
    def place(n: Int): ProtocolParams =
      sentinel.copy(coinsPerUTxOWord = n, drepDeposit = 10 * n, govActionDeposit = 100 * n)
    val r = ShelleyGovStateRecord.babbage
    val futures = Vector(
      StoredFuturePParams.NoPParamsUpdate,
      StoredFuturePParams.PotentialPParamsUpdate(None),
      StoredFuturePParams.PotentialPParamsUpdate(Some(place(3))),
      StoredFuturePParams.DefinitePParamsUpdate(place(3))
    )
    futures.foreach { future =>
      val base = StoredShelleyGovState(Sorted.empty, Sorted.empty, place(1), place(2), future)
      val era = right(s"$future")(ShelleyGovStateRecord.fromStored[Babbage](base))
      assertEquals(ShelleyGovStateRecord.toStored[Babbage](era, base), base, s"$future")
      // a base with the current and previous records swapped is written back as itself, not as
      // the state read: the other eras' fields come from the base's place
      val swapped = base.copy(curPParams = place(2), prevPParams = place(1))
      val back = ShelleyGovStateRecord.toStored[Babbage](era, swapped)
      assertEquals(back.curPParams.coinsPerUTxOWord, BigInt(2), s"$future")
      assertEquals(back.prevPParams.coinsPerUTxOWord, BigInt(1), s"$future")
      assertNotEquals(back, base, s"$future")
    }
    // future parameters where the base has none are written over its current record, from which
    // the flat rules make them
    val noFuture = StoredShelleyGovState(
      Sorted.empty,
      Sorted.empty,
      place(1),
      place(2),
      StoredFuturePParams.NoPParamsUpdate
    )
    val era = right("no future")(ShelleyGovStateRecord.fromStored[Babbage](noFuture))
    val predicted = EraGov.babbage.futurePParamsGovStateL.replace(
      xyz.kd5ujc.scarab.ledger.era.FuturePParams.DefinitePParamsUpdate(era.sgsPrevPParams)
    )(era)
    assertEquals(
      ShelleyGovStateRecord.toStored[Babbage](predicted, noFuture).futurePParams,
      StoredFuturePParams.DefinitePParamsUpdate(r.storedPParams(era.sgsPrevPParams, place(1)))
    )
  }

  /** A proposal of a field in a form other than the canonical one: the bytes the node keeps are
    * kept, where a re-encoding of the update would write others.
    */
  test("a proposed update in a non-canonical form is written back as it is stored") {
    val canonical = ParamUpdate(Map(0 -> right("hex")(Bytes.fromHex("182c"))))
    val wide = ParamUpdate(Map(0 -> right("hex")(Bytes.fromHex("1a0000002c"))))
    val readCanonical = right("canonical")(KeptPParamsUpdate.fromStored[Babbage](canonical))
    val readWide = right("wide")(KeptPParamsUpdate.fromStored[Babbage](wide))
    // the same update, with the bytes each was read from
    assertEquals(readWide, readCanonical)
    assertEquals(readWide.stored, wide)
    // a re-encoding writes the canonical form, not the stored one
    assertEquals(
      PParamsUpdate.encodePParamsUpdate(readWide.update)(using EraPParams.babbage).bytes,
      right("canonical map")(HaskellStateCodecs.paramUpdate.write(canonical))
    )
    assertNotEquals(
      right("wide map")(HaskellStateCodecs.paramUpdate.write(wide)),
      right("canonical map")(HaskellStateCodecs.paramUpdate.write(canonical))
    )
    val genesis = right("genesis key hash")(
      xyz.kd5ujc.scarab.ledger.state.KeyHash
        .fromBytes[xyz.kd5ujc.scarab.ledger.state.Genesis](Bytes(Vector.fill[Byte](28)(7)))
    )
    val base = StoredShelleyGovState(
      Sorted.map(Vector(genesis -> wide)),
      Sorted.map(Vector(genesis -> wide)),
      ProtocolParams.empty,
      ProtocolParams.empty,
      StoredFuturePParams.NoPParamsUpdate
    )
    val era = right("read")(ShelleyGovStateRecord.fromStored[Babbage](base))
    assertEquals(ShelleyGovStateRecord.toStored[Babbage](era, base), base)
  }
