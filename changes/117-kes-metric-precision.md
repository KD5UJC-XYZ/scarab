### Fixed (pool monitoring, #117)

- Keep the precision of KES rotation timestamps in the Prometheus textfile. Six significant digits rounded present-day timestamps by up to 5,000 seconds, shifting the stale-driver alert.
