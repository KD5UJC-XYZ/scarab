// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files, Path}
import munit.Assertions
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.{
  ConwayParamUpdate,
  GovAction,
  GovActionId,
  GovActionState,
  Ipv4,
  Ipv6,
  PoolRelay,
  PoolRelays,
  Proposal,
  RelayPort,
  RewardAccount,
  Vote
}
import xyz.kd5ujc.scarab.ledger.Credential as NodeCredential
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.{ConwayCodecs, LedgerFileEra, ReferenceLayout}

/** The golden bodies of the V10 snapshot (`fixtures/snapshot-v10-bodies`): the Haskell state
  * section a V10 file holds after its sidecar, as [[SnapshotV5.parts]] writes it
  * ([[ReferenceLayout.encode]]) and [[SnapshotV5.decodeBody]] reads it back
  * ([[ReferenceLayout.decodeNative]]). Each case is a captured devnet state of the reference,
  * decoded, given the values a V10 body can hold and a devnet's does not, and written by the
  * current codecs:
  *
  *   - a pool with two owners, a pledge that is not its cost, a relay of each kind (an address with
  *     IPv4 and IPv6, a name with a port, and an SRV name that ends in a dot) and a metadata URL of
  *     100 bytes;
  *   - likelihoods of single-precision floats;
  *   - in Conway, a parameter-change proposal of `Prices` and two `ExUnits`, with two pool votes;
  *   - in the 11.1 layout, the future parameters of a pool (`psFutureStakePoolParams`), with that
  *     metadata URL, which the state writes through
  *     [[xyz.kd5ujc.scarab.ledger.rules.Convert.poolParamsBack]] and reads with the URL's limit of
  *     its decoder version ([[PoolMetadata.read]]).
  *
  * The Leios layout's future parameters are left out: their voting key's layout is not settled yet.
  * A Dijkstra state is not a case: the Leios build's `dijkstra-203` capture is written back byte
  * for byte, so its body would repeat that file. The hashes are made up, and no case holds a key.
  * `V10BodyGoldenSuite` reads every file back and writes it to the same bytes, and
  * `V10BodyFixtureMain` writes them.
  */
