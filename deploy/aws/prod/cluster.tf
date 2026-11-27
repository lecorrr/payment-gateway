module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "21.30.2"

  name               = local.name
  kubernetes_version = "1.37"

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  # The Kubernetes API only from inside the VPC: people through a VPN or a bastion there, the pipeline's deploy job
  # from a runner there. Whoever applies this gets admin access to the cluster.
  endpoint_public_access                   = false
  endpoint_private_access                  = true
  enable_cluster_creator_admin_permissions = true
  deletion_protection                      = true

  addons = {
    coredns    = {}
    kube-proxy = {}
    # Before the nodes, so that their first pods already get credentials (Pod Identity) and addresses (VPC CNI).
    eks-pod-identity-agent = {
      before_compute = true
    }
    vpc-cni = {
      before_compute = true
      # Enforces the chart's NetworkPolicies, which keep the gateway away from the ledger's database.
      configuration_values = jsonencode({ enableNetworkPolicy = "true" })
    }
  }

  eks_managed_node_groups = {
    apps = {
      # x86: the pipeline builds the images for amd64.
      ami_type       = "AL2023_x86_64_STANDARD"
      instance_types = ["m7i.xlarge"]
      # A node in each zone at least, as values-prod.yaml runs three replicas of each app.
      min_size     = 3
      desired_size = 3
      max_size     = 6
    }
  }
}
