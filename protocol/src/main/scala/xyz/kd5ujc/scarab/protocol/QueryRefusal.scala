// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

/** A service keeps its domain-specific decoder/state/writer cause at the generic query boundary. */
enum QueryRefusal[+E]:
  case Undecodable(cause: E, prefix: String)
  case Unsupported(query: String)
  case Unwritable(query: String, cause: E)
  case OutOfRange(query: String)
  case Defect(query: String, cause: Throwable)

  def message: String = this match
    case Undecodable(cause, prefix) => s"cannot decode ($cause): $prefix"
    case Unsupported(query) => s"unsupported query $query"
    case Unwritable(query, cause) => s"cannot write the answer to $query: $cause"
    case OutOfRange(query) => s"cannot write the answer to $query: the answer is out of range"
    case Defect(query, cause) => s"cannot write the answer to $query: ${cause.getMessage}"
