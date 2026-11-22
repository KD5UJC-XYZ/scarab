// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  CommitteeAuth,
  ConwayGenesis,
  Credential as FlatCredential,
  DecodeField,
  DecoderVersion,
  EnactState as FlatEnactState,
  GovAction as FlatGovAction,
  GovActionId,
  GovActionState as FlatGovActionState,
  Proposal,
  ProtocolParams,
  Ratify,
  RatifyState as FlatRatifyState,
  RewardAccount
}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraGovActionId
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayEraAccounts,
  ConwayEraCertState,
  ConwayEraPParams,
  DRepPulsingState,
  DecodeAt,
  EnactState,
  GovAction,
  GovActionPurpose,
  GovPurposeId,
  GovRelation,
  GovStateBases,
  GovStateReadError,
  GovStateRecord,
  PParams,
  ProposalsWriteError,
  Proposals,
  RatifyEnv,
  RatifySignal,
  VotingStakePoolDistr
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayProposals
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraProposals
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayEnact, ConwayRatify, EnactSignal}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  CompactCoin,
  Conway,
  ConwayGovState,
  DRepRole,
  DRepState,
  Dijkstra,
  EpochNo,
  EraTag,
  FlatView,
  KeyHash,
  NewEpochState,
  NonZero,
  ProtVer,
  Sorted,
  StakePool,
  Staking,
  VocabError,
  Word64
}
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellState,
  HaskellStateCodecs,
  LeiosStateCodecs,
  NativeStateCodecs,
  Params,
  ReferenceLayout
}

/** RATIFY on the era types (`ConwayRatify.ratifyTransition`) over the DRep pulser's snapshot of
  * every recorded Conway and Dijkstra state the repository holds, and of the private states of the
  * public Leios testnet where present: from the enact state the pulser starts from
  * (`Governance.hs`'s `mkEnactState`: the state's committee, constitution and parameters, its
  * treasury, no withdrawals and the proposals' roots), over the snapshot's actions in priority
  * order, it reaches the stored ratification, which is the reference's own result; and the flat
  * `Ratify` reaches the stored flat record from the same inputs. For `conway-195`, of the Leios
  * devnet's epoch 0, the start's current parameters are the translation's, before consensus
  * injected the genesis' cost models into the state's current ones (its own test).
  *
  * Every one of these snapshots holds no action (asserted per state), so this binds the empty
  * signal only: the start, the treasury's reset and the record's reading. The pulser's environment
  * is not stored (the stake, the committee's state, the accounts and pools at the epoch's start),
  * so a snapshot holding actions could not be replayed soundly from a later state; the two window G
  * states with the proposal of keys 47 and 48 have no flat view (the flat rule's record has no form
  * for the update), so they are not replayed, and their RATIFY is incomplete (CNF-2). Of the one
  * whose pulser enacted that proposal, 1,576,750, ENACT of the action on the live parameters and
  * roots gives the stored enact state's, read from the state's bytes. A withdrawal to an account of
  * network 2, which the node's stored codec reads and the reference's does not, is ENACT's
  * `VocabError`.
  *
  * The private states are read from the directory `SCARAB_LEIOS_REFERENCE_STATES` names
  * ([[LeiosReferenceStates]]), one test each: skipped by name where the variable is not set (an
  * environmental skip that #54 tracks), failed where it is set and the state is not there.
  */
