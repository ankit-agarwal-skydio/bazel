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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A set of subdirectories to ignore during target pattern matching or globbing, with optional
 * exclusion patterns that override the ignore rules.
 *
 * <p>Exclusion patterns use the same glob semantics as ignore patterns. A directory that matches
 * both an ignore pattern and an exclusion pattern is NOT ignored.
 *
 * <p>When deciding whether to prune a subtree during directory traversal, exclusions are checked to
 * ensure we don't skip directories that contain excluded (un-ignored) children.
 */
public final class IgnoredSubdirectories {
  public static final IgnoredSubdirectories EMPTY =
      new IgnoredSubdirectories(ImmutableSet.of(), ImmutableList.of(), ImmutableList.of());

  private static final Splitter SLASH_SPLITTER = Splitter.on("/");

  private final ImmutableSet<PathFragment> prefixes;

  // String[] is mutable; we keep the split version because that's faster to match and the non-split
  // one because that allows for simpler equality checking and then matchingEntry() doesn't need to
  // allocate new objects.
  private final ImmutableList<String> patterns;
  private final ImmutableList<String[]> splitPatterns;

  private final ImmutableList<String> excludePatterns;
  private final ImmutableList<String[]> splitExcludePatterns;

  private static class Codec implements ObjectCodec<IgnoredSubdirectories> {
    private static final Codec INSTANCE = new Codec();

    @Override
    public Class<? extends IgnoredSubdirectories> getEncodedClass() {
      return IgnoredSubdirectories.class;
    }

    @Override
    public void serialize(
        SerializationContext context, IgnoredSubdirectories obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.serialize(obj.prefixes, codedOut);
      context.serialize(obj.patterns, codedOut);
      context.serialize(obj.excludePatterns, codedOut);
    }

    @Override
    public IgnoredSubdirectories deserialize(
        DeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      ImmutableSet<PathFragment> prefixes = context.deserialize(codedIn);
      ImmutableList<String> patterns = context.deserialize(codedIn);
      ImmutableList<String> excludePatterns = context.deserialize(codedIn);

      return new IgnoredSubdirectories(prefixes, patterns, excludePatterns);
    }
  }

  private IgnoredSubdirectories(
      ImmutableSet<PathFragment> prefixes,
      ImmutableList<String> patterns,
      ImmutableList<String> excludePatterns) {
    this.prefixes = prefixes;
    this.patterns = patterns;
    this.splitPatterns =
        patterns.stream()
            .map(p -> Iterables.toArray(SLASH_SPLITTER.split(p), String.class))
            .collect(toImmutableList());
    this.excludePatterns = excludePatterns;
    this.splitExcludePatterns =
        excludePatterns.stream()
            .map(p -> Iterables.toArray(SLASH_SPLITTER.split(p), String.class))
            .collect(toImmutableList());
  }

  public static IgnoredSubdirectories of(ImmutableSet<PathFragment> prefixes) {
    return of(prefixes, ImmutableList.of(), ImmutableList.of());
  }

  public static IgnoredSubdirectories of(
      ImmutableSet<PathFragment> prefixes, ImmutableList<String> patterns) {
    return of(prefixes, patterns, ImmutableList.of());
  }

  public static IgnoredSubdirectories of(
      ImmutableSet<PathFragment> prefixes,
      ImmutableList<String> patterns,
      ImmutableList<String> excludePatterns) {
    if (prefixes.isEmpty() && patterns.isEmpty() && excludePatterns.isEmpty()) {
      return EMPTY;
    }

    for (PathFragment prefix : prefixes) {
      Preconditions.checkArgument(!prefix.isAbsolute());
    }

    return new IgnoredSubdirectories(prefixes, patterns, excludePatterns);
  }

  public IgnoredSubdirectories withPrefix(PathFragment prefix) {
    Preconditions.checkArgument(!prefix.isAbsolute());

    ImmutableSet<PathFragment> prefixedPrefixes =
        prefixes.stream().map(prefix::getRelative).collect(toImmutableSet());

    ImmutableList<String> prefixedPatterns =
        patterns.stream().map(p -> prefix + "/" + p).collect(toImmutableList());

    ImmutableList<String> prefixedExcludePatterns =
        excludePatterns.stream().map(p -> prefix + "/" + p).collect(toImmutableList());

    return new IgnoredSubdirectories(prefixedPrefixes, prefixedPatterns, prefixedExcludePatterns);
  }

  public IgnoredSubdirectories union(IgnoredSubdirectories other) {
    return new IgnoredSubdirectories(
        ImmutableSet.<PathFragment>builder().addAll(prefixes).addAll(other.prefixes).build(),
        ImmutableList.copyOf(
            ImmutableSet.<String>builder().addAll(patterns).addAll(other.patterns).build()),
        ImmutableList.copyOf(
            ImmutableSet.<String>builder()
                .addAll(excludePatterns)
                .addAll(other.excludePatterns)
                .build()));
  }

