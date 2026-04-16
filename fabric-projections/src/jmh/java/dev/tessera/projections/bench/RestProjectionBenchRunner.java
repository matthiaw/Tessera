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
package dev.tessera.projections.bench;

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
 * Programmatic JMH entry point for REST projection benchmarks.
 * Run via {@code ./mvnw -pl fabric-projections -Pjmh verify}.
 */
public final class RestProjectionBenchRunner {

    private RestProjectionBenchRunner() {}

    public static void main(String[] args) throws Exception {
        String dataset = System.getProperty("jmh.dataset", "100000");
        Path repoRoot = findRepoRoot();
        Path outDir = repoRoot.resolve(".planning/benchmarks");
        Files.createDirectories(outDir);
        String stamp = DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneOffset.UTC)
                .format(Instant.now())
                .replace(":", "-");
        Path outFile = outDir.resolve("rest-projection-" + stamp + "-" + dataset + ".json");

        new Runner(new OptionsBuilder()
                        .include("dev\\.tessera\\.projections\\.bench\\..*Bench")
                        .resultFormat(ResultFormatType.JSON)
                        .result(outFile.toString())
                        .jvmArgsAppend("-Djmh.dataset=" + dataset)
                        .shouldFailOnError(true)
                        .build())
                .run();

        Path latest = outDir.resolve("rest-projection-latest-" + dataset + ".json");
        Files.writeString(latest, Files.readString(outFile));
        System.out.println("JMH results: " + outFile);
        System.out.println("JMH latest : " + latest);
    }

    private static Path findRepoRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null) {
            Path candidate = p.resolve("pom.xml");
            if (java.nio.file.Files.exists(candidate)) {
                try {
                    String content = Files.readString(candidate);
                    if (content.contains("<modules>")) {
                        return p;
                    }
                } catch (Exception e) {
                    // continue
                }
            }
            p = p.getParent();
        }
        throw new IllegalStateException("repo root not found");
    }
}
