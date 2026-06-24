# m365-ingest — Tenant Mailbox Collector (T1)

Microsoft Graph API → `com.etzhayyim.apps.kyber.inbox.emailSignal` pipeline. **No UI, no OAuth.** Pure tenant-app ingest using client credentials flow (`Application.Mail.Read`).

## Responsibility Boundary (RACI)

| Actor | Responsible | Reads | Writes |
|---|---|---|---|
| **m365-ingest** (this) | Graph API raw fetch | `vertex_m365_user`, Graph API | `vertex_m365_sync_state`, `com.etzhayyim.apps.kyber.inbox.emailSignal` |
| `email-service-adapter` (outlook.etzhayyim.com) | OAuth UI + per-user consent | PDS `oauth_connection` | `oauth_connection` |
| `kyber-inbox` (`inb0x4k2`) | signal/noise + dept routing | `emailSignal` (subscribeRepos) | `vertex_email_message` |
| `yabai` (`y8b41k0x`) | threat scoring | `emailSignal` where `signalClass='yabai'` (subscribeRepos) | `yabai.entity/evidence/risk` |

**Key separation**: m365-ingest **only** pulls + normalizes. Routing/classification/scoring belongs to downstream subscribers. Shannon η maximized by keeping 1 responsibility per actor.

## Architecture

| 項目 | 値 |
|---|---|
| **Execution Tier** | T1 (MCP-Compose, manifest-driven, no Worker code) |
| **Trigger sources** | cron (`*/15 * * * *` delta, `0 3 * * *` user enum) + xrpc on-demand |
| **Auth** | Azure AD app (`9ad011ba-148c-4965-8f80-62086440c3df`) + tenant `e9b32269-81a5-4d3a-bf94-048ba6770c99` |
| **Host capability** | `com.etzhayyim.host.m365.*` (4 methods — acquireAppToken, enumerateUsers, fetchMailFolders, fetchMessagesPage) |
| **Output collection** | `com.etzhayyim.apps.kyber.inbox.emailSignal` (derives `vertex_email_message`) |
| **State** | `vertex_m365_user` + `vertex_m365_sync_state` (per upn × data_kind) |
| **PII Tier** | 3 (per ADR-0014) — subject/body Signal-enveloped, hashed message-id in AT Record |

## Pipelines

### 1. `enumerate-tenant-users` (cron `0 3 * * *`)

Refresh `vertex_m365_user` from Graph `/users?$filter=endsWith(upn,'@etzhayyim.com')`.

### 2. `delta-sync-all-users` (cron `*/15 * * * *`)

- Query `vertex_m365_user` → `vertex_m365_sync_state` left join → ORDER BY last_sync ASC (stale first)
- Fan-out to `sync-single-user` pipeline, bounded concurrency = 4
- Respect `throttle_until` (set by 429 handler)

### 3. `sync-single-user` (xrpc `com.etzhayyim.apps.m365Ingest.syncUser`)

- Acquire app token (cached by host 55 min)
- Fetch mail folders (nested recursive)
- Paginate `/users/{upn}/messages` with `$filter=receivedDateTime ge $since`
- Per message: classify (`folder → signalClass`, `from → senderKind`, `noiseScore`)
- Emit `createRecord(com.etzhayyim.apps.kyber.inbox.emailSignal, ...)`
- Update watermark at end

## Lexicons

### Host (4)
- `com.etzhayyim.host.m365.acquireAppToken` — client credentials → Bearer JWT
- `com.etzhayyim.host.m365.enumerateUsers` — `/users` with filter
- `com.etzhayyim.host.m365.fetchMailFolders` — recursive folder tree
- `com.etzhayyim.host.m365.fetchMessagesPage` — one page of `/users/{upn}/messages`

### App (3 XRPC)
- `com.etzhayyim.apps.m365Ingest.syncUser` (procedure) — on-demand single-user ingest
- `com.etzhayyim.apps.m365Ingest.syncAllUsers` (procedure) — manual fan-out trigger
- `com.etzhayyim.apps.m365Ingest.syncStatus` (query) — read vertex_m365_sync_state

## Folder → signalClass Classification

