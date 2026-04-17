# Postgres Transparent Data Encryption (TDE) Deployment Runbook

<!--
  Copyright 2026 Tessera Contributors
  Licensed under the Apache License, Version 2.0
-->

## 1. Overview

### Purpose

Encrypt the PostgreSQL 16 data directory at rest using LUKS/dm-crypt on an IONOS VPS.
This satisfies requirement SEC-03 (Postgres data-at-rest encryption) for the Tessera
self-hosted deployment.

### Scope

Single-server self-hosted deployment running Docker Compose with three containers:

| Container        | Image                    | Role                        |
|------------------|--------------------------|-----------------------------|
| `postgres-age`   | `apache/age` (PG16)     | Primary data store + graph  |
| `tessera`        | Tessera Spring Boot app  | Application server          |
| `vault`          | HashiCorp Vault          | Secrets management (KMS)    |

The Postgres data directory (`PGDATA`) resides on a LUKS2-encrypted block device.
All data written by Postgres -- tables, indexes, WAL, temp files -- is encrypted
transparently by the kernel before hitting disk.

### Prerequisites

| Requirement                         | Notes                                              |
|-------------------------------------|----------------------------------------------------|
| Root or sudo access on the IONOS VPS | Required for `cryptsetup`, `mount`, and `fstab`   |
| Debian 12 / Ubuntu 22.04+          | Commands in this runbook target `apt`-based distros |
| Additional block device or LVM partition | e.g., `/dev/sdb` or an IONOS Cloud Block Storage volume for Postgres data |
| `cryptsetup` package installed      | See Section 2, step 1                              |
| Docker 20.10+ and Docker Compose v2 | Already required by the Tessera deployment         |
| CPU with AES-NI support             | All modern IONOS VPS instances support this         |

---

## 2. LUKS Volume Setup

> **WARNING:** Step 2 (`luksFormat`) is destructive. It will erase all data on the
> target device. Double-check the device path before proceeding.

1. **Install cryptsetup:**

   ```bash
   apt-get update && apt-get install -y cryptsetup
   ```

2. **Create LUKS2 container on the block device:**

   ```bash
   cryptsetup luksFormat \
     --type luks2 \
     --cipher aes-xts-plain64 \
     --key-size 512 \
     --hash sha512 \
     /dev/sdX
   ```

   You will be prompted to type `YES` (uppercase) and enter a passphrase.
   Store this passphrase securely in Vault (see Section 4).

3. **Open the LUKS volume:**

   ```bash
   cryptsetup luksOpen /dev/sdX pg_encrypted
   ```

   This creates the device mapper node at `/dev/mapper/pg_encrypted`.

4. **Create an ext4 filesystem on the encrypted volume:**

   ```bash
   mkfs.ext4 /dev/mapper/pg_encrypted
   ```

5. **Create the mount point and mount:**

   ```bash
   mkdir -p /var/lib/postgresql/data
   mount /dev/mapper/pg_encrypted /var/lib/postgresql/data
   ```

6. **Set ownership for the Postgres container user (UID 999):**

   ```bash
   chown -R 999:999 /var/lib/postgresql/data
   ```

7. **Verify the volume is active and encrypted:**

   ```bash
   cryptsetup status pg_encrypted
   ```

   Expected output includes:
   ```
   /dev/mapper/pg_encrypted is active.
     type:    LUKS2
     cipher:  aes-xts-plain64
     keysize: 512 bits
     key location: keyring
     device:  /dev/sdX
   ```

---

## 3. Postgres Data Directory Configuration

### Docker Compose Volume Mount

Update `docker-compose.yml` to mount the encrypted volume into the `postgres-age`
container:

```yaml
services:
  postgres-age:
    image: apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed
    environment:
      POSTGRES_USER: tessera
      POSTGRES_PASSWORD: "${PG_PASSWORD}"
      POSTGRES_DB: tessera
      PGDATA: /var/lib/postgresql/data/pgdata
    volumes:
      - /var/lib/postgresql/data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U tessera"]
      interval: 10s
      timeout: 5s
      retries: 5
```

Key points:

- The host path `/var/lib/postgresql/data` is the LUKS-encrypted mount from Section 2.
- `PGDATA` is set to a subdirectory (`/var/lib/postgresql/data/pgdata`) to avoid
  Postgres complaining about a non-empty directory (the `lost+found` from ext4).
