// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.syntax.all.*
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.consensus.state.{StateGen as _, *}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Writer}
import xyz.kd5ujc.scarab.protocol.{DijkstraNotYet, Era, StateLayout}

import DecodeError.{Arity, Kind}

/** The `state` layout of the Leios build (#826 step b): the laws of each record it writes
  * differently from 11.1 on generated values, hand-derived bytes of its envelopes, the refusal of
  * each layout's values by the other's codec, and the `state` files captured from a Leios devnet
  * (`fixtures/leios/state`): the last Conway state read and written byte for byte and refused by
  * the 11.1 layout, and the parts of the first Dijkstra state that are not Dijkstra's own.
  */
class LeiosLayoutSuite extends ScalaCheckSuite:
  private val conway = fixture("conway-195")
  private val dijkstra = fixture("dijkstra-203")

  private def fixture(name: String): Bytes =
    Bytes.wrap(Files.readAllBytes(Path.of(s"fixtures/leios/state/$name/state")))

  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e.toString), identity)

  private def bytesOf(body: Writer => Unit): Bytes = StateGen.item(body)

  private def read[A](codec: Codec[A], bytes: Bytes): Either[DecodeError, A] =
    codec.decodeBytes(bytes, HaskellState.limits)

  private def readOk[A](codec: Codec[A], bytes: Bytes): A =
    read(codec, bytes).fold(e => fail(e.render), identity)

  private def failure[A](codec: Codec[A], bytes: Bytes): DecodeError =
    read(codec, bytes).fold(identity, a => fail(s"decoded $a"))

  /** The items of an array node. */
  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def tree(bytes: Bytes): Node =
    Cbor.decode(bytes, HaskellState.limits).fold(e => fail(e.toString), identity)

  /** The two laws of a codec on its generator, and that what is generated is writable. */
  private def laws[A](name: String, gen: Gen[A], codec: Codec[A]): Unit =
    property(s"$name (Leios): decode after encode is the identity, and encode after decode too") {
      forAll(gen) { a =>
        assertEquals(codec.check(a), None, "a generated value is writable")
        val bytes = codec.encodeUnchecked(a)
        val back = readOk(codec, bytes)
        assertEquals(back, a)
        assertEquals(codec.encodeUnchecked(back), bytes)
      }
    }

  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)

  private val bound = Envelope.bound(StateLayout.Leios)

  // ---- the laws of each record of the Leios layout

  laws("BlsKeyState", LeiosGen.blsKeyState, LeiosStateCodecs.blsKeyState)
  laws("LeiosCommittee", LeiosGen.committee, LeiosStateCodecs.leiosCommittee)
  laws("StakePoolState", LeiosGen.stakePoolState, LeiosStateCodecs.stakePoolState)
  laws("StakePoolSnapShot", LeiosGen.stakePoolSnapShot, LeiosStateCodecs.stakePoolSnapShot)
  laws("SnapShots", LeiosGen.snapShots, LeiosStateCodecs.snapShots)
  laws("Bound", LeiosGen.leiosBound, bound)
  laws("PerasState", LeiosGen.perasState, LeiosStateCodecs.perasState)

  private def era[E <: EraTag](name: String)(using ShelleyBasedEra[E]): Unit =
    laws(
      s"NewEpochState[$name]",
      LeiosGen.newEpochState[E],
      LeiosStateCodecs.newEpochState[E]
    )

  era[Shelley]("Shelley")
  era[Allegra]("Allegra")
  era[Mary]("Mary")
  era[Alonzo]("Alonzo")
  era[Babbage]("Babbage")
  era[Conway]("Conway")

  property("a state file (Leios) round-trips in every era before Dijkstra, and 11.1 refuses it") {
    forAll(LeiosGen.file) { file =>
      val bytes =
        HaskellLedgerFile
          .encode(file, StateLayout.Leios)
          .fold(e => fail(e.violation.render), identity)
      val back =
        HaskellLedgerFile.decode(bytes, StateLayout.Leios).fold(e => fail(e.render), identity)
      assert(back === file)
      assertEquals(HaskellLedgerFile.encode(back, StateLayout.Leios), Right(bytes))
      // a value of the Leios layout is not written in the 11.1 layout: there is no conversion
      assert(HaskellLedgerFile.encode(file, StateLayout.Node11_1).isLeft)
    }
  }

  property("a value of the 11.1 layout is not written in the Leios layout, nor the converse") {
    forAll(StateGen.newEpochState[Conway], LeiosGen.newEpochState[Conway]) { (node11, leios) =>
      assert(LeiosStateCodecs.newEpochState[Conway].write(node11).isLeft)
      assert(HaskellStateCodecs.newEpochState[Conway].write(leios).isLeft)
    }
  }

  test("each layout part of another layout is refused, naming where it is") {
    val pool = StateGen.stakePoolState.sample.getOrElse(fail("no sample"))
    assertEquals(
      LeiosStateCodecs.stakePoolState.check(pool).map(_.path),
      Some(List("blsKey"))
    )
    val leiosPool = pool.copy(blsKey = PoolBlsKey.Leios(None))
    assertEquals(
      HaskellStateCodecs.stakePoolState.check(leiosPool).map(_.path),
      Some(List("blsKey"))
    )
    val b = Bound(3, 5, 1, Bound.Layout.Node11_1(None))
    assert(bound.check(b).isDefined)
    assert(Envelope.bound(StateLayout.Node11_1).check(LeiosGen.bound(b)).isDefined)
  }

  // ---- hand-derived bytes

  test("Bound (Leios): three items are round zero, four are always written (Summary.hs:573-601)") {
    // ouroboros-consensus 2416493b writes `encodeListLen 4` and reads three or four items
    val three = hex("83030501")
    val zero = readOk(bound, three)
    assertEquals(zero.layout, Bound.Layout.Leios(PerasRoundNo(Word64.zero)))
    assertEquals(bound.encodeUnchecked(zero), hex("8403050100"))
    val four = hex("8403050107")
    assertEquals(
      readOk(bound, four).layout,
      Bound.Layout.Leios(PerasRoundNo(Word64.fromUnsigned(7L)))
    )
    assertEquals(bound.encodeUnchecked(readOk(bound, four)), four)
    Vector("820305", "850305010700").foreach { refused =>
      assert(failure(bound, hex(refused)).kind.isInstanceOf[Kind.BadArity], refused)
    }
  }

  test("PerasState: the constant of this build, an error state, and a refused epoch context") {
    // `[[1, NoPerasEnabled, NoPerasEnabled], SNothing]` (Ledger/Peras.hs:76-101,
    // Peras/Context.hs:288-301): every state of the captures ends in it
    val constant = hex("828301808080")
    assertEquals(readOk(LeiosStateCodecs.perasState, constant), PerasState.default)
    assertEquals(LeiosStateCodecs.perasState.encodeUnchecked(PerasState.default), constant)
    // `[[0, reason], [round]]`: the resolver's error state, with the bytes of its reason
    val failed = hex("82 82 00 43 616263 81 05")
    val state = readOk(LeiosStateCodecs.perasState, failed)
    assertEquals(
      state,
      PerasState(
        PerasResolver.Failed(hex("616263")),
        Some(PerasRoundNo(Word64.fromUnsigned(5L)))
      )
    )
    assertEquals(LeiosStateCodecs.perasState.encodeUnchecked(state), failed)
    // a tag and length the resolver does not have
    assert(
      failure(LeiosStateCodecs.perasState, hex("82 82 01 80 80")).kind
        .isInstanceOf[Kind.LiteralMismatch]
    )
    // an epoch context `[start, end, [committee, params]]`, the committee `[era, committee]`:
    // Conway's committee is `Void`, refused as the reference refuses it; Dijkstra's is the
    // Dijkstra part of the state, not read by this build (#54)
    def withCommittee(era: Int) =
      hex(s"82 83 01 81 83 00 01 82 82 0$era f6 f6 80 80")
    assertEquals(
      failure(LeiosStateCodecs.perasState, withCommittee(6)).kind,
      Kind.Unsupported("a Peras voting committee of Conway (Void)")
    )
    assertEquals(
      failure(LeiosStateCodecs.perasState, withCommittee(7)).kind,
      Kind.Domain(DijkstraNotYet.LedgerState)
    )
  }

  test("a voting key is a valid point, and definite below version 12 (decodeFixedSized)") {
    val key = LeiosGen.keys.head
    val good = LeiosStateCodecs.blsKey.encodeUnchecked(key)
    assertEquals(readOk(LeiosStateCodecs.blsKey, good), key)
    // not a compressed point of the subgroup: refused as `rawDecodeFixedSized` refuses it
    val zero = bytesOf { w =>
      w.arrayHead(2); w.bytes(Bytes.wrap(new Array[Byte](96))); w.bytes(key.proofOfPossession)
    }
    failure(LeiosStateCodecs.blsKey, zero).kind match
      case Kind.Domain(_: xyz.kd5ujc.scarab.ledger.LedgerDecodeError.LeiosCertificate) => ()
      case other => fail(s"not refused as a BLS point: $other")
    // a chunked byte string is read from version 12 on only (`decodeBytes`, Decoder.hs:355-363)
    val chunked = bytesOf { w =>
      w.arrayHead(2)
      w.raw(Bytes.wrap(Array(0x5f.toByte)))
      w.bytes(key.key.take(40)); w.bytes(key.key.drop(40))
      w.raw(Bytes.wrap(Array(0xff.toByte)))
      w.bytes(key.proofOfPossession)
    }
    assert(failure(LeiosStateCodecs.blsKey, chunked).kind.isInstanceOf[Kind.TypeMismatch], "v9")
    val v12 = Versions.eraLow(ShelleyBasedEra.SDijkstra)
    assertEquals(read(LeiosStateCodecs.blsKey(using v12), chunked), Right(key))
  }

  private def indefiniteAt(n: Node, path: List[Int]): Bytes = bytesOf { w =>
    val xs = items(n)
    path match
      case Nil =>
        w.indefiniteArray()
        xs.foreach(x => w.raw(x.original))
        w.break()
      case index :: rest =>
        w.arrayHead(xs.size)
        xs.zipWithIndex.foreach { (x, i) =>
          w.raw(if i == index then indefiniteAt(x, rest) else x.original)
        }
  }

  // Consensus uses decodeListLen/enforceSize; ledger decodeRecordNamedT permits indefinite arrays.
  Vector(
    "version" -> List.empty[Int],
    "extended state" -> List(1),
    "ledger telescope" -> List(1, 0),
    "past" -> List(1, 0, 0),
    "bound" -> List(1, 0, 0, 0),
    "current ledger" -> List(1, 0, 6),
    "ledger version" -> List(1, 0, 6, 1),
    "Shelley ledger state" -> List(1, 0, 6, 1, 1),
    "ledger tip maybe" -> List(1, 0, 6, 1, 1, 0),
    "Shelley tip" -> List(1, 0, 6, 1, 1, 0, 0),
    "header state" -> List(1, 1),
    "header tip maybe" -> List(1, 1, 0),
    "header tip era" -> List(1, 1, 0, 0),
    "header tip" -> List(1, 1, 0, 0, 1),
    "header telescope" -> List(1, 1, 1),
    "current header" -> List(1, 1, 1, 6),
    "Praos version" -> List(1, 1, 1, 6, 1),
    "Praos state" -> List(1, 1, 1, 6, 1, 1),
    "Praos last slot" -> List(1, 1, 1, 6, 1, 1, 0),
    "Peras state" -> List(1, 2),
    "Peras resolver" -> List(1, 2, 0),
    "current Peras context" -> List(1, 2, 0, 1),
    "previous Peras context" -> List(1, 2, 0, 2),
    "Peras round" -> List(1, 2, 1)
  ).foreach { (label, path) =>
    test(s"Leios consensus $label requires a definite array") {
      val bytes = indefiniteAt(tree(conway), path)
      assert(
        failure(LeiosStateCodecs.haskellLedgerFile, bytes).kind.isInstanceOf[Kind.TypeMismatch],
        label
      )
    }
  }

  test("Leios ledger records retain the reference's indefinite-array acceptance") {
    val bytes = indefiniteAt(tree(conway), List(1, 0, 6, 1, 1, 1))
    assert(read(LeiosStateCodecs.haskellLedgerFile, bytes).isRight, "NewEpochState")
  }

  // ---- the captured state files

  test("slot 195, the last Conway state, is read in the Leios layout and written byte for byte") {
    val file =
      HaskellLedgerFile.decode(conway, StateLayout.Leios).fold(e => fail(e.render), identity)
    assertEquals(file.era, Era.Conway)
    assertEquals(file.layout, ExtLedgerState.Layout.Leios(PerasState.default))
    file.ext.map(_.ledgerState.state) match
      case Some(EraLedgerState.InShelleyEra(_, s)) =>
        assert(s.layout.stateLayout == StateLayout.Leios)
        assertEquals(s.nes.layout, NewEpochState.Layout.Leios)
        // the mark snapshot of a Conway state: epoch, committee size and key age zero, and the
        // set snapshot's committee empty (`State/SnapShots.hs:386-432`)
        assertEquals(
          s.nes.es.snapshots.layout,
          SnapShots.Layout.Leios(EpochNo.fromUnsigned(0L), 0, 0, LeiosCommittee.empty)
        )
      case other => fail(s"not a Shelley-based state: $other")
    assertEquals(HaskellLedgerFile.encode(file, StateLayout.Leios), Right(conway))
  }

  test("slot 195 is refused by the 11.1 layout: its extended state has three parts") {
    // `decodeListLenOf 2` of the 11.1 `ExtLedgerState` (ouroboros-consensus 82ecba32
    // `Ledger/Extended.hs:268-280`)
    val e = HaskellLedgerFile.decode(conway).fold(identity, f => fail(s"decoded $f"))
    assertEquals(e.kind, Kind.BadArity(Arity.Exactly(2), 3))
  }

  test("an extended state of two parts is read with the default Peras state, written with three") {
    // 2416493b `Ledger/Extended.hs:477-505`: two parts read `defaultPerasState`; the encoder
    // always writes three (`:427-442`), so the two-part slot 195 is written as the captured one
    val parts = items(items(tree(conway))(1))
    val two = bytesOf { w =>
      w.arrayHead(2); w.uint(1L); w.arrayHead(2); w.raw(parts(0).original); w.raw(parts(1).original)
    }
    val file = HaskellLedgerFile.decode(two, StateLayout.Leios).fold(e => fail(e.render), identity)
    assertEquals(file.layout, ExtLedgerState.Layout.Leios(PerasState.default))
    assertEquals(HaskellLedgerFile.encode(file, StateLayout.Leios), Right(conway))
    val four = bytesOf { w =>
      w.arrayHead(2); w.uint(1L); w.arrayHead(4); parts.foreach(p => w.raw(p.original)); w.nul()
    }
    assertEquals(
      HaskellLedgerFile.decode(four, StateLayout.Leios).map(_ => ()).leftMap(_.kind),
      Left(Kind.BadArity(Arity.Range(2, Some(3)), 4))
    )
  }

  test("slot 203, the first Dijkstra state: refused at its telescope, its other parts read") {
    val e = HaskellLedgerFile.decode(dijkstra, StateLayout.Leios).fold(identity, f => fail(s"$f"))
    assertEquals(e.kind, Kind.Domain(DijkstraNotYet.LedgerState))
    assertEquals(e.path, List("ledgerState"))
    val parts = items(items(tree(dijkstra))(1))
    assertEquals(parts.size, 3)
    // the Peras state: this build's constant
    assertEquals(readOk(LeiosStateCodecs.perasState, parts(2).original), PerasState.default)
    assertEquals(LeiosStateCodecs.perasState.encodeUnchecked(PerasState.default), parts(2).original)
    // the bounds of the seven past eras of the ledger telescope, and of the header state's
    // chain-dependent telescope: four items each, written as read
    val ledgerPasts = items(parts(0)).init
    val chainDepPasts = items(items(parts(1))(1)).init
    assertEquals((ledgerPasts.size, chainDepPasts.size), (7, 7))
    (ledgerPasts ++ chainDepPasts).flatMap(items).foreach { b =>
      assertEquals(items(b).size, 4)
      assertEquals(bound.encodeUnchecked(readOk(bound, b.original)), b.original)
    }
    // Dijkstra started at slot 200, epoch 1, the next Peras round still zero
    assertEquals(
      readOk(bound, items(ledgerPasts.last)(1).original),
      Bound(
        BigInt("20000000000000"),
        200,
        1,
        Bound.Layout.Leios(PerasRoundNo(Word64.zero))
      )
    )
  }
