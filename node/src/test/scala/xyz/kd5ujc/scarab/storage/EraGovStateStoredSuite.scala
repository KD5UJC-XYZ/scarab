// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ConwayParamUpdate,
  Credential as FlatCredential,
  DecoderVersion,
  GovAction as FlatGovAction,
  GovActionId,
  GovActionState as FlatGovActionState,
  GovActions,
  PrevIds,
  Proposal,
  ProtocolParams,
  Proposals as FlatProposals,
  Tx as FlatTx,
  Vote
}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayEraGov,
  ConwayGovState as EraConwayGovState,
  DRepPulsingState,
  EraGov,
  FuturePParams as EraFuturePParams,
  GovState as EraGovState,
  GovStateBases,
  GovStateParams,
  GovStateRecord
}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayGovState,
  Dijkstra,
  EraTag,
  FuturePParams as StoredFuturePParams,
  GovState,
  NewEpochState
}
import xyz.kd5ujc.scarab.ledger.era.ProposalsWriteError
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError}
import xyz.kd5ujc.scarab.protocol.DijkstraNotYet
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellState,
  HaskellStateCodecs,
  LeiosStateCodecs,
  NativeStateCodecs,
  Params,
  ReferenceLayout
}

/** The governance state of every recorded Conway and Dijkstra state the repository holds, read into
  * the era types (`GovStateRecord.fromStored`) and written back (`GovStateRecord.toStored`)
  * identical: the flat record the state's codec read, and the state's bytes the codec writes from
  * it. The other eras' fields of each parameter record are the ones the codec leaves
  * (`Params.empty`, one base for every position). Each state's counts are asserted, so that a state
  * that holds nothing is seen as such: no recorded state holds a proposal, an enacted action or one
  * in the pulser's snapshot, and the populated states are built from the Preview governance blocks'
  * proposals. The private states of the public Leios testnet (the G1 start and the replay windows,
  * not in the repository: [[LeiosReferenceStates]], `SCARAB_LEIOS_REFERENCE_STATES`) are one test
  * each, skipped by name where the variable is not set (an environmental skip that #54 tracks) and
  * failed where it is set and the state is not there; the two of window G that hold the proposal of
  * keys 47 and 48 are refused by the flat codec, which the era types do not change. A restored
  * state's parameter records, each with other eras' fields of its own, are written back over their
  * own positions' records (`GovStateBases.of`). The deposits the era's `obligationGovState` counts
  * are the flat record's.
  */
