# AWS access for add-ons and tenants through EKS Pod Identity (FR-14, ADR-004).
# Each role is bound to one service account and allowed only what that
# controller needs. Karpenter's role lives in the cluster module.

variable "name" { type = string }
variable "cluster_name" { type = string }
variable "zone_arn" {
  description = "Route 53 zone the DNS controllers may change; null when there is no domain (ADR-013)"
  type        = string
  default     = null
}
variable "tenants" {
  description = "Tenant names; each gets a role that can read only platform/<env>/<tenant>/*"
  type        = list(string)
  default     = []
}
variable "environment" { type = string }

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  # Service accounts named in gitops/platform/<add-on>/values.yaml.
  # The DNS controllers exist only when the platform has a domain.
  dns_add_ons = ["external-dns", "cert-manager"]
  add_ons = {
    external-dns = {
      namespace       = "external-dns"
      service_account = "external-dns"
      settings        = { attach_external_dns_policy = true, external_dns_hosted_zone_arns = [var.zone_arn] }
    }
    cert-manager = {
      namespace       = "cert-manager"
      service_account = "cert-manager"
      settings        = { attach_cert_manager_policy = true, cert_manager_hosted_zone_arns = [var.zone_arn] }
    }
    aws-load-balancer-controller = {
      namespace       = "kube-system"
      service_account = "aws-load-balancer-controller"
      settings        = { attach_aws_lb_controller_policy = true }
    }
    ebs-csi = {
      namespace       = "kube-system"
      service_account = "ebs-csi-controller-sa"
      settings        = { attach_aws_ebs_csi_policy = true }
    }
  }
}

module "add_on" {
  source   = "terraform-aws-modules/eks-pod-identity/aws"
  version  = "~> 1.7"
  for_each = { for k, v in local.add_ons : k => v if var.zone_arn != null || !contains(local.dns_add_ons, k) }

  name            = "${var.name}-${each.key}"
  use_name_prefix = false

  attach_external_dns_policy      = lookup(each.value.settings, "attach_external_dns_policy", false)
  external_dns_hosted_zone_arns   = lookup(each.value.settings, "external_dns_hosted_zone_arns", [])
  attach_cert_manager_policy      = lookup(each.value.settings, "attach_cert_manager_policy", false)
  cert_manager_hosted_zone_arns   = lookup(each.value.settings, "cert_manager_hosted_zone_arns", [])
  attach_aws_lb_controller_policy = lookup(each.value.settings, "attach_aws_lb_controller_policy", false)
  attach_aws_ebs_csi_policy       = lookup(each.value.settings, "attach_aws_ebs_csi_policy", false)

  associations = {
    this = {
      cluster_name    = var.cluster_name
      namespace       = each.value.namespace
      service_account = each.value.service_account
    }
  }
}

# ---------- Secrets (FR-16, ADR-009) ----------
# The External Secrets controller has no secret access of its own. It may
# only assume the per-tenant roles below, each limited to one path.

data "aws_iam_policy_document" "pod_identity_trust" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "external_secrets" {
  name               = "${var.name}-external-secrets"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_trust.json
}

resource "aws_iam_role_policy" "external_secrets_assume_tenants" {
  name = "assume-tenant-roles"
  role = aws_iam_role.external_secrets.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sts:AssumeRole", "sts:TagSession"]
      Resource = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${var.name}-tenant-*-secrets"
    }]
  })
}

resource "aws_eks_pod_identity_association" "external_secrets" {
  cluster_name    = var.cluster_name
  namespace       = "external-secrets"
  service_account = "external-secrets"
  role_arn        = aws_iam_role.external_secrets.arn
}

resource "aws_iam_role" "tenant_secrets" {
  for_each = toset(var.tenants)
  name     = "${var.name}-tenant-${each.key}-secrets"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = ["sts:AssumeRole", "sts:TagSession"]
      Principal = { AWS = aws_iam_role.external_secrets.arn }
    }]
  })
}

resource "aws_iam_role_policy" "tenant_secrets" {
  for_each = toset(var.tenants)
  name     = "read-own-secrets"
  role     = aws_iam_role.tenant_secrets[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
      Resource = "arn:aws:secretsmanager:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:secret:platform/${var.environment}/${each.key}/*"
    }]
  })
}
