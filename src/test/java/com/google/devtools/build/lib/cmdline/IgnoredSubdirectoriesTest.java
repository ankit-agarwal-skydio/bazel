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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link IgnoredSubdirectories}. */
@RunWith(JUnit4.class)
public class IgnoredSubdirectoriesTest {
  private static ImmutableSet<PathFragment> prefixes(String... prefixes) {
    return Arrays.stream(prefixes).map(PathFragment::create).collect(toImmutableSet());
  }

  private static ImmutableList<String> patterns(String... patterns) {
    return ImmutableList.copyOf(patterns);
  }

  @Test
  public void testSimpleUnion() throws Exception {
    IgnoredSubdirectories one = IgnoredSubdirectories.of(prefixes("prefix1"), patterns("pattern1"));
    IgnoredSubdirectories two = IgnoredSubdirectories.of(prefixes("prefix2"), patterns("pattern2"));
    IgnoredSubdirectories union = one.union(two);
    assertThat(union)
        .isEqualTo(
            IgnoredSubdirectories.of(
                prefixes("prefix1", "prefix2"), patterns("pattern1", "pattern2")));
  }

  @Test
  public void testSelfUnionNoop() throws Exception {
    IgnoredSubdirectories ignored = IgnoredSubdirectories.of(prefixes("pre"), patterns("pat"));
    assertThat(ignored.union(ignored)).isEqualTo(ignored);
  }

  @Test
  public void filterPrefixes() throws Exception {
    IgnoredSubdirectories original =
        IgnoredSubdirectories.of(prefixes("foo", "bar", "barbaz", "bar/qux"));
    IgnoredSubdirectories filtered = original.filterForDirectory(PathFragment.create("bar"));
    assertThat(filtered).isEqualTo(IgnoredSubdirectories.of(prefixes("bar", "bar/qux")));
  }

  @Test
  public void filterPatterns() throws Exception {
    IgnoredSubdirectories original =
        IgnoredSubdirectories.of(
            prefixes(),
            patterns(
                "**/sub",
                "foo",
                "bar/*/onesub",
                "bar/qux/**",
                "bar/ba*",
                "bar/not/*/twosub",
                "bar/**/barsub",
                "bar/sub/subsub"));
    IgnoredSubdirectories filtered = original.filterForDirectory(PathFragment.create("bar/sub"));
    assertThat(filtered)
        .isEqualTo(
            IgnoredSubdirectories.of(
                prefixes(), patterns("**/sub", "bar/*/onesub", "bar/**/barsub", "bar/sub/subsub")));
  }

  @Test
  public void filterPatternsForHiddenFiles() throws Exception {
    IgnoredSubdirectories original =
        IgnoredSubdirectories.of(
            prefixes(),
            patterns("not/sub", "*dden/sub", ".hidden/**/sub", ".hi*/*/sub", "*/sub", "**/sub"));
    IgnoredSubdirectories filtered = original.filterForDirectory(PathFragment.create(".hidden"));
    // Glob semantics say that "*dden" is not supposed to match ".hidden".
    // "**" and "*" and ".hi*" should, though.
    assertThat(filtered)
        .isEqualTo(
            IgnoredSubdirectories.of(
                prefixes(), patterns(".hidden/**/sub", ".hi*/*/sub", "*/sub", "**/sub")));
  }

  // --- Exclude parameter tests ---

  private static ImmutableList<String> excludes(String... excludes) {
    return ImmutableList.copyOf(excludes);
  }

