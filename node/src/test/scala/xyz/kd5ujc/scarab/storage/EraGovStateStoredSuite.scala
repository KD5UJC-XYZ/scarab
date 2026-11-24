// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  Credential as FlatCredential,
  EnactState as FlatEnactState,
  GovActionId,
  GovActionState as FlatGovActionState,
  GovActions,
  PrevIds,
  Proposal,
  ProtocolParams,
  Proposals as FlatProposals,
  PulsingSnapshot as FlatPulsingSnapshot,
  RatifyState as FlatRatifyState,
  Tx as FlatTx,
  Vote
}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayEraGov,
  ConwayGovState as EraConwayGovState,
  DRepPulsingState,
  EraGov,
  EraPParams,
  FuturePParams as EraFuturePParams,
  GovAction as EraGovAction,
  GovActionState as EraGovActionState,
  GovState as EraGovState,
  GovStateBases,
  GovStateParams,
  GovStateRecord,
  PParamsUpdate,
  Proposals as EraProposals
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayProposals
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayGovState,
  DRComplete as FlatDRComplete,
  Dijkstra,
  EraTag,
  FuturePParams as StoredFuturePParams,
  GovState,
  NewEpochState
}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellState,
  HaskellStateCodecs,
  LeiosStateCodecs,
  NativeStateCodecs,
  Params,
  ReferenceLayout
}

