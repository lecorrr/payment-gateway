# Prod on AWS, in Terraform

The AWS that [values-prod.yaml](../helm/payment-gateway/values-prod.yaml) runs on, in `eu-central-1`: a VPC, an EKS
cluster, the gateway's and the ledger's PostgreSQL on RDS, Valkey on ElastiCache, Kafka on MSK, and the secrets
External Secrets puts into the app's Secret. It is never applied from this repository. The pipeline's `terraform`
job checks it without an AWS account ([ci/check-terraform.sh](../../ci/check-terraform.sh)):

- `terraform fmt`, then `init` without a backend and `validate`, for prod and for each module;
- `terraform test`, whose mocked providers plan everything with made-up AWS answers. Each module's tests hold it to
  its promises (encrypted, TLS only, a standby or replica in another zone, open to the EKS nodes alone, no password
  in the state), and prod's tests compare the chart's values-prod.yaml with the network here;
- tflint, with its AWS rules, which know which instance types and engine versions exist;
- Trivy, whose HIGH or CRITICAL misconfigurations in this code fail the job.

```
prod/                 the root: the VPC and EKS (from terraform-aws-modules), the stores, the secrets, outputs
  tests/              the checks against values-prod.yaml
modules/postgres/     an RDS PostgreSQL 17 for one app, with its password in Secrets Manager
modules/redis/        ElastiCache for Valkey: a primary and a replica
modules/kafka/        MSK: a broker in each zone, its storage encrypted with a key of its own
modules/secrets/      the app's secret, and External Secrets' IAM role through EKS Pod Identity
cluster-secret-store.yaml   External Secrets' store for prod
```

## The network

The VPC is `10.0.0.0/16`, across three zones. The load balancer and the NAT gateways (one in each zone) are in the
public subnets, and the nodes and their pods are in the private ones. Each store has a `/22` of its own, with one
`/24` of that range in each zone and no route out of the VPC:

| Store              | Range         | Port | Who may connect (values-prod.yaml's NetworkPolicies) |
|--------------------|---------------|------|------------------------------------------------------|
| the gateway's RDS  | 10.0.200.0/22 | 5432 | the gateway                                          |
| the ledger's RDS   | 10.0.204.0/22 | 5432 | the ledger                                           |
| ElastiCache        | 10.0.208.0/22 | 6379 | the gateway, risk-service                            |
| MSK (TLS)          | 10.0.212.0/22 | 9094 | the gateway, the ledger                              |

The stores' security groups let in the EKS nodes, the only machines here that may connect. Every pod runs on those
nodes, so the security groups can't tell one app from another. The chart's NetworkPolicies can, by range: the VPC
CNI enforces them (`enableNetworkPolicy`), and each app may reach only its stores' ranges. That keeps the gateway
away from the ledger's database, as the policies do on k3d. If values-prod.yaml's rules and the ranges of
`prod/network.tf` disagree, `prod/tests/prod.tftest.hcl` fails.

## Secrets

Neither git nor the Terraform state holds a password. Each database gets a password Terraform makes anew at every
run, but sends only through write-only arguments, to RDS and to the database's secret alike, and only when the
module's `password_version` changes. A new password is a higher `password_version` in `prod/stores.tf`, an apply,
and a restart of the app. The app's other keys (`GATEWAY_FINGERPRINT_KEY`, `GATEWAY_MONOBANK_TOKEN`) go into `payment-gateway/prod/app`
by hand, as JSON. External Secrets, with an IAM role that reads these three secrets and no others, writes the
Secret the apps read (the chart's `templates/external-secret.yaml`).

## What an apply would still take

- An S3 bucket for the state, named in `prod/versions.tf`.
- A way into the VPC: the Kubernetes API is private, so people come through a VPN or a bastion, and the pipeline's
  deploy job through a runner inside the VPC.
- In the cluster, with their Helm charts: an ingress controller (Traefik, as values-prod.yaml has it) behind the
  AWS Load Balancer Controller, which needs an IAM role of its own as External Secrets has here, cert-manager with a `letsencrypt` ClusterIssuer, External Secrets in the namespace
  `external-secrets` with `cluster-secret-store.yaml`, kube-prometheus-stack, and an OpenTelemetry Collector in
  `observability`.
- The app's secret filled in. Then the stores' addresses go from the output `chart_values` into values-prod.yaml,
  and `helm upgrade --install` runs with it in the namespace `payment-gateway-prod`.
- A Cluster Autoscaler or Karpenter, for the nodes to follow the gateways' autoscaler beyond the node group's three.
