# REST API Reference Pattern — Design Document

## Vision

### The big idea

Build a **universal application lifecycle platform** — a production-grade, cloud-native system
that handles any domain where something needs to be applied for, reviewed, approved, and searched.

The pattern is universal:

| Domain | Application | Submit | Review | Approve | Search |
|---|---|---|---|---|---|
| Banking | Loan application | Applicant | Credit team | ✓ | Approved loans |
| Government | Company registration | Director | Registrar | ✓ | Company search |
| Insurance | Policy application | Customer | Underwriter | ✓ | Active policies |
| Insurance | Claim | Claimant | Assessor | ✓ | Claim history |
| Telco | Service registration | Customer | Provisioning | ✓ | Active services |
| Healthcare | Patient registration | Patient | Admin | ✓ | Patient records |
| Immigration | Visa application | Applicant | Officer | ✓ | Approved visas |

**The core lifecycle is identical across all domains:**
```
Draft → Submit → Review → Approve / Return → Resubmit → Approved → Search → View → Export
```

Only the domain model (fields, entities, business rules) changes.

### What we are building

**Phase 1 — Loan Platform (reference implementation)**
A complete, production-grade loan application system built as microservices.
This proves the pattern works and serves as the portfolio demo.
No abstraction yet — build it concretely and learn what the real common parts are.

**Phase 2 — Agentic AI Code Generator**
An AI agent that reads the loan platform as a reference and generates a new domain
(mortgage, insurance claim, company registration) following the exact same pattern.
Input: a data model + business rules. Output: production-ready code + PR.

**Phase 3 — Mortgage Platform (second domain)**
Build a mortgage application system using the same pattern as loan.
The common parts between loan and mortgage become obvious at this point.
Do NOT abstract prematurely — let the two implementations reveal what truly belongs in core.

