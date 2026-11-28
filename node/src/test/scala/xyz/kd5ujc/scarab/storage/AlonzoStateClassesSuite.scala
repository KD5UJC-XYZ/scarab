// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Monoid
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.consensus.state.MirrorStep
import scala.collection.immutable.TreeMap
import xyz.kd5ujc.scarab.ledger.{ParamUpdate, ProtocolParams, Update as StoredUpdate}
import xyz.kd5ujc.scarab.ledger.era.{
  EraAccounts,
  EraCertState,
  EraGov,
  EraPParams,
  KeptPParamsUpdate,
  KeptUpdate,
  Obligations,
  PParamsUpdate,
  ShelleyEraAccounts,
  ShelleyGovStateReadError,
  ShelleyGovStateRecord,
  ShelleyProposals
}
import xyz.kd5ujc.scarab.ledger.era.alonzo.AlonzoPParams
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, EraNes, ShelleyCertOps}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Coin,
  CompactCoin,
  FuturePParams as StoredFuturePParams,
  Genesis,
  KeyHash,
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

/** Alonzo's state classes over every recorded Alonzo state the repository holds, against the flat
  * state the node keeps: the Haskell state `alonzo-3013`, the native V10 snapshots `alonzo-349`,
  * `alonzo-539` and `preview-4320`, and every state of the era-walking capture after an Alonzo
  * block ([[ErasStates]]).
  *
  *   - The governance state is read into Alonzo's record (`ShelleyGovStateRecord.fromStored`) and
  *     written back identical (`toStored`): the flat record, which holds every era's parameter
  *     fields, and the state's bytes the codec writes. Written back over parameters that hold none
  *     of Alonzo's values (a sentinel), each record has every Alonzo field of the flat one, so a
  *     field the glue dropped would show. Each proposed update keeps its stored bytes, which a
  *     proposal in a non-canonical form shows, and applied to the current parameters it sets what
  *     the flat path's update sets.
  *   - The accounts read through `EraAccounts[Alonzo]` and `ShelleyEraAccounts[Alonzo]` are the
  *     flat ones, the deposits and obligations through `EraCertState[Alonzo]` the flat ones, and
  *     unregistering and registering each account as the reference's functions do gives the
  *     accounts and pools the flat DELEG's functions give.
  *
  * Each corpus's counts are asserted, so that a state holding nothing is seen as such.
  */
