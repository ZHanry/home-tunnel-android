# API v1.3.0

Vendored from the immutable `ZHanry/home-tunnel-server` tag `api-v1.3.0`.
`lock.json` records the reviewed source revision and SHA-256 values. Run
`python scripts/check-repository.py` after changes.

`openapi.v1.json` describes REST requests and responses, `api.schema.json` provides
JSON Schema 2020-12 types, and the unchanged `home-tunnel.v1.json` describes sync
and realtime envelopes. API tags and historical release tags must never move.

Home Tunnel 7.0 requires a 7.0 server. New paginated device/connection catalogs,
MFA, enrollment, metadata and batch operations are covered by consumer tests.
Unknown capabilities and errors must fail safely. See the server's docs/API.md.

The RD wire additions use a separate `remote.lock.json` and generated protocol/vector
snapshot. The legacy sync fixture remains unchanged for existing management APIs.
The RD lock records an actual source revision, generated-file hashes and dirty-tree
status; it does not claim an `api-v1.2.0` tag exists. Refresh only using
`python scripts/import-remote-contract.py <reviewed-server-checkout>` and verify
the consumer tests after every imported change.
