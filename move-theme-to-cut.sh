#!/data/data/com.termux/files/usr/bin/bash
# Repo ke root se chalayein (cd ~/VELLORA-CUT). VELLORA CUT ka purana theme "cut" hisse mein chala jata hai,
# YTIQ ka naya theme (zip mein) "ytiq" hisse mein hai. Do baar chalane se bhi masla nahi.
set -e
[ -d app/src/main/java/com/vellora/cut ] || { echo "Repo ke root se chalayein: cd ~/VELLORA-CUT"; exit 1; }
F=ui/theme/Theme.kt
M=app/src/main/java/com/vellora/cut/$F
C=app/src/cut/java/com/vellora/cut/$F
mkdir -p "$(dirname "$C")"
if [ -f "$M" ]; then git mv "$M" "$C"; echo "moved   $F"
elif [ -f "$C" ]; then echo "already $F"
else echo "NAHI MILI: $F"; exit 1; fi
echo "Done. Ab: git add -A && git commit && git push"