class EraGovStateStoredSuite extends FunSuite with ExternalInputs:
  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def at(n: Node, path: Int*): Node = path.foldLeft(n)((m, i) => items(m)(i))

  private def decode(bytes: Bytes): Node =
    right("CBOR")(Cbor.decode(bytes, HaskellState.limits))

  /** The current era's index in the ledger's telescope (`[1, [telescope, ...]]`) and its
    * `NewEpochState`.
    */
  private def current(root: Node): (Int, Node) =
    val telescope = items(at(root, 1, 0))
    val index = telescope.size - 1
    (index, at(root, 1, 0, index, 1, 1, 1))

  /** A state's counts: its proposals, the votes on them, the actions the ratification enacts and
    * those of the pulser's snapshot.
    */
  final case class Counts(proposals: Int, votes: Int, enacted: Int, snapshot: Int)

  private def roundTrip[E <: EraTag](what: String, codec: Codec[NewEpochState[E]], nes: Node)(using
      GovStateRecord[E],
      ConwayEraGov[E],
      EraConwayGovState[E] =:= EraGovState[E]
  )(using gs: GovState[E] =:= ConwayGovState): Counts =
    val state = right(s"$what decode")(codec.decode(nes))
    val flat = gs(state.es.ls.utxo.gov)
    val back = through[E](what, flat)
    assertEquals(back, flat, what)
    assertEquals(codec.write(withGov(state, back)), Right(nes.original), what)
    counts[E](what, flat)

  private def withGov[E <: EraTag](state: NewEpochState[E], g: ConwayGovState)(using
      gs: GovState[E] =:= ConwayGovState
  ): NewEpochState[E] =
    state.copy(es =
      state.es.copy(ls = state.es.ls.copy(utxo = state.es.ls.utxo.copy(gov = gs.flip(g))))
    )

  /** `flat` read into era `E` 's record and written back. */
  private def through[E <: EraTag](what: String, flat: ConwayGovState)(using
      GovStateRecord[E]
  ): ConwayGovState =
    val era = right(s"$what fromStored")(GovStateRecord.fromStored[E](flat))
    right(s"$what toStored")(GovStateRecord.toStored[E](era, GovStateBases.uniform(Params.empty)))

  private def counts[E <: EraTag](what: String, flat: ConwayGovState)(using
      GovStateRecord[E]
  )(using
      gov: ConwayEraGov[E],
      ev: EraConwayGovState[E] =:= EraGovState[E]
  ): Counts =
    val era = right(s"$what fromStored")(GovStateRecord.fromStored[E](flat))
    assertEquals(
      gov.obligationGovState(ev(era)).oblProposal.value,
      flat.proposals.totalDeposits,
      what
    )
    val actions = flat.proposals.actions
    Counts(
      actions.size,
      actions.map(a => a.committeeVotes.size + a.drepVotes.size + a.poolVotes.size).sum,
      flat.drepPulsing.ratify.enacted.size,
      flat.drepPulsing.snapshot.proposals.size
    )

  private def stateFile(path: String): Node =
    decode(Bytes.wrap(Files.readAllBytes(Path.of(path))))

  test("the recorded Conway states' governance state reads into the era types and back") {
    val read = Vector(
      ("fixtures/haskell-state/conway-4593/state", HaskellStateCodecs.newEpochState[Conway]),
      ("fixtures/leios/state/conway-195/state", LeiosStateCodecs.newEpochState[Conway])
    ).map { (path, codec) =>
      val (index, nes) = current(stateFile(path))
      assertEquals(index, 6, path)
      path -> roundTrip[Conway](path, codec, nes)
    }.toMap
    assertEquals(
      read,
      Map(
        "fixtures/haskell-state/conway-4593/state" -> Counts(0, 0, 0, 0),
        "fixtures/leios/state/conway-195/state" -> Counts(0, 0, 0, 0)
      )
    )
  }

  /** The Conway states of the native V10 fixtures: their Haskell bytes, read as a native snapshot's
    * are (the proposals as written).
    */
  test("the native V10 fixtures' Conway governance state reads into the era types and back") {
    val read = Vector(
      "conway-native-730.bin",
      "eras/conway-729.bin",
      "eras/conway-730.bin",
      "preview-4320.bin"
    ).flatMap { file =>
      val path = s"fixtures/snapshot-v10/$file"
      val content =
        right(path)(FileLedgerSnapshots.verified(Files.readAllBytes(Path.of(path))))
      val (_, _, _, haskell) =
        right(path)(SnapshotV10.sections(content, ReferenceLayout.Release11_1))
      val (index, nes) = current(decode(haskell))
      Option.when(index == 6)(
        file -> roundTrip[Conway](path, NativeStateCodecs.newEpochState[Conway], nes)
      )
    }.toMap
    assertEquals(
      read,
      Map(
        "conway-native-730.bin" -> Counts(0, 0, 0, 0),
        "eras/conway-729.bin" -> Counts(0, 0, 0, 0),
        "eras/conway-730.bin" -> Counts(0, 0, 0, 0)
      )
    )
  }

  test("the recorded Dijkstra states' governance state reads into the era types and back") {
    val read = Vector("dijkstra-203", "testnet-2168639").map { name =>
      val path = s"fixtures/leios/state/$name/state"
      val (index, nes) = current(stateFile(path))
      assertEquals(index, 7, path)
      name -> roundTrip[Dijkstra](path, LeiosStateCodecs.newEpochState[Dijkstra], nes)
    }.toMap
    assertEquals(
      read,
      Map("dijkstra-203" -> Counts(0, 0, 0, 0), "testnet-2168639" -> Counts(0, 0, 0, 0))
    )
  }

  /** The public Leios testnet's states that are not in the repository ([[LeiosReferenceStates]]):
    * the G1 window's start and the replay windows' states, each by its tip slot.
    */
  private val testnetStates: Vector[LeiosReferenceStates.Capture] =
    LeiosReferenceStates.g1Start +: (LeiosReferenceStates.windowP ++ LeiosReferenceStates.windowG)

  /** The two window G states whose proposals hold the update of keys 47 and 48, which the flat
    * codec refuses (`DijkstraNotYet.ParameterUpdate`).
    */
  private val withDijkstraKeys: Set[LeiosReferenceStates.Capture] =
    Set(1555188L, 1576750L).map(LeiosReferenceStates.Capture(LeiosReferenceStates.Window.G, _))

  // one test each, so that a state not present is skipped by name (CNF-2)
  testnetStates.foreach { capture =>
    val name = capture.name
    val refused = withDijkstraKeys(capture)
    val what =
      if refused then "is refused by the flat codec, its proposal by the flat record"
      else "reads into the era types and back"
    test(s"the testnet's private Dijkstra state $name $what") {
      withInput(LeiosReferenceStates.input(capture)) { root =>
        val path = capture.state(root)
        val codec = LeiosStateCodecs.newEpochState[Dijkstra]
        val (index, nes) = current(decode(Bytes.wrap(Files.readAllBytes(path))))
        assertEquals(index, 7, name)
        if refused then
          assertEquals(
            codec.decode(nes).left.map(_.kind),
            Left(DecodeError.Kind.Domain(DijkstraNotYet.ParameterUpdate)),
            name
          )
          // its one stored proposal, read by the era's reader of a stored Dijkstra proposal: the
          // update of keys 47 and 48, which the flat record has no form for
          val gas = items(at(at(nes, 3, 1, 1, 3), 0, 1))
          assertEquals(gas.size, 1, name)
          val proposal = right(name)(DijkstraProposals.proposal(items(gas(0))(4)))
          assertEquals(
            DijkstraProposals
              .toFlat(GovActionId(Bytes(Vector.fill(32)(0)), BigInt(0)), proposal)
              .left
              .map {
                case ProposalsWriteError.UnstorableUpdate(_, keys) => keys.toSortedSet.toVector
                case other                                         => fail(s"$name: $other")
              },
            Left(Vector(BigInt(47), BigInt(48))),
            name
          )
        // no other state of them holds a proposal the flat codec reads
        else assertEquals(roundTrip[Dijkstra](name, codec, nes), Counts(0, 0, 0, 0), name)
      }
    }
  }

  // ---- the proposals: no recorded state holds one

  private def hash(b: Int): Bytes = Bytes(Vector.fill(28)(b.toByte))

  /** The proposals of the recorded Preview governance blocks
    * (`fixtures/preview/conway-governance.hex`, as `EraPParamsStoredSuite` reads them), each the
    * action of its transaction's id and index.
    */
  private lazy val previewProposals: Vector[(GovActionId, Proposal)] =
    Files
      .readAllLines(Path.of("fixtures/preview/conway-governance.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => right("hex")(Bytes.fromHex(l)))
      .map(b => right("block")(Block.decodeShelley(b).left.map(_.message)))
      .flatMap {
        case b: Block.Shelley =>
          b.transactions.flatMap { t =>
            val tx = right(t.id.hex)(FlatTx.fromBlock(t, b.era))
            tx.body.proposals.zipWithIndex.map((p, i) => GovActionId(tx.body.id, BigInt(i)) -> p)
          }
        case other => fail(s"not a Shelley-based block: $other")
      }

  /** Each proposal's state, with a vote of each kind of voter, the committee's and the DReps'
    * credentials of both kinds.
    */
  private lazy val actions: Vector[FlatGovActionState] =
    previewProposals.zipWithIndex.map { case ((id, p), i) =>
      FlatGovActionState(
        id,
        Map(FlatCredential.Key(hash(i)) -> Vote.Yes, FlatCredential.Script(hash(i + 1)) -> Vote.No),
        Map(
          FlatCredential.Script(hash(i + 2)) -> Vote.Abstain,
          FlatCredential.Key(hash(i + 4)) -> Vote.No
        ),
        Map(hash(i + 3) -> Vote.Yes),
        p,
        BigInt(100 + i),
        BigInt(106 + i)
      )
    }

  /** A store of the actions: the root of each purpose the parent of its first action, the actions
    * that attach to it kept in order (`Proposals.add`).
    */
  private lazy val store: FlatProposals =
    val roots = actions.foldLeft(PrevIds()) { (r, a) =>
      GovActions
        .lineage(a.action)
        .fold(r)((purpose, parent) =>
          if r.get(purpose).isEmpty && parent.isDefined then r.set(purpose, parent) else r
        )
    }
    actions.foldLeft(FlatProposals(Vector.empty, roots))((ps, a) => ps.add(a).getOrElse(ps))

  /** `base` with the actions in its proposals, ratification and pulser's snapshot, and its current
    * parameters as the next epoch's.
    */
  private def holding(base: ConwayGovState): ConwayGovState =
    val pulser = base.drepPulsing
    val enact = pulser.ratify.enact
    base.copy(
      proposals = store,
      futurePParams = StoredFuturePParams.DefinitePParamsUpdate(base.curPParams),
      drepPulsing = pulser.copy(
        snapshot = pulser.snapshot.copy(proposals = actions),
        ratify = pulser.ratify.copy(
          enact = enact.copy(
            withdrawals = Map(FlatCredential.Key(hash(7)) -> BigInt(5)),
            prevIds = store.roots
          ),
          enacted = actions.take(2),
          expired = actions.drop(2).map(_.id).toSet
        )
      )
    )

  private def nesOf[E <: EraTag](path: String, codec: Codec[NewEpochState[E]]): NewEpochState[E] =
    right(path)(codec.decode(current(stateFile(path))._2))

  /** The update of every parameter change written and read again as the flat state codec of a
    * Dijkstra state reads it (at version 12): the original encodings of its values, which the
    * Dijkstra record writes from the typed update, made the writer's.
    */
  private def canonical(g: ConwayGovState): ConwayGovState =
    def update(u: ConwayParamUpdate): ConwayParamUpdate =
      val written = right("the update written")(HaskellStateCodecs.conwayParamUpdate.write(u))
      right("the update read")(
        ConwayParamUpdate.decode(right("CBOR")(Cbor.decode(written)), DecoderVersion.Dijkstra)
      )
    def action(a: FlatGovActionState): FlatGovActionState = a.proposal.action match
      case FlatGovAction.ParameterChange(prev, u, policy) =>
        a.copy(proposal =
          a.proposal.copy(action = FlatGovAction.ParameterChange(prev, update(u), policy))
        )
      case _ => a
    val pulser = g.drepPulsing
    g.copy(
      proposals = g.proposals.copy(actions = g.proposals.actions.map(action)),
      drepPulsing = pulser.copy(
        snapshot = pulser.snapshot.copy(proposals = pulser.snapshot.proposals.map(action)),
        ratify = pulser.ratify.copy(enacted = pulser.ratify.enacted.map(action))
      )
    )

  test(
    "the Preview governance blocks' proposals, voted on, read into each era's governance state and back"
  ) {
    // every kind of action, two of each: each attaches to the root of its purpose or to the one
    // before it
    assertEquals(previewProposals.size, 14)
    assertEquals(store.actions.size, 14)
    assertEquals(
      previewProposals.map(_._2.action.ordinal).groupMapReduce(identity)(_ => 1)(_ + _),
      (0 to 6).map(_ -> 2).toMap
    )
    val conwayPath = "fixtures/haskell-state/conway-4593/state"
    val conwayCodec = HaskellStateCodecs.newEpochState[Conway]
    val conway = nesOf(conwayPath, conwayCodec)
    val conwayFlat = holding(conway.es.ls.utxo.gov)
    val conwayBack = through[Conway]("Conway", conwayFlat)
    assertEquals(conwayBack, conwayFlat)
    assertEquals(
      right("Conway written back")(conwayCodec.write(withGov(conway, conwayBack))),
      right("Conway written")(conwayCodec.write(withGov(conway, conwayFlat)))
    )
    assertEquals(
      counts[Conway]("Conway", conwayFlat),
      Counts(store.actions.size, store.actions.size * 5, 2, actions.size)
    )
    val dijkstraPath = "fixtures/leios/state/testnet-2168639/state"
    val dijkstraCodec = LeiosStateCodecs.newEpochState[Dijkstra]
    val dijkstra = nesOf(dijkstraPath, dijkstraCodec)
    val dijkstraFlat = holding(dijkstra.es.ls.utxo.gov)
    val dijkstraBack = through[Dijkstra]("Dijkstra", dijkstraFlat)
    assertEquals(dijkstraBack, canonical(dijkstraFlat))
    assertEquals(
      right("Dijkstra written back")(dijkstraCodec.write(withGov(dijkstra, dijkstraBack))),
      right("Dijkstra written")(dijkstraCodec.write(withGov(dijkstra, dijkstraFlat)))
    )
  }

  // ---- a restored state: each parameter record with fields of its own

  /** `base` with its five parameter records each distinct, in a field of the era (`minFeeA`) and in
    * one of another era (`minUTxOValue`), as a restored native state can hold them (the current and
    * previous the sidecar's, the enact state's as the Haskell section holds them), definite future
    * parameters, and a delaying action ratified.
    */
  private def restored(base: ConwayGovState): ConwayGovState =
    def at(i: Int): ProtocolParams =
      base.curPParams.copy(minFeeA = BigInt(44 + i), minUTxOValue = BigInt(1000 + i))
    val pulser = base.drepPulsing
    val ratify = pulser.ratify
    base.copy(
      curPParams = at(0),
      prevPParams = at(1),
      futurePParams = StoredFuturePParams.DefinitePParamsUpdate(at(2)),
      drepPulsing = pulser.copy(ratify =
        ratify.copy(
          enact = ratify.enact.copy(params = at(3), prevParams = at(4)),
          delayed = true
        )
      )
    )

  /** `flat` read into era `E` 's record: each of its parameter records the one of its own position,
    * all five distinct, and the delay kept; written back over each position's own record, the same
    * flat record, each position's other eras' field its own; over one record for every position,
    * not.
    */
  private def restoredThrough[E <: EraTag](what: String, flat: ConwayGovState)(using
      r: GovStateRecord[E]
  ): Unit =
    val era = right(s"$what fromStored")(GovStateRecord.fromStored[E](flat))
    val enact = flat.drepPulsing.ratify.enact
    def own(p: ProtocolParams) = right(s"$what pparams")(r.pparams(p))
    val ratify = era.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(_, rs) => rs
    val typed = Vector(
      era.cgsCurPParams,
      era.cgsPrevPParams,
      era.cgsFuturePParams match
        case EraFuturePParams.DefinitePParamsUpdate(pp) => pp
        case other                                      => fail(s"$what: not definite: $other")
      ,
      ratify.rsEnactState.ensCurPParams,
      ratify.rsEnactState.ensPrevPParams
    )
    val definite = flat.futurePParams match
      case StoredFuturePParams.DefinitePParamsUpdate(p) => p
      case other                                        => fail(s"$what: not definite: $other")
    assertEquals(
      typed,
      Vector(flat.curPParams, flat.prevPParams, definite, enact.params, enact.prevParams).map(own),
      what
    )
    assertEquals(typed.distinct.size, 5, what)
    assert(ratify.rsDelayed, what)
    val back =
      right(s"$what toStored")(GovStateRecord.toStored[E](era, GovStateBases.of(flat)))
    assertEquals(back, flat, what)
    val backEnact = back.drepPulsing.ratify.enact
    val written = Vector(
      back.curPParams,
      back.prevPParams,
      GovStateBases.of(back).at(GovStateParams.Future),
      backEnact.params,
      backEnact.prevParams
    )
    assertEquals(
      written.map(p => (p.minFeeA, p.minUTxOValue)),
      (0 to 4).toVector.map(i => (BigInt(44 + i), BigInt(1000 + i))),
      what
    )
    assert(back.drepPulsing.ratify.delayed, what)
    assertNotEquals(
      GovStateRecord.toStored[E](era, GovStateBases.uniform(flat.curPParams)),
      Right(flat),
      what
    )

  test(
    "a restored state's parameter records, each with other eras' fields of its own, read and back"
  ) {
    val conway = nesOf(
      "fixtures/haskell-state/conway-4593/state",
      HaskellStateCodecs.newEpochState[Conway]
    )
    restoredThrough[Conway]("Conway", restored(conway.es.ls.utxo.gov))
    val dijkstra = nesOf(
      "fixtures/leios/state/testnet-2168639/state",
      LeiosStateCodecs.newEpochState[Dijkstra]
    )
    restoredThrough[Dijkstra]("Dijkstra", restored(dijkstra.es.ls.utxo.gov))
  }
