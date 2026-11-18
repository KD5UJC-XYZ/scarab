// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{ConwayParam, DecodeField, GovAction, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Writer}
import xyz.kd5ujc.scarab.protocol.DijkstraNotYet

import DecodeError.{Arity, Kind}

/** Dijkstra's `NewEpochState` in the `state` layout of the Leios build (#826): the state of the
  * first Dijkstra epoch the build wrote (`fixtures/leios/state/dijkstra-203`) and the public
  * testnet's at epoch 100 (`fixtures/leios/state/testnet-2168639`) read and written byte for byte
  * at version 12, and the 11.1 line's Dijkstra state still refused. Neither holds a proposal or
  * future pool parameters, and only the testnet's pools hold keys, so the switches of the
  * reference's decoders at version 12 (cardano-ledger 9df9f98c) are tested on hand-built items: the
  * bounded ratios, a pool's parameters and a proposal's update on both sides of the switch, and the
  * indefinite records and strings at 12 only, where the reference accepts them (below 12 the
  * reference refuses them and this build's state codecs accept them, which is not tested here).
  */
class DijkstraEpochStateSuite extends FunSuite:
  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def at(n: Node, path: Int*): Node = path.foldLeft(n)((m, i) => items(m)(i))

  private val root =
    val bytes = Files.readAllBytes(Path.of("fixtures/leios/state/dijkstra-203/state"))
    Cbor.decode(Bytes.wrap(bytes), HaskellState.limits).fold(e => fail(e.toString), identity)

  /** The `NewEpochState` of the Leios layout: `[1, [ledger, header, Peras]]`, the ledger's
    * telescope at its eighth era, Dijkstra, the current state's `[version, [tip, state]]`.
    */
  private val nes = at(root, 1, 0, 7, 1, 1, 1)

  private def read[A](c: Codec[A], n: Node): A = c.decode(n).fold(e => fail(e.render), identity)

  private def failure[A](c: Codec[A], bytes: Bytes): DecodeError =
    c.decodeBytes(bytes, HaskellState.limits).fold(identity, a => fail(s"decoded $a"))

  test("slot 203's NewEpochState: six fields, read and written byte for byte at version 12") {
    val codec = LeiosStateCodecs.newEpochState[Dijkstra]
    assertEquals(items(nes).size, 6)
    val state = read(codec, nes)
    assertEquals(state.layout, NewEpochState.Layout.Leios)
    assertEquals(codec.write(state), Right(nes.original))
    // the three pools of the devnet, each with no voting key yet
    val pools = LedgerState.pState[Dijkstra].get(state.es.ls).stakePools
    assertEquals(pools.size, 3)
    assert(pools.values.forall(_.blsKey == PoolBlsKey.Leios(None)))
  }

  /** The public Leios testnet's state at slot 2,168,639 (epoch 100), which a following node of the
    * testnet's release wrote (`fixtures/leios/state/testnet-2168639`): far into Dijkstra, with 154
    * pools that each hold a voting key and the indefinite maps the reference writes.
    */
  test(
    "the testnet's NewEpochState at slot 2,168,639: read and written byte for byte, every pool keyed"
  ) {
    val bytes = Files.readAllBytes(Path.of("fixtures/leios/state/testnet-2168639/state"))
    val testnet =
      Cbor.decode(Bytes.wrap(bytes), HaskellState.limits).fold(e => fail(e.toString), identity)
    val codec = LeiosStateCodecs.newEpochState[Dijkstra]
    val state = read(codec, at(testnet, 1, 0, 7, 1, 1, 1))
    assertEquals(state.epoch.toBigInt, BigInt(100))
    assertEquals(codec.write(state), Right(at(testnet, 1, 0, 7, 1, 1, 1).original))
    val pools = LedgerState.pState[Dijkstra].get(state.es.ls).stakePools
    assertEquals(pools.size, 154)
    assert(pools.values.forall(_.blsKey match
      case PoolBlsKey.Leios(key) => key.isDefined
      case PoolBlsKey.Node11_1   => false))
  }

  test("the 11.1 line's Dijkstra state is still refused both ways") {
    val codec = HaskellStateCodecs.newEpochState[Dijkstra]
    assertEquals(failure(codec, nes.original).kind, Kind.Domain(DijkstraNotYet.LedgerState))
    val state = read(LeiosStateCodecs.newEpochState[Dijkstra], nes)
    assert(codec.write(state).isLeft)
  }

  // ---- the switches at version 12

  private val v9 = Versions.eraLow(ShelleyBasedEra.SConway)
  private val v12 = Versions.eraLow(ShelleyBasedEra.SDijkstra)

  private def bytes(body: Writer => Unit): Bytes = StateGen.item(body)

  private def readBytes[A](c: Codec[A], b: Bytes): A =
    c.decodeBytes(b, HaskellState.limits).fold(e => fail(e.render), identity)

  private def ratio(w: Writer, num: BigInt, den: BigInt): Unit =
    w.tag(30); w.arrayHead(2); w.int(num); w.int(den)

  /** `[-1, -1]`: 1 read as two `Integer` s before version 12 (`Plain.decodeRationalWithTag`,
    * `Binary/Plain.hs:159-167`), refused as two `Word64` s from it (`decodeIntegralRational`,
    * `BaseTypes.hs:407-428`, `Decoding/DecCBOR.hs:271-280`; cardano-ledger 9df9f98c).
    */
  private val minusOne = (w: Writer) => ratio(w, -1, -1)
  private val negative = Kind.TypeMismatch("unsigned integer", "negative integer")

  private val hash28 = Bytes.wrap(Array.fill[Byte](28)(1))
  private val hash32 = Bytes.wrap(Array.fill[Byte](32)(2))

  /** A `StakePoolParams` (`State/StakePool.hs:716-771`, cardano-ledger 9df9f98c): nine items, or
    * ten with `third` third.
    */
  private def poolParams(third: Option[Writer => Unit], margin: Writer => Unit): Bytes = bytes {
    w =>
      w.arrayHead(if third.isDefined then 10 else 9)
      w.bytes(hash28)
      w.bytes(hash32)
      third.foreach(_(w))
      w.uint(BigInt(1000))
      w.uint(BigInt(340000000))
      margin(w)
      w.bytes(Bytes.wrap(Array(0xe0.toByte) ++ Array.fill[Byte](28)(3)))
      w.tag(258); w.arrayHead(0)
      w.arrayHead(0)
      w.nul()
  }

  private val tenth = (w: Writer) => ratio(w, 1, 10)

  test(
    "a pool's parameters from version 12: nine items, or ten whose third is null; a key refused"
  ) {
    val leios = LeiosStateCodecs.poolParams(using v12)
    val nine = poolParams(None, tenth)
    val params = readBytes(leios, nine)
    assertEquals(params.margin, Rational(1, 10))
    // `decodeBlsKeyBackwardsCompatible` (`:762-771`): `null` third is no key, ten items
    assertEquals(readBytes(leios, poolParams(Some(_.nul()), tenth)), params)
    // with no key the reference writes nine items (`withStakePoolParamsFlatEncoding`, `:716-736`)
    assertEquals(leios.write(params), Right(nine))
    // a list third is a key: not read by this build
    // a valid key (`BlsKey`, `State/StakePool.hs:532-542`, 9df9f98c), which the reference reads
    val key =
      (w: Writer) => w.raw(LeiosStateCodecs.blsKey(using v12).encodeUnchecked(LeiosGen.keys.head))
    val keyed = failure(leios, poolParams(Some(key), tenth))
    assertEquals(keyed.kind, Kind.Domain(DijkstraNotYet.PoolParamsBlsKey))
    assertEquals(keyed.path.headOption, Some("blsKey"))
    // anything else third is refused, as the reference refuses ten items where it reads nine
    assertEquals(
      failure(leios, poolParams(Some(_.uint(BigInt(7))), tenth)).kind,
      Kind.TypeMismatch("null or a BLS key", "unsigned integer")
    )
    // before version 12, and in the 11.1 layout, there is no key: ten items are refused
    val ten = poolParams(Some(_.nul()), tenth)
    assertEquals(
      failure(LeiosStateCodecs.poolParams(using v9), ten).kind,
      Kind.BadArity(Arity.Exactly(9), 10)
    )
    assertEquals(
      failure(HaskellStateCodecs.poolParams(using v12), ten).kind,
      Kind.BadArity(Arity.Exactly(9), 10)
    )
  }

  test("a pool's margin: [-1, -1] is 1 before version 12 and refused from it") {
    val b = poolParams(None, minusOne)
    assertEquals(readBytes(LeiosStateCodecs.poolParams(using v9), b).margin, Rational.one)
    val e = failure(LeiosStateCodecs.poolParams(using v12), b)
    assertEquals(e.kind, negative)
    assertEquals(e.path.headOption, Some("margin"))
    // a pool state of the fixture, its margin made [-1, -1]
    val state = read(LeiosStateCodecs.newEpochState[Dijkstra], nes)
    val pool = LedgerState
      .pState[Dijkstra]
      .get(state.es.ls)
      .stakePools
      .values
      .headOption
      .getOrElse(fail("no pool"))
    val written = LeiosStateCodecs.stakePoolState(using v12).encodeUnchecked(pool)
    val withMargin = bytes { w =>
      val xs =
        items(Cbor.decode(written, HaskellState.limits).fold(e => fail(e.toString), identity))
      w.arrayHead(xs.size)
      xs.zipWithIndex.foreach((x, i) => if i == 4 then minusOne(w) else w.raw(x.original))
    }
    assertEquals(
      readBytes(LeiosStateCodecs.stakePoolState(using v9), withMargin).margin,
      Rational.one
    )
    val refused = failure(LeiosStateCodecs.stakePoolState(using v12), withMargin)
    assertEquals(refused.kind, negative)
    assertEquals(refused.path.headOption, Some("margin"))
  }

  test("a committee's threshold and an update of it: [-1, -1] is 1 before version 12") {
    val committee = bytes { w =>
      w.arrayHead(2); w.mapHead(0); minusOne(w)
    }
    assertEquals(
      readBytes(LeiosStateCodecs.committee(using v9), committee).threshold,
      Rational.one
    )
    assertEquals(failure(LeiosStateCodecs.committee(using v12), committee).kind, negative)
    val update = bytes { w =>
      w.arrayHead(5); w.uint(BigInt(4)); w.nul(); w.tag(258); w.arrayHead(0); w.mapHead(0)
      minusOne(w)
    }
    assertEquals(
      readBytes(LeiosStateCodecs.govAction(using v9), update),
      GovAction.UpdateCommittee(None, Set.empty, Map.empty, Rational.one)
    )
    val e = failure(LeiosStateCodecs.govAction(using v12), update)
    assertEquals(e.kind, negative)
    assertEquals(e.path.headOption, Some("quorum"))
  }

  /** A `ParameterChange` (`Procedures.hs:935-981`, cardano-ledger 9df9f98c) of one update entry. */
  private def parameterChange(key: Int, value: Writer => Unit): Bytes = bytes { w =>
    w.arrayHead(4); w.uint(BigInt(0)); w.nul()
    w.mapHead(1); w.uint(BigInt(key)); value(w)
    w.nul()
  }

  private def update(a: GovAction): Vector[ConwayParam] = a match
    case GovAction.ParameterChange(_, u, _) => u.params
    case other                              => fail(s"not a parameter change: $other")

  test("a proposal's parameter update from version 12: Conway's keys at 12, Dijkstra's refused") {
    val rho = parameterChange(10, minusOne)
    // before 12 the stored reader reads the `Integer` s: 1
    assertEquals(
      update(readBytes(LeiosStateCodecs.govAction(using v9), rho)),
      Vector(ConwayParam.Rho(Rational.one))
    )
    // from 12 the update's values are `decCBOR` at 12 (`Core/PParams.hs:264-299`, cardano-ledger 9df9f98c)
    assert(
      failure(LeiosStateCodecs.govAction(using v12), rho).kind match
        case Kind.Domain(_: LedgerDecodeError) => true
        case _                                 => false
    )
    val half = parameterChange(10, ratio(_, 1, 2))
    val read12 = readBytes(LeiosStateCodecs.govAction(using v12), half)
    assertEquals(update(read12), Vector(ConwayParam.Rho(Rational(1, 2))))
    assertEquals(LeiosStateCodecs.govAction(using v12).write(read12), Right(half))
    // `ExUnits` as `decodeRecordNamed` reads it from 12 (`ExUnits.hs:208-227`, 9df9f98c): indefinite too
    val units = parameterChange(
      20,
      w => {
        w.raw(Bytes.wrap(Array(0x9f.toByte))); w.uint(BigInt(5)); w.uint(BigInt(6)); w.break()
      }
    )
    assert(readBytes(LeiosStateCodecs.govAction(using v12), units) match
      case GovAction.ParameterChange(_, _, _) => true
      case _                                  => false)
    // key 34, Dijkstra's `maxRefScriptSizePerBlock` (`Dijkstra/PParams.hs:798-810`, 9df9f98c): not yet
    val dijkstraKey = parameterChange(34, _.uint(BigInt(1)))
    val e = failure(LeiosStateCodecs.govAction(using v12), dijkstraKey)
    assertEquals(e.kind, Kind.Domain(DijkstraNotYet.ParameterUpdate))
    assertEquals(e.path.headOption, Some("update"))
    assertEquals(
      failure(LeiosStateCodecs.govAction(using v12), parameterChange(56, _.uint(BigInt(1)))).kind,
      Kind.Domain(DijkstraNotYet.ParameterUpdate)
    )
    // past Dijkstra's keys, and before 12, an unknown key (`Decoder.hs:1243`, 9df9f98c)
    assertEquals(
      failure(LeiosStateCodecs.govAction(using v12), parameterChange(57, _.uint(BigInt(1)))).kind,
      Kind.Domain(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, BigInt(57)))
    )
    assertEquals(
      failure(LeiosStateCodecs.govAction(using v9), dijkstraKey).kind,
      Kind.Domain(LedgerDecodeError.UnknownKey(DecodeField.ParameterUpdate, BigInt(34)))
    )
  }

  test("from version 12 a record and a byte string may be indefinite, and are written definite") {
    val indefinite = Bytes.wrap(Array(0x9f.toByte))
    // `Anchor` (`BaseTypes.hs:1003-1011`, cardano-ledger 9df9f98c), `decodeRecordNamed` from 12, and
    // its url an indefinite text string (`decodeString`, `Decoding/Decoder.hs:1564-1569`, 9df9f98c)
    val anchor = bytes { w =>
      w.raw(indefinite)
      w.raw(Bytes.wrap(Array(0x7f.toByte))); w.text("https://"); w.text("a"); w.break()
      w.bytes(hash32); w.break()
    }
    val a = readBytes(LeiosStateCodecs.anchor(using v12), anchor)
    assertEquals(
      LeiosStateCodecs.anchor(using v12).write(a),
      Right(bytes(w => { w.arrayHead(2); w.text("https://a"); w.bytes(hash32) }))
    )
    // `Committee` (`Procedures.hs:636-646`, cardano-ledger 9df9f98c)
    val committee = bytes { w =>
      w.raw(indefinite); w.mapHead(0); tenth(w); w.break()
    }
    assertEquals(
      readBytes(LeiosStateCodecs.committee(using v12), committee).threshold,
      Rational(1, 10)
    )
    // a key hash as an indefinite byte string (`Decoding/Decoder.hs:358-364`, 9df9f98c)
    val chunked = bytes { w =>
      w.raw(Bytes.wrap(Array(0x5f.toByte)))
      w.bytes(Bytes.wrap(Array.fill[Byte](14)(1)))
      w.bytes(Bytes.wrap(Array.fill[Byte](14)(1)))
      w.break()
    }
    val credential = bytes { w =>
      w.arrayHead(2); w.uint(BigInt(0)); w.raw(chunked)
    }
    val c = readBytes(LeiosStateCodecs.credential[Staking], credential)
    assertEquals(
      LeiosStateCodecs.credential[Staking].write(c),
      Right(bytes(w => { w.arrayHead(2); w.uint(BigInt(0)); w.bytes(hash28) }))
    )
    // a pool's relay whose DNS name is an indefinite text string: written definite, as the
    // reference writes a `DnsName` (`BaseTypes.hs:686-697`, cardano-ledger 9df9f98c)
    def withRelay(name: Writer => Unit): Bytes = bytes { w =>
      val xs = items(
        Cbor
          .decode(poolParams(None, tenth), HaskellState.limits)
          .fold(e => fail(e.toString), identity)
      )
      w.arrayHead(9)
      xs.zipWithIndex.foreach((x, i) =>
        if i == 7 then { w.arrayHead(1); w.arrayHead(3); w.uint(BigInt(1)); w.nul(); name(w) }
        else w.raw(x.original)
      )
    }
    val relay = withRelay(w => {
      w.raw(Bytes.wrap(Array(0x7f.toByte))); w.text("relay."); w.text("example"); w.break()
    })
    val pool = readBytes(LeiosStateCodecs.poolParams(using v12), relay)
    assertEquals(
      LeiosStateCodecs.poolParams(using v12).write(pool),
      Right(withRelay(_.text("relay.example")))
    )
  }
