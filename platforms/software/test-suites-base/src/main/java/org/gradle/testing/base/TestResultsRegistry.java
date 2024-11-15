/*
 * Copyright 2024 the original author or authors.
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

package org.gradle.testing.base;

import org.gradle.api.Action;
import org.gradle.api.Buildable;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;

/**
 * A registry of binary test results, which are aggregated and reported.
 * <p>
 * This registry is buildable, as it wraps an executable step that performs the aggregation.
 * This registry is intended to be used as a finalizer, using the {@link org.gradle.api.Task#finalizedBy(Buildable, Action)}
 * method. This allows the aggregation to be performed after all test tasks have completed, and only
 * if the test tasks was executed. See the following example for how to use this registry:
 *
 * <pre>
 *      plugins {
 *          id("test-suite-base")
 *      }
 *
 *      tasks.register("myTest", Test) {
 *          finalizedBy(testing.results) {
 *              binaryResults.from(binaryResultsDirectory)
 *          }
 *      }
 * </pre>
 *
 * Running the {@code test} task will cause the results to be aggregated with any other
 * executed test and reported.
 */
public interface TestResultsRegistry extends Buildable {

    /**
     * The binary results that are to be aggregated and reported.
     */
    ConfigurableFileCollection getBinaryResults();

    DirectoryProperty getHtmlReport();

}
