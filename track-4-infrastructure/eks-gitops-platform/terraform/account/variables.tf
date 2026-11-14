variable "region" {
  type    = string
  default = "us-east-1"
}

variable "github_repo" {
  description = "owner/name of the repository whose workflows may assume the CI roles"
  type        = string
  default     = "JawadCEO/enterprise-distributed-platform"
}

variable "domain" {
  description = "Platform domain delegated to Route 53; empty means no public DNS (open question 7, ADR-013)"
  type        = string
  default     = ""
}

variable "budget_monthly_usd" {
  description = "Monthly budget (open question 2)"
  type        = number
  default     = 25
}

variable "budget_alert_usd" {
  description = "Early-warning threshold below the budget"
  type        = number
  default     = 12
}

variable "budget_email" {
  description = "Where budget alerts go"
  type        = string
}
