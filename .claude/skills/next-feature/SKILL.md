---
name: next-feature
description: Start the next feature from committed code — pick the next in-scope feature (F01–F21), create its `_feature_fNN` branch, and produce a kickoff brief with a test list to approve. Does not write production code.
argument-hint: "[feature id, e.g. F04 — optional, defaults to the next in-scope feature]"
disable-model-invocation: true
allowed-tools: Bash(git status:*), Bash(git fetch:*), Bash(git branch:*), Bash(git log:*), Bash(git show:*), Bash(git ls-tree:*), Bash(git merge-base:*), Bash(git rev-parse:*), Bash(git switch:*), Read, Grep, Glob
---

# Start the next feature

Requested feature (may be empty): `$ARGUMENTS`

Work from **committed** code only. Do not write production code or tests in this
command; its output is a branch and a kickoff brief.

## 1. Preconditions

1. Run `git status --porcelain`. If anything is listed, stop: show the files and ask
   the user to commit or stash them. Do not stash, commit or discard on their behalf —
   switching branches with these changes would carry them into the new feature.
2. Run `git fetch origin` so branch and merge checks see the remote state.

## 2. Work out what is already implemented

Use `HEAD` and the remote branches as evidence, not the working tree:

- Feature branches: `git branch -a --list "*_feature_f*"`.
- Commit subjects: `git log --oneline -30` (e.g. `f01 implementated.`).
- Feature packages: `git ls-tree -d --name-only HEAD src/main/java/com/exe101/` —
  ignore `entity`, `config`, `common` and `tooling`; map the rest to features using
  the spec's **Implementation** lines.
- Per-feature docs such as `docs/customer-authentication.md`, and the increment notes
  at the top of `docs/implementation-scope.md`.

## 3. Choose the feature

Read `docs/implementation-scope.md` — it overrides the specification's suggested
order. Then:

- **No argument:** choose the first feature of the current priority that is not yet
  implemented. If the scope document explicitly names the next dependency, that wins.
  F03 availability checks belong to priority 1 but are built into the Listing and
  Order features that need them, not as a standalone branch.
- **Argument given:** use it, but if it is outside the current priority, deferred, or
  removed by an amendment, explain that and ask before continuing.
- Unfinished parts of already-implemented features (for example, recorded Policy
  acceptance in F01) are reported in the brief. Never pick them as "the next
  feature" unless the user asks.

State the chosen feature and the evidence for it in one or two lines.

## 4. Choose the base and create the branch

Branch name: `_feature_f<NN>` — lowercase, two digits (`_feature_f02`), matching the
existing convention.

1. If that branch already exists locally or on `origin`, stop and report it. Do not
   reset or reuse it.
2. Check whether the current work is on main:
   `git merge-base --is-ancestor HEAD origin/main`.
   - **Exit 0 (merged):** base is `origin/main`.
   - **Not merged:** the previous feature is not on main yet. Ask the user (with
     AskUserQuestion) whether to branch from the **current branch (stacked —
     recommended when the new feature depends on the unmerged one)** or from
     `origin/main`. Name the unmerged commits (`git log --oneline origin/main..HEAD`)
     in the question.
3. `git switch -c _feature_f<NN> <base>`.
4. Do not push. Offer `git push -u origin _feature_f<NN>` as a next step.

## 5. Kickoff brief

Read the feature's section in `docs/EXE101_Features_Specification.md`, the business
rules it cites in `docs/Marketplace_Business_Rules_Final.md` (including the
31 October 2026 amendment), and section D (Q01–Q19). Then report, concisely:

1. **Feature** — ID, title, branch, base, and whether it is stacked.
2. **Scope for this increment** — what the spec requires, as amended by
   `implementation-scope.md`; call out anything explicitly out of scope.
3. **Acceptance criteria** — from the spec, verbatim or tightly paraphrased.
4. **Blocking questions** — each **Needs Clarification** note and Q-item that affects
   this feature, with the code path it blocks. Do not propose an assumed default as
   the answer. Mark which parts can proceed independently.
5. **Schema touchpoints** — existing entities in `com.exe101.entity` the feature uses,
   and any schema additions the amendment implies (each needs a separate schema
   decision; the database is the schema authority).
6. **Financial correctness** — whether `docs/financial-correctness.md` applies; if so,
   list the invariants that need integration tests.
7. **Planned layout** — the `com/exe101/<feature>/` subpackages it needs, per
   `AGENTS.md` (only layers that carry weight).
8. **Test list for approval** — behaviours only, no test code: each line is a test
   name per `docs/testing.md` (`snake_case_ok` / `_ko`) plus a one-line
   Given/When/Then taken from the spec, not from any implementation. Cover every
   acceptance criterion, the not-found case, and guard clauses that must reject
   before a save; add controller cases where validation, status mapping or response
   shape matter, repository cases only for custom queries, and integration cases for
   financial invariants. Leave out method names, DTO fields and error codes — they
   are decided during implementation.

   Once approved, this list is the contract for the feature's tests: implement first,
   then write exactly these tests and run them. When an approved test fails, fix the
   code. Change or drop a listed behaviour only by naming it and giving the reason,
   and wait for the user to agree. Do not write tests before the implementation, and
   do not report expected failures of tests for code that does not exist yet.
9. **Unfinished items in earlier features** — gaps found in step 2/3.

End by asking which blocking questions the user can answer now, and whether the test
list in item 8 is approved as written.
