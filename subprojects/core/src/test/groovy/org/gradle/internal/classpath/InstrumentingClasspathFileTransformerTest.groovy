/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.internal.classpath

import org.gradle.api.file.RelativePath
import org.gradle.api.internal.file.TestFiles
import org.gradle.cache.FileLockManager
import org.gradle.internal.Pair
import org.gradle.internal.classanalysis.AsmConstants
import org.gradle.internal.classpath.InstrumentingClasspathFileTransformer.Policy
import org.gradle.internal.classpath.types.GradleCoreInstrumentingTypeRegistry
import org.gradle.internal.classpath.types.InstrumentingTypeRegistry
import org.gradle.internal.hash.Hasher
import org.gradle.test.fixtures.archive.JarTestFixture
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import org.objectweb.asm.ClassVisitor
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

import static org.gradle.internal.classpath.InstrumentingClasspathFileTransformer.instrumentForLoadingWithAgent
import static org.gradle.internal.classpath.InstrumentingClasspathFileTransformer.instrumentForLoadingWithClassLoader

class InstrumentingClasspathFileTransformerTest extends Specification {
    @Rule
    TestNameTestDirectoryProvider testDirectoryProvider = new TestNameTestDirectoryProvider(getClass())
    def testDir = testDirectoryProvider.testDirectory

    def cacheDir = testDir.createDir("cached")
    def classpathWalker = new ClasspathWalker(TestFiles.fileSystem())
    def classpathBuilder = new ClasspathBuilder(TestFiles.tmpDirTemporaryFileProvider(testDirectoryProvider.createDir("tmp")))
    def fileLockManager = Stub(FileLockManager)
    def fileSystemAccess = TestFiles.fileSystemAccess()
    def gradleCoreInstrumentingRegistry = Stub(GradleCoreInstrumentingTypeRegistry) {
        getInstrumentedFileHash() >> Optional.empty()
    }
    def typeRegistry = Stub(InstrumentingTypeRegistry)

    def "instrumentation with #policy preserves classes"() {
        given:
        def testFile = jar(testDir.file("thing.jar")) {
            manifest {}
            entry("Foo.class", classOne())
        }

        expect:
        with(jarFixture(transform(testFile, transformerWithPolicy(policy)), expectManifest)) {
            assertContainsFile("Foo.class")
        }

        where:
        policy                                | expectManifest
        instrumentForLoadingWithAgent()       | false
        instrumentForLoadingWithClassLoader() | true
    }

    def "agent instrumentation removes non multi-release manifest"() {
        given:
        def testFile = jar(testDir.file("thing.jar")) {
            manifest {
                mainAttributes.putValue("Some-Value", "theValue")
            }
            entry("Foo.class", classOne())
        }

        expect:
        with(jarFixture(transform(testFile, transformerWithPolicy(instrumentForLoadingWithAgent())), false)) {
            assertNotContainsFile(JarFile.MANIFEST_NAME)
        }
    }

    def "legacy instrumentation copies manifest into transformed jar"() {
        given:
        def testFile = jar(testDir.file("thing.jar")) {
            manifest {
                mainAttributes.putValue("Some-Value", "theValue")
            }
            entry("Foo.class", classOne())
        }

        expect:
        with(jarFixture(transform(testFile, transformerWithPolicy(instrumentForLoadingWithClassLoader())), true)) {
            assertContainsFile(JarFile.MANIFEST_NAME)

            assert manifest.mainAttributes.getValue("Some-Value") == "theValue"
        }
    }

    def "instrumentation with #policy produces multi-release jar"() {
        given:
        def testFile = jar(testDir.file("thing.jar")) {
            manifest {
                mainAttributes.putValue("Multi-Release", "true")
            }

            entry("Foo.class", classOne())
            versionedEntry(AsmConstants.MAX_SUPPORTED_JAVA_VERSION, "Foo.class", classOne())
        }

        expect:
        with(jarFixture(transform(testFile, transformerWithPolicy(policy)))) {
            assertIsMultiRelease()
            assertContainsFile("Foo.class")
            assertContainsVersioned(AsmConstants.MAX_SUPPORTED_JAVA_VERSION, "Foo.class")
        }

        where:
        policy << [instrumentForLoadingWithAgent(), instrumentForLoadingWithClassLoader()]
    }

    def "instrumentation with #policy removes unsupported versioned directories from multi-release jar"() {
        given:
        def testFile = jar(testDir.file("thing.jar")) {
            manifest {
                mainAttributes.putValue("Multi-Release", "true")
            }

            entry("Foo.class", classOne())
            versionedEntry(AsmConstants.MAX_SUPPORTED_JAVA_VERSION + 1, "Foo.class", classOne())
        }

        expect:
        with(jarFixture(transform(testFile, transformerWithPolicy(policy)))) {
            assertContainsFile("Foo.class")
            assertNotContainsVersioned(AsmConstants.MAX_SUPPORTED_JAVA_VERSION, "Foo.class")
        }

        where:
        policy << [instrumentForLoadingWithAgent(), instrumentForLoadingWithClassLoader()]
    }

