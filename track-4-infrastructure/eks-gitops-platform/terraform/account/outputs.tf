output "state_bucket" {
  value = aws_s3_bucket.state.bucket
}

output "ci_role_arns" {
  description = "Set these as GitHub repository variables (not secrets: role ARNs are not credentials)"
  value       = module.ci_identity.role_arns
}

output "zone_id" {
  value = one(aws_route53_zone.platform[*].zone_id)
}

output "zone_name_servers" {
  description = "Add these as NS records for the platform domain at its registrar"
  value       = one(aws_route53_zone.platform[*].name_servers)
}
