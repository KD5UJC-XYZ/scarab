// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import java.nio.file.{Files, Path}
import java.util.concurrent.FutureTask
import munit.ScalaCheckSuite
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Block,
  BootstrapWitness,
  ByteStrings,
  Certificate,
  CostModelDecode,
  Credential,
  DRep,
  DecoderVersion,
  GovAction,
  GovActionId,
  Ipv4,
  Ipv6,
  LedgerDecodeError,
  Metadatum,
  NativeScript,
  PoolParams,
  PoolRelay,
  PoolRelays,
  Proposal,
  Redeemer,
  RelayPort,
  RewardAccount,
  Script,
  ScriptKind,
  TxBody,
  Vote,
  Voter,
  VotingProcedure,
  VotingProcedures,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Word64}
import xyz.kd5ujc.scarab.models.runtime.{Codec, Writer}
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.protocol.Era

import ConwayCodecs.ScriptPayload

/** The reference's `EncCBOR` of the Conway values of [[ConwayCodecs]]. Each codec is checked three
  * ways: what it writes is read back by the ledger's own decoder as the value written (a property
  * over generated values); its framing, map order and tags are the bytes worked out by hand from
  * the encoders at cardano-ledger f649f975 (sets and sequences above 23 items, a cost model of 166
  * parameters, a list of 24 metadata, a script voter before a key voter); and on chain data the
  * repository holds (`fixtures/eras`, a devnet of the reference node 11.1.3, and
  * `fixtures/preview`, blocks of the Preview network), decoding and writing again gives the bytes
  * of the chain. A few cases are written by hand after samples of the cardano-cbor-dataset
  * (r2rationality, `ed6c49a4`), which carries no licence, so none of its bytes is copied: each says
  * so.
  */
