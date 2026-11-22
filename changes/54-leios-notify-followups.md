### Fixed (Leios notify client, #54)

- A LeiosNotify server with one credit left gives it to a pending offer of an endorser block before a
  pending vote, as the reference's server does.
- A LeiosNotify client whose pipeline is full sends the request a reply makes room for only once the
  immutable tip can forecast the current slot, as the reference's client does.