  /** Filters out entries that cannot match anything under {@code directory}. */
  public IgnoredSubdirectories filterForDirectory(PathFragment directory) {
    ImmutableSet<PathFragment> filteredPrefixes =
        prefixes.stream().filter(p -> p.startsWith(directory)).collect(toImmutableSet());

    String[] splitDirectory =
        Iterables.toArray(SLASH_SPLITTER.split(directory.getPathString()), String.class);
    ImmutableList.Builder<String> filteredPatterns = ImmutableList.builder();
    for (int i = 0; i < patterns.size(); i++) {
      if (UnixGlob.canMatchChild(splitPatterns.get(i), splitDirectory)) {
        filteredPatterns.add(patterns.get(i));
      }
    }

    ImmutableList.Builder<String> filteredExcludePatterns = ImmutableList.builder();
    for (int i = 0; i < excludePatterns.size(); i++) {
      if (UnixGlob.canMatchChild(splitExcludePatterns.get(i), splitDirectory)) {
        filteredExcludePatterns.add(excludePatterns.get(i));
      }
    }

    return new IgnoredSubdirectories(
        filteredPrefixes, filteredPatterns.build(), filteredExcludePatterns.build());
  }

  public ImmutableSet<PathFragment> prefixes() {
    return prefixes;
  }

  public boolean isEmpty() {
    return this.prefixes.isEmpty() && this.patterns.isEmpty() && this.excludePatterns.isEmpty();
  }

  /**
   * Checks whether every path in this instance can conceivably match something under {@code
   * directory}.
   */
  public boolean allPathsAreUnder(PathFragment directory) {
    for (PathFragment prefix : prefixes) {
      if (!prefix.startsWith(directory)) {
        return false;
      }

      if (prefix.equals(directory)) {
        return false;
      }
    }

    return true;
  }

  /**
   * Returns the ignore entry that matches a given directory, or {@code null} if the directory
   * should not be ignored.
   *
   * <p>This method handles two cases:
   *
   * <ul>
   *   <li><b>Leaf check:</b> "Is this specific package ignored?" (used by PackageLookupFunction).
   *       A directory that matches an exclusion pattern is not ignored.
   *   <li><b>Pruning check:</b> "Can we skip this entire subtree?" (used by
   *       ProcessPackageDirectory). A subtree cannot be pruned if any exclusion pattern could match
   *       a descendant directory, because that descendant must remain visible.
   * </ul>
   */
  @Nullable
  public String matchingEntry(PathFragment directory) {
    String[] segmentArray = Iterables.toArray(directory.segments(), String.class);

    // First: does this directory directly match an exclusion? If so, never ignore it.
    if (matchesAnyExclusion(segmentArray)) {
      return null;
    }

    // Check prefix-based ignores (from .bazelignore or rooted patterns)
    for (PathFragment prefix : prefixes) {
      if (directory.startsWith(prefix)) {
        // Before pruning this subtree, check whether any exclusion could match a descendant.
        // If so, we must not prune — an un-ignored directory may live underneath.
        if (anyExclusionCanMatchChild(segmentArray)) {
          return null;
        }
        return prefix.getPathString();
      }
    }

    // Check glob-based ignore patterns
    for (int i = 0; i < patterns.size(); i++) {
      if (UnixGlob.matchesPrefix(splitPatterns.get(i), segmentArray)) {
        // Same guard: don't prune if an exclusion could match a descendant.
        if (anyExclusionCanMatchChild(segmentArray)) {
          return null;
        }
        return patterns.get(i);
      }
    }

    return null;
  }

  /** Returns true if the directory path directly matches any exclusion pattern. */
  private boolean matchesAnyExclusion(String[] segmentArray) {
    for (int i = 0; i < splitExcludePatterns.size(); i++) {
      if (UnixGlob.matchesPrefix(splitExcludePatterns.get(i), segmentArray)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns true if any exclusion pattern could match a descendant of the given directory.
   *
   * <p>This prevents premature tree pruning: if an exclusion exists for
   * {@code tools/channel_search/build} and we're currently at {@code tools}, we must not prune
   * even though a {@code **&#47;build} pattern matches {@code tools} as a prefix, because the
   * excluded child needs to remain reachable.
   */
  private boolean anyExclusionCanMatchChild(String[] segmentArray) {
    for (int i = 0; i < splitExcludePatterns.size(); i++) {
      if (UnixGlob.canMatchChild(splitExcludePatterns.get(i), segmentArray)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof IgnoredSubdirectories that)) {
      return false;
    }

    // splitPatterns/splitExcludePatterns are derived from patterns/excludePatterns
    return Objects.equals(this.prefixes, that.prefixes)
        && Objects.equals(this.patterns, that.patterns)
        && Objects.equals(this.excludePatterns, that.excludePatterns);
  }

  @Override
  public int hashCode() {
    return Objects.hash(prefixes, patterns, excludePatterns);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper("IgnoredSubdirectories")
        .add("prefixes", prefixes)
        .add("patterns", patterns)
        .add("excludePatterns", excludePatterns)
        .toString();
  }
}
