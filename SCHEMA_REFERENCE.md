# CERP Schema Reference — for Cash-Book Development

This file is your map. Read it once, then keep it open while building.

It covers the three table groups that matter for the cash-book Android app:

1. **Business Units & Assignments** — who can reach which data
2. **Roles, Groups & ACL** — what they can do once they get there
3. **Finance (`fin_*`)** — the cash-book's actual data

Every column below was verified against the migration source where one exists. Tables flagged **(inferred)** exist at runtime but their initial `CREATE TABLE` is in the tenant template DB, not in a migration file — the columns listed are derived from `SELECT`/`INSERT` usage across the codebase and are correct in practice, but the exact nullability/default may differ slightly from the provisioning DDL.

---

## Part 1 — Business Units & Assignments

### `sys_business_units` (inferred)

The tree of everything. One row per org, division, project, site, and any custom level the admin adds.

| Column        | Type          | Nullable | Notes                                               |
|---------------|---------------|----------|-----------------------------------------------------|
| `id`          | uuid          | NO       | PK                                                  |
| `parent_id`   | uuid          | YES      | FK → `sys_business_units.id`. NULL = root (org).    |
| `organization_id` | uuid      | NO       | FK → `platform_db.organizations.id` (tenant owner)  |
| `name`        | varchar(255)  | NO       |                                                     |
| `code`        | varchar(50)   | YES      | Usually unique within tenant                        |
| `level`       | varchar(50)   | NO       | `'org'`, `'project'`, `'site'`, or admin-defined    |
| `level_order` | integer       | YES      | Display sort order (pulled from `sys_level_orders`) |
| `bu_type`     | varchar(30)   | NO       | `'cluster'` (org/division), `'project'`, `'site'`   |
| `is_active`   | boolean       | NO       | Default `true`. Soft-delete flag.                   |
| `latitude`    | numeric       | YES      | Added via `migrate-business-units-lat-lng.ts`       |
| `longitude`   | numeric       | YES      | Same migration                                      |
| `created_at`  | timestamptz   | NO       | Default `now()`                                     |
| `updated_at`  | timestamptz   | NO       | Default `now()`                                     |

**Key points:**

- `level` is **text**, not an enum. Three values are system-protected (`'org'`, `'project'`, `'site'`); tenants can add `'division'`, `'region'`, etc. via the admin UI.
- `bu_type` is a coarser, fixed bucket used by ACL helpers like `getAccessibleBuIdsByType()`.
- The **root BU** has `parent_id = NULL`, `level = 'org'`, and is seeded during org signup.

### `sys_level_orders` (inferred — referenced in `orgRoutes.ts:130,254-258,466`)

Catalog of legal `level` values and their display ordering.

| Column       | Type         | Nullable | Notes                                          |
|--------------|--------------|----------|------------------------------------------------|
| `id`         | serial       | NO       | PK                                             |
| `level_name` | varchar(50)  | NO       | UNIQUE. Matches `sys_business_units.level`     |
| `level_order`| integer      | NO       | 1 = top of tree                                |
| `label`      | varchar(100) | NO       | Display name                                   |
| `is_active`  | boolean      | NO       | Default `true`                                 |

`'org'`, `'project'`, `'site'` rows cannot be deleted.

### `sys_user_bu_assignments`

