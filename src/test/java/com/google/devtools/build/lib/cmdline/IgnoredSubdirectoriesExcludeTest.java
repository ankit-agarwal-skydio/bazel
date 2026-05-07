// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.cmdline;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the exclude parameter support in {@link IgnoredSubdirectories}. */
@RunWith(JUnit4.class)
public class IgnoredSubdirectoriesExcludeTest {

  // --- Basic exclusion behavior ---

  @Test
  public void exactExclusionOverridesGlobIgnore() {
    // ignore **/build, exclude tools/channel_search/build
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/build"),
            ImmutableList.of("tools/channel_search/build"));

    // The excluded directory should NOT be ignored
    assertThat(ignored.matchingEntry(PathFragment.create("tools/channel_search/build"))).isNull();

    // Other build directories should still be ignored
    assertThat(ignored.matchingEntry(PathFragment.create("foo/build"))).isEqualTo("**/build");
    assertThat(ignored.matchingEntry(PathFragment.create("bar/baz/build"))).isEqualTo("**/build");
  }

  @Test
  public void exactExclusionOverridesPrefixIgnore() {
    // prefix ignore "build", exclude "build/special"
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(PathFragment.create("build")),
            ImmutableList.of(),
            ImmutableList.of("build/special"));

    // build/special should NOT be ignored
    assertThat(ignored.matchingEntry(PathFragment.create("build/special"))).isNull();

    // build/other should still be ignored
    assertThat(ignored.matchingEntry(PathFragment.create("build/other"))).isEqualTo("build");

    // The "build" directory itself cannot be pruned because it contains an excluded child
    assertThat(ignored.matchingEntry(PathFragment.create("build"))).isNull();
  }

  @Test
  public void noExclusionBehaviorUnchanged() {
    // Without exclusions, behavior should be identical to before
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(PathFragment.create("vendor")),
            ImmutableList.of("**/node_modules"));

    assertThat(ignored.matchingEntry(PathFragment.create("vendor"))).isEqualTo("vendor");
    assertThat(ignored.matchingEntry(PathFragment.create("vendor/foo"))).isEqualTo("vendor");
    assertThat(ignored.matchingEntry(PathFragment.create("src/node_modules")))
        .isEqualTo("**/node_modules");
    assertThat(ignored.matchingEntry(PathFragment.create("src/lib"))).isNull();
  }

  // --- Glob exclusion patterns ---

  @Test
  public void globExclusionWithDoublestar() {
    // ignore **/build, exclude **/test_*/build (all test_ dirs' build folders are kept)
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/build"),
            ImmutableList.of("**/test_data/build"));

    // test_data/build at any depth should NOT be ignored
    assertThat(ignored.matchingEntry(PathFragment.create("test_data/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("foo/test_data/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("foo/bar/test_data/build"))).isNull();

    // Regular build directories should still be ignored
    assertThat(ignored.matchingEntry(PathFragment.create("src/build"))).isEqualTo("**/build");
    assertThat(ignored.matchingEntry(PathFragment.create("lib/build"))).isEqualTo("**/build");
  }

  @Test
  public void globExclusionWithSingleStar() {
    // ignore **/data, exclude apps/*/data (keep data dirs one level under apps/)
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/data"),
            ImmutableList.of("apps/*/data"));

    // apps/myapp/data should NOT be ignored
    assertThat(ignored.matchingEntry(PathFragment.create("apps/myapp/data"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("apps/other/data"))).isNull();

    // apps/nested/deep/data is NOT matched by apps/*/data (single * only matches one segment)
    assertThat(ignored.matchingEntry(PathFragment.create("apps/nested/deep/data")))
        .isEqualTo("**/data");

    // data directories elsewhere should still be ignored
    assertThat(ignored.matchingEntry(PathFragment.create("lib/data"))).isEqualTo("**/data");
  }

  // --- Tree pruning prevention ---

  @Test
  public void prefixPruningPreventedWhenExclusionBeneath() {
    // Prefix ignore "third_party", exclude "third_party/special/lib"
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(PathFragment.create("third_party")),
            ImmutableList.of(),
            ImmutableList.of("third_party/special/lib"));

    // third_party itself should NOT be pruned (exclusion is beneath it)
    assertThat(ignored.matchingEntry(PathFragment.create("third_party"))).isNull();

    // third_party/special should NOT be pruned (exclusion is beneath it)
    assertThat(ignored.matchingEntry(PathFragment.create("third_party/special"))).isNull();

    // third_party/special/lib matches the exclusion directly
    assertThat(ignored.matchingEntry(PathFragment.create("third_party/special/lib"))).isNull();

    // third_party/other has nothing excluded beneath it, so it CAN be pruned
    assertThat(ignored.matchingEntry(PathFragment.create("third_party/other")))
        .isEqualTo("third_party");
  }

  @Test
  public void patternPruningPreventedWhenExclusionBeneath() {
    // Glob ignore **/build, exclude "tools/search/build"
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/build"),
            ImmutableList.of("tools/search/build"));

    // tools/search/build matches the exclusion — not ignored
    assertThat(ignored.matchingEntry(PathFragment.create("tools/search/build"))).isNull();

    // other/build has no exclusion beneath it — still ignored
    assertThat(ignored.matchingEntry(PathFragment.create("other/build"))).isEqualTo("**/build");
  }

  @Test
  public void deepGlobExclusionPreventsIntermediatePruning() {
    // ignore "vendor", exclude "vendor/**/special"
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(PathFragment.create("vendor")),
            ImmutableList.of(),
            ImmutableList.of("vendor/**/special"));

    // vendor should NOT be pruned (glob exclusion can match children)
    assertThat(ignored.matchingEntry(PathFragment.create("vendor"))).isNull();

    // vendor/foo should NOT be pruned (glob exclusion might match vendor/foo/special)
    assertThat(ignored.matchingEntry(PathFragment.create("vendor/foo"))).isNull();

    // vendor/foo/special matches the exclusion
    assertThat(ignored.matchingEntry(PathFragment.create("vendor/foo/special"))).isNull();

    // vendor/foo/bar — no exclusion matches or could match a child here
    // Actually vendor/**/special CAN match vendor/foo/bar/special so we still can't prune
    assertThat(ignored.matchingEntry(PathFragment.create("vendor/foo/bar"))).isNull();
  }

  // --- filterForDirectory ---

  @Test
  public void filterForDirectoryPreservesRelevantExclusions() {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/build", "**/dist"),
            ImmutableList.of("tools/search/build", "other/dist"));

    // Filter for "tools" directory
    IgnoredSubdirectories filtered = ignored.filterForDirectory(PathFragment.create("tools"));

    // "tools/search/build" can match child of "tools", so it should be kept
    assertThat(filtered.matchingEntry(PathFragment.create("tools/search/build"))).isNull();

    // But "other/dist" cannot match anything under "tools", so the exclusion is dropped
    // This means tools/something/dist should still be ignored
    assertThat(filtered.matchingEntry(PathFragment.create("tools/something/dist")))
        .isEqualTo("**/dist");
  }

  @Test
  public void filterForDirectoryDropsIrrelevantExclusions() {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/build"),
            ImmutableList.of("apps/special/build"));

    // Filter for "lib" directory — the exclusion "apps/special/build" can't match under "lib"
    IgnoredSubdirectories filtered = ignored.filterForDirectory(PathFragment.create("lib"));

    // Without the exclusion, lib/build should be ignored normally
    assertThat(filtered.matchingEntry(PathFragment.create("lib/build"))).isEqualTo("**/build");
  }

  // --- Multiple exclusions ---

  @Test
  public void multipleExclusionsAllRespected() {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/build"),
            ImmutableList.of("tools/a/build", "tools/b/build", "lib/special/build"));

    assertThat(ignored.matchingEntry(PathFragment.create("tools/a/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("tools/b/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("lib/special/build"))).isNull();

    // Other builds are still ignored
    assertThat(ignored.matchingEntry(PathFragment.create("tools/c/build"))).isEqualTo("**/build");
    assertThat(ignored.matchingEntry(PathFragment.create("lib/other/build"))).isEqualTo("**/build");
  }

  // --- Union and withPrefix ---

  @Test
  public void unionMergesExclusions() {
    IgnoredSubdirectories a =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/build"),
            ImmutableList.of("tools/a/build"));

    IgnoredSubdirectories b =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/dist"),
            ImmutableList.of("lib/special/dist"));

    IgnoredSubdirectories merged = a.union(b);

    assertThat(merged.matchingEntry(PathFragment.create("tools/a/build"))).isNull();
    assertThat(merged.matchingEntry(PathFragment.create("lib/special/dist"))).isNull();
    assertThat(merged.matchingEntry(PathFragment.create("foo/build"))).isEqualTo("**/build");
    assertThat(merged.matchingEntry(PathFragment.create("foo/dist"))).isEqualTo("**/dist");
  }

  @Test
  public void withPrefixPrefixesExclusions() {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(),
            ImmutableList.of("**/build"),
            ImmutableList.of("special/build"));

    IgnoredSubdirectories prefixed = ignored.withPrefix(PathFragment.create("external/repo"));

    // The exclusion is now "external/repo/special/build"
    assertThat(prefixed.matchingEntry(PathFragment.create("external/repo/special/build"))).isNull();
    assertThat(prefixed.matchingEntry(PathFragment.create("external/repo/other/build")))
        .isEqualTo("external/repo/**/build");
  }

  // --- isEmpty ---

  @Test
  public void isEmptyWithOnlyExclusions() {
    // An instance with only exclusions and no ignore patterns is not useful but should work
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            ImmutableSet.of(), ImmutableList.of(), ImmutableList.of("foo/bar"));

    assertThat(ignored.isEmpty()).isFalse();
    assertThat(ignored.matchingEntry(PathFragment.create("anything"))).isNull();
  }

  @Test
  public void emptyHasNoExclusions() {
    assertThat(IgnoredSubdirectories.EMPTY.isEmpty()).isTrue();
    assertThat(IgnoredSubdirectories.EMPTY.matchingEntry(PathFragment.create("foo"))).isNull();
  }
}
