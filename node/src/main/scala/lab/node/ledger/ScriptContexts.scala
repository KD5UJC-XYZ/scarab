// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.syntax.all.*
import lab.cbor.Bytes
import lab.node.plutus.{PlutusData, PlutusLanguage}
import PlutusData.{B, I}

/** The ledger refused to build a script context (a phase-1 failure in the reference ledger). */
final case class TranslationError(reason: String)

/** Translation of a transaction to the Plutus script context of each language (`TxInfo`,
  * `ScriptContext`), against the ledger's own `Plutus/TxInfo.hs` modules for Alonzo, Babbage and
  * Conway. Everything here is a pure function of the transaction, the resolved inputs and the
  * era-history clock.
  *
  * Plutus V1 and V2 share most encodings; V2 adds reference inputs, inline datums, reference
  * scripts, redeemers and a map of datums. V3 changes the transaction id (a plain byte string),
  * delivers withdrawals in the ledger's order and carries the redeemer and a script info in the
  * context.
  */
object ScriptContexts:
  /** What the translation needs besides the transaction. `posix` converts a slot to POSIX
    * milliseconds (the start of the slot) through the era history and the system start.
    */
  final case class Environment(
      protocolMajor: Int,
      spent: Map[TxIn, TxOut],
      references: Map[TxIn, TxOut],
      posix: BigInt => Either[TranslationError, BigInt]
  )

  private type Result[A] = Either[TranslationError, A]

  private def fail[A](reason: String): Result[A] = Left(TranslationError(reason))

  private def bytesOrder(a: Bytes, b: Bytes): Int =
    val n = a.value.length.min(b.value.length)
    var i = 0
    while i < n do
      val c = (a.value(i) & 0xff) - (b.value(i) & 0xff)
      if c != 0 then return c
      i += 1
    a.value.length - b.value.length

  private given Ordering[Bytes] = (a, b) => bytesOrder(a, b)

  // -------------------------------------------------------------------------------------------
  // Shared encodings

  private def constr(tag: Int, fields: PlutusData*): PlutusData = PlutusData.constr(tag, fields*)

  private def credential(c: Credential): PlutusData = c match
    case Credential.Key(h)    => constr(0, B(h))
    case Credential.Script(h) => constr(1, B(h))

  private def stakingHash(c: Credential): PlutusData = constr(0, credential(c))

  private def address(a: Address): Option[PlutusData] = a.kind match
    case Address.Kind.Shelley(_, payment, stake, pointer) =>
      val staking = stake
        .map(stakingHash)
        .orElse(pointer.map { p =>
          constr(1, I(p.slot), I(p.txIx), I(p.certIx))
        })
      Some(constr(0, credential(payment), PlutusData.maybe(staking)))
    case Address.Kind.Byron(_) => None

  private def txId(id: Bytes, v3: Boolean): PlutusData = if v3 then B(id) else constr(0, B(id))

  private def outRef(in: TxIn, v3: Boolean): PlutusData = constr(0, txId(in.id, v3), I(in.index))

  /** `Value` as a map of maps, ada first, then policies and names in byte order. */
  private def value(v: Value, ada: Boolean): PlutusData =
    val adaEntry =
      if ada then
        Vector(
          (B(Bytes.empty): PlutusData) -> (PlutusData.Map(Vector(B(Bytes.empty) -> I(v.coin))))
        )
      else Vector.empty
    val assets = v.assets.toVector.sortBy(_._1).map { case (policy, names) =>
      (B(policy): PlutusData) -> (PlutusData.Map(
        names.toVector.sortBy(_._1).map { case (n, q) => (B(n): PlutusData) -> (I(q): PlutusData) }
      ): PlutusData)
    }
    PlutusData.Map(adaEntry ++ assets)

  private def mint(m: Map[Bytes, Map[Bytes, BigInt]], v3: Boolean): PlutusData =
    // V1 and V2 carry a zero ada entry in the mint field; V3 does not.
    value(Value(0, m), ada = !v3)

  private def datumMap(wits: Witnesses, v2: Boolean): Result[PlutusData] =
    wits.datums
      .traverse(d =>
        PlutusData
          .decode(d)
          .leftMap(e => TranslationError(s"datum: $e"))
          .map(Blake2b32.hash(d) -> _)
      )
      .map { pairs =>
        val sorted = pairs.sortBy(_._1)
        if v2 then PlutusData.Map(sorted.map { case (h, d) => (B(h): PlutusData) -> d })
        else PlutusData.List(sorted.map { case (h, d) => constr(0, B(h), d) })
      }

  // -------------------------------------------------------------------------------------------
  // Intervals

  private def interval(body: TxBody, env: Environment): Result[PlutusData] =
    def bound(ext: PlutusData, inclusive: Boolean) = constr(0, ext, PlutusData.bool(inclusive))
    val negInf = constr(0)
    val posInf = constr(2)
    def finite(t: BigInt) = constr(1, I(t))
    // Conway made the upper bound exclusive for an interval without a lower bound; Alonzo and
    // Babbage used the inclusive `to`.
    val openUpperInclusive = env.protocolMajor < 9
    def interval(lower: PlutusData, upper: PlutusData) = constr(0, lower, upper)
    (body.validityStart, body.ttl) match
      case (None, None) => Right(interval(bound(negInf, true), bound(posInf, true)))
      case (Some(from), None) =>
        env.posix(from).map(t => interval(bound(finite(t), true), bound(posInf, true)))
      case (None, Some(to)) =>
        env.posix(to).map(t => interval(bound(negInf, true), bound(finite(t), openUpperInclusive)))
      case (Some(from), Some(to)) =>
        (env.posix(from), env.posix(to)).mapN((a, b) =>
          interval(bound(finite(a), true), bound(finite(b), false))
        )

  // -------------------------------------------------------------------------------------------
  // Outputs and inputs

  private def outputDatumHash(d: Datum): Option[PlutusData] = d match
    case Datum.Hash(h) => Some(B(h))
    case _             => None

  private def outputV1(out: TxOut, env: Environment): Result[Option[PlutusData]] =
    if out.datum.isInstanceOf[Datum.Inline] then
      fail("inline datums are not supported in Plutus V1")
    else if out.scriptRef.isDefined then fail("reference scripts are not supported in Plutus V1")
    else
      address(out.address) match
        case None =>
          if env.protocolMajor >= 7 then fail("Byron address in a Plutus V1 script context")
          else Right(None)
        case Some(a) =>
          Right(
            Some(
              constr(
                0,
                a,
                value(out.value, ada = true),
                PlutusData.maybe(outputDatumHash(out.datum))
              )
            )
          )

  private def outputV2(out: TxOut, env: Environment): Result[Option[PlutusData]] =
    address(out.address) match
      case None =>
        if env.protocolMajor >= 7 then fail("Byron address in a Plutus script context")
        else Right(None)
      case Some(a) =>
        val datum = out.datum match
          case Datum.NoDatum => Right(constr(0))
          case Datum.Hash(h) => Right(constr(1, B(h)))
          case Datum.Inline(d) =>
            PlutusData
              .decode(d)
              .leftMap(e => TranslationError(s"inline datum: $e"))
              .map(constr(2, _))
        datum.map { d =>
          Some(
            constr(
              0,
              a,
              value(out.value, ada = true),
              d,
              PlutusData.maybe(out.scriptRef.map(s => B(s.hash)))
            )
          )
        }

  private def inputs(
      ins: Vector[TxIn],
      known: Map[TxIn, TxOut],
      language: PlutusLanguage,
      env: Environment
  ): Result[PlutusData] =
    val v3 = language == PlutusLanguage.V3
    ins.sorted
      .traverse { in =>
        known.get(in).toRight(TranslationError(s"unresolved input ${in.render}")).flatMap { out =>
          (if language == PlutusLanguage.V1 then outputV1(out, env) else outputV2(out, env))
            .map(_.map(o => constr(0, outRef(in, v3), o)))
        }
      }
      .map(xs => PlutusData.List(xs.flatten))

  private def outputs(
      outs: Vector[TxOut],
      language: PlutusLanguage,
      env: Environment
  ): Result[PlutusData] =
    outs
      .traverse(o => if language == PlutusLanguage.V1 then outputV1(o, env) else outputV2(o, env))
      .map(xs => PlutusData.List(xs.flatten))

  // -------------------------------------------------------------------------------------------
  // Certificates

  private def dcert(c: Certificate): Result[PlutusData] = c match
    case Certificate.Register(cred, _)   => Right(constr(0, stakingHash(cred)))
    case Certificate.Unregister(cred, _) => Right(constr(1, stakingHash(cred)))
    case Certificate.Delegate(cred, Some(p), None, None) =>
      Right(constr(2, stakingHash(cred), B(p)))
    case Certificate.RegisterPool(p)          => Right(constr(3, B(p.operator), B(p.vrfKeyHash)))
    case Certificate.RetirePool(p, e)         => Right(constr(4, B(p), I(e)))
    case Certificate.GenesisDelegate(_, _, _) => Right(constr(5))
    case Certificate.MoveRewards(_, _)        => Right(constr(6))
    case other => fail(s"certificate $other in a Plutus V1 or V2 script context")

  private def drep(d: DRep): PlutusData = d match
    case DRep.Registered(c)      => constr(0, credential(c))
    case DRep.AlwaysAbstain      => constr(1)
    case DRep.AlwaysNoConfidence => constr(2)

  private def txCert(c: Certificate, bootstrap: Boolean): Result[PlutusData] =
    def delegatee(pool: Option[Bytes], d: Option[DRep]): Result[PlutusData] = (pool, d) match
      case (Some(p), None)    => Right(constr(0, B(p)))
      case (None, Some(v))    => Right(constr(1, drep(v)))
      case (Some(p), Some(v)) => Right(constr(2, B(p), drep(v)))
      case _                  => fail("delegation without a delegatee")
    c match
      // In protocol version 9 the deposit and the refund were omitted from the context.
      case Certificate.Register(cred, dep) =>
        Right(constr(0, credential(cred), PlutusData.maybe(dep.filter(_ => !bootstrap).map(I(_)))))
      case Certificate.Unregister(cred, refund) =>
        Right(
          constr(1, credential(cred), PlutusData.maybe(refund.filter(_ => !bootstrap).map(I(_))))
        )
      case Certificate.Delegate(cred, pool, d, None) =>
        delegatee(pool, d).map(x => constr(2, credential(cred), x))
      case Certificate.Delegate(cred, pool, d, Some(dep)) =>
        delegatee(pool, d).map(x => constr(3, credential(cred), x, I(dep)))
      case Certificate.RegisterDRep(cred, dep, _) => Right(constr(4, credential(cred), I(dep)))
      case Certificate.UpdateDRep(cred, _)        => Right(constr(5, credential(cred)))
      case Certificate.UnregisterDRep(cred, ref)  => Right(constr(6, credential(cred), I(ref)))
      case Certificate.RegisterPool(p)  => Right(constr(7, B(p.operator), B(p.vrfKeyHash)))
      case Certificate.RetirePool(p, e) => Right(constr(8, B(p), I(e)))
      case Certificate.CommitteeAuthorize(cold, hot) =>
        Right(constr(9, credential(cold), credential(hot)))
      case Certificate.CommitteeResign(cold, _) => Right(constr(10, credential(cold)))
      case other => fail(s"certificate $other in a Plutus V3 script context")

  // -------------------------------------------------------------------------------------------
  // Purposes

  private def purposeV1(p: Purpose): Result[PlutusData] = p match
    case Purpose.Minting(_, policy)    => Right(constr(0, B(policy)))
    case Purpose.Spending(_, in, _)    => Right(constr(1, outRef(in, v3 = false)))
    case Purpose.Rewarding(_, account) => Right(constr(2, stakingHash(account.credential)))
    case Purpose.Certifying(_, cert)   => dcert(cert).map(constr(3, _))
    case other => fail(s"purpose $other in a Plutus V1 or V2 script context")

  private def purposeV3(p: Purpose, bootstrap: Boolean): Result[PlutusData] = p match
    case Purpose.Minting(_, policy)      => Right(constr(0, B(policy)))
    case Purpose.Spending(_, in, _)      => Right(constr(1, outRef(in, v3 = true)))
    case Purpose.Rewarding(_, account)   => Right(constr(2, credential(account.credential)))
    case Purpose.Certifying(ix, cert)    => txCert(cert, bootstrap).map(constr(3, I(ix), _))
    case Purpose.Voting(_, voter)        => Right(constr(4, voterData(voter)))
    case Purpose.Proposing(ix, proposal) => proposalData(proposal).map(constr(5, I(ix), _))

  /** `ScriptInfo`: the purpose, with the datum of a spent output. */
  private def scriptInfo(
      p: Purpose,
      datum: Option[PlutusData],
      bootstrap: Boolean
  ): Result[PlutusData] = p match
    case Purpose.Minting(_, policy) => Right(constr(0, B(policy)))
    case Purpose.Spending(_, in, _) =>
      Right(constr(1, outRef(in, v3 = true), PlutusData.maybe(datum)))
    case Purpose.Rewarding(_, account)   => Right(constr(2, credential(account.credential)))
    case Purpose.Certifying(ix, cert)    => txCert(cert, bootstrap).map(constr(3, I(ix), _))
    case Purpose.Voting(_, voter)        => Right(constr(4, voterData(voter)))
    case Purpose.Proposing(ix, proposal) => proposalData(proposal).map(constr(5, I(ix), _))

  // -------------------------------------------------------------------------------------------
  // Governance (Plutus V3)

  private def voterData(v: Voter): PlutusData = v match
    case Voter.CommitteeHot(c) => constr(0, credential(c))
    case Voter.DRepVoter(c)    => constr(1, credential(c))
    case Voter.Pool(h)         => constr(2, B(h))

  private def govActionId(id: GovActionId): PlutusData = constr(0, B(id.txId), I(id.index))

  private def previous(id: Option[GovActionId]): PlutusData = PlutusData.maybe(id.map(govActionId))

  private def policy(h: Option[Bytes]): PlutusData = PlutusData.maybe(h.map(B(_)))

  /** `ChangedParameters`: the update as a map from parameter index to the value in Plutus data, in
    * index order (the ledger's `ToPlutusData PParamsUpdate`).
    */
  def changedParameters(update: ParamUpdate): Result[PlutusData] =
    def rational(n: lab.cbor.Node): Result[PlutusData] =
      CborRead
        .rational(n, "rational")
        .bimap(TranslationError(_), r => PlutusData.List(Vector(I(r.numerator), I(r.denominator))))
    def uint(n: lab.cbor.Node): Result[PlutusData] =
      CborRead.int(n, "integer").bimap(TranslationError(_), I(_))
    def list(f: lab.cbor.Node => Result[PlutusData])(n: lab.cbor.Node): Result[PlutusData] =
      CborRead
        .array(n, "list")
        .leftMap(TranslationError(_))
        .flatMap(_.traverse(f))
        .map(PlutusData.List(_))
    def value(key: Int, n: lab.cbor.Node): Result[PlutusData] = key match
      case 9 | 10 | 11 | 33 => rational(n)
      case 19               => list(rational)(n)
      case 20 | 21          => list(uint)(n)
      case 25 | 26          => list(rational)(n)
      case 18 =>
        CborRead
          .map(n, "cost models")
          .leftMap(TranslationError(_))
          .flatMap(_.traverse { case (k, v) =>
            (uint(k), list(uint)(v)).tupled
          })
          .map(es =>
            PlutusData.Map(es.sortBy(_._1 match
              case I(v) => v
              case _    => BigInt(0)))
          )
      case _ => uint(n)
    update.fields.toVector
      .sortBy(_._1)
      .traverse { case (key, raw) =>
        for
          node <- lab.cbor.Cbor.decode(raw).leftMap(TranslationError(_))
          v <- value(key, node)
        yield (I(key): PlutusData) -> v
      }
      .map(PlutusData.Map(_))

  private def proposalData(p: Proposal): Result[PlutusData] =
    val action: Result[PlutusData] = p.action match
      case GovAction.ParameterChange(prev, update, script) =>
        changedParameters(update).map(c => constr(0, previous(prev), c, policy(script)))
      case GovAction.HardFork(prev, major, minor) =>
        Right(constr(1, previous(prev), constr(0, I(major), I(minor))))
      case GovAction.TreasuryWithdrawals(withdrawals, script) =>
        val entries = withdrawals
          .sortBy(w => (w._1.credential.isInstanceOf[Credential.Key], w._1.credential.hash.hex))
          .map { case (a, amount) =>
            (credential(a.credential): PlutusData) -> (I(amount): PlutusData)
          }
        Right(constr(2, PlutusData.Map(entries), policy(script)))
      case GovAction.NoConfidence(prev) => Right(constr(3, previous(prev)))
      case GovAction.UpdateCommittee(prev, removed, added, quorum) =>
        val ordered = (c: Vector[Credential]) =>
          c.sortBy(c => (c.isInstanceOf[Credential.Key], c.hash.hex))
        Right(
          constr(
            4,
            previous(prev),
            PlutusData.List(ordered(removed.toVector).map(credential)),
            PlutusData.Map(
              ordered(added.keys.toVector).map(c =>
                (credential(c): PlutusData) -> (I(added(c)): PlutusData)
              )
            ),
            constr(0, I(quorum.numerator), I(quorum.denominator))
          )
        )
      case GovAction.NewConstitution(prev, script, _) =>
        Right(constr(5, previous(prev), policy(script)))
      case GovAction.Info => Right(constr(6))
    action.map(a => constr(0, I(p.deposit), credential(p.returnAccount.credential), a))

  private def votes(body: TxBody): PlutusData =
    val voters = Purpose.sortedVoters(body)
    val byVoter = body.votes.toMap
    PlutusData.Map(voters.map { v =>
      val inner = byVoter(v).sortBy(e => (e._1.txId.hex, e._1.index)).map { case (id, vote) =>
        (govActionId(id): PlutusData) -> (constr(vote match
          case Vote.No      => 0
          case Vote.Yes     => 1
          case Vote.Abstain => 2): PlutusData)
      }
      (voterData(v): PlutusData) -> (PlutusData.Map(inner): PlutusData)
    })

  // -------------------------------------------------------------------------------------------
  // Transaction info

  private def decodeRedeemer(r: Redeemer): Result[PlutusData] =
    PlutusData.decode(r.data).leftMap(e => TranslationError(s"redeemer: $e"))

  private def purposes(tx: Tx, env: Environment): Vector[(Redeemer, Purpose)] =
    tx.witnesses.redeemers.sortBy(r => (r.tag, r.index)).flatMap { r =>
      Purpose.resolve(tx.body, env.spent, r.tag, r.index).map(r -> _)
    }

  private def redeemerMap(
      tx: Tx,
      env: Environment,
      purpose: Purpose => Result[PlutusData]
  ): Result[PlutusData] =
    purposes(tx, env)
      .traverse { case (r, p) => (purpose(p), decodeRedeemer(r)).mapN((a, b) => a -> b) }
      .map(PlutusData.Map(_))

  private val signatoriesOrder = summon[Ordering[Bytes]]

  /** `TxInfo` of one language; V1 and V2 for Alonzo and Babbage, V3 for Conway. */
  def txInfo(language: PlutusLanguage, tx: Tx, env: Environment): Result[PlutusData] =
    val body = tx.body
    language match
      case PlutusLanguage.V1 =>
        for
          _ <- Either.cond(
            body.referenceInputs.isEmpty,
            (),
            TranslationError("reference inputs are not supported in Plutus V1")
          )
          _ <- guardNoConway(body)
          ins <- inputs(body.inputs, env.spent, language, env)
          outs <- outputs(body.outputs, language, env)
          certs <- body.certificates.traverse(dcert)
          range <- interval(body, env)
          data <- datumMap(tx.witnesses, v2 = false)
        yield constr(
          0,
          ins,
          outs,
          value(Value.coin(body.fee), ada = true),
          mint(body.mint, v3 = false),
          PlutusData.List(certs),
          withdrawalsV1(body, asMap = false),
          range,
          PlutusData.List(body.requiredSigners.sorted.map(B(_))),
          data,
          constr(0, B(body.id))
        )
      case PlutusLanguage.V2 =>
        for
          _ <- guardNoConway(body)
          ins <- inputs(body.inputs, env.spent, language, env)
          refs <- inputs(body.referenceInputs, env.references, language, env)
          outs <- outputs(body.outputs, language, env)
          certs <- body.certificates.traverse(dcert)
          range <- interval(body, env)
          redeemers <- redeemerMap(tx, env, purposeV1)
          data <- datumMap(tx.witnesses, v2 = true)
        yield constr(
          0,
          ins,
          refs,
          outs,
          value(Value.coin(body.fee), ada = true),
          mint(body.mint, v3 = false),
          PlutusData.List(certs),
          withdrawalsV1(body, asMap = true),
          range,
          PlutusData.List(body.requiredSigners.sorted.map(B(_))),
          redeemers,
          data,
          constr(0, B(body.id))
        )
      case PlutusLanguage.V3 =>
        for
          ins <- inputs(body.inputs, env.spent, language, env)
          refs <- inputs(body.referenceInputs, env.references, language, env)
          outs <- outputs(body.outputs, language, env)
          _ <- Either.cond(
            env.protocolMajor < 11 || body.inputs.toSet
              .intersect(body.referenceInputs.toSet)
              .isEmpty,
            (),
            TranslationError("a reference input is also an input")
          )
          certs <- body.certificates.traverse(txCert(_, env.protocolMajor == 9))
          range <- interval(body, env)
          redeemers <- redeemerMap(tx, env, purposeV3(_, env.protocolMajor == 9))
          data <- datumMap(tx.witnesses, v2 = true)
          proposals <- body.proposals.traverse(proposalData)
        yield constr(
          0,
          ins,
          refs,
          outs,
          I(body.fee),
          mint(body.mint, v3 = true),
          PlutusData.List(certs),
          PlutusData.Map(Purpose.sortedWithdrawals(body).map { case (a, amount) =>
            (credential(a.credential): PlutusData) -> (I(amount): PlutusData)
          }),
          range,
          PlutusData.List(body.requiredSigners.sorted.map(B(_))),
          redeemers,
          data,
          B(body.id),
          votes(body),
          PlutusData.List(proposals),
          PlutusData.maybe(body.currentTreasury.map(I(_))),
          PlutusData.maybe(Option.when(body.donation > 0)(I(body.donation)))
        )

  private def guardNoConway(body: TxBody): Result[Unit] =
    if body.votes.nonEmpty || body.proposals.nonEmpty || body.donation != 0 || body.currentTreasury.isDefined
    then fail("governance fields in a Plutus V1 or V2 script context")
    else Right(())

  /** V1/V2 withdrawals: a list of pairs (V1) or a map (V2) keyed by staking credential in the order
    * of the Plutus credential (public keys before scripts). The V3 field and the redeemer index use
    * the ledger order instead.
    */
  private def withdrawalsV1(body: TxBody, asMap: Boolean): PlutusData =
    val entries = body.withdrawals
      .map { case (a, amount) => (a.credential, amount) }
      .sortBy { case (c, _) => (c.isInstanceOf[Credential.Script], c.hash.hex) }
      .map { case (c, amount) => (stakingHash(c), I(amount): PlutusData) }
    if asMap then PlutusData.Map(entries)
    else PlutusData.List(entries.map { case (k, v) => constr(0, k, v) })

  /** The arguments applied to the script of `language` for one redeemer. */
  def arguments(
      language: PlutusLanguage,
      info: PlutusData,
      tx: Tx,
      redeemer: Redeemer,
      purpose: Purpose,
      protocolMajor: Int = 10
  ): Result[Vector[PlutusData]] =
    decodeRedeemer(redeemer).flatMap { rdmr =>
      language match
        case PlutusLanguage.V3 =>
          for
            datum <- spendingDatum(tx, purpose)
            sinfo <- scriptInfo(purpose, datum, protocolMajor == 9)
          yield Vector(constr(0, info, rdmr, sinfo))
        case _ =>
          for
            ctxPurpose <- purposeV1(purpose)
            datum <- spendingDatum(tx, purpose)
            context = constr(0, info, ctxPurpose)
            needsDatum = purpose.isInstanceOf[Purpose.Spending]
            ds <-
              if needsDatum then
                datum.toRight(TranslationError("spent output has no datum")).map(Vector(_))
              else Right(Vector.empty)
          yield ds ++ Vector(rdmr, context)
    }

  /** The datum of a spent output: inline, or found by hash among the witnesses. */
  private def spendingDatum(tx: Tx, purpose: Purpose): Result[Option[PlutusData]] = purpose match
    case Purpose.Spending(_, _, out) =>
      out.datum match
        case Datum.NoDatum => Right(None)
        case Datum.Inline(d) =>
          PlutusData.decode(d).leftMap(e => TranslationError(s"inline datum: $e")).map(Some(_))
        case Datum.Hash(h) =>
          tx.witnesses.datums.find(d => Blake2b32.hash(d) == h) match
            case None => fail(s"datum ${h.hex} is not in the witnesses")
            case Some(d) =>
              PlutusData.decode(d).leftMap(e => TranslationError(s"datum: $e")).map(Some(_))
    case _ => Right(None)

  private[ledger] object Blake2b32:
    def hash(b: Bytes): Bytes = lab.Blake2b.hash256.hash(b)
