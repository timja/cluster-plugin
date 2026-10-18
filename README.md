# Jenkins Cluster plugins

Run several Jenkins controllers as one cluster:
- they share items, builds, the queue and plugins
- a build keeps running when its controller dies, because another controller adopts it
- `JENKINS_HOME` can be ephemeral

High availability is the main benefit. The design is described in JEP-250 to JEP-257 (draft JEPs).

This is a prototype. It needs a Jenkins core built from the `cnj/t*` branches of [timja/jenkins](https://github.com/timja/jenkins), and the forked plugins listed under [Building](#building).

## The plugins
| Plugin | What it holds | Depends on |
|---|---|---|
| [`cluster`](cluster) | Everything that does not depend on a backend: routing, the shared queue's housekeeping, adoption, Pipeline save points and log shipping, handover, cluster-wide plugin management, the cluster view, fingerprints, milestones. It also defines the cluster SPI (`io.jenkins.plugins.cluster.spi`). | workflow-api, workflow-cps, pipeline-milestone-step, kubernetes (optional) |
| [`cluster-postgresql`](cluster-postgresql) | The store: configuration, build records, membership, leases, claims, the shared queue and schema migrations, in PostgreSQL | `cluster`, `postgresql-api` |
| [`cluster-azure-blob`](cluster-azure-blob) | Blobs (build snapshots, Pipeline logs and save points, plugin archives) in Azure Blob Storage | `cluster`, `azure-sdk` |
| [`cluster-s3`](cluster-s3) | The same, in Amazon S3 or an S3-compatible store | `cluster`, `aws-java-sdk2-s3` |

An installation takes `cluster`, one store plugin and one blob store plugin, plus an artifact manager for artifacts and stashes (azure-artifact-manager or artifact-manager-s3).

## Configuration
| | Environment |
|---|---|
| Controller | `JENKINS_CLUSTER_NAME` (unique among live controllers), `JENKINS_CLUSTER_INTERNAL_URL` |
| PostgreSQL | `CLUSTER_POSTGRES_URL`, `CLUSTER_POSTGRES_USER`, `CLUSTER_POSTGRES_PASSWORD` |
| Azure Blob Storage | `CLUSTER_AZURE_BLOB_CONNECTION_STRING`, `CLUSTER_AZURE_BLOB_CONTAINER` |
| S3 | `CLUSTER_S3_BUCKET`, `CLUSTER_S3_PREFIX`, `CLUSTER_S3_REGION`, `CLUSTER_S3_ENDPOINT`, `CLUSTER_S3_PATH_STYLE` |

- S3 credentials come from the AWS default chain.
- Each setting is also a system property, for example `io.jenkins.plugins.cluster.postgresql.url`.
- A controller is taken for gone by the others after 15 s without a heartbeat (about 6 s once its process died):
  its Pipelines are adopted, its other builds closed as aborted, its leases taken over and its queue items put back.
  `-Dio.jenkins.plugins.cluster.postgresql.PostgresPersistenceStore.memberTimeoutMillis` (the chart's
  `store.memberTimeoutSeconds`) sets it, the same on every controller: raise it where controllers may pause for
  longer (long garbage collections, CPU throttling), at the cost of slower failover.
- After the database is restored to an earlier point in time, every controller restarts: one that notices the store
  went back (its change events go back) restarts itself, safely if it can.

## Building
```
mvn install
```
This builds the four plugins and runs their tests, including Testcontainers tests against PostgreSQL, Azurite and Garage.

It needs, installed locally:
- Jenkins core `2.586-SNAPSHOT`, from the `cnj/t*` branches of timja/jenkins
- workflow-cps and kubernetes at `999999-SNAPSHOT`, from their `cnj/*` branches on the timja forks

## The Helm chart
[`charts/jenkins-cluster`](charts/jenkins-cluster) runs a cluster of controllers on Kubernetes. It includes PostgreSQL, and blob storage set by `blob.type`:
- `azure`: in-chart Azurite
- `s3`: an external bucket

## Acceptance experiments
[`validation/`](validation) holds the experiments (ET1 to ET22) that check a cluster end to end, on Docker Compose and on kind.

```
validation/experiments/ET1-build.sh      # builds core, the forked plugins and these, into validation/.et1
validation/experiments/sweep.sh          # runs every experiment, in parallel lanes sized to the machine
validation/experiments/with-s3.sh ET3-build-records   # one experiment on the S3 variant (Garage)
```

- **Where results go:** each experiment writes its results to `$FINDINGS_DIR/raw/<experiment>`, by default under `validation/out`.
- **Lanes:** on macOS the sweep uses one lane, so a laptop stays usable; elsewhere it sizes the lanes to the machine. `SWEEP_LANES` overrides that.
