# Backup job image: pg_dump (same major as the server) + age, running backup.sh as a non-root user.
FROM postgres:16.10-bookworm
RUN apt-get update \
 && apt-get install -y --no-install-recommends age \
 && rm -rf /var/lib/apt/lists/*
COPY backup.sh restore-test.sh /usr/local/bin/
# Owned by the job user, so a fresh named volume mounted here is writable.
RUN mkdir -p /backups && chown postgres:postgres /backups && chmod 700 /backups
USER postgres
ENTRYPOINT ["/usr/local/bin/backup.sh"]