    def "agent instrumentation removes resources from transformed jar"() {
        given:
        def testFile = jar(testDir.file("thing.jar")) {
            manifest {}

            entry("Foo.class", classOne())
            entry("resource.txt", "")
        }

        expect:
        with(jarFixture(transform(testFile, transformerWithPolicy(instrumentForLoadingWithAgent())), false)) {
            assertNotContainsFile("resource.txt")
        }
    }

    def "legacy instrumentation copies resources into transformed jar"() {
        given:
        def testFile = jar(testDir.file("thing.jar")) {
            manifest {}

            entry("Foo.class", classOne())
            entry("resource.txt", "resource body")
        }

        expect:
        with(jarFixture(transform(testFile, transformerWithPolicy(instrumentForLoadingWithClassLoader())))) {
            assertFileContent("resource.txt", "resource body")
        }
    }

    def "legacy instrumentation removes unsupported versioned resources from transformed jar"() {
        given:
        def testFile = jar(testDir.file("thing.jar")) {
            manifest {
                mainAttributes.putValue("Multi-Release", "true")
            }

            entry("Foo.class", classOne())
            entry("resource.txt", "resource body")
            versionedEntry(AsmConstants.MAX_SUPPORTED_JAVA_VERSION, "resource.txt", "resource MAX body")
            versionedEntry(AsmConstants.MAX_SUPPORTED_JAVA_VERSION + 1, "resource.txt", "resource unsupported body")
        }

        expect:
        with(jarFixture(transform(testFile, transformerWithPolicy(instrumentForLoadingWithClassLoader())))) {
            assertContainsVersioned(AsmConstants.MAX_SUPPORTED_JAVA_VERSION, "resource.txt")
            assertNotContainsVersioned(AsmConstants.MAX_SUPPORTED_JAVA_VERSION + 1, "resource.txt")
        }
    }

    def "multi-release jar must have manifest attribute to be processed by legacy instrumentation"() {
        given:
        def testFile = jar(testDir.file("thing.jar")) {
            manifest {
                // No Multi-Release attribute in the manifest.
            }

            entry("Foo.class", classOne())
            versionedEntry(AsmConstants.MAX_SUPPORTED_JAVA_VERSION + 1, "Foo.class", classOne())
        }

        expect:
        with(jarFixture(transform(testFile, transformerWithPolicy(instrumentForLoadingWithClassLoader())))) {
            assertContainsVersioned(AsmConstants.MAX_SUPPORTED_JAVA_VERSION + 1, "Foo.class")
        }
    }

    private File transform(File file, InstrumentingClasspathFileTransformer transformer) {
        return transformer.transform(file, fileSystemAccess.read(file.path), cacheDir, typeRegistry)
    }

    private InstrumentingClasspathFileTransformer transformerWithPolicy(Policy policy) {
        return new InstrumentingClasspathFileTransformer(fileLockManager, classpathWalker, classpathBuilder, policy, new NoOpTransformer(), gradleCoreInstrumentingRegistry)
    }

    private static class NoOpTransformer implements CachedClasspathTransformer.Transform {
        @Override
        void applyConfigurationTo(Hasher hasher) {
        }

        @Override
        Pair<RelativePath, ClassVisitor> apply(ClasspathEntryVisitor.Entry entry, ClassVisitor visitor, ClassData classData) {
            return Pair.of(entry.path, visitor)
        }
    }

    byte[] classOne() {
        return getClass().classLoader.getResource(SystemPropertyAccessingThing.name.replace('.', '/') + ".class").bytes
    }

    private JarTestFixture jarFixture(File transformedJar, boolean expectManifest = true) {
        return new JarTestFixture(transformedJar, 'UTF-8', null, expectManifest)
    }

    private File jar(File jarFile, @DelegatesTo(JarBuilder) Closure<?> closure) {
        classpathBuilder.jar(jarFile) {
            closure.setDelegate(new JarBuilder(it))
            closure.setResolveStrategy(Closure.DELEGATE_FIRST)
            closure()
        }

        jarFile
    }

    static class JarBuilder {
        private final ClasspathBuilder.EntryBuilder builder
        private boolean hasManifest

        JarBuilder(ClasspathBuilder.EntryBuilder builder) {
            this.builder = builder
        }

        def manifest(@DelegatesTo(value = Manifest, strategy = Closure.DELEGATE_FIRST) Closure<?> closure) {
            def man = new Manifest()
            man.mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")

            closure.setDelegate(man)
            closure()

            def baos = new ByteArrayOutputStream()
            man.write(baos)
            builder.put(JarFile.MANIFEST_NAME, baos.toByteArray())
            hasManifest = true
            this
        }

        def versionedEntry(int version, String path, byte[] bytes) {
            entry(JarTestFixture.toVersionedPath(version, path), bytes)
        }

        def versionedEntry(int version, String path, String body) {
            entry(JarTestFixture.toVersionedPath(version, path), body)
        }

        def entry(String path, byte[] bytes) {
            checkManifestWritten()
            builder.put(path, bytes)
        }

        def entry(String path, String body) {
            entry(path, body.getBytes(StandardCharsets.UTF_8))
        }

        private def checkManifestWritten() {
            assert hasManifest : "Must have manifest before entries"
        }
    }
}