  @Test
  public void excludeOverridesGlobIgnore() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes(), patterns("**/build"), excludes("tools/special/build"));

    assertThat(ignored.matchingEntry(PathFragment.create("tools/special/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("foo/build"))).isEqualTo("**/build");
    assertThat(ignored.matchingEntry(PathFragment.create("bar/baz/build"))).isEqualTo("**/build");
  }

  @Test
  public void excludeOverridesPrefixIgnore() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes("build"), patterns(), excludes("build/special"));

    assertThat(ignored.matchingEntry(PathFragment.create("build/special"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("build/other"))).isEqualTo("build");
    // "build" itself can't be pruned because it contains an excluded child
    assertThat(ignored.matchingEntry(PathFragment.create("build"))).isNull();
  }

  @Test
  public void noExclusionBehaviorUnchanged() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes("vendor"), patterns("**/node_modules"));

    assertThat(ignored.matchingEntry(PathFragment.create("vendor"))).isEqualTo("vendor");
    assertThat(ignored.matchingEntry(PathFragment.create("vendor/foo"))).isEqualTo("vendor");
    assertThat(ignored.matchingEntry(PathFragment.create("src/node_modules")))
        .isEqualTo("**/node_modules");
    assertThat(ignored.matchingEntry(PathFragment.create("src/lib"))).isNull();
  }

  @Test
  public void excludeWithDoublestarGlob() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes(), patterns("**/build"), excludes("**/test_data/build"));

    assertThat(ignored.matchingEntry(PathFragment.create("test_data/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("foo/test_data/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("src/build"))).isEqualTo("**/build");
  }

  @Test
  public void excludeWithSingleStarGlob() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes(), patterns("**/data"), excludes("apps/*/data"));

    assertThat(ignored.matchingEntry(PathFragment.create("apps/myapp/data"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("apps/other/data"))).isNull();
    // Single * only matches one segment
    assertThat(ignored.matchingEntry(PathFragment.create("apps/nested/deep/data")))
        .isEqualTo("**/data");
    assertThat(ignored.matchingEntry(PathFragment.create("lib/data"))).isEqualTo("**/data");
  }

  @Test
  public void prefixPruningPreventedByExclusionBeneath() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            prefixes("third_party"), patterns(), excludes("third_party/special/lib"));

    // Can't prune these — exclusion is beneath
    assertThat(ignored.matchingEntry(PathFragment.create("third_party"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("third_party/special"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("third_party/special/lib"))).isNull();
    // No exclusion beneath here — safe to prune
    assertThat(ignored.matchingEntry(PathFragment.create("third_party/other")))
        .isEqualTo("third_party");
  }

  @Test
  public void patternPruningPreventedByExclusionBeneath() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes(), patterns("**/build"), excludes("tools/search/build"));

    assertThat(ignored.matchingEntry(PathFragment.create("tools/search/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("other/build"))).isEqualTo("**/build");
  }

  @Test
  public void deepGlobExclusionPreventsIntermediatePruning() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes("vendor"), patterns(), excludes("vendor/**/special"));

    // vendor/**/special could match at any depth — can't prune any intermediate dir
    assertThat(ignored.matchingEntry(PathFragment.create("vendor"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("vendor/foo"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("vendor/foo/special"))).isNull();
  }

  @Test
  public void filterForDirectoryPreservesRelevantExclusions() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            prefixes(), patterns("**/build", "**/dist"), excludes("tools/search/build", "other/dist"));

    IgnoredSubdirectories filtered = ignored.filterForDirectory(PathFragment.create("tools"));
    // "tools/search/build" exclusion is kept
    assertThat(filtered.matchingEntry(PathFragment.create("tools/search/build"))).isNull();
    // "other/dist" exclusion is dropped — tools/something/dist is still ignored
    assertThat(filtered.matchingEntry(PathFragment.create("tools/something/dist")))
        .isEqualTo("**/dist");
  }

  @Test
  public void filterForDirectoryDropsIrrelevantExclusions() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes(), patterns("**/build"), excludes("apps/special/build"));

    IgnoredSubdirectories filtered = ignored.filterForDirectory(PathFragment.create("lib"));
    assertThat(filtered.matchingEntry(PathFragment.create("lib/build"))).isEqualTo("**/build");
  }

  @Test
  public void unionMergesExclusions() throws Exception {
    IgnoredSubdirectories a =
        IgnoredSubdirectories.of(prefixes(), patterns("**/build"), excludes("tools/a/build"));
    IgnoredSubdirectories b =
        IgnoredSubdirectories.of(prefixes(), patterns("**/dist"), excludes("lib/special/dist"));
    IgnoredSubdirectories merged = a.union(b);

    assertThat(merged.matchingEntry(PathFragment.create("tools/a/build"))).isNull();
    assertThat(merged.matchingEntry(PathFragment.create("lib/special/dist"))).isNull();
    assertThat(merged.matchingEntry(PathFragment.create("foo/build"))).isEqualTo("**/build");
    assertThat(merged.matchingEntry(PathFragment.create("foo/dist"))).isEqualTo("**/dist");
  }

  @Test
  public void withPrefixPrefixesExclusions() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes(), patterns("**/build"), excludes("special/build"));
    IgnoredSubdirectories prefixed = ignored.withPrefix(PathFragment.create("external/repo"));

    assertThat(prefixed.matchingEntry(PathFragment.create("external/repo/special/build"))).isNull();
    assertThat(prefixed.matchingEntry(PathFragment.create("external/repo/other/build")))
        .isEqualTo("external/repo/**/build");
  }

  @Test
  public void multipleExclusionsAllRespected() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(
            prefixes(),
            patterns("**/build"),
            excludes("tools/a/build", "tools/b/build", "lib/special/build"));

    assertThat(ignored.matchingEntry(PathFragment.create("tools/a/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("tools/b/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("lib/special/build"))).isNull();
    assertThat(ignored.matchingEntry(PathFragment.create("tools/c/build"))).isEqualTo("**/build");
  }

  @Test
  public void isEmptyWithExclusions() throws Exception {
    IgnoredSubdirectories ignored =
        IgnoredSubdirectories.of(prefixes(), patterns(), excludes("foo/bar"));
    assertThat(ignored.isEmpty()).isFalse();
  }
}
