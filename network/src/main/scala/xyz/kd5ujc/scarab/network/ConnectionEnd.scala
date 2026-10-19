// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

/** The value kept until a connection's owner presents its close. Normal release and transport
  * closure are distinct from a protocol failure, a refusal and an unexpected defect.
  */
enum ConnectionEnd:
  case Released
  case Closed(reason: NetworkError.Closure)
  case Failed(error: NetworkError)
  case Refused(error: ConnectionError)
  case Defect(cause: Throwable)

  def message: String = this match
    case Released       => "released"
    case Closed(why)    => NetworkError.BearerClosed(why).message
    case Failed(error)  => error.message
    case Refused(error) => error.message
    case Defect(cause)  => NetworkError.Defect(cause).message

object ConnectionEnd:
  def fromNetwork(error: NetworkError): ConnectionEnd = error match
    case NetworkError.BearerClosed(why) => Closed(why)
    case NetworkError.Defect(cause)     => Defect(cause)
    case other                          => Failed(other)