- The Postgres container writes all data -- tables, indexes, WAL segments, temporary
  files -- to this encrypted volume.

### Verify Encryption Is Active

After starting the container, confirm from the host:

```bash
# Verify the LUKS device is still active
cryptsetup status pg_encrypted

# Verify Postgres is writing to the encrypted mount
docker exec tessera-postgres-age psql -U tessera -c "SHOW data_directory;"
# Expected: /var/lib/postgresql/data/pgdata

# Verify the mount is in use
mount | grep pg_encrypted
# Expected: /dev/mapper/pg_encrypted on /var/lib/postgresql/data type ext4 (rw,relatime)
```

---

## 4. Auto-Unlock on Boot (with Key File)

Two options are documented: automatic unlock via key file (default) and manual
unlock for higher-security environments.

### Option A: Automatic Unlock (Key File)

> **SECURITY NOTE:** The key file resides on the root partition. If the root
> partition is compromised, the attacker gains access to the LUKS key. This is
> acceptable for single-VPS deployments where root compromise already implies
> full data access. For higher security requirements, use Option B.

1. **Generate a random key file:**

   ```bash
   dd if=/dev/urandom of=/root/.pg_luks_key bs=512 count=1
   ```

2. **Restrict permissions:**

   ```bash
   chmod 0400 /root/.pg_luks_key
   chown root:root /root/.pg_luks_key
   ```

3. **Add the key file to a LUKS key slot:**

   ```bash
   cryptsetup luksAddKey /dev/sdX /root/.pg_luks_key
   ```

   You will be prompted for the existing passphrase from Section 2.

4. **Configure `/etc/crypttab` for automatic unlock on boot:**

   ```bash
   echo "pg_encrypted /dev/sdX /root/.pg_luks_key luks" >> /etc/crypttab
   ```

5. **Configure `/etc/fstab` for automatic mount:**

   ```bash
   echo "/dev/mapper/pg_encrypted /var/lib/postgresql/data ext4 defaults 0 2" >> /etc/fstab
   ```

6. **Test the configuration (without rebooting):**

   ```bash
   umount /var/lib/postgresql/data
   cryptsetup luksClose pg_encrypted
   cryptsetup luksOpen /dev/sdX pg_encrypted --key-file /root/.pg_luks_key
   mount /var/lib/postgresql/data
   ls -la /var/lib/postgresql/data/pgdata/
   ```

### Option B: Manual Unlock (Higher Security)

For deployments where the key file must not reside on disk:

1. Do NOT add a key file to the LUKS slot.
2. Leave `/etc/crypttab` entry without a key file path:

   ```
   pg_encrypted /dev/sdX none luks
   ```

3. On each reboot, manually unlock before starting Docker Compose:

   ```bash
   cryptsetup luksOpen /dev/sdX pg_encrypted
   # Enter passphrase interactively
   mount /var/lib/postgresql/data
   cd /home/user/tessera && docker compose up -d
   ```

4. Optionally, retrieve the passphrase from a remote key server or Vault
   instance running on a separate host.

### Store Passphrase in Vault

Regardless of which option you choose, store the LUKS passphrase in Vault:

```bash
vault kv put secret/tessera/luks-passphrase value="<your-luks-passphrase>"
```

This provides a recovery path if the key file is lost or the passphrase is forgotten.

---

## 5. Key Rotation Procedure

LUKS supports multiple key slots (up to 32 for LUKS2). Key rotation replaces the
passphrase or key file in one slot while retaining access through another.

### Passphrase Rotation

1. **Add a new passphrase to an available slot:**

   ```bash
   cryptsetup luksAddKey /dev/sdX
   ```

   Enter the existing passphrase, then enter the new passphrase twice.

2. **Verify the new passphrase works:**

   ```bash
   cryptsetup open --test-passphrase /dev/sdX
   # Enter the NEW passphrase -- should succeed without error
   ```

3. **Remove the old passphrase:**

   ```bash
   cryptsetup luksRemoveKey /dev/sdX
   # Enter the OLD passphrase to remove it
   ```

4. **Update Vault with the new passphrase:**

   ```bash
   vault kv put secret/tessera/luks-passphrase value="<new-passphrase>"
   ```

### Key File Rotation (if using auto-unlock)

