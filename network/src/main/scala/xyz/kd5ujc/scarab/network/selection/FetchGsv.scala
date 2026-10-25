// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

/** Degenerate DeltaQ/GSV model of ouroboros-network 2fb69829, Network/DeltaQ.hs. Time is integral
  * picoseconds, as in the reference's DiffTime. Its current distribution is degenerate and its
  * sampler deliberately retains the fixed 4Mbps serialisation estimate.
  */
object FetchGsv:
  val PicosPerSecond: BigInt = BigInt(10).pow(12)

  final case class Gsv(gPicos: BigInt, sPicosPerByte: BigInt, vPicos: BigInt = 0):
    def arrival(bytes: BigInt): BigInt = gPicos + sPicosPerByte * bytes + vPicos

  final case class PeerGsv(sampleTimePicos: BigInt, outbound: Gsv, inbound: Gsv):
    def duration(bytesInFlight: Long, bytesRequested: Long): BigInt =
      outbound.arrival(100) + inbound.arrival(BigInt(bytesInFlight) + bytesRequested)

    /** The reference is strictly before, rather than at, the two-second deadline. */
    def probability(bytesInFlight: Long, bytesRequested: Long): Double =
      if duration(bytesInFlight, bytesRequested) < 2 * PicosPerSecond then 1.0 else 0.0

    def geographic: BigInt = outbound.gPicos + inbound.gPicos

    def window: Window =
      val numerator = outbound.gPicos + inbound.gPicos + PicosPerSecond / 50
      val denominator = inbound.sPicosPerByte.max(1)
      val low = ((numerator + denominator - 1) / denominator).max(3 * 64 * 1024)
      Window(saturated(low), saturated(low * 2))

    /** New sample first: reference PeerGSV semigroup, 1000-second time constant. */
    def update(previous: PeerGsv): PeerGsv =
      val interval = (sampleTimePicos - previous.sampleTimePicos).max(0)
      val constant = 1000 * PicosPerSecond
      val alphaPicos = interval.min(constant) * PicosPerSecond / constant
      def smooth(current: Gsv, old: Gsv): Gsv =
        current.copy(gPicos =
          old.gPicos + floorDivide(alphaPicos * (current.gPicos - old.gPicos), PicosPerSecond)
        )
      copy(
        outbound = smooth(outbound, previous.outbound),
        inbound = smooth(inbound, previous.inbound)
      )

  final case class Window(lowBytes: Long, highBytes: Long)

  val default: PeerGsv =
    val direction = Gsv(PicosPerSecond / 2, 2000000)
    PeerGsv(0, direction, direction)

  def fromSample(startPicos: BigInt, endPicos: BigInt): PeerGsv =
    val direction = Gsv((endPicos - startPicos).max(0) / 2, 2000000)
    PeerGsv(startPicos, direction, direction)

  // Fixed/Pico floors signed products; BigInt division alone truncates towards zero.
  private[selection] def floorDivide(numerator: BigInt, denominator: BigInt): BigInt =
    val quotient = numerator / denominator
    if numerator < 0 && numerator % denominator != 0 then quotient - 1 else quotient

  private def saturated(bytes: BigInt): Long = bytes.max(0).min(BigInt(Long.MaxValue)).toLong