| Folder pattern | signalClass | Downstream |
|---|---|---|
| `🚨 yabai` / `フィッシング` / `迷惑` / `phishing` / `spam` / `junk` | `yabai` | yabai.entity + FraudSignal evidence auto-register |
| `削除` / `trash` / `deleted` | `deleted` | stored but noise_score=0.7 |
| `送信` / `sent` / `outbox` / `下書き` / `draft` | `sent` | internal only |
| `teams 通知` / `SaaS/MS` / `SES` | `noise` | low priority |
| default | `signal` | kyber-inbox routing |

## Sender Kind Classification

- `internal`: `@etzhayyim.com` or `@etzhayyim.com`
- `noreply`: `no-reply@*`, `noreply@*`, `donotreply@*`
- `ses_vendor`: `@sendgrid|mailchimp|amazonses|mailgun|postmark`
- `external`: everything else

## Consent / Governance

Reading employee mailboxes = high PII. Required consent records (`com.etzhayyim.apps.consent.grant`):

1. **Per-user opt-in** for `security:BEC-detection` purpose
2. **Admin/legal consent** for `compliance:audit` (7-year retention per jp-e-archive-law)
3. **Per-purpose data minimization** — enforce `$select` allowlist, no body/attachments by default

Audit trail: every ingest run writes `com.etzhayyim.apps.m365Ingest.run` event → ocel log.

## Failure Modes

| Failure | Action |
|---|---|
| 401 Unauthorized | Re-acquire token, retry once |
| 429 Throttled | Set `throttle_until = now + Retry-After`, skip this user next cron |
| 503 Service Unavailable | Exponential backoff up to 5 retries |
| 404 User not found | Mark `vertex_m365_user.account_enabled = false`, alert |
| Partial page failure | Persist what was fetched, resume from same nextLink next run |

## Deploy

T1 actor = no Worker. Register via PDS:

```bash
etzhayyim xrpc com.etzhayyim.actor.migrate -d '{"manifestPath":"20-actors/m365-ingest/actor-manifest.jsonld"}'
```

Requires prior:
1. Migration `20260417190000_vertex_m365_sync_state.ts` applied
2. Host WIT `etzhayyim-host:m365/m365@1.0.0` implemented in `50-infra/.../kotodama-host/src/capabilities/m365.ts`
3. Secret `M365_CLIENT_SECRET` in CF Secrets Store (binding on executor worker)

## Operational History (2026-04-17)

- Host TS impl `m365.ts` + 4 host lexicons + dispatcher wiring: **DONE**
- Migrations `20260417190000` (sync_state) + `20260417200000` (BEC columns) applied via psql: **DONE**
- Initial ingest per UPN via `~/.etzhayyim/ingest/m365_mail_ingest.py`:
  - `legal@etzhayyim.com`: skipped (email not reachable via Graph)
  - `jinji@etzhayyim.com`: ingested, Amazon Business email classified IntelExtraction score=28/clean
  - `keiri@etzhayyim.com`: ingested, **BEC campaign detected** — 4 outlook.com actors sent CEO name (河崎純真) + company name spoofing, all registered to yabai (risk 75–80/monitor)
- Compactor scaled 1→3 replicas + 8→16 GiB (Hummock SSTable backlog 11K→draining): **DONE**
- `vertex_m365_user`: not yet seeded (pending T1 executor host.m365.* support)
- `wrangler secret M365_CLIENT_SECRET`: pending executor Phase 2

## Migration Path (from standalone Python script)

Current: `~/.etzhayyim/ingest/m365_mail_ingest.py` (one-off, local)

→ Step 1: Implement host TS `m365.ts` (port of Python capability): **DONE**
→ Step 2: Apply migration `20260417190000_vertex_m365_sync_state`: **DONE**
→ Step 3: Seed `vertex_m365_user` once (daily cron will keep it fresh): PENDING
→ Step 4: Register manifest → `etzhayyim xrpc com.etzhayyim.actor.migrate`: PENDING
→ Step 5: Run `syncUser` for each UPN (initial full sync): PENDING
→ Step 6: Delta cron takes over → Python script archived / deleted: PENDING

## Related

- `email-service-adapter` (outlook.etzhayyim.com) — OAuth per-user (unchanged)
- `kyber-inbox` (`inb0x4k2`) — downstream signal/noise routing
- `yabai` (`y8b41k0x`) — downstream threat scoring
- `gmail` — peer ingest actor for Google Workspace (separate Graph API)
- ADR-0014 PII Tier 3 + Cohort-First Pattern
