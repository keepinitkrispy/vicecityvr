#!/data/data/com.termux/files/usr/bin/bash
# One command: find the PSVR's USB device, start psvrd with head tracking (and VR mode), open the renderer.
#   ./start.sh          head tracking + VR mode
#   ./start.sh --fake   no headset: synthetic head motion (test the renderer anywhere)
set -uo pipefail
cd "$(dirname "$0")"
export RISH_APPLICATION_ID=com.termux
MODELS="${PSVR_MODELS:-$HOME/psvr-control/neural}"   # folder holding depth.onnx (optional)

if [ "${1:-}" = "--fake" ]; then
  exec python3 psvrd/psvrd.py --fake --models "$MODELS"
fi

command -v termux-usb >/dev/null || { echo "Install Termux:API (app + 'pkg install termux-api')"; exit 1; }

# PSVR processor unit = USB 054c:09af. Ask sysfs which /dev/bus/usb path that is (directly, or via Shizuku).
find_psvr() {
  local q='for d in /sys/bus/usb/devices/*; do [ "$(cat $d/idVendor 2>/dev/null)" = 054c ] && [ "$(cat $d/idProduct 2>/dev/null)" = 09af ] && printf "/dev/bus/usb/%03d/%03d\n" $(cat $d/busnum) $(cat $d/devnum); done'
  local p; p=$(sh -c "$q" 2>/dev/null | head -1)
  [ -z "$p" ] && p=$(timeout 8 rish -c "$q" 2>/dev/null | head -1)
  echo "$p"
}
DEV=$(find_psvr)
if [ -z "$DEV" ]; then
  echo "Could not identify the PSVR automatically. USB devices Termux can see:"
  termux-usb -l
  read -rp "Type the PSVR's path (e.g. /dev/bus/usb/001/005): " DEV
fi
echo "PSVR at $DEV - approve the USB prompt on the phone screen if it appears."
( sleep 2; termux-open-url "http://127.0.0.1:8765/" 2>/dev/null ) &
exec termux-usb -r -e "python3 $(pwd)/psvrd/psvrd.py --vr --models $MODELS" "$DEV"