1. **Generate a new key file:**

   ```bash
   dd if=/dev/urandom of=/root/.pg_luks_key_new bs=512 count=1
   chmod 0400 /root/.pg_luks_key_new
   ```

2. **Add the new key file to a LUKS slot:**

   ```bash
   cryptsetup luksAddKey /dev/sdX /root/.pg_luks_key_new
   ```

3. **Remove the old key file from its LUKS slot:**

   ```bash
   cryptsetup luksRemoveKey /dev/sdX --key-file /root/.pg_luks_key
   ```

4. **Replace the old key file:**

   ```bash
   mv /root/.pg_luks_key_new /root/.pg_luks_key
   ```

5. **Verify auto-unlock still works:**

   ```bash
   cryptsetup open --test-passphrase /dev/sdX --key-file /root/.pg_luks_key
   ```

### Recommended Schedule

- **Passphrase rotation:** Quarterly (every 90 days)
- **Key file rotation:** Quarterly, or immediately if a key file may have been exposed

### Full Volume Re-encryption

> **IMPORTANT:** LUKS key rotation changes the passphrase/key file that unlocks the
> master key, but the master key itself (which encrypts the data) remains unchanged.
> If the master key is believed to be compromised, a full re-encryption is required.

> **WARNING:** Full re-encryption requires Postgres to be stopped. Plan for downtime.

```bash
# 1. Stop Tessera and Postgres
cd /home/user/tessera
docker compose down

# 2. Unmount the volume
umount /var/lib/postgresql/data

# 3. Run LUKS2 re-encryption (requires LUKS2 format)
cryptsetup reencrypt /dev/sdX

# 4. Remount and restart
mount /dev/mapper/pg_encrypted /var/lib/postgresql/data
docker compose up -d
```

Re-encryption time depends on volume size. Expect approximately 1 GB/minute on
modern hardware with AES-NI. A 100 GB volume takes roughly 100 minutes.

---

## 6. CMK-Encrypted Backups

Backups are encrypted with a Content Master Key (CMK) using GPG symmetric encryption.
The CMK is stored in HashiCorp Vault, never on the backup media.

### Creating an Encrypted Backup

1. **Store the backup CMK in Vault (one-time setup):**

   ```bash
   # Generate a strong random passphrase
   CMK=$(openssl rand -base64 32)
   vault kv put secret/tessera/backup-cmk key="$CMK"
   ```

2. **Take an encrypted backup:**

   ```bash
   # Retrieve CMK from Vault
   CMK=$(vault kv get -field=key secret/tessera/backup-cmk)

   # Dump and encrypt in a single pipeline
   docker exec -e PGPASSWORD=tessera tessera-postgres-age \
     pg_dump -U tessera -d tessera -n public -Fc | \
     gpg --batch --yes --symmetric --cipher-algo AES256 \
       --passphrase "$CMK" \
       --output /home/user/backups/tessera_$(date +%Y%m%d).dump.gpg

   # Clear the CMK from the shell environment
   unset CMK
   ```

3. **Verify backup integrity:**

   ```bash
   CMK=$(vault kv get -field=key secret/tessera/backup-cmk)
   gpg --batch --decrypt --passphrase "$CMK" \
     /home/user/backups/tessera_$(date +%Y%m%d).dump.gpg | \
     pg_restore --list | head -20
   unset CMK
   ```

   If `pg_restore --list` shows the table of contents, the backup is valid.

### Backup Rotation Policy

| Retention | Count | Example                    |
|-----------|-------|----------------------------|
| Daily     | 7     | Last 7 days                |
| Weekly    | 4     | Last 4 Sunday backups      |
| Monthly   | 3     | Last 3 first-of-month      |

Implement rotation with a cron job:

```bash
# /etc/cron.d/tessera-backup
0 2 * * * root /home/user/tessera/scripts/backup_encrypted.sh
```

The backup script should:
1. Retrieve CMK from Vault
2. Run `pg_dump | gpg` as above
3. Delete daily backups older than 7 days
4. Keep weekly (Sunday) backups for 4 weeks
5. Keep monthly (1st of month) backups for 3 months
6. Log success/failure to syslog

### CMK Rotation

When rotating the backup CMK:

