# Network routing

Network routing selects paths between connected networks. Routers forward
packets according to a routing table and a destination prefix. Link failures
can trigger route changes while applications continue to retry requests.

Java networking clients should configure connection and request deadlines.
A distributed service needs observable latency, bounded queues, and backpressure.