Source: [`temp/migrate-bu-assignments-split.ts:42-94`](../cerp-backend/temp/migrate-bu-assignments-split.ts#L42-L94)

One row per (user, BU). The same user can be assigned to multiple BUs — each assignment is independent.

| Column              | Type        | Nullable | Notes                                                         |
|---------------------|-------------|----------|---------------------------------------------------------------|
| `id`                | uuid        | NO       | PK, default `gen_random_uuid()`                               |
| `user_id`           | uuid        | NO       | FK → `auth_users.id`                                          |
| `business_unit_id`  | uuid        | YES      | FK → `sys_business_units.id`. NULL = "no BU" / gypsy state    |
| `designation_id`    | uuid        | YES      | FK → `hr_designations.id` (only if HR module is in use)       |
| `is_primary`        | boolean     | NO       | Default `false`. Max one `true` per user (unique partial idx) |
| `effective_from`    | date        | YES      | NULL = always effective from start                            |
| `effective_to`      | date        | YES      | NULL = never expires                                          |
| `is_active`         | boolean     | NO       | Default `true`. Soft-delete                                   |
| `joined_at`         | timestamptz | NO       | Default `now()`                                               |
| `updated_at`        | timestamptz | NO       | Default `now()`                                               |

**Indexes:**

- `UNIQUE (user_id) WHERE is_primary = true AND is_active = true` — enforces single primary per user
- `UNIQUE (user_id, business_unit_id) WHERE is_active = true` — no duplicate active assignments

### `sys_user_bu_assignment_roles`

Source: [`temp/migrate-bu-assignments-split.ts:57-83`](../cerp-backend/temp/migrate-bu-assignments-split.ts#L57-L83)

The roles held on a specific assignment. N roles per assignment.

| Column          | Type        | Nullable | Notes                                                 |
|-----------------|-------------|----------|-------------------------------------------------------|
| `id`            | uuid        | NO       | PK, default `gen_random_uuid()`                       |
| `assignment_id` | uuid        | NO       | FK → `sys_user_bu_assignments.id` ON DELETE CASCADE   |
| `role_id`       | uuid        | NO       | FK → `auth_roles.id`                                  |
| `created_at`    | timestamptz | NO       | Default `now()`                                       |

`UNIQUE (assignment_id, role_id)`.

### `sys_bu_module_instance_link` (inferred)

Which modules are "installed" on a BU.

| Column               | Type        | Nullable | Notes                                                |
|----------------------|-------------|----------|------------------------------------------------------|
| `id`                 | uuid        | NO       | PK                                                   |
| `business_unit_id`   | uuid        | NO       | FK → `sys_business_units.id`                         |
| `module_instance_id` | uuid        | NO       | FK → `sys_module_instances.id`                       |
| `link_type`          | varchar(20) | NO       | `'owner'` or `'child'`                               |
| `created_at`         | timestamptz | NO       | Default `now()`                                      |

The root org BU holds `link_type='owner'` links to every module instance. Descendants inherit via ancestor walk at request time.

### `sys_org_members` (VIEW — do not write to it)

Source: [`temp/migrate-bu-assignments-split.ts:155-187`](../cerp-backend/temp/migrate-bu-assignments-split.ts#L155-L187)

This was a real table before the multi-role refactor. It is now a **read-only view** stitched together from `sys_user_bu_assignments` + `sys_user_bu_assignment_roles`. Legacy read queries still work; every new write must hit the two underlying tables directly.

Shape:

```sql
CREATE VIEW sys_org_members AS
SELECT r.id, a.user_id, r.role_id, a.business_unit_id, a.designation_id,
       a.is_primary, a.is_active, a.joined_at, a.updated_at
  FROM sys_user_bu_assignments a
  JOIN sys_user_bu_assignment_roles r ON r.assignment_id = a.id
UNION ALL
SELECT a.id, a.user_id, NULL, a.business_unit_id, a.designation_id,
       a.is_primary, a.is_active, a.joined_at, a.updated_at
  FROM sys_user_bu_assignments a
 WHERE NOT EXISTS (SELECT 1 FROM sys_user_bu_assignment_roles r2 WHERE r2.assignment_id = a.id);
```

---

## Part 2 — Roles, Groups & ACL

### `auth_roles` (inferred)

Individual roles. Seeded per tenant.

| Column         | Type         | Nullable | Notes                                 |
|----------------|--------------|----------|---------------------------------------|
| `id`           | uuid         | NO       | PK                                    |
| `name`         | varchar(100) | NO       | UNIQUE. Machine code, e.g. `'owner'`  |
| `label`        | varchar(255) | NO       | Display, e.g. `'Owner'`               |
| `description`  | text         | YES      |                                       |
| `is_active`    | boolean      | NO       | Default `true`                        |
| `created_at`   | timestamptz  | NO       | Default `now()`                       |
| `updated_at`   | timestamptz  | NO       | Default `now()`                       |

Cash-book relevant rows:

| name                    | label                 | Purpose                                  |
|-------------------------|-----------------------|------------------------------------------|
| `owner`                 | Owner                 | Seeded for org creator on signup         |
| `site_cash_clerk`       | Site Cash Clerk       | Disburses cash, reviews expense vouchers |
| `site_cash_custodian`   | Site Cash Custodian   | Acknowledges cash, submits expenses      |

### `auth_role_groups` (inferred)

Buckets used for permission inheritance.

| Column       | Type         | Nullable | Notes                              |
|--------------|--------------|----------|------------------------------------|
| `id`         | uuid         | NO       | PK                                 |
| `name`       | varchar(100) | NO       | UNIQUE. e.g. `'Owner'`, `'Admin'`  |
| `description`| text         | YES      |                                    |
| `is_active`  | boolean      | NO       | Default `true`                     |

The `'Owner'` group is special — any role in it short-circuits every BU/ACL check via `isUserOwner()`.

### `auth_role_group_map` (inferred)

Junction.

| Column          | Type        | Nullable | Notes                                                |
|-----------------|-------------|----------|------------------------------------------------------|
| `id`            | uuid        | NO       | PK                                                   |
| `role_id`       | uuid        | NO       | FK → `auth_roles.id` ON DELETE CASCADE               |
| `role_group_id` | uuid        | NO       | FK → `auth_role_groups.id` ON DELETE CASCADE         |
| `created_at`    | timestamptz | NO       | Default `now()`                                      |

`UNIQUE (role_id, role_group_id)`.

### `perm_entities` (inferred)

The things you can ACL-protect — one row per entity, per module.

| Column       | Type         | Nullable | Notes                                   |
|--------------|--------------|----------|-----------------------------------------|
| `id`         | uuid         | NO       | PK                                      |
| `module_id`  | integer      | NO       | FK → `sys_system_module.id`             |
| `code`       | varchar(100) | NO       | e.g. `'cash_advance'`                   |
| `name`       | varchar(255) | NO       | Display                                 |
| `table_name` | varchar(255) | YES      | Underlying DB table (optional)          |
| `created_at` | timestamptz  | YES      | Default `now()`                         |

Cash-book entities (seeded by site-cash ACL seed): `cash_advance`, `expense_voucher`, `accounting_period`.

### `perm_fields` (inferred)

Fields for field-level ACL. Supports composites (nested fields).

| Column            | Type         | Nullable | Notes                                             |
|-------------------|--------------|----------|---------------------------------------------------|
| `id`              | uuid         | NO       | PK                                                |
| `entity_id`       | uuid         | NO       | FK → `perm_entities.id`                           |
| `code`            | varchar(100) | NO       | e.g. `'amount'`                                   |
| `name`            | varchar(255) | NO       |                                                   |
| `field_type`      | varchar(50)  | NO       | `'simple'` or `'composite'`                       |
| `data_type`       | varchar(50)  | NO       | `'text'`/`'numeric'`/`'uuid'`/`'boolean'`/`'date'`|
| `is_composite`    | boolean      | YES      | Default `false`                                   |
| `parent_field_id` | uuid         | YES      | FK → `perm_fields.id` for nested fields           |
| `sort_order`      | integer      | NO       |                                                   |

### `perm_states` (inferred)

Workflow states per entity.

| Column       | Type         | Nullable | Notes                                       |
|--------------|--------------|----------|---------------------------------------------|
| `id`         | uuid         | NO       | PK                                          |
| `entity_id`  | uuid         | NO       | FK → `perm_entities.id`                     |
| `code`       | varchar(100) | NO       | e.g. `'pending'`, `'acknowledged'`          |
| `name`       | varchar(255) | NO       |                                             |
| `is_initial` | boolean      | NO       | Default `false`                             |
| `is_final`   | boolean      | NO       | Default `false`                             |
| `sort_order` | integer      | NO       |                                             |

### `perm_state_transitions` (inferred)

Which state transitions are legal.

| Column            | Type         | Nullable | Notes                                       |
|-------------------|--------------|----------|---------------------------------------------|
| `id`              | uuid         | NO       | PK                                          |
| `entity_id`       | uuid         | NO       | FK → `perm_entities.id`                     |
| `from_state_id`   | uuid         | YES      | FK → `perm_states.id`. NULL = any state     |
| `to_state_id`     | uuid         | NO       | FK → `perm_states.id`                       |
| `transition_code` | varchar(100) | NO       | e.g. `'acknowledge'`                        |
| `transition_name` | varchar(255) | NO       |                                             |

### `perm_actions` (inferred)

Action buttons that can be ACL-gated.

| Column        | Type         | Nullable | Notes                                        |
|---------------|--------------|----------|----------------------------------------------|
| `id`          | uuid         | NO       | PK                                           |
| `entity_id`   | uuid         | NO       | FK → `perm_entities.id`                      |
| `code`        | varchar(100) | NO       | e.g. `'create'`, `'cancel'`                  |
| `name`        | varchar(255) | NO       |                                              |
| `action_type` | varchar(50)  | NO       | `'button'` or `'workflow'`                   |
| `icon`        | varchar(100) | YES      | Lucide icon name                             |
| `sort_order`  | integer      | NO       |                                              |

### `perm_access_control_lists`

Source: [`temp/migrate-acl-group-inheritance.ts:34-75`](../cerp-backend/temp/migrate-acl-group-inheritance.ts#L34-L75)

**The heart of the permission system.** Every allow/deny lives here.

| Column          | Type         | Nullable | Notes                                                    |
|-----------------|--------------|----------|----------------------------------------------------------|
| `id`            | uuid         | NO       | PK                                                       |
| `entity_id`     | uuid         | NO       | FK → `perm_entities.id`                                  |
| `role_id`       | uuid         | YES      | FK → `auth_roles.id` ON DELETE CASCADE. **XOR** with next|
| `role_group_id` | uuid         | YES      | FK → `auth_role_groups.id` ON DELETE CASCADE. **XOR**    |
| `state_id`      | uuid         | YES      | FK → `perm_states.id`. NULL = applies to all states      |
| `access_type`   | varchar(50)  | NO       | `'field'`, `'action'`, `'state'`, `'transition'`         |
| `target_code`   | varchar(100) | NO       | Field / action / transition code                         |
| `permission`    | varchar(50)  | NO       | `'allowed'`, `'disabled'`, `'hidden'`, `'read'`, `'write'`|
| `created_at`    | timestamptz  | YES      | Default `now()`                                          |

**Constraints:**

- `CHECK ((role_id IS NULL) <> (role_group_id IS NULL))` — exactly one target set
- `UNIQUE (entity_id, role_id, state_id, access_type, target_code) WHERE role_id IS NOT NULL`
- `UNIQUE (entity_id, role_group_id, state_id, access_type, target_code) WHERE role_group_id IS NOT NULL`

**Resolution order** (see [`middleware/requireAcl.ts`](../cerp-backend/src/middleware/requireAcl.ts)):

1. Owner short-circuit → always allow
2. Walk BU ancestry, find nearest assignment, collect all roles on it
3. Query rows matching `(entity_id, role_id IN user_roles, state, action/field)` — **role-level wins**
4. Fall back to `(entity_id, role_group_id IN groups_of_user_roles, ...)` — **group-level**
5. Explicit `'disabled'` anywhere in the matched set beats `'allowed'` → deny
6. No rows at all → **default allow**

---

## Part 3 — Finance Tables (`fin_*`)

### `fin_chart_of_accounts`

Source: [`temp/migrate-chart-of-accounts.ts:24-51`](../cerp-backend/temp/migrate-chart-of-accounts.ts#L24-L51)

Hierarchical 4-level COA.

| Column              | Type         | Nullable | Notes                                                                |
|---------------------|--------------|----------|----------------------------------------------------------------------|
| `account_id`        | uuid         | NO       | PK, default `gen_random_uuid()`                                      |
| `parent_account_id` | uuid         | YES      | FK → self, ON DELETE SET NULL                                        |
| `account_code`      | varchar(20)  | NO       |                                                                      |
| `account_name`      | varchar(100) | NO       |                                                                      |
| `level`             | integer      | NO       | `CHECK (level BETWEEN 1 AND 4)`                                      |
| `account_type`      | varchar(30)  | NO       | `'asset'`/`'liability'`/`'equity'`/`'income'`/`'expense'`            |
| `is_leaf`           | boolean      | YES      | Default `true`                                                       |
| `is_active`         | boolean      | YES      | Default `true`                                                       |
| `created_at`        | timestamptz  | YES      | Default `now()`                                                      |

`UNIQUE (parent_account_id, account_code)`.

### `fin_bank_accounts`

Source: [`temp/migrate-bank-accounts.ts:33-57`](../cerp-backend/temp/migrate-bank-accounts.ts#L33-L57)

Site-scoped bank accounts used as a transfer destination on cash advances.

| Column               | Type         | Nullable | Notes                                                  |
|----------------------|--------------|----------|--------------------------------------------------------|
| `id`                 | uuid         | NO       | PK                                                     |
| `site_bu_id`         | uuid         | NO       | Points to a site-level `sys_business_units.id`         |
| `account_name`       | varchar(100) | NO       |                                                        |
| `account_number`     | varchar(50)  | NO       |                                                        |
| `account_holder_name`| varchar(100) | YES      |                                                        |
| `bank_name`          | varchar(100) | YES      |                                                        |
| `branch`             | varchar(100) | YES      |                                                        |
| `branch_code`        | varchar(20)  | YES      |                                                        |
| `currency`           | varchar(10)  | NO       | Default `'LKR'`                                        |
| `is_active`          | boolean      | NO       | Default `true`                                         |
| `created_by`         | uuid         | YES      |                                                        |
| `created_at`         | timestamptz  | NO       | Default `now()`                                        |
| `updated_at`         | timestamptz  | NO       | Default `now()`                                        |

`UNIQUE (site_bu_id, account_number)`.
Index: `(site_bu_id) WHERE is_active = true`.

### `fin_site_cash_accounting_periods`

Source: [`temp/migrate-site-cash.ts:35-57`](../cerp-backend/temp/migrate-site-cash.ts#L35-L57)

Open/closed periods per site.

| Column         | Type         | Nullable | Notes                                            |
|----------------|--------------|----------|--------------------------------------------------|
| `id`           | uuid         | NO       | PK                                               |
| `site_bu_id`   | uuid         | NO       |                                                  |
| `period_name`  | varchar(100) | NO       |                                                  |
| `start_date`   | date         | NO       |                                                  |
| `end_date`     | date         | NO       |                                                  |
| `status`       | varchar(20)  | NO       | `'open'`/`'closed'`/`'locked'`. Default `'open'` |
| `closed_by`    | uuid         | YES      |                                                  |
| `closed_at`    | timestamptz  | YES      |                                                  |
| `created_by`   | uuid         | NO       |                                                  |
| `created_at`   | timestamptz  | NO       | Default `now()`                                  |
| `updated_at`   | timestamptz  | NO       | Default `now()`                                  |

`UNIQUE (site_bu_id, start_date, end_date)`.

### `fin_site_cash_advances`

Source: [`temp/migrate-site-cash.ts:62-92`](../cerp-backend/temp/migrate-site-cash.ts#L62-L92) + [`migrate-bank-accounts.ts:60-93`](../cerp-backend/temp/migrate-bank-accounts.ts#L60-L93)

Clerk → custodian cash disbursement.

| Column               | Type           | Nullable | Notes                                                                |
|----------------------|----------------|----------|----------------------------------------------------------------------|
| `id`                 | uuid           | NO       | PK                                                                   |
| `site_bu_id`         | uuid           | NO       | Site-level BU                                                        |
| `custodian_id`       | uuid           | NO       | The user receiving the cash                                          |
| `disbursed_by`       | uuid           | NO       | The clerk                                                            |
| `amount`             | numeric(15,2)  | NO       | `CHECK (amount > 0)`                                                 |
| `currency`           | varchar(10)    | NO       | Default `'LKR'`                                                      |
| `reference_no`       | varchar(50)    | YES      |                                                                      |
| `description`        | text           | YES      |                                                                      |
| `status`             | varchar(20)    | NO       | `'pending'`/`'acknowledged'`/`'cancelled'`. Default `'pending'`      |
| `acknowledged_at`    | timestamptz    | YES      |                                                                      |
| `period_id`          | uuid           | YES      | FK → `fin_site_cash_accounting_periods.id` ON DELETE SET NULL        |
| `method_of_transfer` | varchar(20)    | YES      | `'cash'`/`'online'`/`'cdm'`/`'cheque'`/`'counter'`                   |
| `bank_account_id`    | uuid           | YES      | FK → `fin_bank_accounts.id` ON DELETE SET NULL                       |
| `receipt_url`        | text           | YES      |                                                                      |
| `created_at`         | timestamptz    | NO       | Default `now()`                                                      |
| `updated_at`         | timestamptz    | NO       | Default `now()`                                                      |

### `fin_site_expense_vouchers`

Source: [`temp/migrate-site-cash.ts:96-130`](../cerp-backend/temp/migrate-site-cash.ts#L96-L130)

Custodian submits expense, clerk approves/rejects.

| Column              | Type           | Nullable | Notes                                                                |
|---------------------|----------------|----------|----------------------------------------------------------------------|
| `id`                | uuid           | NO       | PK                                                                   |
| `voucher_no`        | varchar(50)    | NO       |                                                                      |
| `site_bu_id`        | uuid           | NO       |                                                                      |
| `custodian_id`      | uuid           | NO       |                                                                      |
| `expense_date`      | date           | NO       |                                                                      |
| `category`          | varchar(50)    | YES      |                                                                      |
| `description`       | text           | NO       |                                                                      |
| `amount`            | numeric(15,2)  | NO       | `CHECK (amount > 0)`                                                 |
| `currency`          | varchar(10)    | NO       | Default `'LKR'`                                                      |
| `receipt_url`       | text           | YES      |                                                                      |
| `status`            | varchar(20)    | NO       | `'submitted'`/`'approved'`/`'rejected'`. Default `'submitted'`       |
| `reviewed_by`       | uuid           | YES      | Clerk who approved/rejected                                          |
| `reviewed_at`       | timestamptz    | YES      |                                                                      |
| `rejection_reason`  | text           | YES      |                                                                      |
| `period_id`         | uuid           | YES      | FK → `fin_site_cash_accounting_periods.id` ON DELETE SET NULL        |
| `coa_account_id`    | uuid           | YES      | FK → `fin_chart_of_accounts.account_id`                              |
| `created_at`        | timestamptz    | NO       | Default `now()`                                                      |
| `updated_at`        | timestamptz    | NO       | Default `now()`                                                      |

### `fin_site_cash_imprest_ledger`

Source: [`temp/migrate-site-cash.ts:135-175`](../cerp-backend/temp/migrate-site-cash.ts#L135-L175)

Running debit/credit ledger per custodian per site.

| Column            | Type           | Nullable | Notes                                                                 |
|-------------------|----------------|----------|-----------------------------------------------------------------------|
| `id`              | uuid           | NO       | PK                                                                    |
| `site_bu_id`      | uuid           | NO       |                                                                       |
| `custodian_id`    | uuid           | NO       |                                                                       |
| `txn_date`        | date           | NO       |                                                                       |
| `txn_type`        | varchar(20)    | NO       | `'advance_in'`/`'expense_out'`/`'adjustment'`                         |
| `reference_id`    | uuid           | YES      | The `cash_advance` or `expense_voucher` row id                        |
| `reference_type`  | varchar(30)    | YES      | `'cash_advance'`/`'expense_voucher'` (or NULL for adjustments)        |
| `debit`           | numeric(15,2)  | NO       | Default `0`                                                           |
| `credit`          | numeric(15,2)  | NO       | Default `0`                                                           |
| `running_balance` | numeric(15,2)  | NO       | Snapshot of custodian's balance AFTER this row                        |
| `description`    | text           | YES      |                                                                       |
| `period_id`       | uuid           | YES      | FK → `fin_site_cash_accounting_periods.id` ON DELETE SET NULL         |
| `created_at`      | timestamptz    | NO       | Default `now()`                                                       |

Indexes:
- `(custodian_id, site_bu_id)`
- `(txn_date DESC)`

### `fin_site_cash_notifications`

Source: [`temp/migrate-site-cash.ts:179-210`](../cerp-backend/temp/migrate-site-cash.ts#L179-L210)

Event inbox for clerks and custodians.

| Column            | Type         | Nullable | Notes                                                                                     |
|-------------------|--------------|----------|-------------------------------------------------------------------------------------------|
| `id`              | uuid         | NO       | PK                                                                                        |
| `recipient_id`    | uuid         | NO       |                                                                                           |
| `sender_id`       | uuid         | YES      |                                                                                           |
| `site_bu_id`      | uuid         | NO       |                                                                                           |
| `type`            | varchar(40)  | NO       | `'advance_received'`/`'advance_acknowledged'`/`'expense_submitted'`/`'expense_approved'`/`'expense_rejected'` |
| `reference_id`    | uuid         | NO       |                                                                                           |
| `reference_type`  | varchar(30)  | NO       | `'cash_advance'`/`'expense_voucher'`                                                      |
| `title`           | varchar(200) | NO       |                                                                                           |
| `message`         | text         | YES      |                                                                                           |
| `is_read`         | boolean      | NO       | Default `false`                                                                           |
| `read_at`         | timestamptz  | YES      |                                                                                           |
| `created_at`      | timestamptz  | NO       | Default `now()`                                                                           |

Index: `(recipient_id, is_read)`.

---

## How it fits together (the dev flow)

### When a request lands on the backend

1. **JWT middleware** reads the cookie → `{ userId, organizationId, businessUnitId, isOwner }`.
2. **Tenant middleware** picks the right tenant pool from `organizationId`.
3. **`requireAcl(module, entity, action)`** middleware:
   - `isUserOwner()`? → allow.
   - Otherwise recursive CTE up `sys_business_units.parent_id` from current BU, find nearest ancestor with a row in `sys_user_bu_assignments` for this user (respect `effective_from`/`effective_to`).
   - Collect all `role_id`s from `sys_user_bu_assignment_roles` at that ancestor.
   - Look up `perm_access_control_lists` rows matching (entity, state, target). Role-level first, then group-level via `auth_role_group_map`.
   - Apply deny-wins logic.
4. Route handler runs with the guarantee that `req.user` has permission.

### When building a new feature

For each new entity you want to ACL-gate:

1. Pick a **module code** and an **entity code** (e.g. `module='finance'`, `entity='bank_account'`).
2. Add a seed file under `cerp-backend/temp/seed-<module>-acl.ts` that:
   - Inserts into `perm_entities` (the entity itself).
   - Inserts the relevant `perm_fields`, `perm_states`, `perm_state_transitions`, `perm_actions`.
   - Inserts default rows in `perm_access_control_lists` to grant roles access (or leave empty — default is allow).
3. Run the seed against every tenant DB + `template_tenant_db`.
4. Wrap mutating routes in `requireAcl('<module>', '<entity>', '<action>')`.
5. Frontend uses `useEntityAcl(moduleCode, entityCode)` hook to fetch the resolved permissions and gate UI.

### When adding a BU-level feature

Never filter by `user_id` in your business-data queries. Filter by **`business_unit_id`**, then use helpers from [`utils/aclIntrospection.ts`](../cerp-backend/src/utils/aclIntrospection.ts):

- `isUserOwner(pool, userId)` → bypass everything
- `getAccessibleBuIds(pool, userId)` → all BUs the user can reach (owner = all; others = assigned only)
- `getAccessibleBuIdsByType(pool, userId, 'site')` → filter by `bu_type`
- `getUserRolesForBu(pool, userId, buId)` → ancestor-walked role list for a specific BU

---

## Gotchas

1. **`sys_org_members` is a view.** Never `INSERT`/`UPDATE`/`DELETE` against it. Use `sys_user_bu_assignments` + `sys_user_bu_assignment_roles`.
2. **`is_primary` is global per user, not per-org-or-BU.** One user has exactly zero or one primary assignments across the entire tenant.
3. **Ancestor walk does NOT union parent + child roles.** If a user is assigned to both Division and a Site under it, selecting the Site uses only the Site's roles. The Division roles are shadowed. This is by design — more-specific assignments override less-specific ones.
4. **The Owner group grants tenant-wide access.** Do not put roles in the `Owner` group unless you want them to bypass every ACL check.
5. **`site_bu_id` always means a BU where `LOWER(level) = 'site'`.** Backend endpoints will 400 if you pass a project/division id.
6. **ACL default is allow.** If no `perm_access_control_lists` row matches, access is granted. To deny, you must insert an explicit `'disabled'` row.
7. **Migrations must target all tenant DBs + `template_tenant_db`.** Copy the iteration pattern from `migrate-site-cash.ts`. Don't forget the template — new orgs cloned from it will silently miss your change.
8. **ACL seeds are per-tenant too.** Adding a new entity to `perm_entities` means inserting the rows in every tenant DB AND the template.

---

## Quick map for the cash-book app

When building a screen in the Android app, this is the mental path:

```
User opens screen
      │
      ▼
Which BU is active?  ─────►  session.businessUnitId  (set by /site/select-unit)
      │
      ▼
What can this user do on this BU?  ─────►  GET /site-cash/my-role
      │                                    (→ "clerk" / "custodian" / "both" / null)
      ▼
Show tiles gated by cashRole
      │
      ▼
User taps a tile → calls /site-cash/* endpoint
      │
      ▼
Backend: requireAcl('finance', 'cash_advance', 'create')
         → ancestor walk → role check → allow/deny
      │
      ▼
If allowed: INSERT INTO fin_site_cash_advances (...)
            INSERT INTO fin_site_cash_imprest_ledger (...)
            INSERT INTO fin_site_cash_notifications (...)
```

If you remember nothing else: **data lives in `fin_*` tables, permissions live in `perm_*` tables, and "who is this user" lives in `sys_user_bu_assignments` + `sys_user_bu_assignment_roles`.** Everything else is scaffolding around those three groups.
