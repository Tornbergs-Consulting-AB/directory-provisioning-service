#!/usr/bin/env bash
# Copyright 2026 Tornbergs Consulting AB
# SPDX-License-Identifier: Apache-2.0
# Fresh installation only. Existing installations are never overwritten.
set -euo pipefail
set +x
umask 077
usage() {
  cat <<'TXT'
Usage: sudo bash install.sh --distribution /path/to/quarkus-app --config /path/to/application.properties --certs /path/to/certs --java /absolute/path/to/java [--start]
Fresh RHEL/systemd installation to /opt/directory-provisioning-service.
Prompts for keystore/truststore passwords without echo. Does not install Java,
change firewall/SELinux, or store AD credentials. Existing installation is refused.
TXT
}
distribution='' config='' certs='' java='' start=false
while (($#)); do
 case "$1" in
  --distribution|--config|--certs|--java)
   (($#>=2)) || { usage; exit 2; }
   case "$1" in
    --distribution) distribution=$2;; --config) config=$2;; --certs) certs=$2;; --java) java=$2;;
   esac
   shift 2;;
  --start) start=true;shift;;
  --help) usage;exit 0;;
  *) usage;exit 2;;
 esac
done
[[ $EUID == 0 ]] || { echo 'Run as root.' >&2; exit 1; }
app=/opt/directory-provisioning-service
secrets=/etc/directory-provisioning-service
unit=/etc/systemd/system/directory-provisioning.service
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
release_root=$(cd -- "$script_dir/../.." && pwd)
for legal_file in LICENSE NOTICE THIRD_PARTY_NOTICES.md; do
 [[ -f $release_root/$legal_file ]] || { echo 'Use the complete release layout with its licence materials.' >&2;exit 1; }
done
[[ -d $release_root/third-party ]] || { echo 'Third-party licence/source materials are missing.' >&2;exit 1; }
[[ -f $script_dir/directory-provisioning.service ]] || { echo 'Place installer alongside supplied systemd unit.' >&2;exit 1; }
[[ ! -e $app && ! -e $secrets && ! -e $unit ]] || { echo 'Existing installation detected; nothing changed. Use documented upgrade steps.' >&2;exit 1; }
[[ -f $distribution/quarkus-run.jar && -d $distribution/lib && -f $config && -d $certs && $java == /* && -x $java ]] || { usage;exit 2; }
[[ $java != *[[:space:]]* ]] || { echo 'Java executable path must not contain whitespace.' >&2;exit 2; }
[[ $java != /root/* && $java != /home/* ]] || { echo 'Use Java outside /root and /home (ProtectHome).' >&2;exit 2; }
for command in install systemctl systemd-analyze getent useradd python3; do command -v "$command" >/dev/null;done
"$java" -version
[[ -r /dev/tty ]] || { echo 'Interactive terminal required for passwords.' >&2;exit 1; }
IFS= read -r -s -p 'HTTPS keystore password: ' https_password </dev/tty;echo >/dev/tty
IFS= read -r -s -p 'LDAP truststore password: ' trust_password </dev/tty;echo >/dev/tty
[[ -n $https_password && -n $trust_password ]] || { echo 'Passwords must not be empty.' >&2;exit 1; }
# Prevent importing symlinks to files outside the supplied certificate tree.
if [[ -n $(find "$certs" -type l -print -quit) ]]; then echo 'Certificate directory must not contain symlinks.' >&2;exit 1;fi
if getent passwd dps >/dev/null; then
 [[ $(id -u dps) != 0 ]] || { echo 'dps must not be root.' >&2;exit 1; }
else
 useradd --system --user-group --home-dir "$app" --shell /sbin/nologin dps
fi
getent group dps >/dev/null || { echo 'Existing dps user requires group dps.' >&2;exit 1; }
install -d -m 0750 -o root -g dps "$app" "$app/config" "$app/config/certs"
install -d -m 0700 -o dps -g dps "$app/logs"
install -d -m 0700 -o root -g root "$secrets"
cp -R "$distribution" "$app/quarkus-app"
for legal_file in LICENSE NOTICE THIRD_PARTY_NOTICES.md; do
 install -m 0640 -o root -g dps "$release_root/$legal_file" "$app/$legal_file"
done
cp -R "$release_root/third-party" "$app/third-party"
chown -R root:dps "$app/third-party"
find "$app/third-party" -type d -exec chmod 0750 {} +
find "$app/third-party" -type f -exec chmod 0640 {} +
cp -R "$certs/." "$app/config/certs/"
install -m 0640 -o root -g dps "$config" "$app/config/application.properties"
chown -R root:dps "$app/quarkus-app" "$app/config"
find "$app/quarkus-app" "$app/config" -type d -exec chmod 0750 {} +
find "$app/quarkus-app" "$app/config" -type f -exec chmod 0640 {} +
# Quote EnvironmentFile values safely, including spaces, quotes and backslashes.
# stdin passes secrets without command-line arguments or exported variables.
printf '%s\n%s\n' "$https_password" "$trust_password" | python3 -c '
import sys
values=sys.stdin.read().splitlines()
if len(values)!=2: raise SystemExit("Passwords must be single-line values")
def quote(v): return "\""+v.replace("\\","\\\\").replace("\"","\\\"")+"\""
with open("/etc/directory-provisioning-service/secrets.env","w") as f:
 for key,value in zip(("DPS_HTTPS_KEYSTORE_PASSWORD","DPS_TRUSTSTORE_PASSWORD"),values): f.write(key+"="+quote(value)+"\n")
'
unset https_password trust_password
chmod 0600 "$secrets/secrets.env"
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ -f $script_dir/directory-provisioning.service ]] || { echo 'Place installer alongside supplied systemd unit.' >&2;exit 1; }
install -m 0644 "$script_dir/directory-provisioning.service" "$unit"
python3 - "$unit" "$java" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]);s=p.read_text();s=s.replace('ExecStart=/usr/bin/java ', 'ExecStart="'+sys.argv[2].replace('%','%%').replace('"','\\"')+'" ');p.write_text(s)
PY
systemd-analyze verify "$unit"
systemctl daemon-reload
systemctl enable directory-provisioning.service
if $start; then
 systemctl start directory-provisioning.service
 systemctl status directory-provisioning.service --no-pager
else
 echo 'Installed and enabled; not started. Review config/certificate paths, then run:'
 echo 'systemctl start directory-provisioning.service'
fi
echo 'Firewall and SELinux remain unchanged. Check logs and authenticated connection before use.'
