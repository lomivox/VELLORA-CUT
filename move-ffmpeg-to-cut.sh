#!/data/data/com.termux/files/usr/bin/bash
# Run from the repo root (~/VELLORA-CUT). Moves the FFmpeg/editor files into the "cut" flavor only,
# so YTIQ is built without FFmpeg. Uses git mv, so history is kept. Safe to run twice.
set -e
[ -d app/src/main/java/com/vellora/cut ] || { echo "Repo ke root se chalayein: cd ~/VELLORA-CUT"; exit 1; }
M=app/src/main/java/com/vellora/cut
C=app/src/cut/java/com/vellora/cut
mkdir -p "$C/autogen/render" "$C/autogen/ui"
for f in \
  autogen/render/AudioChunker.kt \
  autogen/render/VideoAudioExtractor.kt \
  autogen/render/VideoReshaper.kt \
  autogen/render/AudioProcessor.kt \
  autogen/render/RenderEngine.kt \
  autogen/ui/TimelineScreen.kt \
  autogen/ui/AutoGenHomeScreen.kt
do
  if [ -f "$M/$f" ]; then git mv "$M/$f" "$C/$f"; echo "moved   $f"
  elif [ -f "$C/$f" ]; then echo "already $f"
  else echo "NAHI MILI: $f"; exit 1; fi
done
echo "Done. Ab: git add -A && git commit && git push"
