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

package org.gradle.integtests.tooling.r812

import org.gradle.integtests.tooling.fixture.ProgressEvents
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.tooling.model.gradle.GradleBuild

@TargetGradleVersion('>=8.12')
class BuildActionModelFetchProgressEventsCrossVersionSpec extends ToolingApiSpecification {

    def "build action model requests have build operations"() {
        given:
        def listener = ProgressEvents.create()
        settingsFile << "rootProject.name = 'root'"

        expect:
        def buildModel = succeeds { connection ->
            connection.action(new FetchBuildEnvironment())
                .addProgressListener(listener, EnumSet.of(OperationType.GENERIC))
                .run()
        }

        and:
        buildModel != null

        and:
        def buildModelOp = listener.operation("Fetch model 'org.gradle.tooling.model.build.BuildEnvironment' for default scope")
        buildModelOp.descendants {
            it.descriptor.displayName == "Configure build"
        }.size() > 0
    }

    def "phased build action model requests have build operations"() {
        given:
        def listener = ProgressEvents.create()
        settingsFile << "rootProject.name = 'root'"

        expect:
        GradleBuild projectsLoadedModel
        BuildEnvironment buildModel
        succeeds { connection ->
            connection.action()
                .projectsLoaded(new FetchGradleBuild()) {
                    projectsLoadedModel = it
                }
                .buildFinished(new FetchBuildEnvironment()) {
                    buildModel = it
                }
                .build()
                .addProgressListener(listener, EnumSet.of(OperationType.GENERIC))
                .run()
            true
        }

        and:
        projectsLoadedModel != null
        buildModel != null

        and:
        def projectsLoadedModelOp = listener.operation("Fetch model 'org.gradle.tooling.model.gradle.GradleBuild' for default scope")
        projectsLoadedModelOp.descendants {
            it.descriptor.displayName == "Load build"
        }.size() > 0

        def buildModelOp = listener.operation("Fetch model 'org.gradle.tooling.model.build.BuildEnvironment' for default scope")
        buildModelOp.descendants {
            it.descriptor.displayName == "Configure build"
        }.size() > 0
    }
}
