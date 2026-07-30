#!/usr/bin/env bats

load test_helper

setup() {
  cd "$(repo_root)"
}

@test "standalone conformance validator accepts the canonical fixture" {
  run "$(python_bin)" tools/validate_repository_format.py repository-format/v1/fixtures/valid/repository --json
  [ "$status" -eq 0 ]
  [[ "$output" == *'"valid": true'* ]]
}

@test "standalone conformance validator rejects every invalid fixture" {
  local fixture
  for fixture in repository-format/v1/fixtures/invalid/*/repository; do
    run "$(python_bin)" tools/validate_repository_format.py "$fixture" --json
    [ "$status" -eq 1 ]
    [[ "$output" == *'"valid": false'* ]]
  done
}

@test "supported dependencies and successful new revise publish flows remain green" {
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_supported_dependencies_pass_without_mutating_worktree
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_new_creates_current_profile_date_markdown_draft
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_revise_creates_complete_child_and_copies_referenced_assets
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_publish_confirms_commits_only_valid_new_files_and_pushes
}

@test "Python and IANA timezone behavior remains green" {
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_missing_iana_timezone_data_fails_without_mutating_worktree
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_invalid_profile_timezone_fails_without_tzdata_install_advice
}

@test "dependency failures and unsupported Bash versions are mutation free" {
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_missing_dependencies_fail_without_mutating_worktree
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_unsupported_bash_version_reports_supported_environment_without_mutation
}

@test "dirty worktrees are rejected without stashing" {
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_pull_rejects_dirty_worktree_without_stashing_or_overwriting
}

@test "invalid schemas and missing assets are rejected" {
  run_python_test tools.tests.test_repository_format_validator.RepositoryFormatValidatorTest.test_invalid_repository_fixtures_report_expected_codes
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_validate_reports_missing_asset_without_mutation
}

@test "remote advancement preserves the local commit and avoids force push" {
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_publish_push_rejection_preserves_local_commit_without_force
}

@test "declined publication leaves the index and history unchanged" {
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_publish_decline_does_not_stage_commit_or_push
}

@test "multiple commits in one date directory are allowed" {
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_publish_allows_multiple_ordinary_commits_in_the_same_daily_directory
}

@test "PAT values stay out of arguments, output, files, and history" {
  run_python_test tools.tests.test_review_sync_script.ReviewSyncPreflightTest.test_publish_never_leaks_pat_from_environment_to_output_files_or_history
}
