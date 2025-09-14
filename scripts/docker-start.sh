#!/usr/bin/env bash
set -e

# go to project root
cd "$(dirname "$(realpath "$0")")/.."

echo "Starting Auton8 stack..."

docker compose up
