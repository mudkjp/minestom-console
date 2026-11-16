#!/usr/bin/env bash

set -euo pipefail

group="${UPDATE_VERSION_GROUP:?group input is required}"
artifact="${UPDATE_VERSION_ARTIFACT:?artifact input is required}"
version="${UPDATE_VERSION_VERSION:?version input is required}"
docs_token="${UPDATE_VERSION_DOCS_TOKEN:?docs_token input is required}"
docs_repository="${UPDATE_VERSION_DOCS_REPOSITORY:?docs_repository input is required}"
docs_branch="${UPDATE_VERSION_DOCS_BRANCH:?docs_branch input is required}"
docs_directory="${UPDATE_VERSION_DOCS_DIRECTORY:?docs_directory input is required}"
source_readme="${UPDATE_VERSION_SOURCE_README:?source_readme input is required}"

if [[ ! "$group" =~ ^[a-z0-9][a-z0-9.-]*$ ]]; then
    echo "Invalid Maven group: $group"
    exit 1
fi

if [[ ! "$artifact" =~ ^[a-z0-9][a-z0-9.-]*$ ]]; then
    echo "Invalid Maven artifact: $artifact"
    exit 1
fi

if [[ ! "$version" =~ ^[0-9]{4}\.[0-9]{2}\.[0-9]{2}-.+$ ]]; then
    echo "Invalid version: $version"
    exit 1
fi

update_file() {
    local file="$1"

    GROUP="$group" ARTIFACT="$artifact" VERSION="$version" perl -0pi -e '
        my $group = quotemeta($ENV{"GROUP"});
        my $artifact = quotemeta($ENV{"ARTIFACT"});
        my $version = $ENV{"VERSION"};
        s{($group:$artifact:)[^\s\x22\x27()]+}{${1}${version}}g;
        s{(<artifactId>$artifact</artifactId>\s*<version>)[^<]+(</version>)}{${1}${version}${2}}gs;
    ' "$file"
}

coordinate="$group:$artifact:"

if [[ ! -f "$source_readme" ]]; then
    echo "README does not exist: $source_readme"
    exit 1
fi

if ! grep -Fq "$coordinate" "$source_readme"; then
    echo "README has no dependency coordinate for $artifact: $source_readme"
    exit 1
fi

if ! grep -q '<version>' "$source_readme"; then
    echo "README has no Maven version element: $source_readme"
    exit 1
fi

update_file "$source_readme"

source_branch="${GITHUB_REF_NAME:-$(git branch --show-current)}"
if [[ -z "$source_branch" || "$source_branch" == "HEAD" ]]; then
    echo "Could not determine the source branch to update"
    exit 1
fi

if ! git diff --quiet -- "$source_readme"; then
    git config user.name "github-actions[bot]"
    git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
    git add -- "$source_readme"
    git commit -m "update readme version to $version"
    git push origin "HEAD:${source_branch}"
else
    echo "Project README already uses $version"
fi

docs_pathname="${docs_directory%/}/${artifact}.md"

docs_working_directory="$(mktemp -d)"
trap 'rm -rf "$docs_working_directory"' EXIT

authorization="$(printf 'x-access-token:%s' "$docs_token" | base64 --wrap=0)"
git -c "http.extraheader=AUTHORIZATION: basic ${authorization}" \
    clone --depth 1 --branch "$docs_branch" --single-branch \
    "https://github.com/${docs_repository}.git" "$docs_working_directory"

docs_file="$docs_working_directory/${docs_pathname}"
if [[ ! -f "$docs_file" ]]; then
    echo "Documentation page does not exist: ${docs_pathname}"
    exit 1
fi

if ! grep -Fq "$coordinate" "$docs_file"; then
    echo "Documentation page has no dependency coordinate for $artifact"
    exit 1
fi

if ! grep -q '<version>' "$docs_file"; then
    echo "Documentation page has no Maven version element for $artifact"
    exit 1
fi

update_file "$docs_file"

if git -C "$docs_working_directory" diff --quiet -- "$docs_pathname"; then
    echo "Documentation already uses $version"
    exit 0
fi

git -C "$docs_working_directory" config user.name "github-actions[bot]"
git -C "$docs_working_directory" config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git -C "$docs_working_directory" add -- "$docs_pathname"
git -C "$docs_working_directory" commit -m "update ${artifact} version to ${version}"
git -C "$docs_working_directory" -c "http.extraheader=AUTHORIZATION: basic ${authorization}" \
    push origin "$docs_branch"
