### Fixed

- Complete the finite registration of a peer's pipeline workers before honoring cancellation of
  its first read. An immediate hot-peer demotion can no longer leave the pipeline marked started
  without the producer that its drainer awaits. Network reads, writes and worker bodies remain
  cancelable; protocol shutdown deadlines and churn acceptance are unchanged.