1. Generate and store a new CMK in Vault
2. All future backups use the new CMK
3. Retain the old CMK in Vault (versioned) until all backups encrypted with it expire
4. Vault KV v2 versioning handles this automatically:

   ```bash
   # New CMK is stored as a new version
   NEW_CMK=$(openssl rand -base64 32)
   vault kv put secret/tessera/backup-cmk key="$NEW_CMK"

   # Old versions remain accessible
   vault kv get -version=1 -field=key secret/tessera/backup-cmk
   ```

---

## 7. DR Restore from Encrypted Backup

This procedure restores Tessera on a new or replacement IONOS VPS from an encrypted
backup. It assumes the backup was created following Section 6.

### Step-by-Step Restore

1. **Provision a new IONOS VPS** with a block device for Postgres data.

2. **Set up the LUKS volume** on the new VPS by following Section 2 (steps 1-7).

3. **Install required packages:**

   ```bash
   apt-get update && apt-get install -y cryptsetup gnupg docker.io docker-compose-plugin
   ```

4. **Transfer the encrypted backup** to the new VPS:

   ```bash
   scp /home/user/backups/tessera_YYYYMMDD.dump.gpg user@<new-vps-ip>:/home/user/backups/
   ```

5. **Retrieve the CMK from Vault:**

   ```bash
   # If Vault is accessible from the new VPS:
   CMK=$(vault kv get -field=key secret/tessera/backup-cmk)

   # If Vault is NOT yet running on the new VPS, retrieve from another Vault instance
   # or from the sealed backup of the Vault unseal keys.
   ```

6. **Decrypt and restore the backup:**

   ```bash
   # Start a fresh Postgres-AGE container
   docker run -d --name tessera-restore \
     -e POSTGRES_USER=tessera -e POSTGRES_PASSWORD=tessera -e POSTGRES_DB=tessera \
     -v /var/lib/postgresql/data:/var/lib/postgresql/data \
     -p 5432:5432 \
     apache/age@sha256:16aa423d20a31aed36a3313244bf7aa00731325862f20ed584510e381f2feaed

   # Wait for readiness
   until docker exec -e PGPASSWORD=tessera tessera-restore \
     psql -h 127.0.0.1 -U tessera -d tessera -c 'SELECT 1' 2>/dev/null; do
     sleep 2
   done

   # Load AGE extension
   docker exec -e PGPASSWORD=tessera tessera-restore \
     psql -h 127.0.0.1 -U tessera -d tessera \
     -c "CREATE EXTENSION IF NOT EXISTS age"

   # Decrypt and restore
   gpg --batch --decrypt --passphrase "$CMK" \
     /home/user/backups/tessera_YYYYMMDD.dump.gpg | \
     docker exec -i -e PGPASSWORD=tessera tessera-restore \
       pg_restore -U tessera -d tessera \
       --clean --if-exists --no-owner --schema=public

   unset CMK
   ```

7. **Verify the graph is queryable** (smoke test from DR-DRILL.md):

   ```bash
   docker exec -e PGPASSWORD=tessera tessera-restore \
     psql -h 127.0.0.1 -U tessera -d tessera \
     -c "SELECT count(*) FROM graph_events;"
   ```

8. **Run Flyway validate** to confirm schema consistency:

   ```bash
   cd /home/user/tessera
   ./mvnw -B -ntp -pl fabric-app flyway:validate \
     -Dflyway.url="jdbc:postgresql://localhost:5432/tessera" \
     -Dflyway.user=tessera \
     -Dflyway.password=tessera
   ```

9. **Deploy the full Docker Compose stack:**

   ```bash
   # Stop the temporary restore container
   docker stop tessera-restore && docker rm tessera-restore

   # Start the full stack
   cd /home/user/tessera
   docker compose up -d
   ```

10. **Verify application health:**

    ```bash
    sleep 15
    curl -sf http://localhost:8080/actuator/health | jq .
    # Expected: {"status":"UP"}
    ```

---

## 8. Monitoring

### LUKS Status Check

Verify the encrypted volume is active and healthy:

```bash
cryptsetup status pg_encrypted
```

Expected: `is active` with `cipher: aes-xts-plain64` and `keysize: 512 bits`.

### Disk Health (S.M.A.R.T.)

Monitor the underlying physical device for early failure detection:

```bash
apt-get install -y smartmontools
smartctl -a /dev/sdX
```

Key indicators to watch:
- `Reallocated_Sector_Ct` -- should be 0
- `Current_Pending_Sector` -- should be 0
- `Offline_Uncorrectable` -- should be 0

