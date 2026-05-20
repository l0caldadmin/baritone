# Baritone Code Review Action Plan

## Objective
Stabilize the build, eliminate high-risk runtime issues, and reduce technical debt in the Baritone codebase while preserving current functionality.

## Phase 1: Build and Runtime Risk

### Task 1.1: Resolve ProGuard JDK module warnings
- Update `proguard.pro` and Gradle configuration so ProGuard can resolve JDK 25 modules.
- Add the JDK `jmods` directory to ProGuard `-libraryjars`.
- Verify that `java.net.http` and `java.base` are available during shrinking.
- Remove or narrow `-dontwarn java.net.http.**` once configuration is correct.

**Acceptance criteria**
- ProGuard completes without warnings about `java.net.http`.
- The build succeeds without relying on broad warning suppression.

### Task 1.2: Validate `AutonomousClient` in the packaged artifact
- Add an integration test or runtime verification step for `baritone.llm.AutonomousClient`.
- Instantiate the client from the shaded or obfuscated jar.
- Execute a simple HTTP request against a mock or safe test endpoint.
- Confirm behavior in the final standalone artifact rather than only in the dev environment.

**Acceptance criteria**
- No `NoClassDefFoundError` or module-related runtime failure occurs.
- The HTTP client path works in the packaged jar.

## Phase 2: Dependency and Packaging Hygiene

### Task 2.1: Audit duplicate classes
- Inspect duplicate definitions for `module-info` and shaded libraries such as `mixin`.
- Use Gradle dependency inspection tasks, including `dependencies` and `dependencyInsight`.
- Determine whether duplicates are intentional shading artifacts or actual version conflicts.
- Exclude or align redundant artifacts where duplication is accidental.

**Acceptance criteria**
- No unintended duplicate class conflicts remain.
- Intentional shading is documented and version-pinned.

## Phase 3: Targeted Performance Work

### Task 3.1: `CachedChunk` block-tracking optimization
- Implement the suggested predicate-based filter for block tracking.
- Add a small benchmark or timing harness to compare before and after behavior.

### Task 3.2: `FasterWorldScanner` result limiting
- Reduce the maximum result set from the current 4k-style behavior to a smaller configurable cap.
- Use a safe default such as fewer than 512 results unless benchmarks justify another value.
- Confirm no important scanning or pathing behavior is lost.

### Task 3.3: `BetterBlockPos` efficiency cleanup
- Replace manual conversions with `BlockPos.fromLong()` where appropriate.
- Verify that behavior remains identical.

### Task 3.4: `CachedWorld` chunk-packing benchmark
- Compare the current chunk-packing implementation with an `addAll` approach.
- Keep whichever implementation is measurably faster and no less readable.

**Acceptance criteria**
- Each performance change is benchmarked or otherwise measured.
- No logic regressions are introduced.
- Only changes with clear value are kept.

## Phase 4: Refactoring and Maintainability

### Task 4.1: Refactor `InventoryBehavior.firstValidThrowaway`
- Split the method into smaller helpers.
- Make selection rules explicit and easier to test.

### Task 4.2: Move `pathStart()` out of `PathingBehavior`
- Extract `pathStart()` into a utility or helper class.
- Avoid introducing circular dependencies or unnecessary abstraction.

### Task 4.3: Improve `Settings.blocksToAvoidBreaking`
- Evaluate replacing the current structure with `HashSet` or `ImmutableSet`.
- Prefer the structure that matches actual usage patterns, especially lookup-heavy code paths.

### Task 4.4: Revisit modded door assumptions in `MovementHelper`
- Replace the assumption that all modded doors are openable.
- Use capability checks, interface checks, tags, or a conservative fallback path.

**Acceptance criteria**
- Refactored code is easier to read and test.
- Existing behavior remains stable unless intentionally corrected.

## Phase 5: Null Safety and Bug Prevention

### Task 5.1: Harden `ElytraProcess`
- Audit state transitions and nullable inputs.
- Add guard clauses for player state, equipment state, and any flight preconditions.
- Fail safely instead of allowing null-driven crashes.

**Acceptance criteria**
- Null-related failures are prevented in known edge cases.
- Flight logic degrades gracefully when prerequisites are not met.

## Phase 6: Feature Gap Backlog

### Task 6.1: Improve command suggestions
- Extend `MixinCommandSuggestionHelper` to populate command usage text in suggestions.
- Preserve compatibility with the existing suggestion flow.

### Task 6.2: Restore Beacon rendering support
- Re-implement the Beacon rendering API path for 1.21.11 and newer.
- Add version-gating if the rendering path differs across supported versions.

**Acceptance criteria**
- Feature work is isolated from stabilization changes.
- New behavior is covered by tests or manual verification notes.

## Phase 7: Deprecated API Cleanup

### Task 7.1: Find deprecated internal usage
- Search for uses of deprecated methods in `IPlayerContext`, `RotationUtils`, and legacy `Settings` patterns.

### Task 7.2: Migrate internal callers
- Replace deprecated eye-height usage with `entity.getEyeHeight(Pose.CROUCHING)` where applicable.
- Migrate rotation logic to the preferred helpers.
- Leave compatibility shims only where external consumers still depend on them.

### Task 7.3: Prepare removal for a minor release
- Mark deprecated internals as pending removal in the next minor release.
- Add changelog or migration notes for downstream users.

**Acceptance criteria**
- Internal code no longer depends on deprecated APIs.
- Removal work is staged cleanly for release planning.

## Suggested Execution Order
1. Fix ProGuard and verify `AutonomousClient` in the final jar.
2. Audit duplicate classes and dependency shading.
3. Address null safety in `ElytraProcess`.
4. Complete low-risk performance changes with benchmarks.
5. Perform refactors that improve maintainability.
6. Move feature gaps and deprecation removals into a controlled follow-up release if they are not required for immediate stability.

## Deliverables
- Clean ProGuard configuration for JDK 25.
- Verified working packaged artifact for the LLM HTTP client path.
- Duplicate dependency findings with remediation notes.
- Benchmarked performance improvements in selected hotspots.
- Refactored core logic in identified high-debt areas.
- Deprecated internal API migration plan suitable for a minor release.
