// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.plutus.PlutusData.{B, Constr, I, List as L, Map as M}
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

/** The Plutus V4 types (`PV4`, plutus-ledger-api 1.71.0.0 at plutus 261766ad) against the Plutus V4
  * arguments the Leios testnet's build collected: each argument the reference recorded is read into
  * the types, field by field, by a reader of the encodings the types' `ToData` writes, which
  * refuses whatever it does not expect (a test's reading of the reference's `FromData`,
  * `V4/Contexts.hs`), and written back with `ToData` to the reference's bytes. The fixtures are the
  * chain's own transactions (`leios-testnet-chain-contexts`, 34 Plutus V4 contexts), the
  * hand-written cases of every V4 field (`leios-testnet-contexts`, 14) and the cases the chain does
  * not reach (`leios-testnet-context-refusals`, 1): 49 in all, at both levels.
  *
  * This shows that the types hold every field of each recorded context in the reference's encoding.
  * That the ledger's translation builds each of the 49, byte for byte, from the transaction it was
  * recorded for is `DijkstraPlutusContextSuite` 's.
  */
class PlutusLedgerApiV4Suite extends FunSuite:
  private def parse(path: String): Json =
    parser.parse(Files.readString(Path.of(path))).fold(e => fail(e.toString), identity)

  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(s"$name: ${e.message}"), identity)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  /** The arguments of each Plutus V4 context the reference collected, in a fixture of `context`
    * answers.
    */
  private def v4Arguments(path: String): Vector[(String, Node)] =
    val results = parse(path).asArray.getOrElse(fail(s"$path is not an array"))
    results.flatMap { r =>
      val name = field[String](r, "name")
      val result = field[Json](r, "result")
      val levels = field[Json](result, "top") +: field[Vector[Json]](result, "subtxs")
      levels.zipWithIndex.flatMap { (level, n) =>
        level.hcursor.get[Vector[String]]("contexts").getOrElse(Vector.empty).zipWithIndex.flatMap {
          (pwc, k) =>
            Cbor.decode(hex(pwc)).fold(e => fail(e.toString), identity).value match
              case Value.Arr(Vector(_, script, arguments, _, _)) =>
                script.value match
                  case Value.Arr(Vector(lang, _)) if lang.value == Value.UInt(BigInt(3)) =>
                    Vector(s"$name, level $n, script $k" -> arguments)
                  case Value.Arr(_) => Vector.empty
                  case other        => fail(s"$name: not a script: $other")
              case other => fail(s"$name: not a PlutusWithContext: $other")
        }
      }
    }

  /** Each fixture with the number of Plutus V4 contexts the reference recorded in it. */
  private val fixtures = Vector(
    "fixtures/reference/leios-testnet-chain-contexts/results.json" -> 34,
    "fixtures/reference/leios-testnet-contexts/results.json" -> 14,
    "fixtures/reference/leios-testnet-context-refusals/results.json" -> 1
  )

  private val recorded: Vector[(String, Node)] =
    fixtures.flatMap { (path, count) =>
      val arguments = v4Arguments(path)
      assertEquals(arguments.size, count, path)
      arguments
    }

  // A reader of the encodings, each refusing what it does not expect.

  private def bytes(d: PlutusData): Bytes = d match
    case B(b)  => b
    case other => fail(s"not bytes: $other")

  private def int(d: PlutusData): BigInt = d match
    case I(i)  => i
    case other => fail(s"not an integer: $other")

  private def list[A](d: PlutusData)(f: PlutusData => A): Vector[A] = d match
    case L(xs) => xs.map(f)
    case other => fail(s"not a list: $other")

  /** A record of `size` fields, which the callers match by its size alone (`@unchecked`). */
  private def record(d: PlutusData, size: Int): Vector[PlutusData] = d match
    case L(xs) if xs.size == size => xs
    case other                    => fail(s"not a record of $size fields: $other")

  private def constr(d: PlutusData): (Int, Vector[PlutusData]) = d match
    case Constr(t, fs) => (t.toInt, fs)
    case other         => fail(s"not a constructor: $other")

  private def option[A](d: PlutusData)(f: PlutusData => A): Option[A] = constr(d) match
    case (0, Vector(x)) => Some(f(x))
    case (1, Vector())  => None
    case other          => fail(s"not a Maybe: $other")

  private def map[K, V](d: PlutusData)(k: PlutusData => K, v: PlutusData => V): AssocMap[K, V] =
    d match
      case M(entries) => AssocMap(entries.map((a, b) => (k(a), v(b))))
      case other      => fail(s"not a map: $other")

  private def credential(d: PlutusData): PV1.Credential = constr(d) match
    case (0, Vector(h)) => PV1.Credential.PubKeyCredential(PV1.PubKeyHash(bytes(h)))
    case (1, Vector(h)) => PV1.Credential.ScriptCredential(PV1.ScriptHash(bytes(h)))
    case other          => fail(s"not a credential: $other")

  private def scriptHash(d: PlutusData): PV1.ScriptHash = PV1.ScriptHash(bytes(d))
  private def pubKeyHash(d: PlutusData): PV1.PubKeyHash = PV1.PubKeyHash(bytes(d))
  private def lovelace(d: PlutusData): PV1.Lovelace = PV1.Lovelace(int(d))
  private def datum(d: PlutusData): PV1.Datum = PV1.Datum(d)
  private def txId(d: PlutusData): PV3.TxId = PV3.TxId(bytes(d))
  private def accountId(d: PlutusData): PV4.AccountId = PV4.AccountId(credential(d))

  private def value(d: PlutusData): PV1.Value =
    PV1.Value(
      map(d)(c => PV1.CurrencySymbol(bytes(c)), n => map(n)(t => PV1.TokenName(bytes(t)), int))
    )

  private def mintValue(d: PlutusData): PV3.MintValue = PV3.MintValue(value(d).getValue)

  private def txOutRef(d: PlutusData): PV4.TxOutRef = (record(d, 2): @unchecked) match
    case Vector(id, ix) => PV4.TxOutRef(txId(id), int(ix))

  private def outputDatum(d: PlutusData): PV2.OutputDatum = constr(d) match
    case (0, Vector())  => PV2.OutputDatum.NoOutputDatum
    case (1, Vector(h)) => PV2.OutputDatum.OutputDatumHash(PV1.DatumHash(bytes(h)))
    case (2, Vector(x)) => PV2.OutputDatum.OutputDatum(datum(x))
    case other          => fail(s"not an output datum: $other")

  private def txOut(d: PlutusData): PV4.TxOut = (record(d, 4): @unchecked) match
    case Vector(address, v, od, rs) =>
      val a = (record(address, 2): @unchecked) match
        case Vector(payment, staking) =>
          PV4.Address(credential(payment), option(staking)(accountId))
      PV4.TxOut(a, value(v), outputDatum(od), option(rs)(scriptHash))

  private def txInInfo(d: PlutusData): PV4.TxInInfo = (record(d, 2): @unchecked) match
    case Vector(ref, out) => PV4.TxInInfo(txOutRef(ref), txOut(out))

  private def coldCredential(d: PlutusData) = PV3.ColdCommitteeCredential(credential(d))
  private def hotCredential(d: PlutusData) = PV3.HotCommitteeCredential(credential(d))
  private def drepCredential(d: PlutusData) = PV3.DRepCredential(credential(d))

  private def drep(d: PlutusData): PV3.DRep = constr(d) match
    case (0, Vector(c)) => PV3.DRep.DRep(drepCredential(c))
    case (1, Vector())  => PV3.DRep.DRepAlwaysAbstain
    case (2, Vector())  => PV3.DRep.DRepAlwaysNoConfidence
    case other          => fail(s"not a DRep: $other")

  private def delegatee(d: PlutusData): PV3.Delegatee = constr(d) match
    case (0, Vector(p))    => PV3.Delegatee.DelegStake(pubKeyHash(p))
    case (1, Vector(r))    => PV3.Delegatee.DelegVote(drep(r))
    case (2, Vector(p, r)) => PV3.Delegatee.DelegStakeVote(pubKeyHash(p), drep(r))
    case other             => fail(s"not a delegatee: $other")

  private def txCert(d: PlutusData): PV4.TxCert = constr(d) match
    case (0, Vector(a, l)) => PV4.TxCert.TxCertRegAccount(accountId(a), lovelace(l))
    case (1, Vector(a, l)) => PV4.TxCert.TxCertUnRegAccount(accountId(a), lovelace(l))
    case (2, Vector(a, e)) => PV4.TxCert.TxCertDelegAccount(accountId(a), delegatee(e))
    case (3, Vector(a, e, l)) =>
      PV4.TxCert.TxCertRegAccountDeleg(accountId(a), delegatee(e), lovelace(l))
    case (4, Vector(c, l)) => PV4.TxCert.TxCertRegDRep(drepCredential(c), lovelace(l))
    case (5, Vector(c))    => PV4.TxCert.TxCertUpdateDRep(drepCredential(c))
    case (6, Vector(c, l)) => PV4.TxCert.TxCertUnRegDRep(drepCredential(c), lovelace(l))
    case (7, Vector(p, v)) => PV4.TxCert.TxCertPoolRegister(pubKeyHash(p), pubKeyHash(v))
    case (8, Vector(p, e)) => PV4.TxCert.TxCertPoolRetire(pubKeyHash(p), int(e))
    case (9, Vector(c, h)) => PV4.TxCert.TxCertAuthHotCommittee(coldCredential(c), hotCredential(h))
    case (10, Vector(c))   => PV4.TxCert.TxCertResignColdCommittee(coldCredential(c))
    case other             => fail(s"not a certificate: $other")

  private def voter(d: PlutusData): PV3.Voter = constr(d) match
    case (0, Vector(c)) => PV3.Voter.CommitteeVoter(hotCredential(c))
    case (1, Vector(c)) => PV3.Voter.DRepVoter(drepCredential(c))
    case (2, Vector(p)) => PV3.Voter.StakePoolVoter(pubKeyHash(p))
    case other          => fail(s"not a voter: $other")

  private def vote(d: PlutusData): PV3.Vote = constr(d) match
    case (0, Vector()) => PV3.Vote.VoteNo
    case (1, Vector()) => PV3.Vote.VoteYes
    case (2, Vector()) => PV3.Vote.Abstain
    case other         => fail(s"not a vote: $other")

  private def actionId(d: PlutusData): PV4.GovernanceActionId = (record(d, 2): @unchecked) match
    case Vector(id, ix) => PV4.GovernanceActionId(txId(id), int(ix))

  private def governanceAction(d: PlutusData): PV4.GovernanceAction = constr(d) match
    case (0, Vector(p, c, s)) =>
      PV4.GovernanceAction.ParameterChange(
        option(p)(actionId),
        PV3.ChangedParameters(c),
        option(s)(scriptHash)
      )
    case (1, Vector(p, v)) =>
      val version = (record(v, 2): @unchecked) match
        case Vector(major, minor) => PV4.ProtocolVersion(int(major), int(minor))
      PV4.GovernanceAction.HardForkInitiation(option(p)(actionId), version)
    case (2, Vector(w, s)) =>
      PV4.GovernanceAction.TreasuryWithdrawals(map(w)(credential, lovelace), option(s)(scriptHash))
    case (3, Vector(p)) => PV4.GovernanceAction.NoConfidence(option(p)(actionId))
    case (4, Vector(p, r, a, q)) =>
      val quorum = (record(q, 2): @unchecked) match
        case Vector(n, m) =>
          PV4.Rational(
            PV3.Rational.ratio(int(n), int(m)).getOrElse(fail(s"a zero denominator: $q"))
          )
      PV4.GovernanceAction.UpdateCommittee(
        option(p)(actionId),
        list(r)(coldCredential),
        map(a)(coldCredential, int),
        quorum
      )
    case (5, Vector(p, c)) =>
      val constitution = (record(c, 1): @unchecked) match
        case Vector(s) => PV4.Constitution(option(s)(scriptHash))
      PV4.GovernanceAction.NewConstitution(option(p)(actionId), constitution)
    case (6, Vector()) => PV4.GovernanceAction.InfoAction
    case other         => fail(s"not a governance action: $other")

  private def proposal(d: PlutusData): PV4.ProposalProcedure = (record(d, 3): @unchecked) match
    case Vector(deposit, account, action) =>
      PV4.ProposalProcedure(lovelace(deposit), credential(account), governanceAction(action))

  private def scriptPurpose(d: PlutusData): PV4.ScriptPurpose = constr(d) match
    case (0, Vector(h, c)) => PV4.ScriptPurpose.Minting(scriptHash(h), PV1.CurrencySymbol(bytes(c)))
    case (1, Vector(h, r)) => PV4.ScriptPurpose.Spending(scriptHash(h), txOutRef(r))
    case (2, Vector(h, c)) => PV4.ScriptPurpose.Withdrawing(scriptHash(h), credential(c))
    case (3, Vector(h, i, c)) => PV4.ScriptPurpose.Certifying(scriptHash(h), int(i), txCert(c))
    case (4, Vector(h, v))    => PV4.ScriptPurpose.Voting(scriptHash(h), voter(v))
    case (5, Vector(h, i, p)) => PV4.ScriptPurpose.Proposing(scriptHash(h), int(i), proposal(p))
    case (6, Vector(h, i))    => PV4.ScriptPurpose.Guarding(scriptHash(h), int(i))
    case other                => fail(s"not a script purpose: $other")

  private def range(d: PlutusData): PV4.POSIXTimeRange = (record(d, 2): @unchecked) match
    case Vector(from, until) =>
      PV4.POSIXTimeRange(
        option(from)(t => PV1.POSIXTime(int(t))),
        option(until)(t => PV1.POSIXTime(int(t)))
      )

  private def intervals(d: PlutusData): PV4.AccountBalanceIntervals =
    PV4.AccountBalanceIntervals(
      map(d)(
        accountId,
        i =>
          constr(i) match
            case (0, Vector(l)) => PV4.AccountBalanceInterval.AccountBalanceLowerBound(lovelace(l))
            case (1, Vector(u)) => PV4.AccountBalanceInterval.AccountBalanceUpperBound(lovelace(u))
            case (2, Vector(l, u)) =>
              PV4.AccountBalanceInterval.AccountBalanceBothBounds(lovelace(l), lovelace(u))
            case (3, Vector(e)) => PV4.AccountBalanceInterval.AccountBalanceExact(lovelace(e))
            case other          => fail(s"not a balance interval: $other")
      )
    )

  private def votes(d: PlutusData) = map(d)(voter, m => map(m)(actionId, vote))

  private def txInfo(d: PlutusData): PV4.TxInfo = (record(d, 19): @unchecked) match
    case Vector(
          id,
          ix,
          ins,
          refs,
          outs,
          mint,
          certs,
          wdrl,
          deps,
          abis,
          vr,
          gs,
          rtlg,
          rdmrs,
          dats,
          vts,
          props,
          cur,
          don
        ) =>
      PV4.TxInfo(
        txId(id),
        option(ix)(int),
        list(ins)(txInInfo),
        list(refs)(txInInfo),
        list(outs)(txOut),
        mintValue(mint),
        list(certs)(txCert),
        map(wdrl)(credential, lovelace),
        map(deps)(credential, lovelace),
        intervals(abis),
        range(vr),
        list(gs)(credential),
        map(rtlg)(credential, option(_)(datum)),
        map(rdmrs)(scriptPurpose, r => PV1.Redeemer(r)),
        map(dats)(h => PV1.DatumHash(bytes(h)), datum),
        votes(vts),
        list(props)(proposal),
        option(cur)(lovelace),
        lovelace(don)
      )
    case other => fail(s"not a TxInfo: $other")

  private def topTxInfo(d: PlutusData): PV4.TopTxInfo = (record(d, 4): @unchecked) match
    case Vector(subs, dats, starting, simplified) =>
      val s = (record(simplified, 18): @unchecked) match
        case Vector(
              ids,
              ins,
              refs,
              outs,
              mints,
              burns,
              certs,
              wdrl,
              deps,
              vr,
              gs,
              rtlg,
              hashes,
              ds,
              vts,
              props,
              cur,
              don
            ) =>
          PV4.TopTxInfoSimplified(
            list(ids)(txId),
            list(ins)(txInInfo),
            list(refs)(txInInfo),
            list(outs)(txOut),
            mintValue(mints),
            mintValue(burns),
            list(certs)(txCert),
            map(wdrl)(credential, lovelace),
            map(deps)(credential, lovelace),
            range(vr),
            list(gs)(credential),
            list(rtlg)(credential),
            list(hashes)(scriptHash),
            map(ds)(h => PV1.DatumHash(bytes(h)), datum),
            votes(vts),
            list(props)(proposal),
            option(cur)(lovelace),
            lovelace(don)
          )
        case other => fail(s"not a TopTxInfoSimplified: $other")
      PV4.TopTxInfo(list(subs)(txInfo), map(dats)(txId, datum), intervals(starting), s)

  private def scriptInfo(d: PlutusData): PV4.ScriptInfo = constr(d) match
    case (0, Vector(c))    => PV4.ScriptInfo.MintingScript(PV1.CurrencySymbol(bytes(c)))
    case (1, Vector(r, x)) => PV4.ScriptInfo.SpendingScript(txOutRef(r), option(x)(datum))
    case (2, Vector(a))    => PV4.ScriptInfo.WithdrawingScript(accountId(a))
    case (3, Vector(i, c)) => PV4.ScriptInfo.CertifyingScript(int(i), txCert(c))
    case (4, Vector(v))    => PV4.ScriptInfo.VotingScript(voter(v))
    case (5, Vector(i, p)) => PV4.ScriptInfo.ProposingScript(int(i), proposal(p))
    case (6, Vector(i, t)) => PV4.ScriptInfo.GuardingScript(int(i), option(t)(topTxInfo))
    case other             => fail(s"not a script info: $other")

  private def scriptContext(d: PlutusData): PV4.ScriptContext = (record(d, 4): @unchecked) match
    case Vector(info, redeemer, purpose, hash) =>
      PV4.ScriptContext(txInfo(info), PV1.Redeemer(redeemer), scriptInfo(purpose), scriptHash(hash))

  test(
    "every Plutus V4 argument the reference recorded reads into the types and writes back to its bytes"
  ) {
    val read = recorded.map { (at, arguments) =>
      val d = PlutusData.fromNode(arguments).fold(e => fail(s"$at: ${e.toString}"), identity)
      val context = scriptContext(d)
      assertEquals(
        DataSerialisation.serialise(ToData[PV4.ScriptContext].toBuiltinData(context)),
        arguments.original,
        at
      )
      context.scriptContextScriptInfo
    }
    // every script purpose is among them, and a guard both with the whole transaction and without
    assertEquals(read.map(_.ordinal).distinct.sorted, Vector(0, 1, 2, 3, 4, 5, 6))
    assert(read.exists {
      case PV4.ScriptInfo.GuardingScript(_, Some(_)) => true
      case _                                         => false
    })
    assert(read.exists {
      case PV4.ScriptInfo.GuardingScript(_, None) => true
      case _                                      => false
    })
  }

  test("a value of each type writes the reference's encoding of it") {
    val h = PV1.ScriptHash(Bytes.fromArray(Array.fill[Byte](28)(1)))
    val cred = PV1.Credential.ScriptCredential(h)
    // a record is the list of its fields; a sum the constructor of its index
    assertEquals(
      ToData[PV4.Address].toBuiltinData(PV4.Address(cred, Some(PV4.AccountId(cred)))),
      L(
        Vector(
          Constr(1, Vector(B(h.getScriptHash))),
          Constr(0, Vector(Constr(1, Vector(B(h.getScriptHash)))))
        )
      )
    )
    assertEquals(
      ToData[PV4.POSIXTimeRange].toBuiltinData(PV4.POSIXTimeRange(None, Some(PV1.POSIXTime(5)))),
      L(Vector(Constr(1, Vector()), Constr(0, Vector(I(5)))))
    )
    assertEquals(
      ToData[PV4.ScriptPurpose].toBuiltinData(PV4.ScriptPurpose.Guarding(h, 2)),
      Constr(6, Vector(B(h.getScriptHash), I(2)))
    )
    assertEquals(
      ToData[PV4.AccountBalanceInterval].toBuiltinData(
        PV4.AccountBalanceInterval.AccountBalanceBothBounds(PV1.Lovelace(1), PV1.Lovelace(9))
      ),
      Constr(2, Vector(I(1), I(9)))
    )
    assertEquals(ToData[PV4.Rational].toBuiltinData(rational(1, 3)), L(Vector(I(1), I(3))))
  }

  private def rational(n: BigInt, d: BigInt): PV4.Rational =
    PV4.Rational(PV3.Rational.ratio(n, d).getOrElse(fail(s"$n/$d")))

  test("a ratio keeps plutus-tx's invariants: reduced, its denominator positive, none at zero") {
    def parts(r: Option[PV3.Rational]) = r.map(x => (x.numerator, x.denominator))
    assertEquals(parts(PV3.Rational.ratio(2, 4)), Some((BigInt(1), BigInt(2))))
    assertEquals(parts(PV3.Rational.ratio(1, -2)), Some((BigInt(-1), BigInt(2))))
    assertEquals(parts(PV3.Rational.ratio(-6, -4)), Some((BigInt(3), BigInt(2))))
    assertEquals(parts(PV3.Rational.ratio(0, -5)), Some((BigInt(0), BigInt(1))))
    assertEquals(PV3.Rational.ratio(1, 0), None)
    assertEquals(
      parts(Some(PV3.Rational.fromHaskellRatio(xyz.kd5ujc.scarab.codec.Rational(6, -4)))),
      Some((BigInt(-3), BigInt(2)))
    )
    assertEquals(ToData[PV4.Rational].toBuiltinData(rational(-6, 4)), L(Vector(I(-3), I(2))))
    // only the two factories make one: no constructor, no case class's `apply`, no product mirror
    assert(compileErrors("new PV3.Rational(BigInt(1), BigInt(0))").nonEmpty)
    assert(compileErrors("PV3.Rational(BigInt(1), BigInt(0))").nonEmpty)
    assert(
      compileErrors("summon[scala.deriving.Mirror.ProductOf[PV3.Rational]]").nonEmpty
    )
  }

  // Values whose fields the recorded arguments cannot tell apart, each encoded by hand in the order
  // of the reference's fields (`V4/Contexts.hs`, plutus 261766ad).

  private def h(fill: Int, size: Int = 28): Bytes =
    Bytes.fromArray(Array.fill[Byte](size)(fill.toByte))
  private def keyCred(fill: Int): PV1.Credential =
    PV1.Credential.PubKeyCredential(PV1.PubKeyHash(h(fill)))
  private def keyCredData(fill: Int): PlutusData = Constr(0, Vector(B(h(fill))))

  test("a minting purpose is its script's hash, then its currency symbol") {
    // `Minting V2.ScriptHash V2.CurrencySymbol` (`V4/Contexts.hs:324`, plutus 261766ad)
    assertEquals(
      ToData[PV4.ScriptPurpose].toBuiltinData(
        PV4.ScriptPurpose.Minting(PV1.ScriptHash(h(1)), PV1.CurrencySymbol(h(2)))
      ),
      Constr(0, Vector(B(h(1)), B(h(2))))
    )
  }

  test("a committee is its members, then its quorum, a list") {
    // `newtype Committee = CommitteeValue (Map ColdCommitteeCredential Integer, Rational)`, `ListEncoded`
    // (`V4/Contexts.hs:171-179`, plutus 261766ad)
    assertEquals(
      ToData[PV4.Committee].toBuiltinData(
        PV4.Committee(
          (AssocMap(Vector(PV3.ColdCommitteeCredential(keyCred(3)) -> BigInt(7))), rational(2, 3))
        )
      ),
      L(Vector(M(Vector(keyCredData(3) -> I(7))), L(Vector(I(2), I(3)))))
    )
  }

  test("the voters and the vote the recorded arguments lack are V3's, by their indices") {
    // `CommitteeVoter` 0, `StakePoolVoter` 2, `VoteNo` 0 (`V3/Contexts.hs:667-683`, plutus 261766ad)
    assertEquals(
      ToData[PV3.Voter].toBuiltinData(
        PV3.Voter.CommitteeVoter(PV3.HotCommitteeCredential(keyCred(4)))
      ),
      Constr(0, Vector(keyCredData(4)))
    )
    assertEquals(
      ToData[PV3.Voter].toBuiltinData(PV3.Voter.StakePoolVoter(PV1.PubKeyHash(h(5)))),
      Constr(2, Vector(B(h(5))))
    )
    assertEquals(ToData[PV3.Vote].toBuiltinData(PV3.Vote.VoteNo), Constr(0, Vector()))
  }

  test("a simplified view of the whole transaction has the reference's 18 fields in their order") {
    def outRef(k: Int) = PV4.TxOutRef(PV3.TxId(h(k, 32)), k)
    def outRefData(k: Int) = L(Vector(B(h(k, 32)), I(k)))
    def out(k: Int) = PV4.TxOut(
      PV4.Address(keyCred(k), None),
      PV1.Value(AssocMap(Vector())),
      PV2.OutputDatum.NoOutputDatum,
      None
    )
    def outData(k: Int) =
      L(
        Vector(
          L(Vector(keyCredData(k), Constr(1, Vector()))),
          M(Vector()),
          Constr(0, Vector()),
          Constr(1, Vector())
        )
      )
    def mint(k: Int, q: BigInt) =
      PV3.MintValue(
        AssocMap(Vector(PV1.CurrencySymbol(h(k)) -> AssocMap(Vector(PV1.TokenName(h(k, 1)) -> q))))
      )
    def mintData(k: Int, q: BigInt) = M(Vector(B(h(k)) -> M(Vector(B(h(k, 1)) -> I(q)))))
    val simplified = PV4.TopTxInfoSimplified(
      ttisIds = Vector(PV3.TxId(h(1, 32))),
      ttisInputs = Vector(PV4.TxInInfo(outRef(2), out(2))),
      ttisReferenceInputs = Vector(PV4.TxInInfo(outRef(3), out(3))),
      ttisOutputs = Vector(out(4)),
      ttisMints = mint(5, 1),
      ttisBurns = mint(6, -1),
      ttisTxCerts = Vector(PV4.TxCert.TxCertUpdateDRep(PV3.DRepCredential(keyCred(7)))),
      ttisWithdrawals = AssocMap(Vector(keyCred(8) -> PV1.Lovelace(8))),
      ttisDirectDeposits = AssocMap(Vector(keyCred(9) -> PV1.Lovelace(9))),
      ttisValidRange = PV4.POSIXTimeRange(Some(PV1.POSIXTime(10)), None),
      ttisGuards = Vector(keyCred(11)),
      ttisRequiredTopLevelGuards = Vector(keyCred(12)),
      ttisRedeemerHashes = Vector(PV1.ScriptHash(h(13))),
      ttisData = AssocMap(Vector(PV1.DatumHash(h(14, 32)) -> PV1.Datum(I(14)))),
      ttisVotes = AssocMap(
        Vector(
          PV3.Voter.StakePoolVoter(PV1.PubKeyHash(h(15))) ->
            AssocMap(Vector(PV4.GovernanceActionId(PV3.TxId(h(15, 32)), 15) -> PV3.Vote.VoteNo))
        )
      ),
      ttisProposalProcedures = Vector(
        PV4.ProposalProcedure(PV1.Lovelace(16), keyCred(16), PV4.GovernanceAction.InfoAction)
      ),
      ttisCurrentTreasuryAmount = Some(PV1.Lovelace(17)),
      ttisTreasuryDonations = PV1.Lovelace(18)
    )
    // `data TopTxInfoSimplified` (`V4/Contexts.hs:397-447,658`, plutus 261766ad): `ttisIds` to `ttisTreasuryDonations`
    assertEquals(
      ToData[PV4.TopTxInfoSimplified].toBuiltinData(simplified),
      L(
        Vector(
          L(Vector(B(h(1, 32)))),
          L(Vector(L(Vector(outRefData(2), outData(2))))),
          L(Vector(L(Vector(outRefData(3), outData(3))))),
          L(Vector(outData(4))),
          mintData(5, 1),
          mintData(6, -1),
          L(Vector(Constr(5, Vector(keyCredData(7))))),
          M(Vector(keyCredData(8) -> I(8))),
          M(Vector(keyCredData(9) -> I(9))),
          L(Vector(Constr(0, Vector(I(10))), Constr(1, Vector()))),
          L(Vector(keyCredData(11))),
          L(Vector(keyCredData(12))),
          L(Vector(B(h(13)))),
          M(Vector(B(h(14, 32)) -> I(14))),
          M(
            Vector(
              Constr(2, Vector(B(h(15)))) ->
                M(Vector(L(Vector(B(h(15, 32)), I(15))) -> Constr(0, Vector())))
            )
          ),
          L(Vector(L(Vector(I(16), keyCredData(16), Constr(6, Vector()))))),
          Constr(0, Vector(I(17))),
          I(18)
        )
      )
    )
  }

  test("a Plutus V4 script's arguments are its context alone, the reference's bytes") {
    // `evaluatePlutusRunnable` of `PlutusLanguage 'PlutusV4` applies the script to `PV3.toData` of
    // the context alone (`libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:582-601`,
    // cardano-ledger 9df9f98c)
    recorded.foreach { (at, arguments) =>
      val d = PlutusData.fromNode(arguments).fold(e => fail(s"$at: ${e.toString}"), identity)
      val args = PlutusLanguage.v4.plutusArgsToData(PlutusV4Args(scriptContext(d)))
      assertEquals(args.map(DataSerialisation.serialise), Vector(arguments.original), at)
    }
  }
