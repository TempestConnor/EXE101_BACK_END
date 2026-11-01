---
name: next-feature
description: Start the next feature from committed code — pick the next in-scope feature (F01–F21), create its `_feature_fNN` branch, and produce a kickoff brief with a test list to approve. Does not write production code.
argument-hint: "[feature id, e.g. F04 — optional, defaults to the next in-scope feature]"
disable-model-invocation: true
allowed-tools: Bash(git status:*), Bash(git fetch:*), Bash(git branch:*), Bash(git log:*), Bash(git show:*), Bash(git ls-tree:*), Bash(git merge-base:*), Bash(git rev-parse:*), Bash(git switch:*), Read, Grep, Glob, AskUserQuestion
---

# Start the next feature

Requested feature (may be empty): `$ARGUMENTS`

Work from **committed** code only. Do not write production code or tests until the
kickoff questions are answered (section 5). Once they are, continue straight into
implementation in the same run (section 6) and commit automatically (see the end of
section 5, item 8).

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
   `git merge-base --is-ancestor HEAD main` (local `main`, which may be ahead of
   `origin/main` after an earlier local merge; fall back to `origin/main` if there
   is no local `main`).
   - **Exit 0 (merged):** base is `main`.
   - **Not merged:** decide from evidence whether the new feature depends on the
     unmerged work (`git log --oneline main..HEAD`). It depends on it when the
     new feature's spec **Implementation** line, functional requirements or the
     scope document need entities, endpoints or behaviour that this unmerged
     work introduces. Needing something already on `main` is not a dependency.
     - **Depends — stack:** base is the current branch.
     - **Independent — merge, then branch from main:** first run the current
       branch's tests (`.\mvnw.cmd clean package`). If they fail, stop and report
       the output; do not merge. If they pass, ask the user (with
       AskUserQuestion) to confirm merging the current branch into `main`,
       naming the unmerged commits. On a yes:
       `git switch main`, `git merge --ff-only origin/main` (stop if this fails),
       `git merge --no-ff <current branch>`. Stop on any conflict and report it;
       do not resolve conflicts on the user's behalf. Base is then `main`.
     - **Unclear:** ask the user which applies, giving the evidence both ways.
   State which rule applied and the dependency evidence in the brief.
3. `git switch -c _feature_f<NN> <base>`.
4. Do not push, neither the new branch nor `main`. Offer
   `git push -u origin _feature_f<NN>` (and `git push origin main` after a merge)
   as next steps.

## 5. Kickoff brief

Read the feature's section in `docs/EXE101_Features_Specification.md`, the business
rules it cites in `docs/Marketplace_Business_Rules_Final.md` (including the
31 October 2026 amendment), and section D (Q01–Q19). Then report, concisely:

1. **Feature** — ID, title, branch, base, whether it is stacked or a merge into
   `main` happened, and the dependency evidence.
2. **Scope for this increment** — what the spec requires, as amended by
   `implementation-scope.md`; call out anything explicitly out of scope.
3. **Acceptance criteria** — from the spec, verbatim or tightly paraphrased.
4. **Open questions** — start from the Q-items and **Needs Clarification** notes that
   affect this feature, then drop every one that is:
   - **Resolved** or **Moot** in the section D Status column, or answered in a
     per-feature doc (`docs/customer-authentication.md`, `docs/listing-catalog.md`, …);
   - removed or settled by the 31 October amendment;
   - assigned to a later feature or priority by `implementation-scope.md` or by an
     earlier feature doc's "out of scope here" list.

   List what was dropped in one line with the reason, so a wrong drop is visible.
   Sort the rest into two groups, each with the code path it affects:
   - **Blocking** — financial policy (amounts, rounding, refunds, releases, payment
     anomalies), schema changes, or decisions that are hard to reverse once data
     exists. Give no recommended answer; show the options and their consequences.
   - **Recommended** — everything else. Give one recommended answer with a one-line
     reason drawn from the spec, the business rules or earlier feature decisions.
     It is a proposal, not an assumption: nothing is built on it until the user
     approves it.

   Mark which parts can proceed independently.
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

   When the implementation is done, commit it on `_feature_f<NN>` without asking,
   once all of these hold:
   - every approved test is written, and `.\mvnw.cmd clean package` passes;
   - the current branch is still `_feature_f<NN>`;
   - `git status --porcelain` lists only changes made for this feature. If it shows
     anything else, stop and ask instead of committing.

   Stage those files and commit with the subject `f<NN> implemented.`, a short body
   listing what was built and any deferred items, and the session's attribution
   lines. Do not push; offer `git push -u origin _feature_f<NN>` as the next step.
   If the build fails, fix it or report the output. Do not commit a failing build.
9. **Unfinished items in earlier features** — gaps found in step 2/3.

End with one AskUserQuestion call (at most four questions; batch the rest in the
brief). Ask:
- one question per **Blocking** item, with its options;
- one question to accept every **Recommended** answer as listed, with an option to
  override some, naming them in a follow-up;
- whether the test list in item 8 is approved as written.

Record every answer during implementation, and commit it with the feature: put the
decision in the feature's doc (`docs/<feature>.md`, under "Approved policies"), set
the matching Status in section D of `docs/EXE101_Features_Specification.md` with a link
to that doc, and mark the inline **Needs Clarification** note resolved. A decision
recorded only in the chat will be asked again by the next run.

## 6. Implement immediately after the answers

Do not end the turn after the AskUserQuestion call returns, and do not ask for a
separate "go". In the same run:

- **All answered without overrides or changes:** acknowledge the decisions in a
  line or two, then start implementing at once — production code, the feature doc
  and spec updates, then exactly the approved tests, then `.\mvnw.cmd clean package`
  and the automatic commit from item 8.
- **"Override some" or "Changes needed" chosen:** ask only for those specifics
  (one AskUserQuestion, or a short question if free text is needed), then proceed
  as above once answered.
- **A Blocking answer pauses the feature** (for example "do another feature
  first"): stop and report; do not implement.

Implementation follows `AGENTS.md` and the topic docs, as for any feature.
