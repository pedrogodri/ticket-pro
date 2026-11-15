terraform {
  required_version = ">= 1.16.0"

  # State lives in the `tf-state` volume (see infra/compose/compose.yaml): re-applying on every
  # `docker compose up` is a no-op, and after a MiniStack restart the refresh recreates what is gone.
  backend "local" {
    path = "/state/terraform.tfstate"
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}
