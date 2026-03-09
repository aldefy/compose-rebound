#!/bin/bash
# Build the rebound-relay binary from Swift source
set -e
cd "$(dirname "$0")/.."
swiftc -O -o tools/rebound-relay tools/rebound-relay.swift
echo "Built tools/rebound-relay"
