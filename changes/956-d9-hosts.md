### Fixed (network, #956)

- Peer DNS lookups (`--peer` and the topology's local roots, public roots and bootstrap peers, and ledger relays) no longer read the hosts file, as cardano-node does: every name is sent to the DNS servers of the system configuration. Before, a name listed in the hosts file (`/etc/hosts`) was answered from it without asking DNS, where the reference may get another address or none. A peer whose name only the hosts file knows no longer resolves; name it by its IP address, or give it a DNS record.
