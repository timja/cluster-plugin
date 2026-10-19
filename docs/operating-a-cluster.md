# Operating a cluster

How to install, scale, upgrade, roll back, migrate to, back up and restore a cluster of Jenkins controllers running the
Cluster plugins, and what to expect of it. The behaviour this relies on is specified in the draft JEPs (JEP-250 to
JEP-257); this guide is about running it.

This is a prototype. The chart, the image and the scripts named here are the ones in this repository, and the
acceptance experiments under [`validation/`](../validation) check what this guide says (see
[What checks this](#what-checks-this)).

## What a controller needs

- **The stores.** The database's JDBC URL and credentials (`CLUSTER_POSTGRES_URL`, `CLUSTER_POSTGRES_USER`,
  `CLUSTER_POSTGRES_PASSWORD`) and the blob store's:
  - Azure Blob Storage: `CLUSTER_AZURE_BLOB_CONNECTION_STRING`, `CLUSTER_AZURE_BLOB_CONTAINER`
  - S3: `CLUSTER_S3_BUCKET`, `CLUSTER_S3_PREFIX`, `CLUSTER_S3_REGION`, `CLUSTER_S3_ENDPOINT`, `CLUSTER_S3_PATH_STYLE`,
    with credentials from the AWS default chain

  Access to the stores is what makes a controller part of the cluster: there is no join step, token or UI. Each
  setting is also a system property, for example `io.jenkins.plugins.cluster.postgresql.url`.
- **The cluster's key material.** `secret.key`, `secret.key.not-so-secret`, `secrets/` and the instance identity
  (`identity.key.enc`), made once for the cluster and given to every controller. The chart's
  [`make-seed-secret.sh`](../charts/jenkins-cluster/scripts/make-seed-secret.sh) makes it as a Kubernetes Secret
  (`seed.tgz`), and the image's entrypoint unpacks it into an empty home (`CNJ_SEED_TGZ`). A controller started with
  other keys refuses to start: the first controller records a value encrypted with its key, and a controller that cannot
  decrypt it stops and says why (`-Dio.jenkins.plugins.cluster.KeyCheck.skip=true` starts it anyway).
- **An identity.** A name unique among the live controllers (`JENKINS_CLUSTER_NAME`; the chart uses the pod name) and
  an internal URL the other controllers can reach it at (`JENKINS_CLUSTER_INTERNAL_URL`).
- **Two system properties:**
  - `org.kohsuke.stapler.bind.BoundObjectTable.idPrefix=<controller name>`, so that requests for another controller's
    bound objects (`renderOnDemand`, progressive rendering, JavaScript proxies) are forwarded to it. The image's
    entrypoint sets it from `JENKINS_CLUSTER_NAME`; set it yourself with another image.
  - `hudson.security.TokenBasedRememberMeServices2.alwaysRemember=true`, so that every form sign-in issues a
    remember-me cookie, which any controller accepts. The chart sets it.
- **A root URL** (Manage Jenkins > System, or JCasC `unclassified.location.url`). Forwarded requests do not carry the
  browser's `Host` header, so links built from the request would name the internal URL.
- **An artifact manager** keeping artifacts and stashes in shared storage: azure-artifact-manager or
  artifact-manager-s3, matching the blob store. Without one, a build adopted by another controller loses its stashes,
  and a fresh home lacks the artifacts of earlier builds; an administrative monitor says so.
- **A compatible version:** the same image, or an adjacent one during a rolling upgrade (see
  [Rolling upgrades](#rolling-upgrades)).

## Installing with the Helm chart

[`charts/jenkins-cluster`](../charts/jenkins-cluster) runs a StatefulSet of controllers with `emptyDir` homes and a
Service without session affinity, plus, for development, Postgres and Azurite (the Azure Storage emulator) in the
chart.

```
charts/jenkins-cluster/scripts/make-seed-secret.sh <image> <namespace>          # once per cluster
helm -n <namespace> install <release> charts/jenkins-cluster --set image=<image>
```

`make-seed-secret.sh` starts the image once in Docker, has it make its keys and stores them as the Secret
`cluster-seed` (the chart's `seedSecret`). Keep a copy: a cluster's secrets cannot be read without it.

What the chart sets up:

- the controllers (`controllers`, default 2), each with its name, internal URL (the pod's name on the headless Service)
  and the store settings from a Secret
- JCasC (`casc`), the same on every controller. Settings not declared there are edited in the UI and shared.
  Every controller applies JCasC as it starts, through the shared store, so the chart runs it with
  `io.jenkins.plugins.casc.core.JenkinsConfigurator.keepUndeclaredNodes=true`: one controller applying the YAML does
  not delete the nodes the others hold. This needs the configuration-as-code prototype branch
  `cnj/t15-keep-undeclared-nodes`.
- a Kubernetes cloud (`kubernetesCloud.*`) whose agents connect back to the controller that launched them
  (`KUBERNETES_JENKINS_URL` is the controller's internal URL)
- the artifact manager (`artifactManager.*`), in the blob container or bucket under `artifactManager.prefix`
- probes on `/login` (see [Probes and starting](#probes-and-starting))

### The main values

| Value | Default | What it does |
|---|---|---|
| `controllers` | `2` | How many controllers |
| `image` | `cnj/jenkins:k8s` | The controller image: core, the cluster plugins and the other plugins |
| `javaOpts` | `""` | Extra JVM options, for the system properties below |
| `updateSites.fetch` | `true` | Download update-site and tool metadata; `false` for air-gapped clusters |
| `store.existingSecret` | `""` | A Secret with the store settings, instead of the values below |
| `store.poolSize` | `8` | Database connections per controller for ordinary work (plus 2 for heartbeats and leases) |
| `store.sessionLocks` | `true` | Session-lock liveness (a dead controller is taken for gone in about 6 s); `false` behind a pooler in transaction mode (PgBouncer) |
| `store.memberTimeoutSeconds` | `15` | How long a controller may go without a heartbeat before the others take it for gone |
| `store.sharePluginFiles` | `false` | Share files plugins write in item and user directories with their own file APIs, every minute (it scans every item and user directory) |
| `postgres.inChart` | `true` | In-chart Postgres, for development |
| `postgres.url`, `postgres.user`, `postgres.password` | | An external database |
| `postgres.urlParameters` | `""` | Appended to the JDBC URL, for example `sslmode=verify-full&sslrootcert=...` |
| `blob.type` | `azure` | `azure` (cluster-azure-blob) or `s3` (cluster-s3) |
| `blob.inChart` | `true` | `azure` only: in-chart Azurite |
| `blob.connectionString`, `blob.container` | | `azure`: an external storage account |
| `blob.s3.bucket`, `.prefix`, `.region`, `.endpoint`, `.pathStyle` | | `s3`: the bucket (always external) |
| `artifactManager.enabled`, `.prefix` | `true`, `artifacts/` | The artifact manager |
| `artifactManager.accountName`, `.blobEndpoint` | | `azure`, not in-chart: the artifact manager's storage account |
| `service.type`, `service.nodePort` | `ClusterIP` | The Service browsers, API clients and agents use |

### The member timeout

A controller is taken for gone by the others after `store.memberTimeoutSeconds` (15 s) without a heartbeat, or about
6 s after its process died (its session lock is freed). Its Pipelines are then adopted, its other builds closed as
aborted (a freestyle build cannot be resumed elsewhere), its leases taken over and its queue items put back. A
controller that is only paused for longer than the timeout (a long garbage collection, CPU throttling) loses those
builds. Raise the timeout where such pauses are expected, at the cost of slower failover. It must be the same on every
controller. Without the chart, the property is
`io.jenkins.plugins.cluster.postgresql.PostgresPersistenceStore.memberTimeoutMillis`.

### Managed services

In-chart Postgres and Azurite are for development. In production, point at managed services:

- **The store Secret.** `store.existingSecret` names a Secret the controllers take their environment from, and the chart
  then makes none of its own: put every store setting in it.
  - always: `CLUSTER_POSTGRES_URL` (with its TLS parameters), `CLUSTER_POSTGRES_USER`, `CLUSTER_POSTGRES_PASSWORD`
  - `blob.type: azure`: `CLUSTER_AZURE_BLOB_CONNECTION_STRING`, `CLUSTER_AZURE_BLOB_CONTAINER`, and for the artifact
    manager `CNJ_BLOB_ACCOUNT_KEY`. The account name and endpoint are not secret: set them as
    `artifactManager.accountName` and `.blobEndpoint`, which the chart gives the controllers as `CNJ_BLOB_ACCOUNT_NAME`
    and `CNJ_BLOB_ENDPOINT`, or leave those empty and put both in the Secret too. `artifactManager.accountName` without
    `store.existingSecret` fails the install, as the key would be missing.
  - `blob.type: s3`: `CLUSTER_S3_BUCKET`, `CLUSTER_S3_PREFIX`, `CLUSTER_S3_REGION`, `CLUSTER_S3_ENDPOINT`,
    `CLUSTER_S3_PATH_STYLE`, and AWS credentials if they do not come from elsewhere in the default chain
    (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`; or IRSA through the service account)
- **TLS.** Connections to the stores should use TLS: `postgres.urlParameters` (for example `sslmode=verify-full`) and an
  `https` blob endpoint or connection string.
- **Poolers.** Behind PgBouncer in transaction mode, which does not keep sessions, set `store.sessionLocks: false`.
  Liveness then rests on heartbeats alone, so a dead controller is taken for gone after the member timeout rather than
  in about 6 s. Each controller uses `store.poolSize` + 2 connections.

### Azure Blob Storage or S3

- **Azure** (`blob.type: azure`): cluster-azure-blob for the cluster's blobs, azure-artifact-manager for artifacts. The
  artifact manager's storage credential (id `cluster-blob`) is made at startup if absent, from `CNJ_BLOB_ACCOUNT_NAME`,
  `CNJ_BLOB_ACCOUNT_KEY` and `CNJ_BLOB_ENDPOINT`, by the image's `init.groovy.d/cluster-blob-credential.groovy`; an
  image of your own needs that script too. It is not declared in JCasC on purpose: JCasC replaces the system
  credentials it declares, and every controller that starts applies it through the shared store, which would delete
  credentials added at runtime.
- **S3** (`blob.type: s3`): cluster-s3 for the cluster's blobs, artifact-manager-s3 for artifacts, in the same bucket
  (artifacts under `blob.s3.prefix` + `artifactManager.prefix`). The bucket is always external; for an S3-compatible
  store set `blob.s3.endpoint` and, usually, `blob.s3.pathStyle: true`. Credentials for both come from the AWS default
  chain.

The image carries both blob store plugins; each stays off unless it is configured.

## Probes and starting

The chart probes `/login`: a controller is ready, and given traffic, once Jenkins has started. A starting controller
waits up to 10 minutes for its stores (`-Dio.jenkins.plugins.cluster.startupWaitSeconds`, default 600) before failing.
The chart's startup probe allows 5 minutes (60 × 5 s), so a controller still waiting for its stores after that is
restarted by the kubelet and waits again. The probes are not chart values: to have it wait the whole time, raise
the startup probe's `failureThreshold` in `templates/controllers.yaml`, or lower `startupWaitSeconds` through
`javaOpts`.

A controller on a fresh home first fetches the plugins installed at runtime (the cluster's plugin set) before anything
is loaded, and restarts once if it fetched any (see [Plugins](#plugins)).

## StatefulSet or Deployment

Either works: nothing depends on a controller keeping its name. Membership, leases and adoption go by origin, a new
one each time a controller starts, and the internal URL can be a pod IP (`http://$(POD_IP):8080/`). Only the chart's
StatefulSet is exercised. It uses one for:

- stable, readable names (`cnj-0`, `cnj-1`) in the cluster view and logs, and per-pod DNS through a headless Service
- one-at-a-time rolling updates, which keep the number of controllers adopting builds predictable
- pod management `Parallel`, so that a scale-up or a restart of every pod does not wait for each pod in turn

Controller names must be unique among the live controllers: bound-object ids and forwarding use them.

## No persistent volumes for controllers

`JENKINS_HOME` is a cache: everything that must survive is in the stores, and an empty home is rebuilt from them at
start. So the chart uses `emptyDir`, which survives a container restart (a safe restart, or a crash) but not a
rescheduled pod. Persistent volumes would make failover worse: a volume that can be attached to one node at a time
keeps a replacement pod waiting while it detaches from a dead node (typically minutes), and ties pods to the volume's
zone.

What a fresh home costs is the start. Measured on a laptop (Docker, 8 CPUs), with every plugin in the image:

| Case | Time |
|---|---|
| Fresh controller, 2,000 jobs and 2,000 builds in the stores: API served after the container started | 12.8–20.4 s (median 16.4 s, three runs): 2.6–4.9 s container and JVM, 7–11 s Jenkins initialisation |
| Its first full page of 2,000 jobs | 5–19 s more, then 0.6 s warm |
| The same, with one plugin installed at runtime and loaded live | 12.9 s |
| Three controllers on 200,000 build records, started together | 18 s |
| A job page with 20,000 builds, on each of three controllers | 0.6–0.8 s cold, 0.02–0.03 s warm |
| Deleting a job with 20,000 builds | 1 s |
| Two controllers, 2,000 jobs: the second lists the jobs after the first starts creating them | 10 s |
| Restart of a controller with its home | 14 s |
| 50 running Pipelines adopted after their controller is lost | within 24 s |

A fresh controller that has runtime-installed plugins to fetch restarts once before it loads anything, which adds a
JVM start that has not been measured. To start faster, put plugins in the image rather than installing them at
runtime, and size the `emptyDir` (or use `medium: Memory`) for the builds people look at.

## Scaling

`helm upgrade --reuse-values --set controllers=N`. A new controller joins by starting; a controller removed by a
scale-down leaves cleanly (its row is deleted and the others are told at once), and the builds it was running are
adopted (Pipelines) or closed as aborted (other builds). Scaling to 0 stops the cluster; its state stays in the stores.

## Plugins

`-Dio.jenkins.plugins.cluster.ClusterPluginSet.mode` (through `javaOpts`) sets what the plugin manager may change:

- `cluster` (the default): installs, upgrades, uninstalls, disables and enables apply to every controller, including
  CLI `install-plugin` from a file, URL or standard input. Plugins that can be loaded live are; anything else is applied
  by rolling restarts, one controller at a time, each quieting down first (shown under Manage Jenkins > Cluster). These
  are refused: downgrades, changes to the cluster plugins themselves, upgrades of Jenkins itself (a new image), and the
  progress page's single-controller restart.
- `fixed`: the plugin set is the image's; every change is refused, with a message pointing to the image.
- `local`: changes stay on the controller that served the request, as without a cluster. Its plugins then differ from
  the others' until its home is gone.

Changes made at runtime are an overlay on the image's plugins, kept in the stores, and applied again whenever a
controller starts on an empty home. In `cluster` mode, a plugin upgrade cannot be undone through the plugin manager: go
back by [rolling back](#rolling-back).

## Rolling upgrades

`helm upgrade --reuse-values --set image=<new image>` replaces the controllers one at a time. Running Pipelines are
adopted by the controllers still up, and the old and new versions run side by side as long as the new one can work with
the store as the old one leaves it.

- **Protocol.** Each version speaks a cluster protocol number, and the store records the oldest protocol it accepts
  (its floor), raised when a schema migration is contracted. A controller older than the floor refuses to start, with
  a message saying a newer version is needed.
- **Schema migrations** run in the background on one controller at a time (`-Dio.jenkins.plugins.cluster.postgresql.PostgresPersistenceStore.migrate=false`
  keeps a controller out of them), and are shown in the cluster view: expand at startup, backfill in batches, and
  contract once the backfill is done and no live controller speaks an older protocol than the migration needs.
- **Incompatible versions.** A version that cannot run beside older ones is started with
  `-Dio.jenkins.plugins.cluster.minimumPeerProtocol=N`: it refuses to start while a controller speaking an older
  protocol is alive, so every old controller has to be replaced first. Two controllers started at the same moment can
  both pass the check before either has heartbeated; rolling upgrades start one at a time.
- **An old store.** A version that finds a database written in an earlier layout (build data keyed by item path)
  refuses to start, and says to stop every controller and empty the database. Nothing released used that layout.

**Mixed versions: limitation.** Compatibility during a roll is guaranteed for the store protocol and the schema, not
for the shape of serialised objects. If a new version of core or of a plugin adds a field, and a controller still on
the old version saves the same object, the old controller writes it without the field, and the new one reloads it with
the field reset. A `readResolve` that renames elements has the same effect in both directions, and each side's save
then looks like a change to the other's, so a save may be refused and reloaded. The same applies to a plugin change
rolling through the cluster. Do not change configuration while a roll is in progress. JCasC is applied by each
controller as it starts, so the new version's fields can be lost again by an old controller that saves afterwards.

## Rolling back

- **Before a migration of the new version is contracted**, deploying the previous image (a rolling upgrade the other
  way) restores the previous version: the schema is a superset of both.
- **After it is contracted**, the protocol it needed is the cluster's floor, and an image with an older protocol
  refuses to start. The way back is to restore the database and the blob store from a backup taken before the upgrade,
  into a new release, and start the previous image on it. Builds that ran after the backup are lost.
- Contract runs shortly after a roll finishes, so the window for rolling back by image is short.
- **Plugins:** a plugin downgrade is refused. Going back to an older plugin set means an image with the older set and a
  store restored to before the change.

## Importing a file-based home

Start one controller on the existing home, configured for the cluster's (empty) stores, with
`-Dio.jenkins.plugins.cluster.importHome=true`. It uploads what the store keeps (jobs and folders, users, nodes, global
and descriptor configuration, credentials), the fingerprints and, once jobs are loaded, the completed builds with their
logs. Keys the store already has are left alone, so importing again imports nothing. A report is written to
`$JENKINS_HOME/cluster-import-report.txt`.

The old home's keys become the cluster's: make the seed Secret from that home's `secret.key`,
`secret.key.not-so-secret`, `identity.key.enc` and `secrets/`, so that the other controllers can read the imported
secrets. A home whose keys are not those of a store that already has data is refused.

## Backup and restore

- [`scripts/backup.sh`](../charts/jenkins-cluster/scripts/backup.sh) `<namespace> <release> <directory>` takes a
  `pg_dump` and then copies the blob store;
  [`scripts/restore.sh`](../charts/jenkins-cluster/scripts/restore.sh) `<namespace> <release> <directory> <chart> [helm args...]`
  installs a new release with no controllers, loads the dump and the blobs into it, then starts `CONTROLLERS`
  controllers (default 2). Pass the same values as the original release, including its `seedSecret`, which must exist in
  the target namespace.
- **The scripts cover in-chart Postgres and Azurite only.** With managed services, or with `blob.type: s3`, back up the
  database with its own tools and the container or bucket with the provider's.
- The backup is consistent when no controller runs: scale to 0 first (`--set controllers=0`). Blobs are written before
  the records that refer to them, but a build deleted or republished while the backup runs can leave a record whose
  files are missing from the copy, or newer. For an online backup, use point-in-time recovery.
- After a restore, builds that were running when the backup was taken are adopted from their save points, or closed as
  aborted.

### Point-in-time recovery

Restore Postgres with its own tools (a base backup plus archived WAL, or a managed service's restore to a time) into a
new database, and start fresh controllers on it. Restore the blob store to the same point too: Azure Blob Storage's
versioning and point-in-time restore do this; for S3, bucket versioning with a restore to a point in time (for example
from AWS Backup). The cluster deletes blobs with their records (there is no deferred deletion), so without blob
versioning a restore to an earlier time finds the records of builds deleted since, with their files gone.

If the database is restored under running controllers, every controller restarts: one that notices the store went back
(its change events go back) restarts itself, safely if it can.

Only Postgres point-in-time recovery has been exercised, with the blob store copied as it was at the restore point
standing in for blob versioning (Azurite has none).

## Downgrading Jenkins

A controller on a Jenkins version without the persistence store cannot use the cluster's stores. An item's
`config.xml` taken from a cluster carries `<itemUuid>`, which an older version reports as unknown data until the item
is saved again. Downgrading a cluster is done by restoring a backup, as above.

## Security

- The controllers are one trust domain, and the stores are inside it: whoever can write to the database or the blob
  store controls every controller. Limit network access to the stores and to the controllers' internal URLs to the
  controllers.
- The store and blob credentials come from Secrets, and connections should use TLS (`postgres.urlParameters`, the blob
  endpoint or connection string).
- The chart's internal URL is plain HTTP. Traffic between controllers carries users' credentials (forwarded requests
  keep their cookies), so it should use TLS: a service mesh, or an HTTPS `JENKINS_CLUSTER_INTERNAL_URL`.
- The seed Secret holds the keys that decrypt every secret in the store.
- The chart's default JCasC (an `admin`/`admin` user, `loggedInUsersCanDoAnything`) and Azurite's well-known account
  key are for development only.

## Known limitations

- Point-in-time recovery needs blob versioning, which Azurite lacks; it is exercised for Postgres only.
- A fresh controller's first full page of 2,000 jobs takes 5–19 s (0.6 s warm); not profiled yet.
- Only the chart's StatefulSet is exercised, not a Deployment.

## What checks this

The acceptance experiments in [`validation/experiments/`](../validation/experiments):

- `ET9-kubernetes.sh`: the chart on a local kind cluster (scaling, agents, losing a controller)
- `ET10-operations.sh`: rolling upgrade, a refused older peer, import of a file-based home, backup and restore with the
  chart's scripts
- `ET14-store-outage.sh`: Postgres paused and restarted, Azurite stopped, a controller booting while Postgres is down
- `ET16-scale.sh`, `ET17-scale-3.sh`: the measurements above
- `ET18-pitr.sh`: point-in-time recovery of Postgres to a named restore point, with and without blob storage restored
  to the same point (Docker Compose)
- `ET19-plugin-manager.sh`, `ET13-cluster-plugins.sh`: cluster-wide plugin management
