#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
gradle_options=("$@")

task_graph() {
    ./gradlew "${gradle_options[@]}" --dry-run --console=plain "$@" |
        awk '/^:[^ ]+ SKIPPED$/ { print $1 }'
}

require_task() {
    if ! grep -Fxq -- "$2" <<< "$1"; then
        printf 'Missing task: %s\n%s\n' "$2" "$1" >&2
        exit 1
    fi
}

# 同时检查普通构建、运行和测试的依赖并集，所有前端及发布检查都应位于这张图之外。
daily_graph=$(task_graph build :backend:assemble :backend:jar :backend:run :backend:test :backend:check)
for task in :backend:compileKotlin :backend:jar :backend:run :backend:test :backend:check :backend:build; do
    require_task "$daily_graph" "$task"
done
if grep -Eq '^(:webui:|:backend:(generateLicenseReport|createLicenses|processFrontendResources|shadowJar|startShadowScripts|shadowDistTar|shadowDistZip|verifyPackagedSerialization|verifyPackagedResources|releaseBuild)$)' <<< "$daily_graph"; then
    printf 'Daily backend tasks unexpectedly depend on frontend or release tasks:\n%s\n' "$daily_graph" >&2
    exit 1
fi
printf 'Daily backend task boundaries: PASS\n'

# 分别检查直接入口、缩写和间接调用，防止依赖再次随命令行名称改变。
for entry in shadowJar sJ verifyPackagedSerialization verifyPackagedResources releaseBuild runShadow shadowDistZip shadowDistTar installShadowDist; do
    release_graph=$(task_graph ":backend:$entry")
    for task in :webui:npmBuild :webui:generateLicenses :backend:generateLicenseReport :backend:createLicenses :backend:processFrontendResources :backend:shadowJar; do
        require_task "$release_graph" "$task"
    done
    if [[ "$entry" == releaseBuild ]]; then
        for task in :backend:test :backend:check :backend:verifyPackagedSerialization :backend:verifyPackagedResources; do
            require_task "$release_graph" "$task"
        done
    fi
    printf 'Release task %s: PASS\n' "$entry"
done
