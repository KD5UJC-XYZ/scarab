// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.Assertions.fail
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.rules.NativeRules
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.node.mempool.{ConwayPools, MempoolState, TxKit}
import xyz.kd5ujc.scarab.node.ntc.CborOut
import xyz.kd5ujc.scarab.protocol.Era

/** Inputs whose byte strings are drawn from `strings` (`CollidingBytes.of` for strings of one hash
  * code, `CollidingBytes.random` for the control), for [[SharedHashCodeSuite]] and the measurement
  * in `tools` (`SharedHashCodeBench`).
  */
object SharedHashCodeInputs:
  type Strings = (Int, Int) => Vector[Bytes]

  lazy val conwayRaw: Bytes = Files
    .readString(Path.of("fixtures/eras/blocks.hex"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(line => Bytes.fromHex(line).fold(e => fail(e.message), identity))
    .find(raw => Block.decode(raw, BigInt(50)).exists(_.era == Era.Conway))
    .getOrElse(fail("no Conway block among the fixtures"))

  lazy val conway: Block =
    Block.decode(conwayRaw, BigInt(50)).fold(e => fail(e.message), identity)

  /** The fixture block with its header kept and its body replaced by one transaction, its witness
    * set `witnesses` and the auxiliary data `auxiliary`.
    */
  private def forged(
      witnesses: CborOut => Unit,
      auxiliary: CborOut => Unit,
      fields: Vector[CborOut => Unit] = Vector.empty,
      outputs: CborOut => Unit = w =>
        w.array(1).array(2).bytes(Bytes(0x61.toByte +: Vector.fill(28)(7.toByte))).uint(1000000)
  ): Bytes =
    val parts = Cbor.decode(conwayRaw).fold(e => fail(e.message), identity).value match
      case CborValue.Arr(Vector(_, block)) =>
        block.value match
          case CborValue.Arr(items) => items
          case other                => fail(s"expected the block's parts, got $other")
      case other => fail(s"expected [era, block], got $other")
    val body = CborOut.build { w =>
      // `{0: [[id, 0]], 1: outputs, 2: 200000}` and `fields`
      w.array(1).map(3 + fields.size)
      w.uint(0).array(1).array(2).bytes(Bytes(Vector.fill(32)(1.toByte))).uint(0)
      w.uint(1)
      outputs(w)
      w.uint(2).uint(200000)
      fields.foreach(_(w))
      w.array(1)
      witnesses(w)
      auxiliary(w)
      w.array(0)
    }
    conwayRaw.take(parts(1).offset) ++ body

  /** A fetched body of one transaction whose witness set holds `n` datums of seven bytes. */
  def datumsBody(strings: Strings, n: Int): Bytes =
    val datums = strings(n, 7)
    forged(w => { w.map(1).uint(4).array(datums.size); datums.foreach(w.bytes) }, _.map(0))

  /** A fetched body whose auxiliary data map has `n` keys, byte strings of seven bytes. */
  def auxiliaryKeysBody(strings: Strings, n: Int): Bytes =
    val keys = strings(n, 7)
    forged(_.map(0), w => { w.map(keys.size); keys.foreach(k => w.bytes(k).uint(0)) })

  /** A fetched body whose transaction registers `n` stake credentials, a fixed prefix and seven
    * bytes each (Conway's certificates, an ordered set, key 4).
    */
  def certificatesBody(strings: Strings, n: Int): Bytes =
    val hashes = strings(n, 7).map(Bytes(Vector.fill(21)(4.toByte)) ++ _)
    forged(
      _.map(0),
      _.map(0),
      Vector(w => {
        w.uint(4).array(hashes.size);
        hashes.foreach(h => w.array(2).uint(0).array(2).uint(0).bytes(h))
      })
    )

  /** A fetched body whose transaction proposes `n` information actions, each returning its deposit
    * to its own reward account, a fixed prefix and seven bytes (Conway's proposals, key 20).
    */
  def proposalsBody(strings: Strings, n: Int): Bytes =
    val accounts = strings(n, 7).map(s => Bytes(0xe0.toByte +: Vector.fill(21)(4.toByte)) ++ s)
    forged(
      _.map(0),
      _.map(0),
      Vector(w => {
        w.uint(20).array(accounts.size)
        accounts.foreach(a =>
          w.array(4).uint(1000).bytes(a).array(1).uint(6)
          w.array(2).text("u").bytes(Bytes(Vector.fill(32)(2.toByte)))
        )
      })
    )

  /** A fetched body whose transaction has `outputs` outputs, each of one policy and 1,480 names of
    * seven bytes, as many as a value may hold and still compact (`44 * 1480 + 28 <= 65535`).
    */
  def outputsBody(strings: Strings, outputs: Int): Bytes =
    val names = strings(1480, 7)
    val policy = Bytes(Vector.fill(28)(9.toByte))
    val address = Bytes(0x61.toByte +: Vector.fill(28)(7.toByte))
    forged(
      _.map(0),
      _.map(0),
      outputs = w => {
        w.array(outputs)
        (1 to outputs).foreach { _ =>
          w.array(2).bytes(address).array(2).uint(2000000).map(1).bytes(policy).map(names.size)
          names.foreach(d => w.bytes(d).uint(1))
        }
      }
    )

  /** A mint field of one policy and `n` names of seven bytes. */
  def mint(strings: Strings, n: Int, quantity: Int = 1): Bytes =
    val names = strings(n, 7)
    CborOut.build { w =>
      w.map(1).bytes(Bytes(Vector.fill(28)(9.toByte))).map(names.size)
      names.foreach(d => w.bytes(d).uint(quantity))
    }

  /** A mint field of `n` policies, a fixed prefix and seven bytes, each with no names. */
  def emptyPolicies(strings: Strings, n: Int): Bytes =
    val policies = strings(n, 7).map(Bytes(Vector.fill(21)(9.toByte)) ++ _)
    CborOut.build { w =>
      w.map(policies.size)
      policies.foreach(p => w.bytes(p).map(0))
    }

  /** `n` inputs whose ids are a fixed prefix and seven bytes. */
  def inputs(strings: Strings, n: Int): Vector[TxIn] =
    strings(n, 7).map(d => TxIn(Bytes(Vector.fill(25)(3.toByte)) ++ d, 0))

  /** A pool over a ledger whose `inputs` outputs hold 550 names each (a value of under 5,000 bytes,
    * as `maxValueSize` allows), and a transaction that spends them all and does not balance.
    */
  def unbalanced(strings: Strings, inputs: Int): (MempoolState, Bytes) =
    import TxKit.{alice, bob, ada}
    val policy = Bytes(Vector.fill(28)(9.toByte))
    val funds = strings(inputs * 550, 7).grouped(550).toVector.zipWithIndex.map { (names, i) =>
      TxIn(Blake2b.hash256.hash(Bytes(Vector((i >> 8).toByte, i.toByte))), 0) ->
        TxOut(alice.address(), Value(30 * ada, Map(policy -> names.map(_ -> BigInt(1)).toMap)))
    }
    val ledger = ConwayPools.of(NativeRules.stateIn[Conway](10, funds))
    val (raw, _) =
      TxKit.tx(
        funds.map(_._1),
        Vector(bob.address() -> (30 * ada * inputs - ada)),
        ada,
        Vector(alice)
      )
    (MempoolState.empty(Right(ledger), 1000, None), raw)

  /** A transaction as a peer relays it, `[body, {4: [* datum]}, true, null]`, whose witness set
    * holds `n` datums of six bytes: 9,300 of them make 65,192 bytes, under the 65,536 a reply's
    * transaction may take.
    */
  def relayed(strings: Strings, n: Int): Bytes =
    CborOut.build { w =>
      w.array(4).map(3)
      w.uint(0).array(1).array(2).bytes(Bytes(Vector.fill(32)(1.toByte))).uint(0)
      w.uint(1).array(1).array(2).bytes(Bytes(0x61.toByte +: Vector.fill(28)(7.toByte)))
      w.uint(99000000)
      w.uint(2).uint(1000000)
      val datums = strings(n, 6)
      w.map(1).uint(4).array(datums.size)
      datums.foreach(w.bytes)
      w.bool(true).nul
    }

  /** A Dijkstra witness set of `n` vkey witnesses, keys of a fixed prefix and seven bytes, one
    * signature: `{0: [* [vkey, signature]]}`.
    */
  def dijkstraWitnesses(strings: Strings, n: Int): Bytes =
    val keys = strings(n, 7).map(Bytes(Vector.fill(25)(6.toByte)) ++ _)
    CborOut.build { w =>
      w.map(1).uint(0).array(keys.size)
      keys.foreach(k => w.array(2).bytes(k).bytes(Bytes(Vector.fill(64)(8.toByte))))
    }

  /** `n` key hashes of 28 bytes, in ascending order, as a set argument is written. */
  private def keyHashes(strings: Strings, n: Int): Vector[Bytes] =
    strings(n, 7).map(Bytes(Vector.fill(21)(5.toByte)) ++ _).sorted

  /** Conway's `GetStakePoolParams` of `n` pools: `[0,[0,[6,[17, #6.258([* keyhash])]]]]`. */
  def poolsQuery(strings: Strings, n: Int): Bytes =
    val ids = keyHashes(strings, n)
    CborOut.build { w =>
      w.array(2).uint(0).array(2).uint(0).array(2).uint(6).array(2).uint(17).set(ids.size)
      ids.foreach(w.bytes)
    }

  /** Conway's `GetFilteredDelegationsAndRewardAccounts` of `n` key credentials. */
  def accountsQuery(strings: Strings, n: Int): Bytes =
    val ids = keyHashes(strings, n)
    CborOut.build { w =>
      w.array(2).uint(0).array(2).uint(0).array(2).uint(6).array(2).uint(10).array(ids.size)
      ids.foreach(h => w.array(2).uint(0).bytes(h))
    }
