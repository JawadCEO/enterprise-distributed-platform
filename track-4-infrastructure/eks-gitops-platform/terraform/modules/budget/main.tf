# Billing alarm that exists before the first cluster apply (FR-24, NFR-22).
# Alerts on actual spend at an early threshold and at the budget, and on
# forecast spend reaching the budget.

variable "monthly_usd" { type = number }
variable "alert_at_usd" { type = number }
variable "email" { type = string }

resource "aws_budgets_budget" "monthly" {
  name         = "eks-gitops-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    notification_type          = "ACTUAL"
    threshold_type             = "ABSOLUTE_VALUE"
    threshold                  = var.alert_at_usd
    subscriber_email_addresses = [var.email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    notification_type          = "ACTUAL"
    threshold_type             = "PERCENTAGE"
    threshold                  = 100
    subscriber_email_addresses = [var.email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    notification_type          = "FORECASTED"
    threshold_type             = "PERCENTAGE"
    threshold                  = 100
    subscriber_email_addresses = [var.email]
  }
}
