#!/bin/zsh
# siliconshaders shader benchmark for a Mac without the repository (no Python needed): double-click it (or run it in Terminal).
# Lies in a folder next to siliconshaders-*.jar (tools/benchmark-package makes that folder). It
#  1. finds the Minecraft folder whose mods folder has siliconshaders (official launcher, Prism, Modrinth, or a path as argument),
#  2. puts this jar in place of the old siliconshaders jar (the old one goes to mods-siliconshaders-backup next to mods, nothing is deleted),
#  3. writes config/siliconshaders-benchmark.json (the same request as tools/benchmark --fullscreen 1920x1200),
#  4. opens the launcher, waits for the result and copies .txt and .json to the desktop.
# Default profile: pfade, at the display's current resolution, about 15 minutes.
# Usage: "Benchmark starten.command" [MINECRAFT-FOLDER] [--profile kurz|standard|voll|diagnose|pfade] [--mode WxH|desktop] [--window]
set -u
here=${0:A:h}
profile=pfade
window=fullscreen
display_mode=
game=
while (( $# )); do
  case $1 in
    --profile) profile=$2; shift ;;
    --mode) display_mode=$2; shift ;;
    --window) window=fixed ;;
    *) game=${1:A} ;;
  esac
  shift
done

say() { print -r -- "$*" }
has_siliconshaders() { local m=("$1"/mods/(siliconshaders|SiliconShaders|mcopt|metal)-*.jar(N)); (( ${#m} )) }
has_sodium() { local m=("$1"/mods/sodium*.jar(N)); (( ${#m} )) }
fail() { say ""; say "FEHLER: $*"; say ""; read -k1 "?Taste drücken zum Schließen."; exit 1 }

jars=("$here"/(siliconshaders|SiliconShaders)-*.jar(N))
(( ${#jars} == 1 )) || fail "Neben diesem Skript muss genau eine siliconshaders-*.jar liegen (gefunden: ${#jars})."
jar=$jars[1]
say "siliconshaders Shader-Benchmark ($jar:t)"
say ""

# 1. The Minecraft folder: the one whose mods folder has an siliconshaders jar.
if [[ -z $game ]]; then
  support="$HOME/Library/Application Support"
  candidates=("$support/minecraft" "$support"/PrismLauncher/instances/*/(minecraft|.minecraft)(N/) \
              "$support"/ModrinthApp/profiles/*(N/) "$support"/com.modrinth.theseus/profiles/*(N/) \
              "$HOME"/Library/Application\ Support/CurseForge/Instances/*(N/))
  found=()
  for c in $candidates; do
    has_siliconshaders "$c" && found+=("$c")
  done
  (( ${#found} )) || fail "Kein Minecraft-Ordner mit siliconshaders gefunden. Ordner als Argument angeben, z. B.
  \"$0\" \"$support/minecraft\""
  if (( ${#found} == 1 )); then
    game=$found[1]
  else
    say "Mehrere Minecraft-Ordner mit siliconshaders:"
    PS3="Nummer wählen: "
    select g in $found; do [[ -n $g ]] && { game=$g; break }; done
  fi
fi
[[ -d "$game/mods" ]] || fail "Kein mods-Ordner in $game"
say "Minecraft-Ordner: $game"
has_sodium "$game" && fail "Sodium liegt im mods-Ordner; siliconshaders läuft nicht zusammen mit Sodium."

# 2. This jar in place of the old one.
if ! cmp -s "$jar" "$game/mods/$jar:t"; then
  backup="$game/mods-siliconshaders-backup"
  old=("$game"/mods/(siliconshaders|SiliconShaders|mcopt|metal)-*.jar(N))
  if (( ${#old} )); then
    mkdir -p "$backup" || fail "Kann $backup nicht anlegen."
    for o in $old; do mv -f "$o" "$backup/" || fail "Kann $o nicht verschieben."; done
    say "Alte siliconshaders-Version verschoben nach: $backup"
  fi
  cp "$jar" "$game/mods/" || fail "Kann den Jar nicht in den mods-Ordner kopieren."
  say "Neue siliconshaders-Version installiert: $jar:t"
fi

# 3. The request.
power=$(pmset -g batt | head -1)
case $power in
  *"AC Power"*) power=Netzteil ;;
  *"Battery Power"*) power=Akku; say ""; say "Hinweis: der Mac läuft auf Akku. Für vergleichbare Werte das Netzteil anschließen." ;;
  *) power= ;;
esac
model=$(sysctl -n hw.model 2>/dev/null)
label=${${model:-mac}//[^A-Za-z0-9._-]/-}
mode=
[[ -n $display_mode ]] || { [[ $profile == pfade ]] && display_mode=desktop || display_mode=1920x1200 }
[[ $display_mode == desktop || $display_mode =~ '^[0-9]{3,5}x[0-9]{3,5}$' ]] || fail "--mode braucht BxH, zum Beispiel 2560x1600, oder desktop."
[[ $window == fullscreen ]] && mode=",\"mode\":\"$display_mode\""
mkdir -p "$game/config" "$game/siliconshaders-benchmarks" || fail "Kann $game/config nicht anlegen."
print -r -- "{\"profile\":\"$profile\",\"label\":\"$label\",\"quit\":true,\"power\":\"$power\",\"window\":\"$window\"$mode}" \
  > "$game/config/siliconshaders-benchmark.json" || fail "Kann die Benchmark-Anfrage nicht schreiben."
before=("$game"/siliconshaders-benchmarks/*.json(N:t))

# 4. Launcher, then wait.
say ""
case $game in
  */PrismLauncher/*) apps=("Prism Launcher" "PrismLauncher") ;;
  */ModrinthApp/*|*/com.modrinth.theseus/*) apps=("Modrinth App") ;;
  */CurseForge/*) apps=("CurseForge") ;;
  *) apps=("Minecraft" "Minecraft Launcher") ;;
esac
opened=
for app in $apps; do
  if open -a "$app" 2>/dev/null; then say "$app wird geöffnet."; opened=1; break; fi
done
[[ -n $opened ]] || say "Launcher nicht gefunden: bitte selbst öffnen."
say ""
say ">>> Jetzt im Launcher Minecraft mit siliconshaders starten (Spielen)."
case $profile in
  pfade) minutes="etwa 15 Minuten" ;;
  diagnose) minutes="etwa 20 Minuten" ;;
  voll) minutes="etwa 25 Minuten" ;;
  kurz) minutes="etwa 3 Minuten" ;;
  *) minutes="etwa 8 Minuten" ;;
esac
say ">>> Der Benchmark beginnt von selbst im Titelbildschirm, dauert $minutes"
say ">>> und schließt Minecraft danach. Bitte nichts drücken, Esc bricht ab."
say ""
say "Warte auf das Ergebnis …"
start=$SECONDS
while true; do
  sleep 5
  result=
  for f in "$game"/siliconshaders-benchmarks/*.json(N); do
    (( ${before[(Ie)$f:t]} )) || result=$f
  done
  if [[ -n $result ]]; then
    txt=${result:r}.txt
    for i in {1..20}; do [[ -f $txt ]] && break; sleep 0.5; done
    say ""
    [[ -f $txt ]] && cat "$txt"
    cp "$result" "$HOME/Desktop/" 2>/dev/null
    [[ -f $txt ]] && cp "$txt" "$HOME/Desktop/" 2>/dev/null
    logfile=${result:r}.log
    [[ -f $logfile ]] && cp "$logfile" "$HOME/Desktop/" 2>/dev/null
    html=${result:r}.html
    [[ -f $html ]] && cp "$html" "$HOME/Desktop/" 2>/dev/null
    say ""
    say "Fertig. Ergebnis liegt auf dem Schreibtisch: $result:t"
    say "Schick die .json-Datei per AirDrop an den anderen Mac."
    if [[ -f "$HOME/Desktop/${logfile:t}" ]]; then open -R "$HOME/Desktop/${logfile:t}" 2>/dev/null; else open -R "$HOME/Desktop/$result:t" 2>/dev/null; fi
    say ""
    read -k1 "?Taste drücken zum Schließen."
    exit 0
  fi
  if (( SECONDS - start > 90*60 )); then
    fail "Nach 90 Minuten kein Ergebnis. Läuft Minecraft mit siliconshaders? Siehe $game/logs/latest.log"
  fi
done
