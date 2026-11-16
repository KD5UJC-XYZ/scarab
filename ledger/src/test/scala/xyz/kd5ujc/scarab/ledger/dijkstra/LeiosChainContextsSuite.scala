// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{CborRead, Credential}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

/** The Plutus scripts of transactions recorded on the public Leios testnet's chain, collected with
  * their contexts by the testnet's build (cardano-ledger 9df9f98c, plutus-ledger-api 1.71.0.0).
  * `fixtures/reference/leios-testnet-chain-contexts` holds a stratified sample of the chain's
  * transactions with redeemers, with the outputs they spend and the chain's environment
  * (`leios_chain_scan.py`), and the contexts the reference collected for them before evaluation
  * (`mkDijkstraStAnnTopTx`, the chain context oracle `scripts/oracle/leios-testnet/chain`), each
  * the `ToCBOR PlutusWithContext` encoding: the protocol version, the script with its language, the
  * arguments, the redeemer's execution units and the cost model.
  *
  * [[DijkstraScriptContexts.transaction]] must collect the same scripts, in the same order, each
  * for the same purpose (the same input, policy, account, voter, or certificate, proposal or guard
  * index), with the same redeemer, and each PlutusV4 script's argument serialised to the
  * reference's bytes. Every collection of the reference, at the top level and in each
  * sub-transaction, must have succeeded with an explicit list of contexts. The node builds no
  * context for a PlutusV1 to PlutusV3 script (it collects one as `Untranslated`), so only for those
  * scripts the argument is not compared, and the suite reports how many; a PlutusV4 script whose
  * context the node does not build fails.
  */