### Filesystem Space

```bash
df -h /var/lib/postgresql/data
```

Alert when available space drops below 20% of total capacity.

### Automated Monitoring Script

Create `/usr/local/bin/tessera-disk-check.sh`:

```bash
#!/bin/bash
set -euo pipefail

ALERT_WEBHOOK="${TESSERA_ALERT_WEBHOOK:-}"
HOSTNAME=$(hostname)
ERRORS=""

# Check LUKS volume is active
if ! cryptsetup status pg_encrypted >/dev/null 2>&1; then
  ERRORS+="CRITICAL: LUKS volume pg_encrypted is NOT active\n"
fi

# Check filesystem usage
USAGE=$(df --output=pcent /var/lib/postgresql/data | tail -1 | tr -d '% ')
if [ "$USAGE" -gt 80 ]; then
  ERRORS+="WARNING: Postgres data volume is ${USAGE}% full\n"
fi

# Check S.M.A.R.T. health (if smartctl is available)
if command -v smartctl >/dev/null 2>&1; then
  if ! smartctl -H /dev/sdX 2>/dev/null | grep -q "PASSED"; then
    ERRORS+="CRITICAL: S.M.A.R.T. health check FAILED for /dev/sdX\n"
  fi
fi

# Send alert if errors found
if [ -n "$ERRORS" ]; then
  echo -e "$ERRORS"
  if [ -n "$ALERT_WEBHOOK" ]; then
    curl -s -X POST "$ALERT_WEBHOOK" \
      -H "Content-Type: application/json" \
      -d "{\"text\":\"[$HOSTNAME] Tessera disk alert:\\n$ERRORS\"}"
  fi
  exit 1
fi

echo "OK: All disk checks passed"
```

```bash
chmod +x /usr/local/bin/tessera-disk-check.sh
```

### Cron Schedule

```bash
# /etc/cron.d/tessera-disk-check
0 */6 * * * root /usr/local/bin/tessera-disk-check.sh >> /var/log/tessera-disk-check.log 2>&1
```

### Prometheus Metrics (via node_exporter)

If `node_exporter` is deployed on the VPS, the following metrics are automatically
available for Grafana dashboards or alerting rules:

| Metric                                | Use                                    |
|---------------------------------------|----------------------------------------|
| `node_filesystem_avail_bytes`         | Available space on the encrypted mount |
| `node_filesystem_size_bytes`          | Total size of the encrypted mount      |
| `node_disk_io_time_seconds_total`     | I/O saturation on the underlying device |
| `node_disk_read_bytes_total`          | Read throughput                         |
| `node_disk_written_bytes_total`       | Write throughput                        |

Example Prometheus alert rule:

```yaml
groups:
  - name: tessera-disk
    rules:
      - alert: PostgresDataVolumeLow
        expr: node_filesystem_avail_bytes{mountpoint="/var/lib/postgresql/data"} / node_filesystem_size_bytes{mountpoint="/var/lib/postgresql/data"} < 0.2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Postgres encrypted data volume below 20% free"
```

---

## 9. Troubleshooting

### LUKS volume won't open

**Symptom:** `No key available with this passphrase.`

**Checks:**
1. Verify the correct device path: `lsblk` to list block devices
2. Check key file permissions: `ls -la /root/.pg_luks_key` (should be `-r--------`)
3. Verify key file is in a LUKS slot: `cryptsetup luksDump /dev/sdX` and check key slot status
4. If using a passphrase, verify it matches what is stored in Vault:
   `vault kv get -field=value secret/tessera/luks-passphrase`

### Postgres won't start after reboot

**Symptom:** Docker Compose starts but `postgres-age` container exits immediately.

**Checks:**
1. Verify LUKS volume is open: `cryptsetup status pg_encrypted`
2. Verify mount is active: `mount | grep pg_encrypted`
3. If not mounted, check `/etc/crypttab` and `/etc/fstab` entries
4. Ensure LUKS volume opens before Docker starts. On systemd-based systems,
   `cryptsetup` runs before Docker if `/etc/crypttab` is configured correctly.
   If not, create a systemd dependency:

   ```bash
   # /etc/systemd/system/docker.service.d/wait-for-luks.conf
   [Unit]
   After=systemd-cryptsetup@pg_encrypted.service
   Requires=systemd-cryptsetup@pg_encrypted.service
   ```

   ```bash
   systemctl daemon-reload
   ```

