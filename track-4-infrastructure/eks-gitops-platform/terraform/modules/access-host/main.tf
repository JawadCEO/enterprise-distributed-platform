# A tiny instance inside the VPC, reachable only through AWS Systems Manager:
# no SSH key, no inbound rule, no public IP (FR-28, flow F2, flow F12).
# Used once per cluster for the ArgoCD bootstrap and afterwards for
# break-glass access. Cluster admin comes from an EKS access entry that is
# switched off by setting cluster_admin = false after bootstrap.

variable "name" { type = string }
variable "vpc_id" { type = string }
variable "subnet_id" { type = string }
variable "instance_type" {
  type    = string
  default = "t4g.nano"
}

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

data "aws_iam_policy_document" "ec2_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = "${var.name}-access-host"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "describe_cluster" {
  name = "describe-cluster"
  role = aws_iam_role.this.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["eks:DescribeCluster"], Resource = "*" }]
  })
}

resource "aws_iam_instance_profile" "this" {
  name = "${var.name}-access-host"
  role = aws_iam_role.this.name
}

# Outbound only: HTTPS to SSM, the EKS API, and tool downloads via the NAT.
resource "aws_security_group" "this" {
  name        = "${var.name}-access-host"
  description = "Access host: no inbound, HTTPS outbound"
  vpc_id      = var.vpc_id

  egress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "this" {
  ami                         = data.aws_ssm_parameter.al2023_arm64.value
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [aws_security_group.this.id]
  iam_instance_profile        = aws_iam_instance_profile.this.name
  associate_public_ip_address = false

  metadata_options {
    http_tokens = "required" # IMDSv2 only
  }

  root_block_device {
    encrypted   = true
    volume_size = 8
  }

  # kubectl, helm and git for the bootstrap script. Versions are pinned.
  user_data = <<-EOT
    #!/bin/bash
    set -euo pipefail
    dnf install -y git
    curl -fsSLo /usr/local/bin/kubectl https://dl.k8s.io/release/v1.31.4/bin/linux/arm64/kubectl
    chmod +x /usr/local/bin/kubectl
    curl -fsSL https://get.helm.sh/helm-v3.16.4-linux-arm64.tar.gz | tar -xz -C /tmp
    mv /tmp/linux-arm64/helm /usr/local/bin/helm
  EOT

  tags = { Name = "${var.name}-access-host" }
}

output "role_arn" { value = aws_iam_role.this.arn }
output "security_group_id" { value = aws_security_group.this.id }
output "instance_id" { value = aws_instance.this.id }
