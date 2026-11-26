// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.StateLayout
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.ledger.{Credential, ProtocolParams, Tx}
import xyz.kd5ujc.scarab.ledger.era.{
  EraGovRun,
  EraPParams,
  EraTx,
  EraTxBody,
  GovAction,
  PParams,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayEra,
  ConwayGov,
  ConwayGovEvent,
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  GovEnv as EraGovEnv
}
import xyz.kd5ujc.scarab.ledger.rules.{CertLens, Globals, Test}
import xyz.kd5ujc.scarab.ledger.state.{
  CommitteeState,
  Conway,
  ConwayCertState,
  ConwayGovState,
  ConwayLedgerState,
  ConwayNewEpochState,
  EpochNo,
  Hash32,
  Network,
  ProtVer,
  ScriptHash,
  ShelleyBasedEra,
  Word64
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, HaskellState}

/** Conway's GOV predicates and its whole transition on the era types, on every transaction of the
  * recorded Conway refusal corpora and on cases crafted from them. Where the flat GOV rule was the
  * oracle, before it was deleted (#85, stage 3), the era rule is held to its judgments, recorded
  * after the two were found to agree (`fixtures/conway-flat-refusals/gov-judgments.jsonl`): the
  * failures each ported predicate reports, each as often, and the whole transition's failures in
  * the reference's order at the root, or the proposals after. A predicate the corpus never fails is
  * named, and a crafted case fails it. The corpora the flat rule judged are named
  * ([[RecordedCorpora]]); those recorded for the era rules alone are bound to the reference's bytes
  * by `ReferenceRefusalSuite` and by this suite's reference tests.
  */
class ConwayGovChecksSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private val body = EraTxBody.conway
  private val top = body.top
  private given EraPParams[Conway] = EraPParams.conway

  /** One Conway case as both decoders read it. */
  private final case class Seen(
      label: String,
      bytes: Bytes,
      globals: Globals,
      epoch: BigInt,
      flat: Tx,
      typed: ConwayTx,
      gov: ConwayGovState,
      cert: ConwayCertState,
      flatPp: ProtocolParams,
      pp: PParams[Conway],
      families: Vector[Family],
      reason: Bytes,
      afterAdmitted: Boolean
  )

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private val seen: Vector[Seen] =
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    loaded.corpora.filter(_.era == RefusalEra.Conway).flatMap { c =>
      val setup = right(s"${c.path} genesis")(
        NodeSetup.fromFiles(c.build)(name => c.genesis.get(name).map(_.toArray))
      )
      val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes: Bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val nes = right(s"$what state")(
          codecs.newEpochState[Conway].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val gov = ConwayNewEpochState.gov.get(nes)
        Seen(
          what,
          bytes,
          Globals(setup.globals, setup.geometry),
          nes.epoch.toBigInt,
          right(s"$what flat")(Tx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes, StateLayout.Node11_1)),
          gov,
          ConwayNewEpochState.ls.andThen(ConwayLedgerState.cert).get(nes),
          gov.curPParams,
          PParams[Conway](
            right(s"$what typed parameters")(ConwayPParams.fromStored(gov.curPParams))
          ),
          rc.families,
          rc.reason,
          rc.before.nonEmpty
        )
      }
    }

  /** The corpora the flat GOV judged before it was deleted, whose judgments are recorded, by name:
    * a corpus added later is not among them.
    */
  private val RecordedCorpora: Set[String] = Set(
    "conway-bootstrap-attributes",
    "conway-bootstrap-attributes-leios-b004fe0c",
    "conway-deleg-collateral",
    "conway-devnet",
    "conway-gov-proposals",
    "conway-governance",
    "conway-three-certificates",
    "conway-v11-coverage"
  )

  /** The cases of [[RecordedCorpora]]. */
  private lazy val recordedSeen: Vector[Seen] =
    seen.filter(s => RecordedCorpora.exists(c => s.label.startsWith(s"$c/")))

  /** The case the crafted cases are made from, by its label. */
  private lazy val base: Seen =
    val label = "conway-bootstrap-attributes/utxo-bootstrap-attributes-too-big"
    seen.find(_.label == label).getOrElse(fail(s"no case $label"))

  /** The recorded judgment `id`, failing the test where none was recorded. */
  private def recorded(id: String): Vector[String] =
    FlatRecordings.govJudgments.getOrElse(id, fail(s"no recording of $id"))

  /** The predicate each GOV failure of the ported checks comes from. */
  private def predicateOf(f: ConwayGovPredFailure[Conway]): Option[String] = f match
    case ConwayGovPredFailure.DisallowedProposalDuringBootstrap(_) => Some("checkBootstrapProposal")
    case ConwayGovPredFailure.MalformedProposal(_)                 => Some("actionWellFormed")
    case ConwayGovPredFailure.InvalidGuardrailsScriptHash(_, _) => Some("checkGuardrailsScriptHash")
    case ConwayGovPredFailure.UnelectedCommitteeVoters(_)       => Some("unelectedCommitteeVoters")
    case _                                                      => None

  /** Conway's GOV whole on the era types, `depth` levels down: its failures, or the proposals after
    * as the node records them, with GOV's events.
    */
  private def eraRun(
      s: Seen,
      depth: Int = 0
  ): Either[Vector[
    ConwayGovPredFailure[Conway]
  ], (xyz.kd5ujc.scarab.ledger.Proposals, EraGovRun.Events)] =
    eraGov(s, depth) match
      case Left(fs)               => Left(fs.toChain.toVector)
      case Right((after, events)) => Right((EraGovRun.toStored(after), events))

  /** Conway's GOV whole on the era types, `depth` levels down: its failures, or the proposals after
    * with GOV's events.
    */
  private def eraGov(s: Seen, depth: Int) =
    val tx = EraTx.conway
    val tb = tx.body[TxLevel.Top](s.typed)(using tx.bodyOf.top)
    val signal = EraGovRun.signalOf(s.typed)
    val env = EraGovEnv[Conway](
      right(s"${s.label}: id")(Hash32.fromBytes(body.id[TxLevel.Top](tb)(using top))),
      right(s"${s.label}: epoch")(EpochNo.fromBigInt(s.epoch)),
      s.pp,
      s.gov.constitution.script.map(b => right(s"${s.label}: guardrails")(ScriptHash.fromBytes(b))),
      s.cert,
      s.gov.committee
    )
    val netId = s.globals.networkId
    val state = right(s"${s.label}: the stored proposals")(EraGovRun.proposals(s.gov.proposals))
    right(s"${s.label}: a defect")(EraGovRun.run(env, signal, netId, state, depth))

  /** The failures the typed predicates report, as the reference's transition runs them: per
    * proposal the bootstrap check, the well-formedness and the guardrails of a withdrawal or a
    * parameter change; from version 11 the unelected committee voters.
    */
  private def typedFailures(s: Seen): Vector[ConwayGovPredFailure[Conway]] =
    val tx = EraTx.conway
    val tb = tx.body[TxLevel.Top](s.typed)(using tx.bodyOf.top)
    val pv = summon[EraPParams[Conway]].ppProtocolVersionL.get(s.pp)
    val constitutionPolicy =
      s.gov.constitution.script.map(b => right(s"${s.label}: guardrails")(ScriptHash.fromBytes(b)))
    val procedures = body.proposalProcedures[TxLevel.Top](tb)(using top).toVector
    val proposals = procedures.map { p =>
      val guardrails = p.pProcGovAction match
        case GovAction.TreasuryWithdrawals(_, policy) =>
          ConwayGov.checkGuardrailsScriptHash[Conway](constitutionPolicy, policy)
        case GovAction.ParameterChange(_, _, policy) =>
          ConwayGov.checkGuardrailsScriptHash[Conway](constitutionPolicy, policy)
        case GovAction.HardForkInitiation(_, _) | GovAction.NoConfidence(_) |
            GovAction.UpdateCommittee(_, _, _, _) | GovAction.NewConstitution(_, _) |
            GovAction.InfoAction() =>
          Test.pass[ConwayGovPredFailure[Conway]]
      Vector(
        ConwayGov.checkBootstrapProposal[Conway](s.pp, p),
        ConwayGov.actionWellFormed[Conway](pv, p.pProcGovAction),
        guardrails
      )
    }.flatten
    val unelected =
      if ConwayEra.hardforkConwayDisallowUnelectedCommitteeFromVoting(pv) then
        val bad = ConwayGov.unelectedCommitteeVoters[Conway](
          s.gov.committee,
          CommitteeState(CertLens.committee.get(s.cert)),
          body.votingProcedures[TxLevel.Top](tb)(using top)
        )
        NonEmptyList
          .fromList(bad.toList)
          .map(ConwayGovPredFailure.UnelectedCommitteeVoters[Conway](_))
          .toVector
      else Vector.empty
    proposals.flatMap(Test.failures(_).toVector.flatMap(_.toChain.toVector)) ++ unelected

  // ---- the recorded cells (`fixtures/conway-flat-refusals/README.md`)

  /** A refusal's cell: `R:` and the digest of the `ApplyTxErr` bytes of `failures`, in their order,
    * each under LEDGER's `ConwayGovFailure`, as the node sends GOV's refusal.
    */
  private def refusedCell(failures: Iterable[ConwayGovPredFailure[Conway]]): String =
    "R:" + FlatRecordings.digest(
      ConwayFailureCborCheck.direct(failures.map(ConwayLedgerPredFailure.ConwayGovFailure(_)))
    )

  /** An admission's cell: `A:` and the digest of `s` 's governance state with the proposals
    * `after`, in the Haskell state's encoding of 11.1's layout at protocol version 9.
    */
  private def admittedCell(s: Seen, after: xyz.kd5ujc.scarab.ledger.Proposals): String =
    given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    val bytes = HaskellLedgerFile
      .codecs(StateLayout.Node11_1)
      .govState[Conway]
      .write(s.gov.copy(proposals = after))
      .fold(e => fail(s"${s.label}: the state: $e"), identity)
    "A:" + FlatRecordings.digest(bytes)

  /** The whole transition's cell at the root. */
  private def wholeCell(s: Seen): String = eraRun(s) match
    case Left(fs)          => refusedCell(fs)
    case Right((after, _)) => admittedCell(s, after)

  /** The ported predicates' cell: each failure's own refusal cell, sorted, as the flat rule's
    * failures were compared, each as often; `-` for none.
    */
  private def predicatesCell(s: Seen): String =
    val fs = typedFailures(s)
    if fs.isEmpty then "-" else fs.map(f => refusedCell(Vector(f))).sorted.mkString(",")

  test("the typed GOV predicates report the flat rule's recorded failures on every recorded case"):
    assert(recordedSeen.nonEmpty)
    recordedSeen.foreach { s =>
      assertEquals(Vector(predicatesCell(s)), recorded(s"predicates/${s.label}"), s.label)
    }

  /** The predicates the corpus fails somewhere. */
  private lazy val refusedSomewhere: Set[String] =
    recordedSeen.flatMap(s => typedFailures(s).flatMap(predicateOf)).toSet

  // ---- crafted from a recorded case

  private def key(k: Int): Node = Node(Value.UInt(k), Bytes.empty)
  private def isKey(k: Int)(n: Node): Boolean = n.value == Value.UInt(k)

  private def node(hex: String): Node =
    right(hex)(Cbor.decode(right(hex)(Bytes.fromHex(hex))))

  /** `s` with its body's proposals those of `proposals` (key 20, an ordered set) and its votes
    * those of `votes` (key 19), when given.
    */
  private def edited(s: Seen, proposals: Option[String], votes: Option[String]): Seen =
    val root = right("envelope")(Cbor.decode(s.bytes))
    val again = root.value match
      case Value.Arr(parts) =>
        parts.head.value match
          case Value.Map(fields) =>
            val withProposals = proposals.fold(fields)(hex =>
              fields.filterNot((k, _) => isKey(20)(k)) :+ (key(20) -> node(hex))
            )
            val withVotes = votes.fold(withProposals)(hex =>
              withProposals.filterNot((k, _) => isKey(19)(k)) :+ (key(19) -> node(hex))
            )
            right("re-encode")(
              Cbor.encode(
                Value.Arr(Node(Value.Map(withVotes), Bytes.empty) +: parts.tail),
                Cbor.Limits.Unbounded
              )
            )
          case other => fail(s"a body that is not a map: $other")
      case other => fail(s"an envelope that is not an array: $other")
    s.copy(
      flat = right("edited flat")(Tx.fromEnvelope(again, Era.Conway)),
      typed = right("edited typed")(ConwayTx.fromEnvelope(again, StateLayout.Node11_1))
    )

  private def withVersion(s: Seen, major: Int): Seen =
    val flatPp = s.flatPp.copy(protocolMajor = major)
    s.copy(
      flatPp = flatPp,
      gov = s.gov.copy(curPParams = flatPp),
      pp = PParams[Conway](right("edited parameters")(ConwayPParams.fromStored(flatPp)))
    )

  private def withGuardrails(s: Seen, script: Option[Bytes]): Seen =
    s.copy(gov = s.gov.copy(constitution = s.gov.constitution.copy(script = script)))

  // a proposal `[deposit, return account, action, anchor]` on the case's network
  private def account(s: Seen): String =
    val header = if s.globals.networkId == Network.Mainnet then "e1" else "e0"
    s"581d$header${"a1" * 28}"
  private val anchor = "826161" + "5820" + "cc" * 32
  private def proposal(s: Seen, action: String): String = s"8400${account(s)}$action$anchor"
  private def tagged(set: String*): String = s"d90102${"8" + set.size.toString}${set.mkString}"

  /** A crafted case `id` 's predicates give the recorded failures, and `predicate` fails. */
  private def refused(s: Seen, id: String, predicate: String): Unit =
    assertEquals(Vector(predicatesCell(s)), recorded(s"predicates/crafted/$id"), id)
    assert(typedFailures(s).exists(predicateOf(_).contains(predicate)), s"$id: $predicate passes")

  private val script = "51" * 28

  test("crafted: a treasury withdrawal proposed in the bootstrap phase"):
    val s = withVersion(base, 9)
    refused(
      edited(s, Some(tagged(proposal(s, s"8302a1${account(s)}01f6"))), None),
      "bootstrap-treasury-withdrawal",
      "checkBootstrapProposal"
    )

  test("crafted: an update setting the maximum transaction size to zero"):
    val s = withGuardrails(withVersion(base, 10), None)
    refused(
      edited(s, Some(tagged(proposal(s, "8400f6a10300f6"))), None),
      "max-tx-size-zero",
      "actionWellFormed"
    )

  test("crafted: a parameter change and a withdrawal naming another guardrails script"):
    val s = withGuardrails(withVersion(base, 10), Some(right("script")(Bytes.fromHex("52" * 28))))
    refused(
      edited(
        s,
        Some(
          tagged(
            proposal(s, s"8400f6a10a${"d81e820101"}581c$script"),
            proposal(s, s"8302a1${account(s)}01581c$script")
          )
        ),
        None
      ),
      "other-guardrails",
      "checkGuardrailsScriptHash"
    )

  /** At version 11, votes of two committee keys that no elected member authorised: a script and a
    * key hot credential, the key's first in the map, so out of the order of `Ord Voter`, which puts
    * a script credential before a key one.
    */
  private def unelectedVotes(first: String, second: String): Seen =
    val s = withVersion(base, 11)
    val action = "825820" + "dd" * 32 + "00"
    val vote = "8201f6"
    edited(s, None, Some(s"a2${first}a1$action$vote${second}a1$action$vote"))

  private val hotKey = "8200581c" + "4b" * 28
  private val hotScript = "8201581c" + "4c" * 28

  test("crafted: at version 11, votes of committee keys no elected member authorised"):
    refused(unelectedVotes(hotScript, hotKey), "unelected-script-first", "unelectedCommitteeVoters")

  /** The reference collects the unelected voters into a `Set (Credential HotCommitteeRole)` and
    * reports its ascending list (`Conway/Rules/Gov.hs:478-481,652-665`, cardano-ledger f649f975), a
    * script credential before a key one, whatever order the body's map arrived in; the era rule
    * does, as the flat rule did (each order's recorded failures).
    */
  test("unelected voters are listed in the reference's order, whatever the body's"):
    def voters(fs: Vector[ConwayGovPredFailure[Conway]]) = fs.collect {
      case ConwayGovPredFailure.UnelectedCommitteeVoters(cs) => cs.toList.toVector
    }
    val reported = Vector(
      (hotKey, hotScript, "unelected-key-first"),
      (hotScript, hotKey, "unelected-script-first")
    ).map { (first, second, id) =>
      val s = unelectedVotes(first, second)
      assertEquals(Vector(predicatesCell(s)), recorded(s"predicates/crafted/$id"), id)
      val typed = voters(typedFailures(s))
      assertEquals(typed.map(_.size), Vector(2), s"$first first")
      typed
    }
    assertEquals(reported(0), reported(1))
    assert(
      reported(0).flatten.headOption.exists {
        case Credential.Script(_) => true
        case Credential.Key(_)    => false
      },
      s"a script credential first: ${reported(0)}"
    )

  test("the corpus fails the predicates it can tell apart, and names the rest"):
    val all = Set(
      "checkBootstrapProposal",
      "actionWellFormed",
      "checkGuardrailsScriptHash",
      "unelectedCommitteeVoters"
    )
    assertEquals(all -- refusedSomewhere, NeverRefused)

  /** The predicates no Conway case of the corpus fails: a crafted case above fails each. */
  private val NeverRefused: Set[String] = Set("checkBootstrapProposal")

  // ---- the whole transition

  /** `s` at its own protocol version and at 9, 10 and 11. */
  private def versionsOf(s: Seen): Vector[Seen] =
    s +: Vector(9, 10, 11).map(v => withVersion(s, v).copy(label = s"${s.label}@$v"))

  /** Each case at its own protocol version and at 9, 10 and 11. */
  private lazy val versions: Vector[Seen] = seen.flatMap(versionsOf)

  test("the whole era-typed GOV gives the flat rule's recorded verdicts on every recorded case"):
    // the flat GOV, before it was deleted, judged each case at the root at its recorded protocol
    // version and at 9, 10 and 11, and the era GOV gave the same failures in the same order, or the
    // same proposals after: 117 refusals and 219 admissions
    val cells = recordedSeen.flatMap { s =>
      val judged = versionsOf(s).map(wholeCell)
      assertEquals(judged, recorded(s"whole/${s.label}"), s.label)
      judged
    }
    assertEquals(cells.count(_.startsWith("R:")), 117, "the refusals compared")
    assertEquals(cells.count(_.startsWith("A:")), 219, "the admissions compared")

  test("the recording of the flat GOV's judgments holds this suite's alone, each once"):
    val crafted = Set(
      "predicates/crafted/bootstrap-treasury-withdrawal",
      "predicates/crafted/max-tx-size-zero",
      "predicates/crafted/other-guardrails",
      "predicates/crafted/unelected-script-first",
      "predicates/crafted/unelected-key-first",
      "whole/crafted/committee-conflict-expiry"
    )
    val corpus = recordedSeen.map(_.label).toSet
    assertEquals(corpus.size, 84, "the recorded cases")
    assertEquals(
      FlatRecordings.govJudgments.keySet,
      corpus.map(l => s"predicates/$l") ++ corpus.map(l => s"whole/$l") ++ crafted
    )

  test("GOV's events: the proposals after, the transaction's id and the DReps it unregisters"):
    versions.foreach { s =>
      eraRun(s).foreach { (after, events) =>
        val tx = EraTx.conway
        val id = right(s"${s.label}: id")(
          Hash32.fromBytes(
            body.id[TxLevel.Top](tx.body[TxLevel.Top](s.typed)(using tx.bodyOf.top))(using top)
          )
        )
        events.toList match
          case List(
                ConwayGovEvent.GovNewProposals(newId, proposals),
                ConwayGovEvent.GovRemovedVotes(removedId, _, unregistered)
              ) =>
            assertEquals(newId, id, s.label)
            assertEquals(removedId, id, s.label)
            assertEquals(EraGovRun.toStored(proposals), after, s.label)
            assertEquals(
              unregistered.toSet.map(xyz.kd5ujc.scarab.ledger.rules.Convert.back(_)),
              s.flat.body.certificates.collect {
                case xyz.kd5ujc.scarab.ledger.Certificate.UnregisterDRep(c, _) => c
              }.toSet,
              s.label
            )
          case other => fail(s"${s.label}: events $other")
      }
    }

  /** The failures of GOV no run above reaches, each named: none. */
  test("the whole transition's runs reach every failure of GOV, or name it"):
    val reached = versions
      .flatMap(s => eraRun(s).left.toOption.toVector.flatten)
      .map(_.productPrefix)
      .toSet
    // the 19 constructors of `ConwayGovPredFailure` (`Conway/Rules/Gov.hs:237-255`, f649f975)
    val all = Set(
      "GovActionsDoNotExist",
      "MalformedProposal",
      "ProposalProcedureNetworkIdMismatch",
      "TreasuryWithdrawalsNetworkIdMismatch",
      "ProposalDepositIncorrect",
      "DisallowedVoters",
      "ConflictingCommitteeUpdate",
      "ExpirationEpochTooSmall",
      "InvalidPrevGovActionId",
      "VotingOnExpiredGovAction",
      "ProposalCantFollow",
      "InvalidGuardrailsScriptHash",
      "DisallowedProposalDuringBootstrap",
      "DisallowedVotesDuringBootstrap",
      "VotersDoNotExist",
      "ZeroTreasuryWithdrawals",
      "ProposalReturnAccountDoesNotExist",
      "TreasuryWithdrawalReturnAccountsDoNotExist",
      "UnelectedCommitteeVoters"
    )
    assertEquals(all -- reached, NeverReached)

  private val NeverReached: Set[String] = Set()

  test("where only GOV refused, the era-typed GOV's refusal is the reference's, byte for byte"):
    // GOV two levels down, under MEMPOOL and LEDGER, its failures under LEDGER's, encoded by
    // Conway's encoder as the node sends a refusal; a case the reference judged after admitting
    // items first is replayed by `ReferenceRefusalSuite`, which admits them too
    val govOnly = seen.filter(s => s.families == Vector(Family.GOV) && !s.afterAdmitted)
    assert(govOnly.nonEmpty, "no case only GOV refuses")
    govOnly.foreach { s =>
      val failures = eraGov(s, depth = 2).fold(
        _.toChain.toVector.map(ConwayLedgerPredFailure.ConwayGovFailure[Conway](_)),
        _ => fail(s"${s.label}: admitted")
      )
      assertEquals(ConwayFailureCborCheck.direct(failures).hex, s.reason.hex, s.label)
    }

  /** A voter as the body encodes it (`EncCBOR Voter`, `Procedures.hs:357-363`, cardano-ledger
    * f649f975).
    */
  private def voterHex(v: xyz.kd5ujc.scarab.ledger.Voter): String =
    def cred(key: Int, script: Int, c: Credential): String = c match
      case Credential.Key(h)    => s"820${key}581c${h.hex}"
      case Credential.Script(h) => s"820${script}581c${h.hex}"
    v match
      case xyz.kd5ujc.scarab.ledger.Voter.CommitteeHot(c) => cred(0, 1, c)
      case xyz.kd5ujc.scarab.ledger.Voter.DRepVoter(c)    => cred(2, 3, c)
      case xyz.kd5ujc.scarab.ledger.Voter.Pool(h)         => s"8204581c${h.hex}"

  /** The reference folds the votes of a `Map` (`foldrVotingProcedures`, `Procedures.hs:431-442`,
    * cardano-ledger f649f975), so the actions that do not exist are listed by voter and then
    * action, ascending, whatever order the body's maps arrived in. The flat rule, deleted since,
    * listed them as they arrived (#85's item
    * https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7054122427, closed with it).
    */
  test("crafted: votes on actions that do not exist, listed ascending whatever the body's order"):
    val s = seen.find(_.label.endsWith("/vote-on-unknown-action")).getOrElse(fail("no such case"))
    val voter = s.flat.body.votes.headOption.map(_._1).getOrElse(fail("no voter"))
    def action(ix: Int) = "825820" + "ee" * 32 + f"$ix%02x"
    val vote = "8201f6"
    val crafted = edited(s, None, Some(s"a1${voterHex(voter)}a2${action(1)}$vote${action(0)}$vote"))
    val unknown = eraRun(crafted).left.toOption.toVector.flatten.collect {
      case ConwayGovPredFailure.GovActionsDoNotExist(ids) => ids.toList.toVector.map(_.index)
    }
    assertEquals(unknown, Vector(Vector(BigInt(0), BigInt(1))))

  /** A committee update that removes and adds one member whose term has ended: the conflict is
    * checked before the expiry (`Conway/Rules/Gov.hs:551-556`, cardano-ledger f649f975), so both
    * are recorded, in that order.
    */
  test("crafted: a committee update failing the conflict and the expiry, in that order"):
    val s = withVersion(base, 10)
    val member = "8200581c" + "c1" * 28
    val update = s"8504f6d9010281${member}a1${member}00d81e820102"
    val crafted = edited(s, Some(tagged(proposal(s, update))), None)
    // the flat GOV's failures at the root, recorded
    assertEquals(Vector(wholeCell(crafted)), recorded("whole/crafted/committee-conflict-expiry"))
    val listed = eraRun(crafted, depth = 1).left.toOption.toVector.flatten.collect {
      case f @ ConwayGovPredFailure.ConflictingCommitteeUpdate(_) => f.productPrefix
      case f @ ConwayGovPredFailure.ExpirationEpochTooSmall(_)    => f.productPrefix
    }
    assertEquals(listed, Vector("ConflictingCommitteeUpdate", "ExpirationEpochTooSmall"))
