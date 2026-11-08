# Smarte ÖPNV-Suche – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Goal:** Compare several stations near a place and rank connections by door-to-door balance.
**Spec:** `docs/superpowers/specs/2026-11-08-transit-smart-search-design.md`

## Global Constraints
- Gradle with JDK 21 path; `-q`; task-done commands end with `&& echo PASS`. Version 0.57.0 (75), German CHANGELOG.
- New fields with defaults at the end of classes.

## Review Focus
1. Pair searches run in parallel but must keep the per-pair cache/in-flight behaviour; no unbounded threads.
2. A failing pair must not fail the whole smart search unless all fail.
3. Time shift per start station crosses midnight correctly (23:55 + 8 min).
4. Station-given requests never become smart.
5. Chosen route saved with its own stations/walks, not the best route's.

### Task 1: Server smart search
Files: `shared/.../TransitProtocol.kt` (`TransitRequest.smart`, `TransitResult.smart`, `TransitRoute.accessMeters/accessMinutes/egressMeters/egressMinutes/doorMinutes/score/best`), `server/.../TransitService.kt` (`smartSearch`, refactor single search into `searchPair(req, from, fromMeters, to, toMeters)`), test `server/.../TransitSmartSearchTest.kt`.
- [ ] Tests (per spec list) → FAIL → implement → PASS → server suite green → commit.

### Task 2: App (0.57.0)
Files: `app/.../transit/TransitViewModel.kt` (`TransitForm.smart`, `requestFor`), `TransitScreen.kt` (switch, `chosenRoute`), `TransitViews.kt` (card lines, badge), test `app/.../transit/TransitSmartTest.kt`.
- [ ] Tests → FAIL → implement → PASS → full suites green → 0.57.0 / 75, CHANGELOG → commit.