class ConwayCodecsSuite extends ScalaCheckSuite:
  private given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
  private val version = DecoderVersion.of(Era.Conway)
  private given ByteStrings = ByteStrings.of(version)

  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e.toString), identity)

  private def node(b: Bytes): Node =
    Cbor.decode(b, Cbor.Limits.Unbounded).fold(e => fail(e.toString), identity)

  /** A Conway body of no inputs, outputs or fee with `item` under `key`, read by the body decoder.
    */
  private def body(key: String, item: Bytes): Either[LedgerDecodeError, TxBody] =
    TxBody.decode(hex(s"a4 00 80 01 80 02 00 $key") ++ item, Era.Conway)

  private def written[A](codec: Codec[A])(a: A): Bytes =
    codec.write(a).fold(v => fail(s"refused: $v"), identity)

  private def same(expected: String)(actual: Bytes): Unit =
    assertEquals(actual.hex, expected.filterNot(_.isWhitespace))

  // ---------------------------------------------------------------------- generators

  private def bytesOf(n: Int): Gen[Bytes] =
    Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(bs => Bytes.fromArray(bs.toArray))
  private val anyBytes: Gen[Bytes] = Gen.choose(0, 70).flatMap(bytesOf)
  private val hash28 = bytesOf(28)
  private val hash32 = bytesOf(32)
  private val word64: Gen[BigInt] = Gen.choose(BigInt(0), (BigInt(1) << 64) - 1)
  private val credential: Gen[Credential] =
    Gen.oneOf(hash28.map(Credential.Key(_)), hash28.map(Credential.Script(_)))
  private val drep: Gen[DRep] = Gen.oneOf(
    credential.map(DRep.Registered(_)),
    Gen.const(DRep.AlwaysAbstain),
    Gen.const(DRep.AlwaysNoConfidence)
  )
  private val text: Gen[String] = Gen.choose(0, 64).flatMap(Gen.stringOfN(_, Gen.alphaNumChar))
  private val anchor: Gen[Anchor] = Gen.zip(text, hash32).map(Anchor(_, _))
  private val account: Gen[RewardAccount] =
    Gen.zip(Gen.oneOf(0, 1), credential).map(RewardAccount(_, _))
  private val unit: Gen[Rational] =
    Gen.choose(1L, 1000000L).flatMap(d => Gen.choose(0L, d).map(n => Rational(n, d)))

  private val relay: Gen[PoolRelay] =
    val port = Gen.option(Gen.choose(0, 65535)).map(_.flatMap(RelayPort.fromInt))
    val ipv4 = Arbitrary.arbitrary[Int].map(Ipv4(_))
    val ipv6 = Gen.zip(Arbitrary.arbitrary[Long], Arbitrary.arbitrary[Long]).map(Ipv6(_, _))
    Gen.oneOf(
      Gen.zip(port, Gen.option(ipv4), Gen.option(ipv6)).map(PoolRelay.SingleHostAddr(_, _, _)),
      Gen.zip(port, text.suchThat(_.nonEmpty)).map(PoolRelay.SingleHostName(_, _)),
      text.suchThat(_.nonEmpty).map(PoolRelay.MultiHostName(_))
    )

  private val metadataItem = Record.of[(String, Bytes)]((Codec.text, Codec.bytes))

  /** A registration as the body decoder gives it: the relays and the metadata as their bytes. */
  private def pool(relays: Gen[Vector[PoolRelay]]): Gen[PoolParams] =
    for
      operator <- hash28
      vrf <- hash32
      pledge <- word64
      cost <- word64
      margin <- unit
      reward <- account
      owners <- Gen.listOf(hash28).map(_.take(4).toSet)
      rs <- relays
      metadata <- Gen.option(Gen.zip(text, hash32).map(metadataItem.encodeUnchecked))
    yield PoolParams(
      operator,
      vrf,
      pledge,
      cost,
      margin,
      reward,
      owners,
      if rs.isEmpty then Bytes.empty else PoolRelays.encode(rs),
      metadata
    )

  private val certificate: Gen[Certificate] =
    import Certificate.*
    Gen.oneOf(
      credential.map(Register(_, None)),
      credential.map(Unregister(_, None)),
      Gen.zip(credential, hash28).map((c, p) => Delegate(c, Some(p), None, None)),
      pool(Gen.choose(0, 3).flatMap(Gen.listOfN(_, relay)).map(_.toVector)).map(RegisterPool(_)),
      Gen.zip(hash28, word64).map(RetirePool(_, _)),
      Gen.zip(credential, word64).map((c, d) => Register(c, Some(d))),
      Gen.zip(credential, word64).map((c, d) => Unregister(c, Some(d))),
      Gen.zip(credential, drep).map((c, d) => Delegate(c, None, Some(d), None)),
      Gen.zip(credential, hash28, drep).map((c, p, d) => Delegate(c, Some(p), Some(d), None)),
      Gen.zip(credential, hash28, word64).map((c, p, d) => Delegate(c, Some(p), None, Some(d))),
      Gen.zip(credential, drep, word64).map((c, v, d) => Delegate(c, None, Some(v), Some(d))),
      Gen
        .zip(credential, hash28, drep, word64)
        .map((c, p, v, d) => Delegate(c, Some(p), Some(v), Some(d))),
      Gen.zip(credential, credential).map(CommitteeAuthorize(_, _)),
      Gen.zip(credential, Gen.option(anchor)).map(CommitteeResign(_, _)),
      Gen.zip(credential, word64, Gen.option(anchor)).map(RegisterDRep(_, _, _)),
      Gen.zip(credential, word64).map(UnregisterDRep(_, _)),
      Gen.zip(credential, Gen.option(anchor)).map(UpdateDRep(_, _))
    )

  private val voter: Gen[Voter] = Gen.oneOf(
    credential.map(Voter.CommitteeHot(_)),
    credential.map(Voter.DRepVoter(_)),
    hash28.map(Voter.Pool(_))
  )
  private val actionId: Gen[GovActionId] =
    Gen.zip(hash32, Gen.choose(0, 65535)).map((t, i) => GovActionId(t, BigInt(i)))
  private val procedure: Gen[VotingProcedure] =
    Gen.zip(Gen.oneOf(Vote.values.toSeq), Gen.option(anchor)).map(VotingProcedure(_, _))
  private val votes: Gen[Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]] =
    Gen
      .listOf(
        Gen.zip(
          voter,
          Gen.nonEmptyListOf(Gen.zip(actionId, procedure)).map(_.toVector.distinctBy(_._1))
        )
      )
      .map(_.toVector.distinctBy(_._1))

  private val action: Gen[GovAction] =
    val previous = Gen.option(actionId)
    Gen.oneOf(
      Gen.const(GovAction.Info),
      previous.map(GovAction.NoConfidence(_)),
      Gen
        .zip(previous, Gen.choose(0, 12), Gen.choose(0L, 4294967295L))
        .map((p, a, b) => GovAction.HardFork(p, a, b)),
      Gen
        .zip(previous, Gen.option(hash28), anchor)
        .map(GovAction.NewConstitution(_, _, _))
    )
  private val proposal: Gen[Proposal] =
    Gen.zip(word64, account, action, anchor).map(Proposal(_, _, _, _))

  /** A native script and its encoding, written here as the reference writes a time lock. */
  private def nativeScript(depth: Int): Gen[NativeScript] =
    val leaf = Gen.oneOf(
      hash28.map(NativeScript.Sig(_)),
      word64.map(NativeScript.InvalidBefore(_)),
      word64.map(NativeScript.InvalidHereafter(_))
    )
    if depth == 0 then leaf
    else
      val inner = Gen.choose(0, 3).flatMap(Gen.listOfN(_, nativeScript(depth - 1))).map(_.toVector)
      Gen.oneOf(
        leaf,
        inner.map(NativeScript.AllOf(_)),
        inner.map(NativeScript.AnyOf(_)),
        Gen.zip(Gen.choose(0, 3), inner).map((n, ss) => NativeScript.NOfK(n, ss))
      )

  private def nativeBytes(s: NativeScript): Bytes =
    val out = new Writer
    def go(s: NativeScript): Unit = s match
      case NativeScript.Sig(h)              => out.arrayHead(2); out.uint(0L); out.bytes(h)
      case NativeScript.AllOf(ss)           => out.arrayHead(2); out.uint(1L); list(ss)
      case NativeScript.AnyOf(ss)           => out.arrayHead(2); out.uint(2L); list(ss)
      case NativeScript.NOfK(n, ss)         => out.arrayHead(3); out.uint(3L); out.uint(n); list(ss)
      case NativeScript.InvalidBefore(slot) => out.arrayHead(2); out.uint(4L); out.uint(slot)
      case NativeScript.InvalidHereafter(slot) =>
        out.arrayHead(2); out.uint(5L); out.uint(slot)
    def list(ss: Vector[NativeScript]): Unit =
      out.arrayHead(ss.size)
      ss.foreach(go)
    go(s)
    out.result

  private val scriptPayload: Gen[ScriptPayload] = Gen.oneOf(
    nativeScript(3).map(s => ScriptPayload(ScriptKind.Native, nativeBytes(s))),
    Gen
      .zip(Gen.oneOf(ScriptKind.PlutusV1, ScriptKind.PlutusV2, ScriptKind.PlutusV3), anyBytes)
      .map(ScriptPayload(_, _))
  )

  /** Plutus data, each as some encoding of it: the redeemer keeps the bytes. */
  private val data: Gen[Bytes] = Gen.oneOf(
    Gen.const(hex("00")),
    Gen.const(hex("d87980")),
    Gen.const(hex("9f0102ff")),
    Gen.const(hex("a14100d87a80")),
    Gen.choose(0L, Long.MaxValue).map(n => Codec.int.encodeUnchecked(BigInt(n)))
  )
  private val redeemer: Gen[Redeemer] =
    for
      tag <- Gen.choose(0, 5)
      index <- Gen.choose(0L, 4294967295L)
      d <- data
      mem <- Gen.choose(0L, Long.MaxValue)
      steps <- Gen.choose(0L, Long.MaxValue)
    yield Redeemer(tag, index, d, ExUnits(mem, steps))

  private def metadatum(depth: Int): Gen[Metadatum] =
    val leaf = Gen.oneOf(
      Gen.choose(-(BigInt(1) << 64), (BigInt(1) << 64) - 1).map(Metadatum.Num(_)),
      Gen.choose(0, 64).flatMap(bytesOf).map(Metadatum.Bin(_)),
      text.map(Metadatum.Str(_))
    )
    if depth == 0 then leaf
    else
      val inner = metadatum(depth - 1)
      Gen.oneOf(
        leaf,
        Gen.choose(0, 4).flatMap(Gen.listOfN(_, inner)).map(xs => Metadatum.Items(xs.toVector)),
        Gen
          .choose(0, 3)
          .flatMap(Gen.listOfN(_, Gen.zip(inner, inner)))
          .map(ps => Metadatum.Entries(ps.toVector))
      )

  private val costModels: Gen[Map[Int, Vector[BigInt]]] =
    Gen
      .listOf(
        Gen.zip(
          Gen.choose(0, 255),
          Gen.listOf(Gen.choose(BigInt(Long.MinValue), BigInt(Long.MaxValue))).map(_.toVector)
        )
      )
      .map(_.toMap)

  // ---------------------------------------------------------------------- read back by the ledger

  property("a certificate is read back by Conway's certificate decoder as itself") {
    forAll(certificate) { c =>
      Certificate.decode(node(written(ConwayCodecs.certificate)(c)), Era.Conway) == Right(c)
    }
  }

  property("the certificates of a body are read back by the body decoder, in their order") {
    forAll(Gen.nonEmptyListOf(certificate).map(_.toVector.distinct)) { cs =>
      body("04", written(ConwayCodecs.certificates)(cs)).map(_.certificates) == Right(cs)
    }
  }

  property("a voter is read back as itself") {
    forAll(voter)(v => Voter.decode(node(written(ConwayCodecs.voter)(v))) == Right(v))
  }

  property("voting procedures are read back in the order of their voters and actions") {
    forAll(votes) { vs =>
      val sorted = vs
        .sortBy(_._1)(using Voter.ordering)
        .map((v, ps) => v -> ps.sortBy(_._1)(using NodeOrders.given_Order_GovActionId.toOrdering))
      VotingProcedures.decode(node(written(ConwayCodecs.votingProcedures)(vs)), version) ==
        Right(sorted) &&
        (vs.isEmpty || body("13", written(ConwayCodecs.votingProcedures)(vs)).map(_.votes) ==
          Right(sorted))
    }
  }

  property("the proposal procedures of a body are read back by the body decoder, in their order") {
    forAll(Gen.nonEmptyListOf(proposal).map(_.toVector.distinct)) { ps =>
      body("14", written(ConwayCodecs.proposalProcedures)(ps)).map(_.proposals) == Right(ps)
    }
  }

  property("a script is read back with its language, its bytes and the hash over them") {
    forAll(scriptPayload) { s =>
      val read = Script.decode(node(written(ConwayCodecs.script)(s)), ScriptKind.PlutusV3)
      val hash = Blake2b.hash224.hash(Bytes(s.kind.tag.toByte +: s.payload.value))
      val native =
        if s.kind.isPlutus then None
        else NativeScript.decode(node(s.payload), NativeScript.Form.Timelock).toOption
      read.map(r => (r.kind, r.hash, r.plutus, r.native)) ==
        Right((s.kind, hash, Option.when(s.kind.isPlutus)(s.payload), native))
    }
  }

  property("a redeemer is read back from a witness set's list as itself") {
    forAll(redeemer) { r =>
      Witnesses
        .decode(hex("a10581") ++ written(ConwayCodecs.redeemer)(r), Era.Conway)
        .map(_.redeemers) == Right(Vector(r))
    }
  }

  property(
    "a vkey witness and a bootstrap witness are read back from a witness set as themselves"
  ) {
    forAll(hash32, bytesOf(64), anyBytes, anyBytes) { (vkey, signature, chainCode, attributes) =>
      val boot = BootstrapWitness(vkey, signature, chainCode, attributes)
      Witnesses
        .decode(hex("a10081") ++ written(ConwayCodecs.vkeyWitness)((vkey, signature)), Era.Conway)
        .map(_.vkeys) == Right(Vector((vkey, signature))) &&
      Witnesses
        .decode(hex("a10281") ++ written(ConwayCodecs.bootstrapWitness)(boot), Era.Conway)
        .map(_.bootstrap) == Right(Vector(boot))
    }
  }

  property("a metadatum is read back as itself") {
    forAll(metadatum(4)) { m =>
      Metadatum.decode(node(written(ConwayCodecs.metadatum)(m)), version) == Right(m)
    }
  }

  property("cost models are read back by the Conway reader of a parameter update") {
    forAll(costModels) { cms =>
      CostModelDecode.decode(
        node(written(ConwayCodecs.costModels)(cms)),
        CostModelDecode.Format.Open
      ) == Right(cms)
    }
  }

  // ---------------------------------------------------------------------- framing, order, tags

  private val key = "11" * 28
  private val cred = s"8200581c$key"

  test("a certificate is its tag and fields in a definite array, as the reference writes each") {
    import Certificate.*
    val c = Credential.Key(hex(key))
    val pool = hex("22" * 28)
    val a = Anchor("u", hex("33" * 32))
    val cases = Vector(
      Register(c, None) -> s"8200 $cred",
      Unregister(c, None) -> s"8201 $cred",
      Delegate(c, Some(pool), None, None) -> s"8302 $cred 581c${"22" * 28}",
      RetirePool(pool, 300) -> s"8304 581c${"22" * 28} 19012c",
      Register(c, Some(2000000)) -> s"8307 $cred 1a001e8480",
      Unregister(c, Some(2000000)) -> s"8308 $cred 1a001e8480",
      Delegate(c, None, Some(DRep.AlwaysAbstain), None) -> s"8309 $cred 8102",
      Delegate(c, Some(pool), Some(DRep.AlwaysNoConfidence), None) ->
        s"840a $cred 581c${"22" * 28} 8103",
      Delegate(c, Some(pool), None, Some(5)) -> s"840b $cred 581c${"22" * 28} 05",
      Delegate(c, None, Some(DRep.Registered(c)), Some(5)) -> s"840c $cred $cred 05",
      Delegate(c, Some(pool), Some(DRep.AlwaysAbstain), Some(5)) ->
        s"850d $cred 581c${"22" * 28} 8102 05",
      CommitteeAuthorize(c, Credential.Script(hex(key))) -> s"830e $cred 8201581c$key",
      CommitteeResign(c, None) -> s"830f $cred f6",
      RegisterDRep(c, 7, Some(a)) -> s"8410 $cred 07 82 6175 5820${"33" * 32}",
      UnregisterDRep(c, 7) -> s"8311 $cred 07",
      UpdateDRep(c, None) -> s"8312 $cred f6"
    )
    cases.foreach((cert, bytes) => same(bytes)(written(ConwayCodecs.certificate)(cert)))
  }

  test("a certificate Conway has no constructor of is refused, never written") {
    val c = Credential.Key(hex(key))
    assert(ConwayCodecs.certificate.write(Certificate.Delegate(c, None, None, None)).isLeft)
    assert(
      ConwayCodecs.certificate
        .write(Certificate.GenesisDelegate(hex(key), hex(key), hex("00" * 32)))
        .isLeft
    )
  }

  test("a pool registration writes its owners as a tagged set and its relays as their values") {
    // the relays kept indefinite and with a wide port head are written as the reference writes
    // the values: a definite array and the shortest head
    val relays = hex("9f 83 01 19 0bb8 61 61 ff")
    val metadata = hex("82 7f 6161 ff 4100")
    val params = PoolParams(
      hex("01" * 28),
      hex("02" * 32),
      BigInt(1),
      BigInt(340000000),
      Rational(1, 4),
      RewardAccount(0, Credential.Key(hex("03" * 28))),
      Set(hex("05" * 28), hex("04" * 28)),
      relays,
      Some(metadata)
    )
    same(
      s"""8a 03 581c${"01" * 28} 5820${"02" * 32} 01 1a1443fd00 d81e 82 01 04
          581d e0${"03" * 28} d9 0102 82 581c${"04" * 28} 581c${"05" * 28}
          81 83 01 190bb8 6161 82 6161 4100"""
    )(written(ConwayCodecs.certificate)(Certificate.RegisterPool(params)))
  }

  property("a registration of 24 relays writes them indefinite and is read back as itself") {
    forAll(pool(Gen.listOfN(24, relay).map(_.toVector))) { p =>
      val bytes = written(ConwayCodecs.certificate)(Certificate.RegisterPool(p))
      val relays = node(bytes).value match
        case CborValue.Arr(xs) => xs.lift(8).map(_.original.hex.take(2))
        case _                 => None
      relays == Some("9f") &&
      Certificate.decode(node(bytes), Era.Conway) == Right(Certificate.RegisterPool(p))
    }
  }

  test("an ordered set is tagged 258 and definite up to 23 elements, indefinite above") {
    val c = Credential.Key(hex(key))
    def certs(n: Int) = Vector.tabulate(n)(i => Certificate.RetirePool(hex(key), i))
    assert(written(ConwayCodecs.certificates)(certs(23)).hex.startsWith("d9010297"))
    val above = written(ConwayCodecs.certificates)(certs(24)).hex
    assert(above.startsWith("d901029f") && above.endsWith("ff"), above.take(12))
    // the tag is written whatever the version: `OSet` does not ask it
    assert(
      ConwayCodecs
        .certificates(using ProtVer(Word64.fromUnsigned(7), Word64.zero))
        .encodeUnchecked(Vector(Certificate.Register(c, None)))
        .hex
        .startsWith("d9010281")
    )
  }

  test("voting procedures put a committee script before a committee key, as Ord Voter does") {
    val h = "44" * 28
    val id = GovActionId(hex("55" * 32), 1)
    val yes = VotingProcedure(Vote.Yes, None)
    val all = Vector(
      Voter.Pool(hex(h)) -> Vector(id -> yes),
      Voter.CommitteeHot(Credential.Key(hex(h))) -> Vector(id -> yes),
      Voter.CommitteeHot(Credential.Script(hex(h))) -> Vector(id -> yes)
    )
    val entry = s"a1 825820${"55" * 32}01 8201f6"
    same(s"a3 8201581c$h $entry 8200581c$h $entry 8204581c$h $entry")(
      written(ConwayCodecs.votingProcedures)(all)
    )
  }

  test("voting procedures of 24 voters are an indefinite map") {
    val id = GovActionId(hex("55" * 32), 0)
    val all = Vector.tabulate(24)(i =>
      Voter.Pool(Bytes(Vector.fill(28)(i.toByte))) -> Vector(id -> VotingProcedure(Vote.No, None))
    )
    val bytes = written(ConwayCodecs.votingProcedures)(all).hex
    assert(bytes.startsWith("bf") && bytes.endsWith("ff"), bytes.take(8))
  }

  test("a voting procedure is [vote, anchor / null]") {
    same(s"82 02 82 6161 5820${"66" * 32}")(
      written(ConwayCodecs.votingProcedure)(
        VotingProcedure(Vote.Abstain, Some(Anchor("a", hex("66" * 32))))
      )
    )
  }

  test("a cost model of 166 parameters is an indefinite list, the languages in order") {
    val v1 = Vector.tabulate(166)(i => BigInt(i))
    val bytes = written(ConwayCodecs.costModels)(Map(1 -> Vector(BigInt(-1)), 0 -> v1)).hex
    assert(bytes.startsWith("a2 00 9f 00 01".filterNot(_.isWhitespace)), bytes.take(12))
    assert(bytes.endsWith("ff 01 81 20".filterNot(_.isWhitespace)), bytes.takeRight(12))
  }

  test("a metadatum list of 24 items and a map keep a definite head; a map keeps its pairs") {
    val items = Metadatum.Items(Vector.tabulate(24)(i => Metadatum.Num(i)))
    assert(written(ConwayCodecs.metadatum)(items).hex.startsWith("9818"))
    val repeated = Metadatum.Entries(
      Vector(
        Metadatum.Num(2) -> Metadatum.Str("b"),
        Metadatum.Num(1) -> Metadatum.Bin(hex("ff")),
        Metadatum.Num(2) -> Metadatum.Num(-1)
      )
    )
    same("a3 02 6162 01 41ff 02 20")(written(ConwayCodecs.metadatum)(repeated))
    // an integer past 64 bits is not read by the reference, but written by `encodeInteger`
    same("c2 49 010000000000000000")(
      written(ConwayCodecs.metadatum)(Metadatum.Num(BigInt(1) << 64))
    )
  }

  test("a metadatum nested deeper than a small stack is read and written on one") {
    val depth = 100000
    val bytes = hex("81" * depth + "00")
    val task = new FutureTask(() =>
      Metadatum
        .decode(node(bytes), version)
        .map(m => written(ConwayCodecs.metadatum)(m) == bytes && m == m)
    )
    new Thread(null, task, "small", 256 * 1024).start()
    assertEquals(task.get(), Right(true))
  }

  test("a metadatum string past 64 bytes is refused from decoder version 3, as before") {
    val long = Metadatum.Str("x" * 65)
    val bytes = written(ConwayCodecs.metadatum)(long)
    assert(Metadatum.decode(node(bytes), version).isLeft)
    assertEquals(Metadatum.decode(node(bytes), DecoderVersion.of(Era.Shelley)), Right(long))
  }

  test("a native script is written as the bytes it was read with, a Plutus script as bytes") {
    // the time lock kept indefinite: the reference keeps its bytes (`MemoBytes`)
    same(s"82 00 9f 00 581c$key ff")(
      written(ConwayCodecs.script)(ScriptPayload(ScriptKind.Native, hex(s"9f00581c${key}ff")))
    )
    same("82 03 43 010203")(
      written(ConwayCodecs.script)(ScriptPayload(ScriptKind.PlutusV3, hex("010203")))
    )
  }

  test("a redeemer is [tag, index, data, [mem, steps]] with its data as kept") {
    same("84 05 1a00010000 9f01ff 82 1903e8 1b0000000100000000")(
      written(ConwayCodecs.redeemer)(
        Redeemer(5, 65536, hex("9f01ff"), ExUnits(1000, BigInt(1) << 32))
      )
    )
  }

  // ------------------------------------- written by hand after samples of the cardano-cbor-dataset

  test("by hand, after dataset samples: an indefinite delegation is written definite") {
    // the shape of a `certificate` sample: `[2, credential, pool]` in indefinite arrays
    val read =
      Certificate.decode(node(hex(s"9f02 9f01581c${key}ff 581c${"77" * 28} ff")), Era.Conway)
    same(s"83 02 8201581c$key 581c${"77" * 28}")(
      written(ConwayCodecs.certificate)(read.fold(e => fail(e.message), identity))
    )
  }

  test("by hand, after dataset samples: a vkey witness and a bootstrap witness") {
    val vkey = "88" * 32
    val sig = "99" * 64
    val read = Witnesses.decode(hex(s"a1 00 81 9f 5820$vkey 5840$sig ff"), Era.Conway)
    same(s"82 5820$vkey 5840$sig")(
      written(ConwayCodecs.vkeyWitness)(
        read.toOption.flatMap(_.vkeys.headOption).getOrElse(fail("no vkey"))
      )
    )
    val boot = s"84 5820$vkey 5840$sig 5820${"aa" * 32} 41a0"
    val readBoot = Witnesses.decode(hex(s"a1 02 81 $boot"), Era.Conway)
    same(boot)(
      written(ConwayCodecs.bootstrapWitness)(
        readBoot.toOption.flatMap(_.bootstrap.headOption).getOrElse(fail("no witness"))
      )
    )
  }

  test("by hand, after dataset samples: a voter and an indefinite metadatum") {
    val read = Voter.decode(node(hex(s"8202581c$key")))
    same(s"8202581c$key")(written(ConwayCodecs.voter)(read.fold(e => fail(e.message), identity)))
    val m = Metadatum.decode(node(hex("bf 01 5f 4101 4102 ff 02 9f 7f 6161 ff ff ff")), version)
    same("a2 01 42 0102 02 81 6161")(
      written(ConwayCodecs.metadatum)(m.fold(e => fail(e.message), identity))
    )
  }

  // ---------------------------------------------------------------------- chain data

  private def blocks(file: String): Vector[(Bytes, Block.Shelley)] =
    Files
      .readAllLines(Path.of(file))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(hex)
      .flatMap(raw =>
        Block.decodeShelley(raw).toOption.collect { case b: Block.Shelley => raw -> b }
      )

  test("blocks of the reference node and of Preview are written again as the bytes they came as") {
    val all = blocks("fixtures/eras/blocks.hex") ++ blocks("fixtures/preview/conway.hex") ++
      blocks("fixtures/preview/conway-governance.hex")
    val conway = all.filter(_._2.era == Era.Conway)
    assert(conway.size >= 50, conway.size)
    all.foreach { (raw, b) =>
      assertEquals(
        hex("82" + f"${b.era.blockTag}%02x") ++ written(ConwayCodecs.block)(b),
        raw,
        s"block ${b.header.raw.size}"
      )
    }
  }

  test("a block built by hand, without the segments it was read with, is refused") {
    val (_, b) = blocks("fixtures/preview/conway.hex").head
    assert(ConwayCodecs.block.write(b.copy(segments = Vector.empty)).isLeft)
  }

  test("the certificates, votes, proposals and vkey witnesses of Preview are written as on chain") {
    val txs = blocks("fixtures/preview/conway-governance.hex").flatMap(_._2.transactions)
    def fields(raw: Bytes): Map[CborValue, Node] = node(raw).value match
      case CborValue.Map(kvs) =>
        kvs.map((k, v) => k.value -> v).toMap
      case _ => fail("a map")
    def items(n: Node): Vector[Node] = n.value match
      case CborValue.Tag(_, inner) => items(inner)
      case CborValue.Arr(xs)       => xs
      case _                       => fail("an array")
    val seen = txs.map { t =>
      val body = TxBody.decode(t.body, Era.Conway).fold(e => fail(e.message), identity)
      val witnesses = Witnesses.decode(t.witnesses, Era.Conway).fold(e => fail(e.message), identity)
      val inBody = fields(t.body)
      inBody
        .get(CborValue.UInt(4))
        .foreach(n =>
          assertEquals(written(ConwayCodecs.certificates)(body.certificates), n.original)
        )
      inBody
        .get(CborValue.UInt(19))
        .foreach(n => assertEquals(written(ConwayCodecs.votingProcedures)(body.votes), n.original))
      inBody
        .get(CborValue.UInt(20))
        .foreach(n =>
          assertEquals(written(ConwayCodecs.proposalProcedures)(body.proposals), n.original)
        )
      val vkeys = fields(t.witnesses).get(CborValue.UInt(0)).map(items).getOrElse(Vector.empty)
      assertEquals(witnesses.vkeys.map(written(ConwayCodecs.vkeyWitness)), vkeys.map(_.original))
      (
        inBody.contains(CborValue.UInt(4)),
        inBody.contains(CborValue.UInt(19)),
        inBody.contains(CborValue.UInt(20))
      )
    }
    assert(seen.exists(_._1) && seen.exists(_._2) && seen.exists(_._3), seen)
  }
