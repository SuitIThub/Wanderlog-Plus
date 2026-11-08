# ÖPNV Karte/Namensvettern/Blättern – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans.

**Spec:** `docs/superpowers/specs/2026-11-08-transit-map-namesakes-paging-design.md`

## Global Constraints
- JDK 21 Gradle, `-q`, task-done commands end with `&& echo PASS`; version 0.58.0 (76) at the end; German CHANGELOG.

## Review Focus
1. Names with prefecture must not break stations whose Yahoo name already has a bracket (なんば(地下鉄)).
2. Parallel stop lookups bounded and failure-tolerant.
3. Old saved connections without stop coordinates still render.
4. Render cache key changes when colors/segments change.
5. Earlier/later in smart mode use door times.

### Task 1: Server namesakes + stop coordinates
Files: `GoogleStationFinder.kt`, `TransitService.kt`, `TransitProtocol.kt`; tests in `GoogleStationFinderTest`, `TransitServiceTest`.
- [ ] tests → FAIL → implement → PASS → server suite → commit.

### Task 2: Route geometry with segments
Files: `app/.../maps/RouteGeometry.kt`, `RouteMapRenderer.kt`, new `app/.../maps/TransitGeometry.kt`; callers in Logistics list cards/detail/Journey/LegRow; stop list on the train detail (`train-stops`); test `app/.../maps/TransitGeometryTest.kt`.
- [ ] tests → FAIL → implement → PASS → commit.

### Task 3: Result map and earlier/later (0.58.0)
Files: `TransitViews.kt`, `TransitScreen.kt`, `TransitViewModel.kt`; test `app/.../transit/TransitPagingTest.kt`.
- [ ] tests → FAIL → implement → PASS → full suites → 0.58.0 / 76, CHANGELOG → commit.
