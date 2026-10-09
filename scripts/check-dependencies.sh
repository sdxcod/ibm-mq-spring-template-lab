#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -B -ntp dependency:tree -Dscope=runtime '-Dincludes=ch.qos.logback:*,org.bouncycastle:*,org.springframework:*,com.ibm.mq:*'
printf 'Resolved versions shown above; this is not a CVE scanner.\n'
