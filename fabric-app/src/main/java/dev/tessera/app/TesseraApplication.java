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
package dev.tessera.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tessera main entry point. Component scan is rooted at {@code dev.tessera}
 * so {@code fabric-core} beans — including {@code LockProviderConfig}
 * (which activates {@code @EnableSchedulerLock} for the Outbox poller) — are
 * picked up automatically.
 */
@SpringBootApplication(scanBasePackages = "dev.tessera")
@EnableScheduling
public class TesseraApplication {
    public static void main(String[] args) {
        SpringApplication.run(TesseraApplication.class, args);
    }
}
