# Distributed systems

Distributed systems coordinate independent computers over a network. Messages
can arrive late, machines can stop, and a timeout cannot prove a remote failure.
Reliable distributed applications therefore use bounded retries, idempotent
operations, and explicit failure handling.

Replication improves availability but requires decisions about consistency.
A leader can order database writes while followers apply a replicated log.
Network partitions force designers to consider which operations remain safe.