object V10BodyCases extends Assertions:
  /** A body and how it is made: `source` is a reference `state` file, `edits` what is put into it.
    */
  enum Case(val file: String, val source: String, val build: ReferenceBuild, val edits: Edits):
    case Node11_1Conway
        extends Case(
          "node11_1-conway.cbor",
          "fixtures/haskell-state/conway-4593/state",
          ReferenceBuild.Release11_1,
          Edits.ConwayWithFuture
        )
    case LeiosConway
        extends Case(
          "leios-conway.cbor",
          "fixtures/leios/state/conway-195/state",
          ReferenceBuild.LeiosB004fe0c,
          Edits.Conway
        )

  /** What a case puts into its source state. */
  enum Edits:
    case ConwayWithFuture, Conway

  /** The body of [[Case.Node11_1Conway]] as an earlier writer may have stored it: the `Prices` and
    * `ExUnits` of its proposal indefinite lists, which the stored-update reader keeps accepting
    * (`ConwayParamUpdate.decodeStored`). It reads as the canonical body and writes it.
    */
  val IndefiniteFile: String = "node11_1-conway-indefinite-prices.cbor"

  val Directory: Path = Path.of("fixtures/snapshot-v10-bodies")

  def layout(c: Case): ReferenceLayout =
    ReferenceLayout.of(c.build).fold(e => fail(e.toString), identity)

  private def hex(text: String): Bytes =
    Bytes.fromHex(text.replace(" ", "")).fold(e => fail(e.toString), identity)

  private def keyHash[R <: KeyRole](byte: String): KeyHash[R] =
    KeyHash.fromBytes[R](hex(byte * 28)).fold(e => fail(e.toString), identity)

  // ---- the values a case puts in

  /** One relay of each kind; the SRV name ends in a dot, which the reference keeps. */
  val relays: Vector[PoolRelay] =
    val port = RelayPort.fromInt(3001).getOrElse(fail("port"))
    Vector(
      PoolRelay.SingleHostAddr(
        Some(port),
        Some(Ipv4(0xc0000201)),
        Some(Ipv6(0x20010db800000000L, 0x0000000000000001L))
      ),
      PoolRelay.SingleHostName(Some(port), "relay.example.org"),
      PoolRelay.MultiHostName("_cardano._tcp.example.org.")
    )

  val owners: SortedSet[KeyHash[Staking]] =
    Sorted.set(Seq(keyHash[Staking]("0c"), keyHash[Staking]("0b")))
  val pledge: Coin = Coin(BigInt(7_000_000_000L))
  val cost: Coin = Coin(BigInt(340_000_000L))

  /** A metadata URL of 100 bytes of UTF-8: more than the 64 a `Url` holds below decoder version 9,
    * within the 128 it holds from it (`BaseTypes.hs:678-685`, cardano-ledger f649f975).
    */
  val metadataUrl: String = "https://pools.example.org/" + "m" * 69 + ".json"
  val metadataBytes: Bytes =
    val url = metadataUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    assert(url.length == 100, "a URL of 100 bytes")
    hex("82 7864" + Bytes.wrap(url).hex + "5820" + "1f" * 32)
  val metadata: PoolMetadata =
    PoolMetadata.held(metadataBytes).fold(e => fail(e.toString), identity)

  val likelihood: Likelihood =
    Likelihood(Vector(LogWeight(1.5f), LogWeight(-0.1f), LogWeight(3.0e-3f)))

  /** `{19: [577/10000, 721/10000000], 20: [16000000, 10000000000], 21: [62000000, 20000000000]}`:
    * `Prices` and the transaction's and the block's `ExUnits`, canonical and indefinite.
    */
  private val updateFields = Vector(
    19 -> "d81e82190241192710 d81e821902d11a00989680",
    20 -> "1a00f42400 1b00000002540be400",
    21 -> "1a03b20b80 1b00000004a817c800"
  )
  private def updateBytes(open: String, close: String): Bytes =
    hex("a3" + updateFields.map((k, items) => f"$k%02x" + open + items + close).mkString)
  val canonicalUpdate: Bytes = updateBytes("82", "")
  val indefiniteUpdate: Bytes = updateBytes("9f", "ff")

  private val update: ConwayParamUpdate =
    ConwayParamUpdate
      .fromEncoded(updateFields.map((k, items) => BigInt(k) -> hex("82" + items)))
      .fold(e => fail(e.toString), identity)

  private val proposalId = GovActionId(hex("5d" * 32), BigInt(0))

  private def proposal(voters: Vector[Bytes]): GovActionState =
    GovActionState(
      proposalId,
      Map.empty,
      Map.empty,
      voters.map(_ -> Vote.Yes).toMap,
      Proposal(
        BigInt(100_000_000_000L),
        RewardAccount(0, NodeCredential.Key(hex("0d" * 28))),
        GovAction.ParameterChange(None, update, None)
      ),
      BigInt(9),
      BigInt(15)
    )

  // ---- making the bodies

  private def edited(state: NewEpochState[Conway], edits: Edits): NewEpochState[Conway] =
    val pools = ConwayNewEpochState.pools.get(state)
    val (first, pool) = pools.headOption.getOrElse(fail("a state with a pool"))
    val voters = pools.keys.take(2).map(KeyHash.bytes(_)).toVector
    assert(voters.sizeIs == 2, "a state with two pools")
    val relayBytes = PoolRelays.encode(relays)
    val shaped = pool.copy(
      owners = owners,
      pledge = pledge,
      cost = cost,
      relays = relayBytes,
      metadata = Some(metadataBytes)
    )
    val withPool = ConwayNewEpochState.pools.modify(_.updated(first, shaped))(state)
    val withLikelihood = ConwayNewEpochState.es
      .andThen(EpochState.nonMyopic[Conway])
      .andThen(NonMyopic.likelihoods)
      .modify(_.updated(first, likelihood))(withPool)
    val withProposal = ConwayNewEpochState.ls
      .andThen(ConwayLedgerState.proposals)
      .modify(p => p.copy(actions = p.actions :+ proposal(voters)))(withLikelihood)
    edits match
      case Edits.ConwayWithFuture =>
        val future = StakePoolParams(
          first,
          shaped.vrf,
          PoolParamsBlsKey.Node11_1,
          Coin(BigInt(9_000_000_000L)),
          Coin(BigInt(345_000_000L)),
          UnitInterval.boundRational(Rational(1, 7)).getOrElse(fail("margin")),
          AccountAddress(Network.Testnet, AccountId(Credential.KeyHashObj(keyHash[Staking]("0e")))),
          owners,
          relayBytes,
          Some(metadata)
        )
        ConwayNewEpochState.ls
          .andThen(ConwayLedgerState.pState)
          .andThen(PState.future)
          .modify(_.updated(first, future))(withProposal)
      case Edits.Conway => withProposal

  /** The canonical body of `c`. */
  def body(c: Case): Bytes =
    val codec = layout(c)
    val source = codec
      .decode(Bytes.wrap(Files.readAllBytes(Path.of(c.source))))
      .fold(e => fail(s"${c.source}: $e"), identity)
    val file = source.ledger.state match
      case LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, s)) =>
        val next = s.copy(nes = edited(s.nes, c.edits))
        source.copy(ledger =
          source.ledger.withState(
            LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, next))
          )
        )
      case _ => fail(s"${c.source}: not a Conway state")
    assertEquals(ConwayCodecs.parameterUpdate.write(update), Right(canonicalUpdate))
    codec.encode(file).fold(e => fail(s"${c.file}: $e"), identity)

  /** [[IndefiniteFile]]: the canonical body of [[Case.Node11_1Conway]], its update's three records
    * written as indefinite lists. The update's bytes occur in the body once.
    */
  def indefinite(canonical: Bytes): Bytes =
    val text = canonical.hex
    val at = text.indexOf(canonicalUpdate.hex)
    assert(at >= 0 && at % 2 == 0, "the body holds the update")
    assertEquals(text.indexOf(canonicalUpdate.hex, at + 1), -1, "the update occurs once")
    hex(text.patch(at, indefiniteUpdate.hex, canonicalUpdate.hex.length))