### Backup decryption fails

**Symptom:** `gpg: decryption failed: Bad session key`

**Checks:**
1. Verify the correct CMK is being used (check Vault version):
   `vault kv get -field=key secret/tessera/backup-cmk`
2. If the CMK was rotated, try older versions:
   `vault kv get -version=1 -field=key secret/tessera/backup-cmk`
3. Verify GPG version compatibility: `gpg --version` (use GPG 2.x on both
   encryption and decryption hosts)
4. Ensure the backup file is not corrupted: `file tessera_YYYYMMDD.dump.gpg`
   should report `GPG symmetrically encrypted data`

### Performance degradation after enabling encryption

**Symptom:** Postgres query latency increases noticeably after moving to the encrypted volume.

**Checks:**
1. Verify AES-NI hardware acceleration is available:

   ```bash
   grep -c aes /proc/cpuinfo
   ```

   If the count is 0, the CPU does not support AES-NI and encryption will be
   performed in software (significantly slower).

2. Check I/O wait:

   ```bash
   iostat -x 5 3
   ```

   High `%iowait` or `await` values indicate I/O bottleneck.

3. Benchmark the encrypted volume vs. an unencrypted volume:

   ```bash
   # Write benchmark
   dd if=/dev/zero of=/var/lib/postgresql/data/bench bs=1M count=1024 oflag=direct
   rm /var/lib/postgresql/data/bench
   ```

   On modern hardware with AES-NI, encrypted I/O should be within 2-5% of
   unencrypted I/O.

4. If IONOS VPS uses network-attached storage, latency may be inherent to the
   storage type rather than encryption. Check IONOS control panel for storage
   IOPS limits.

### Lost LUKS passphrase and key file

**Symptom:** Cannot unlock the volume.

**Recovery steps:**
1. Check Vault for the stored passphrase:
   `vault kv get -field=value secret/tessera/luks-passphrase`
2. If Vault is also lost, the data is unrecoverable by design. This is the
   security guarantee of full-disk encryption.
3. Restore from the latest CMK-encrypted backup (Section 7) onto a new LUKS volume.

---

## Appendix A: Quick Reference Commands

| Operation                  | Command                                                         |
|----------------------------|-----------------------------------------------------------------|
| Open LUKS volume           | `cryptsetup luksOpen /dev/sdX pg_encrypted`                     |
| Close LUKS volume          | `umount /var/lib/postgresql/data && cryptsetup luksClose pg_encrypted` |
| Check LUKS status          | `cryptsetup status pg_encrypted`                                |
| Dump LUKS header info      | `cryptsetup luksDump /dev/sdX`                                  |
| List key slots             | `cryptsetup luksDump /dev/sdX \| grep "Key Slot"`              |
| Add passphrase             | `cryptsetup luksAddKey /dev/sdX`                                |
| Remove passphrase          | `cryptsetup luksRemoveKey /dev/sdX`                             |
| Backup LUKS header         | `cryptsetup luksHeaderBackup /dev/sdX --header-backup-file /root/luks-header.bak` |
| Restore LUKS header        | `cryptsetup luksHeaderRestore /dev/sdX --header-backup-file /root/luks-header.bak` |
| Encrypted backup           | `pg_dump -Fc \| gpg --symmetric --cipher-algo AES256 -o file.gpg` |
| Decrypt backup             | `gpg --decrypt file.gpg \| pg_restore -d tessera`              |
| Full re-encryption         | `cryptsetup reencrypt /dev/sdX`                                 |

## Appendix B: LUKS Header Backup

> **CRITICAL:** If the LUKS header is damaged, the entire volume is unrecoverable
> regardless of having the correct passphrase. Always back up the LUKS header.

```bash
# Back up the header (do this immediately after initial setup)
cryptsetup luksHeaderBackup /dev/sdX \
  --header-backup-file /root/luks-header-$(date +%Y%m%d).bak

# Store a copy off-host (e.g., in a secure object store or separate VPS)
scp /root/luks-header-*.bak user@backup-host:/secure/tessera/
```

The header backup is approximately 16 MB for LUKS2. It does NOT contain the
data encryption key in plaintext -- an attacker still needs a valid passphrase
or key file to use it.
