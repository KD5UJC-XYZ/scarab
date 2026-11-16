// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.CborRead
import xyz.kd5ujc.scarab.plutus.PlutusData

/** The Plutus scripts of transactions recorded on the public Leios testnet's chain, collected with
  * their contexts by the testnet's build (cardano-ledger 9df9f98c, plutus-ledger-api 1.71.0.0).
  * `fixtures/reference/leios-testnet-chain-contexts` holds a stratified sample of the chain's
  * transactions with redeemers, with the outputs they spend and the chain's environment
  * (`leios_chain_scan.py`), and the contexts the reference collected for them before evaluation
  * (`mkDijkstraStAnnTopTx`, the chain context oracle `scripts/oracle/leios-testnet/chain`), each
  * the `ToCBOR PlutusWithContext` encoding: the protocol version, the script with its language, the
  * arguments, the redeemer's execution units and the cost model.
  *
  * [[DijkstraScriptContexts.transaction]] must collect the same scripts, in the same order, for the
  * same purposes, with the same redeemers, and a PlutusV4 script's argument byte for byte. It
  * builds no context for a PlutusV1 to PlutusV3 script (it collects one as `Untranslated`), so for
  * those scripts the argument is not compared: the suite reports how many such contexts are
  * unchecked.
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

  /** The redeemer and the purpose's constructor (`ScriptInfo`, or `ScriptPurpose` for PlutusV1 and
    * PlutusV2) of a reference context's arguments.
    */
  private def redeemerAndPurpose(r: Reference): (PlutusData, BigInt) =
    def constructor(d: PlutusData): BigInt = d match
      case PlutusData.Constr(tag, _) => tag
      case other                     => fail(s"not a constructor: $other")
    r.language match
      case DijkstraLanguage.PlutusV1 | DijkstraLanguage.PlutusV2 =>
        r.arguments.value match
          case Value.Arr(args) if args.size >= 2 =>
            data(args(args.size - 1)) match
              case PlutusData.Constr(_, Vector(_, purpose)) =>
                (data(args(args.size - 2)), constructor(purpose))
              case other => fail(s"not a ScriptContext: $other")
          case other => fail(s"not a list of arguments: $other")
      case DijkstraLanguage.PlutusV3 =>
        data(r.arguments) match
          case PlutusData.Constr(_, Vector(_, redeemer, info)) => (redeemer, constructor(info))
          case other => fail(s"not a ScriptContext: $other")
      case DijkstraLanguage.PlutusV4 =>
        data(r.arguments) match
          case PlutusData.List(Vector(_, redeemer, info, _)) => (redeemer, constructor(info))
          case other                                         => fail(s"not a ScriptContext: $other")

  /** The constructor of a purpose in `ScriptInfo` (plutus-ledger-api 1.71.0.0, `V3/Contexts.hs` and
    * `V4/Contexts.hs`; PlutusV1 and PlutusV2's `ScriptPurpose` orders its first four the same).
    */
  private def constructorOf(p: DijkstraPurpose): BigInt = p match
    case _: DijkstraPurpose.Minting     => 0
    case _: DijkstraPurpose.Spending    => 1
    case _: DijkstraPurpose.Withdrawing => 2
    case _: DijkstraPurpose.Certifying  => 3
    case _: DijkstraPurpose.Voting      => 4
    case _: DijkstraPurpose.Proposing   => 5
    case _: DijkstraPurpose.Guarding    => 6

  private def collectedContexts(j: Json): Vector[Reference] =
    j.hcursor
      .downField("contexts")
      .as[Option[Vector[String]]]
      .fold(e => fail(e.message), identity)
      .getOrElse(Vector.empty)
      .map(s => reference(hex(s)))

  /** The node's collection of one transaction or sub-transaction against the reference's: the
    * languages of the scripts whose arguments were compared (PlutusV4) and of those whose were not.
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
      val (purpose, hash, language) = c match
        case DijkstraScriptContexts.Collected.V4(context) =>
          (context.purpose, context.scriptHash, DijkstraLanguage.PlutusV4)
        case DijkstraScriptContexts.Collected.Untranslated(p, h, l) => (p, h, l)
      val at = s"$name, script $k"
      assertEquals(language, r.language, at)
      assertEquals(hash, r.scriptHash, at)
      val redeemer = tx.witnesses.redeemers
        .findLast(x => x.tag == purpose.tag && x.index == purpose.index.toLong)
        .getOrElse(fail(s"$at: no redeemer"))
      val (theirRedeemer, theirPurpose) = redeemerAndPurpose(r)
      assertEquals(data(node(redeemer.data)), theirRedeemer, s"$at: the redeemer")
      assertEquals(
        (redeemer.memory, redeemer.steps),
        (r.memory, r.steps),
        s"$at: the execution units"
      )
      assertEquals(constructorOf(purpose), theirPurpose, s"$at: the purpose")
      c match
        case DijkstraScriptContexts.Collected.V4(context) =>
          assertEquals(context.argument, data(r.arguments), s"$at: the argument")
          language -> true
        case _ => language -> false
    }

  test("the cases and the results are the same transactions, of a sample of the chain") {
    assert(cases.size >= 100, s"only ${cases.size} cases")
    assertEquals(results.map(field[String](_, "name")), cases.map(field[String](_, "name")))
    assertEquals(field[Vector[Int]](chain, "protocolVersion"), Vector(12, 0))
  }

  test("every transaction: the scripts, their order, purposes and redeemers are the reference's") {
    val scripts = cases.zip(results).flatMap { (c, r) =>
      val name = field[String](c, "name")
      val result = field[Json](r, "result")
      assert(field[Boolean](result, "accepted"), name)
      assertEquals(field[Option[String]](result, "failure"), None, name)
      val (tx, utxo) = read(c)
      assertEquals(tx.id.hex, field[String](result, "id"), name)
      assertEquals(tx.isValid, field[Boolean](c, "valid"), name)
      val env = DijkstraScriptContexts.Environment(utxo, posix)
      val (top, subs) = DijkstraScriptContexts.transaction(tx, env)
      val theirSubs = field[Vector[Json]](result, "subtxs")
      assertEquals(subs.size, theirSubs.size, s"$name: sub-transactions")
      compare(
        name,
        DijkstraScriptContexts.Transaction.of(tx),
        top,
        collectedContexts(field[Json](result, "top"))
      ) ++
        subs.zip(theirSubs).zip(tx.body.subTransactions).zipWithIndex.flatMap {
          case (((ours, theirs), sub), n) =>
            val at = s"$name sub-transaction $n"
            compare(at, DijkstraScriptContexts.Transaction.of(sub), ours, collectedContexts(theirs))
        }
    }
    val (compared, unchecked) = scripts.partition(_._2)
    println(
      s"Leios testnet chain: ${cases.size} transactions, ${scripts.size} scripts; ${compared.size} PlutusV4 " +
        s"arguments equal to the reference's; ${unchecked.size} PlutusV1 to PlutusV3 arguments not built " +
        "by the node, so not compared"
    )
    assertEquals(scripts.map(_._1).distinct.sortBy(_.hashTag), DijkstraLanguage.values.toVector)
    assert(compared.nonEmpty, "no PlutusV4 argument compared")
  }
