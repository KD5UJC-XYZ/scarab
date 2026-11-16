// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{GovFailure, PredicateFailure, ProtocolParams, Tx}
import xyz.kd5ujc.scarab.ledger.era.{EraPParams, EraTx, EraTxBody, GovAction, PParams, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx}
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayEra, ConwayGov}
import xyz.kd5ujc.scarab.ledger.rules.{CertLens, Globals, Gov, GovEnv, GovSignal, Judgment}
import xyz.kd5ujc.scarab.ledger.state.{
  CommitteeState,
  Conway,
  ConwayCertState,
  ConwayGovState,
  ConwayLedgerState,
  ConwayNewEpochState,
  ScriptHash
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellState

/** Conway's GOV predicates on the era types against the flat rule, on every transaction of the
  * recorded Conway refusal corpora and on cases crafted from them: the failures the predicates
  * report are the ones the flat GOV rule records for the same checks, each as often. A predicate
  * the corpus never fails is named, and a crafted case fails it.
  */
class ConwayGovChecksSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private val body = EraTxBody.conway
  private val top = body.top
  private given EraPParams[Conway] = EraPParams.conway

  /** One Conway case as both paths see it. */
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
      pp: PParams[Conway]
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
        val bytes: Bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item))
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
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes)),
          gov,
          ConwayNewEpochState.ls.andThen(ConwayLedgerState.cert).get(nes),
          gov.curPParams,
          PParams[Conway](
            right(s"$what typed parameters")(ConwayPParams.fromStored(gov.curPParams))
          )
        )
      }
    }

  /** The predicate each GOV failure of the ported checks comes from. */
  private def predicateOf(f: PredicateFailure): Option[String] = f match
    case PredicateFailure.Gov(g) =>
      g match
        case GovFailure.DisallowedProposalDuringBootstrap(_) => Some("checkBootstrapProposal")
        case GovFailure.MalformedProposal(_)                 => Some("actionWellFormed")
        case GovFailure.InvalidGuardrailsScriptHash(_, _)    => Some("checkGuardrailsScriptHash")
        case GovFailure.UnelectedCommitteeVoters(_)          => Some("unelectedCommitteeVoters")
        case _                                               => None
    case _ => None

  /** The failures the flat GOV rule records for the ported checks, each as often as it records it.
    */
  private def flatFailures(s: Seen): Map[PredicateFailure, Int] =
    val fb = s.flat.body
    val env = GovEnv(
      fb.id,
      s.epoch,
      s.flatPp,
      s.gov.constitution.script,
      s.cert,
      s.gov.committee
    )
    val signal = GovSignal(
      fb.votes.map((voter, votes) => voter -> votes.map((id, p) => id -> p.vote)),
      fb.proposals,
      fb.certificates
    )
    val recorded =
      right(s"${s.label}: a defect")(
        Judgment.run(Gov, Judgment.Policy.Accumulate)(s.globals, env, s.gov.proposals, signal)
      ).fold(_.toChain.toVector, _ => Vector.empty)
    recorded.filter(predicateOf(_).isDefined).groupMapReduce(identity)(_ => 1)(_ + _)

  /** The failures the typed predicates report, as the reference's transition runs them: per
    * proposal the bootstrap check, the well-formedness and the guardrails of a withdrawal or a
    * parameter change; from version 11 the unelected committee voters.
    */
  private def typedFailures(s: Seen): Map[PredicateFailure, Int] =
    val tx = EraTx.conway
    val tb = tx.body[TxLevel.Top](s.typed)(using tx.bodyOf.top)
    val pv = summon[EraPParams[Conway]].ppProtocolVersionL.get(s.pp)
    val constitutionPolicy =
      s.gov.constitution.script.map(b => right(s"${s.label}: guardrails")(ScriptHash.fromBytes(b)))
    val proposals = body
      .proposalProcedures[TxLevel.Top](tb)(using top)
      .toVector
      .map { p =>
        val guardrails = p.pProcGovAction match
          case GovAction.TreasuryWithdrawals(_, policy) =>
            ConwayGov.checkGuardrailsScriptHash(constitutionPolicy, policy)
          case GovAction.ParameterChange(_, _, policy) =>
            ConwayGov.checkGuardrailsScriptHash(constitutionPolicy, policy)
          case GovAction.HardForkInitiation(_, _) | GovAction.NoConfidence(_) |
              GovAction.UpdateCommittee(_, _, _, _) | GovAction.NewConstitution(_, _) |
              GovAction.InfoAction() =>
            xyz.kd5ujc.scarab.ledger.rules.Test.pass[PredicateFailure]
        Vector(
          ConwayGov.checkBootstrapProposal[Conway](s.pp, p),
          ConwayGov.actionWellFormed[Conway](pv, p.pProcGovAction)(p.read.action),
          guardrails
        )
      }
      .flatten
    val unelected =
      if ConwayEra.hardforkConwayDisallowUnelectedCommitteeFromVoting(pv) then
        val bad = ConwayGov.unelectedCommitteeVoters[Conway](
          s.gov.committee,
          CommitteeState(CertLens.committee.get(s.cert)),
          body.votingProcedures[TxLevel.Top](tb)(using top)
        )
        Vector(
          xyz.kd5ujc.scarab.ledger.rules.Test.failureUnless(
            bad.isEmpty,
            PredicateFailure.gov(GovFailure.UnelectedCommitteeVoters(bad.toVector))
          )
        )
      else Vector.empty
    (proposals ++ unelected)
      .flatMap(xyz.kd5ujc.scarab.ledger.rules.Test.failures(_).toVector.flatMap(_.toChain.toVector))
      .groupMapReduce(identity)(_ => 1)(_ + _)

  private def compared(s: Seen): Map[PredicateFailure, Int] =
    val (flat, typed) = (flatFailures(s), typedFailures(s))
    assertEquals(typed, flat, s.label)
    flat

  test("the typed GOV predicates report the flat rule's failures on every Conway case"):
    assert(seen.nonEmpty)
    seen.foreach(compared)

  /** The predicates the corpus fails somewhere. */
  private lazy val refusedSomewhere: Set[String] =
    seen.flatMap(s => flatFailures(s).keys.flatMap(predicateOf)).toSet

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
      typed = right("edited typed")(ConwayTx.fromEnvelope(again))
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
    val header = if s.globals.networkId == 1 then "e1" else "e0"
    s"581d$header${"a1" * 28}"
  private val anchor = "826161" + "5820" + "cc" * 32
  private def proposal(s: Seen, action: String): String = s"8400${account(s)}$action$anchor"
  private def tagged(set: String*): String = s"d90102${"8" + set.size.toString}${set.mkString}"

  private def refused(s: Seen, predicate: String): Unit =
    val flat = compared(s)
    assert(flat.keys.exists(predicateOf(_).contains(predicate)), s"${s.label}: $predicate passes")

  private val script = "51" * 28

  test("crafted: a treasury withdrawal proposed in the bootstrap phase"):
    val s = withVersion(seen.head, 9)
    refused(
      edited(s, Some(tagged(proposal(s, s"8302a1${account(s)}01f6"))), None),
      "checkBootstrapProposal"
    )

  test("crafted: an update setting the maximum transaction size to zero"):
    val s = withGuardrails(withVersion(seen.head, 10), None)
    refused(edited(s, Some(tagged(proposal(s, "8400f6a10300f6"))), None), "actionWellFormed")

  test("crafted: a parameter change and a withdrawal naming another guardrails script"):
    val s = withGuardrails(withVersion(seen.head, 10), Some(Bytes.fromHex("52" * 28).toOption.get))
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
      "checkGuardrailsScriptHash"
    )

  /** At version 11, votes of two committee keys that no elected member authorised: a script and a
    * key hot credential, the key's first in the map, so out of the order of `Ord Voter`, which puts
    * a script credential before a key one.
    */
  private def unelectedVotes(first: String, second: String): Seen =
    val s = withVersion(seen.head, 11)
    val action = "825820" + "dd" * 32 + "00"
    val vote = "8201f6"
    edited(s, None, Some(s"a2${first}a1$action$vote${second}a1$action$vote"))

  private val hotKey = "8200581c" + "4b" * 28
  private val hotScript = "8201581c" + "4c" * 28

  test("crafted: at version 11, votes of committee keys no elected member authorised"):
    refused(unelectedVotes(hotScript, hotKey), "unelectedCommitteeVoters")

  test("a difference found: the flat rule lists unelected voters in the body's order"):
    // The reference collects them into a `Set (Credential HotCommitteeRole)` and reports its
    // ascending list (`Conway/Rules/Gov.hs:478-481,652-665`, cardano-ledger f649f975); the flat rule
    // lists them in the order the body's map arrived in, which the decoder does not require to be
    // the map's order. Reported to the lead; the flat path is not changed here.
    val s = unelectedVotes(hotKey, hotScript)
    val (flat, typed) = (flatFailures(s), typedFailures(s))
    def voters(fs: Map[PredicateFailure, Int]) = fs.keys.collect {
      case PredicateFailure.Gov(GovFailure.UnelectedCommitteeVoters(cs)) => cs
    }.toVector
    assertEquals(voters(typed).map(_.toSet), voters(flat).map(_.toSet))
    assertEquals(voters(typed).map(_.reverse), voters(flat))

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
