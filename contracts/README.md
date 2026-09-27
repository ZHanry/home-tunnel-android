# API v1.4.0 proposed

Vendored from server commit `80bd4736a20594b9359c436783508106ac1b8f0f`.
The OpenAPI document names this shape `api-v1.4.0`. That name is not an
immutable tag. `lock.json` records `contract_status: proposed`,
`frozen_tag: null`, and the previous immutable tag `api-v1.3.0`.
The REST prefix remains `/api/v1`. Run `python scripts/check-repository.py`
after changes. Refresh only with:

```bash
python scripts/import-remote-contract.py <server-checkout> --revision <40-hex>
```

Do not pass `--published-ref` until the tag exists and was independently published.

`openapi.v1.json` describes REST requests and responses, `api.schema.json` provides
JSON Schema 2020-12 types, and the unchanged `home-tunnel.v1.json` describes sync
and realtime envelopes. API tags and historical release tags must never move.

Home Tunnel 7.0 requires a 7.0 server. New paginated device/connection catalogs,
MFA, enrollment, metadata and batch operations are covered by consumer tests.
Unknown capabilities and errors must fail safely. See the server's docs/API.md.

The RD wire additions use a separate `remote.lock.json` and generated protocol/vector
snapshot. The legacy sync fixture remains unchanged for existing management APIs.
The RD lock records the same proposed commit, generated-file hashes and
`published_contract_ref: null`. It does not claim that `api-v1.4.0` was tagged.
