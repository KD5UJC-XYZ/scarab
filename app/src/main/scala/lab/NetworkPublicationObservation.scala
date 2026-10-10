// SPDX-License-Identifier: Apache-2.0
package lab
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import scala.concurrent.duration.FiniteDuration

private[lab] final case class NetworkPublicationObservation(
    index: Int,
    announced: Point,
    original: BoundedChainFollower.Original,
    envelopeSHA256: Bytes,
    blockSHA256: Bytes,
    arrived: FiniteDuration,
    fetched: FiniteDuration,
    applied: Option[FiniteDuration]
)
