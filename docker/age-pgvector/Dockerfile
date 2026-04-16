# Custom Docker image: Apache AGE + pgvector for Tessera.
# Extends the pinned AGE image with pgvector compiled from source.
# T-02.5-01: pgvector pinned to exact git tag v0.8.0 (not main branch).
#
# Copyright 2026 Tessera Contributors
# Licensed under the Apache License, Version 2.0

FROM apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed

RUN apt-get update && \
    apt-get install -y --no-install-recommends build-essential git ca-certificates postgresql-server-dev-16 && \
    cd /tmp && git clone --branch v0.8.0 --depth 1 https://github.com/pgvector/pgvector.git && \
    cd pgvector && make && make install && \
    cd / && rm -rf /tmp/pgvector && \
    apt-get remove -y build-essential git postgresql-server-dev-16 && \
    apt-get autoremove -y && rm -rf /var/lib/apt/lists/*
