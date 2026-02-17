#!/bin/bash
#
# UDM License Key Generator
#
# Usage:
#   ./scripts/generate-license.sh john@example.com              # 1-year license
#   ./scripts/generate-license.sh john@example.com 2027-12-31   # Custom expiry
#   ./scripts/generate-license.sh --beta tester@example.com     # Beta (6 months)
#   ./scripts/generate-license.sh --student student@edu         # Student (1 year)
#   ./scripts/generate-license.sh --friend mom@family.com       # Friend (perpetual)
#   ./scripts/generate-license.sh --internal dev-team           # Internal dev
#

set -e

cd "$(dirname "$0")/.."

if [ -z "$1" ]; then
    echo "UDM License Key Generator"
    echo ""
    echo "Usage:"
    echo "  $0 <email> [expiry-date]     Standard 1-year license"
    echo "  $0 --beta <email>            Beta tester (6 months)"
    echo "  $0 --student <email>         Student (1 year)"
    echo "  $0 --trial <email>           Trial (30 days)"
    echo "  $0 --friend <email>          Friend/Family (perpetual)"
    echo "  $0 --contributor <email>     OSS contributor (2 years)"
    echo "  $0 --perpetual <email>       Lifetime license"
    echo "  $0 --internal <name>         Internal dev license"
    echo ""
    echo "Examples:"
    echo "  $0 customer@example.com"
    echo "  $0 customer@example.com 2027-12-31"
    echo "  $0 --beta tester@gmail.com"
    echo "  $0 --student alice@university.edu"
    echo "  $0 --friend mom@family.com"
    echo "  $0 --internal keystone-team"
    exit 1
fi

case "$1" in
    --beta)
        ./gradlew -q generateLicense --args="beta $2"
        ;;
    --student)
        ./gradlew -q generateLicense --args="student $2"
        ;;
    --trial)
        ./gradlew -q generateLicense --args="trial $2"
        ;;
    --friend)
        ./gradlew -q generateLicense --args="friend $2"
        ;;
    --contributor)
        ./gradlew -q generateLicense --args="contributor $2"
        ;;
    --perpetual)
        ./gradlew -q generateLicense --args="perpetual $2"
        ;;
    --internal)
        ./gradlew -q generateLicense --args="internal $2"
        ;;
    *)
        if [ -z "$2" ]; then
            ./gradlew -q generateLicense --args="generate $1"
        else
            ./gradlew -q generateLicense --args="generate $1 $2"
        fi
        ;;
esac
