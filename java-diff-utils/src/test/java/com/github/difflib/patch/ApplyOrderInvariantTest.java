package com.github.difflib.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins down ordering invariants of {@link Patch} application. Every test here
 * fails if the corresponding lines in {@code Patch.java} were rewritten in an
 * apparently equivalent but reordered way. Merely asserting "no exception" is
 * not enough for that.
 */
class ApplyOrderInvariantTest {

	/**
	 * {@code Patch.applyToExisting} must walk the deltas from the end of the
	 * list to the front. The chunk positions stored in every delta are original
	 * coordinates, so applying an early delta first shifts the positions of the
	 * deltas that follow. Going backwards means no offset accumulator is
	 * needed; going forwards silently inserts later chunks at the wrong index.
	 */
	@Test
	void applyToMustProcessDeltasInReversePositionOrder() throws PatchFailedException {
		Patch<String> patch = new Patch<>();
		// insert two lines before original index 1
		patch.addDelta(
						new InsertDelta<>(new Chunk<>(1, new ArrayList<>()), new Chunk<>(1, Arrays.asList("B1", "B2"))));
		// insert one line before original index 4
		patch.addDelta(new InsertDelta<>(new Chunk<>(4, new ArrayList<>()), new Chunk<>(4, Arrays.asList("D1"))));

		List<String> original = Arrays.asList("a", "b", "c", "d", "e");
		List<String> expected = Arrays.asList("a", "B1", "B2", "b", "c", "d", "D1", "e");

		assertThat(patch.applyTo(original)).isEqualTo(expected);

		// Same expectation, applied to a modifiable list: this exercises
		// applyToExisting directly instead of its copying wrapper.
		List<String> mutable = new ArrayList<>(original);
		patch.applyToExisting(mutable);
		assertThat(mutable).isEqualTo(expected);
	}

	/**
	 * {@code Patch.findPositionWithFuzzAndMoreDelta} expands the search
	 * symmetrically around the drifted default position, but within the same
	 * radius the backward candidate is verified before the forward one. So when
	 * identical candidates exist on both sides, the nearer-to-the-front one
	 * wins. Reversing those two checks would apply the delta one line too far.
	 */
	@Test
	void fuzzySearchPrefersTheBackwardCandidateAtEqualDistance() throws PatchFailedException {
		Patch<String> patch = new Patch<>();
		// chunk claims patch position 5; the live text contains "Q" at index 4
		// (one step backward) and at index 6 (one step forward)
		patch.addDelta(new ChangeDelta<>(new Chunk<>(5, Arrays.asList("Q")), new Chunk<>(5, Arrays.asList("DONE"))));

		List<String> target = new ArrayList<>(Arrays.asList("0", "1", "2", "3", "Q", "x", "Q", "7", "8", "9"));

		assertThat(patch.applyFuzzy(target, 1))
					.isEqualTo(Arrays.asList("0", "1", "2", "3", "DONE", "x", "Q", "7", "8", "9"));
	}

	/**
	 * {@code AbstractDelta.verifyAndApplyTo} must verify before it mutates. If
	 * those two steps are swapped, a mismatching chunk is applied before the
	 * conflict handler runs, so the input list is corrupted even though the
	 * caller is notified of a conflict.
	 */
	@Test
	void failingVerificationMustLeaveTargetUntouched() throws PatchFailedException {
		Patch<String> patch = new Patch<>();
		patch.addDelta(
						new ChangeDelta<>(new Chunk<>(1, Arrays.asList("bbb")), new Chunk<>(1, Arrays.asList("BBB"))));

		List<String> corruptedTarget = new ArrayList<>(Arrays.asList("aaa", "XXX", "ccc"));

		assertThatThrownBy(() -> patch.applyTo(corruptedTarget)).isInstanceOf(PatchFailedException.class);

		List<String> recordedTarget = new ArrayList<>(corruptedTarget);
		List<VerifyChunk> seen = new ArrayList<>();
		patch.withConflictOutput((verifyChunk, delta, result) -> seen.add(verifyChunk));

		patch.applyToExisting(recordedTarget);

		assertThat(seen).containsExactly(VerifyChunk.CONTENT_DOES_NOT_MATCH_TARGET);
		assertThat(recordedTarget).isEqualTo(corruptedTarget);
	}
}
