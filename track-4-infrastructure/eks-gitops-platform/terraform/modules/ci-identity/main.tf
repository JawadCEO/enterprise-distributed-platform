# GitHub Actions reaches AWS only through short-lived OIDC tokens (FR-15).
# Three roles, each trusting one kind of workflow run:
#   plan  - pull requests                      read-only
#   apply - jobs in a GitHub environment       writes; the environment's
#           (aws-dev / aws-prod), which         required reviewers are the
#           only main-branch jobs can enter     approval gate
#   drift - scheduled runs on main             read-only

variable "github_repo" { type = string }
variable "state_bucket" {
  description = "ARN of the Terraform state bucket"
  type        = string
}
variable "name_prefix" {
  type    = string
  default = "eks-gitops"
}

data "aws_caller_identity" "current" {}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

locals {
  subjects = {
    plan  = ["repo:${var.github_repo}:pull_request"]
    apply = ["repo:${var.github_repo}:environment:aws-dev", "repo:${var.github_repo}:environment:aws-prod"]
    drift = ["repo:${var.github_repo}:ref:refs/heads/main"]
  }
}

data "aws_iam_policy_document" "trust" {
  for_each = local.subjects
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = each.value
    }
  }
}

resource "aws_iam_role" "ci" {
  for_each             = local.subjects
  name                 = "${var.name_prefix}-ci-${each.key}"
  assume_role_policy   = data.aws_iam_policy_document.trust[each.key].json
  max_session_duration = 3600
}

# Every role reads state and writes the lock file; only apply writes state.
data "aws_iam_policy_document" "state" {
  for_each = local.subjects
  statement {
    actions   = ["s3:ListBucket"]
    resources = [var.state_bucket]
  }
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${var.state_bucket}/*"]
  }
  statement {
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = each.key == "apply" ? ["${var.state_bucket}/*"] : ["${var.state_bucket}/*.tflock"]
  }
}

resource "aws_iam_role_policy" "state" {
  for_each = local.subjects
  name     = "terraform-state"
  role     = aws_iam_role.ci[each.key].id
  policy   = data.aws_iam_policy_document.state[each.key].json
}

resource "aws_iam_role_policy_attachment" "read_only" {
  for_each   = toset(["plan", "drift"])
  role       = aws_iam_role.ci[each.key].name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# Apply: everything except IAM, plus IAM limited to this platform's own
# roles and policies (name prefix), so CI cannot grant itself more (NFR-9).
resource "aws_iam_role_policy_attachment" "apply_power_user" {
  role       = aws_iam_role.ci["apply"].name
  policy_arn = "arn:aws:iam::aws:policy/PowerUserAccess"
}

data "aws_iam_policy_document" "apply_iam" {
  statement {
    actions = ["iam:*"]
    resources = [
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${var.name_prefix}-*",
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/${var.name_prefix}-*",
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:instance-profile/${var.name_prefix}-*",
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/oidc.eks.*",
    ]
  }
  statement {
    actions   = ["iam:Get*", "iam:List*", "iam:CreateServiceLinkedRole"]
    resources = ["*"]
  }
  # The apply role must never edit the CI roles themselves.
  statement {
    effect    = "Deny"
    actions   = ["iam:*"]
    resources = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${var.name_prefix}-ci-*"]
  }
}

resource "aws_iam_role_policy" "apply_iam" {
  name   = "platform-iam"
  role   = aws_iam_role.ci["apply"].id
  policy = data.aws_iam_policy_document.apply_iam.json
}

output "role_arns" {
  value = { for k, r in aws_iam_role.ci : k => r.arn }
}
