# EKS with a private-only API endpoint (FR-2, NFR-8), a small managed node
# group for system pods, and Karpenter's AWS side (ADR-006).
# Terraform never calls the Kubernetes API: everything inside the cluster
# is installed by ArgoCD.

variable "name" { type = string }
variable "kubernetes_version" {
  description = "Must be in EKS standard support; extended support bills $0.60/hour (ADR-011)"
  type        = string
}
variable "vpc_id" { type = string }
variable "private_subnet_ids" { type = list(string) }
variable "admin_role_arns" {
  description = "Roles given cluster admin through EKS access entries (access host, break-glass)"
  type        = map(string)
  default     = {}
}
variable "api_client_security_group_ids" {
  description = "Security groups allowed to reach the private API endpoint (the access host)"
  type        = list(string)
  default     = []
}
variable "node_instance_types" {
  type    = list(string)
  default = ["t3.medium"]
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.31"

  cluster_name    = var.name
  cluster_version = var.kubernetes_version

  cluster_endpoint_public_access  = false
  cluster_endpoint_private_access = true

  vpc_id     = var.vpc_id
  subnet_ids = var.private_subnet_ids

  # Only the access host may reach the private endpoint from outside the nodes.
  cluster_security_group_additional_rules = {
    for i, sg in var.api_client_security_group_ids : "access-host-${i}" => {
      description              = "Kubernetes API from the access host"
      type                     = "ingress"
      protocol                 = "tcp"
      from_port                = 443
      to_port                  = 443
      source_security_group_id = sg
    }
  }

  # Access entries instead of the aws-auth ConfigMap; no implicit admin for
  # whoever ran the apply.
  authentication_mode                      = "API"
  enable_cluster_creator_admin_permissions = false

  access_entries = {
    for k, arn in var.admin_role_arns : k => {
      principal_arn = arn
      policy_associations = {
        admin = {
          policy_arn   = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
          access_scope = { type = "cluster" }
        }
      }
    }
  }

  # AWS-side add-ons the nodes need at creation. Network policy enforcement
  # is off by default in the VPC CNI and is switched on here (FR-18).
  cluster_addons = {
    vpc-cni = {
      most_recent    = true
      before_compute = true
      configuration_values = jsonencode({
        enableNetworkPolicy = "true"
      })
    }
    coredns                = { most_recent = true }
    kube-proxy             = { most_recent = true }
    eks-pod-identity-agent = { most_recent = true, before_compute = true }
    aws-ebs-csi-driver     = { most_recent = true }
  }

  eks_managed_node_groups = {
    system = {
      instance_types = var.node_instance_types
      min_size       = 2
      max_size       = 3
      desired_size   = 2
      labels         = { "platform/pool" = "system" }
    }
  }

  node_security_group_tags = {
    "karpenter.sh/discovery" = var.name
  }
}

# Karpenter's controller role (via Pod Identity) and node role. The node role
# name is fixed so the EC2NodeClass in Git can refer to it.
module "karpenter" {
  source  = "terraform-aws-modules/eks/aws//modules/karpenter"
  version = "~> 20.31"

  cluster_name                    = module.eks.cluster_name
  enable_v1_permissions           = true
  enable_pod_identity             = true
  create_pod_identity_association = true
  namespace                       = "karpenter"
  service_account                 = "karpenter"

  node_iam_role_use_name_prefix = false
  node_iam_role_name            = "${var.name}-karpenter-node"

  # Spot interruption handling needs an SQS queue; left out to keep the
  # idle cost at zero. Karpenter still replaces interrupted nodes.
  enable_spot_termination = false
}

output "cluster_name" { value = module.eks.cluster_name }
output "cluster_endpoint" { value = module.eks.cluster_endpoint }
output "cluster_security_group_id" { value = module.eks.cluster_security_group_id }
