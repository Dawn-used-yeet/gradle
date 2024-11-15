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

package org.gradle.testing.base.internal;

import com.google.common.collect.ImmutableSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.tasks.TaskDependencyFactory;
import org.gradle.api.internal.tasks.TaskDependencyInternal;
import org.gradle.api.internal.tasks.testing.junit.result.AggregateTestResultsProvider;
import org.gradle.api.internal.tasks.testing.junit.result.BinaryResultBackedTestResultsProvider;
import org.gradle.api.internal.tasks.testing.junit.result.TestResultsProvider;
import org.gradle.api.internal.tasks.testing.report.HtmlTestReport;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationRunner;
import org.gradle.testing.base.TestResultsRegistry;

import javax.inject.Inject;
import java.util.LinkedList;
import java.util.List;

import static org.gradle.internal.concurrent.CompositeStoppable.stoppable;
import static org.gradle.util.internal.CollectionUtils.collect;

// TODO: A lot of this should probably be merged with AggregateTestReport / DefaultAggregateTestReport

public abstract class DefaultTestResultsRegistry implements TestResultsRegistry {

    private final TaskDependencyInternal buildDependencies;

    @Inject
    public DefaultTestResultsRegistry(
        TaskDependencyFactory taskDependencyFactory,
        TaskContainer tasks
    ) {
        TaskProvider<AggregateTestReportGenerator> aggregateReportTask =
            tasks.register("generateAggregateReport", AggregateTestReportGenerator.class, t -> {
                t.getBinaryResults().from(getBinaryResults());
                t.getDestinationDirectory().set(getHtmlReport());
            });

        this.buildDependencies = taskDependencyFactory.configurableDependency(ImmutableSet.of(aggregateReportTask));
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return buildDependencies;
    }

    public static abstract class AggregateTestReportGenerator extends DefaultTask {

        private final BuildOperationRunner buildOperationRunner;
        private final BuildOperationExecutor buildOperationExecutor;

        @Inject
        public AggregateTestReportGenerator(
            BuildOperationRunner buildOperationRunner,
            BuildOperationExecutor buildOperationExecutor
        ) {
            this.buildOperationRunner = buildOperationRunner;
            this.buildOperationExecutor = buildOperationExecutor;
        }

        @InputFiles
        @SkipWhenEmpty
        @IgnoreEmptyDirectories
        @PathSensitive(PathSensitivity.NONE)
        public abstract ConfigurableFileCollection getBinaryResults();

        @OutputDirectory
        public abstract DirectoryProperty getDestinationDirectory();

        // TODO: The below logic was copied from the TestReport task. It depends on some JVM-specific classes,
        // but will not anymore after Gradle 9.0. After then, we should move TestReport into :testing-base and
        // merge this logic with the logic there.

        @TaskAction
        void generateReport() {
            TestResultsProvider resultsProvider = createAggregateProvider();
            try {
                if (resultsProvider.isHasResults()) {
                    HtmlTestReport testReport = new HtmlTestReport(buildOperationRunner, buildOperationExecutor);
                    testReport.generateReport(resultsProvider, getDestinationDirectory().get().getAsFile());
                } else {
                    getLogger().info("{} - no binary test results found in dirs: {}.", getPath(), getBinaryResults().getFiles());
                    setDidWork(false);
                }
            } finally {
                stoppable(resultsProvider).stop();
            }
        }

        private TestResultsProvider createAggregateProvider() {
            List<TestResultsProvider> resultsProviders = new LinkedList<TestResultsProvider>();
            try {
                FileCollection resultDirs = getBinaryResults();
                if (resultDirs.getFiles().size() == 1) {
                    return new BinaryResultBackedTestResultsProvider(resultDirs.getSingleFile());
                } else {
                    return new AggregateTestResultsProvider(collect(resultDirs, resultsProviders, BinaryResultBackedTestResultsProvider::new));
                }
            } catch (RuntimeException e) {
                stoppable(resultsProviders).stop();
                throw e;
            }
        }

    }

}
