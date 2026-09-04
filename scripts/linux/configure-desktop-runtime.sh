#!/usr/bin/env bash

set -euo pipefail

required_values=(
    LOCAL_PROPERTIES_BASE64
    SENTRY_AUTH_TOKEN
    SENTRY_DESKTOP_DSN
)
missing=()
for value_name in "${required_values[@]}"; do
    if [[ -z "${!value_name:-}" ]]; then
        missing+=("${value_name}")
    fi
done
if (( ${#missing[@]} > 0 )); then
    printf 'Missing required desktop release values: %s\n' "${missing[*]}" >&2
    exit 1
fi

printf '%s' "${LOCAL_PROPERTIES_BASE64}" | base64 --decode > local.properties
sed -i -E '/^[[:space:]]*(sdk\.dir|NUVIO_RELEASE_(STORE_FILE|STORE_PASSWORD|KEY_ALIAS|KEY_PASSWORD)|NUVIO_MACOS_(SIGNING_IDENTITY|NOTARY_APPLE_ID|NOTARY_TEAM_ID|NOTARY_PASSWORD|NOTARY_KEYCHAIN_PROFILE|NOTARY_KEYCHAIN_PATH))[[:space:]]*=/d' local.properties

required_properties=(
    NUVIO_SUPABASE_URL
    NUVIO_SUPABASE_ANON_KEY
    TRAKT_CLIENT_ID
    TRAKT_CLIENT_SECRET
)
for property_name in "${required_properties[@]}"; do
    if ! grep -Eq "^${property_name}=.+" local.properties; then
        echo "Missing required desktop property: ${property_name}" >&2
        exit 1
    fi
done
