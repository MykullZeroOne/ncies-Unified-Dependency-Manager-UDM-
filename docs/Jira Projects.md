That being said this is what I’m thinking, a Jira/Compass setup that matches how we *actually* deliver work (
microservices + vendor UI extensions), without turning Jira into a hot mess.

The goal is simple: **clear ownership, easy filtering, and traceability from request → code → deploy**.

---

## The big picture

- **One Jira project per delivery stream**
    - **CBP** = Core Banking Platform (our microservices)
    - **KEYUI** = CORE Keystone UI Extensions (React/JS customization)
- **These projects do not cross over**
    - Separate backlogs/boards, releases, and reporting
    - No shared epics or cross-project dependency management (by design)
- **Epics are outcomes**
    - Epics represent *capabilities/features* (not repos)
- **Stories are the unit of execution**
    - Each story is owned by *one* service/module (via **Component**)
- **Compass is our “source of truth” for components**
    - Ownership/on-call, docs/runbooks, repo links, health, and deployment/activity visibility live there
- **Bitbucket is how we prove work happened**
    - Branches/commits/PRs reference the Jira key so traceability is automatic

---

## CBP — Core Banking Platform (Microservices)

In CBP, I’d like us to keep the model straightforward:

- **Project:** `CBP`
- **Components:** represent microservices (synced/paired with Compass)
    - Examples: `fee-service`, `transaction-service`, `participation-loan-service`, etc.
- **Fix Versions:** are **platform milestones / feature drops**, not per-service semver
    - Examples: `MVP-1`, `Pilot Release`, `Regulatory Cutover 2026-03`
- **Working agreement:** every ticket has a *primary* Component; if work spans services, we split it into one ticket per
  service.

### One concrete CBP lifecycle example

```text 
╔══════════════════════════════════════════════════════════════════════════════╗ 
║                 CBP — Core Banking Platform (Microservices)                  ║
╚══════════════════════════════════════════════════════════════════════════════╝
┌─ EPIC ──────────────────────────────────────────────────────────────────────┐ 
│                           Fee Calculation MVP                               │
└─────────────────────────────────────────────────────────────────────────────┘ 
                                        │ 
                                        ▼ 
┌─ STORY ─────────────────────────────────────────────────────────────────────┐ 
│ Fee Service — Implement calculation engine                                  │
│ Component : fee-service                                                     │ 
│ Fix Version : MVP-1                                                         │ 
└─────────────────────────────────────────────────────────────────────────────┘ 
                                        │
                                        ▼
┌─ SUB-TASKS (optional) ──────────────────────────────────────────────────────┐ 
│ - unit tests                                                                │ 
│ - API docs                                                                  │ 
│ - metrics/logging                                                           │
│ - review fixes                                                              │ 
└─────────────────────────────────────────────────────────────────────────────┘ 
                                        │ 
                                        ▼ 
┌─ DELIVERY ──────────────────────────────────────────────────────────────────┐ 
│ Branch : feature/<CBP-KEY>-fee-calculation-engine                           │
│ Commits : <CBP-KEY> referenced                                              │ 
│ PR : <CBP-KEY> referenced → CI → review → merge                             │ 
│ Deploy : fee-service deployed independently (CI/CD pipeline)                │
└─────────────────────────────────────────────────────────────────────────────┘ 
                                        │
                                        ▼ 
┌─ COMPASS COMPONENT (Source of Truth) ───────────────────────────────────────┐ 
│ Component : fee-service                                                     │ 
│ Owns : repo link, owners/on-call, docs/runbook                              │
│ Health : operational health signals + activity visibility                   │ 
│ Version : current deployed/released version info                            │ 
│ Deploys : deployment/activity history                                       │ 
└─────────────────────────────────────────────────────────────────────────────┘

┌─ RELEASE / VERSION (Jira Project-level) ────────────────────────────────────┐ 
│ Fix Version: MVP-1 (platform milestone / feature drop)                      │ 
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## KEYUI — CORE Keystone UI Extensions (React/JS)

Keystone UI scripting is a separate world (vendor software we extend), so it deserves its own Jira project and board.

- **Project:** `KEYUI` (Keystone UI Extensions)
- **Components:** represent Keystone UI modules like “services”
    - Examples: `member-snapshot`, `transactions`, `fees`, `history`, `shared-ui-lib` (optional)
- **Fix Versions:** represent Keystone UI releases/drops
    - Example: `KEYUI-Release-1`
- **Bitbucket:** same lifecycle expectations as CBP: branch/commit/PR reference the story key.

### One concrete KEYUI lifecycle example

```text
 ╔══════════════════════════════════════════════════════════════════════════════╗ 
 ║                  KEYUI — CORE Keystone UI Extensions                         ║ 
 ╚══════════════════════════════════════════════════════════════════════════════╝
┌─ EPIC ──────────────────────────────────────────────────────────────────────┐ 
│                    Member Snapshot Permission Upgrade                       │ 
└─────────────────────────────────────────────────────────────────────────────┘ 
                                    │
                                    ▼
┌─ STORY ─────────────────────────────────────────────────────────────────────┐ 
│ Assign "View Transaction" role to user                                      │ 
│ Component : member-snapshot                                                 │ 
│ Fix Version : KEYUI-Release-1                                               │
└─────────────────────────────────────────────────────────────────────────────┘ 
                                    │ 
                                    ▼ 
┌─ DELIVERY ──────────────────────────────────────────────────────────────────┐ 
│  Branch : feature/<KEYUI-KEY>-assign-view-transaction-role                  │ 
│ Commits : <KEYUI-KEY> referenced                                            │ 
│ PR : <KEYUI-KEY> referenced → CI → review → merge                           │ 
│ Deploy : pipeline/packaging process                                         │ 
└─────────────────────────────────────────────────────────────────────────────┘ 
                                    │
                                    ▼
┌─ COMPASS COMPONENT (Source of Truth) ───────────────────────────────────────┐ 
│ Component : member-snapshot                                                 │ 
│ Owns : repo link, owners/on-call, docs/runbook                              │ 
│ Health : operational health signals + activity visibility                   │ 
│ Version : current deployed/released version info                            │
│ Deploys : deployment/activity history                                       │
└─────────────────────────────────────────────────────────────────────────────┘

``` 

---

## Lightweight working agreements (so this stays easy)

- **Every story has exactly one primary Component**
- **Branch/commit/PR must reference the Jira key**
    - That’s what gives us effortless traceability
- **Mark the story Done when it’s shipped**
    - Not “done when merged” (unless merging always implies release for that stream)
- **Compass is where people go to answer “what is this thing?”**
    - Jira tells us *what work we’re doing*
    - Compass tells us *what the component is*, who owns it, and how healthy it is

---


