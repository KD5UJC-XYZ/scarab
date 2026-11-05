// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.HaskellShow
import cats.Eval
import cats.syntax.all.*
import java.util.Base64
import xyz.kd5ujc.scarab.plutus.{EvalRequest, PlutusData, PlutusLanguage}

import ReferenceDocument.{Doc, *}

/** The Plutus V1/V2/V3 context diagnostic instances, at plutus 507cab8c:
  * `PlutusLedgerApi/V{1,2,3}/Contexts.hs`, `V{1,2}/Tx.hs`, and `PlutusCore/Data.hs:51-60`. The
  * schemas distinguish `Pretty` from derived Haskell `Show`; e.g. a credential in a certificate is
  * shown as a constructor, while an output's address uses the Pretty instance. An Eval traversal
  * keeps arbitrary datum/redeemer nesting off the JVM stack.
  */
private[ntc] object PlutusContextText:
  enum Kind:
    case Number, Hex, Token, RawData, DataShow, Credential, Staking, Address, Value, MintValue
    case LegacyId, Id, OutRefV1, OutRefV3, OutputV1, OutputV2, InputV1, InputV2, InputV3
    case Interval, Certificate, PurposeV1, PurposeV3, ScriptInfo, TxCert, DRep, Delegatee, Voter,
      Vote
    case GovernanceId, GovernanceAction, Proposal, Constitution, ProtocolVersion, Rational
    case Datum, Redeemer, ChangedParameters, OutputDatum
    case ContextV1, ContextV2, ContextV3, InfoV1, InfoV2, InfoV3
    case Sequence(element: Kind)
    case Association(key: Kind, value: Kind)
    case Pair(first: Kind, second: Kind)
    case Optional(element: Kind)

  enum Error:
    case Shape(expected: Kind, actual: PlutusData)
    case Constructor(expected: Kind, tag: BigInt)
    case Arguments(language: PlutusLanguage, count: Int)
    case LayoutArity(expected: Int, actual: Int)

  import Kind.*
  import PlutusData.{Constr, I, B}
  private type Result[A] = Either[Error, A]
  private final case class Task(
      kind: Kind,
      data: PlutusData,
      show: Boolean = false,
      precedence: Int = 0
  )
  private final case class Plan(children: Vector[Task], assemble: Vector[Doc] => Result[Doc])
  private final case class Constructor(name: String, fields: Vector[Kind])

  private def leaf(document: Doc): Result[Plan] = Right(Plan(Vector.empty, _ => Right(document)))
  private def node(
      children: Vector[Task]
  )(assemble: PartialFunction[Vector[Doc], Doc]): Result[Plan] =
    Right(
      Plan(
        children,
        docs => assemble.lift(docs).toRight(Error.LayoutArity(children.size, docs.size))
      )
    )
  private def many(children: Vector[Task])(assemble: Vector[Doc] => Doc): Result[Plan] =
    Right(Plan(children, docs => Right(assemble(docs))))
  private def child(parent: Task, kind: Kind, data: PlutusData, precedence: Int = 0): Task =
    Task(kind, data, parent.show, precedence)
  private def shown(kind: Kind, data: PlutusData, precedence: Int = 0): Task =
    Task(kind, data, true, precedence)
  private def protect(task: Task, document: Doc, applied: Boolean = true): Doc =
    if task.show && task.precedence > 10 && applied then parens(document) else document
  private def flatList(docs: Vector[Doc]): Doc = text("[") ++ join(text(","), docs) ++ text("]")
  private def flatTuple(docs: Vector[Doc]): Doc = text("(") ++ join(text(","), docs) ++ text(")")
  private def record(name: String, labels: Vector[String], docs: Vector[Doc]): Doc =
    text(name + " {") ++ join(
      text(", "),
      labels.zip(docs).map((label, doc) => text(label + " = ") ++ doc)
    ) ++ text("}")
  private def fields(task: Task, kinds: Vector[Kind]): Result[Vector[PlutusData]] = task.data match
    case Constr(tag, values) if tag == 0 && values.size == kinds.size => Right(values)
    case other => Left(Error.Shape(task.kind, other))
  private def product(task: Task, kinds: Vector[Kind], precedence: Int = 0)(
      assemble: PartialFunction[Vector[Doc], Doc]
  ): Result[Plan] =
    fields(task, kinds).flatMap(values =>
      node(kinds.zip(values).map((kind, value) => child(task, kind, value, precedence)))(assemble)
    )
  private def sum(
      task: Task,
      constructors: Vector[Constructor],
      prettyShow: Boolean = true
  ): Result[Plan] =
    task.data match
      case Constr(tag, values) if tag.isValidInt =>
        constructors.lift(tag.toInt).toRight(Error.Constructor(task.kind, tag)).flatMap {
          constructor =>
            if constructor.fields.size != values.size then Left(Error.Shape(task.kind, task.data))
            else
              val show = prettyShow || task.show
              many(
                constructor.fields.zip(values).map((kind, value) => Task(kind, value, show, 11))
              ) { docs =>
                val body = join(text(" "), text(constructor.name) +: docs)
                protect(task, body, docs.nonEmpty)
              }
        }
      case other => Left(Error.Shape(task.kind, other))
  private def ctor(name: String, kinds: Kind*): Constructor = Constructor(name, kinds.toVector)

  private def evaluate(task: Task): Eval[Result[Doc]] = Eval.defer {
    prepare(task) match
      case Left(error) => Eval.now(Left(error))
      case Right(plan) => plan.children.traverse(evaluate).map(_.sequence.flatMap(plan.assemble))
  }

  def render(kind: Kind, data: PlutusData): Result[String] =
    evaluate(Task(kind, data)).value.map(_.render)

  def arguments(request: EvalRequest): Result[Doc] =
    val context = request.language match
      case PlutusLanguage.V1 => ContextV1
      case PlutusLanguage.V2 => ContextV2
      case PlutusLanguage.V3 => ContextV3
    if request.language == PlutusLanguage.V3 then
      request.args match
        case Vector(data) => evaluate(Task(context, data)).value
        case _            => Left(Error.Arguments(request.language, request.args.size))
    else
      val described = request.args match
        case Vector(redeemer, ctx) =>
          Right(
            Vector("Redeemer:" -> Task(RawData, redeemer), "ScriptContext:" -> Task(context, ctx))
          )
        case Vector(datum, redeemer, ctx) =>
          Right(
            Vector(
              "Datum:" -> Task(RawData, datum),
              "Redeemer:" -> Task(RawData, redeemer),
              "ScriptContext:" -> Task(context, ctx)
            )
          )
        case _ => Left(Error.Arguments(request.language, request.args.size))
      described.flatMap { values =>
        values
          .traverse { (label, task) =>
            evaluate(task).value.map(doc => Vector(text(label), doc.indent(5)))
          }
          .map(parts =>
            text(s"LegacyPlutusArgs ${request.args.size} : ") ++ line ++ text("    ") ++ vsep(
              parts.flatten
            ).align
          )
      }

  private def prepare(task: Task): Result[Plan] =
    val data = task.data
    task.kind match
      case Number =>
        data match
          case I(value) =>
            leaf(
              if task.show && task.precedence > 6 && value < 0 then parens(text(value.toString))
              else text(value.toString)
            )
          case _ => Left(Error.Shape(task.kind, data))
      case Hex | Token | Id =>
        data match
          case B(value) => leaf(text((if task.kind == Token then "0x" else "") + value.hex))
          case _        => Left(Error.Shape(task.kind, data))
      case LegacyId           => product(task, Vector(Hex)) { case Vector(id) => id }
      case RawData | DataShow => rawData(task)
      case Sequence(element) =>
        data match
          case PlutusData.List(values) =>
            many(values.map(child(task, element, _)))(docs =>
              if task.show then flatList(docs) else list(docs)
            )
          case _ => Left(Error.Shape(task.kind, data))
      case Pair(first, second) =>
        product(task, Vector(first, second)) { case Vector(a, b) =>
          if task.show then flatTuple(Vector(a, b)) else tuple(Vector(a, b))
        }
      case Association(key, value) =>
        data match
          case PlutusData.Map(entries) =>
            many(entries.flatMap((k, v) => Vector(child(task, key, k), child(task, value, v)))) {
              docs =>
                val pairs = docs
                  .grouped(2)
                  .map(pair => if task.show then flatTuple(pair) else tuple(pair))
                  .toVector
                if task.show then
                  protect(task, text("Map {unMap = ") ++ flatList(pairs) ++ text("}"))
                else list(pairs)
            }
          case _ => Left(Error.Shape(task.kind, data))
      case Optional(element) =>
        data match
          case Constr(tag, Vector()) if tag == 1 =>
            leaf(if task.show then text("Nothing") else Doc.Empty)
          case Constr(tag, Vector(value)) if tag == 0 =>
            node(Vector(child(task, element, value, if task.show then 11 else 0))) {
              case Vector(doc) =>
                if task.show then protect(task, text("Just ") ++ doc) else doc
            }
          case _ => Left(Error.Shape(task.kind, data))
      case Credential =>
        if task.show then
          sum(task, Vector(ctor("PubKeyCredential", Hex), ctor("ScriptCredential", Hex)))
        else
          sum(task, Vector(ctor("PubKeyCredential:", Hex), ctor("ScriptCredential:", Hex)), false)
      case Staking =>
        if task.show then
          sum(
            task,
            Vector(ctor("StakingHash", Credential), ctor("StakingPtr", Number, Number, Number))
          )
        else
          sum(
            task,
            Vector(ctor("StakingHash", Credential), ctor("StakingPtr:", Number, Number, Number)),
            false
          )
      case Address =>
        fields(task, Vector(Credential, Optional(Staking))).flatMap {
          case Vector(credential, stake) =>
            if task.show then
              node(Vector(shown(Credential, credential), shown(Optional(Staking), stake))) {
                case Vector(cred, staking) =>
                  protect(
                    task,
                    record(
                      "Address",
                      Vector("addressCredential", "addressStakingCredential"),
                      Vector(cred, staking)
                    )
                  )
              }
            else
              stake match
                case Constr(tag, Vector()) if tag == 1 =>
                  node(Vector(Task(Credential, credential))) { case Vector(cred) =>
                    cred.space(text("(no staking credential)"))
                  }
                case _ =>
                  node(Vector(Task(Credential, credential), Task(Optional(Staking), stake))) {
                    case Vector(cred, staking) => cred.space(parens(staking))
                  }
          case _ => Left(Error.Shape(task.kind, data))
        }
      case Value | MintValue =>
        val association = Association(Hex, Association(Token, Number))
        node(Vector(shown(association, data))) { case Vector(doc) =>
          val (name, field) =
            if task.kind == Value then ("Value", "getValue") else ("UnsafeMintValue", "unMintValue")
          protect(task, record(name, Vector(field), Vector(doc)))
        }
      case OutRefV1 | OutRefV3 =>
        val idKind = if task.kind == OutRefV1 then LegacyId else Id
        product(task, Vector(idKind, Number)) { case Vector(id, index) =>
          if task.show then
            protect(
              task,
              record("TxOutRef", Vector("txOutRefId", "txOutRefIdx"), Vector(id, index))
            )
          else id ++ text("!") ++ index
        }
      case InputV1 | InputV2 | InputV3 =>
        val outRef = if task.kind == InputV3 then OutRefV3 else OutRefV1
        val output = if task.kind == InputV1 then OutputV1 else OutputV2
        product(task, Vector(outRef, output)) { case Vector(ref, out) =>
          if task.show then
            protect(
              task,
              record("TxInInfo", Vector("txInInfoOutRef", "txInInfoResolved"), Vector(ref, out))
            )
          else ref.space(text("->")).space(out)
        }
      case OutputV1 =>
        fields(task, Vector(Address, Value, Optional(Hex))).flatMap { values =>
          if task.show then
            many(Vector(Address, Value, Optional(Hex)).zip(values).map((k, v) => shown(k, v))) {
              docs =>
                protect(
                  task,
                  record("TxOut", Vector("txOutAddress", "txOutValue", "txOutDatumHash"), docs)
                )
            }
          else
            node(Vector(Address, Value).zip(values).map((k, v) => Task(k, v))) {
              case Vector(address, value) =>
                vsep(Vector(text("-").space(value).space(text("addressed to")), address)).hang(2)
            }
        }
      case OutputV2 =>
        product(task, Vector(Address, Value, OutputDatum, Optional(Hex))) {
          case Vector(address, value, datum, script) =>
            if task.show then
              protect(
                task,
                record(
                  "TxOut",
                  Vector("txOutAddress", "txOutValue", "txOutDatum", "txOutReferenceScript"),
                  Vector(address, value, datum, script)
                )
              )
            else
              vsep(
                Vector(
                  text("-").space(value).space(text("addressed to")),
                  address,
                  text("with datum"),
                  datum,
                  text("with referenceScript"),
                  script
                )
              ).hang(2)
        }
      case OutputDatum =>
        if task.show then
          sum(
            task,
            Vector(ctor("NoOutputDatum"), ctor("OutputDatumHash", Hex), ctor("OutputDatum", Datum))
          )
        else
          data match
            case Constr(tag, Vector()) if tag == 0 => leaf(text("no datum"))
            case Constr(tag, Vector(hash)) if tag == 1 =>
              node(Vector(Task(Hex, hash))) { case Vector(doc) => text("datum hash: ").space(doc) }
            case Constr(tag, Vector(inline)) if tag == 2 =>
              node(Vector(Task(RawData, inline))) { case Vector(doc) =>
                text("inline datum : ").space(doc)
              }
            case _ => Left(Error.Shape(task.kind, data))
      case Datum | Redeemer | ChangedParameters =>
        if !task.show then node(Vector(Task(RawData, data))) { case Vector(doc) => doc }
        else
          val (name, field) = task.kind match
            case Datum    => ("Datum", "getDatum")
            case Redeemer => ("Redeemer", "getRedeemer")
            case _        => ("ChangedParameters", "getChangedParameters")
          node(Vector(shown(DataShow, data))) { case Vector(doc) =>
            protect(task, record(name, Vector(field), Vector(doc)))
          }
      case Interval => interval(task)
      case Certificate =>
        sum(
          task,
          Vector(
            ctor("DCertDelegRegKey", Staking),
            ctor("DCertDelegDeRegKey", Staking),
            ctor("DCertDelegDelegate", Staking, Hex),
            ctor("DCertPoolRegister", Hex, Hex),
            ctor("DCertPoolRetire", Hex, Number),
            ctor("DCertGenesis"),
            ctor("DCertMir")
          )
        )
      case PurposeV1 =>
        sum(
          task,
          Vector(
            ctor("Minting", Hex),
            ctor("Spending", OutRefV1),
            ctor("Rewarding", Staking),
            ctor("Certifying", Certificate)
          )
        )
      case PurposeV3 =>
        sum(
          task,
          Vector(
            ctor("Minting", Hex),
            ctor("Spending", OutRefV3),
            ctor("Rewarding", Credential),
            ctor("Certifying", Number, TxCert),
            ctor("Voting", Voter),
            ctor("Proposing", Number, Proposal)
          )
        )
      case ScriptInfo =>
        sum(
          task,
          Vector(
            ctor("MintingScript", Hex),
            ctor("SpendingScript", OutRefV3, Optional(Datum)),
            ctor("RewardingScript", Credential),
            ctor("CertifyingScript", Number, TxCert),
            ctor("VotingScript", Voter),
            ctor("ProposingScript", Number, Proposal)
          )
        )
      case DRep =>
        sum(
          task,
          Vector(
            ctor("DRep", Credential),
            ctor("DRepAlwaysAbstain"),
            ctor("DRepAlwaysNoConfidence")
          )
        )
      case Delegatee =>
        sum(
          task,
          Vector(
            ctor("DelegStake", Hex),
            ctor("DelegVote", DRep),
            ctor("DelegStakeVote", Hex, DRep)
          )
        )
      case Voter =>
        sum(
          task,
          Vector(
            ctor("CommitteeVoter", Credential),
            ctor("DRepVoter", Credential),
            ctor("StakePoolVoter", Hex)
          )
        )
      case Vote => sum(task, Vector(ctor("VoteNo"), ctor("VoteYes"), ctor("Abstain")))
      case TxCert =>
        sum(
          task,
          Vector(
            ctor("TxCertRegStaking", Credential, Optional(Number)),
            ctor("TxCertUnRegStaking", Credential, Optional(Number)),
            ctor("TxCertDelegStaking", Credential, Delegatee),
            ctor("TxCertRegDeleg", Credential, Delegatee, Number),
            ctor("TxCertRegDRep", Credential, Number),
            ctor("TxCertUpdateDRep", Credential),
            ctor("TxCertUnRegDRep", Credential, Number),
            ctor("TxCertPoolRegister", Hex, Hex),
            ctor("TxCertPoolRetire", Hex, Number),
            ctor("TxCertAuthHotCommittee", Credential, Credential),
            ctor("TxCertResignColdCommittee", Credential)
          )
        )
      case GovernanceId =>
        labelled(task, "GovernanceActionId", Vector("gaidTxId" -> Id, "gaidGovActionIx" -> Number))
      case ProtocolVersion =>
        labelled(task, "ProtocolVersion", Vector("pvMajor" -> Number, "pvMinor" -> Number))
      case Proposal =>
        labelled(
          task,
          "ProposalProcedure",
          Vector(
            "ppDeposit" -> Number,
            "ppReturnAddr" -> Credential,
            "ppGovernanceAction" -> GovernanceAction
          )
        )
      case Constitution =>
        fields(task, Vector(Optional(Hex))).flatMap { values =>
          values match
            case Vector(script) =>
              node(Vector(child(task, Optional(Hex), script, task.precedence))) {
                case Vector(doc) =>
                  if task.show then doc else text("constitutionScript:").space(doc)
              }
            case _ => Left(Error.Shape(task.kind, data))
        }
      case Rational =>
        product(task, Vector(Number, Number), if task.show then 11 else 0) { case Vector(n, d) =>
          if task.show then protect(task, text("Rational").space(n).space(d))
          else text("Rational:").space(n).space(d)
        }
      case GovernanceAction =>
        sum(
          task,
          Vector(
            ctor("ParameterChange", Optional(GovernanceId), ChangedParameters, Optional(Hex)),
            ctor("HardForkInitiation", Optional(GovernanceId), ProtocolVersion),
            ctor("TreasuryWithdrawals", Association(Credential, Number), Optional(Hex)),
            ctor("NoConfidence", Optional(GovernanceId)),
            ctor(
              "UpdateCommittee",
              Optional(GovernanceId),
              Sequence(Credential),
              Association(Credential, Number),
              Rational
            ),
            ctor("NewConstitution", Optional(GovernanceId), Constitution),
            ctor("InfoAction")
          )
        )
      case ContextV1 | ContextV2 =>
        val info = if task.kind == ContextV1 then InfoV1 else InfoV2
        product(task, Vector(info, PurposeV1)) { case Vector(txInfo, purpose) =>
          vsep(
            Vector(text("Purpose:").space(purpose), vsep(Vector(text("TxInfo:"), txInfo)).nest(2))
          )
        }
      case ContextV3 =>
        product(task, Vector(InfoV3, Redeemer, ScriptInfo)) {
          case Vector(info, redeemer, scriptInfo) =>
            vsep(
              Vector(
                text("ScriptInfo:").space(scriptInfo),
                vsep(Vector(text("TxInfo:"), info)).nest(2),
                vsep(Vector(text("Redeemer:"), redeemer)).nest(2)
              )
            )
        }
      case InfoV1 | InfoV2 | InfoV3 => info(task)

  private def labelled(task: Task, name: String, labels: Vector[(String, Kind)]): Result[Plan] =
    fields(task, labels.map(_._2)).flatMap { values =>
      many(labels.map(_._2).zip(values).map((k, v) => child(task, k, v))) { docs =>
        if task.show then protect(task, record(name, labels.map(_._1), docs))
        else vsep(labels.map(_._1).zip(docs).map((label, doc) => text(label + ":").space(doc)))
      }
    }

  private def info(task: Task): Result[Plan] =
    val v1 = task.kind == InfoV1
    val v3 = task.kind == InfoV3
    val inputs = if v1 then InputV1 else if v3 then InputV3 else InputV2
    val outputs = if v1 then OutputV1 else OutputV2
    val labels =
      Vector("Inputs" -> Sequence(inputs)) ++
        (if v1 then Vector.empty else Vector("Reference inputs" -> Sequence(inputs))) ++
        Vector(
          "Outputs" -> Sequence(outputs),
          "Fee" -> (if v3 then Number else Value),
          "Value minted" -> (if v3 then MintValue else Value),
          (if v3 then "TxCerts" else "DCerts") -> Sequence(if v3 then TxCert else Certificate),
          "Wdrl" -> (if v1 then Sequence(Pair(Staking, Number))
                     else Association(if v3 then Credential else Staking, Number)),
          "Valid range" -> Interval,
          "Signatories" -> Sequence(Hex)
        ) ++
        (if v1 then Vector.empty
         else Vector("Redeemers" -> Association(if v3 then PurposeV3 else PurposeV1, Redeemer))) ++
        Vector(
          "Datums" -> (if v1 then Sequence(Pair(Hex, Datum)) else Association(Hex, Datum)),
          "TxId" -> (if v3 then Id else LegacyId)
        ) ++
        (if v3 then
           Vector(
             "Votes" -> Association(Voter, Association(GovernanceId, Vote)),
             "Proposal Procedures" -> Sequence(Proposal),
             "Current Treasury Amount" -> Optional(Number),
             "Treasury Donation" -> Optional(Number)
           )
         else Vector.empty)
    fields(task, labels.map(_._2)).flatMap { values =>
      val (beforeId, fromId) = labels.zip(values).splitAt(if v1 then 9 else 11)
      val ordered = fromId.take(1) ++ beforeId ++ fromId.drop(1)
      many(ordered.map { case ((_, kind), value) => Task(kind, value) }) { docs =>
        vsep(ordered.map(_._1._1).zip(docs).map((label, doc) => text(label + ":").space(doc)))
      }
    }

  private def interval(task: Task): Result[Plan] = task.data match
    case Constr(
          tag,
          Vector(
            Constr(lowerTag, Vector(lower, closedLower)),
            Constr(upperTag, Vector(upper, closedUpper))
          )
        ) if tag == 0 && lowerTag == 0 && upperTag == 0 =>
      def bound(value: PlutusData, closed: PlutusData, lower: Boolean): Result[Plan] =
        val inclusive = closed match
          case Constr(t, Vector()) if t == 0 || t == 1 => Right(t == 1)
          case _                                       => Left(Error.Shape(Interval, task.data))
        inclusive.flatMap { yes =>
          value match
            case Constr(t, Vector()) if t == 0 => leaf(text(if lower then "(-∞" else "-∞)"))
            case Constr(t, Vector()) if t == 2 => leaf(text(if lower then "(+∞" else "+∞)"))
            case Constr(t, Vector(I(n))) if t == 1 =>
              leaf(
                if lower then text(if yes then "[ " else "( ") ++ text(s"POSIXTime $n")
                else text(s"POSIXTime $n") ++ text(if yes then " ]" else " )")
              )
            case _ => Left(Error.Shape(Interval, task.data))
        }
      (bound(lower, closedLower, true), bound(upper, closedUpper, false)).mapN { (lo, hi) =>
        Plan(
          Vector.empty,
          _ =>
            (lo.assemble(Vector.empty), hi.assemble(Vector.empty)).mapN((a, b) =>
              a.space(text(",")).space(b)
            )
        )
      }
    case _ => Left(Error.Shape(Interval, task.data))

  private def rawData(task: Task): Result[Plan] =
    val showing = task.kind == DataShow || task.show
    def children(values: Vector[PlutusData]): Vector[Task] =
      values.map(value => Task(if showing then DataShow else RawData, value))
    task.data match
      case I(value) =>
        if showing then
          leaf(
            protect(
              task,
              text("I ") ++ (if value < 0 then parens(text(value.toString))
                             else text(value.toString))
            )
          )
        else leaf(text(value.toString))
      case B(value) =>
        leaf(
          if showing then protect(task, text("B ") ++ text(HaskellShow.bytes(value)))
          else text(Base64.getEncoder.encodeToString(value.toArray))
        )
      case Constr(tag, values) =>
        many(children(values)) { docs =>
          if showing then
            protect(
              task,
              text("Constr ") ++ (if tag < 0 then parens(text(tag.toString))
                                  else text(tag.toString)) ++ text(" ") ++ flatList(docs)
            )
          else
            text("<") ++ sep(
              docs.zipWithIndex.map((doc, index) =>
                if index + 1 < docs.size then doc ++ text(",") else doc
              )
            ) ++ text(">")
        }
      case PlutusData.List(values) =>
        many(children(values)) { docs =>
          if showing then protect(task, text("List ") ++ flatList(docs))
          else
            text("[") ++ sep(
              docs.zipWithIndex.map((doc, index) =>
                if index + 1 < docs.size then doc ++ text(",") else doc
              )
            ) ++ text("]")
        }
      case PlutusData.Map(entries) =>
        many(children(entries.flatMap((k, v) => Vector(k, v)))) { docs =>
          val pairs = docs
            .grouped(2)
            .map { pair =>
              if showing then flatTuple(pair) else join(text(": "), pair)
            }
            .toVector
          if showing then protect(task, text("Map ") ++ flatList(pairs))
          else
            text("{") ++ sep(
              pairs.zipWithIndex.map((doc, index) =>
                if index + 1 < pairs.size then doc ++ text(",") else doc
              )
            ) ++ text("}")
        }
