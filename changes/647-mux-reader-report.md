### Fixed (network, #647)

- A connection whose socket read fails no longer also reports that failure to the node's runtime log as an uncaught fiber failure. The read failure still ends the connection, as before.
