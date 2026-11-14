output "cluster_name" { value = module.cluster.cluster_name }
output "vpc_id" { value = module.network.vpc_id }

output "access_host_instance_id" {
  description = "aws ssm start-session --target <this>, then run scripts/bootstrap.sh"
  value       = module.access_host.instance_id
}