class EraRatifyStoredSuite extends FunSuite with ExternalInputs:
  private def right[A](what: String)(e: Either[?, A]): A = e.fold(x => fail(s"$what: $x"), identity)

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def at(n: Node, path: Int*): Node = path.foldLeft(n)((m, i) => items(m)(i))

  private def decode(bytes: Bytes): Node = right("CBOR")(Cbor.decode(bytes, HaskellState.limits))

  private def current(root: Node): (Int, Node) =
    val telescope = items(at(root, 1, 0))
    val index = telescope.size - 1
    (index, at(root, 1, 0, index, 1, 1, 1))

  private def stateFile(path: Path): Node = decode(Bytes.wrap(Files.readAllBytes(path)))

  /** The snapshot's environment at the era's types, with the state's committee state, accounts and
    * pools, and the snapshot's pool stakes as the voting stake, their total the sum (one, the
    * default, if none).
    */
  private def environment[E <: EraTag](
      state: NewEpochState[E],
      flat: ConwayGovState
  )(using c: ConwayEraCertState[E]): RatifyEnv[E] =
    val snapshot = flat.drepPulsing.snapshot
    val cert = state.es.ls.cert
    val stakes = right("pools")(
      snapshot.poolDistr.toVector.traverse((k, v) =>
        (KeyHash.fromBytes[StakePool](k), CompactCoin.fromBigInt(v)).tupled
      )
    )
    val total = NonZero.from(Coin(stakes.map(_._2.toBigInt).sum)).getOrElse(Coin.oneNonZero)
    RatifyEnv[E](
      state.es.ls.utxo.instantStake,
      VotingStakePoolDistr(Sorted.map(stakes), total),
      right("DReps")(
        snapshot.drepDistr.toVector.traverse((d, v) =>
          (Convert.drep(d), CompactCoin.fromBigInt(v)).tupled
        )
      ).toMap,
      right("DRep states")(snapshot.drepState.toVector.traverse { (k, d) =>
        (
          Convert.credential[DRepRole](k),
          EpochNo.fromBigInt(d.expiry),
          CompactCoin.fromBigInt(d.deposit),
          d.delegs.toVector.traverse(Convert.credential[Staking])
        ).mapN((cred, expiry, deposit, delegs) =>
          cred -> DRepState(expiry, d.anchor, deposit, Sorted.set(delegs))
        )
      }).toMap,
      state.epoch,
      c.certVStateL.get(cert).committeeState,
      c.certDStateL.get(cert).accounts,
      c.certPStateL.get(cert).stakePools
    )

  /** The flat rule's environment of the same inputs, as `ConwayPulser` builds it, without the
    * accounts, which it reads only for the default vote of a pool on an action.
    */
  private def flatEnvironment[E <: EraTag](
      state: NewEpochState[E],
      flat: ConwayGovState
  )(using c: ConwayEraCertState[E]): Ratify.Env =
    val snapshot = flat.drepPulsing.snapshot
    val cert = state.es.ls.cert
    Ratify.Env(
      snapshot.drepDistr,
      snapshot.drepState,
      snapshot.poolDistr,
      snapshot.poolDistr.values.sum,
      state.epoch.toBigInt,
      FlatView.stakeMap(c.certVStateL.get(cert).committeeState.creds) {
        case xyz.kd5ujc.scarab.ledger.state.CommitteeAuthorization.CommitteeHotCredential(h) =>
          CommitteeAuth.Hot(FlatView.credBack(h))
        case xyz.kd5ujc.scarab.ledger.state.CommitteeAuthorization.CommitteeMemberResigned(a) =>
          CommitteeAuth.Resigned(a)
      },
      Map.empty,
      FlatView.pools(0, c.certPStateL.get(cert).stakePools)
    )

  /** A state's governance state as the node's flat record, which the flat rule reads, and as the
    * era's record: Conway's state holds the flat record, read into the era's; Dijkstra's holds the
    * era's, written as the flat record over the fields its codec left at zero (`Params.empty`).
    */
  private trait GovViews[E <: EraTag]:
    def apply(
        nes: NewEpochState[E]
    ): Either[
      GovStateReadError | ProposalsWriteError,
      (ConwayGovState, xyz.kd5ujc.scarab.ledger.era.ConwayGovState[E])
    ]

  private given GovViews[Conway] = nes =>
    val flat: ConwayGovState = nes.es.ls.utxo.gov
    GovStateRecord.fromStored[Conway](flat).map(flat -> _)

  private given GovViews[Dijkstra] = nes =>
    val era: xyz.kd5ujc.scarab.ledger.era.ConwayGovState[Dijkstra] = nes.es.ls.utxo.gov
    GovStateRecord
      .toStored[Dijkstra](era, GovStateBases.uniform(Params.empty))
      .map(_ -> era)

  /** RATIFY of a state's snapshot on both rules, beside its stored ratification. */
  private final case class Replayed[E <: EraTag](
      era: xyz.kd5ujc.scarab.ledger.era.ConwayGovState[E],
      end: xyz.kd5ujc.scarab.ledger.era.RatifyState[E],
      stored: xyz.kd5ujc.scarab.ledger.era.RatifyState[E],
      flatEnd: FlatRatifyState,
      flatStored: FlatRatifyState,
      actions: Int
  )

  /** RATIFY of `nes` 's snapshot on both rules, from the enact state the pulser starts from with
    * the state's current parameters, or `startCur` in their place where the pulser was made before
    * the state's current parameters were.
    */
  private def replayed[E <: EraTag](
      what: String,
      codec: Codec[NewEpochState[E]],
      nes: Node,
      startCur: Option[PParams[E]]
  )(using
      r: GovStateRecord[E],
      c: ConwayEraCertState[E],
      p: ConwayEraPParams[E],
      a: ConwayEraAccounts[E]
  )(using views: GovViews[E]): (EpochNo, Replayed[E]) =
    val state = right(s"$what decode")(codec.decode(nes))
    val (flat, era) = right(s"$what views")(views(state))
    val (snapshot, stored) = era.cgsDRepPulsingState match
      case DRepPulsingState.DRComplete(s, rs) => (s, rs)
    val treasury = state.es.chainAccount.treasury
    val cur = startCur.getOrElse(era.cgsCurPParams)
    val start = EnactState[E](
      era.cgsCommittee,
      era.cgsConstitution,
      cur,
      era.cgsPrevPParams,
      treasury,
      Sorted.empty,
      Proposals.toPrevGovActionIds(era.cgsProposals.pRoots)
    )
    val end = right(s"$what RATIFY")(
      ConwayRatify.ratifyTransition(
        environment(state, flat),
        xyz.kd5ujc.scarab.ledger.era
          .RatifyState[E](start, Vector.empty, scala.collection.immutable.SortedSet.empty, false),
        RatifySignal(RatifySignal.reorderActions(snapshot.psProposals))
      )
    )
    // the flat record of the start's parameters is the one the state's codec reads of them, its other
    // eras' fields the codec's (`Params.empty`)
    val flatStart = FlatEnactState(
      flat.committee,
      flat.constitution,
      startCur.fold(flat.curPParams)(r.storedPParams(_, Params.empty)),
      flat.prevPParams,
      treasury.value,
      Map.empty,
      flat.proposals.roots
    )
    val flatEnd: FlatRatifyState =
      Ratify.ratify(flatEnvironment(state, flat), flatStart, flat.drepPulsing.snapshot.proposals)
    (
      state.epoch,
      Replayed[E](era, end, stored, flatEnd, flat.drepPulsing.ratify, snapshot.psProposals.size)
    )

  /** RATIFY of a state's snapshot on both rules reaches its stored ratification; the number of
    * actions in its snapshot.
    */
  private def replay[E <: EraTag](what: String, codec: Codec[NewEpochState[E]], nes: Node)(using
      GovStateRecord[E],
      ConwayEraCertState[E],
      ConwayEraPParams[E],
      ConwayEraAccounts[E]
  )(using GovViews[E]): Int =
    val (_, r) = replayed[E](what, codec, nes, None)
    assertEquals(r.end, r.stored, what)
    assertEquals(r.flatEnd, r.flatStored, what)
    r.actions

  private def leios(path: String): Node =
    val (index, nes) = current(stateFile(Path.of(path)))
    assertEquals(index, 6, path)
    nes

  test("RATIFY over conway-4593's snapshot reaches its stored ratification") {
    val path = "fixtures/haskell-state/conway-4593/state"
    val (index, nes) = current(stateFile(Path.of(path)))
    assertEquals(index, 6, path)
    assertEquals(replay[Conway](path, HaskellStateCodecs.newEpochState[Conway], nes), 0, path)
  }

  private def genesis(name: String): Json =
    parse(Files.readString(Path.of(s"fixtures/leios/state/genesis/$name")))
      .fold(e => fail(s"$name: ${e.message}"), identity)

  /** The positions at which two parameter records, as lists, hold different bytes. */
  private def differing(a: Node, b: Node): Vector[Int] =
    val (xs, ys) = (items(a), items(b))
    assertEquals(xs.size, ys.size)
    xs.indices.toVector.filter(i => xs(i).original != ys(i).original)

  test(
    "RATIFY over conway-195's snapshot, from the translation's parameters, reaches its ratification"
  ) {
    // The Leios devnet's last Conway state, of its epoch 0, where the chain starts in Conway: its
    // pulser is the one the Babbage to Conway translation completed, over `mkEnactState` of the
    // translated governance state, the current parameters from the current and the previous from the
    // previous (cardano-ledger 9df9f98c `Conway/Translation.hs:70-85, 165-178`,
    // `Conway/Governance.hs:329-339, 449-458`). Consensus injects the Alonzo genesis'
    // `extraConfig` cost models after that, into the current parameters only (ouroboros-consensus
    // 2416493b `Cardano/Node.hs:843-865, 881-889`; cardano-ledger 9df9f98c
    // `Conway/Transition.hs:161-169`, `Alonzo/Transition.hs:63-82`). So the pulser's enact state
    // holds the translation's parameters, which differ from the state's current ones in their cost
    // models only.
    val path = "fixtures/leios/state/conway-195/state"
    val alonzo = genesis("alonzo-genesis.json")
    val translated = right("the translation's parameters")(
      ProtocolParams.translatedFromGenesis(
        genesis("shelley-genesis.json"),
        alonzo,
        Some(genesis("conway-genesis.json"))
      )
    )
    val injected = right("the injected cost models")(ProtocolParams.injectedCostModels(alonzo))
    val record = GovStateRecord.conway
    val start = right("the translation's era parameters")(record.pparams(translated))
    val nes = leios(path)
    val (epoch, r) =
      replayed[Conway](path, LeiosStateCodecs.newEpochState[Conway], nes, Some(start))
    assertEquals(epoch.toBigInt, BigInt(0))
    assertEquals(r.actions, 0)
    // the previous parameters and both of the enact state's are the translation's
    assertEquals(r.era.cgsPrevPParams, start)
    assertEquals(r.stored.rsEnactState.ensCurPParams, start)
    assertEquals(r.stored.rsEnactState.ensPrevPParams, start)
    // the current ones are the translation's with the injected cost models over them: the Alonzo
    // and Conway genesis' PlutusV1 and PlutusV3 models replaced, and PlutusV2 added
    val base = record.storedPParams(start, Params.empty)
    assertEquals(base.costModels.view.mapValues(_.size).toMap, Map(0 -> 166, 2 -> 251))
    assertEquals(injected.view.mapValues(_.size).toMap, Map(0 -> 332, 1 -> 332, 2 -> 350))
    assertEquals(
      record.storedPParams(r.era.cgsCurPParams, Params.empty),
      base.copy(costModels = base.costModels ++ injected)
    )
    // in the state's bytes, the current parameters differ from the enact state's in field 15 (the
    // cost models) only, of 31, and the previous parameters from the enact state's in none
    val gov = at(nes, 3, 1, 1, 3)
    val enact = at(gov, 6, 1, 0)
    assertEquals(items(at(gov, 3)).size, 31)
    assertEquals(differing(at(gov, 3), at(enact, 2)), Vector(15))
    assertEquals(differing(at(gov, 4), at(enact, 3)), Vector.empty)
    assertEquals(differing(at(gov, 3), at(gov, 4)), Vector(15))
    // and RATIFY from the translation's enact state reaches the stored ratification exactly
    assertEquals(r.end, r.stored)
    assertEquals(r.flatEnd, r.flatStored)
  }

  test("RATIFY over the native V10 fixtures' Conway snapshots reaches their stored ratification") {
    val read = Vector("conway-native-730.bin", "eras/conway-729.bin", "eras/conway-730.bin").map {
      file =>
        val path = s"fixtures/snapshot-v10/$file"
        val content = right(path)(FileLedgerSnapshots.verified(Files.readAllBytes(Path.of(path))))
        val (_, _, _, haskell) =
          right(path)(SnapshotV10.sections(content, ReferenceLayout.Release11_1))
        val (index, nes) = current(decode(haskell))
        assertEquals(index, 6, path)
        replay[Conway](path, NativeStateCodecs.newEpochState[Conway], nes)
    }
    assertEquals(read, Vector(0, 0, 0), "no recorded snapshot holds an action")
  }

  test("RATIFY over the recorded Dijkstra states' snapshots reaches their stored ratification") {
    val read = Vector("dijkstra-203", "testnet-2168639").map { name =>
      val path = s"fixtures/leios/state/$name/state"
      val (index, nes) = current(stateFile(Path.of(path)))
      assertEquals(index, 7, path)
      replay[Dijkstra](path, LeiosStateCodecs.newEpochState[Dijkstra], nes)
    }
    assertEquals(read, Vector(0, 0), "no recorded snapshot holds an action")
  }

  private val windows: Vector[LeiosReferenceStates.Capture] =
    LeiosReferenceStates.windowP ++ LeiosReferenceStates.windowG

  private val withDijkstraKeys: Set[LeiosReferenceStates.Capture] =
    Set(1555188L, 1576750L).map(LeiosReferenceStates.Capture(LeiosReferenceStates.Window.G, _))

  // one test each, so that a state not present is skipped by name (CNF-2)
  windows.foreach { capture =>
    val name = capture.name
    if withDijkstraKeys(capture) then
      test(
        s"RATIFY over the testnet's private state $name is incomplete: its proposal of keys 47 and 48 has no flat view"
      ) {
        withInput(LeiosReferenceStates.input(capture)) { root =>
          val (_, nes) = current(stateFile(capture.state(root)))
          val state = right(name)(LeiosStateCodecs.newEpochState[Dijkstra].decode(nes))
          // its proposal updates keys 47 and 48, which the flat record of the flat rule has no
          // form for
          assert(summon[GovViews[Dijkstra]](state).isLeft, name)
        }
      }
    else
      test(s"RATIFY over the testnet's private state $name's snapshot reaches its ratification") {
        withInput(LeiosReferenceStates.input(capture)) { root =>
          val (index, nes) = current(stateFile(capture.state(root)))
          assertEquals(index, 7, name)
          assertEquals(
            replay[Dijkstra](name, LeiosStateCodecs.newEpochState[Dijkstra], nes),
            0,
            name
          )
        }
      }
  }

  test(
    "ENACT of window G's ratified update at 1,576,750 gives the stored parameters and roots"
  ) {
    // The pulser of the state at the end of epoch 72 holds one action, the one its ratification
    // enacted: a change of keys 47 and 48. ENACT of it on the enact state before it, the live
    // current and previous parameters and the live roots, read field by field from the state's
    // bytes, gives the stored enact state's parameters and roots. RATIFY over this snapshot stays
    // incomplete (CNF-2): the voting inputs at the epoch's start are not established.
    val capture = LeiosReferenceStates.Capture(LeiosReferenceStates.Window.G, 1576750L)
    val name = capture.name
    withInput(LeiosReferenceStates.input(capture)) { root =>
      val path = capture.state(root)
      val (index, nes) = current(stateFile(path))
      assertEquals(index, 7, name)
      val gov = at(nes, 3, 1, 1, 3)
      val pulser = at(gov, 6)
      val enact = at(pulser, 1, 0)
      val snapshot = items(at(pulser, 0, 0))
      val enacted = items(at(pulser, 1, 1))
      assertEquals(enacted.size, 1, name)
      assertEquals(snapshot.map(_.original), enacted.map(_.original), name)
      val gas = items(enacted(0))
      val id = right(s"$name id")(DijkstraGovActionId.decode(gas(0))) match
        case DijkstraGovActionId(tx, ix) => GovActionId(tx, BigInt(ix))
      val proposal = right(s"$name proposal")(DijkstraProposals.proposal(gas(4)))
      given DecodeAt = DecodeAt(DecoderVersion.Dijkstra, DecodeField.ParameterUpdate)
      def pparams(n: Node): PParams[Dijkstra] =
        right(s"$name parameters")(PParams.decodePParams[Dijkstra](n))
      def roots(n: Node): GovRelation[Option] =
        ConwayProposals.roots(right(s"$name roots")(LeiosStateCodecs.prevIds.decode(n)))
      val liveRoots = roots(at(gov, 0, 0))
      proposal.pProcGovAction match
        case GovAction.ParameterChange(parent, _, _) =>
          assertEquals(parent, liveRoots.grPParamUpdate, name)
        case other => fail(s"$name: $other")
      // the live current parameters differ from the stored enact state's at keys 47 and 48 only (the
      // record's positions 44 and 45), the previous ones from its previous ones nowhere
      assertEquals(differing(at(gov, 3), at(enact, 2)), Vector(44, 45), name)
      assertEquals(differing(at(gov, 4), at(enact, 3)), Vector.empty, name)
      val before = EnactState[Dijkstra](
        None,
        ConwayGenesis.noConstitution,
        pparams(at(gov, 3)),
        pparams(at(gov, 4)),
        Coin.zero,
        Sorted.empty,
        liveRoots
      )
      val after = right(s"$name ENACT")(
        ConwayEnact.enactmentTransition(before, EnactSignal[Dijkstra](id, proposal.pProcGovAction))
      )
      assertEquals(after.ensCurPParams, pparams(at(enact, 2)), name)
      assertEquals(PParams.encodePParams(after.ensCurPParams).bytes, at(enact, 2).original, name)
      assertEquals(after.ensPrevPParams, pparams(at(enact, 3)), name)
      assertEquals(after.ensPrevGovActionIds, roots(at(enact, 6)), name)
      assertEquals(
        after.ensPrevGovActionIds.grPParamUpdate,
        Some(GovPurposeId[GovActionPurpose.PParamUpdate](id)),
        name
      )
    }
  }

  test("ENACT of an imported withdrawal to an account of network 2 is the typed VocabError") {
    // The node's stored reward-account codec takes the network from the header's low nibble, 0 to
    // 15 (`NodeTypeCodecs.rewardAccount`), where the reference's account decoder refuses any header
    // but 0xe0, 0xe1, 0xf0 and 0xf1 (cardano-ledger 9df9f98c `Address.hs:930-946`). A state the
    // node imports can so hold a withdrawal to network 2, which ENACT reads at the reference's
    // `AccountAddress` and refuses; no state the reference wrote holds one.
    given ProtVer = ProtVer(Word64.fromUnsigned(10), Word64.zero)
    def node(hex: String): Node = decode(right(hex)(Bytes.fromHex(hex)))
    val hash = "05" * 28
    val account =
      right("the account")(HaskellStateCodecs.rewardAccount.decode(node(s"581de2$hash")))
    val credential = FlatCredential.Key(right(hash)(Bytes.fromHex(hash)))
    assertEquals(account, RewardAccount(2, credential))
    val action =
      right("the withdrawal")(HaskellStateCodecs.govAction.decode(node(s"8302a1581de2${hash}07f6")))
    assertEquals(action, FlatGovAction.TreasuryWithdrawals(Vector(account -> BigInt(7)), None))
    val stored = FlatGovActionState(
      GovActionId(Bytes(Vector.fill(32)(6.toByte)), BigInt(0)),
      Map.empty,
      Map.empty,
      Map.empty,
      Proposal(BigInt(100), RewardAccount(0, credential), action),
      BigInt(0),
      BigInt(6)
    )
    val gas = right("the action's state")(GovStateRecord.conway.action(stored))
    val path = "fixtures/haskell-state/conway-4593/state"
    val (_, r) = replayed[Conway](
      path,
      HaskellStateCodecs.newEpochState[Conway],
      current(stateFile(Path.of(path)))._2,
      None
    )
    assertEquals(
      ConwayEnact.enactmentTransition(
        r.end.rsEnactState.copy(ensTreasury = Coin(1000)),
        EnactSignal[Conway](gas.gasId, gas.gasAction)
      ),
      Left(VocabError.OutOfRange(VocabError.Kind.Network, 2))
    )
  }
