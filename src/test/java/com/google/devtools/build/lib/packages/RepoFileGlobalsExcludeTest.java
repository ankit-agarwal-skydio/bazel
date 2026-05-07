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

package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for the exclude parameter in ignore_directories() as parsed by RepoFileGlobals.
 *
 * <p>These test the Starlark-level API: that REPO.bazel files can call
 * ignore_directories(["**/build"], exclude=["tools/special/build"]).
 */
@RunWith(JUnit4.class)
public class RepoFileGlobalsExcludeTest {

  @Test
  public void excludeParameterStored() throws Exception {
    RepoThreadContext context = new RepoThreadContext();
    context.setIgnoredDirectories(ImmutableList.of("**/build", "**/node_modules"));
    context.setExcludedDirectories(ImmutableList.of("tools/special/build", "apps/*/node_modules"));

    assertThat(context.getIgnoredDirectories())
        .containsExactly("**/build", "**/node_modules");
    assertThat(context.getExcludedDirectories())
        .containsExactly("tools/special/build", "apps/*/node_modules");
  }

  @Test
  public void excludeParameterDefaultsToEmpty() throws Exception {
    RepoThreadContext context = new RepoThreadContext();
    context.setIgnoredDirectories(ImmutableList.of("**/build"));

    assertThat(context.getExcludedDirectories()).isEmpty();
  }

  @Test
  public void excludeParameterEmptyList() throws Exception {
    RepoThreadContext context = new RepoThreadContext();
    context.setIgnoredDirectories(ImmutableList.of("**/build"));
    context.setExcludedDirectories(ImmutableList.of());

    assertThat(context.getExcludedDirectories()).isEmpty();
    assertThat(context.getIgnoredDirectories()).containsExactly("**/build");
  }
}
