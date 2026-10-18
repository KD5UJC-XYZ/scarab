// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import io.circe.{ACursor, Json}
import java.time.{Instant, ZoneOffset}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.protocol.NtcVersion
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, ShelleyBasedEra}
import xyz.kd5ujc.scarab.storage.haskell.state.{NodeOrders, Versions, Wire}

/** `GetGenesisConfig`: the compact Shelley genesis (`CompactGenesis`, whose initial funds and
  * staking are dropped) in the 15-field encoding of node-to-client versions 16–23 (the one without
  * the extra configuration): system start, network magic and id, active slot coefficient, security
  * parameter, epoch length, KES period and evolutions, slot length in microseconds, update quorum,
  * maximum supply, the Shelley protocol parameters, the genesis delegates, and two empty maps.
  * Before NTC 21 the Shelley protocol version occupies two contiguous fields instead of one pair
  * (`Query/LegacyShelleyGenesis.hs:34-57`, `LegacyPParams.hs:46-68` at ouroboros-consensus
  * 82ecba329d7d054340bf707d44fe6e9ac27cec40).
  */
object GenesisConfig:
  private type R[A] = Either[String, A]

  /** The version the answer is written at: the lowest of the Conway era (`Versions.eraLow`). */
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)
  import NodeOrders.given

  /** The genesis delegates: genesis key hash to the delegate's key hash and VRF key hash. */
  private val delegateCodec = Wire.definiteMapOf(
    xyz.kd5ujc.scarab.models.runtime.Codec.bytes,
    Wire.writeOnly[(Bytes, Bytes)]("a genesis delegate") { (d, out) =>
      out.arrayHead(2)
      out.bytes(d._1)
      out.bytes(d._2)
    }
  )

  private def get[A: io.circe.Decoder](c: ACursor, name: String): R[A] =
    c.downField(name).as[A].leftMap(e => s"genesis $name: ${e.message}")

  private def hex(c: ACursor, name: String): R[Bytes] =
    get[String](c, name).flatMap(Bytes.fromHex)

  private def decimal(c: ACursor, name: String): R[Rational] =
    c.downField(name).as[BigDecimal].map(Rational.fromDecimal).leftMap(_ => s"genesis $name")

  /** The result item, from the Shelley genesis JSON. */
  def encode(shelley: Json, version: NtcVersion = NtcVersion.LatestReleased): R[Bytes] =
    val c = shelley.hcursor
    val p = c.downField("protocolParams")
    for
      start <- get[String](c, "systemStart").flatMap(s =>
        Either.catchNonFatal(Instant.parse(s)).leftMap(_ => s"systemStart $s")
      )
      magic <- get[BigInt](c, "networkMagic")
      network <- get[String](c, "networkId")
      active <- decimal(c, "activeSlotsCoeff")
      k <- get[BigInt](c, "securityParam")
      epochLength <- get[BigInt](c, "epochLength")
      kesPeriod <- get[BigInt](c, "slotsPerKESPeriod")
      kesEvolutions <- get[BigInt](c, "maxKESEvolutions")
      slotLength <- get[BigDecimal](c, "slotLength")
      quorum <- get[BigInt](c, "updateQuorum")
      supply <- get[BigInt](c, "maxLovelaceSupply")
      delegates <- c
        .downField("genDelegs")
        .as[Option[Map[String, Json]]]
        .leftMap(_ => "genDelegs")
        .flatMap(
          _.getOrElse(Map.empty).toVector.traverse { case (g, j) =>
            for
              genesis <- Bytes.fromHex(g)
              delegate <- hex(j.hcursor, "delegate")
              vrf <- hex(j.hcursor, "vrf")
            yield genesis -> (delegate, vrf)
          }
        )
      params <- shelleyParams(p, version)
    yield CborOut.build { w =>
      w.array(15)
      val utc = start.atZone(ZoneOffset.UTC)
      val picos = BigInt(utc.getHour * 3600L + utc.getMinute * 60L + utc.getSecond) *
        BigInt(10).pow(12) + BigInt(utc.getNano) * 1000
      w.array(3).uint(utc.getYear).uint(utc.getDayOfYear).uint(picos)
      w.uint(magic).uint(if network == "Mainnet" then 1 else 0)
      w.array(2).uint(active.numerator).uint(active.denominator)
      w.uint(k).uint(epochLength).uint(kesPeriod).uint(kesEvolutions)
      w.uint((slotLength * 1000000).toBigInt)
      w.uint(quorum).uint(supply)
      params(w)
      w.raw(delegateCodec.encodeUnchecked(delegates.toMap))
      w.map(0)
      w.array(2).map(0).map(0)
    }

  /** The 17 Shelley protocol parameters of the genesis file. */
  private def shelleyParams(p: ACursor, version: NtcVersion): R[CborOut => Unit] =
    val legacy = version.number < NtcVersion.V21.number
    for
      minFeeA <- get[BigInt](p, "minFeeA")
      minFeeB <- get[BigInt](p, "minFeeB")
      maxBody <- get[BigInt](p, "maxBlockBodySize")
      maxTx <- get[BigInt](p, "maxTxSize")
      maxHeader <- get[BigInt](p, "maxBlockHeaderSize")
      keyDeposit <- get[BigInt](p, "keyDeposit")
      poolDeposit <- get[BigInt](p, "poolDeposit")
      eMax <- get[BigInt](p, "eMax")
      nOpt <- get[BigInt](p, "nOpt")
      a0 <- decimal(p, "a0")
      rho <- decimal(p, "rho")
      tau <- decimal(p, "tau")
      d <- decimal(p, "decentralisationParam")
      entropy <- p
        .downField("extraEntropy")
        .downField("tag")
        .as[Option[String]]
        .leftMap(_ => "extraEntropy")
        .flatMap {
          case None | Some("NeutralNonce") => Right(None)
          case Some(_) => hex(p.downField("extraEntropy"), "contents").map(Some(_))
        }
      major <- get[BigInt](p.downField("protocolVersion"), "major")
      minor <- get[BigInt](p.downField("protocolVersion"), "minor")
      minUtxo <- get[BigInt](p, "minUTxOValue")
      minPool <- get[BigInt](p, "minPoolCost")
    yield (w: CborOut) =>
      w.array(if legacy then 18 else 17)
      w.uint(minFeeA).uint(minFeeB).uint(maxBody).uint(maxTx).uint(maxHeader)
      w.uint(keyDeposit).uint(poolDeposit).uint(eMax).uint(nOpt)
      List(a0, rho, tau, d).foreach(r => w.raw(Wire.ratio.encodeUnchecked(r)))
      w.raw(Wire.nonce.encodeUnchecked(entropy.fold[Nonce](Nonce.Neutral)(Nonce.Hash(_))))
      if !legacy then w.array(2)
      w.uint(major).uint(minor)
      w.uint(minUtxo).uint(minPool)