/** The governance state of every recorded Conway state the repository holds, read into the era
  * types (`GovStateRecord.fromStored`) and written back (`GovStateRecord.toStored`) identical: the
  * flat record the state's codec read, and the state's bytes the codec writes from it. A Dijkstra
  * state's governance state is the era's record, which its codec reads and writes byte for byte; a
  * flat record is read into Dijkstra's where the chain enters Dijkstra, and each action of it is
  * read as Conway reads it (the flat path's action, or a parameter change whose update each era's
  * encoder writes the same). The other eras' fields of each parameter record are the ones the codec
  * leaves (`Params.empty`, one base for every position). Each state's counts are asserted, so that
  * a state that holds nothing is seen as such: no recorded state holds a proposal, an enacted
  * action or one in the pulser's snapshot, and the populated states are built from the Preview
  * governance blocks' proposals. The private states of the public Leios testnet (the G1 start and
  * the replay windows, not in the repository: [[LeiosReferenceStates]],
  * `SCARAB_LEIOS_REFERENCE_STATES`) are one test each, skipped by name where the variable is not
  * set (an environmental skip that #54 tracks) and failed where it is set and the state is not
  * there; the two of window G that hold the proposal of keys 47 and 48 are read and written back
  * too. A restored state's parameter records, each with other eras' fields of its own, are read
  * each into its own position, and Conway's written back over their own positions' records
  * (`GovStateBases.of`). The deposits the era's `obligationGovState` counts are the flat record's.
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

  private def roundTrip(what: String, codec: Codec[NewEpochState[Conway]], nes: Node): Counts =
    val state = right(s"$what decode")(codec.decode(nes))
    val flat: ConwayGovState = state.es.ls.utxo.gov
    val back = through(what, flat)
    assertEquals(back, flat, what)
    assertEquals(codec.write(withGov(state, back)), Right(nes.original), what)
    counts[Conway](what, flat)

  private def withGov[E <: EraTag](state: NewEpochState[E], g: ConwayGovState)(using
      gs: GovState[E] =:= ConwayGovState
  ): NewEpochState[E] =
    state.copy(es =
      state.es.copy(ls = state.es.ls.copy(utxo = state.es.ls.utxo.copy(gov = gs.flip(g))))
    )

  /** `flat` read into Conway's record and written back. */
  private def through(what: String, flat: ConwayGovState): ConwayGovState =
    val era = right(s"$what fromStored")(GovStateRecord.fromStored[Conway](flat))
    GovStateRecord.toStored(era, GovStateBases.uniform(Params.empty))

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

  /** A Dijkstra state, whose governance state is the era's record: read by the state's codec and
    * written back byte for byte, with its counts; the deposits the era's `obligationGovState`
    * counts are its proposals'.
    */
  private def dijkstraRoundTrip(
      what: String,
      codec: Codec[NewEpochState[Dijkstra]],
      nes: Node
  ): Counts =
    val state = right(s"$what decode")(codec.decode(nes))
    assertEquals(codec.write(state).map(_.hex), Right(nes.original.hex), what)
    eraCounts(what, state.es.ls.utxo.gov)

  private def eraCounts(what: String, era: EraConwayGovState[Dijkstra]): Counts =
    val actions = EraProposals.proposalsActions(era.cgsProposals)
    assertEquals(
      EraGov.dijkstra.obligationGovState(era).oblProposal.value,
      actions.map(_.gasDeposit.value).sum,
      what
    )
    val ratify = DRepPulsingState.extractDRepPulsingState(era.cgsDRepPulsingState)
    val snapshot = era.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(s, _) => s
    Counts(
      actions.size,
      actions
        .map(a => a.gasCommitteeVotes.size + a.gasDRepVotes.size + a.gasStakePoolVotes.size)
        .sum,
      ratify.rsEnacted.size,
      snapshot.psProposals.size
    )

  /** A Dijkstra state's governance state, which holds no action, as a flat record of it: its
    * parameter records as the rules that read the flat record view them (`GovStateRecord.view`, the
    * other eras' fields at zero), the rest the era record's.
    */
  private def flatOf(era: EraConwayGovState[Dijkstra]): ConwayGovState =
    val (snapshot, ratify) = era.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(s, r) => (s, r)
    assertEquals(eraCounts("flatOf", era), Counts(0, 0, 0, 0))
    val enact = ratify.rsEnactState
    ConwayGovState(
      FlatProposals(
        Vector.empty,
        ConwayProposals.prevIds(EraProposals.toPrevGovActionIds(era.cgsProposals.pRoots))
      ),
      era.cgsCommittee,
      era.cgsConstitution,
      GovStateRecord.view(era.cgsCurPParams),
      GovStateRecord.view(era.cgsPrevPParams),
      GovStateRecord.futureView(era.cgsFuturePParams),
      FlatDRComplete(
        FlatPulsingSnapshot(
          Vector.empty,
          snapshot.psDRepDistr,
          snapshot.psDRepState,
          snapshot.psPoolDistr
        ),
        FlatRatifyState(
          FlatEnactState(
            enact.ensCommittee,
            enact.ensConstitution,
            GovStateRecord.view(enact.ensCurPParams),
            GovStateRecord.view(enact.ensPrevPParams),
            enact.ensTreasury.value,
            enact.ensWithdrawals.iterator.map((c, v) => Convert.back(c) -> v.value).toMap,
            ConwayProposals.prevIds(enact.ensPrevGovActionIds)
          ),
          Vector.empty,
          ratify.rsExpired,
          ratify.rsDelayed
        )
      )
    )

  /** Dijkstra's reading of the flat action states `flat` (`d`) against Conway's of them: the same
    * ids, votes and epochs, deposits, return accounts and anchors; a parameter change of the same
    * parent and guardrails whose update each era's encoder writes the same, every other action the
    * flat one (`DijkstraProposals.flatOrUpdate`).
    */
  private def sameActions(what: String)(
      flat: Vector[FlatGovActionState],
      d: Vector[EraGovActionState[Dijkstra]]
  ): Unit =
    assertEquals(d.size, flat.size, what)
    flat.zip(d).foreach { (f, d) =>
      val c = right(s"$what Conway")(GovStateRecord.conway.action(f))
      assertEquals(
        (d.gasId, d.gasCommitteeVotes, d.gasDRepVotes, d.gasStakePoolVotes),
        (c.gasId, c.gasCommitteeVotes, c.gasDRepVotes, c.gasStakePoolVotes),
        what
      )
      assertEquals((d.gasProposedIn, d.gasExpiresAfter), (c.gasProposedIn, c.gasExpiresAfter), what)
      val (dp, cp) = (d.gasProposalProcedure, c.gasProposalProcedure)
      assertEquals(
        (dp.pProcDeposit, dp.pProcReturnAddr, dp.pProcAnchor),
        (cp.pProcDeposit, cp.pProcReturnAddr, cp.pProcAnchor),
        what
      )
      (DijkstraProposals.flatOrUpdate(dp.pProcGovAction), cp.pProcGovAction) match
        case (
              Left(EraGovAction.ParameterChange(dParent, du, dGuard)),
              EraGovAction.ParameterChange(cParent, cu, cGuard)
            ) =>
          assertEquals(
            (dParent.map(_.unGovPurposeId), dGuard),
            (cParent.map(_.unGovPurposeId), cGuard),
            what
          )
          assertEquals(
            PParamsUpdate.encodePParamsUpdate(du)(using EraPParams.dijkstra).bytes,
            PParamsUpdate.encodePParamsUpdate(cu)(using EraPParams.conway).bytes,
            what
          )
        case (Right(action), _) => assertEquals(action, f.proposal.action, what)
        case other              => fail(s"$what: $other")
    }

  test("the recorded Conway states' governance state reads into the era types and back") {
    val read = Vector(
      ("fixtures/haskell-state/conway-4593/state", HaskellStateCodecs.newEpochState[Conway]),
      ("fixtures/leios/state/conway-195/state", LeiosStateCodecs.newEpochState[Conway])
    ).map { (path, codec) =>
      val (index, nes) = current(stateFile(path))
      assertEquals(index, 6, path)
      path -> roundTrip(path, codec, nes)
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
        file -> roundTrip(path, NativeStateCodecs.newEpochState[Conway], nes)
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

  test("the recorded Dijkstra states read into the era types and are written back byte for byte") {
    val read = Vector("dijkstra-203", "testnet-2168639").map { name =>
      val path = s"fixtures/leios/state/$name/state"
      val (index, nes) = current(stateFile(path))
      assertEquals(index, 7, path)
      name -> dijkstraRoundTrip(path, LeiosStateCodecs.newEpochState[Dijkstra], nes)
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

  /** The two window G states whose proposals hold the update of keys 47 and 48 (proposal 2). */
  private val withDijkstraKeys: Set[LeiosReferenceStates.Capture] =
    Set(1555188L, 1576750L).map(LeiosReferenceStates.Capture(LeiosReferenceStates.Window.G, _))

  // one test each, so that a state not present is skipped by name (CNF-2)
  testnetStates.foreach { capture =>
    val name = capture.name
    val proposing = withDijkstraKeys(capture)
    val what =
      if proposing then "holds the proposal of keys 47 and 48, read and written back"
      else "reads into the era types and is written back"
    test(s"the testnet's private Dijkstra state $name $what") {
      withInput(LeiosReferenceStates.input(capture)) { root =>
        val path = capture.state(root)
        val codec = LeiosStateCodecs.newEpochState[Dijkstra]
        val (index, nes) = current(decode(Bytes.wrap(Files.readAllBytes(path))))
        assertEquals(index, 7, name)
        val counts = dijkstraRoundTrip(name, codec, nes)
        if proposing then
          assertEquals(counts.proposals, 1, name)
          val gov = right(name)(codec.decode(nes)).es.ls.utxo.gov
          val keys = EraProposals.proposalsActions(gov.cgsProposals).map(_.gasAction).collect {
            case EraGovAction.ParameterChange(_, u, _) => DijkstraProposals.updateKeys(u)
          }
          assertEquals(keys, Vector(Vector(BigInt(47), BigInt(48))), name)
        // no other state of them holds a proposal
        else assertEquals(counts, Counts(0, 0, 0, 0), name)
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
    val conwayBack = through("Conway", conwayFlat)
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
    val dijkstraGov = dijkstra.es.ls.utxo.gov
    val dijkstraFlat = holding(flatOf(dijkstraGov))
    val era = right("Dijkstra fromStored")(GovStateRecord.fromStored[Dijkstra](dijkstraFlat))
    // each action as Conway reads it, in the proposals, the ratification and the pulser's snapshot
    val (snapshot, ratify) = era.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(s, r) => (s, r)
    val flatPulser = dijkstraFlat.drepPulsing
    sameActions("proposals")(store.actions, EraProposals.proposalsActions(era.cgsProposals))
    sameActions("enacted")(flatPulser.ratify.enacted, ratify.rsEnacted)
    sameActions("snapshot")(flatPulser.snapshot.proposals, snapshot.psProposals)
    assertEquals(
      EraProposals.toPrevGovActionIds(era.cgsProposals.pRoots),
      ConwayProposals.roots(store.roots)
    )
    assertEquals(ratify.rsEnactState.ensPrevGovActionIds, ConwayProposals.roots(store.roots))
    assertEquals(ratify.rsExpired, flatPulser.ratify.expired)
    assertEquals(
      ratify.rsEnactState.ensWithdrawals.iterator.map((c, v) => Convert.back(c) -> v.value).toMap,
      flatPulser.ratify.enact.withdrawals
    )
    // the parameters the state's own, the future ones its current ones
    assertEquals(
      (era.cgsCurPParams, era.cgsPrevPParams, era.cgsFuturePParams),
      (
        dijkstraGov.cgsCurPParams,
        dijkstraGov.cgsPrevPParams,
        EraFuturePParams.DefinitePParamsUpdate(dijkstraGov.cgsCurPParams)
      )
    )
    // the era's record holding them, written by the state's codec and read back the same
    val held = dijkstra.copy(es =
      dijkstra.es.copy(ls =
        dijkstra.es.ls.copy[Dijkstra](utxo = dijkstra.es.ls.utxo.copy[Dijkstra](gov = era))
      )
    )
    val written = right("Dijkstra written")(dijkstraCodec.write(held))
    val reread = right("Dijkstra read")(dijkstraCodec.decode(decode(written)))
    assertEquals(reread.es.ls.utxo.gov, era)
    assertEquals(
      eraCounts("Dijkstra", era),
      Counts(store.actions.size, store.actions.size * 5, 2, actions.size)
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
    * all five distinct, and the delay kept.
    */
  private def restoredRead[E <: EraTag](what: String, flat: ConwayGovState)(using
      r: GovStateRecord[E]
  ): EraConwayGovState[E] =
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
    era

  /** `flat` read into Conway's record ([[restoredRead]]) and written back over each position's own
    * record, the same flat record, each position's other eras' field its own; over one record for
    * every position, not.
    */
  private def restoredThrough(what: String, flat: ConwayGovState): Unit =
    val era = restoredRead[Conway](what, flat)
    val back = GovStateRecord.toStored(era, GovStateBases.of(flat))
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
      GovStateRecord.toStored(era, GovStateBases.uniform(flat.curPParams)),
      flat,
      what
    )

  test(
    "a restored state's parameter records, each with other eras' fields of its own, read and back"
  ) {
    val conway = nesOf(
      "fixtures/haskell-state/conway-4593/state",
      HaskellStateCodecs.newEpochState[Conway]
    )
    restoredThrough("Conway", restored(conway.es.ls.utxo.gov))
    val dijkstra = nesOf(
      "fixtures/leios/state/testnet-2168639/state",
      LeiosStateCodecs.newEpochState[Dijkstra]
    )
    restoredRead[Dijkstra]("Dijkstra", restored(flatOf(dijkstra.es.ls.utxo.gov)))
  }
