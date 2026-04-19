/*
 * Copyright 2026 Tessera Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.tessera.core.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Programmatic JMH entry point for the Phase 0 benchmark harness (D-04).
 *
 * <p>Locates the repo root by walking up from the CWD until it finds the
 * parent {@code pom.xml}, writes results as JSON into
 * {@code .planning/benchmarks/<iso-timestamp>-<dataset>.json}, and also
 * updates a stable {@code latest-<dataset>.json} pointer that
 * {@code scripts/check_regression.sh} compares against the baseline.
 *
 * <p>Reads dataset size from system property {@code jmh.dataset} (default
 * {@code 100000}) and propagates it into the forked JMH JVM so
 * {@link BenchHarness} honors the same value.
 */
public final class JmhRunner {

    private JmhRunner() {}

    public static void main(String[] args) throws Exception {
        String dataset = System.getProperty("jmh.dataset", "100000");
        Path repoRoot = findRepoRoot();
        Path outDir = repoRoot.resolve(".planning/benchmarks");
        Files.createDirectories(outDir);
        String stamp = DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneOffset.UTC)
                .format(Instant.now())
                .replace(":", "-");
        Path outFile = outDir.resolve(stamp + "-" + dataset + ".json");

        new Runner(new OptionsBuilder()
                        .include("dev\\.tessera\\.core\\.bench\\..*Bench")
                        .resultFormat(ResultFormatType.JSON)
                        .result(outFile.toString())
                        .jvmArgsAppend("-Xmx4g", "-Djmh.dataset=" + dataset)
                        .shouldFailOnError(true)
                        .build())
                .run();

        Path latest = outDir.resolve("latest-" + dataset + ".json");
        Files.writeString(latest, Files.readString(outFile));
        System.out.println("JMH results: " + outFile);
        System.out.println("JMH latest : " + latest);
    }

    /** Walk up from CWD until a directory contains the parent {@code pom.xml}. */
    private static Path findRepoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null) {
            Path candidate = p.resolve("pom.xml");
            if (Files.exists(candidate) && isMultiModuleParent(candidate)) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("repo root not found (no parent pom.xml located)");
    }

    private static boolean isMultiModuleParent(Path pomPath) {
        try {
            String content = Files.readString(pomPath);
            return content.contains("<modules>");
        } catch (Exception e) {
            return false;
        }
    }
}