**Phase 4 — Core Platform (extract, don't design upfront)**
After loan + mortgage exist, extract the genuinely common parts into `core-platform`:

```
core-platform/
  core-lifecycle/       ← state machine: Draft→Submit→Review→Approve/Return→Resubmit
  core-workqueue/       ← generic work queue (any domain)
  core-notification/    ← Kafka consumer → email/SMS (any domain)
  core-document/        ← S3 upload, PDF template engine (any domain)
  core-search/          ← Elasticsearch patterns (any domain)
  core-api/             ← shared OpenAPI schemas (Address, Person, Document, Status)
  core-base/            ← base Spring components (controller, validator, repository)
  core-events/          ← standard Kafka event contracts all domains extend
  core-infra/           ← Docker Compose base, GitHub Actions templates
```

Domain platforms extend core:
```
loan-platform      extends core-platform  + adds loan domain
mortgage-platform  extends core-platform  + adds mortgage domain
insurance-platform extends core-platform  + adds insurance domain
```

This is the same pattern as Verne:
- `catalyst-ng` = core-platform
- `companies`, `ppsr`, `business-names` = domain platforms

**Phase 5 — Universal Platform Product**
The platform becomes domain-configurable — define your model and rules, get the full
lifecycle out of the box. Delivered as consulting or SaaS.

### Extensible workflow (payment and other steps)

The workflow state machine must be **extensible without modifying existing services**.

Different domains attach payment (and other steps) at different points:

| Domain | Payment position | Pattern |
|---|---|---|
| Company registration | Before submission | Pay → then submit |
| Loan application | After approval | Approve → pay → activate |
| Insurance | After approval | External recurring billing |
| Visa application | Before submission | Pay → then submit |

**Design principle: event-driven steps**

New workflow steps are added by publishing and consuming Kafka events — never by modifying existing services. This is the **open/closed principle at the architecture level**.

```
Example: adding payment after loan approval (future)

loan-review-service   publishes → LoanApplicationApproved
payment-service       consumes  → initiates payment
                      publishes → PaymentReceived / PaymentFailed
loan-application-service consumes → status = ACTIVE / PAYMENT_FAILED
```

Adding the payment step touches zero existing code — just a new `payment-service`.

**For Phase 1 (loan platform):** payment is out of scope — design the status lifecycle to leave room for it:
```
DRAFT → SUBMITTED → REVISION_REQUIRED → APPROVED → ACTIVE
                                                  ↑
                                    (payment step slots in here in future)
```

**For core-platform (Phase 4):** payment position becomes a domain configuration — each domain declares where in the lifecycle payment is required and which model applies (pre-submission, post-approval, external billing).

**Rule for Phase 1:** never hardcode the lifecycle transitions as a fixed enum sequence. Use a status field + event-driven transitions so steps can be inserted between any two states with no existing code changes.

---

### Why this is valuable
- Every regulated industry needs this lifecycle
- Generic frameworks (Spring Boot etc.) give you the tools but not the pattern
- This platform encodes the pattern — domain teams plug in their model and go
- The agentic AI makes it practical at scale — 100 domains, not 1

---

## Microservice Architecture

Each service is independently deployable, owns its own data, and communicates via Kafka events.
No direct service-to-service REST calls — all inter-service communication is event-driven.

```
                        ┌─────────────────────────────────────────┐
                        │           API Gateway (nginx)            │
                        └──────┬──────────┬──────────┬────────────┘
                               │          │          │
               ┌───────────────┘    ┌─────┘    ┌────┘
               ▼                    ▼           ▼
  ┌─────────────────────┐  ┌──────────────┐  ┌───────────────────┐
  │ loan-application-   │  │ loan-review- │  │  loan-search-     │
  │ service             │  │ service      │  │  service          │
  │                     │  │              │  │                   │
  │ Submit/draft/resubmit│  │ Work queue   │  │ Elasticsearch     │
  │ PostgreSQL (own DB) │  │ Approve      │  │ queries           │
  └──────────┬──────────┘  │ Return       │  └───────────────────┘
             │             │ PostgreSQL   │
             │             │ (own DB)     │  ┌───────────────────┐
             │             └──────────────┘  │ loan-document-    │
             │                               │ service           │
             │        ┌──────────────┐       │ S3 upload/download│
             │        │notification- │       │ PDF export        │
             │        │service       │       │ PostgreSQL        │
             │        │ Email / SMS  │       └───────────────────┘
             │        └──────────────┘
             │
             ▼
    ┌─────────────────┐
    │   Apache Kafka  │  ← all inter-service communication
    └─────────────────┘
```

### Services

| Service | Responsibility | Database | Port |
|---|---|---|---|
| `loan-application-service` | Submit, draft, resubmit | PostgreSQL (loans DB) | 8081 |
| `loan-review-service` | Work queue, approve, return for revision | PostgreSQL (review DB) | 8082 |
| `loan-search-service` | Public + staff search | Elasticsearch | 8083 |
| `loan-document-service` | S3 upload, download, PDF export | PostgreSQL (docs DB) | 8084 |
| `notification-service` | Email/SMS on status change | None (stateless) | 8085 |
| `loan-ui` | React staff + public UI | — | 3000 |

### Inter-service communication via Kafka

```
loan-application-service  publishes → LoanApplicationSubmitted
  loan-review-service     consumes  → adds to work queue
  notification-service    consumes  → sends "application received" email

loan-review-service       publishes → LoanApplicationApproved
  loan-search-service     consumes  → indexes in Elasticsearch (public)
  notification-service    consumes  → sends "approved" email to applicant

loan-review-service       publishes → LoanApplicationReturnedForRevision
  notification-service    consumes  → sends "revision required" email + reason

loan-application-service  publishes → LoanApplicationResubmitted
  loan-review-service     consumes  → re-adds to work queue
  notification-service    consumes  → sends "resubmission received" email
```

**Rule:** Services never call each other directly via REST.
All state propagation happens through Kafka events.
This ensures loose coupling and independent deployability.

### Each service follows the same internal pattern
```
Controller  ← HTTP boundary
Service     ← business logic, @Transactional
Validator   ← submit-scope rules (loan-application-service only)
Repository  ← Spring Data JPA
Entity      ← JPA entities
KafkaPublisher ← publishes domain events
KafkaConsumer  ← consumes events from other services (where applicable)
```

---

## Core Design Principles

### 1. Contract-First (OpenAPI)
- OpenAPI YAML is the source of truth — written before any code
- Contract defines structure ONLY (see section below)
- Implementation follows the contract, never the reverse

### 2. Structure-Only Contract (No Validation Rules in Schema)

**The rule:** OpenAPI schema contains type/format/enum — but NOT minLength, maxLength, required,
minItems, pattern, or any business rule constraints.

**Why:** Schema validation is payload-level. Business validation is state-level. These are
fundamentally different concerns and must live in different layers.

| Scenario | Why structure-only is required |
|---|---|
| Incremental draft save | Only fields present are saved — nothing is "missing" |
| PATCH resubmission (delta) | Delta contains only what changed — mandatory rules must not apply |
| Maintenance updates | Same — client sends only the changed fields |
| Migration of legacy data | Legacy data may not have all fields |

**The fundamental rule:**
> You cannot enforce mandatory/minItems/minLength rules on a delta payload.
> These rules are meaningful only when validated against the COMPLETE merged state at submission time.

**What IS safe in the schema:**
- `type: string / integer / boolean / array / object`
- `format: date / email / uuid`
- `enum` values (wrong enum = wrong type, always invalid regardless of context)
- `x-` extension metadata (see Validation Metadata section)

### 3. Two-Layer Validation

```
HTTP Request
    ↓
OpenAPI schema validation (400)     ← structural only: wrong type, invalid enum
    ↓
Save/PATCH endpoint                 ← no mandatory validation, just persist delta
    ↓
Submit endpoint                     ← full business rule validation (422)
                                       applied to COMPLETE merged state
```

**Save-time validation (non-blocking):**
- Structure only (OpenAPI handles this at the HTTP layer)
- Optional: warnings for obviously invalid data (format checks)

**Submit-time validation (blocking — 422):**
- All mandatory rules
- Cross-field rules (e.g. "if hasOwnConstitution=true, at least one constitution document required")
- Cross-collection rules (e.g. "every shareholder must appear in at least one allocation")
- Format rules (email RFC, phone digits, IRD check-digit)
- Contextual mandatory (e.g. "consent document only required for new registrations, not migrations")

### 4. Submit / Resubmit Pattern

```
POST   /api/v1/{resource}              — create + submit (one-shot)
POST   /api/v1/{resource}/drafts       — create draft (save without submit)
GET    /api/v1/{resource}/drafts/{id}  — retrieve draft
PATCH  /api/v1/{resource}/drafts/{id}  — update draft (delta only)
POST   /api/v1/{resource}/drafts/{id}/submit    — submit draft
POST   /api/v1/{resource}/{id}/resubmit         — resubmit after rejection (delta)
GET    /api/v1/{resource}/{id}/status  — poll current status
```

**PATCH semantics (delta only):**
- Absent field = unchanged (do not touch)
- Explicit null = clear the value
- Present value = update

This supports incremental save, resubmission with partial payload, and maintenance updates
without the client resending the entire state.

### 5. Validation Metadata Endpoint

Clients should be able to discover validation rules without reading code.
Two complementary approaches:

**Runtime endpoint:**
```
GET /api/v1/{resource}/validation-rules
```
Returns:
```json
{
  "submitRules": [
    { "field": "person.firstName", "rule": "required", "message": "First name is required on submission" },
    { "field": "directors", "rule": "minItems:1", "message": "At least one director is required" }
  ],
  "saveRules": [
    { "field": "person.firstName", "rule": "maxLength:200", "message": "First name max 200 characters" }
  ]
}
```

**OpenAPI extensions (documentation layer):**
```yaml
firstName:
  type: string
  x-submit-required: true
  x-max-length: 200
  x-validation-message: "First name is required on submission"
```

`x-` extensions are metadata only — not enforced by the schema validator.
Client reads the spec; the extensions document the submit rules without breaking delta semantics.

---

## Application Layer Architecture

### Standard Layered Pattern

```
Controller          — HTTP boundary: deserialise, validate structure, delegate
    ↓
Service             — business logic, @Transactional, orchestration
    ↓
Validator           — submit-scope business rules, throws 422 on violation
    ↓
Repository          — JPA/data access, no business logic
    ↓
Entity / Domain     — JPA entities, value objects
```

### Controller responsibilities (only):
- Deserialise request payload
- Delegate to service
- Serialise response
- No business logic, no validation logic

### Service responsibilities:
- `@Transactional` boundary
- Orchestrate repositories
- Apply delta merge (PATCH semantics)
- Call validator before submit
- No HTTP concern

### Validator responsibilities:
- Validate COMPLETE merged state at submit time
- Return flat list of violations `{ field, code, message, severity }`
- Blocking (ERROR) vs non-blocking (WARNING) violations
- Throw `ValidationException` on any ERROR violations → caught by `@ControllerAdvice` → 422

### Error response standard:
```json
// 400 — schema validation failure
{ "errors": [{ "field": "/directors/0/person/firstName", "message": "string expected" }] }

// 422 — business rule violation
{ "violations": [{ "field": "directors[0].person.firstName", "code": "required", "message": "First name is required" }] }

// 404
{ "error": "NOT_FOUND", "message": "Draft not found: abc-123" }

// 409
{ "error": "CONFLICT", "message": "Cannot resubmit — current status is 'approved', expected 'declined'" }
```

---

## Complete System Lifecycle

```
APPLICANT (external)                    STAFF (internal)
─────────────────────────────────────────────────────────────────

POST /loan-applications                 GET /work-queue
  → create + submit                       → lists all SUBMITTED applications
  → Kafka: LoanApplicationSubmitted       → paginated, filterable
  → indexed in Elasticsearch             → each item: applicant, amount, date

                                        GET /work-queue/{id}
                                          → full submitted details
                                          → all sections: applicant, assets,
                                            liabilities, loan terms, documents

                                        POST /loan-applications/{id}/approve
                                          → status → APPROVED
                                          → Kafka: LoanApplicationApproved
                                          → Elasticsearch: indexed as public

                                        POST /loan-applications/{id}/return-for-revision
                                          → status → REVISION_REQUIRED
                                          → body: { reason, comments }
                                          → Kafka: LoanApplicationReturnedForRevision

POST /loan-applications/{id}/resubmit   ← applicant receives notification
  → delta payload only                  ← re-appears in work queue
  → re-submit for review

─────────────────────────────────────────────────────────────────
AFTER APPROVAL (public read path)

GET /loans/search?q=John+Smith          → Elasticsearch full-text search
GET /loans/search?amount=50000          → filtered search
GET /loans/{id}                         → full loan details (all tabs)
GET /loans/{id}/export/pdf              → PDF: loan summary + approval details
```

---

## Internal Work Queue

Staff-facing endpoints for reviewing submitted applications:

```
GET  /work-queue                        — list submitted applications (paginated)
GET  /work-queue/{id}                   — full submitted details
POST /loan-applications/{id}/approve    — approve → APPROVED
POST /loan-applications/{id}/return-for-revision  — return → REVISION_REQUIRED
```

**Work queue item (list view):**
```json
{
  "id": "abc-123",
  "reference": "LOAN-2026-00042",
  "applicantName": "John Smith",
  "loanAmount": 450000,
  "loanPurpose": "Home purchase",
  "submittedAt": "2026-10-10T09:30:00Z",
  "status": "SUBMITTED",
  "documentCount": 4
}
```

**Return for revision request:**
```json
{
  "reason": "INSUFFICIENT_DOCUMENTATION",
  "comments": "Please provide last 3 months of bank statements."
}
```

**Status lifecycle:**
```
DRAFT → SUBMITTED → REVISION_REQUIRED → SUBMITTED (resubmit)
                 ↓
              APPROVED
```

---

## Search (Elasticsearch)

Elasticsearch is updated via Kafka events — the loan API never writes to ES directly.

```
Kafka consumer (search-indexer):
  LoanApplicationSubmitted   → index with status=SUBMITTED (staff search only)
  LoanApplicationApproved    → update status=APPROVED (public search)
  LoanApplicationRevision    → update status=REVISION_REQUIRED
```

**Public search endpoints (approved loans only):**
```
GET /loans/search?q={text}              — full-text: name, reference, purpose
GET /loans/search?minAmount=100000      — filter by loan amount range
GET /loans/search?approvedAfter=2026-01-01  — filter by approval date
```

**Staff search (all statuses):**
```
GET /work-queue?status=SUBMITTED        — filter by status
GET /work-queue?applicantName=Smith     — filter by name
GET /work-queue?submittedAfter=2026-10-01  — filter by date
```

---

## PDF Export

```
GET /loans/{id}/export/pdf
```

Generates a PDF loan summary containing:
- Applicant details (name, address, employment)
- Co-applicants (if any)
- Loan terms (amount, purpose, term, repayment)
- Assets and liabilities summary
- Approval details (date, approved by, conditions)
- Filing reference and timestamp

**Implementation:** JasperReports or iText — generates PDF from a template populated with loan data.
Returns `Content-Type: application/pdf` with `Content-Disposition: attachment; filename=LOAN-2026-00042.pdf`.

---

## Updated Docker Compose

```yaml
services:
  postgres:
    image: postgres:16

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]

  elasticsearch:
    image: elasticsearch:8.11.0
    environment:
      discovery.type: single-node
      xpack.security.enabled: false
    ports: ["9200:9200"]

  localstack:
    image: localstack/localstack:3.0
    environment:
      SERVICES: s3,secretsmanager
```

---

## React UI

A lightweight staff-facing admin UI and public-facing search UI. Calls the REST API directly.

### Project structure
```
loan-platform/
  loan-application-service/   ← Spring Boot — submit, draft, resubmit
  loan-review-service/        ← Spring Boot — work queue, approve, return
  loan-search-service/        ← Spring Boot — Elasticsearch queries
  loan-document-service/      ← Spring Boot — S3, PDF export
  notification-service/       ← Spring Boot — email/SMS (Kafka consumer)
  loan-ui/                    ← React — staff work queue + public search
  docker-compose.yml          ← runs everything together
  .github/workflows/          ← CI/CD pipelines
```

### Tech stack

| Choice | Why |
|---|---|
| React 18 + Vite | Modern, fast build — what JDs ask for |
| TypeScript | Required in banking — type safety |
| React Query | Standard for REST data fetching, caching, loading states |
| React Router v6 | Navigation between screens |
| Tailwind CSS | Fast, utility-first styling — no design decisions |
| Axios | HTTP client with interceptors for auth headers |

### Screens

**1. Work Queue (staff)**
- Table of submitted loan applications
- Columns: reference, applicant name, loan amount, purpose, submitted date, status
- Click row → Application Detail
- Filter by status / date range

**2. Application Detail (staff)**
- Tabbed view of full submitted details:
  - Applicant tab — personal details, address, employment
  - Co-applicants tab (if any)
  - Assets & Liabilities tab
  - Loan Terms tab
  - Documents tab — list of uploaded files with download links
- Action buttons: **Approve** | **Return for Revision**
- Approve → confirmation modal → POST /approve
- Return → modal with reason dropdown + comment text → POST /return-for-revision

**3. Public Search**
- Search bar (full-text) + filters (amount range, approval date)
- Results list — approved loans only
- Click result → Loan Detail (read-only)
- **Export PDF** button → GET /export/pdf → downloads PDF

**4. Loan Detail (public, read-only)**
- Same tabbed layout as staff detail view
- No action buttons
- Export PDF button

### What this demonstrates for interviews
- React 18 + TypeScript (ticks most banking JD boxes)
- REST API integration with React Query (loading, error, success states)
- Tabbed component layout
- Modal forms (approve/return)
- File download (PDF export)
- Search with filters
- Clean separation — UI knows nothing about business logic, just calls the API

### Not in scope (keep it focused)
- Authentication / login screen (would add complexity without adding pattern value)
- Complex state management (Redux etc.) — React Query is sufficient
- Mobile responsive design (desktop only for the demo)
- Applicant-facing submission UI (API is the channel — demo via Postman)

---

## Agentic AI Integration (Project 2)

The Loan Platform (Project 1) is the reference pattern for an agentic AI code generator.

### The core insight
> Generic AI generates generic code. Valuable AI generates YOUR code — following your
> specific patterns, conventions, naming standards, and validation rules.

### How it works

```
Input:  customer data model + business rules + (optionally) their coding standards
        + Loan Platform codebase as reference

Agent loop:
  1. Read reference pattern (loan-api/ + loan-ui/)
  2. Understand structure: controller → service → validator → repository → Kafka → ES
  3. Read target data model
  4. Generate OpenAPI contract (structure-only)
  5. Generate Spring Boot service (controller, service, validator, repository, entities)
  6. Generate Kafka events
  7. Generate React UI (work queue, detail view, search)
  8. Generate integration tests
  9. Run tests → fix failures → repeat
  10. Open GitHub PR

Output: production-ready, idiomatic code indistinguishable from a senior developer
```

### Two business models

**Model 1 — Consulting (per engagement)**
- Customer describes their system, shares their data model + standards
- You run the agent, review output, deliver working system
- Charge per engagement ($500-$1000/day)
- You own the expertise; customers pay for the result
- Low overhead, high margin

**Model 2 — SaaS platform**
- Customer logs in, uploads their reference codebase + data model
- Agent generates code on demand
- Customer downloads or gets a PR
- Subscription model (monthly/per generation)
- Scales without your time

**Both models are supported by the same agent.** The difference is packaging and delivery.

### Value proposition

| Traditional approach | With the agent |
|---|---|
| 3 developers, 6 months | 1 developer, 2 weeks |
| Generic patterns, needs rework | Follows customer's exact standards |
| $300k+ project cost | Fraction of the cost |
| Knowledge leaves with the developers | Encoded in the agent permanently |

### Growth path
Every new customer reference pattern added (mortgage, personal loan, trade finance, KYC,
insurance) makes the agent more capable and more valuable. The agent gets smarter with
each engagement.

### Agent inputs
1. Target data model (fields, relationships, business rules)
2. Reference pattern (Loan Platform codebase)
3. Customer-specific standards (optional — naming, error codes, coding style)
4. Task description ("generate a mortgage application service")

### Agent outputs
1. OpenAPI YAML contract (structure-only)
2. Spring Boot controller + service + validator + repository + JPA entities
3. Kafka event definitions
4. Elasticsearch index mapping
5. React UI (work queue + detail + search screens)
6. Integration tests
7. Docker Compose additions
8. GitHub PR

---

## Domain — Loan Application

### Entity relationships
```
LoanApplication
  → 1     LoanDetails
  → 1..*  Applicant
              → 1     Employment
              → 1     Address (residential)
              → 0..1  Address (mailing)
  → 0..*  Asset
              → 0..1  Address (property only)
  → 0..*  Liability
  → 0..*  Document
```

### LoanApplication (root)
| Field | Type | Notes |
|---|---|---|
| id | UUID | Internal primary key |
| reference | string | Public-facing e.g. LOAN-2026-00042 |
| status | enum | DRAFT, SUBMITTED, REVISION_REQUIRED, APPROVED, ACTIVE |
| createdAt | datetime | |
| submittedAt | datetime | Set on submit |
| approvedAt | datetime | Set on approval |
| revisionReason | string | Set when returned for revision |
| revisionComments | string | Set when returned for revision |

### LoanDetails (1-to-1)
| Field | Type | Notes |
|---|---|---|
| purpose | enum | HOME_PURCHASE, REFINANCE, INVESTMENT, PERSONAL, VEHICLE, BUSINESS |
| amount | decimal | Loan amount requested |
| termMonths | integer | e.g. 360 = 30 years |
| repaymentType | enum | PRINCIPAL_AND_INTEREST, INTEREST_ONLY |
| interestRateType | enum | FIXED, VARIABLE |

### Applicant (1-to-many, min 1)
| Field | Type | Notes |
|---|---|---|
| type | enum | PRIMARY, CO_APPLICANT |
| firstName | string | |
| middleName | string | Optional |
| lastName | string | |
| dateOfBirth | date | |
| email | string | |
| phone | string | |
| mobilePhone | string | Optional |
| residencyStatus | enum | CITIZEN, PERMANENT_RESIDENT, TEMPORARY_VISA |
| residentialAddress | → Address | |
| mailingAddress | → Address | Optional |
| employment | → Employment | |

### Employment (1-to-1 with Applicant)
| Field | Type | Notes |
|---|---|---|
| employmentType | enum | FULL_TIME, PART_TIME, CASUAL, SELF_EMPLOYED, UNEMPLOYED, RETIRED |
| employerName | string | Optional when UNEMPLOYED or RETIRED |
| occupation | string | Optional when UNEMPLOYED or RETIRED |
| annualIncome | decimal | |
| startDate | date | |

### Asset (0-to-many)
| Field | Type | Notes |
|---|---|---|
| type | enum | PROPERTY, VEHICLE, SAVINGS, SHARES, OTHER |
| description | string | |
| value | decimal | |
| address | → Address | Only when type = PROPERTY |

### Liability (0-to-many)
| Field | Type | Notes |
|---|---|---|
| type | enum | MORTGAGE, PERSONAL_LOAN, CREDIT_CARD, CAR_LOAN, OTHER |
| lender | string | |
| outstandingBalance | decimal | |
| monthlyRepayment | decimal | |
| creditLimit | decimal | Only when type = CREDIT_CARD |

### Document (0-to-many)
| Field | Type | Notes |
|---|---|---|
| type | enum | PAYSLIP, BANK_STATEMENT, IDENTIFICATION, TAX_RETURN, OTHER |
| filename | string | Original filename |
| s3Key | string | Internal S3 object key |
| uploadedAt | datetime | |

### Address (shared — used by Applicant + Asset)
| Field | Type | Notes |
|---|---|---|
| line1 | string | |
| line2 | string | Optional |
| suburb | string | |
| city | string | |
| state | string | |
| postCode | string | |
| countryCode | string | ISO 3166-1 alpha-2 e.g. AU, NZ |

---

## Tech Stack

| Layer | Technology | Why |
|---|---|---|
| Language | Java 21 | Industry standard in banking |
| Framework | Spring Boot 3.x | Most common enterprise framework |
| API | REST, contract-first (OpenAPI 3.x) | Contract-first design |
| Validation | Bean Validation + custom `ServiceValidator` | Two-layer validation pattern |
| Persistence | Spring Data JPA + Hibernate | Standard relational ORM |
| Database | PostgreSQL (local) / AWS RDS (production) | Relational DB with ACID transactions |
| Schema migration | Flyway | Production-grade DB versioning |
| Messaging | Apache Kafka | Event-driven architecture (banking standard) |
| Document storage | AWS S3 | Payslips, ID documents, supporting docs |
| Secrets | AWS Secrets Manager | API keys, DB credentials (never in code) |
| Logging / monitoring | AWS CloudWatch | Observability |
| Containerisation | Docker + AWS ECS | Deployment |
| Testing | JUnit 5 + MockMvc (unit) + Testcontainers (integration) | Full test coverage |
| Build | Maven | Standard enterprise build |
| Schema validation | `openapi-request-validator-core` (Atlassian) | Request/response contract enforcement |

---

## Event-Driven Architecture (Kafka)

Domain events published to Kafka after key state transitions:

```
POST /loan-applications/{id}/submit
    → Service submits application
    → Publishes LoanApplicationSubmitted event to Kafka topic
    → Returns 201

Kafka consumer (downstream):
    → Credit check service consumes LoanApplicationSubmitted
    → Publishes CreditCheckCompleted event
    → Loan service consumes → updates status to review/declined
```

**Kafka topics:**
| Topic | Event | Producer | Consumer |
|---|---|---|---|
| `loan.application.submitted` | `LoanApplicationSubmitted` | Loan API | Credit check service |
| `loan.application.approved` | `LoanApplicationApproved` | Loan API | Notification service |
| `loan.application.declined` | `LoanApplicationDeclined` | Loan API | Notification service |
| `loan.application.status.changed` | `LoanStatusChanged` | Loan API | Audit service |

**Why Kafka (not just REST callbacks):**
- Decouples loan API from downstream services
- Downstream services can be added without changing the loan API
- Events are durable — replayed if a consumer is down
- Standard in banking for compliance/audit trail

---

## AWS Integration

### S3 — Document Upload
Applicants upload supporting documents (payslips, ID, bank statements):

```
POST /loan-applications/{id}/documents
    → Validates file type + size
    → Uploads to S3: loan-applications/{id}/documents/{filename}
    → Stores S3 key in DB
    → Returns { documentId, url }

GET /loan-applications/{id}/documents/{documentId}
    → Generates pre-signed S3 URL (15 min expiry)
    → Returns { url } — client downloads directly from S3
```

**Why pre-signed URLs:** The API never proxies the file — S3 serves it directly. Scales to any file size, no API memory pressure.

### RDS — PostgreSQL in Production
Local development uses PostgreSQL in Docker. Production uses AWS RDS:
- Automated backups
- Multi-AZ for high availability
- Same PostgreSQL — zero code change

### Secrets Manager
No credentials in `application.properties` or environment variables:
```java
// Spring Cloud AWS reads secrets at startup
spring.config.import=aws-secretsmanager:/loan-app/prod/db-credentials
```

### CloudWatch
Structured JSON logs shipped to CloudWatch automatically via the ECS task definition.
Spring Boot Actuator metrics exposed → CloudWatch metrics dashboard.

---

## Local Development (Docker Compose)

Everything runs locally in Docker — no AWS account needed for development.

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16
    ports: ["5432:5432"]

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports: ["9092:9092"]
    depends_on: [zookeeper]

  localstack:
    image: localstack/localstack:3.0
    ports: ["4566:4566"]
    environment:
      SERVICES: s3,secretsmanager   # emulates AWS locally
```

**LocalStack** emulates AWS services inside Docker:
- `s3` → replaces AWS S3
- `secretsmanager` → replaces AWS Secrets Manager

**Spring profiles:**
```
local profile   → LocalStack endpoints (http://localhost:4566)
prod profile    → Real AWS endpoints
```

Code never changes between local and production — only the endpoint + credentials switch via profile.

**Developer workflow:**
```bash
docker compose up        # start all infrastructure
mvn spring-boot:run      # app connects to Docker services
```

---

## Application Deployment (AWS ECS)

```
GitHub → CI/CD pipeline → Docker image → ECR → ECS Fargate
```

- `Dockerfile` in project root
- ECS task definition (CPU/memory/env vars from Secrets Manager)
- Application Load Balancer in front of ECS
- RDS in private subnet (not publicly accessible)

---

## Testing Strategy

Three layers of testing — each with a distinct purpose:

| Layer | Technology | What it tests |
|---|---|---|
| Unit | JUnit 5 + Mockito | Service logic, validator rules in isolation |
| Integration | JUnit 5 + Testcontainers + MockMvc | API endpoints against real PostgreSQL + Kafka + Elasticsearch |
| E2E | Playwright (Java) | Full user journey through the React UI |

### Unit tests
- Validator rules: each business rule tested in isolation
- Service logic: mocked repository, assert correct state transitions
- Kafka event publisher: verify correct event published on submit/approve/decline
- Naming: `Test*` format (e.g. `TestLoanApplicationValidator`)

### Integration tests
- Testcontainers spins up real PostgreSQL + Kafka + Elasticsearch in Docker for each test run
- MockMvc tests every endpoint: happy path + validation errors + state transition guards
- Kafka consumer tests: publish event → assert Elasticsearch index updated
- Naming: `*IT` format (e.g. `LoanApplicationSubmitIT`)

### E2E tests (Playwright Java)
Full user journey through the React UI against a running local stack:

```
TestSubmitAndApprove:
  → Submit loan application via API (setup)
  → Open work queue in browser
  → Click application row → verify all details tabs
  → Click Approve → confirm modal → verify status = APPROVED
  → Open public search → search applicant name → verify result
  → Click result → verify loan details
  → Click Export PDF → verify download

TestReturnForRevision:
  → Submit loan application via API (setup)
  → Open work queue → click row
  → Click Return for Revision → enter reason + comment → submit
  → Verify status = REVISION_REQUIRED
  → Resubmit via API (delta)
  → Verify re-appears in work queue
```

- Uses `@PlaywrightTest` with headed/headless mode
- Chromium browser (configurable)
- Tests run against `http://localhost:3000` (React UI) + `http://localhost:8080` (API)
- Naming: `Test*` format (e.g. `TestWorkQueueApprovalJourney`)

---

## CI/CD Pipeline (GitHub Actions)

Every PR triggers an automated build pipeline.

### PR build (`.github/workflows/pr-build.yml`)

```yaml
on:
  pull_request:
    branches: [main, develop]

jobs:
  build:
    steps:
      - Checkout
      - Set up Java 21
      - Set up Node 20
      - Cache Maven + npm dependencies
      - Build API (mvn clean verify)        ← runs unit + integration tests (Testcontainers)
      - Build UI (npm ci + npm run build)   ← TypeScript compile + lint
      - Run UI tests (npm run test)         ← React component tests (Vitest)
      - Build Docker images
      - Run E2E tests (Playwright)          ← full stack in Docker Compose
      - Upload test reports (Allure / Surefire)
      - Upload Playwright traces on failure
```

### Branch strategy
```
main      ← production releases only
develop   ← integration branch
feature/* ← feature branches → PR → develop
bugfix/*  ← bug fix branches → PR → develop
```

### PR rules
- All checks must pass before merge
- At least 1 reviewer approval
- Conventional commit format: `feat(loans): add return-for-revision endpoint`

### Deployment pipeline (`.github/workflows/deploy.yml`)
```
merge to main
  → build Docker images
  → push to AWS ECR
  → deploy to AWS ECS (rolling update)
  → run smoke tests
  → notify on Slack
```

---

## Status

### Phase 1 — Foundation
- [x] Choose domain (Loan Application)
- [x] Design architecture (microservices + Kafka + Elasticsearch + AWS)
- [ ] Define data model (entities, fields, relationships)
- [ ] Write OpenAPI contracts (one per service, structure-only)
- [ ] Create GitHub repo (`loan-platform`)

### Phase 2 — loan-application-service
- [ ] Spring Boot scaffold
- [ ] JPA entities + Flyway migrations
- [ ] Submit + draft + resubmit endpoints
- [ ] Two-layer validation (OpenAPI + ServiceValidator)
- [ ] Validation metadata endpoint
- [ ] Kafka event publishing
- [ ] Unit + integration tests (Testcontainers)

### Phase 3 — loan-review-service
- [ ] Work queue endpoint
- [ ] Approve + return-for-revision endpoints
- [ ] Kafka consumer (LoanApplicationSubmitted → work queue)
- [ ] Kafka publisher (Approved / ReturnedForRevision)
- [ ] Unit + integration tests

### Phase 4 — loan-search-service
- [ ] Elasticsearch integration
- [ ] Kafka consumer (indexes on Approved)
- [ ] Public search endpoint
- [ ] Staff search endpoint
- [ ] Integration tests

### Phase 5 — loan-document-service
- [ ] AWS S3 upload (LocalStack locally)
- [ ] Pre-signed URL download
- [ ] PDF export (JasperReports/iText)
- [ ] Integration tests

### Phase 6 — notification-service
- [ ] Kafka consumers for all events
- [ ] Email templates (AWS SES or SMTP)

### Phase 7 — loan-ui (React)
- [ ] Work queue screen
- [ ] Application detail (tabbed)
- [ ] Approve / return modal
- [ ] Public search screen
- [ ] Loan detail + PDF export button
- [ ] Playwright E2E tests

### Phase 8 — Infrastructure
- [ ] Docker Compose (all services + PostgreSQL + Kafka + Elasticsearch + LocalStack)
- [ ] GitHub Actions CI/CD (PR build + deploy pipeline)
- [ ] AWS ECS deployment

### Phase 9 — Agentic AI (Project 2)
- [ ] Agent reads loan-platform as reference
- [ ] Generates new service from data model input
- [ ] Runs tests, fixes failures, opens PR
- [ ] Consulting + SaaS packaging
