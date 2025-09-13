#!/usr/bin/env bash
set -e

# go to project root
cd "$(dirname "$(realpath "$0")")/.."

echo "Stopping Auton8 stack..."

docker compose down
