# The prod environment, started once to prove promotion (FR-9): network, cluster, workload identity, access host.
# Same modules as dev; only these files differ (NFR-20). Destroyed after use.
# Account-level resources (state, CI roles, budget, DNS zone) live in
# terraform/account and survive teardown.
#
#   terraform init -backend-config="bucket=eks-gitops-tfstate-<account-id>"
#   terraform apply

terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.80" }
  }
  backend "s3" {
    key          = "envs/prod/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true # S3 native locking (ADR-010)
  }
}

locals {
  environment = "prod"
  name        = "eks-gitops-${local.environment}"
}

provider "aws" {
  region = var.region
  # Cost tags on everything Terraform creates (FR-24). Controllers add the
  # same tags to what they create (see gitops/platform/*/values.yaml).
  default_tags {
    tags = { project = "eks-gitops-platform", owner = "platform-team", environment = local.environment }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_route53_zone" "platform" {
  count = var.domain == "" ? 0 : 1
  name  = var.domain
}

module "network" {
  source = "../../modules/network"
  name   = local.name
  azs    = slice(data.aws_availability_zones.available.names, 0, 2)
}

module "access_host" {
  source    = "../../modules/access-host"
  name      = local.name
  vpc_id    = module.network.vpc_id
  subnet_id = module.network.private_subnet_ids[0]
}

module "cluster" {
  source             = "../../modules/cluster"
  name               = local.name
  kubernetes_version = var.kubernetes_version
  vpc_id             = module.network.vpc_id
  private_subnet_ids = module.network.private_subnet_ids

  api_client_security_group_ids = [module.access_host.security_group_id]
  # Cluster admin for the access host during bootstrap and break-glass only.
  admin_role_arns = var.access_host_admin ? { access-host = module.access_host.role_arn } : {}
}

module "workload_identity" {
  source       = "../../modules/workload-identity"
  name         = local.name
  environment  = local.environment
  cluster_name = module.cluster.cluster_name
  zone_arn     = one(data.aws_route53_zone.platform[*].arn)
  tenants      = var.tenants
}
