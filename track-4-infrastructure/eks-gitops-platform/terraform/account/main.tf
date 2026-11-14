# Account-level resources that outlive every environment (NFR-24):
# the state bucket, the GitHub OIDC roles, the budget alarms, and the DNS zone.
# Applied once from a laptop with the owner's own short-lived AWS sign-in;
# its state stays local and is never committed (it is in .gitignore).
#
#   cd terraform/account
#   terraform init && terraform apply -var budget_email=you@example.com

terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.80" }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { project = "eks-gitops-platform", owner = "platform-team", environment = "shared" }
  }
}

data "aws_caller_identity" "current" {}

locals {
  account_id   = data.aws_caller_identity.current.account_id
  state_bucket = "eks-gitops-tfstate-${local.account_id}"
}

# ---------- Terraform state (FR-3, ADR-010) ----------

resource "aws_s3_bucket" "state" {
  bucket = local.state_bucket
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration { status = "Enabled" }
}

# SSE-S3, not a customer-managed key: same protection here, no $1/month (NFR-24).
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 90 days of state history (data.md retention).
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    id     = "expire-old-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration { noncurrent_days = 90 }
  }
}

resource "aws_s3_bucket_policy" "state_tls_only" {
  bucket = aws_s3_bucket.state.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

# ---------- Everything else ----------

module "ci_identity" {
  source       = "../modules/ci-identity"
  github_repo  = var.github_repo
  state_bucket = aws_s3_bucket.state.arn
}

module "budget" {
  source       = "../modules/budget"
  monthly_usd  = var.budget_monthly_usd
  alert_at_usd = var.budget_alert_usd
  email        = var.budget_email
}

# The zone survives teardown, so the registrar's NS delegation never changes.
# With no domain (open question 7 unanswered), no zone is created and the
# platform runs with internal hostnames and its own CA (ADR-013).
resource "aws_route53_zone" "platform" {
  count = var.domain == "" ? 0 : 1
  name  = var.domain
}
