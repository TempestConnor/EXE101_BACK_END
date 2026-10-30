# Customer addresses and supported delivery areas (F04)

Decisions recorded 31 October 2026 for the F04 increment.

## Approved policies

- **Phone:** a Vietnamese number, `0` followed by nine digits. `+84` and other forms are rejected, not normalized.
- **Address shape:** the two-tier structure in force since 1 July 2025: province, then ward/commune. Recipient,
  phone, line 1, ward and province are required. District is optional and kept only because the column exists.
  Postal code is optional and, when given, five digits. Country is always `VN` (also a database check constraint).
- **Province codes:** the official two-digit codes of the 34 province-level units, for example `01` Hà Nội and
  `79` TP. Hồ Chí Minh. The Admin adds provinces; a new province starts unsupported.
- **Saving an address:** its province must exist and be supported at the moment of saving (create and update).
  Checkout checks again, because support can be withdrawn after an address is saved.
- **Default address:** at most one active default per Customer, enforced by `UX_Address_Default`. Deleting the
  default leaves the Customer without one; no other address is promoted.
- **Deletion:** logical, through `DeletedAt`. Deleted addresses are hidden from the Customer and from checkout.
- **Audit:** every Admin support change writes an `AdminAudit` record (`PROVINCE_SUPPORT_CHANGED`, target
  `SupportedProvince`, `TargetId` = `ProvinceId`) in the same transaction.

## API

| Method and path | Role | Result |
| --- | --- | --- |
| `GET /addresses` | Customer | the Customer's active addresses |
| `POST /addresses` | Customer | `201` created address |
| `PUT /addresses/{addressId}` | Customer | full replacement of an owned, active address |
| `DELETE /addresses/{addressId}` | Customer | `204`, logical deletion |
| `GET /provinces/supported` | Customer | provinces currently supported, for the address form |
| `GET /provinces` | Admin | all provinces |
| `POST /provinces` | Admin | `201` new, unsupported province |
| `PUT /provinces/{provinceId}/support` | Admin | set support with an optional reason; audited |

Another Customer's address, or a deleted one, is reported as not found.

## Checkout hand-off to F10

`AddressService.selectForCheckout(userId, addressId)` checks ownership, `VN` and current province support inside
the caller's transaction, and returns the values to copy into `OrderShippingAddress`. F10 writes the snapshot row
(it needs the `OrderId`) and owns the acceptance tests for one snapshot per physical Order and its immutability
after later address edits. `TR_ShippingSnapshot_Immutable` already blocks snapshot updates in the database.

## Schema change

[`database/migrations/0001_supported_province_id.sql`](../database/migrations/0001_supported_province_id.sql)
adds `SupportedProvince.ProvinceId` (identity primary key) so audit records can reference a province;
`ProvinceCode` keeps a unique constraint.
