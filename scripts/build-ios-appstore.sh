#!/usr/bin/env bash

# Signed counterpart to build-ios-ipa.sh. That script forces CODE_SIGNING_ALLOWED=NO and
# hand-zips a Payload directory, which is fine for an artifact you sideload but is rejected
# by App Store Connect.
#
# Signing assets are not baked into the repo or into CI secrets. Given an App Store Connect
# API key, xcodebuild -allowProvisioningUpdates fetches the team's managed distribution
# certificate and creates the provisioning profiles it needs, so there is no .p12 to rotate
# and no profile to regenerate whenever an identifier changes.

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
version_file="${repository_root}/iosApp/Configuration/Version.xcconfig"
version="${1:-$(sed -nE 's/^[[:space:]]*MARKETING_VERSION[[:space:]]*=[[:space:]]*([^[:space:]#]+).*$/\1/p' "${version_file}" | head -n 1)}"

: "${IOS_TEAM_ID:?IOS_TEAM_ID is required}"
: "${IOS_BUNDLE_ID:?IOS_BUNDLE_ID is required (must match the App Store Connect record)}"
: "${ASC_KEY_ID:?ASC_KEY_ID is required}"
: "${ASC_ISSUER_ID:?ASC_ISSUER_ID is required}"
: "${ASC_KEY_PATH:?ASC_KEY_PATH is required (path to the AuthKey_*.p8)}"

if [[ ! -f "${ASC_KEY_PATH}" ]]; then
    echo "App Store Connect key not found at ${ASC_KEY_PATH}." >&2
    exit 1
fi

derived_data="${IOS_DERIVED_DATA_PATH:-${repository_root}/build/ios-derived-appstore}"
output_directory="${IOS_IPA_OUTPUT_DIR:-${repository_root}/build/ios-appstore}"
archive_path="${derived_data}/Nuvio.xcarchive"

if [[ ! "${version}" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
    echo "Invalid version: ${version}" >&2
    exit 1
fi

cd "${repository_root}"
mkdir -p "${output_directory}"

authentication=(
    -allowProvisioningUpdates
    -authenticationKeyPath "${ASC_KEY_PATH}"
    -authenticationKeyID "${ASC_KEY_ID}"
    -authenticationKeyIssuerID "${ASC_ISSUER_ID}"
)

build_environment=(
    env
    # 'full' is the sideload flavour: it compiles src/iosFull, whose PluginCrypto calls
    # CCCryptorGCMEncrypt/Decrypt/Final -- private CommonCrypto symbols that App Store
    # Connect rejects with error 90338. 'appstore' selects src/iosAppStore instead.
    NUVIO_IOS_DISTRIBUTION=appstore
    CLANG_MODULE_CACHE_PATH="${derived_data}/ModuleCache.noindex"
    SWIFTPM_MODULECACHE_OVERRIDE="${derived_data}/SwiftPMModuleCache.noindex"
)
if [[ -n "${NUVIO_GRADLE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_org.gradle.jvmargs=${NUVIO_GRADLE_JVMARGS}")
fi
if [[ -n "${NUVIO_KOTLIN_NATIVE_JVMARGS:-}" ]]; then
    build_environment+=("ORG_GRADLE_PROJECT_kotlin.native.jvmArgs=${NUVIO_KOTLIN_NATIVE_JVMARGS}")
fi

"${build_environment[@]}" \
    xcodebuild \
    -project iosApp/iosApp.xcodeproj \
    -scheme iosApp \
    -configuration Release \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "${derived_data}" \
    -archivePath "${archive_path}" \
    "${authentication[@]}" \
    CODE_SIGN_STYLE=Automatic \
    DEVELOPMENT_TEAM="${IOS_TEAM_ID}" \
    archive

if [[ ! -d "${archive_path}" ]]; then
    echo "xcodebuild did not produce ${archive_path}." >&2
    exit 1
fi

app_plist="${archive_path}/Products/Applications/Nuvio.app/Info.plist"
built_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${app_plist}")"
if [[ "${built_version}" != "${version}" ]]; then
    echo "Archived version ${built_version} does not match ${version}." >&2
    exit 1
fi
built_bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${app_plist}")"
if [[ "${built_bundle_id}" != "${IOS_BUNDLE_ID}" ]]; then
    echo "Archived bundle id ${built_bundle_id} does not match ${IOS_BUNDLE_ID}." >&2
    echo "Config.xcconfig and project.pbxproj disagree; reconcile them before uploading." >&2
    exit 1
fi

export_options="${derived_data}/ExportOptions.plist"
cat > "${export_options}" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>app-store-connect</string>
    <key>teamID</key>
    <string>${IOS_TEAM_ID}</string>
    <key>signingStyle</key>
    <string>automatic</string>
    <key>uploadSymbols</key>
    <true/>
    <key>manageAppVersionAndBuildNumber</key>
    <false/>
</dict>
</plist>
PLIST

xcodebuild \
    -exportArchive \
    -archivePath "${archive_path}" \
    -exportOptionsPlist "${export_options}" \
    -exportPath "${output_directory}" \
    "${authentication[@]}"

ipa_path="$(find "${output_directory}" -maxdepth 1 -type f -name '*.ipa' -print -quit)"
if [[ -z "${ipa_path}" ]]; then
    echo "Export did not produce an IPA." >&2
    exit 1
fi

# Checking merely that _CodeSignature exists is not enough: the project pins
# CODE_SIGN_IDENTITY to "Apple Development" at target level, and a development-signed
# IPA archives, exports and zips without complaint. Apple only rejects it after upload,
# by which point the build number is spent and cannot be reused.
inspect_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ipa-verify.XXXXXX")"
trap 'rm -rf "${inspect_dir}"' EXIT
unzip -q "${ipa_path}" -d "${inspect_dir}"
app_dir="$(find "${inspect_dir}/Payload" -maxdepth 1 -name '*.app' -print -quit)"
if [[ -z "${app_dir}" ]]; then
    echo "Exported IPA has no .app in Payload." >&2
    exit 1
fi

authority="$(codesign -dvv "${app_dir}" 2>&1 | sed -nE 's/^Authority=(.*)$/\1/p' | head -n 1)"
case "${authority}" in
    "Apple Distribution"*) ;;
    *)
        echo "Exported IPA is signed by '${authority:-nothing}', not Apple Distribution." >&2
        echo "App Store Connect rejects anything else; refusing to hand it a bad build." >&2
        exit 1
        ;;
esac
echo "Signed by: ${authority}"

# App Store Connect rejects private CommonCrypto symbols with error 90338, and it does so
# during processing -- after the upload has "succeeded" and the build number is spent.
# These arrive by compiling the wrong distribution flavour, so check the binary itself
# rather than trusting NUVIO_IOS_DISTRIBUTION to have been set correctly.
app_binary="${app_dir}/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${app_dir}/Info.plist")"
private_symbols="$(nm -u "${app_binary}" 2>/dev/null | grep -E '_CCCryptorGCM' | sort -u || true)"
if [[ -n "${private_symbols}" ]]; then
    echo "Binary references private CommonCrypto symbols:" >&2
    printf '  %s\n' ${private_symbols} >&2
    echo "This is the 'full' flavour leaking in; App Store builds need NUVIO_IOS_DISTRIBUTION=appstore." >&2
    exit 1
fi

echo "Created ${ipa_path}"
