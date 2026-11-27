// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.{Assertions, Location}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Credential as FlatCredential,
  Ptr,
  TxIn,
  TxOut,
  Utxo,
  testByteStrings
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Credential, Staking, UTxOState}
import xyz.kd5ujc.scarab.plutus.EvalRequest
import xyz.kd5ujc.scarab.uplc.builtin.core.DataSerialisation

/** The judgments of the flat Conway UTXOS and of the flat UTXO's change of the state, recorded for
  * the ledger's suites that held the era rules to them before the flat rules were deleted
  * (`fixtures/conway-flat-refusals/utxos-judgments.jsonl`, #85), and the forms they are recorded
  * in. The ledger's tests have no refusal encoder and no state encoding, so a state and a script's
  * request are recorded by the digest of a CBOR form of their own, which holds every field
  * (`state`, `request`).
  */
object UtxoRecordings:
  private val Dir = Path.of("fixtures/conway-flat-refusals")

  /** The first 16 hex digits of the SHA-256 of `bytes`, the recordings' digest. */
  def digest(bytes: Bytes): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes.toArray)
      .take(8)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** The records of `name`: each id to its cells, separated by spaces, failing the test where an id
    * occurs twice.
    */
  def judgments(name: String): Map[String, Vector[String]] =
    val rows = Files.readAllLines(Dir.resolve(name)).asScala.toVector.map { l =>
      val j = io.circe.parser.parse(l).fold(e => Assertions.fail(s"$name: $e"), identity)
      def field(key: String) =
        j.hcursor.get[String](key).fold(e => Assertions.fail(s"$name: $key, $e"), identity)
      field("id") -> field("r").split(' ').toVector
    }
    val ids = rows.map(_._1)
    val twice = ids.diff(ids.distinct).distinct
    if twice.nonEmpty then Assertions.fail(s"$name: an id more than once: $twice")
    rows.toMap

  /** The judgments of Conway's era UTXOS and of its change of the state that the flat rules gave
    * the same of, in `EraUtxosSuite` .
    */
  lazy val utxos: Map[String, Vector[String]] = judgments("utxos-judgments.jsonl")

  private def leaf(v: Value): Node = Node(v, Bytes.empty)
  private def int(n: BigInt): Node = leaf(if n.signum < 0 then Value.NInt(n) else Value.UInt(n))
  private def bytes(b: Bytes): Node = leaf(Value.ByteString(b))
  private def arr(items: Node*): Node = leaf(Value.Arr(items.toVector))
  private def encode(n: Node)(using Location): Bytes =
    Cbor.encode(n.value, Cbor.Limits.Unbounded).fold(e => Assertions.fail(s"encode: $e"), identity)

  private def txIn(in: TxIn): Node = arr(bytes(in.id), int(in.index))
  private def out(o: TxOut): Node = bytes(o.raw)
  private def flatCredential(c: FlatCredential): Node =
    arr(int(if c.isKey then 0 else 1), bytes(c.hash))
  private def credential(c: Credential[Staking]): Node = c match
    case Credential.KeyHashObj(h)    => arr(int(0), bytes(h.bytes))
    case Credential.ScriptHashObj(h) => arr(int(1), bytes(h.bytes))
  private def ptr(p: Ptr): Node = arr(int(p.slot), int(p.txIx), int(p.certIx))

  /** The UTxO set's form: every field of it, each map in key order, an output by its bytes. */
  private def utxo(u: Utxo): Node =
    arr(
      arr(u.diff.toVector.sortBy(_._1).map { (in, change) =>
        arr(txIn(in), change.out.fold(leaf(Value.Null))(out), int(change.stamp))
      }*),
      arr(u.resident.toVector.sortBy(_._1).map((in, o) => arr(txIn(in), out(o)))*),
      arr(
        u.stake.toVector
          .sortBy((c, _) => (c.isKey, c.hash.hex))
          .map((c, n) => arr(flatCredential(c), int(n)))*
      ),
      arr(
        u.ptrStake.toVector
          .sortBy((p, _) => (p.slot, p.txIx, p.certIx))
          .map((p, n) => arr(ptr(p), int(n)))*
      ),
      int(u.coin),
      int(u.count),
      int(u.stamp),
      int(u.flushed)
    )

  /** The bytes of the CBOR form of the UTxO state `s` but its governance state, which UTXO leaves
    * as it is: the UTxO set whole, the deposits, the fees, the donation and the instant stake. An
    * output is held by its bytes, which [[heldWhole]] checks hold it.
    */
  def state(s: UTxOState[Conway])(using Location): Bytes =
    encode(
      arr(
        utxo(s.utxo.utxo),
        int(s.deposited.value),
        int(s.fees.value),
        int(s.donation.value),
        arr(
          s.instantStake.credentialStake.toVector.map((c, coin) =>
            arr(credential(c), int(coin.toBigInt))
          )*
        )
      )
    )

  /** Whether each output of `s` 's UTxO set is the output its bytes read as, so that [[state]]
    * holds it whole.
    */
  def heldWhole(s: UTxOState[Conway]): Boolean =
    val u = s.utxo.utxo
    (u.diff.values.flatMap(_.out) ++ u.resident.values).forall(o =>
      TxOut.fromBytes(o.raw).toOption.contains(o)
    )

  /** A state's cell: `A:` and the digest of [[state]], once [[heldWhole]] holds. */
  def stateCell(s: UTxOState[Conway])(using Location): String =
    Assertions.assert(heldWhole(s), "an output of the state is not the one its bytes read as")
    s"A:${digest(state(s))}"

  /** The bytes of the CBOR form of a script's request: its language, protocol version, script,
    * arguments (each serialised as Plutus data), cost model and budget.
    */
  def request(r: EvalRequest)(using Location): Bytes =
    encode(
      arr(
        int(r.language.ordinal),
        int(r.protocolMajor),
        bytes(r.script),
        arr(r.args.map(a => bytes(DataSerialisation.serialise(a)))*),
        arr(r.costModel.map(c => int(c))*),
        arr(int(r.budget.mem), int(r.budget.steps))
      )
    )