class AlonzoStateClassesSuite extends FunSuite:
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

  /** Parameters whose every Alonzo field is a value no recorded record holds, each within its
    * field's type, as `EraPParamsStoredSuite` 's (`alonzoSentinel`).
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
    d = Rational(1, 7),
    extraEntropy = xyz.kd5ujc.scarab.ledger.Nonce.Hash(Bytes.fromArray(Array.fill(32)(7.toByte))),
    protocolMajor = 7,
    protocolMinor = 9879,
    minPoolCost = 987654331,
    coinsPerUTxOWord = 9949,
    costModels = Map(1 -> Vector(BigInt(-7))),
    priceMem = Rational(11, 23),
    priceSteps = Rational(13, 29),
    maxTxExUnits = ExUnits(9887, 9901),
    maxBlockExUnits = ExUnits(9907, 9923),
    maxValueSize = 9929,
    collateralPercentage = 9931,
    maxCollateralInputs = 9941
  )

  /** Alonzo's fields of the node's parameters (`Alonzo/PParams.hs:228-284`, cardano-ledger
    * f649f975), the protocol version as its two fields.
    */
  private def alonzoFields(p: ProtocolParams): Vector[(String, String)] = Vector(
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
    "d" -> p.d.toString,
    "extraEntropy" -> p.extraEntropy.toString,
    "protocolMajor" -> p.protocolMajor.toString,
    "protocolMinor" -> p.protocolMinor.toString,
    "minPoolCost" -> p.minPoolCost.toString,
    "coinsPerUTxOWord" -> p.coinsPerUTxOWord.toString,
    "costModels" -> p.costModels.toString,
    "priceMem" -> p.priceMem.toString,
    "priceSteps" -> p.priceSteps.toString,
    "maxTxExUnits" -> p.maxTxExUnits.toString,
    "maxBlockExUnits" -> p.maxBlockExUnits.toString,
    "maxValueSize" -> p.maxValueSize.toString,
    "collateralPercentage" -> p.collateralPercentage.toString,
    "maxCollateralInputs" -> p.maxCollateralInputs.toString
  )

  test("the sentinel differs from every recorded record in every Alonzo field") {
    // checked per record below too; here the sentinel against Alonzo's empty parameters
    val empty = AlonzoPParams.toStored(AlonzoPParams.empty, ProtocolParams.empty)
    alonzoFields(sentinel).zip(alonzoFields(empty)).foreach((s, e) => assertNotEquals(s, e))
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

  /** The form of a state's future parameters. */
  enum Prediction:
    case NoUpdate, Definite, Potential, PotentialOfNone

  private def prediction(f: StoredFuturePParams): Prediction = f match
    case StoredFuturePParams.NoPParamsUpdate                 => Prediction.NoUpdate
    case StoredFuturePParams.DefinitePParamsUpdate(_)        => Prediction.Definite
    case StoredFuturePParams.PotentialPParamsUpdate(Some(_)) => Prediction.Potential
    case StoredFuturePParams.PotentialPParamsUpdate(None)    => Prediction.PotentialOfNone

  /** How many states hold each number of this epoch's proposals and each form of prediction. */
  private def histograms(
      states: Vector[NewEpochState[Alonzo]]
  ): (Map[Int, Int], Map[Prediction, Int]) =
    (
      states.groupMapReduce(_.es.ls.utxo.gov.curProposals.size)(_ => 1)(_ + _),
      states.groupMapReduce(nes => prediction(nes.es.ls.utxo.gov.futurePParams))(_ => 1)(_ + _)
    )

  private def plus(a: Counts, b: Counts): Counts =
    Counts(
      a.cur + b.cur,
      a.future + b.future,
      a.futureParams + b.futureParams,
      a.accounts + b.accounts,
      a.pointers + b.pointers
    )

  /** The reference's proposed updates as ordered pairs, as `Map.toList` gives them. */
  private def proposedPairs(
      p: xyz.kd5ujc.scarab.ledger.era.ProposedPPUpdates[Alonzo]
  ): Vector[(KeyHash[Genesis], xyz.kd5ujc.scarab.ledger.era.PParamsUpdate[Alonzo])] =
    p.updates.toVector

  /** The stored proposals read by the update proposal's own reader ([[KeptUpdate.fromStored]]), in
    * the order of the delegates' key hashes.
    */
  private def storedPairs(
      what: String,
      stored: scala.collection.immutable.SortedMap[KeyHash[Genesis], ParamUpdate]
  ): Vector[(KeyHash[Genesis], xyz.kd5ujc.scarab.ledger.era.PParamsUpdate[Alonzo])] =
    val read = right(s"$what KeptUpdate")(
      KeptUpdate.fromStored[Alonzo](
        StoredUpdate(TreeMap.from(stored.map((k, u) => k.bytes -> u)), BigInt(0))
      )
    ).update.proposals.updates
    stored.keys.toVector.map(k => k -> read.getOrElse(k, fail(s"$what: $k not read")))

  /** The governance state of `nes` read into Alonzo's record and back, field by field against the
    * flat record; its counts.
    */
  private def governance(what: String, nes: NewEpochState[Alonzo]): Counts =
    val g: StoredShelleyGovState = nes.es.ls.utxo.gov
    val era = right(s"$what read")(ShelleyGovStateRecord.fromStored[Alonzo](g))
    // the inverse of the read, every era's fields of each record kept
    assertEquals(ShelleyGovStateRecord.toStored[Alonzo](era, g), g, what)
    // over a base that holds none of Alonzo's values, each record has the flat one's Alonzo
    // fields, read through the class's lenses
    val sentinelBase = StoredShelleyGovState(
      Sorted.empty,
      Sorted.empty,
      sentinel,
      sentinel,
      StoredFuturePParams.map(g.futurePParams)(_ => sentinel)
    )
    val over = ShelleyGovStateRecord.toStored[Alonzo](era, sentinelBase)
    val gov = EraGov.alonzo
    val r = ShelleyGovStateRecord.alonzo
    Vector(
      (r.storedPParams(gov.curPParamsGovStateL.get(era), sentinel), over.curPParams, g.curPParams),
      (
        r.storedPParams(gov.prevPParamsGovStateL.get(era), sentinel),
        over.prevPParams,
        g.prevPParams
      )
    ).foreach { (viaLens, viaRecord, flat) =>
      alonzoFields(flat)
        .zip(alonzoFields(sentinel))
        .foreach((f, s) => assertNotEquals(f, s, s"$what: the sentinel holds a recorded value"))
      assertEquals(alonzoFields(viaLens), alonzoFields(flat), what)
      assertEquals(alonzoFields(viaRecord), alonzoFields(flat), what)
    }
    assertEquals(over.futurePParams.ordinal, g.futurePParams.ordinal, what)
    assertEquals(
      over.futurePParams.nextParams.map(alonzoFields),
      g.futurePParams.nextParams.map(alonzoFields),
      what
    )
    // `nextEpochPParams`: the known future parameters, else the current ones
    assertEquals(
      alonzoFields(r.storedPParams(EraGov.nextEpochPParams[Alonzo](era), sentinel)),
      alonzoFields(g.futurePParams.definite.getOrElse(g.curPParams)),
      what
    )
    assertEquals(gov.obligationGovState(era), Monoid[Obligations].empty, what)
    // each proposed update keeps its stored bytes, and applied to the current parameters sets what
    // the flat path's update sets
    Vector(era.sgsCurProposals -> g.curProposals, era.sgsFutureProposals -> g.futureProposals)
      .foreach { (kept, stored) =>
        assertEquals(kept.updates.keySet, stored.keySet, what)
        assertEquals(proposedPairs(kept.proposed), storedPairs(what, stored), what)
        kept.updates.foreach { (genesis, u) =>
          assertEquals(u.stored, stored(genesis), what)
          // a recorded state's updates were written by the reference, canonically
          assertEquals(
            PParamsUpdate.encodePParamsUpdate(u.update)(using EraPParams.alonzo).bytes,
            right(what)(HaskellStateCodecs.paramUpdate.write(stored(genesis))),
            what
          )
          val flat = right(s"$what flat update")(
            ProtocolParams.update[Alonzo](g.curPParams, stored(genesis))(using
              ShelleyBasedEra.SAlonzo
            )
          )
          val typed = r.storedPParams(
            EraPParams.alonzo.applyPPUpdates(era.sgsCurPParams, u.update),
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

  /** The accounts of `nes` read through Alonzo's accounts classes, and each account unregistered
    * and registered again by the reference's functions, against the flat DELEG's; their counts.
    */
  private def accounts(what: String, nes: NewEpochState[Alonzo]): Counts =
    val ls = nes.es.ls
    val cs = ls.cert
    val acc = cs.dState.accounts
    val a = EraAccounts.alonzo
    assertEquals(a.accountsMapL.get(acc), acc.states, what)
    assertEquals(a.accountsPtrsMapG.get(acc), acc.ptrs, what)
    assertEquals(
      EraAccounts.sumDepositsAccounts[Alonzo](acc).value,
      EraLedger.keyDeposits(ls)(using ShelleyBasedEra.SAlonzo),
      what
    )
    assertEquals(
      EraAccounts.sumBalancesAccounts[Alonzo](acc).value,
      CompactCoin.sum(acc.states.valuesIterator.map(_.balance)).toBigInt,
      what
    )
    acc.states.foreach { (c, s) =>
      assertEquals(acc.ptrs.get(a.ptrAccountStateG.get(s)), Some(c), what)
      assertEquals(EraAccounts.lookupStakePoolDelegation[Alonzo](c, acc), s.pool, what)
      val (found, without) = ShelleyEraAccounts.unregisterShelleyAccount[Alonzo](c, acc)
      assertEquals(found, Some(s), what)
      assertEquals(a.unregisterAccount(c, acc), (found, without), what)
      val flat = ShelleyCertOps.unregister(cs, c, s)
      assertEquals(without, flat.dState.accounts, what)
      // the account's pointer goes with it
      assertEquals(without.ptrs.size, acc.ptrs.size - 1, what)
      assertEquals(
        EraCertState.unDelegReDelegStakePool[Alonzo](c, s, None, cs.pState),
        flat.pState,
        what
      )
      val again =
        ShelleyEraAccounts.registerShelleyAccount[Alonzo](c, s.ptr, s.deposit, None, without)
      assertEquals(again, ShelleyCertOps.register(flat, c, s.ptr, s.deposit).dState.accounts, what)
    }
    // an account not registered is not found, and nothing changes
    acc.states.headOption.foreach { (c, _) =>
      val (_, without) = ShelleyEraAccounts.unregisterShelleyAccount[Alonzo](c, acc)
      assertEquals(
        ShelleyEraAccounts.unregisterShelleyAccount[Alonzo](c, without),
        (None, without),
        what
      )
    }
    // the obligations of the certificate state: the accounts' deposits and the pools'
    assertEquals(
      EraCertState.alonzo.obligationCertState(cs),
      Obligations(
        Coin(EraLedger.keyDeposits(ls)(using ShelleyBasedEra.SAlonzo)),
        CompactCoin.sum(cs.pState.stakePools.valuesIterator.map(_.deposit)).toCoin,
        Coin.zero,
        Coin.zero
      ),
      what
    )
    assertEquals(EraCertState.alonzo.certDStateL.get(cs), cs.dState, what)
    assertEquals(EraCertState.alonzo.certPStateL.get(cs), cs.pState, what)
    Counts(0, 0, 0, acc.states.size, acc.ptrs.size)

  private def withGov(
      nes: NewEpochState[Alonzo],
      g: StoredShelleyGovState
  ): NewEpochState[Alonzo] =
    nes.copy(es = nes.es.copy(ls = nes.es.ls.copy(utxo = nes.es.ls.utxo.copy[Alonzo](gov = g))))

  /** A state whose bytes the codec read: read through the classes, and its governance state written
    * back over itself and over the sentinel's records (whose Alonzo fields the codec writes are
    * those of the era's record), each written by the codec as the state's bytes.
    */
  private def stored(what: String, codec: Codec[NewEpochState[Alonzo]], node: Node): Counts =
    val nes = right(s"$what decode")(codec.decode(node))
    val g = nes.es.ls.utxo.gov
    val era = right(s"$what read")(ShelleyGovStateRecord.fromStored[Alonzo](g))
    val sentinelBase = g.copy(
      curPParams = sentinel,
      prevPParams = sentinel,
      futurePParams = StoredFuturePParams.map(g.futurePParams)(_ => sentinel)
    )
    Vector(g, sentinelBase).foreach(base =>
      assertEquals(
        codec.write(withGov(nes, ShelleyGovStateRecord.toStored[Alonzo](era, base))),
        Right(node.original),
        what
      )
    )
    plus(governance(what, nes), accounts(what, nes))

  test("the recorded Haskell Alonzo state reads through the classes and is written back") {
    val path = "fixtures/haskell-state/alonzo-3013/state"
    val (index, nes) =
      current(decode(Bytes.wrap(Files.readAllBytes(Path.of(path)))))
    assertEquals(index, 4, path)
    assertEquals(
      stored(path, HaskellStateCodecs.newEpochState[Alonzo], nes),
      Counts(3, 0, 1, 3, 3)
    )
  }

  test("the native V10 Alonzo snapshots read through the classes and are written back") {
    val read = Vector("eras/alonzo-349", "eras/alonzo-539", "preview-4320").map { name =>
      val path = s"fixtures/snapshot-v10/$name.bin"
      val haskell = right(path)(
        SnapshotV10Sections.haskellState(
          Files.readAllBytes(Path.of(path)),
          ReferenceLayout.Release11_1
        )
      )
      val (index, nes) = current(decode(haskell))
      assertEquals(index, 4, path)
      name -> stored(path, NativeStateCodecs.newEpochState[Alonzo], nes)
    }.toMap
    // the three delegates' proposals in one of them; future parameters, three accounts and their
    // pointers in each
    assertEquals(
      read,
      Map(
        "eras/alonzo-349" -> Counts(0, 0, 1, 3, 3),
        "eras/alonzo-539" -> Counts(3, 0, 1, 3, 3),
        "preview-4320" -> Counts(0, 0, 1, 3, 3)
      )
    )
  }

  test("every Alonzo state of the era-walking capture reads through the classes") {
    val states = ErasStates.samples
      .filter(_.era == Era.Alonzo)
      .flatMap(s => MirrorStep.nesOf(s.mirror))
      .map { case i: EraNes.InEra[e] =>
        NewEpochState.retype(i.era, ShelleyBasedEra.SAlonzo)(i.nes).getOrElse(fail("not Alonzo"))
      }
    val counts = states.zipWithIndex
      .map((nes, i) => plus(governance(s"state $i", nes), accounts(s"state $i", nes)))
    assertEquals(states.size, 192)
    // 187 states hold the three delegates' proposal, the other five none; the future parameters are
    // potential in 131 states, definite (past the slot of no return) in 56 and a potential update of
    // no parameters in 5; none holds a proposal for the next epoch
    assertEquals(counts.foldLeft(noUpdate)(plus), Counts(561, 0, 192, 576, 576))
    assertEquals(
      histograms(states),
      (
        Map(3 -> 187, 0 -> 5),
        Map(
          Prediction.Potential -> 131,
          Prediction.Definite -> 56,
          Prediction.PotentialOfNone -> 5
        )
      )
    )
  }

  /** The node's parameter records each with other eras' fields of their own: the recorded states
    * hold the same in every place, so this is what shows each record written back over its own
    * place's record and not another's.
    */
  test("each parameter record is written back over the record of its own place") {
    def place(n: Int): ProtocolParams =
      sentinel.copy(coinsPerUTxOByte = n, drepDeposit = 10 * n, govActionDeposit = 100 * n)
    val r = ShelleyGovStateRecord.alonzo
    val futures = Vector(
      StoredFuturePParams.NoPParamsUpdate,
      StoredFuturePParams.PotentialPParamsUpdate(None),
      StoredFuturePParams.PotentialPParamsUpdate(Some(place(3))),
      StoredFuturePParams.DefinitePParamsUpdate(place(3))
    )
    futures.foreach { future =>
      val base = StoredShelleyGovState(Sorted.empty, Sorted.empty, place(1), place(2), future)
      val era = right(s"$future")(ShelleyGovStateRecord.fromStored[Alonzo](base))
      assertEquals(ShelleyGovStateRecord.toStored[Alonzo](era, base), base, s"$future")
      // a base with the current and previous records swapped is written back as itself, not as
      // the state read: the other eras' fields come from the base's place
      val swapped = base.copy(curPParams = place(2), prevPParams = place(1))
      val back = ShelleyGovStateRecord.toStored[Alonzo](era, swapped)
      assertEquals(back.curPParams.coinsPerUTxOByte, BigInt(2), s"$future")
      assertEquals(back.prevPParams.coinsPerUTxOByte, BigInt(1), s"$future")
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
    val era = right("no future")(ShelleyGovStateRecord.fromStored[Alonzo](noFuture))
    val predicted = EraGov.alonzo.futurePParamsGovStateL.replace(
      xyz.kd5ujc.scarab.ledger.era.FuturePParams.DefinitePParamsUpdate(era.sgsPrevPParams)
    )(era)
    assertEquals(
      ShelleyGovStateRecord.toStored[Alonzo](predicted, noFuture).futurePParams,
      StoredFuturePParams.DefinitePParamsUpdate(r.storedPParams(era.sgsPrevPParams, place(1)))
    )
  }

  /** Five or more delegates, each with a proposal of its own: the reference's proposed updates keep
    * the order of the key hashes, which a hash map of that size does not.
    */
  test("the proposed updates keep the delegates' order and each one's own update") {
    val proposals = (1 to 7).toVector.map { i =>
      val genesis = right("genesis key hash")(
        KeyHash.fromBytes[Genesis](Bytes(Vector.fill[Byte](27)(0) :+ (40 - i).toByte))
      )
      genesis -> ParamUpdate(Map(0 -> Bytes(Vector(0x18.toByte, (30 + i).toByte))))
    }
    val stored = Sorted.map(proposals)
    val g = StoredShelleyGovState(
      stored,
      Sorted.empty,
      sentinel,
      sentinel,
      StoredFuturePParams.NoPParamsUpdate
    )
    val era = right("read")(ShelleyGovStateRecord.fromStored[Alonzo](g))
    val pairs = proposedPairs(era.sgsCurProposals.proposed)
    assertEquals(pairs, storedPairs("seven", stored))
    // built in descending order of the key hashes
    assertEquals(
      pairs.map(_._1),
      proposals.map(_._1).sorted(using summon[cats.Order[KeyHash[Genesis]]].toOrdering)
    )
    assertNotEquals(pairs.map(_._1), proposals.map(_._1))
    // each delegate's own update, none of them empty
    assertEquals(pairs.map(_._2).distinct.size, 7)
    assert(!pairs.map(_._2).contains(EraPParams.alonzo.emptyPParamsUpdate))
  }

  /** A stored proposal with a key that is not one of Alonzo's update keys (15, the minimum output
    * value of the eras before it, `sppMinUTxOValue`, which Alonzo's parameters have not,
    * `Alonzo/PParams.hs:344-369`, cardano-ledger f649f975): the era's read refuses the proposal and
    * so the record.
    */
  test("a stored proposal with a key not of Alonzo's update is refused, the record with it") {
    val unread = ParamUpdate(
      Map(0 -> right("hex")(Bytes.fromHex("01")), 15 -> right("hex")(Bytes.fromHex("01")))
    )
    val refused = KeptPParamsUpdate.fromStored[Alonzo](unread)
    assert(refused.isLeft, refused.toString)
    val genesis =
      right("genesis key hash")(KeyHash.fromBytes[Genesis](Bytes(Vector.fill[Byte](28)(9))))
    val g = StoredShelleyGovState(
      Sorted.empty,
      Sorted.map(Vector(genesis -> unread)),
      sentinel,
      sentinel,
      StoredFuturePParams.NoPParamsUpdate
    )
    assertEquals(
      ShelleyGovStateRecord.fromStored[Alonzo](g),
      Left(
        ShelleyGovStateReadError.Proposal(
          ShelleyProposals.Future,
          genesis,
          refused.left.getOrElse(fail("not refused"))
        )
      )
    )
  }

  test("a delegate's proposal is read at Alonzo, an era up to Babbage, and not at Conway") {
    assertEquals(
      compileErrors("KeptPParamsUpdate.fromStored[Alonzo](ParamUpdate(Map.empty))"),
      ""
    )
    assert(
      compileErrors(
        "KeptPParamsUpdate.fromStored[xyz.kd5ujc.scarab.ledger.state.Conway](ParamUpdate(Map.empty))"
      ).nonEmpty
    )
  }

  /** A proposal of a field in a form other than the canonical one: the bytes the node keeps are
    * kept, where a re-encoding of the update would write others.
    */
  test("a proposed update in a non-canonical form is written back as it is stored") {
    val canonical = ParamUpdate(Map(0 -> right("hex")(Bytes.fromHex("182c"))))
    val wide = ParamUpdate(Map(0 -> right("hex")(Bytes.fromHex("1a0000002c"))))
    val readCanonical = right("canonical")(KeptPParamsUpdate.fromStored[Alonzo](canonical))
    val readWide = right("wide")(KeptPParamsUpdate.fromStored[Alonzo](wide))
    // the same update, with the bytes each was read from
    assertEquals(readWide, readCanonical)
    assertEquals(readWide.stored, wide)
    // a re-encoding writes the canonical form, not the stored one
    assertEquals(
      PParamsUpdate.encodePParamsUpdate(readWide.update)(using EraPParams.alonzo).bytes,
      right("canonical map")(HaskellStateCodecs.paramUpdate.write(canonical))
    )
    assertNotEquals(
      right("wide map")(HaskellStateCodecs.paramUpdate.write(wide)),
      right("canonical map")(HaskellStateCodecs.paramUpdate.write(canonical))
    )
    val genesis = right("genesis key hash")(
      KeyHash.fromBytes[Genesis](Bytes(Vector.fill[Byte](28)(7)))
    )
    val base = StoredShelleyGovState(
      Sorted.map(Vector(genesis -> wide)),
      Sorted.map(Vector(genesis -> wide)),
      ProtocolParams.empty,
      ProtocolParams.empty,
      StoredFuturePParams.NoPParamsUpdate
    )
    val era = right("read")(ShelleyGovStateRecord.fromStored[Alonzo](base))
    assertEquals(ShelleyGovStateRecord.toStored[Alonzo](era, base), base)
  }
