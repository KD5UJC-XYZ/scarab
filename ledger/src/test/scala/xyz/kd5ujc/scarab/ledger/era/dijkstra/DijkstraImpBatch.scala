// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.TestLedger.Key
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.era.{TxLevel, UTxO}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.{hex, node, txIn}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra

/** Batches built as the reference's `Imp` tests build theirs (`Test/Cardano/Ledger/Dijkstra/
  * ImpTest.hs`, cardano-ledger 9df9f98c), encoded as the CDDL gives them
  * (`eras/dijkstra/impl/cddl/data/dijkstra.cddl`): bodies of the fields given by key, each level
  * signed by the keys given (`TestLedger.Key`, real signatures over its own body's id), and the
  * outputs they spend. What the reference's fixups add (an input, a fee, a change output, the
  * signatures, a collateral input, the script integrity hash) a test gives itself.
  */
object DijkstraImpBatch extends munit.Assertions:
  def leaf(v: Value): Node = Node(v, Bytes.empty)
  def uint(n: BigInt): Node = leaf(Value.UInt(n))
  def bytes(b: Bytes): Node = leaf(Value.ByteString(b))
  def arr(items: Node*): Node = leaf(Value.Arr(items.toVector))
  def cmap(fields: (Node, Node)*): Node = leaf(Value.Map(fields.toVector))
  def tagged(n: Int, item: Node): Node = leaf(Value.Tag(BigInt(n), item))
  val nil: Node = leaf(Value.Null)

  /** `set<a0>` and `nonempty_oset<a0>` in their tagged form (`#6.258`). */
  def set(items: Node*): Node = tagged(258, leaf(Value.Arr(items.toVector)))

  def encode(n: Node): Bytes = Cbor.encode(n.value).fold(e => fail(s"$e"), identity)

  /** A script's hash: blake2b-224 of its language's tag, then the script (`hashScript`). */
  def scriptHash(tag: Int, script: Bytes): Bytes =
    Blake2b.hash224.hash(Bytes(tag.toByte +: script.value))

  /** A transaction input: the transaction's id and the output's index. */
  final case class In(txId: Bytes, ix: Int = 0):
    def node: Node = arr(bytes(txId), uint(ix))

  /** An input of a transaction whose id is 32 bytes of `n`, a funded input of a test's own. */
  def fresh(n: Int): In = In(Bytes(Vector.fill(32)(n.toByte)))

  /** The testnet's (network 0) enterprise address of a key, of a script; its reward account. */
  def keyAddress(k: Key): Bytes = Bytes(0x60.toByte +: k.hash.value)
  def scriptAddress(hash: Bytes): Bytes = Bytes(0x70.toByte +: hash.value)
  def rewardAccount(k: Key): Bytes = Bytes(0xe0.toByte +: k.hash.value)

  /** `alonzo_transaction_output` of ada alone. */
  def out(address: Bytes, coin: BigInt): Node = arr(bytes(address), uint(coin))

  /** `babbage_transaction_output` holding a Plutus V3 reference script (`script_ref`). */
  def refScriptOut(address: Bytes, coin: BigInt, plutusV3: Bytes): Node =
    cmap(
      uint(0) -> bytes(address),
      uint(1) -> uint(coin),
      uint(3) -> tagged(24, bytes(encode(arr(uint(3), bytes(plutusV3)))))
    )

  /** `alonzo_transaction_output` of ada and the hash of a datum (`datum_hash`). */
  def datumHashOut(address: Bytes, coin: BigInt, datumHash: Bytes): Node =
    arr(bytes(address), uint(coin), bytes(datumHash))

  /** A datum's hash: blake2b-256 of its bytes (`hashData`). */
  def dataHash(datum: Node): Bytes = Blake2b.hash256.hash(encode(datum))

  /** A body of the fields given by key. */
  def body(fields: (Int, Node)*): Node = cmap(fields.map((k, v) => uint(k) -> v)*)

  def id(body: Node): Bytes = Blake2b.hash256.hash(encode(body))

  /** A witness set: a signature of `body` 's id by each of `signers`, and the fields `more`. */
  def witnesses(body: Node, signers: Vector[Key], more: (Int, Node)*): Node =
    val vkeys = signers.distinctBy(_.vkey).map(k => arr(bytes(k.vkey), bytes(k.sign(id(body)))))
    cmap(
      (Option.when(vkeys.nonEmpty)(uint(0) -> set(vkeys*)).toVector ++
        more.map((k, v) => uint(k) -> v))*
    )

  /** `sub_transaction`: its body signed by `signers`, without auxiliary data. */
  def subTx(body: Node, signers: Vector[Key]): Node = arr(body, witnesses(body, signers), nil)

  /** `block_transaction`: the body, its witness set, no auxiliary data and the flag. */
  def topTx(body: Node, wits: Node, valid: Boolean = true): DijkstraTx[TxLevel.Top] =
    DijkstraTx
      .inBlock(node(encode(arr(body, wits, nil, leaf(Value.Bool(valid))))))
      .fold(e => fail(s"$e"), identity)

  /** The outputs the inputs given spend. */
  def utxo(entries: (In, Node)*): UTxO[Dijkstra] =
    UTxO[Dijkstra](
      entries
        .map((in, o) =>
          txIn(DijkstraTxIn.decode(node(encode(in.node))).fold(e => fail(s"$e"), identity)) ->
            DijkstraTxOut.decode(node(encode(o))).fold(e => fail(s"$e"), identity)
        )
        .toMap
    )

  /** `alwaysFailsWithDatum SPlutusV3` and `purposeIsWellformedNoDatum SPlutusV3`, their
    * `PlutusBinary`
    * (`libs/cardano-ledger-core/testlib/Test/Cardano/Ledger/Plutus/Examples.hs:309-328, 725-765`,
    * cardano-ledger 9df9f98c), with the script hash the comment before each lists.
    */
  lazy val alwaysFailsWithDatumV3: Bytes = hex(
    "589c0101009800aab9daba2ab9aaab9eaba1ab9c48888889660026466002446464b30013370e90011806000c4c" +
      "c01488c8c94cd4d6600266e1d2000001891909118010019806800c56600266e1d200200189091000c5900e201c" +
      "300d002214a02944c03c004dd518050009804800c528a0143754600e6014004600c002600a6ea800888cc008c0" +
      "14004c02000629344c98cc009241035054350049900501"
  )

  lazy val purposeIsWellformedNoDatumV3: Bytes = hex(
    "5902720101009800aab9daba3aba0aba2ab9aaab9eaba1ab9bab9caab9f4888888888896600264660024464660" +
      "08446464646464646464b30013370e9000000c66002ea66eb8c04cc050016653001237560032374c0032329800" +
      "980d800c01e244600400680c26002eb646ea000653001337109000000c488c00800e00e80c200280126eacc050" +
      "025001111191800800919803a5eb7bdb18094cd4c00cc014c064004848cc078cdd818100019803001198020020" +
      "008919801801800a0048992cc004cdc3a400400513300f223232533535980099b8748000006246424460040066" +
      "03200315980099b87480080062401516406880d0c06400885280cc004c076600240032001919baf00533014220" +
      "02301a3754003375801e802a94294501a180e8009baa301600130150068acc004cdc3a4008005198009000cc05" +
      "0c05401a6eacc05001d003456600266e1d2006002899807914c004c06e600240032001919baf30170020019bac" +
      "301600a400d4a14a280c0c05401a2b30013370e90040014660024003301430150069bab3014301730173017301" +
      "730173017007400d15980099b874802800a26601e44944c05401a26466028002002c80b1016202c405880b0888" +
      "c88c008004c8c0040048a600297ae091192cc004c0180062646604060100040026600a00a00513300500500240" +
      "74600e005001402080a8c060010888c8cc0040040088cc0152814c004cdd79802802180e000c928c8cc00c00c0" +
      "050181112cc00400620071330023013001301600140402440026ea8c038c04401cc040004c03c004c038c038c0" +
      "38004c028dd5001980400098039baa00222330023007001300a0018a4d132633003491035054350049900701"
  )

  val alwaysFailsWithDatumV3Hash: Bytes =
    hex("fe6f5484631753d44c197639b874d34bd65b33a590ac7d8c1cd43ca5")
  val purposeIsWellformedNoDatumV3Hash: Bytes =
    hex("c4940231fc846fdda35da36b5b92d99d93c87a0dd1b55d3a9ed46ee3")
