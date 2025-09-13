#!/usr/bin/env bash
set -e

# go to project root
cd "$(dirname "$(realpath "$0")")/.."

echo "Starting Auton8 stack..."

# starting in background
docker compose up -d

echo "Auton8 services are now running."
echo "Mosquitto on 127.0.0.1:1883"
echo "n8n editor on http://localhost:5678"
echo "Read live logs with: docker compose logs -f"
