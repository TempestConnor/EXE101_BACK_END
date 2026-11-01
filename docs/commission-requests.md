# Commission requests and messages (F16)

Decisions recorded 2 November 2026 for the first priority-3 increment. A Customer asks an Artist for custom work,
and both talk in a message thread that cannot be edited. Quotes, the sketch stage, payments, work stages, delivery,
cancellation and disputes belong to F17–F20.

## Approved policies

- **Public display choice (amendment §D.11, schema):** migration `0004` adds `Commission.AllowPublicDisplay bit NOT
  NULL DEFAULT 0`. Every request must state it (`allowPublicDisplay`), and nothing changes it afterwards. Rows
  created before the migration read as `0`: no recorded consent means no display.
- **Who may request (§17.4, §17.8):** any active User, including an Artist commissioning another member (§1.8). The
  Artist must be `ACTIVE` and accepting requests. The request is refused with `404 ARTIST_NOT_FOUND`,
  `409 OWN_ARTIST_PROFILE` (the requester owns the Artist Profile), `409 ARTIST_NOT_ACTIVE` (deactivated) or
  `409 ARTIST_NOT_ACCEPTING_COMMISSIONS`.
- **Concurrency:** the request reads the Artist row under the same shared lock as publishing ([Artist
  deactivation](artist-deactivation.md#concurrency)). A deactivation in progress makes the request wait, and the
  request then sees the deactivation. A request in progress commits before a deactivation can.
- **Commission settings (§2.6, §17.2–3):** `PUT /artists/me/commission-settings` sets `acceptingCommissions` and an
  optional `startingPrice` (whole VND, at least 0). Only an `ACTIVE` Artist may change them; otherwise
  `409 ARTIST_NOT_ACTIVE`. The starting price is informational and never becomes a Quote price. New Artists start
  closed (F02). Deactivation leaves the setting unchanged; the status alone blocks requests.
- **Public profile:** `GET /artists/{artistId}` needs no token and returns `displayName`, `bio`, `status`,
  `acceptingCommissions` and `startingPrice`, so Customers can see who takes requests.
- **Text limits:** the description and each message are 1–5000 characters after trimming (`400`).
- **Attachments (Q17):** reference files are stored as protected `StoredFile`s with `AccessClass = COMMISSION`.
  Accepted by extension: png, jpg, jpeg, webp, pdf, psd, zip; contents are not inspected. Each file is at most 20 MB,
  and a Commission has at most 10. The Customer may attach files with the request, and either participant may add
  more later (`POST /commissions/{id}/attachments`). Attachments are not linked to a message (the schema has no
  `MessageId`). Files are checked before anything is locked or written.
- **Duplicate submission (Q18):** no idempotency key. A Customer may have several open requests to the same Artist;
  a duplicate request moves no money, and the Artist can decline it ([Commission Quotes](commission-quotes.md)).
- **Pending requests when an Artist is deactivated (Q05, F16 part):** deactivation leaves existing `REQUESTED`
  Commissions and their threads as they are (§17.9: nothing ends automatically). A deactivated Artist keeps reading
  and messaging. Quotes by a deactivated Artist are decided in [Commission Quotes](commission-quotes.md).
- **Access (§27.6):** only the Customer and the commissioned Artist can read a Commission, its messages and its
  files. Anyone else gets `404 COMMISSION_NOT_FOUND`, like Orders. Admin access comes with disputes (F19).
- **Messages (§18):** append-only. There is no edit or delete API (`405`), and `TR_Message_Immutable` rejects
  `UPDATE` and `DELETE` in the database. Messages change no terms and no status. Sending a message or a file is
  refused with `409 COMMISSION_CLOSED` once the Commission is `DECLINED`, `CANCELLED` or `COMPLETED`; the thread
  stays readable. A message is written under a shared lock on the Commission, so it cannot cross a status change.
- **History (§23):** creating a request writes a `CommissionEvent` (`REQUESTED`, `null` → `REQUESTED`, actor = the
  Customer) in the same transaction. The Commission keeps the Customer's and the Artist's display names as
  snapshots.

## HTTP contract

All `/commissions` endpoints need a Customer bearer token.

| Endpoint | Request | Success |
| --- | --- | --- |
| `POST /commissions` | JSON `artistId`, `description`, `allowPublicDisplay` | 201 with the Commission (`status: "REQUESTED"`, `attachments`) |
| `POST /commissions` (multipart) | part `request` (the same JSON), up to 10 parts `files` | 201, as above |
| `GET /commissions` | — | 200, the User's Commissions as Customer or Artist, newest first |
| `GET /commissions/{id}` | — | 200 with the description and attachment metadata |
| `GET /commissions/{id}/messages` | — | 200, oldest first |
| `POST /commissions/{id}/messages` | JSON `body` | 201 with `messageId`, `senderUserId`, `body`, `sentAt` |
| `POST /commissions/{id}/attachments` | multipart part `file` | 201 with the attachment metadata |
| `GET /commissions/{id}/attachments/{attachmentId}/content` | — | 200, the file streamed |
| `PUT /artists/me/commission-settings` | JSON `acceptingCommissions`, optional `startingPrice` | 200 with the Artist |
| `GET /artists/{artistId}` | no token | 200 with the public profile |

New error codes: `COMMISSION_NOT_FOUND`, `COMMISSION_ATTACHMENT_NOT_FOUND` (404); `COMMISSION_INVALID`,
`ARTIST_SETTINGS_INVALID` (400); `OWN_ARTIST_PROFILE`, `ARTIST_NOT_ACCEPTING_COMMISSIONS`, `COMMISSION_CLOSED`,
`COMMISSION_ATTACHMENT_LIMIT_REACHED` (409); `COMMISSION_ATTACHMENT_TYPE_NOT_SUPPORTED` (415);
`COMMISSION_ATTACHMENT_TOO_LARGE` (413).

## Financial correctness

F16 moves and records no money. The starting price is a displayed figure only. Quote prices, the two 50% payments
and progress-based refunds come with F17–F20 under [financial correctness](financial-correctness.md).

## Known limits

- **No Admin view of threads.** Evidence is kept but can only be read in the database until F19.
- **No spam guard.** Nothing limits how many requests a Customer sends. Closing it would take a rate limit or a cap
  on open requests per Customer and Artist.
- **Uploads are read fully into memory,** as for Listing files; ten 20 MB files need that much heap per request.

## Tests

`CommissionServiceTest`, `CommissionControllerTest` and the settings cases in `ArtistServiceTest` are unit tests.
`CommissionPersistenceTest` commits messages, which can never be deleted, so it runs only against the disposable
test database selected by `TEST_DB_URL` and leaves its rows there.
