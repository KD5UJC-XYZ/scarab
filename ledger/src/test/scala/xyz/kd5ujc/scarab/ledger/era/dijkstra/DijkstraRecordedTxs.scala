// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import io.circe.Json
import munit.Assertions
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.era.{IsPhase2Valid, TxLevel, UTxO}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra

import DijkstraCorpus.{hex, str}

/** Every Dijkstra transaction the repository records that the reference reads, each selected by the
  * verdict its fixture records and read by the era's decoders, with the bytes it was recorded as
  * and the outputs it spends where the fixture records them. A transaction the reference reads that
  * the era's decoder refuses fails, and so does one of the Leios corpus the reference refuses that
  * the era's decoder reads.
  *
  * [[Parts]] reads what a suite compares the era's types with from those bytes, by plain CBOR and
  * the Dijkstra CDDL (`eras/dijkstra/impl/cddl/data/dijkstra.cddl`, cardano-ledger 9df9f98c), not
  * by the era's decoders.
  */
object DijkstraRecordedTxs extends Assertions:

  /** How the bytes hold the transaction: a block's form `[body, witnesses, auxiliary data / null,
    * is_valid]`, or a submitted one `[body, witnesses, auxiliary data / null]` (or the legacy
    * `[body, witnesses, true, auxiliary data / null]`).
    */
  enum Form:
    case InBlock, Submitted

  object Form:
    given CanEqual[Form, Form] = CanEqual.derived

  final case class Recorded(
      name: String,
      raw: Bytes,
      form: Form,
      utxo: Option[Vector[(Bytes, Bytes)]],
      tx: DijkstraTx[TxLevel.Top]
  )

  // ---- the sources

  /** A transaction in its block form that the reference read, which the era's decoder must read. */
  def inBlock(name: String, raw: Bytes, utxo: Option[Vector[(Bytes, Bytes)]]): Recorded =
    DijkstraCorpus.cbor(raw) match
      case None => fail(s"$name: the reference reads it, and it is not CBOR")
      case Some(n) =>
        DijkstraTx
          .inBlock(n)
          .fold(
            e => fail(s"$name: the reference reads it, the era's decoder refuses it: $e"),
            tx => Recorded(name, raw, Form.InBlock, utxo, tx)
          )

  private def utxoOf(j: Json): Vector[(Bytes, Bytes)] =
    j.hcursor
      .get[Vector[(String, String)]]("utxo")
      .fold(e => fail(e.message), identity)
      .map((i, o) => (hex(i), hex(o)))

  /** The transactions of a Dijkstra block `[8, [header, [transactions, certificate, Peras]]]`,
    * which the era's block decoder must read, with the bytes the block holds for each.
    */
  def ofBlock(name: String, raw: Bytes): Vector[Recorded] =
    val block = DijkstraBlock.decode(raw).fold(e => fail(s"$name: $e"), identity)
    val held = elems(elems(elems(elems(item(raw))(1))(1))(0)).map(_.original)
    val txs = block.blockBody.txs
    assertEquals(txs.size, held.size, name)
    txs.zip(held).zipWithIndex.map { case ((tx, bytes), i) =>
      Recorded(s"$name tx $i", bytes, Form.InBlock, None, tx)
    }

  /** The Leios corpus's transactions alone (`fixtures/reference/leios-testnet-blocks`): the 273 the
    * build reads; each of the 263 it refuses is refused.
    */
  lazy val leiosTxs: Vector[Recorded] =
    DijkstraCorpus.leiosCorpus.filter(j => str(j, "kind") == "tx").flatMap { j =>
      val raw = hex(str(j, "bytes"))
      if j.hcursor.get[Boolean]("accepted").fold(e => fail(e.message), identity) then
        Vector(inBlock(str(j, "name"), raw, None))
      else
        assert(
          DijkstraCorpus.cbor(raw).forall(DijkstraTx.inBlock(_).isLeft),
          s"${str(j, "name")}: the reference refuses it, the era's decoder reads it"
        )
        Vector.empty
    }

  /** The transactions of the Leios corpus's blocks the build reads, each the one the corpus records
    * at its place (its id).
    */
  lazy val leiosBlockTxs: Vector[Recorded] =
    DijkstraCorpus.leiosCorpus
      .filter(j =>
        str(j, "kind") == "block" &&
          j.hcursor.get[Boolean]("accepted").fold(e => fail(e.message), identity) &&
          isDijkstra(hex(str(j, "bytes")))
      )
      .flatMap { j =>
        val name = str(j, "name")
        val txs = ofBlock(name, hex(str(j, "bytes")))
        // each transaction's id and validity flag as the corpus records them, in order
        val listed = j.hcursor
          .get[Vector[Json]]("txs")
          .fold(e => fail(e.message), identity)
          .map(t =>
            t.hcursor.get[String]("id").fold(e => fail(e.message), identity) ->
              t.hcursor.get[Boolean]("valid").fold(e => fail(e.message), identity)
          )
        assertEquals(
          txs.map(r => r.tx.id.hex -> (DijkstraTx.isValid(r.tx) == IsPhase2Valid.Phase2Valid)),
          listed,
          name
        )
        txs
      }

  /** Whether a block's envelope `[tag, block]` is tag 8, Dijkstra's (`CardanoShelleyEras`). */
  def isDijkstra(raw: Bytes): Boolean = elems(item(raw)).headOption.exists(_.value == Value.UInt(8))

  /** The public testnet's Dijkstra blocks (`fixtures/leios/testnet/blocks-rc-2026w44.tsv`). */
  lazy val testnetBlockTxs: Vector[Recorded] =
    DijkstraCorpus.testnetBlocks.filter((_, raw) => isDijkstra(raw)).flatMap(ofBlock)

  /** The devnet chain's Dijkstra blocks (`fixtures/dijkstra-devnet`). */
  lazy val devnetBlockTxs: Vector[Recorded] =
    DijkstraCorpus.devnetBlocks.filter((_, raw) => isDijkstra(raw)).flatMap(ofBlock)

  /** The script-context fixtures' transactions, with the outputs they spend: the 11 written by
    * hand, the 200 of the testnet's chain and the 22 of the context refusals, each of which the
    * reference read.
    */
  lazy val contextTxs: Vector[Recorded] =
    val refusals = DijkstraCorpus
      .json("fixtures/reference/leios-testnet-context-refusals/cases.json")
      .hcursor
      .get[Vector[Json]]("cases")
      .fold(e => fail(e.message), identity)
    (DijkstraCorpus.contextCases ++ DijkstraCorpus.chainContextCases ++ refusals).map(c =>
      inBlock(str(c, "name"), hex(str(c, "bytes")), Some(utxoOf(c)))
    )

  /** The reference's UTXOW and ENTITIES cases (`fixtures/reference/dijkstra-rules`), each a
    * transaction of a block body with the outputs it spends, which the reference read.
    */
  lazy val rulesTxs: Vector[(String, Recorded)] =
    DijkstraCorpus
      .jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl")
      .filter(j => Set("utxow", "entities")(str(j, "kind")))
      .map(j => str(j, "kind") -> inBlock(str(j, "name"), hex(str(j, "tx")), Some(utxoOf(j))))

  /** The submitted transactions: the devnet's and the crossing corpus's mempool forms, which the
    * reference read.
    */
  lazy val submittedTxs: Vector[Recorded] =
    (DijkstraCorpus.devnetSubmitted +: DijkstraCorpus.crossingForms).zipWithIndex.map { (raw, i) =>
      DijkstraTx
        .fromSubmission(raw)
        .fold(
          e => fail(s"submitted $i: $e"),
          tx => Recorded(s"submitted $i", raw, Form.Submitted, None, tx)
        )
    }

  // ---- the parts, read by plain CBOR

  def item(raw: Bytes): Node = Cbor.decode(raw).fold(e => fail(e.toString), identity)

  /** An array's items, through a tag (`set<a> = #6.258([* a]) / [* a]`). */
  def elems(n: Node): Vector[Node] = n.value match
    case Value.Tag(_, inner) => elems(inner)
    case Value.Arr(xs)       => xs
    case other               => fail(s"not an array: $other")

  private def entries(n: Node): Vector[(Node, Node)] = n.value match
    case Value.Tag(_, inner) => entries(inner)
    case Value.Map(kvs)      => kvs
    case other               => fail(s"not a map: $other")

  private def field(map: Node, key: Int): Option[Node] =
    entries(map).collectFirst { case (k, v) if k.value == Value.UInt(BigInt(key)) => v }

  private def uint(n: Node): BigInt = n.value match
    case Value.UInt(x) => x
    case other         => fail(s"not an unsigned integer: $other")

  private def bytes(n: Node): Bytes = n.value match
    case Value.ByteString(b) => b
    case other               => fail(s"not a byte string: $other")

  /** A level of a transaction: a body and its witness set. */
  final case class Level(body: Node, wits: Node):
    /** `set<transaction_input>` (key 0) and the reference inputs (key 18), each `[id, index]`. */
    def inputs: Vector[TxIn] =
      (field(body, 0).toVector ++ field(body, 18)).flatMap(elems).map { i =>
        elems(i) match
          case Vector(id, ix) => TxIn(bytes(id), uint(ix))
          case other          => fail(s"not an input: $other")
      }

    /** The Plutus scripts of the witness set, by hash, with their language's tag: keys 3, 6, 7 and
      * 8 hold V1 to V4 (`transaction_witness_set`, `dijkstra.cddl:865-875`), each hashed with its
      * tag before its bytes (`hashScript`).
      */
    def plutusScripts: Map[Bytes, Int] =
      Vector(3 -> 1, 6 -> 2, 7 -> 3, 8 -> 4).flatMap { (key, tag) =>
        field(wits, key).toVector.flatMap(elems).map(s => plutusHash(tag, bytes(s)) -> tag)
      }.toMap

    /** The redeemers' execution units added up (`redeemers`, `dijkstra.cddl:888-892`, a map of
      * `[tag, index]` to `[data, ex_units]`; the list form is refused from version 12). A key given
      * twice counts once, with its last value, as `decodeMapRedeemers` builds the map
      * (`Map.fromList`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:566-592`,
      * cardano-ledger 9df9f98c).
      */
    def units: (BigInt, BigInt) =
      val byKey = field(wits, 5).toVector.flatMap { r =>
        entriesOrNone(r) match
          case Some(kvs) => kvs.map((k, v) => elems(k).map(uint) -> elems(elems(v)(1)).map(uint))
          case None      => fail(s"not a map of redeemers: $r")
      }
      byKey.toMap.values.foldLeft((BigInt(0), BigInt(0))) {
        case ((m, s), Vector(mem, steps)) => (m + mem, s + steps)
        case (_, other)                   => fail(s"not execution units: $other")
      }

  private def entriesOrNone(n: Node): Option[Vector[(Node, Node)]] = n.value match
    case Value.Tag(_, inner) => entriesOrNone(inner)
    case Value.Map(kvs)      => Some(kvs)
    case _                   => None

  def plutusHash(tag: Int, script: Bytes): Bytes =
    Blake2b.hash224.hash(Bytes.fromArray(Array(tag.toByte)) ++ script)

  /** A transaction's top level, then each sub-transaction's (`sub_transactions`, key 23, each
    * `[sub_transaction_body, transaction_witness_set, auxiliary_data / nil]`,
    * `dijkstra.cddl:827-830`).
    */
  def levels(r: Recorded): Vector[Level] =
    val top = elems(item(r.raw)) match
      case b +: w +: _ => Level(b, w)
      case other       => fail(s"${r.name}: not a transaction: $other")
    top +: field(top.body, 23).toVector.flatMap(elems).map { s =>
      elems(s) match
        case Vector(b, w, _) => Level(b, w)
        case other           => fail(s"${r.name}: not a sub-transaction: $other")
    }

  /** The id of a level, the hash of its body's bytes. */
  def id(level: Level): Bytes = Blake2b.hash256.hash(level.body.original)

  /** An output's Plutus reference script, by hash, with its language's tag: key 3 of the map form,
    * `#6.24(bytes .cbor script)`, whose script is `[tag, bytes]` (`dijkstra.cddl:218-223, 385,
    * 393-399`); a native script's or none.
    */
  def referenceScript(out: Bytes): Option[(Bytes, Int)] =
    val o = item(out)
    entriesOrNone(o).flatMap(_ => field(o, 3)).flatMap { ref =>
      ref.value match
        case Value.Tag(t, inner) if t == BigInt(24) =>
          elems(item(bytes(inner))) match
            case Vector(tag, script) =>
              val t = uint(tag).toInt
              Option.when(t >= 1)(plutusHash(t, bytes(script)) -> t)
            case other => fail(s"not a script: $other")
        case other => fail(s"not a script reference: $other")
    }

  /** The recorded outputs as the era's UTxO set. */
  def eraUtxo(r: Recorded): UTxO[Dijkstra] =
    UTxO[Dijkstra](
      r.utxo
        .getOrElse(Vector.empty)
        .map { (i, o) =>
          val in = elems(item(i)) match
            case Vector(id, ix) => TxIn(bytes(id), uint(ix))
            case other          => fail(s"not an input: $other")
          in -> DijkstraTxOut.decode(DijkstraCorpus.node(o)).fold(e => fail(e.toString), identity)
        }
        .toMap
    )

  /** The recorded outputs' Plutus reference scripts, by the input that spends each. */
  def referenceScripts(r: Recorded): Map[TxIn, (Bytes, Int)] =
    r.utxo
      .getOrElse(Vector.empty)
      .flatMap { (i, o) =>
        val in = elems(item(i)) match
          case Vector(id, ix) => TxIn(bytes(id), uint(ix))
          case other          => fail(s"not an input: $other")
        referenceScript(o).map(in -> _)
      }
      .toMap
