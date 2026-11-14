variable "region" {
  type    = string
  default = "us-east-1"
}

variable "kubernetes_version" {
  description = "Check it is in EKS standard support before each session (ADR-011)"
  type        = string
  default     = "1.34"
}

variable "domain" {
  description = "Platform zone created by terraform/account; empty runs without public DNS (ADR-013)"
  type        = string
  default     = ""
}

variable "access_host_admin" {
  description = "Give the access host cluster admin. true for bootstrap or break-glass, false otherwise"
  type        = bool
  default     = true
}

variable "tenants" {
  description = "Tenants that get a secrets role; must match gitops/tenants/<cluster>/"
  type        = list(string)
  default     = ["team-a"]
}
