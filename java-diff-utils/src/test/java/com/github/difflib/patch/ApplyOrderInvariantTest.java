/*
 * Copyright 2026 java-diff-utils.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.difflib.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins ordering and coordinate invariants of {@link Patch} that ANALYSIS.md
 * documents. The assertions here are content based: if the corresponding
 * implementation lines are replaced by an "equivalent" looking variant (e.g.
 * forward iteration in applyTo, swapped chunk coordinates in restore, reversed
 * before/after probes in fuzzy search), these tests fail instead of merely
 * checking that no exception was thrown.
 */
public class ApplyOrderInvariantTest {

    /**
     * Invariant 1: {@code applyToExisting} must walk the deltas <em>backwards</em>
     * (Patch.applyToExisting, reverse {@code ListIterator}). All chunk positions
     * are coordinates of the original list, so two inserts cannot be applied in
     * ascending order: the first insert would shift the insertion point of the
     * second one, duplicating the inserted line and losing another line.
     */
    @Test
    public void applyToRequiresDescendingDeltaOrderSoPositionsStayOriginalCoordinates() throws PatchFailedException {
        Patch<String> patch = new Patch<>();
        patch.addDelta(new DeleteDelta<>(new Chunk<>(0, Arrays.asList("a")), new Chunk<>(0, new ArrayList<>())));
        patch.addDelta(new DeleteDelta<>(new Chunk<>(2, Arrays.asList("c")), new Chunk<>(1, new ArrayList<>())));

        assertThat(patch.getDeltas()).extracting(d -> d.getSource().getPosition()).containsExactly(0, 2);

        List<String> result = patch.applyTo(Arrays.asList("a", "b", "c"));

        assertThat(result).containsExactly("b");
    }

    /**
     * Invariant 2: {@code applyTo} operates on {@code source} positions while
     * {@code restore} operates on {@code target} positions. Here source and
     * target position deliberately differ, so swapping the coordinates in
     * either direction produces visibly wrong content instead of failing.
     */
    @Test
    public void applyToUsesSourcePositionAndRestoreUsesTargetPosition() {
        Patch<String> patch = new Patch<>();
        patch.addDelta(new ChangeDelta<>(
                new Chunk<>(1, Arrays.asList("s0", "s1")),
                new Chunk<>(4, Arrays.asList("t0", "t1", "t2"))));

        List<String> original = Arrays.asList("a", "s0", "s1", "b");
        List<String> revised = Arrays.asList("p", "q", "r", "q", "t0", "t1", "t2", "z");

        List<String> applied = applyUnchecked(patch, original);
        assertThat(applied).containsExactly("a", "t0", "t1", "t2", "b");

        List<String> restored = patch.restore(revised);
        assertThat(restored).containsExactly("p", "q", "r", "q", "s0", "s1", "z");
    }

    /**
     * Invariant 3: when fuzzy search finds a match at the same offset in both
     * directions, the backward probe runs first (Patch.findPositionWithFuzz
     * AndMoreDelta: before candidate is verified before the after candidate).
     * Both positions verify with fuzz 0, so reversing that order silently
     * patches the other occurrence.
     */
    @Test
    public void fuzzySearchPrefersBackwardCandidateOnSymmetricTie() throws PatchFailedException {
        Patch<String> patch = new Patch<>();
        patch.addDelta(new ChangeDelta<>(
                new Chunk<>(3, Arrays.asList("m", "m", "m")),
                new Chunk<>(3, Arrays.asList("n", "n", "n"))));

        // Default position is 3; identical matches exist at position 1 and 5.
        List<String> target = Arrays.asList("x", "m", "m", "m", "x", "m", "m", "m", "x");

        List<String> result = patch.applyFuzzy(new ArrayList<>(target), 0);

        assertThat(result).containsExactly("x", "n", "n", "n", "x", "m", "m", "m", "x");
    }

    /**
     * Invariant 4: a delta that cannot be matched anywhere within the fuzz
     * range is reported as a CONTENT_DOES_NOT_MATCH_TARGET conflict instead of
     * being applied at an arbitrary position.
     */
    @Test
    public void fuzzySearchWithoutAnyCandidateReportsContentConflict() {
        Patch<String> patch = new Patch<>();
        patch.addDelta(new ChangeDelta<>(
                new Chunk<>(0, Arrays.asList("a", "b")),
                new Chunk<>(0, Arrays.asList("c", "d"))));

        assertThatThrownBy(() -> patch.applyFuzzy(new ArrayList<>(Arrays.asList("x", "y")), 0))
                .isInstanceOf(PatchFailedException.class)
                .hasMessageContaining(VerifyChunk.CONTENT_DOES_NOT_MATCH_TARGET.toString());
    }

    private static List<String> applyUnchecked(Patch<String> patch, List<String> target) {
        try {
            return patch.applyTo(target);
        } catch (PatchFailedException e) {
            throw new AssertionError(e);
        }
    }
}