class LeiosChainContextsSuite extends FunSuite:
  private val root = Path.of("fixtures/reference/leios-testnet-chain-contexts")

  private def parse(name: String): Json =
    parser.parse(Files.readString(root.resolve(name))).fold(e => fail(e.toString), identity)

  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(s"$name: ${e.message}"), identity)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private def node(b: Bytes): Node =
    CborRead.decode(b, V12.nestedLimits).fold(e => fail(e.toString), identity)

  private val chain = parse("cases.json")
  private val cases = field[Vector[Json]](chain, "cases")
  private val results =
    parse("results.json").asArray.getOrElse(fail("results.json is not an array"))

  /** Slot to POSIX milliseconds as the oracle's epoch information gives it: the chain's system
    * start and slots of `slotLength` seconds (`fixedEpochInfo`).
    */
  private val posix: BigInt => DijkstraScriptContexts.Result[BigInt] =
    val start = BigInt(Instant.parse(field[String](chain, "systemStart")).getEpochSecond)
    val length = BigInt(field[Long](chain, "slotLength"))
    slot => Right((start + slot * length) * 1000)

  private def read(c: Json): (DijkstraTx, Map[DijkstraTxIn, DijkstraTxOut]) =
    val tx =
      DijkstraTx.inBlock(node(hex(field[String](c, "bytes")))).fold(e => fail(e.toString), identity)
    val utxo = field[Vector[(String, String)]](c, "utxo").map { (i, o) =>
      DijkstraTxIn.decode(node(hex(i))).fold(e => fail(e.toString), identity) ->
        DijkstraTxOut.decode(node(hex(o))).fold(e => fail(e.toString), identity)
    }
    (tx, utxo.toMap)

  /** A context of the reference, read from its `PlutusWithContext` encoding. */
  private final case class Reference(
      language: DijkstraLanguage,
      scriptHash: Bytes,
      arguments: Node,
      memory: BigInt,
      steps: BigInt
  )

  private def pair(n: Node): (Value, Value) = n.value match
    case Value.Arr(Vector(a, b)) => (a.value, b.value)
    case other                   => fail(s"not a pair: $other")

  private def reference(pwc: Bytes): Reference =
    Cbor.decode(pwc).fold(e => fail(e.toString), identity).value match
      case Value.Arr(Vector(_, script, arguments, units, _)) =>
        (pair(script), pair(units)) match
          case (
                (Value.UInt(lang), Value.ByteString(code)),
                (Value.UInt(memory), Value.UInt(steps))
              ) =>
            val language = DijkstraLanguage.values
              .find(_.hashTag == lang.toInt + 1)
              .getOrElse(fail(s"language $lang"))
            Reference(
              language,
              DijkstraScript.Plutus(language, code).hash,
              arguments,
              memory,
              steps
            )
          case other => fail(s"not a script and execution units: $other")
      case other => fail(s"not a PlutusWithContext: $other")

  private def data(n: Node): PlutusData =
    PlutusData.fromNode(n).fold(e => fail(e.toString), identity)

  /** Which purpose a script runs for, as both sides can name it: the input it spends, the policy it
    * mints, the credential of the account it withdraws from, the voter, or the index of the
    * certificate, proposal or guard in the (sub-)transaction's body.
    */
  private enum Identity:
    case Spends(id: Bytes, index: BigInt)
    case Mints(policy: Bytes)
    case Withdraws(credential: Credential)
    case Certifies(index: BigInt)
    case Votes(voter: DijkstraVoter)
    case Proposes(index: BigInt)
    case Guards(index: BigInt)

  private def identityOf(p: DijkstraPurpose): Identity = p match
    case DijkstraPurpose.Spending(_, in)      => Identity.Spends(in.id, in.index)
    case DijkstraPurpose.Minting(_, policy)   => Identity.Mints(policy)
    case DijkstraPurpose.Withdrawing(_, acct) => Identity.Withdraws(acct.credential)
    case DijkstraPurpose.Certifying(ix, _)    => Identity.Certifies(ix)
    case DijkstraPurpose.Voting(_, voter)     => Identity.Votes(voter)
    case DijkstraPurpose.Proposing(ix, _)     => Identity.Proposes(ix)
    case DijkstraPurpose.Guarding(ix, _)      => Identity.Guards(ix)

  /** The fields of a constructor, which must be the one named. */
  private def fields(d: PlutusData, tag: Int, at: String): Vector[PlutusData] = d match
    case PlutusData.Constr(t, fs) if t == tag => fs
    case other                                => fail(s"$at: not constructor $tag: $other")

  private def bytes(d: PlutusData, at: String): Bytes = d match
    case PlutusData.B(b) => b
    case other           => fail(s"$at: not bytes: $other")

  private def int(d: PlutusData, at: String): BigInt = d match
    case PlutusData.I(i) => i
    case other           => fail(s"$at: not an integer: $other")

  /** `Credential`: `PubKeyCredential` (0) or `ScriptCredential` (1), in every version. */
  private def credential(d: PlutusData, at: String): Credential = d match
    case PlutusData.Constr(c, Vector(PlutusData.B(h))) if c == 0 => Credential.Key(h)
    case PlutusData.Constr(c, Vector(PlutusData.B(h))) if c == 1 => Credential.Script(h)
    case other => fail(s"$at: not a credential: $other")

  /** The identity of the purpose a reference context's arguments name (plutus-ledger-api 1.71.0.0):
    * PlutusV1 and PlutusV2's `ScriptPurpose` (`V1/Contexts.hs`: a `TxOutRef` of a `TxId`
    * constructor, a `StakingHash` credential, and a `DCert` with no index, which is not compared
    * and so refused here), PlutusV3's `ScriptInfo` (`V3/Contexts.hs`: a `TxOutRef` of bare bytes)
    * and PlutusV4's (`V4/Contexts.hs`, a list for a record, `makeIsDataSchemaAsList`).
    */
  private def referenceIdentity(r: Reference, at: String): Identity =
    val (purpose, version) = r.language match
      case DijkstraLanguage.PlutusV1 | DijkstraLanguage.PlutusV2 =>
        r.arguments.value match
          case Value.Arr(args) if args.size >= 2 =>
            (fields(data(args.last), 0, s"$at: ScriptContext") match
              case Vector(_, p) => p
              case other        => fail(s"$at: not a ScriptContext: $other")
            ) -> 2
          case other => fail(s"$at: not a list of arguments: $other")
      case DijkstraLanguage.PlutusV3 =>
        (fields(data(r.arguments), 0, s"$at: ScriptContext") match
          case Vector(_, _, info) => info
          case other              => fail(s"$at: not a ScriptContext: $other")
        ) -> 3
      case DijkstraLanguage.PlutusV4 =>
        (data(r.arguments) match
          case PlutusData.List(Vector(_, _, info, _)) => info
          case other                                  => fail(s"$at: not a ScriptContext: $other")
        ) -> 4
    def input(d: PlutusData): Identity = (version, d) match
      case (2, PlutusData.Constr(c, Vector(PlutusData.Constr(t, Vector(id)), index)))
          if c == 0 && t == 0 =>
        Identity.Spends(bytes(id, at), int(index, at))
      case (3, PlutusData.Constr(c, Vector(id, index))) if c == 0 =>
        Identity.Spends(bytes(id, at), int(index, at))
      case (4, PlutusData.List(Vector(id, index))) => Identity.Spends(bytes(id, at), int(index, at))
      case other                                   => fail(s"$at: not an output reference: $other")
    purpose match
      case PlutusData.Constr(tag, fs) =>
        (tag.toInt, fs) match
          case (0, Vector(policy)) => Identity.Mints(bytes(policy, at))
          case (1, ref +: _)       => input(ref)
          case (2, Vector(staking)) if version == 2 =>
            Identity.Withdraws(credential(fields(staking, 0, s"$at: StakingHash").head, at))
          case (2, Vector(cred))                    => Identity.Withdraws(credential(cred, at))
          case (3, Vector(index, _)) if version > 2 => Identity.Certifies(int(index, at))
          case (4, Vector(voter)) if version > 2 =>
            voter match
              case PlutusData.Constr(c, Vector(cred)) if c == 0 =>
                Identity.Votes(DijkstraVoter.Committee(credential(cred, at)))
              case PlutusData.Constr(c, Vector(cred)) if c == 1 =>
                Identity.Votes(DijkstraVoter.DRep(credential(cred, at)))
              case PlutusData.Constr(c, Vector(pool)) if c == 2 =>
                Identity.Votes(DijkstraVoter.StakePool(bytes(pool, at)))
              case other => fail(s"$at: not a voter: $other")
          case (5, Vector(index, _)) if version > 2 => Identity.Proposes(int(index, at))
          case (6, index +: _) if version == 4      => Identity.Guards(int(index, at))
          case other => fail(s"$at: a purpose this suite does not identify: $other")
      case other => fail(s"$at: not a purpose: $other")

  /** The redeemer of a reference context's arguments. */
  private def referenceRedeemer(r: Reference, at: String): PlutusData = r.language match
    case DijkstraLanguage.PlutusV1 | DijkstraLanguage.PlutusV2 =>
      r.arguments.value match
        case Value.Arr(args) if args.size >= 2 => data(args(args.size - 2))
        case other                             => fail(s"$at: not a list of arguments: $other")
    case DijkstraLanguage.PlutusV3 =>
      fields(data(r.arguments), 0, s"$at: ScriptContext") match
        case Vector(_, redeemer, _) => redeemer
        case other                  => fail(s"$at: not a ScriptContext: $other")
    case DijkstraLanguage.PlutusV4 =>
      data(r.arguments) match
        case PlutusData.List(Vector(_, redeemer, _, _)) => redeemer
        case other                                      => fail(s"$at: not a ScriptContext: $other")

  /** A collection of the reference, which must have succeeded with an explicit list of contexts: a
    * failed collection at any level is no collection with no scripts.
    */
  private def collectedContexts(j: Json, at: String): Vector[Reference] =
    assertEquals(field[Boolean](j, "collected"), true, s"$at: the reference's collection")
    field[Vector[String]](j, "contexts").map(s => reference(hex(s)))

  /** Equal bytes, or a failure naming the first byte that differs and the bytes around it. */
  private def sameBytes(ours: Bytes, theirs: Bytes, at: String): Unit =
    if ours != theirs then
      val first = ours.value.zip(theirs.value).indexWhere(_ != _) match
        case -1 => math.min(ours.size, theirs.size)
        case i  => i
      def around(b: Bytes) = b.slice(math.max(0, first - 8), math.min(b.size, first + 24)).hex
      fail(
        s"$at: first difference at byte $first of ${ours.size} (node) and ${theirs.size} " +
          s"(reference); from byte ${math.max(0, first - 8)}: node ${around(ours)}, " +
          s"reference ${around(theirs)}"
      )

  /** The node's collection of one transaction or sub-transaction against the reference's: for each
    * script the language and whether its argument was compared (PlutusV4, byte for byte) or not
    * (PlutusV1 to PlutusV3, whose context the node does not build).
    */
  private def compare(
      name: String,
      tx: DijkstraScriptContexts.Transaction,
      ours: DijkstraScriptContexts.Collection,
      theirs: Vector[Reference]
  ): Vector[(DijkstraLanguage, Boolean)] =
    val collected = ours.fold(e => fail(s"$name: not collected: $e"), identity)
    assertEquals(collected.size, theirs.size, s"$name: the number of scripts")
    collected.zip(theirs).zipWithIndex.map { case ((c, r), k) =>
      val at = s"$name, script $k"
      val (purpose, hash, language) = c match
        case DijkstraScriptContexts.Collected.V4(context) =>
          (context.purpose, context.scriptHash, DijkstraLanguage.PlutusV4)
        case DijkstraScriptContexts.Collected.Untranslated(_, _, DijkstraLanguage.PlutusV4) =>
          fail(s"$at: a PlutusV4 script whose context the node did not build")
        case DijkstraScriptContexts.Collected.Untranslated(p, h, l) => (p, h, l)
      assertEquals(language, r.language, at)
      assertEquals(hash, r.scriptHash, at)
      assertEquals(identityOf(purpose), referenceIdentity(r, at), s"$at: the purpose")
      val redeemer = tx.witnesses.redeemers
        .findLast(x => x.tag == purpose.tag && x.index == purpose.index.toLong)
        .getOrElse(fail(s"$at: no redeemer"))
      assertEquals(data(node(redeemer.data)), referenceRedeemer(r, at), s"$at: the redeemer")
      assertEquals(
        (redeemer.memory, redeemer.steps),
        (r.memory, r.steps),
        s"$at: the execution units"
      )
      c match
        case DijkstraScriptContexts.Collected.V4(context) =>
          sameBytes(
            DataSerialisation.serialise(context.argument),
            r.arguments.original,
            s"$at: the argument"
          )
          language -> true
        case _ => language -> false
    }

  test("the cases and the results are the same transactions, of a sample of the chain") {
    assert(cases.size >= 100, s"only ${cases.size} cases")
    assertEquals(results.map(field[String](_, "name")), cases.map(field[String](_, "name")))
    assertEquals(field[Vector[Int]](chain, "protocolVersion"), Vector(12, 0))
  }

  test("every transaction: the scripts, their order, purposes and redeemers are the reference's") {
    val compared = cases.zip(results).flatMap { (c, r) =>
      val name = field[String](c, "name")
      val result = field[Json](r, "result")
      assert(field[Boolean](result, "accepted"), name)
      assertEquals(field[Option[String]](result, "failure"), None, name)
      val (tx, utxo) = read(c)
      assertEquals(tx.id.hex, field[String](result, "id"), name)
      assertEquals(tx.isValid, field[Boolean](c, "valid"), name)
      val env = DijkstraScriptContexts.Environment(utxo, posix)
      val (top, subs) = DijkstraScriptContexts.transaction(tx, env)
      val theirTop = collectedContexts(field[Json](result, "top"), name)
      val theirSubs = field[Vector[Json]](result, "subtxs").zipWithIndex
        .map((j, n) => collectedContexts(j, s"$name sub-transaction $n"))
      assertEquals(subs.size, theirSubs.size, s"$name: sub-transactions")
      val ourScripts = compare(name, DijkstraScriptContexts.Transaction.of(tx), top, theirTop) ++
        subs.zip(theirSubs).zip(tx.body.subTransactions).zipWithIndex.flatMap {
          case (((ours, theirs), sub), n) =>
            val at = s"$name sub-transaction $n"
            compare(at, DijkstraScriptContexts.Transaction.of(sub), ours, theirs)
        }
      ourScripts.zip(theirTop ++ theirSubs.flatten)
    }
    val (argued, unchecked) = compared.map(_._1).partition(_._2)
    val referenceV4 = compared.count(_._2.language == DijkstraLanguage.PlutusV4)
    println(
      s"Leios testnet chain: ${cases.size} transactions, ${compared.size} scripts, each of the same " +
        s"purpose; ${argued.size} PlutusV4 arguments the reference's byte for byte; ${unchecked.size} " +
        "PlutusV1 to PlutusV3 arguments not built by the node, so not compared"
    )
    assertEquals(argued.size, referenceV4, "every PlutusV4 argument of the reference is compared")
    assert(unchecked.forall(_._1 != DijkstraLanguage.PlutusV4), "an unchecked PlutusV4 argument")
    assertEquals(
      compared.map(_._1._1).distinct.sortBy(_.hashTag),
      DijkstraLanguage.values.toVector
    )
    assert(argued.nonEmpty, "no PlutusV4 argument compared")
  }
